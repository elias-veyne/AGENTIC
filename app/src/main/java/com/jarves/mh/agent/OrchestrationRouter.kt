package com.jarves.mh.agent

/**
 * Classifies an incoming request and decides which core should serve it.
 *
 * ## Relationship to [Orchestrator]
 *
 * These are two different concerns that happen to both live under `agent/`, and
 * deliberately neither calls the other:
 *
 *  - [Orchestrator] decides **how work splits** - it decomposes one instruction into
 *    subtasks and shards them across workers.
 *  - [OrchestrationRouter] decides **who does the work** - whether a given
 *    instruction is served by the on-device model or dispatched to a cloud provider.
 *
 * The router sits at the [TaskExecutor] seam rather than inside the orchestrator, so
 * decomposition stays unchanged and both cores remain interchangeable workers. That
 * also means `Orchestrator.kt` needs no edits to adopt routing.
 *
 * ## Why escalation keys off RequestKind, not Route
 *
 * The interesting case is "the local model died, do we send this to the cloud?".
 * Answering that from [Route] alone is unsafe, because `LOCAL_ONLY` covers two
 * populations that must be treated oppositely:
 *
 *  - [RequestKind.DEVICE_CONTROL] and [RequestKind.AMBIGUOUS_DEVICE] are local
 *    *because of what they contain* - contact names, message bodies, screenshots.
 *    Escalating one leaks exactly the content routing exists to keep on the device.
 *  - [RequestKind.SHORT_CHAT] is local as a matter of preference and cost. Escalating
 *    it on a genuine runtime failure is the whole point of having a fallback.
 *
 * So [mayEscalate] asks about the [RequestKind] and the [LocalFailure] together. A
 * route alone cannot answer it.
 *
 * ## Why the rules are explicit and not model-driven
 *
 * The v1 classifier is deterministic. A wrong route has real consequences, and
 * neither failure mode should depend on how a language model happened to feel about
 * the wording. Every decision is a rule a reviewer can read, and [classify] is pure so
 * the whole table is unit-testable without a device or a network.
 *
 * [classifier] is the seam for a future model-assisted classifier. When that arrives
 * it should *propose* a [RequestKind], and [routeFor], [isDeviceBound] and
 * [mayEscalate] remain the enforcement point regardless of what the model proposes.
 */
class OrchestrationRouter(
    // Written as a lambda rather than ::defaultClassifier so the reference to the
    // companion member is unambiguous at the default-argument call site.
    private val classifier: (String) -> RequestKind = { text -> defaultClassifier(text) },
    private val policy: Policy = Policy(),
) {
    /** What the request actually is, independent of who will serve it. */
    enum class RequestKind {
        /**
         * Phone control: app launch, SMS, calls, settings, UI automation.
         * The prompt can name a contact or carry a screenshot, so this kind is
         * pinned to the device and never escalates.
         */
        DEVICE_CONTROL,

        /** Quick factual Q&A and short chat. Preferred local; may escalate on failure. */
        SHORT_CHAT,

        /** Ambiguous or multi-step device tasks. Planned locally, then executed locally. */
        AMBIGUOUS_DEVICE,

        /** Code generation, repo edits, CI and git operations. */
        HEAVY_CODE,

        /** The user explicitly asked for the cloud ("use GPT", "heavy thinking"). */
        EXPLICIT_CLOUD,
    }

    /** Which core serves the request. */
    enum class Route {
        /** Local, and a local failure surfaces rather than escalating. */
        LOCAL_ONLY,

        /** Local, but a local *runtime* failure may be retried against the cloud. */
        LOCAL_PREFERRED_REMOTE_FALLBACK,

        /** Remote only. */
        REMOTE_ONLY,

        /** Local plans, local executes. Two local passes, no remote hop. */
        HYBRID,
    }

    /**
     * Tunable policy switches.
     *
     * The PRD's thresholds - "short chat under 512 estimated output tokens", "code
     * generation beyond 200 lines" - are deliberately **not** here. Both are
     * properties of generated output, which does not exist until the model has
     * produced it, so they cannot gate a routing decision made before generation.
     * They belong to post-hoc telemetry and, later, to a model-assisted classifier.
     * Carrying them as fields now would imply a precision the v1 rules do not have.
     *
     * [localCoreAvailable] is a parameter so a device with no local model installed can
     * report an honest route, and so tests can assert escalation without provoking a
     * real out-of-memory error.
     */
    data class Policy(
        /**
         * Whether a local model is loaded and able to serve a request. Used only to
         * report an honest route to the UI; it does not by itself authorise a
         * dispatch. See [route].
         */
        val localCoreAvailable: Boolean = true,
    )

    /**
     * A local failure, split by whether escalation could ever be appropriate.
     *
     * The distinction is the whole point of the type. [Fatal] covers "there is no
     * local core here at all", which is a configuration state, not a runtime fault,
     * and must not be read as permission to send the request to a provider.
     *
     * [reason] is declared on the interface so a failure can be turned into a message
     * without the caller having to branch on which subtype it holds.
     */
    sealed interface LocalFailure {
        /** Human-readable cause, safe to surface in a message or log. */
        val reason: String

        /** The model was present but could not be loaded, or the runtime aborted it for memory. */
        data class Unavailable(override val reason: String) : LocalFailure

        /** No local core, cancelled, or a genuine bug. Never a dispatch authorisation. */
        data class Fatal(override val reason: String) : LocalFailure
    }

    /** Classify [instruction] without routing it. */
    fun classify(instruction: String): RequestKind = classifier(instruction)

    /**
     * The PRD's classification table, applied in precedence order.
     *
     * Precedence is the part the table leaves implicit. [RequestKind.EXPLICIT_CLOUD] is
     * checked first because an explicit request from the user is an intentional
     * override of every other rule - if the user names a provider, applying a
     * lower-preference rule instead substitutes our judgement for theirs.
     *
     * The result is the route a *healthy* local core would take.
     */
    fun routeFor(kind: RequestKind): Route = when (kind) {
        RequestKind.EXPLICIT_CLOUD -> Route.REMOTE_ONLY
        RequestKind.HEAVY_CODE -> Route.REMOTE_ONLY
        RequestKind.DEVICE_CONTROL -> Route.LOCAL_ONLY
        RequestKind.AMBIGUOUS_DEVICE -> Route.HYBRID
        RequestKind.SHORT_CHAT -> Route.LOCAL_ONLY
    }

    /**
     * Whether this kind of request must never be dispatched to a cloud provider.
     *
     * True for anything that touches the device or the user's own context. These are
     * the requests the privacy guarantee is actually about.
     */
    fun isDeviceBound(kind: RequestKind): Boolean =
        kind == RequestKind.DEVICE_CONTROL || kind == RequestKind.AMBIGUOUS_DEVICE

    /**
     * The route to report for [instruction] right now.
     *
     * This is what the UI shows before the user commits. When a local-bound request
     * cannot be served locally and could legally fall back, the reported route is
     * [Route.LOCAL_PREFERRED_REMOTE_FALLBACK] - the PRD's "local model crashed/OOM"
     * row - so the UI can show that a cloud hop may happen rather than discovering it
     * afterwards.
     *
     * Note this does not authorise anything. [mayEscalate] is still consulted at
     * execution time against the failure that actually occurred.
     */
    fun route(instruction: String): Route {
        val kind = classify(instruction)
        val intent = routeFor(kind)
        val fallbackAllowed = !isDeviceBound(kind) &&
            intent != Route.REMOTE_ONLY &&
            !policy.localCoreAvailable
        return if (fallbackAllowed) Route.LOCAL_PREFERRED_REMOTE_FALLBACK else intent
    }

    /**
     * Whether a failed local attempt of [kind] may be retried against the cloud.
     *
     * Requires both that the content is safe to send and that the failure looks like a
     * runtime fault. A [LocalFailure.Fatal] is never escalated, so a local bug or an
     * absent model cannot become silent egress of whatever the user typed.
     */
    fun mayEscalate(kind: RequestKind, failure: LocalFailure): Boolean =
        !isDeviceBound(kind) && failure is LocalFailure.Unavailable

    companion object {
        /**
         * Phrases that mark an explicit request for the cloud. Matched
         * case-insensitively against the whole instruction.
         */
        private val CLOUD_REQUESTS = listOf(
            "use gpt", "ask gpt", "ask the cloud", "use claude", "ask claude",
            "use deepseek", "use kimi", "ask kimi", "use openrouter",
            "heavy thinking", "think harder", "cloud agent",
        )

        /**
         * Markers of device-control traffic, which must never be dispatched.
         *
         * Multi-word phrases only. A bare `"text"` was tried first and is wrong: it
         * matches inside `"context"`, so every instruction mentioning a context window
         * was classified as device control and pinned local. Substring matching
         * rewards specificity here - a false positive sends ordinary work to the
         * cloud, a false negative can send a screenshot there.
         */
        private val DEVICE_MARKERS = listOf(
            "send a text", "send a message", "send an sms", "text message",
            "make a call", "place a call", "call mom", "call dad",
            "set an alarm", "set a reminder", "set a timer",
            "turn on bluetooth", "turn on wifi", "turn off wifi", "airplane mode",
            "open settings", "open the camera", "open maps", "take a screenshot",
            "read my notifications", "reply to", "unlock the phone",
            "tap the", "swipe", "scroll the", "go to the home screen",
        )

        /** Markers of work that needs a real context window and a server. */
        private val HEAVY_MARKERS = listOf(
            "refactor", "rewrite the", "migrate", "ci pipeline", "pull request",
            "commit", "rebase", "merge branch", "repository", "scaffold",
            "implement a", "build a", "test suite", "benchmark",
        )

        /** Markers of multi-step work, which needs a plan before it acts. */
        private val AMBIGUOUS_MARKERS = listOf(
            "then", "after that", "afterwards", "and also", "first ", "finally",
            "for each", "every time", "whenever",
        )

        /**
         * The default v1 classifier.
         *
         * Device and ambiguity markers are tested before the fallback, because the
         * failure that matters is a *device* request reaching a cloud provider, not a
         * chat request staying local.
         */
        fun defaultClassifier(instruction: String): RequestKind {
            val text = instruction.lowercase()
            return when {
                CLOUD_REQUESTS.any { it in text } -> RequestKind.EXPLICIT_CLOUD
                HEAVY_MARKERS.any { it in text } -> RequestKind.HEAVY_CODE
                DEVICE_MARKERS.any { it in text } ->
                    // A single-step device command stays DEVICE_CONTROL; only
                    // multi-step device work needs the plan/execute split.
                    if (AMBIGUOUS_MARKERS.any { m -> m in text }) RequestKind.AMBIGUOUS_DEVICE
                    else RequestKind.DEVICE_CONTROL
                AMBIGUOUS_MARKERS.any { it in text } -> RequestKind.AMBIGUOUS_DEVICE
                else -> RequestKind.SHORT_CHAT
            }
        }
    }
}

/**
 * Routes each instruction to a local or remote executor and records the decision.
 *
 * Local and remote are both plain [TaskExecutor]s, so this composes with the existing
 * engine without either core knowing anything about the other.
 *
 * The escalation decision is taken here rather than baked into a route, because only
 * the failure that actually occurred can tell an absent local model apart from a
 * local model that died mid-session.
 */
class RoutingTaskExecutor(
    private val router: OrchestrationRouter,
    private val local: TaskExecutor?,
    private val remote: TaskExecutor,
    private val telemetry: RouteTelemetry = NoRouteTelemetry,
) : TaskExecutor {
    /** The route reported for [instruction], for UI display. Does not execute. */
    fun routeFor(instruction: String): OrchestrationRouter.Route = router.route(instruction)

    override suspend fun execute(instruction: String): String {
        val kind = router.classify(instruction)
        val route = router.routeFor(kind)
        telemetry.record(instruction, kind, route)

        if (route == OrchestrationRouter.Route.REMOTE_ONLY) return remote.execute(instruction)
        return runLocal(kind, instruction)
    }

    private suspend fun runLocal(kind: OrchestrationRouter.RequestKind, instruction: String): String {
        val executor = local
        if (executor == null) {
            // No local core installed. That is a configuration state, not a runtime
            // fault, so it is Fatal and never escalates - otherwise simply not having
            // downloaded a model would quietly start sending device traffic upstream.
            throw LocalExecutionException(
                OrchestrationRouter.LocalFailure.Fatal("no local model installed"),
            )
        }

        return try {
            executor.execute(instruction)
        } catch (e: LocalExecutionException) {
            if (router.mayEscalate(kind, e.failure)) {
                telemetry.recordEscalation(OrchestrationRouter.Route.LOCAL_PREFERRED_REMOTE_FALLBACK, e.failure)
                remote.execute(instruction)
            } else {
                throw e
            }
        }
    }
}

/** Thrown when a local attempt failed in a way the router may need to classify. */
class LocalExecutionException(
    val failure: OrchestrationRouter.LocalFailure,
    cause: Throwable? = null,
) : Exception(failure.reason, cause)

/**
 * A minimal on-device inference surface.
 *
 * Deliberately small: the router and the agent runtime only need "given a prompt,
 * produce text". Streaming and vision extend this interface rather than reshape it.
 */
interface LocalInferenceBackend {
    /** Whether a model is loaded and able to serve a request right now. */
    val isReady: Boolean

    /** Stable identifier of the loaded model, for telemetry. Empty when none is loaded. */
    val modelId: String

    /**
     * Run [prompt] to completion and return the generated text.
     *
     * @throws LocalModelUnavailableException when no model is loaded, or the runtime
     *   cannot currently serve the request.
     */
    suspend fun complete(prompt: String, maxTokens: Int = 512): String

    /** Release the model. Must be safe to call when nothing is loaded. */
    fun release()
}

/** Signals that the local core cannot serve a request right now. */
class LocalModelUnavailableException(message: String) : Exception(message)

/**
 * Wraps a local model so the engine can execute against it through [TaskExecutor].
 *
 * This is the entire integration surface between the on-device core and the existing
 * agent runtime. A failure is retyped as [LocalExecutionException] so the router can
 * tell an unavailable model from a genuine error instead of seeing an opaque exception.
 */
class LocalTaskExecutor(
    private val backend: LocalInferenceBackend,
) : TaskExecutor {
    override suspend fun execute(instruction: String): String = try {
        backend.complete(instruction)
    } catch (e: LocalModelUnavailableException) {
        throw LocalExecutionException(
            OrchestrationRouter.LocalFailure.Unavailable(e.message ?: "local model unavailable"),
            e,
        )
    }
}

/**
 * Telemetry sink for routing decisions.
 *
 * The PRD calls for a `route_telemetry` table. It is an interface here so the router
 * stays free of Android and SQLite, keeping it unit-testable on the JVM.
 */
interface RouteTelemetry {
    fun record(
        instruction: String,
        kind: OrchestrationRouter.RequestKind,
        route: OrchestrationRouter.Route,
    )

    /** Emitted when a local attempt escalated, so escalation rate is visible per model. */
    fun recordEscalation(route: OrchestrationRouter.Route, failure: OrchestrationRouter.LocalFailure)
}

/** A no-op [RouteTelemetry] for callers that do not collect it. */
object NoRouteTelemetry : RouteTelemetry {
    override fun record(
        instruction: String,
        kind: OrchestrationRouter.RequestKind,
        route: OrchestrationRouter.Route,
    ) = Unit

    override fun recordEscalation(
        route: OrchestrationRouter.Route,
        failure: OrchestrationRouter.LocalFailure,
    ) = Unit
}
