package com.jarves.mh.agent.memory.chm

import com.jarves.mh.agent.memory.db.FrameDao
import com.jarves.mh.agent.memory.model.MemoryKind
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — Consolidation Worker.
 *
 * The "sleep" phase. Runs off the turn path every [triggerInterval] turns or
 * when the app backgrounds. Decays idle edges, prunes low-trust nodes, and
 * merges similar episodic frames into compact summaries — the pass that keeps
 * the prompt budget shrinking as a task runs long.
 *
 * Mobile adaptation: a plain coroutine rather than WorkManager, so CHM adds no
 * new dependency and stays under the app's existing lifecycle.
 */
class ConsolidationWorker(
    private val store: BiTemporalStore,
    private val graph: HebbianGraph,
    private val frames: EpisodicFrameRecorder,
    private val frameDao: FrameDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Summary(
        val edgesDecayed: Int = 0,
        val edgesPruned: Int = 0,
        val nodesDemoted: Int = 0,
        val nodesPruned: Int = 0,
        val framesCompacted: Int = 0,
    )

    private var turnsSinceLastRun = 0

    /** Call after every turn; the worker self-triggers on the interval. */
    fun tick(taskId: String, now: Long = clock()): Summary? {
        turnsSinceLastRun++
        return if (turnsSinceLastRun >= TRIGGER_INTERVAL) {
            turnsSinceLastRun = 0
            consolidate(taskId, now)
        } else {
            null
        }
    }

    /** Run the full sleep pass unconditionally. */
    fun consolidate(taskId: String, now: Long = clock()): Summary {
        val edgeSummary = graph.decayAndPrune(now)
        val nodes = store.current(taskId)
        var demoted = 0
        var pruned = 0
        nodes.forEach { node ->
            if (node.pinned) return@forEach
            // Passive decay: trust decays toward the manual-review floor.
            TrustGradient.passiveDecay(node, now)
            if (node.trust <= PRUNE_TRUST_BELOW && node.coActivationCount == 0) {
                store.supersede(node, now)
                pruned++
            } else {
                store.direct.updateScores(node.id, node.trust, node.salience)
                demoted++
            }
        }
        val compacted = compactFrames(taskId, now)
        return Summary(
            edgesDecayed = edgeSummary.decayed,
            edgesPruned = edgeSummary.pruned,
            nodesDemoted = demoted,
            nodesPruned = pruned,
            framesCompacted = compacted,
        )
    }

    /**
     * Merge the oldest frames into a summary memory and drop their rows. The
     * summary keeps the *outcome distribution*, which is what the watchdog
     * needs, while shedding the per-call digests.
     */
    private fun compactFrames(taskId: String, now: Long): Int {
        val old = frameDao.oldest(taskId, FRAMES_PER_SUMMARY)
        if (old.size < FRAMES_PER_SUMMARY) return 0
        val relevant = old.count { it.outcome == com.jarves.mh.agent.memory.model.FrameOutcome.RELEVANT }
        val total = old.size
        val earliest = old.minOf { it.timestamp }
        val summary = buildString {
            append("Over ${total} earlier steps, $relevant produced relevant results ")
            append("(${"%.0f".format(100f * relevant / total)}%). ")
            val tools = old.groupBy { it.tool }.entries.sortedByDescending { it.value.size }
            append("Most used: ${tools.take(3).joinToString { "${it.key}×${it.value.size}" }}.")
        }
        store.remember(
            content = summary,
            kind = MemoryKind.TOOL_DIGEST,
            taskId = taskId,
            now = now,
        ).also { store.direct.updateScores(it.id, 0.55f, 0.5f) }
        frameDao.deleteOlderThan(taskId, earliest + 1)
        return total
    }

    companion object {
        /** Consolidate roughly every N turns, or on background. */
        const val TRIGGER_INTERVAL = 50

        /** Frames rolled into one summary memory. */
        const val FRAMES_PER_SUMMARY = 40

        /** An unpinned, unused memory below this trust is pruned. */
        const val PRUNE_TRUST_BELOW = 0.15f
    }
}
