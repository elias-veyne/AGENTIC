package com.jarves.mh.agent.memory.chm

import com.jarves.mh.agent.memory.db.EdgeDao
import com.jarves.mh.agent.memory.model.MemoryEdge
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — Hebbian Co-Activation Graph (HeLa-Mem).
 *
 * The anti-drift engine. When two memories are retrieved and used together in
 * the same reasoning step the edge between them strengthens; an edge that is
 * never co-activated decays. The graph thereby learns which memories belong
 * together *for this codebase and task type*. After the agent has worked on
 * SettingsScreen a few times the edges between SettingsScreen.kt, Prefs.kt,
 * dark_mode_toggle and user_preference_persistence are strong, so the next
 * task follows the learned associative path directly instead of searching —
 * pattern completion rather than retrieval.
 *
 * Mobile adaptation: [coActivationCount] is the weight proxy. Pure integer
 * arithmetic, no embeddings required for traversal.
 */
class HebbianGraph(private val dao: EdgeDao) {

    /**
     * Strengthen every pair of [nodes] that were retrieved and used together.
     * Called after each turn with the set actually injected into context.
     */
    fun strengthen(nodes: List<MemoryNode>, taskId: String, now: Long) {
        val active = nodes.filter { it.id > 0 }
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                dao.upsertCoActivation(active[i].id, active[j].id, taskId, now, LEARNING_RATE.toInt())
            }
        }
        active.forEach { node ->
            node.coActivationCount += 1
            node.lastCoactivatedAt = now
        }
    }

    /**
     * The decay/prune pass, run during consolidation ("sleep"). Edges whose
     * last co-activation predates [decayThreshold] lose weight; edges below
     * the pruning threshold are deleted outright.
     */
    fun decayAndPrune(now: Long, batchSize: Int = DEFAULT_BATCH): Summary {
        var decayed = 0
        var pruned = 0
        var cursor = 0
        while (cursor < batchSize) {
            val candidates = dao.decayCandidates(batchSize)
            if (candidates.isEmpty()) break
            candidates.forEach { edge ->
                val age = now - edge.lastCoactivatedAt
                if (age > DECAY_THRESHOLD_MS) {
                    val newWeight = (edge.coActivationCount * DECAY_FACTOR).toInt()
                    dao.applyDecay(edge, newWeight, now)
                    decayed++
                }
                if (edge.coActivationCount < MemoryEdge.PRUNING_THRESHOLD.toInt()) {
                    dao.prune(edge)
                    pruned++
                }
            }
            cursor += candidates.size
            if (candidates.size < batchSize) break
        }
        return Summary(decayed = decayed, pruned = pruned)
    }

    /** Associative traversal: one hop from [seed], strongest first. */
    fun neighbours(seed: MemoryNode, limit: Int): List<MemoryEdge> = dao.edgesFor(seed.id, limit)

    data class Summary(val decayed: Int, val pruned: Int)

    companion object {
        /** Weight added per co-retrieval. Small, so a single task can't over-train. */
        const val LEARNING_RATE = 1.0

        /** Fraction of weight an idle edge keeps each consolidation pass. */
        const val DECAY_FACTOR = 0.85

        /** An edge unused for this long starts to decay. */
        const val DECAY_THRESHOLD_MS = 5 * 60 * 1000L

        private const val DEFAULT_BATCH = 256
    }
}
