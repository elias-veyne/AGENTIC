package com.jarves.mh.agent.memory.chm

import com.jarves.mh.agent.memory.model.EpisodicFrame
import com.jarves.mh.agent.memory.model.FrameOutcome
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — the Drift Watchdog.
 *
 * Combines salience decay, Hebbian weight, and temporal frame analysis into a
 * single composite signal. Above [THRESHOLD] the harness injects a
 * forced-schema re-anchor — not a scolding, but a structured output
 * requirement that shows the agent its own low-trust activity. That
 * metacognitive feedback ("here is what you have been doing that does not
 * matter") is far more effective than repeating the objective.
 *
 * DRIFT_SCORE = w1 * (1 - salience_of_current_action)
 *             + w2 * (turns_since_plan_advance / 10)
 *             + w3 * (hebbian_weight_of_current_focus_path / max_possible)
 *             + w4 * (tokens_since_refocus / 25000)
 */
class DriftWatchdog(
    private val frames: EpisodicFrameRecorder,
    private val graph: HebbianGraph,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Mutable turn-level counters the engine advances each turn. */
    data class Counters(
        var turnsSincePlanAdvance: Int = 0,
        var tokensSinceRefocus: Long = 0L,
        var lastRefocusAt: Long = 0L,
    )

    /** The inputs needed to score a turn. */
    data class Context(
        val taskId: String,
        val objective: MemoryNode?,
        val activeStep: MemoryNode?,
        val focusPath: List<MemoryNode>,
        val counters: Counters,
        /** High-trust memories relevant to the active step. */
        val highTrust: List<MemoryNode>,
        /** Low-trust memories the watchdog will mark for pruning. */
        val lowTrust: List<MemoryNode>,
    )

    /** The composite score plus the individual signals that produced it. */
    data class Score(
        val value: Float,
        val salience: Float,
        val planStalled: Float,
        val hebbianPath: Float,
        val tokens: Float,
        val triggered: Boolean,
    )

    fun evaluate(ctx: Context): Score {
        val now = clock()

        // w1: current action salience. The most recent TOOL_CALL frame is the
        // current action; a string of low-salience frames is drift's fingerprint.
        val recent = frames.recent(ctx.taskId, now - DRIFT_WINDOW_MS, MAX_SIGNAL_FRAMES)
        val salience = currentSalience(recent)
        val s1 = (1f - salience) * W_SALIENCE

        // w2: turns since the plan last advanced.
        val s2 = (ctx.counters.turnsSincePlanAdvance / 10f).coerceIn(0f, 1f) * W_PLAN_STALLED

        // w3: strength of the Hebbian focus path — a well-trained path means
        // the agent is working inside learned, relevant territory.
        val hebbian = hebbianStrength(ctx.focusPath)
        val s3 = (1f - hebbian) * W_HEBBIAN

        // w4: tokens burned since the last refocus.
        val s4 = (ctx.counters.tokensSinceRefocus / REFOCUS_TOKEN_BUDGET.toFloat())
            .coerceIn(0f, 1f) * W_TOKENS

        val value = (s1 + s2 + s3 + s4).coerceIn(0f, 1f)
        return Score(
            value = value,
            salience = salience,
            planStalled = s2 / W_PLAN_STALLED,
            hebbianPath = hebbian,
            tokens = s4 / W_TOKENS,
            triggered = value > THRESHOLD,
        )
    }

    /** Mean salience of the most recent frames, weighted toward recency. */
    private fun currentSalience(recent: List<EpisodicFrame>): Float {
        if (recent.isEmpty()) return 0.5f
        // Recency weighting: later frames (closer to now) count more.
        var weightSum = 0f
        var acc = 0f
        recent.forEachIndexed { index, frame ->
            val w = (index + 1).toFloat() / recent.size
            acc += frame.salience * w
            weightSum += w
        }
        return (acc / weightSum).coerceIn(0f, 1f)
    }

    /** Fraction of the focus path backed by a strong learned edge. */
    private fun hebbianStrength(path: List<MemoryNode>): Float {
        if (path.size < 2) return 0.1f
        val strong = path.count { it.coActivationCount >= STRONG_EDGE_WEIGHT }
        return (strong.toFloat() / path.size).coerceIn(0f, 1f)
    }

    /**
     * Build the forced-schema re-anchor block. The key novelty is that it
     * shows the agent its own low-trust activity — metacognitive feedback.
     */
    fun reAnchor(score: Score, ctx: Context): String = buildString {
        appendLine("[CHM — RE-ANCHOR ACTIVATED]")
        appendLine("DRIFT_SCORE: ${"%.2f".format(score.value)} (threshold $THRESHOLD)")
        appendLine(
            "SIGNALS: salience_${label(score.salience)}(" +
                "${"%.2f".format(score.salience)}) + plan_stalled(" +
                "${ctx.counters.turnsSincePlanAdvance} turns) + hebbian_path_${label(score.hebbianPath)}(" +
                "${"%.2f".format(score.hebbianPath)}) + tokens_since_refocus(" +
                "${ctx.counters.tokensSinceRefocus})",
        )
        appendLine()
        appendLine("OBJECTIVE (trust=1.0): ${ctx.objective?.content ?: "(none recorded)"}")
        appendLine("ACTIVE STEP (trust=0.8): ${ctx.activeStep?.content ?: "(none)"}")
        appendLine("HIGH-TRUST MEMORIES THIS STEP:")
        if (ctx.highTrust.isEmpty()) {
            appendLine("  - (none)")
        } else {
            ctx.highTrust.take(MAX_ANCHOR_MEMORIES).forEach { node ->
                appendLine("  - ${node.content} (trust=${"%.2f".format(node.trust)})")
            }
        }
        appendLine("LOW-TRUST ACTIVITY (will be pruned):")
        if (ctx.lowTrust.isEmpty()) {
            appendLine("  - (none)")
        } else {
            ctx.lowTrust.take(MAX_ANCHOR_MEMORIES).forEach { node ->
                appendLine("  - ${node.content} (trust=${"%.2f".format(node.trust)})")
            }
        }
        appendLine()
        appendLine("Respond ONLY:")
        appendLine("DECISION: <one sentence of what you now know>")
        appendLine("NEXT ACTION: <a concrete edit, or explicit plan revision>")
    }

    private fun label(value: Float): String = if (value < 0.4f) "low" else if (value < 0.7f) "mid" else "high"

    companion object {
        /** Above this, the harness forces a re-anchor. */
        const val THRESHOLD = 0.65f

        // Composite weights — must sum to ~1.0 so the score stays normalized.
        private const val W_SALIENCE = 0.35f
        private const val W_PLAN_STALLED = 0.25f
        private const val W_HEBBIAN = 0.20f
        private const val W_TOKENS = 0.20f

        /** The window the watchdog scans for rumination. */
        const val DRIFT_WINDOW_MS = 15 * 60 * 1000L

        /** Tokens since the last refocus that saturate the w4 term. */
        const val REFOCUS_TOKEN_BUDGET = 25_000L

        /** Frames examined for the salience signal. */
        const val MAX_SIGNAL_FRAMES = 12

        /** Co-activations needed for a focus-path node to count as "strong". */
        const val STRONG_EDGE_WEIGHT = 3

        /** Memories listed in each half of the re-anchor block. */
        const val MAX_ANCHOR_MEMORIES = 4

        /** A non-relevant frame counts as evidence the plan did not advance. */
        fun isNoise(frame: EpisodicFrame): Boolean =
            frame.outcome == FrameOutcome.IRRELEVANT || frame.outcome == FrameOutcome.ERROR
    }
}
