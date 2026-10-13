package com.jarves.mh.agent.memory.chm

import com.jarves.mh.agent.memory.db.ChmDatabase
import com.jarves.mh.agent.memory.db.NodeDao
import com.jarves.mh.agent.memory.model.MemoryKind
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — Bi-Temporal Edge Memory (ARIADNE).
 *
 * Every memory is a node and every relationship a first-class edge carrying
 * two time axes: system time ([created_at]/[expired_at]) and real-world
 * validity ([valid_at]/[invalid_at]). Point-in-time reconstruction is the
 * point: the agent can ask what it believed about a file *as of* a prior
 * moment and get that exact prior state instead of a noisy current retrieval.
 */
class BiTemporalStore(private val dao: NodeDao) {

    /**
     * Direct DAO access for the consolidation worker, which writes trust and
     * salience back without going through the semantic helpers above.
     */
    val direct: NodeDao get() = dao

    /** Record a new fact. Pinned (user request) memories never decay. */
    fun remember(content: String, kind: MemoryKind, taskId: String, now: Long): MemoryNode {
        val node = MemoryNode(
            id = 0,
            content = content,
            kind = kind,
            trust = kind.initialTrust,
            createdAt = now,
            expiredAt = null,
            validAt = now,
            invalidAt = null,
            salience = kind.initialTrust,
            coActivationCount = 0,
            lastCoactivatedAt = 0L,
            taskId = taskId,
            pinned = kind == MemoryKind.MISSION,
        )
        node.id = dao.insert(node)
        return node
    }

    /** The current working set for a task, highest salience first. */
    fun current(taskId: String): List<MemoryNode> = dao.current(taskId)

    /**
     * Point-in-time reconstruction. Returns the memories that constituted the
     * agent's working set at [asOf]. Superseded facts are still visible here
     * — that is precisely the anti-drift property, and it is what a
     * current-only store cannot give you.
     */
    fun reconstructAt(taskId: String, asOf: Long): List<MemoryNode> = dao.validAt(taskId, asOf)

    /** BM25 retrieval over node content (FTS5 — no embeddings, no inference cost). */
    fun search(taskId: String, query: String, limit: Int): List<MemoryNode> =
        dao.search(taskId, query, limit)

    /** Supersede a fact on the system clock. The real-world axis is untouched. */
    fun supersede(node: MemoryNode, now: Long) {
        node.expiredAt = now
        dao.expire(node.id, now)
    }

    /** Revoke a fact in the real world (it stopped being true). */
    fun invalidate(node: MemoryNode, now: Long) {
        node.invalidAt = now
        dao.updateScores(node.id, node.trust, node.salience)
        // A fact that is no longer true must not dominate retrieval.
        dao.expire(node.id, now)
    }

    fun byId(id: Long): MemoryNode? = dao.byId(id)

    fun byIds(ids: List<Long>): List<MemoryNode> = dao.byIds(ids)

    /** Co-activation neighbours above the traversal weight floor. */
    fun coActivationsOf(node: MemoryNode, minWeight: Int, limit: Int): List<MemoryNode> =
        dao.coActivations(node.id, minWeight, limit)

    companion object {
        /** A task is long enough to matter once its history spans this many nodes. */
        const val MIN_NODES_FOR_RECONSTRUCTION = 8
    }
}

/** DI seam so tests and the engine can share one helper instance. */
internal fun chmStore(db: ChmDatabase): BiTemporalStore = BiTemporalStore(NodeDao(db))
