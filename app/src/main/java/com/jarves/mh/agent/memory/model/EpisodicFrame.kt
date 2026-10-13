package com.jarves.mh.agent.memory.model

/**
 * CHM — one SEEM-style Episodic Event Frame.
 *
 * Instead of logging raw turns, each interaction is normalized into a frame
 * with provenance pointers and an [outcome]. The [outcome] is what makes the
 * Drift Watchdog's query possible: "frames from the last 15 minutes where
 * outcome != RELEVANT" surfaces rumination directly, which raw logs cannot.
 */
data class EpisodicFrame(
    val id: Long,
    /** Stable external id, e.g. "evt_042". */
    val frameId: String,
    val taskId: String,
    val timestamp: Long,
    val type: FrameType,
    /** Tool name for TOOL_CALL frames; empty otherwise. */
    val tool: String,
    /** Lossy digest of the invocation, e.g. "dark_mode in *.kt". */
    val argsDigest: String,
    /** Lossy digest of the result, e.g. "3 matches: SettingsScreen.kt:45". */
    val resultDigest: String,
    /** Provenance pointers, e.g. ["file:SettingsScreen.kt", "file:Prefs.kt"]. */
    val provenance: List<String>,
    /** Normalized 0..1 importance; feeds the Drift Watchdog's first term. */
    val salience: Float,
    /** Which plan step this frame served, if any. */
    val linkedPlanStep: String?,
    val outcome: FrameOutcome,
)

enum class FrameType { TOOL_CALL, REASONING, PLAN, MESSAGE }

/**
 * The drift-watchdog signal. [RELEVANT] means the frame moved the task toward
 * the active plan step; anything else is a candidate symptom of drift.
 */
enum class FrameOutcome { RELEVANT, IRRELEVANT, ERROR, NEUTRAL }
