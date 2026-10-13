package com.jarves.mh.agent.memory.db

import com.jarves.mh.agent.memory.model.EpisodicFrame

/**
 * CHM — Episodic Event Frame access (SEEM). Frames are queryable by
 * [com.jarves.mh.agent.memory.model.FrameOutcome] and recency, which is what
 * the Drift Watchdog's trigger logic reads.
 */
class FrameDao(private val db: ChmDatabase) {

    fun insert(frame: EpisodicFrame): Long =
        db.writableDatabase.insert(
            ChmDatabase.TABLE_FRAMES, null, db.frameValues(frame, includeId = false),
        )

    /** The watchdog's primary signal: recent non-relevant activity. */
    fun recent(taskId: String, sinceMillis: Long, limit: Int): List<EpisodicFrame> {
        val c = db.readableDatabase.rawQuery(
            """
            SELECT * FROM ${ChmDatabase.TABLE_FRAMES}
            WHERE task_id = ? AND timestamp >= ?
            ORDER BY timestamp DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(taskId, sinceMillis.toString(), limit.toString()),
        )
        return c.use { sequence { while (it.moveToNext()) yield(db.frameFromCursor(it)) }.toList() }
    }

    /** Count frames in the window whose outcome is not RELEVANT. */
    fun countNonRelevant(taskId: String, sinceMillis: Long): Int {
        val c = db.readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM ${ChmDatabase.TABLE_FRAMES}
            WHERE task_id = ? AND timestamp >= ? AND outcome != ?
            """.trimIndent(),
            arrayOf(taskId, sinceMillis.toString(),
                com.jarves.mh.agent.memory.model.FrameOutcome.RELEVANT.name),
        )
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun count(taskId: String): Int {
        val c = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${ChmDatabase.TABLE_FRAMES} WHERE task_id = ?",
            arrayOf(taskId),
        )
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Consolidation summarizes the oldest frames and frees their rows. */
    fun oldest(taskId: String, limit: Int): List<EpisodicFrame> {
        val c = db.readableDatabase.rawQuery(
            """
            SELECT * FROM ${ChmDatabase.TABLE_FRAMES}
            WHERE task_id = ? ORDER BY timestamp ASC LIMIT ?
            """.trimIndent(),
            arrayOf(taskId, limit.toString()),
        )
        return c.use { sequence { while (it.moveToNext()) yield(db.frameFromCursor(it)) }.toList() }
    }

    fun deleteOlderThan(taskId: String, cutoffMillis: Long): Int =
        db.writableDatabase.delete(
            ChmDatabase.TABLE_FRAMES,
            "task_id = ? AND timestamp < ?",
            arrayOf(taskId, cutoffMillis.toString()),
        )
}
