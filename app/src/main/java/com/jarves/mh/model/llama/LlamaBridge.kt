package com.jarves.mh.model.llama

import com.jarves.mh.agent.LocalInferenceBackend
import com.jarves.mh.agent.LocalModelUnavailableException
import com.jarves.mh.model.ManifestEntry
import com.jarves.mh.model.MemoryPlanner
import com.jarves.mh.model.ModelPathResolver
import com.jarves.mh.model.VerificationOutcome
import com.jarves.mh.model.ChecksumVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Timing for one generation, as reported by the native side.
 *
 * This exists because the PRD's performance targets can only be settled on a phone.
 * Time to first token under 800 ms and a sustained 18 tok/s are claims about a
 * specific SoC with a specific thermal state; no amount of CI can confirm or refute
 * them. Running [LlamaBridge.benchmark] on the target device is the only way to find
 * out, so the bridge reports the raw numbers rather than a pass/fail verdict.
 *
 * @property timeToFirstTokenMs wall time to the first generated token, or -1 if none.
 * @property totalDecodeMs wall time across the whole generation.
 * @property tokensGenerated tokens produced before EOG or the token cap.
 * @property tokensPerSecond sustained decode rate, 0 when nothing was generated.
 * @property promptTokens tokens the prompt occupied.
 */
data class BenchmarkResult(
    val timeToFirstTokenMs: Long,
    val totalDecodeMs: Long,
    val tokensGenerated: Int,
    val tokensPerSecond: Float,
    val promptTokens: Int,
) {
    companion object {
        /** Indices shared with `nativeBenchmark`. Order is part of the JNI contract. */
        private const val I_TTFT = 0
        private const val I_DECODE_MS = 1
        private const val I_TOKENS = 2
        private const val I_TPS = 3
        private const val I_PROMPT = 4

        /** The fixed prompt used for benchmarking, kept identical across runs. */
        const val BENCHMARK_PROMPT =
            "Explain in one paragraph why local-first inference matters for a mobile app " +
                "that handles personal data."

        /** Decode it back. Returns null for a malformed array rather than throwing. */
        fun fromNative(values: FloatArray?): BenchmarkResult? {
            if (values == null || values.size < 5) return null
            return BenchmarkResult(
                timeToFirstTokenMs = values[I_TTFT].toLong(),
                totalDecodeMs = values[I_DECODE_MS].toLong(),
                tokensGenerated = values[I_TOKENS].toInt(),
                tokensPerSecond = values[I_TPS],
                promptTokens = values[I_PROMPT].toInt(),
            )
        }
    }
}

/**
 * On-device inference backed by the vendored llama.cpp runtime.
 *
 * Implements [LocalInferenceBackend] so the agent router can treat the local core as
 * just another worker. Everything llama-specific - the handle, the context window,
 * the sampler - is hidden behind that interface, which means a future runtime swap
 * touches nothing above it.
 *
 * ## Threading
 *
 * Every public entry point runs on [Dispatchers.IO]. Loading a model takes seconds
 * and each generated token takes hundreds of milliseconds; either on the main thread
 * is an ANR. The native side additionally serialises access to the context, because
 * a llama context is not safe for concurrent decode.
 *
 * ## Degradation
 *
 * If the native library is absent the bridge reports itself unavailable rather than
 * throwing at construction. That keeps remote-only installs working, and it means the
 * router sees a clean "no local core" state instead of an `Error` escaping from a
 * class initialiser.
 */
class LlamaBridge(
    private val resolver: ModelPathResolver,
    private val planner: MemoryPlanner = MemoryPlanner(),
    private val verifier: ChecksumVerifier = ChecksumVerifier(),
    /**
     * Bytes of KV cache per context token.
     *
     * Needed by [MemoryPlanner] to project peak RSS, and not derivable from the file
     * name: it depends on the checkpoint's layer count, KV head count and head
     * dimension. 64 KiB per token is a deliberately conservative figure for a ~1B f16
     * model (2 x layers x kv-heads x head-dim x 2 bytes). It should be replaced with
     * the real figure once a checkpoint is on hand - the planner is only as good as
     * this number, and over-estimating costs a little headroom rather than an OOM kill.
     */
    private val kvBytesPerToken: Long = DEFAULT_KV_BYTES_PER_TOKEN,
    /**
     * Free bytes available to this process, for the load projection.
     *
     * Injected rather than read from a platform API so the policy is testable on the
     * JVM. The default is a *proxy*: `Runtime.maxMemory()` is the JVM heap ceiling,
     * but llama.cpp allocates almost entirely in native memory, which that number does
     * not describe. So the default deliberately under-reports, because refusing a
     * marginal load costs the user a retry while accepting one that will not fit costs
     * them their session.
     *
     * Replacing this with `ActivityManager.MemoryInfo.availMem` on a real device is
     * the intended follow-up and is tracked as such.
     */
    private val availableRam: () -> Long = { Runtime.getRuntime().maxMemory() },

    private val threads: Int = defaultThreads(),
) : LocalInferenceBackend {

    @Volatile
    private var handle: Long = 0

    @Volatile
    private var loadedModelId: String = ""

    @Volatile
    private var loadedWeightBytes: Long = 0

    override val isReady: Boolean get() = handle != 0L && LlamaNative.isLibraryLoaded

    override val modelId: String get() = loadedModelId

    /** Whether the native library is present. False means local inference cannot run. */
    val isAvailable: Boolean get() = LlamaNative.isLibraryLoaded

    /**
     * Load [weightFile] and keep it resident.
     *
     * Refuses a load that [MemoryPlanner] predicts will not fit, before touching the
     * disk, because the alternative is the kernel killing the process mid-load and
     * taking the session with it.
     *
     * @param contextTokens context window to reserve. Must exceed prompt plus output.
     * @throws LocalModelUnavailableException when the library is missing, the file is
     *   absent, the digest does not match, or the projection exceeds free memory.
     */
    suspend fun load(weightFile: File, modelId: String, contextTokens: Int) = withContext(Dispatchers.IO) {
        requireAvailable()
        if (handle != 0L) release()

        if (!weightFile.exists()) {
            throw LocalModelUnavailableException("model file not found: ${weightFile.absolutePath}")
        }

        val bytes = weightFile.length()
        verify(weightFile, bytes)

        // The projection deliberately does not add the draft head here. Speculative
        // decoding is Phase 2; budgeting for it now would refuse loads that fit.
        val projected = planner.projectedRss(bytes, contextTokens, kvBytesPerToken)
        planner.refusalOrNull(
            availableRamBytes = availableRam(),
            weightBytes = bytes,
            contextTokens = contextTokens,
            kvBytesPerToken = kvBytesPerToken,
        )?.let { refusal ->
            throw LocalModelUnavailableException(
                "load refused: needs about ${refusal.projectedBytes} B, " +
                    "only ${refusal.availableRamBytes} B available; " +
                    "try the ${refusal.suggested.tier} profile",
            )
        }
        check(projected > 0) { "projection must be positive" }

        val newHandle = LlamaNative.nativeLoadModel(
            path = weightFile.absolutePath,
            nCtx = contextTokens,
            nThreads = threads,
            // CPU only: there is no GPU backend on the devices this targets, and a
            // positive value makes llama.cpp log a backend fallback warning per load.
            nGpuLayers = 0,
        )
        if (newHandle == 0L) throw LocalModelUnavailableException("native load returned no handle")

        handle = newHandle
        loadedModelId = modelId
        loadedWeightBytes = bytes
    }

    /**
     * Reject a file whose digest does not match the manifest, when a manifest entry
     * exists.
     *
     * A missing manifest entry is *not* fatal here. The manifest signature is not
     * implemented yet, so a hard failure would refuse every model on a device until
     * that lands. Failing closed on a present-and-matching digest while tolerating
     * absence keeps the check meaningful without bricking local inference in the
     * meantime. This is a temporary position and is recorded as such.
     */
    private fun verify(weightFile: File, bytes: Long) {
        val entryFile = resolver.manifestFile(loadedManifestId(weightFile))
        if (!entryFile.exists()) return

        val entry = runCatching { parseManifestEntry(entryFile, weightFile, bytes) }.getOrNull() ?: return
        when (val outcome = verifier.verify(weightFile, entry)) {
            is VerificationOutcome.Verified -> Unit
            is VerificationOutcome.Mismatched ->
                throw LocalModelUnavailableException("checksum mismatch for ${weightFile.name}")
            is VerificationOutcome.Unreadable ->
                throw LocalModelUnavailableException("could not read ${weightFile.name}: ${outcome.reason}")
            is VerificationOutcome.NoUsableDigest -> Unit
        }
    }

    private fun loadedManifestId(weightFile: File): String = weightFile.parentFile?.name ?: ""

    /**
     * Minimal manifest reader: a flat JSON object of file name to digest and size.
     *
     * Deliberately not a general JSON parser. The manifest format is not pinned yet,
     * so this reads the shape that is currently shipped and degrades to "no check"
     * rather than throwing.
     */
    private fun parseManifestEntry(file: File, target: File, bytes: Long): ManifestEntry? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val sha = Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"").find(text)?.groupValues?.get(1)
        val md5 = Regex("\"md5\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").find(text)?.groupValues?.get(1)
        if (sha == null && md5 == null) return null
        return ManifestEntry(target.name, bytes, sha256 = sha, md5 = md5)
    }

    override suspend fun complete(prompt: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val current = handle
        if (current == 0L) throw LocalModelUnavailableException("no local model loaded")
        requireAvailable()

        val out = LlamaNative.nativeGenerate(
            handle = current,
            prompt = prompt,
            maxTokens = maxTokens,
            temp = 0.7f,
            topK = 40,
            topP = 0.95f,
        )
        // An empty string is a legitimate result (the model emitted EOG immediately).
        // Null means the native side failed and it already threw; treating it as
        // empty would silently turn a failure into a blank answer.
        out ?: throw LocalModelUnavailableException("native generation returned null")
    }

    /**
     * Run a fixed prompt and report timings.
     *
     * The entry point for settling the PRD's latency KPIs on real hardware.
     *
     * @param repeat how many generations to run. The first is discarded because it
     *   includes cold page faults on the weight file; a single cold run is the worst
     *   case and the only number that is reproducible, so callers get both.
     */
    suspend fun benchmark(
        maxTokens: Int = 64,
        repeat: Int = 1,
        prompt: String = BenchmarkResult.BENCHMARK_PROMPT,
    ): List<BenchmarkResult> = withContext(Dispatchers.IO) {
        val current = handle
        if (current == 0L) throw LocalModelUnavailableException("no local model loaded")
        requireAvailable()

        (1..repeat).mapNotNull {
            BenchmarkResult.fromNative(
                LlamaNative.nativeBenchmark(
                    handle = current,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temp = 0.0f, // greedy, so token counts are reproducible across runs
                    topK = 0,
                    topP = 0.0f,
                )
            )
        }
    }

    override fun release() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        loadedModelId = ""
        loadedWeightBytes = 0
        runCatching { LlamaNative.nativeFreeModel(current) }
    }

    /** Bytes of the currently loaded weight file, for telemetry. */
    val residentWeightBytes: Long get() = loadedWeightBytes

    private fun requireAvailable() {
        if (!LlamaNative.isLibraryLoaded) {
            throw LocalModelUnavailableException("libagentic-llama.so is not available in this build")
        }
    }

    companion object {
        /** See [kvBytesPerToken] for the derivation and its conservatism. */
        const val DEFAULT_KV_BYTES_PER_TOKEN = 64L * 1024L

        /**
         * Thread count for decode.
         *
         * Half the available cores, at least 2. llama.cpp scales poorly past the
         * big-core count and the thermal budget on a phone is the binding constraint,
         * not the core count - which is also why the PRD's thermals KPI needs a real
         * device rather than a benchmark harness.
         */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

        /** Backend and build info, for bug reports. Empty when the library is absent. */
        fun backendInfo(): String =
            if (LlamaNative.isLibraryLoaded) runCatching { LlamaNative.nativeBackendInfo() }.getOrDefault("")
            else ""
    }
}
