package com.jarves.mh.agent.memory

import android.content.Context
import android.util.Log
import com.jarves.mh.agent.memory.chm.BiTemporalStore
import com.jarves.mh.agent.memory.chm.ConsolidationWorker
import com.jarves.mh.agent.memory.chm.ContextCompiler
import com.jarves.mh.agent.memory.chm.EpisodicFrameRecorder
import com.jarves.mh.agent.memory.chm.HebbianGraph
import com.jarves.mh.agent.memory.chm.TrustGradient
import com.jarves.mh.agent.memory.db.ChmDatabase
import com.jarves.mh.agent.memory.db.EdgeDao
import com.jarves.mh.agent.memory.db.FrameDao
import com.jarves.mh.agent.memory.db.NodeDao
import com.jarves.mh.agent.memory.model.EpisodicFrame
import com.jarves.mh.agent.memory.model.FrameType
import com.jarves.mh.agent.memory.model.MemoryKind
import com.jarves.mh.agent.memory.model.MemoryNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CHM — the single facade the rest of the app talks to.
 *
 * Wires the four interlocking mechanisms — Bi-Temporal Edge Memory, the
 * Hebbian Co-Activation Graph, Trust-Gradient promotion, and SEEM episodic
 * frames — behind the turn pipeline:
 *
 *   1. INGEST     -> [ingestToolCall] / [ingestReasoning] / [ingestPlanStep]
 *   2. RETRIEVE   -> [compileContext] (BM25 + Hebbian + recency, trust-ranked)
 *   3. DRIFT      -> [compileContext] also runs the watchdog and may re-anchor
 *   4. POST-TURN  -> [postTurnUpdate] strengthens edges and ticks consolidation
 *
 * Every call is best-effort: a memory failure must never break the chat, so
 * each public method swallows its own errors and degrades to a no-op.
 */
class ChmEngine private constructor(
    private val store: BiTemporalStore,
    private val graph: HebbianGraph,
    private val recorder: EpisodicFrameRecorder,
    private val compiler: ContextCompiler,
    private val consolidator: ConsolidationWorker,
    private val frameDao: FrameDao,
    private val taskId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val counters = com.jarves.mh.agent.memory.chm.DriftWatchdog.Counters()

    /** The compiled context block for the outgoing prompt, or "" if CHM is empty. */
    suspend fun compileContext(query: String): ContextCompiler.Compiled = withContext(Dispatchers.IO) {
        runCatching { compiler.compile(taskId, query, counters = counters) }
            .getOrElse { ContextCompiler.Compiled("", emptyList(), false, 0f) }
    }

    /** Record the user's original request — trust 1.0, pinned, never decays. */
    suspend fun ingestMission(objective: String) = write {
        runCatching {
            store.remember(objective, MemoryKind.MISSION, taskId, System.currentTimeMillis())
        }
    }

    /** Record a plan step the agent committed to. */
    suspend fun ingestPlanStep(step: String, parentObjective: String? = null) = write {
        runCatching {
            store.remember(step, MemoryKind.PLAN_STEP, taskId, System.currentTimeMillis())
        }
    }

    /** Record a tool call as a SEEM frame and a trust-0.6 tool digest. */
    suspend fun ingestToolCall(
        tool: String,
        argsDigest: String,
        resultDigest: String,
        provenance: List<String> = emptyList(),
        linkedPlanStep: String? = null,
    ) = write {
        runCatching {
            val outcome = recorder.classifyToolResult(tool, resultDigest)
            recorder.record(
                taskId = taskId,
                type = FrameType.TOOL_CALL,
                tool = tool,
                argsDigest = argsDigest,
                resultDigest = resultDigest,
                provenance = provenance,
                linkedPlanStep = linkedPlanStep,
                outcome = outcome,
            )
            store.remember(
                content = "$tool → $resultDigest",
                kind = MemoryKind.TOOL_DIGEST,
                taskId = taskId,
                now = System.currentTimeMillis(),
            )
        }
    }

    /** Record reasoning output (assistant text) at inference trust. */
    suspend fun ingestReasoning(summary: String, linkedPlanStep: String? = null) = write {
        runCatching {
            recorder.record(
                taskId = taskId,
                type = FrameType.REASONING,
                tool = "",
                argsDigest = "",
                resultDigest = summary.take(MAX_DIGEST_CHARS),
                provenance = emptyList(),
                linkedPlanStep = linkedPlanStep,
                outcome = com.jarves.mh.agent.memory.model.FrameOutcome.NEUTRAL,
            )
        }
    }

    /**
     * Post-turn housekeeping: strengthen the Hebbian edges between the
     * memories that were actually injected into context, then tick the
     * consolidation worker (the "sleep" phase).
     */
    suspend fun postTurnUpdate(injected: List<MemoryNode>) = write {
        runCatching {
            graph.strengthen(injected, taskId, System.currentTimeMillis())
            counters.turnsSincePlanAdvance++
            consolidator.tick(taskId)
        }
    }

    /** Account for tokens spent this turn (feeds the watchdog's w4 term). */
    fun accountTokens(tokens: Long) {
        counters.tokensSinceRefocus += tokens
    }

    /** The watchdog fired this turn — reset the counters it saturated. */
    fun noteRefocus() {
        counters.turnsSincePlanAdvance = 0
        counters.tokensSinceRefocus = 0
    }

    /** Snapshot of recent frames for diagnostics/UI. */
    suspend fun recentFrames(limit: Int = 20): List<EpisodicFrame> = withContext(Dispatchers.IO) {
        runCatching { recorder.recent(taskId, 0, limit) }.getOrElse { emptyList() }
    }

    /** Force a consolidation pass (e.g. on app background). */
    suspend fun consolidate() = write {
        runCatching { consolidator.consolidate(taskId) }
    }

    private suspend fun <T> write(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "CHM write failed for task $taskId", t)
                null
            }
        }
    }

    companion object {
        private const val TAG = "ChmEngine"
        private const val MAX_DIGEST_CHARS = 240

        private val cache = mutableMapOf<String, ChmEngine>()

        /**
         * The engine for [taskId], creating its isolated SQLite graph on first
         * use. Cached so a task keeps one engine for its lifetime.
         */
        fun forTask(context: Context, taskId: String): ChmEngine {
            val key = taskId.lowercase()
            return synchronized(cache) {
                cache.getOrPut(key) {
                    val db = ChmDatabase(context.applicationContext, taskId)
                    val store = BiTemporalStore(NodeDao(db))
                    val graph = HebbianGraph(EdgeDao(db))
                    val recorder = EpisodicFrameRecorder(FrameDao(db))
                    val compiler = ContextCompiler(store, recorder, graph)
                    val consolidator = ConsolidationWorker(store, graph, recorder, FrameDao(db))
                    ChmEngine(store, graph, recorder, compiler, consolidator, FrameDao(db), taskId)
                }
            }
        }
    }
}
