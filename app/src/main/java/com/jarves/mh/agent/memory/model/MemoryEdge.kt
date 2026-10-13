package com.jarves.mh.agent.memory.model

/**
 * CHM — a first-class relationship between two memories.
 *
 * Edges are nodes in their own right (ARIADNE): they carry their own fact and
 * their own bi-temporal validity, so "I believed X causes Y" can be expired
 * without expiring either endpoint. The Hebbian machinery trains on this
 * table — [coActivationCount] is the learned associative weight that lets
 * retrieval follow a well-worn path between memories instead of re-searching.
 */
data class MemoryEdge(
    /** Assigned by the store on insert; mutable so DAOs can backfill it. */
    var id: Long,
    val sourceId: Long,
    val targetId: Long,
    /** The relationship itself, e.g. "declared_in" or "causes". */
    val fact: String,
    val trustScore: Float,
    /** System clock millis — when the edge was created. */
    val createdAt: Long,
    /** System clock millis — null while current. */
    var expiredAt: Long?,
    /** Real-world clock millis. */
    val validAt: Long,
    var invalidAt: Long?,
    /** Hebbian weight proxy. Strengthened on co-retrieval, decays otherwise. */
    var coActivationCount: Int,
    /** System clock millis of the last co-activation. */
    var lastCoactivatedAt: Long,
    val taskId: String,
) {
    companion object {
        /** Cap a learned edge weight so an old, over-trained path can't dominate. */
        const val MAX_WEIGHT: Float = 32.0f

        /** Edges strictly weaker than this are pruned during consolidation. */
        const val PRUNING_THRESHOLD: Float = 1.0f
    }
}
