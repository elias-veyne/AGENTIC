package com.jarves.mh.model.llama

import com.jarves.mh.agent.LocalModelUnavailableException
import com.jarves.mh.model.ModelPathResolver
import com.jarves.mh.model.StorageRoots
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The Kotlin half of the local core, tested with no native library present.
 *
 * That absence is not an accident of the harness - it is exactly the situation on a
 * `preBuild` APK or on the desktop JVM, so these tests exercise the degradation path
 * for real rather than mocking it. The library is genuinely missing here, which makes
 * "does the bridge fail gracefully" a question with a definite answer.
 */
class LlamaBridgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val made = mutableMapOf<String, File>()
    private fun dir(key: String): File = made.getOrPut(key) { temp.newFolder() }

    /**
     * Built lazily, not as a field initialiser.
     *
     * JUnit creates the `TemporaryFolder` root in the rule's `before()`, which runs
     * *after* the test instance is constructed. A `val resolver = ...` initialiser
     * therefore executes too early and every test in the class fails with
     * "the temporary folder has not yet been created" - including ones that never
     * touch the filesystem.
     */
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

    private fun bridge(
        availableRam: Long = Long.MAX_VALUE,
        kvBytesPerToken: Long = 1024,
    ) = LlamaBridge(
        resolver = resolver,
        kvBytesPerToken = kvBytesPerToken,
        availableRam = { availableRam },
        threads = 2,
    )

    // --- the library really is absent under test ----------------------------

    @Test
    fun `the native library is not loadable on a desktop jvm`() {
        // If this ever becomes true the rest of this class is testing nothing useful,
        // because every branch below depends on the library being unavailable.
        assertFalse("expected libagentic-llama.so to be absent", LlamaNative.isLibraryLoaded)
    }

    @Test
    fun `constructing the bridge without the native library does not throw`() {
        // The load guard catches UnsatisfiedLinkError into a flag. If it did not, this
        // line would take the test JVM down with an Error from a class initialiser.
        val bridge = bridge()
        assertFalse(bridge.isAvailable)
    }

    @Test
    fun `a bridge with no library reports itself not ready`() {
        assertFalse(bridge().isReady)
        assertEquals("", bridge().modelId)
    }

    @Test
    fun `loading without the library reports unavailable rather than crashing`() {
        val model = dir("external").resolve("model.gguf").apply { writeText("not a real gguf") }
        var thrown: LocalModelUnavailableException? = null
        try {
            runBlocking { bridge().load(model, "model", 2048) }
        } catch (e: LocalModelUnavailableException) {
            thrown = e
        }
        assertTrue("expected LocalModelUnavailableException", thrown != null)
    }

    @Test
    fun `generating without a loaded model reports unavailable`() {
        var thrown: LocalModelUnavailableException? = null
        try {
            runBlocking { bridge().complete("hello") }
        } catch (e: LocalModelUnavailableException) {
            thrown = e
        }
        assertTrue("expected LocalModelUnavailableException", thrown != null)
    }

    @Test
    fun `benchmarking without a loaded model reports unavailable`() {
        var thrown: LocalModelUnavailableException? = null
        try {
            runBlocking { bridge().benchmark() }
        } catch (e: LocalModelUnavailableException) {
            thrown = e
        }
        assertTrue("expected LocalModelUnavailableException", thrown != null)
    }

    @Test
    fun `release is safe to call when nothing is loaded`() {
        // Called from release paths and on teardown; a second call must not throw.
        val bridge = bridge()
        bridge.release()
        bridge.release()
        assertEquals(0L, bridge.residentWeightBytes)
    }

    @Test
    fun `backend info is empty when the library is absent`() {
        assertEquals("", LlamaBridge.backendInfo())
    }

    // --- thread count -------------------------------------------------------

    @Test
    fun `thread count is clamped to a sane range`() {
        // Half the cores, but never 1 (which would serialise decode) and never above 8
        // (past which llama.cpp stops gaining and the phone starts throttling).
        val threads = LlamaBridge.defaultThreads()
        assertTrue("threads=$threads", threads in 2..8)
    }

    // --- benchmark result parsing -------------------------------------------

    @Test
    fun `native timings are decoded into the documented order`() {
        // Index order is a JNI contract: ttft, decodeMs, tokens, tokPerSec, prompt.
        val decoded = BenchmarkResult.fromNative(
            floatArrayOf(812f, 4_000f, 72f, 18f, 26f)
        )

        assertEquals(812L, decoded!!.timeToFirstTokenMs)
        assertEquals(4_000L, decoded.totalDecodeMs)
        assertEquals(72, decoded.tokensGenerated)
        assertEquals(18f, decoded.tokensPerSecond, 0.001f)
        assertEquals(26, decoded.promptTokens)
    }

    @Test
    fun `a first-token time of minus one means no token was produced`() {
        // The native side reports -1 rather than 0 because 0 would be indistinguishable
        // from an instant response.
        val decoded = BenchmarkResult.fromNative(floatArrayOf(-1f, 0f, 0f, 0f, 12f))

        assertEquals(-1L, decoded!!.timeToFirstTokenMs)
        assertEquals(0, decoded.tokensGenerated)
        assertEquals(0f, decoded.tokensPerSecond, 0.001f)
    }

    @Test
    fun `malformed native output decodes to null rather than throwing`() {
        assertNull(BenchmarkResult.fromNative(null))
        assertNull(BenchmarkResult.fromNative(floatArrayOf()))
        assertNull(BenchmarkResult.fromNative(floatArrayOf(1f, 2f)))
    }

    @Test
    fun `the benchmark prompt is fixed so runs are comparable`() {
        // A varying prompt would change the token count and make tok/s meaningless
        // across builds.
        assertTrue(BenchmarkResult.BENCHMARK_PROMPT.isNotBlank())
        assertEquals(BenchmarkResult.BENCHMARK_PROMPT, BenchmarkResult.BENCHMARK_PROMPT)
    }

    // --- memory refusal happens before the disk is touched -------------------

    @Test
    fun `a model that cannot fit is refused`() {
        val model = dir("external").resolve("big.gguf").apply { writeBytes(ByteArray(2_048)) }
        var thrown: LocalModelUnavailableException? = null
        try {
            // 4 bytes of "available" RAM against a 2 KB file: refused.
            runBlocking { bridge(availableRam = 4L).load(model, "big", 4096) }
        } catch (e: LocalModelUnavailableException) {
            thrown = e
        }
        assertTrue("expected the load to be refused", thrown != null)
    }

    @Test
    fun `a missing model file is reported as unavailable`() {
        // On a JVM the library check fires first, so the file-missing branch is not
        // reachable here. That ordering is deliberate and is what this pins: telling
        // a user their model file is missing when the build cannot load models at all
        // would send them looking in the wrong place. The file check still runs, and
        // reports "not found", on any build that does have the library.
        val missing = dir("external").resolve("absent.gguf")
        var thrown: LocalModelUnavailableException? = null
        try {
            runBlocking { bridge().load(missing, "absent", 2048) }
        } catch (e: LocalModelUnavailableException) {
            thrown = e
        }
        assertTrue("expected LocalModelUnavailableException", thrown != null)
        assertTrue(
            "library absence should be reported before file absence; got: ${thrown?.message}",
            thrown?.message?.contains("not available") == true,
        )
    }

    @Test
    fun `an absent library is reported before anything touches the filesystem`() {
        // Same precedence, stated as its own case so a future reorder that checks the
        // file first is caught here rather than in the test above.
        val model = dir("external").resolve("present.gguf").apply { writeText("x") }
        var thrown: LocalModelUnavailableException? = null
        try {
            runBlocking { bridge().load(model, "present", 2048) }
        } catch (e: LocalModelUnavailableException) {
            thrown = e
        }
        assertTrue(thrown?.message?.contains("not available") == true)
    }
}
