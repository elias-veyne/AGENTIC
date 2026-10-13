package com.jarves.mh.agent.memory.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — node access. Bi-temporal point-in-time reconstruction and FTS5 BM25
 * retrieval both live here; the Hebbian graph reads its endpoints through
 * [coActivations] and [byIds].
 */
class NodeDao(private val db: ChmDatabase) {

    fun writable(): SQLiteDatabase = db.writableDatabase

    /** Persist a new node, returning the row id bound into [node]. */
    fun insert(node: MemoryNode): Long {
        val id = db.writableDatabase.insert(
            ChmDatabase.TABLE_NODES, null, db.nodeValues(node, includeId = false),
        )
        return id
    }

    fun byId(id: Long): MemoryNode? = readableOne(
        "SELECT * FROM ${ChmDatabase.TABLE_NODES} WHERE id = ?",
        arrayOf(id.toString()),
    )

    fun byIds(ids: List<Long>): List<MemoryNode> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return readableList(
            "SELECT * FROM ${ChmDatabase.TABLE_NODES} WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
        )
    }

    /** The current belief set: not yet superseded on the system clock. */
    fun current(taskId: String): List<MemoryNode> = readableList(
        "SELECT * FROM ${ChmDatabase.TABLE_NODES} WHERE task_id = ? AND expired_at IS NULL ORDER BY salience DESC",
        arrayOf(taskId),
    )

    /**
     * Point-in-time reconstruction (ARIADNE). Returns the memories that were
     * the agent's working set at [asOf]: created by then and not yet
     * superseded. This is the query that answers "what did I believe about
     * SettingsScreen ten minutes ago?".
     */
    fun validAt(taskId: String, asOf: Long): List<MemoryNode> = readableList(
        """
        SELECT * FROM ${ChmDatabase.TABLE_NODES}
        WHERE task_id = ? AND created_at <= ? AND (expired_at IS NULL OR expired_at > ?)
        ORDER BY salience DESC, trust DESC
        """.trimIndent(),
        arrayOf(taskId, asOf.toString(), asOf.toString()),
    )

    /** FTS5 BM25 ranking — zero-inference semantic-ish retrieval. */
    fun search(taskId: String, query: String, limit: Int): List<MemoryNode> {
        if (query.isBlank()) return emptyList()
        // BM25 scores lower = better in SQLite, hence ASC.
        val rows = db.readableDatabase.rawQuery(
            """
            SELECT n.*, ${ChmDatabase.TABLE_NODES_FTS}.rank AS fts_rank
            FROM ${ChmDatabase.TABLE_NODES_FTS}
            JOIN ${ChmDatabase.TABLE_NODES} n ON n.id = ${ChmDatabase.TABLE_NODES_FTS}.rowid
            WHERE ${ChmDatabase.TABLE_NODES_FTS} MATCH ? AND n.task_id = ?
            ORDER BY fts_rank ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(ftsQuery(query), taskId, limit.toString()),
        )
        return rows.use { sequence { while (it.moveToNext()) yield(db.nodeFromCursor(it)) }.toList() }
    }

    /** Escape a free-text query into an FTS5 MATCH expression. */
    private fun ftsQuery(query: String): String =
        query.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { word ->
            // Trailing * makes each token a prefix match; quotes neutralize syntax chars.
            "\"" + word.replace("\"", "") + "\"*"
        }

    /** Hebbian: nodes whose co-activation weight with [id] is above [minWeight]. */
    fun coActivations(id: Long, minWeight: Int, limit: Int): List<MemoryNode> {
        val rows = db.readableDatabase.rawQuery(
            """
            SELECT n.* FROM ${ChmDatabase.TABLE_EDGES} e
            JOIN ${ChmDatabase.TABLE_NODES} n
            ON n.id = CASE WHEN e.source_id = ? THEN e.target_id ELSE e.source_id END
            WHERE (e.source_id = ? OR e.target_id = ?) AND e.co_activation_count >= ?
            ORDER BY e.co_activation_count DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(id.toString(), id.toString(), id.toString(), minWeight.toString(), limit.toString()),
        )
        return rows.use { sequence { while (it.moveToNext()) yield(db.nodeFromCursor(it)) }.toList() }
    }

    /** Promote/demote trust and salience without rewriting the row. */
    fun updateScores(id: Long, trust: Float, salience: Float) {
        val values = ContentValues().apply {
            put("trust", trust.coerceIn(0f, 1f))
            put("salience", salience.coerceIn(0f, 1f))
        }
        db.writableDatabase.update(
            ChmDatabase.TABLE_NODES, values, "id = ?", arrayOf(id.toString()),
        )
    }

    fun bumpCoActivation(id: Long, now: Long) {
        val values = ContentValues().apply {
            put("co_activation_count", 1)
            put("last_coactivated_at", now)
        }
        db.writableDatabase.update(
            ChmDatabase.TABLE_NODES, values, "id = ?",
            arrayOf(id.toString()),
        )
    }

    /** Supersede on the system clock; the real-world clock stays intact. */
    fun expire(id: Long, now: Long) {
        val values = ContentValues().apply { put("expired_at", now) }
        db.writableDatabase.update(
            ChmDatabase.TABLE_NODES, values, "id = ?", arrayOf(id.toString()),
        )
    }

    fun delete(id: Long) {
        db.writableDatabase.delete(
            ChmDatabase.TABLE_NODES, "id = ?", arrayOf(id.toString()),
        )
    }

    /** Consolidation walks low-trust, low-salience, unpinned candidates. */
    fun decayCandidates(taskId: String, limit: Int): List<MemoryNode> = readableList(
        """
        SELECT * FROM ${ChmDatabase.TABLE_NODES}
        WHERE task_id = ? AND pinned = 0 AND expired_at IS NULL
        ORDER BY trust ASC, salience ASC
        LIMIT ?
        """.trimIndent(),
        arrayOf(taskId, limit.toString()),
    )

    private fun readableList(sql: String, args: Array<String>): List<MemoryNode> =
        db.readableDatabase.rawQuery(sql, args).use { c ->
            sequence { while (c.moveToNext()) yield(db.nodeFromCursor(c)) }.toList()
        }

    private fun readableOne(sql: String, args: Array<String>): MemoryNode? =
        db.readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) db.nodeFromCursor(c) else null }
}
