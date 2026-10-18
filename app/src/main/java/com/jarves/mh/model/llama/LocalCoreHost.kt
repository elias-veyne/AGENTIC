package com.jarves.mh.model.llama

import android.util.Log
import com.jarves.mh.agent.LocalTaskExecutor
import com.jarves.mh.agent.NoRouteTelemetry
import com.jarves.mh.agent.OrchestrationRouter
import com.jarves.mh.agent.RouteTelemetry
import com.jarves.mh.agent.RoutingTaskExecutor
import com.jarves.mh.agent.TaskExecutor
import com.jarves.mh.model.ModelPathResolver
import java.io.File

/**
 * Owns the on-device core for the life of the process and decides whether the agent
 * router should be engaged at all.
 *
 * ## Why this gate exists
 *
 * [RoutingTaskExecutor] treats a missing local executor as a *fatal* failure, and the
 * router classifies ordinary chat as `LOCAL_ONLY`. Wiring the two together with no
 * model on disk would therefore make every chat request throw instead of reaching a
 * provider - the app would be unusable until the user downloaded something.
 *
 * That behaviour is correct inside the router and wrong at this layer. Refusing to
 * escalate is the right call *when the user asked for local inference*; "no local
 * model was ever installed" is a different state, and the product answer to it is
 * "behave exactly as before", not "refuse to work".
 *
 * So [routingExecutor] returns [remote] untouched until a model is genuinely resident.
 * The router is engaged on the first load and never silently bypassed afterwards.
 */
class LocalCoreHost(
    private val resolver: ModelPathResolver,
    private val planner: com.jarves.mh.model.MemoryPlanner = com.jarves.mh.model.MemoryPlanner(),
    /**
     * Run one benchmark immediately after the first successful load and log it.
     *
     * On by default because the PRD's latency and thermal targets can only be settled
     * on a real device, and a number that requires a UI affordance nobody has built
     * yet is a number that never gets taken. The cost is one short generation, once.
     */
    private val autoBenchmark: Boolean = true,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** Result of trying to bring a local model up. */
    sealed interface LoadOutcome {
        data class Loaded(val modelId: String, val description: String, val loadMillis: Long) : LoadOutcome

        /** No GGUF on disk. Not an error - it is the state of a fresh install. */
        data object NotInstalled : LoadOutcome

        /** The library is missing from this build, or a load failed at runtime. */
        data class Unavailable(val reason: String) : LoadOutcome

        /** [MemoryPlanner] predicted the load would not fit. */
        data class Refused(val reason: String) : LoadOutcome
    }

    private val bridge: LlamaBridge by lazy { LlamaBridge(resolver, planner) }

    /** Whether the native runtime exists in this build. */
    val isRuntimeAvailable: Boolean get() = bridge.isAvailable

    /** Whether a model is loaded and able to serve. */
    val isModelResident: Boolean get() = bridge.isReady

    /** Backend and build info, for bug reports. Empty when the runtime is absent. */
    val backendInfo: String get() = LlamaBridge.backendInfo()

    /**
     * Locate a GGUF already present on disk.
     *
     * The PRD's Model Manager and download manager are not built yet, so there is
     * nothing here that *puts* a model on the device. Until that lands, this is how a
     * model arrives: pushed onto the device by hand into the directory
     * [ModelPathResolver.modelsDir] resolves to, which is
     * `Android/data/<pkg>/files/models/<modelId>/`.
     *
     * @param modelId directory name to look under, e.g. `minicpm-v-4_7`.
     * @return the first `.gguf` found, or null.
     */
    fun discoverWeightFile(modelId: String): File? =
        resolver.modelDir(modelId)
            .listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.length() }
            ?.firstOrNull()

    /**
     * Bring up a model if one is present and it is expected to fit.
     *
     * Idempotent: a second call with a model already resident returns the same
     * [LoadOutcome.Loaded] without re-reading the file.
     */
    suspend fun loadIfPresent(modelId: String, contextTokens: Int): LoadOutcome {
        if (bridge.isReady) {
            return LoadOutcome.Loaded(bridge.modelId, bridge.modelId, 0L)
        }
        if (!bridge.isAvailable) {
            return LoadOutcome.Unavailable("libagentic-llama.so is not present in this build")
        }

        val weights = discoverWeightFile(modelId)
            ?: return LoadOutcome.NotInstalled

        val started = System.currentTimeMillis()
        return try {
            bridge.load(weights, modelId, contextTokens)
            val took = System.currentTimeMillis() - started
            log("loaded $modelId in ${took}ms, ctx=$contextTokens")
            if (autoBenchmark) {
                runCatching { benchmark(maxTokens = AUTO_BENCHMARK_TOKENS) }
                    .onFailure { log("auto-benchmark failed: ${it.message}") }
            }
            LoadOutcome.Loaded(modelId, describeSafely(), took)
        } catch (e: com.jarves.mh.agent.LocalModelUnavailableException) {
            val message = e.message ?: "unavailable"
            // A refusal and a runtime failure mean different things to the user: one
            // says "use a smaller model", the other says "this build cannot do it".
            if (message.startsWith("load refused")) {
                log("refused $modelId: $message")
                LoadOutcome.Refused(message)
            } else {
                log("unavailable $modelId: $message")
                LoadOutcome.Unavailable(message)
            }
        }
    }

    private fun describeSafely(): String =
        if (bridge.isReady) runCatching { bridge.modelDescription }.getOrDefault(bridge.modelId)
        else bridge.modelId

    /**
     * Wrap [remote] so the router can prefer the local core.
     *
     * Returns [remote] unchanged while no model is resident. See the class note for why
     * that is not merely an optimisation.
     */
    fun routingExecutor(remote: TaskExecutor, telemetry: RouteTelemetry = NoRouteTelemetry): TaskExecutor {
        if (!bridge.isReady) return remote
        val router = OrchestrationRouter(policy = OrchestrationRouter.Policy(localCoreAvailable = true))
        return RoutingTaskExecutor(
            router = router,
            local = LocalTaskExecutor(bridge),
            remote = remote,
            telemetry = telemetry,
        )
    }

    /**
     * Measure the PRD's latency KPIs on this device.
     *
     * @return one result per run, or empty when no model is resident.
     */
    suspend fun benchmark(
        maxTokens: Int = DEFAULT_BENCHMARK_TOKENS,
        repeat: Int = 1,
    ): List<BenchmarkResult> = if (!bridge.isReady) {
        emptyList()
    } else {
        bridge.benchmark(maxTokens, repeat).also { report(it) }
    }

    /** Emit results as logcat lines. The first line is the one to grep for. */
    private fun report(results: List<BenchmarkResult>) {
        if (results.isEmpty()) {
            log("BENCH no results")
            return
        }
        results.forEachIndexed { i, r ->
            log(
                "BENCH[$i] ttft=${r.timeToFirstTokenMs}ms decode=${r.totalDecodeMs}ms " +
                    "tokens=${r.tokensGenerated} tps=${"%.2f".format(r.tokensPerSecond)} prompt=${r.promptTokens}"
            )
        }
        // A single headline makes the run greppable without parsing the detail lines.
        val best = results.minByOrNull { it.timeToFirstTokenMs.coerceAtLeast(Long.MAX_VALUE) }
        best?.let { log("BENCH_SUMMARY ttft=${it.timeToFirstTokenMs}ms tps=${"%.2f".format(it.tokensPerSecond)}") }
    }

    /** Release the model. The native runtime itself stays initialised. */
    fun release() = bridge.release()

    companion object {
        const val TAG = "AgenticLocalCore"

        const val DEFAULT_BENCHMARK_TOKENS = 64
        const val AUTO_BENCHMARK_TOKENS = 32

        /**
         * Model directory the PRD names as the entry-tier target, and the one to push
         * a GGUF into when testing by hand.
         */
        const val ENTRY_MODEL_ID = "minicpm-v-4_7"
    }
}
