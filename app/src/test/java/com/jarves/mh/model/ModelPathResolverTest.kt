package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Path resolution and free-space policy, exercised on the JVM.
 *
 * No device and no `Context`: [StorageRoots] supplies temporary directories, which is
 * the reason the resolver takes its roots as an injected interface in the first place.
 */
class ModelPathResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Folders created once per test and reused.
     *
     * `TemporaryFolder.newFolder(name)` fails if that name already exists, so a test
     * that called [resolver] twice - as `files dir follows the same preference` does,
     * once per branch - died on "a folder with the path 'internal' already exists"
     * before asserting anything. Created lazily because `TemporaryFolder` does not
     * create its root until `before()` runs, which is after field initialisers.
     */
    private val made = mutableMapOf<String, File>()
    private fun dir(key: String): File = made.getOrPut(key) { temp.newFolder() }

    private fun roots(external: File? = dir("external"), secondary: List<File> = emptyList()) =
        object : StorageRoots {
            override val externalFilesDir: File? = external
            override val internalFilesDir: File = dir("internal")
            override val cacheDir: File = dir("cache")
            override val secondaryExternalFilesDirs: List<File> = secondary
        }

    private fun resolver(
        external: File? = dir("external"),
        secondary: List<File> = emptyList(),
        freeSpaceOf: (File) -> Long = { it.usableSpace },
    ) = ModelPathResolver(roots(external, secondary), freeSpaceOf)

    /** A resolver whose primary and secondary volumes report fixed free space. */
    private fun resolverWithSpace(primaryFree: Long, secondaryFree: Long, secondary: List<File>) =
        resolver(secondary = secondary, freeSpaceOf = { candidate ->
            if (secondary.contains(candidate)) secondaryFree else primaryFree
        })

    // --- root selection -----------------------------------------------------

    @Test
    fun `external storage is preferred for weights`() {
        assertTrue(resolver().modelsDir.path.contains(dir("external").name))
    }

    @Test
    fun `weights fall back to internal storage when external is unavailable`() {
        // Emulated storage can be removed, and some devices ship with no usable
        // external volume at all. Falling back keeps the app working rather than
        // failing every download.
        assertTrue(resolver(external = null).modelsDir.path.contains(dir("internal").name))
    }

    @Test
    fun `files dir follows the same preference`() {
        // Both branches are exercised in one test, which is why the folders are
        // created once and reused rather than per resolver.
        assertTrue(resolver().filesDir.path.contains(dir("external").name))
        assertTrue(resolver(external = null).filesDir.path.contains(dir("internal").name))
    }

    // --- layout -------------------------------------------------------------

    @Test
    fun `a model is one directory holding weights and configs together`() {
        val r = resolver()
        assertEquals(r.modelsDir.resolve("minicpm"), r.modelDir("minicpm"))
        assertEquals(r.modelDir("minicpm"), r.weightFile("minicpm", "Q4_K_M").parentFile)
    }

    @Test
    fun `weight files carry the quantisation in their name`() {
        assertEquals("minicpm_Q4_K_M.gguf", resolver().weightFile("minicpm", "Q4_K_M").name)
    }

    @Test
    fun `the projector sits beside the weights so a model moves as a unit`() {
        val r = resolver()
        assertEquals(r.modelDir("minicpm"), r.mmProjFile("minicpm").parentFile)
    }

    @Test
    fun `draft heads live under the model they accelerate`() {
        val r = resolver()
        assertEquals(r.modelDir("minicpm"), r.draftDir("minicpm").parentFile)
    }

    @Test
    fun `manifests are named per model`() {
        assertEquals("minicpm.json", resolver().manifestFile("minicpm").name)
    }

    @Test
    fun `staging and quarantine sit under cache, not files`() {
        // Both are disposable or diagnostic. Leaving them in files/ would make them
        // survive a cache clear the app relies on.
        val r = resolver()
        assertTrue(r.downloadStagingDir.path.contains(dir("cache").name))
        assertTrue(r.quarantineDir.path.contains(dir("cache").name))
        assertFalse(r.downloadStagingDir.path.contains(dir("external").name))
    }

    @Test
    fun `speech and wake-word assets are separate classes`() {
        val r = resolver()
        assertTrue(r.sttDir().path.endsWith("asr"))
        assertTrue(r.wakeWordDir().path.endsWith("wake"))
        assertTrue(r.ttsDir().path.endsWith("tts"))
    }

    // --- free space ---------------------------------------------------------

    @Test
    fun `free space is measured on a path that does not exist yet`() {
        // StatFs throws on a missing path, and the models directory is always missing
        // on a fresh install - exactly when the check matters most.
        val r = resolver()
        val missing = r.modelsDir.resolve("not-created-yet/deeper")
        assertFalse(missing.exists())
        assertTrue("should walk up to an existing ancestor", r.freeSpace(missing) > 0)
    }

    @Test
    fun `free space is zero when there is no ancestor to measure`() {
        // A bare relative name has no parent to walk up to. An absolute path can
        // never hit this on Windows, because the walk terminates at a drive root
        // that exists - so the relative case is the one worth pinning.
        assertEquals(0L, resolver().freeSpace(File("no-parent-relative-name")))
    }

    @Test
    fun `a deep missing path resolves against the volume root`() {
        assertTrue(resolver().freeSpace(resolver().modelsDir.resolve("a/b/c/d/e")) > 0)
    }

    @Test
    fun `headroom is one and a quarter times the file`() {
        val r = resolver()
        assertEquals(125L, r.storageHeadroom(100))
        assertEquals(1_250L, r.storageHeadroom(1_000))
    }

    @Test
    fun `headroom of an empty or negative file is zero`() {
        val r = resolver()
        assertEquals(0L, r.storageHeadroom(0))
        assertEquals(0L, r.storageHeadroom(-5))
    }

    @Test
    fun `a file that fits is allowed`() {
        val r = resolver()
        assertTrue(r.hasRoomFor(r.modelsDir, 1_000_000_000))
    }

    @Test
    fun `a file larger than the volume is refused`() {
        // Long.MAX_VALUE is larger than any real volume by many orders of magnitude.
        val r = resolver()
        assertFalse(r.hasRoomFor(r.modelsDir, Long.MAX_VALUE / 2))
    }

    // --- destination choice -------------------------------------------------

    @Test
    fun `a model that fits lands on the primary volume`() {
        val r = resolverWithSpace(primaryFree = 100_000_000L, secondaryFree = 100_000_000L, secondary = emptyList())
        assertEquals(r.modelsDir, r.destinationFor("minicpm", 1_000_000L))
    }

    @Test
    fun `a secondary volume is offered only when the primary cannot take the file`() {
        // The probe is injected because two real temp directories share one physical
        // volume: their genuine free space is always nearly identical, so a test could
        // otherwise never create the only case this method exists for.
        val sd = dir("sd")
        val r = resolverWithSpace(primaryFree = 1_000L, secondaryFree = 100_000_000L, secondary = listOf(sd))

        assertEquals("should fall back to the SD volume", sd, r.destinationFor("minicpm", 1_000_000L))
    }

    @Test
    fun `no destination is offered when nothing fits`() {
        // The PRD queues this as WAITING_STORAGE rather than failing the request.
        val sd = dir("sd")
        val r = resolverWithSpace(primaryFree = 1_000L, secondaryFree = 2_000L, secondary = listOf(sd))

        assertNull(r.destinationFor("minicpm", 1_000_000L))
    }

    @Test
    fun `a smaller file stays on the primary volume even when an SD card exists`() {
        // "Move to SD card" is a suggestion for when the primary is full, not the
        // default destination - otherwise models scatter unpredictably.
        val sd = dir("sd")
        val r = resolverWithSpace(primaryFree = 100_000_000L, secondaryFree = 500_000_000L, secondary = listOf(sd))

        assertEquals(r.modelsDir, r.destinationFor("minicpm", 1_000_000L))
    }

    @Test
    fun `the first secondary volume with room wins`() {
        val first = dir("sd1")
        val second = dir("sd2")
        val r = resolverWithSpace(primaryFree = 1_000L, secondaryFree = 100_000_000L, secondary = listOf(first, second))

        assertEquals(first, r.destinationFor("minicpm", 1_000_000L))
    }

    @Test
    fun `the media scanner marker has the exact name Android looks for`() {
        assertEquals(".nomedia", ModelPathResolver.NO_MEDIA)
    }
}
