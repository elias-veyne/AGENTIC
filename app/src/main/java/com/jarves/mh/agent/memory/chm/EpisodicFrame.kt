package com.jarves.mh.agent.memory.chm

import com.jarves.mh.agent.memory.db.FrameDao
import com.jarves.mh.agent.memory.model.EpisodicFrame
import com.jarves.mh.agent.memory.model.FrameOutcome
import com.jarves.mh.agent.memory.model.FrameType

/**
 * CHM — Structured Episodic Event Frames (SEEM).
 *
 * Turns a raw turn into a queryable frame with provenance pointers and an
 * outcome. Being queryable *by outcome* — not just content — is what lets the
 * Drift Watchdog ask "show me every frame in the last 15 minutes whose outcome
 * was not RELEVANT" and see rumination as a pattern.
 */
class EpisodicFrameRecorder(private val dao: FrameDao, private val clock: () -> Long = System::currentTimeMillis) {

    private var counter = 0

    /** Build and persist one frame, returning it for the watchdog to score. */
    fun record(
        taskId: String,
        type: FrameType,
        tool: String,
        argsDigest: String,
        resultDigest: String,
        provenance: List<String>,
        linkedPlanStep: String? = null,
        outcome: FrameOutcome,
        salience: Float = defaultSalience(type, outcome),
        now: Long = clock(),
    ): EpisodicFrame {
        val frame = EpisodicFrame(
            id = 0,
            frameId = "evt_${++counter}",
            taskId = taskId,
            timestamp = now,
            type = type,
            tool = tool,
            argsDigest = argsDigest,
            resultDigest = resultDigest,
            provenance = provenance,
            salience = salience,
            linkedPlanStep = linkedPlanStep,
            outcome = outcome,
        )
        frame.id = dao.insert(frame)
        return frame
    }

    fun recent(taskId: String, sinceMillis: Long, limit: Int): List<EpisodicFrame> =
        dao.recent(taskId, sinceMillis, limit)

    fun count(taskId: String): Int = dao.count(taskId)

    /**
     * Salience heuristic. A frame is salient when it either advanced the plan
     * or produced a concrete, non-empty result; noise (empty greps, re-reads)
     * scores low and feeds the watchdog's first term.
     */
    fun defaultSalience(type: FrameType, outcome: FrameOutcome): Float = when (type) {
        FrameType.PLAN -> 0.95f
        FrameType.MESSAGE -> 0.80f
        FrameType.REASONING -> 0.55f
        FrameType.TOOL_CALL -> when (outcome) {
            FrameOutcome.RELEVANT -> 0.72f
            FrameOutcome.NEUTRAL -> 0.40f
            FrameOutcome.IRRELEVANT -> 0.18f
            FrameOutcome.ERROR -> 0.10f
        }
    }

    /** Detect a genuinely empty/no-op tool result — the signature of rumination. */
    fun classifyToolResult(toolName: String, summary: String): FrameOutcome {
        val text = summary.trim()
        if (text.isEmpty()) return FrameOutcome.IRRELEVANT
        if (text.startsWith("Error", ignoreCase = true) || text.startsWith("Failed", ignoreCase = true)) {
            return FrameOutcome.ERROR
        }
        val emptySignals = listOf(
            "no such file", "not found", "0 matches", "no matches", "empty",
            "no output", "nothing to", "already up to date",
        )
        return if (emptySignals.any { text.lowercase().contains(it) }) {
            FrameOutcome.IRRELEVANT
        } else {
            FrameOutcome.RELEVANT
        }
    }
}
