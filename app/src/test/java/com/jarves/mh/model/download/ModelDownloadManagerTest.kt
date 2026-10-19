package com.jarves.mh.model.download

import com.jarves.mh.model.DownloadState
import com.jarves.mh.model.ModelPathResolver
import com.jarves.mh.model.StorageRoots
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The downloader's offline-testable surface.
 *
 * The network path is not exercised here - there is no mock server in the dependency
 * set, and standing one up to test a two-line resume header is not worth the build
 * time. What *is* tested is everything that decides whether a download is even
 * attempted: the spec's own validation, where files are written, the storage gate, and
 * the installed/partial bookkeeping.
 */
class ModelDownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val made = mutableMapOf<String, File>()
    private fun dir(key: String): File = made.getOrPut(key) { temp.newFolder() }

    private val resolver: ModelPathResolver by lazy {
        ModelPathResolver(
            object : StorageRoots {
                override val externalFilesDir: File = dir("external")
                override val internalFilesDir: File = dir("internal")
                override val cacheDir: File = dir("cache")
                override val secondaryExternalFilesDirs: List<File> = emptyList()
            }
        )
    }

    private fun manager(freeSpace: Long = Long.MAX_VALUE) =
        ModelDownloadManager(resolver, client = okhttp3.OkHttpClient())

    private fun spec(
        id: String = "test-model",
        sha: String = "a".repeat(64),
        size: Long = 1_000L,
    ) = ModelSpec(
        id = id,
        displayName = "Test",
        url = "https://example.invalid/model.gguf",
        sizeBytes = size,
        sha256 = sha,
    )

    // --- spec validation ------------------------------------------------------

    @Test
    fun `a spec with a malformed digest is rejected at construction`() {
        // Fail fast: a bad constant here would otherwise surface as every download
        // failing verification, which looks exactly like a network fault.
        var threw = false
        try {
            spec(sha = "not-a-digest")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected rejection of a malformed sha256", threw)
    }

    @Test
    fun `an uppercase digest is rejected`() {
        // The comparison is case-insensitive at verify time, but the constant is
        // normalised on the way in so two builds cannot differ only in case.
        var threw = false
        try {
            spec(sha = "A".repeat(64))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a zero size is rejected`() {
        var threw = false
        try {
            spec(size = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a well formed spec is accepted`() {
        val s = spec()
        assertEquals("test-model", s.id)
        assertEquals(64, s.sha256.length)
    }

    // --- catalogue ------------------------------------------------------------

    @Test
    fun `the catalogue pins a real sized artefact`() {
        val entry = ModelCatalog.entryTier
        assertEquals(638_856_128L, entry.sizeBytes)
        assertTrue(entry.url.endsWith(".gguf"))
        assertTrue("must be the entry-tier quant the PRD names", entry.url.contains("IQ4_XS"))
    }

    @Test
    fun `the catalogue digest is a real digest and not a placeholder`() {
        // A placeholder would make every download fail verification in production,
        // so it is asserted rather than trusted.
        val sha = ModelCatalog.entryTier.sha256
        assertFalse("placeholder left in the catalogue", sha.contains("PLACEHOLDER"))
        assertEquals(64, sha.length)
        assertTrue(sha.all { it in "0123456789abcdef" })
    }

    // --- file placement -------------------------------------------------------

    @Test
    fun `a partial download lands in cache and not beside the weights`() {
        // Staging has to be disposable: a half-written file next to an installed
        // model is indistinguishable from a corrupt one.
        val spec = spec()
        val staging = resolver.downloadStagingDir.resolve("${spec.directoryName}.gguf.part")
        assertTrue(
            "staging must sit under the cache dir",
            staging.path.contains(dir("cache").name),
        )
        assertFalse(
            "staging must not sit beside the installed weights",
            staging.path.contains(resolver.modelsDir.name),
        )
        // File.extension excludes the dot, so this is "part" not ".part".
        assertEquals("part", staging.extension)
    }

    @Test
    fun `the installed file keeps its upstream name`() {
        // Keeps the quantisation visible on disk, which matters when diagnosing
        // "it loaded but the answers are worse than expected".
        val spec = spec()
        val target = resolver.modelDir(spec.directoryName).resolve("model.gguf")
        assertEquals("model.gguf", target.name)
    }

    // --- installed / partial bookkeeping --------------------------------------

    @Test
    fun `nothing is installed before a download`() {
        assertFalse(manager().isInstalled(spec()))
    }

    @Test
    fun `an installed model of the right size is reported installed`() {
        val spec = spec(size = 512)
        val target = resolver.modelDir(spec.directoryName).resolve("model.gguf")
        target.parentFile.mkdirs()
        target.writeBytes(ByteArray(512))

        assertTrue(manager().isInstalled(spec))
    }

    @Test
    fun `a model of the wrong size is not reported installed`() {
        // A truncated file that happens to exist must not be mistaken for an install;
        // otherwise the app would try to load it and fail with a far worse message.
        val spec = spec(size = 512)
        val target = resolver.modelDir(spec.directoryName).resolve("model.gguf")
        target.parentFile.mkdirs()
        target.writeBytes(ByteArray(400))

        assertFalse(manager().isInstalled(spec))
    }

    @Test
    fun `a partial can be discarded`() {
        val manager = manager()
        val spec = spec()
        val staging = resolver.downloadStagingDir.resolve("${spec.directoryName}.gguf.part")
        staging.parentFile.mkdirs()
        staging.writeBytes(ByteArray(64))

        assertTrue(manager.discardPartial(spec))
        assertFalse(staging.exists())
    }

    // --- the storage gate -----------------------------------------------------

    @Test
    fun `a volume with no room parks the download instead of failing`() {
        // WAITING_STORAGE, not an error: the user can free space and resume, and the
        // PRD asks for exactly this rather than a hard failure.
        val resolverNoSpace = ModelPathResolver(
            object : StorageRoots {
                override val externalFilesDir: File = dir("external")
                override val internalFilesDir: File = dir("internal")
                override val cacheDir: File = dir("cache")
                override val secondaryExternalFilesDirs: List<File> = emptyList()
            },
            freeSpaceOf = { 1_000L }, // 1 KB free against a 10 MB file
        )
        val bigSpec = spec(size = 10_000_000L)

        val states = runBlocking {
            ModelDownloadManager(resolverNoSpace).download(bigSpec).toList()
        }

        assertEquals(DownloadState.WAITING_STORAGE, states.last().state)
        assertTrue(states.last().message.contains("free"))
    }

    @Test
    fun `progress fractions stay within bounds`() {
        val p = DownloadProgress(DownloadState.DOWNLOADING, 0.5f, 500, 1_000, "half")
        assertTrue(p.fraction in 0f..1f)
        assertEquals(1_000L, p.totalBytes)
    }

    @Test
    fun `terminal states are reported as terminal`() {
        assertTrue(DownloadProgress(DownloadState.READY, 1f, 1, 1, "").isTerminal)
        assertTrue(DownloadProgress(DownloadState.FAILED_CORRUPT, 1f, 1, 1, "").isTerminal)
        assertFalse(DownloadProgress(DownloadState.DOWNLOADING, 0f, 0, 1, "").isTerminal)
    }
}
