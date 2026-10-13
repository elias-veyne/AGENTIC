package com.jarves.mh.agent.memory.db

import android.content.ContentValues
import com.jarves.mh.agent.memory.model.MemoryEdge

/**
 * CHM — edge access. An edge is a first-class memory (its own fact, trust, and
 * bi-temporal fields) that also carries the Hebbian co-activation weight.
 */
class EdgeDao(private val db: ChmDatabase) {

    /** Upsert an undirected co-activation edge, creating it on first use. */
    fun upsertCoActivation(sourceId: Long, targetId: Long, taskId: String, now: Long, delta: Int): MemoryEdge? {
        if (sourceId == targetId) return null
        val (a, b) = if (sourceId < targetId) sourceId to targetId else targetId to sourceId
        val existing = between(a, b)
        val edge = if (existing != null) {
            existing.copy(
                coActivationCount = (existing.coActivationCount + delta).coerceAtMost(
                    com.jarves.mh.agent.memory.model.MemoryEdge.MAX_WEIGHT.toInt(),
                ),
                lastCoactivatedAt = if (delta > 0) now else existing.lastCoactivatedAt,
            )
        } else {
            MemoryEdge(
                id = 0,
                sourceId = a,
                targetId = b,
                fact = "co_activated_with",
                trustScore = 0.5f,
                createdAt = now,
                expiredAt = null,
                validAt = now,
                invalidAt = null,
                coActivationCount = delta.coerceAtLeast(0),
                lastCoactivatedAt = if (delta > 0) now else 0L,
                taskId = taskId,
            )
        }
        val values = db.edgeValues(edge, includeId = existing != null)
        if (existing != null) {
            db.writableDatabase.update(
                ChmDatabase.TABLE_EDGES, values, "id = ?", arrayOf(edge.id.toString()),
            )
        } else {
            edge.id = db.writableDatabase.insert(ChmDatabase.TABLE_EDGES, null, values)
        }
        return edge
    }

    private fun between(a: Long, b: Long): MemoryEdge? {
        val c = db.readableDatabase.rawQuery(
            "SELECT * FROM ${ChmDatabase.TABLE_EDGES} WHERE source_id = ? AND target_id = ? LIMIT 1",
            arrayOf(a.toString(), b.toString()),
        )
        return c.use { if (it.moveToFirst()) db.edgeFromCursor(it) else null }
    }

    /** Every edge touching [nodeId], strongest first — the graph traversal step. */
    fun edgesFor(nodeId: Long, limit: Int): List<MemoryEdge> {
        val c = db.readableDatabase.rawQuery(
            """
            SELECT * FROM ${ChmDatabase.TABLE_EDGES}
            WHERE source_id = ? OR target_id = ?
            ORDER BY co_activation_count DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(nodeId.toString(), nodeId.toString(), limit.toString()),
        )
        return c.use { sequence { while (it.moveToNext()) yield(db.edgeFromCursor(it)) }.toList() }
    }

    /** Consolidation pass: everything weak enough to decay or prune. */
    fun decayCandidates(limit: Int): List<MemoryEdge> {
        val c = db.readableDatabase.rawQuery(
            """
            SELECT * FROM ${ChmDatabase.TABLE_EDGES}
            WHERE expired_at IS NULL AND co_activation_count < ?
            ORDER BY co_activation_count ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                com.jarves.mh.agent.memory.model.MemoryEdge.MAX_WEIGHT.toInt().toString(),
                limit.toString(),
            ),
        )
        return c.use { sequence { while (it.moveToNext()) yield(db.edgeFromCursor(it)) }.toList() }
    }

    fun applyDecay(edge: MemoryEdge, newWeight: Int, now: Long) {
        val values = ContentValues().apply {
            put("co_activation_count", newWeight.coerceAtLeast(0))
            if (newWeight <= 0) put("last_coactivated_at", now)
        }
        db.writableDatabase.update(
            ChmDatabase.TABLE_EDGES, values, "id = ?", arrayOf(edge.id.toString()),
        )
    }

    fun prune(edge: MemoryEdge) {
        db.writableDatabase.delete(
            ChmDatabase.TABLE_EDGES, "id = ?", arrayOf(edge.id.toString()),
        )
    }

    fun count(): Int {
        val c = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${ChmDatabase.TABLE_EDGES}", null,
        )
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
}
