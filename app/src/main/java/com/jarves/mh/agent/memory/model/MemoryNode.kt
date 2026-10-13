package com.jarves.mh.agent.memory.model

/**
 * CHM — a single memory atom.
 *
 * Bi-temporal: [createdAt]/[expiredAt] live on the *system* clock (when this
 * record was written and when it was superseded in the store) while
 * [validAt]/[invalidAt] live on the *real-world* clock (when the fact became
 * true in the world and when it stopped being true). The split is what lets
 * the store answer "what did I believe about SettingsScreen ten minutes ago?"
 * — a superseded memory is still retrievable at a historical task time.
 *
 * Trust comes from the Trust-Gradient: the origin of a fact fixes its initial
 * trust and therefore how much retrieval weight it earns. See [MemoryKind].
 */
data class MemoryNode(
    val id: Long,
    /** The memory content, stated as a self-contained fact. */
    val content: String,
    val kind: MemoryKind,
    /** 0.0 (untrusted speculation) .. 1.0 (pinned user request). */
    var trust: Float,
    /** System clock millis — when the record was written. */
    val createdAt: Long,
    /** System clock millis — null while the memory is current. */
    var expiredAt: Long?,
    /** Real-world clock millis — when the fact became true in the world. */
    val validAt: Long,
    /** Real-world clock millis — null while the fact is still true. */
    var invalidAt: Long?,
    /** Normalized 0..1 importance signal from the Drift Watchdog. */
    var salience: Float,
    /** Hebbian: times this node was retrieved *and used* alongside another. */
    var coActivationCount: Int,
    /** System clock millis of the last Hebbian strengthening. */
    var lastCoactivatedAt: Long,
    /** The task/chat that produced this memory. */
    val taskId: String,
    /** True only for the user's own request — pinned, never decays. */
    val pinned: Boolean,
)

/**
 * The origin of a memory fixes its initial trust (Trust-Gradient table).
 * Promotion rules live in [com.jarves.mh.agent.memory.chm.TrustGradient].
 */
enum class MemoryKind(val initialTrust: Float) {
    /** The user's original request. Trust 1.0, pinned, never decays. */
    MISSION(1.0f),

    /** A step the agent's own plan produced. Trust 0.8. */
    PLAN_STEP(0.8f),

    /** A digest of tool output. Trust 0.6. */
    TOOL_DIGEST(0.6f),

    /** A conclusion the agent drew from reading code. Trust 0.4. */
    INFERENCE(0.4f),

    /** Ungrounded speculation. Trust 0.2, decays aggressively. */
    SPECULATION(0.2f),
}
