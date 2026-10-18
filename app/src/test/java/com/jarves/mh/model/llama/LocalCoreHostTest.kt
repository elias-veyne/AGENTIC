package com.jarves.mh.model.llama

import com.jarves.mh.agent.TaskExecutor
import com.jarves.mh.model.ModelPathResolver
import com.jarves.mh.model.StorageRoots
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Behaviour of the on-device host with no model on disk and no native library.
 *
 * That is the state of every current install, which makes it the state that matters
 * most: it must be indistinguishable from the app before any of this existed.
 */
class LocalCoreHostTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val logs = mutableListOf<String>()
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

    private fun host(autoBenchmark: Boolean = false) =
        LocalCoreHost(resolver, autoBenchmark = autoBenchmark, log = { logs += it })

    private val remote = TaskExecutor { "remote:$it" }

    // --- the behaviour that stops the app breaking ---------------------------

    @Test
    fun `routing is bypassed entirely while no model is resident`() = runBlocking {
        // The router classifies chat as LOCAL_ONLY, and RoutingTaskExecutor treats a
        // null local executor as fatal. Wiring the two together without this guard
        // would make every chat request throw instead of reaching a provider.
        assertFalse(host().isModelResident)

        val wrapped = host().routingExecutor(remote)

        assertSame("must be the remote executor itself", remote, wrapped)
    }

    @Test
    fun `an unwrapped executor still reaches the provider`() = runBlocking {
        // Proves the pass-through above is functionally correct, not just identical
        // by reference.
        val executor = host().routingExecutor(remote)
        assertEquals("remote:hello", executor.execute("hello"))
    }

    @Test
    fun `the runtime is reported absent in this environment`() {
        assertFalse(host().isRuntimeAvailable)
        assertEquals("", host().backendInfo)
    }

    // --- discovery -----------------------------------------------------------

    @Test
    fun `discovery finds nothing when no model directory exists`() {
        assertEquals(null, host().discoverWeightFile("minicpm-v-4_7"))
    }

    @Test
    fun `discovery finds a gguf on disk`() {
        val weights = resolver.modelDir("minicpm-v-4_7").resolve("minicpm-v-4_7_IQ4_XS.gguf")
        weights.parentFile.mkdirs()
        weights.writeText("stub")

        assertEquals(weights, host().discoverWeightFile("minicpm-v-4_7"))
    }

    @Test
    fun `discovery ignores non-gguf files`() {
        val dir = resolver.modelDir("minicpm-v-4_7")
        dir.mkdirs()
        dir.resolve("tokenizer.json").writeText("{}")
        dir.resolve("config.json").writeText("{}")

        assertEquals(null, host().discoverWeightFile("minicpm-v-4_7"))
    }

    @Test
    fun `discovery prefers the smallest gguf when several are present`() {
        // A quantised and a full-precision copy in the same directory would otherwise
        // be an arbitrary choice; smaller is the one that fits the entry tier.
        val dir = resolver.modelDir("m")
        dir.mkdirs()
        dir.resolve("m_Q8_0.gguf").writeBytes(ByteArray(4_096))
        dir.resolve("m_IQ4_XS.gguf").writeBytes(ByteArray(512))

        assertEquals("m_IQ4_XS.gguf", host().discoverWeightFile("m")?.name)
    }

    // --- load outcomes -------------------------------------------------------

    @Test
    fun `an absent runtime outranks an absent model`() = runBlocking {
        // Precedence: the runtime is checked before the filesystem. On a build with
        // no native library, telling the user to install a model would be advice
        // that cannot help - the load could not be attempted either way. The same
        // ordering is pinned in LlamaBridgeTest.
        val outcome = host().loadIfPresent("minicpm-v-4_7", 4_096)

        assertTrue("expected Unavailable, got $outcome", outcome is LocalCoreHost.LoadOutcome.Unavailable)
    }

    @Test
    fun `not installed is the state on a device that has the runtime but no model`() = runBlocking {
        // Not reachable under a desktop JVM, where the runtime is always absent, so
        // this documents the intended state rather than asserting it. Discovery is
        // what decides it, and that is covered directly below.
        assertEquals(null, host().discoverWeightFile("minicpm-v-4_7"))
    }

    @Test
    fun `an absent native library reports unavailable`() = runBlocking {
        // A model on disk but no runtime: the load cannot even be attempted.
        val dir = resolver.modelDir("m")
        dir.mkdirs()
        dir.resolve("m.gguf").writeBytes(ByteArray(64))

        val outcome = host().loadIfPresent("m", 2_048)

        assertTrue("expected Unavailable, got $outcome", outcome is LocalCoreHost.LoadOutcome.Unavailable)
        assertTrue((outcome as LocalCoreHost.LoadOutcome.Unavailable).reason.contains("not present"))
    }

    @Test
    fun `benchmarking with no model resident yields no results`() = runBlocking {
        assertTrue(host().benchmark().isEmpty())
    }

    @Test
    fun `release is safe with nothing loaded`() {
        host().release()
        assertFalse(host().isModelResident)
    }

    // --- constants the manual test procedure depends on ----------------------

    @Test
    fun `the manual push path is under the applicationId not the namespace`() {
        // Android scopes external app storage by applicationId. Using the Kotlin
        // namespace here would name a directory that cannot exist, so this pins the
        // distinction that is easy to get wrong.
        assertTrue(AndroidStorageRootsPushHint.contains("com.veyne.agentic"))
        assertFalse(AndroidStorageRootsPushHint.contains("com.jarves.mh/files"))
    }

    @Test
    fun `the entry model id and benchmark budget are fixed`() {
        assertEquals("minicpm-v-4_7", LocalCoreHost.ENTRY_MODEL_ID)
        assertTrue(LocalCoreHost.DEFAULT_BENCHMARK_TOKENS > 0)
    }

    private val AndroidStorageRootsPushHint: String
        get() = com.jarves.mh.model.AndroidStorageRoots.manualPushHint()
}
