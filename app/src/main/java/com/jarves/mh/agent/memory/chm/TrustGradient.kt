package com.jarves.mh.agent.memory.chm

import com.jarves.mh.agent.memory.model.MemoryKind
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — Trust-Gradient Fact Promotion (ARIADNE).
 *
 * Not all memories deserve equal trust. A fact the agent inferred from reading
 * code is weaker than a fact the user stated. Initial trust is fixed by
 * [MemoryKind.initialTrust]; promotion is *evidence-gated* — a memory only
 * rises when the world confirms it.
 *
 * Inverted as a drift-prevention mechanism: low-trust rumination is
 * down-weighted in retrieval so it cannot crowd out the high-trust Mission
 * Brief. The agent's own idle inspection becomes progressively less visible to
 * itself over time, which is exactly the desired effect.
 */
object TrustGradient {

    /** A user request is pinned at 1.0 and never moves. */
    fun pinned(): Float = 1.0f

    /**
     * Promote [node] because outcome evidence confirmed it. Promotion is
     * bounded and asymmetric: confirming evidence raises trust toward a per-kind
     * ceiling, and no amount of self-agreement lifts speculation past the
     * evidence ceiling without independent confirmation.
     */
    fun promote(node: MemoryNode, evidence: Evidence, now: Long): Float {
        if (node.pinned) return pinned()
        val ceiling = ceilingFor(node.kind, evidence)
        val next = (node.trust + evidence.delta).coerceAtMost(ceiling)
        node.trust = next
        // Salience follows trust: trusted memories matter more in retrieval.
        node.salience = (node.salience + evidence.delta / 2f).coerceIn(0f, 1f)
        return next
    }

    /** Demote [node] — evidence contradicted it, or it is decaying as rumination. */
    fun demote(node: MemoryNode, factor: Float, now: Long): Float {
        if (node.pinned) return pinned()
        node.trust = (node.trust * factor).coerceIn(MIN_TRUST, 1f)
        node.salience = (node.salience * factor).coerceIn(0f, 1f)
        return node.trust
    }

    /**
     * A speculation decays harder than everything else: it is the category most
     * likely to carry drift. Speculation can only be promoted by [Evidence.Compiled]
     * or [Evidence.ConfirmedBySecondRead] — never by the agent repeating it.
     */
    fun passiveDecay(node: MemoryNode, now: Long): Float {
        if (node.pinned) return pinned()
        val factor = when (node.kind) {
            MemoryKind.SPECULATION -> 0.90f
            MemoryKind.INFERENCE -> 0.96f
            MemoryKind.TOOL_DIGEST -> 0.98f
            MemoryKind.PLAN_STEP -> 0.99f
            MemoryKind.MISSION -> 1.0f
        }
        return demote(node, factor, now)
    }

    private fun ceilingFor(kind: MemoryKind, evidence: Evidence): Float = when (evidence) {
        // A write that compiles is the strongest possible confirmation.
        Evidence.Compiled -> 0.98f
        Evidence.ConfirmedBySecondRead -> 0.92f
        Evidence.OutcomeMatched -> when (kind) {
            MemoryKind.PLAN_STEP -> 0.95f
            else -> 0.90f
        }
        // The agent's own repetition never promotes past the inference ceiling.
        Evidence.SelfReferenced -> 0.65f
    }

    /** ARIADNE review bands. */
    fun band(trust: Float): Band = when {
        trust > 0.95f -> Band.AUTO_PROMOTED
        trust >= 0.85f -> Band.BATCH_REVIEW
        trust >= 0.5f -> Band.ACTIVE
        else -> Band.MANUAL_REVIEW
    }

    enum class Band { AUTO_PROMOTED, BATCH_REVIEW, ACTIVE, MANUAL_REVIEW }

    /**
     * The evidence that can promote a memory. [delta] is the trust increment.
     * Mirrors the promotion table: plan step promoted when the step's outcome
     * matched its prediction, tool digest promoted when a second independent
     * read confirms it, inference promoted when a validating write compiles.
     */
    sealed class Evidence(val delta: Float) {
        /** A plan step's outcome matched its prediction. */
        data object OutcomeMatched : Evidence(0.15f)
        /** A second independent read confirmed a tool digest. */
        data object ConfirmedBySecondRead : Evidence(0.12f)
        /** A validating write compiled or passed. */
        data class Compiled(val passed: Boolean) : Evidence(if (passed) 0.20f else 0.05f)
        /** The agent restated its own memory — weakest evidence, low ceiling. */
        data object SelfReferenced : Evidence(0.02f)
    }

    private const val MIN_TRUST = 0.05f
}
