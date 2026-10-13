package com.jarves.mh.agent.memory.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarves.mh.agent.memory.model.EpisodicFrame
import com.jarves.mh.agent.memory.model.FrameOutcome
import com.jarves.mh.agent.memory.model.FrameType
import com.jarves.mh.agent.memory.model.MemoryEdge
import com.jarves.mh.agent.memory.model.MemoryKind
import com.jarves.mh.agent.memory.model.MemoryNode

/**
 * CHM — mobile-adapted persistence.
 *
 * The architecture calls for Neo4j-style graph storage; on Android that maps
 * cleanly onto three SQLite tables where edges are first-class rows with
 * foreign keys to their endpoints. Retrieval uses SQLite's built-in FTS5
 * (available since API 21, so safe at our minSdk 28) for BM25 ranking — no
 * embeddings, no inference cost. Everything the graph traversal needs is
 * integer/float arithmetic over [ChmDatabase.TABLE_EDGES].
 *
 * The helper is keyed by task id so each conversation gets an isolated memory
 * graph; the schema is identical across them.
 */
class ChmDatabase(context: Context, taskId: String) : SQLiteOpenHelper(
    /* name must be task-scoped: an in-memory or shared db would mix graphs. */
    context, NAME_PREFIX + sanitize(taskId), null, VERSION,
) {
    companion object {
        private const val VERSION = 1
        private const val NAME_PREFIX = "chm_"

        const val TABLE_NODES = "memory_nodes"
        const val TABLE_EDGES = "memory_edges"
        const val TABLE_FRAMES = "episodic_frames"
        const val TABLE_NODES_FTS = "memory_nodes_fts"

        private fun sanitize(taskId: String) =
            taskId.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(48).ifBlank { "default" }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Bi-temporal columns are indexed: point-in-time reconstruction filters
        // on them, and the Drift Watchdog's recency window scans created_at.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NODES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                content TEXT NOT NULL,
                kind TEXT NOT NULL,
                trust REAL NOT NULL,
                created_at INTEGER NOT NULL,
                expired_at INTEGER,
                valid_at INTEGER NOT NULL,
                invalid_at INTEGER,
                salience REAL NOT NULL DEFAULT 0.5,
                co_activation_count INTEGER NOT NULL DEFAULT 0,
                last_coactivated_at INTEGER NOT NULL DEFAULT 0,
                task_id TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_created ON $TABLE_NODES(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_task ON $TABLE_NODES(task_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_valid ON $TABLE_NODES(valid_at, invalid_at)")

        // Edges are first-class nodes: their own fact, trust, and bi-temporal fields.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EDGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                fact TEXT NOT NULL,
                trust_score REAL NOT NULL DEFAULT 0.5,
                created_at INTEGER NOT NULL,
                expired_at INTEGER,
                valid_at INTEGER NOT NULL,
                invalid_at INTEGER,
                co_activation_count INTEGER NOT NULL DEFAULT 0,
                last_coactivated_at INTEGER NOT NULL DEFAULT 0,
                task_id TEXT NOT NULL,
                FOREIGN KEY (source_id) REFERENCES $TABLE_NODES(id) ON DELETE CASCADE,
                FOREIGN KEY (target_id) REFERENCES $TABLE_NODES(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_source ON $TABLE_EDGES(source_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_target ON $TABLE_EDGES(target_id)")
        // The Hebbian traversal looks up "everything co-activated with X".
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_weight ON $TABLE_EDGES(co_activation_count)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FRAMES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                frame_id TEXT NOT NULL UNIQUE,
                task_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                tool TEXT NOT NULL DEFAULT '',
                args_digest TEXT NOT NULL DEFAULT '',
                result_digest TEXT NOT NULL DEFAULT '',
                provenance TEXT NOT NULL DEFAULT '',
                salience REAL NOT NULL DEFAULT 0.5,
                linked_plan_step TEXT,
                outcome TEXT NOT NULL
            )
            """.trimIndent(),
        )
        // The watchdog's key query: recent frames by outcome.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frames_time ON $TABLE_FRAMES(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frames_outcome ON $TABLE_FRAMES(outcome, timestamp)")

        // FTS5 over node content gives BM25 ranking with zero inference cost.
        // content=external keeps a single source of truth in TABLE_NODES.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_NODES_FTS
            USING fts5(content, content='$TABLE_NODES', content_rowid='id')
            """.trimIndent(),
        )
        // Keep the index in sync with the source table.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS nodes_ai AFTER INSERT ON $TABLE_NODES BEGIN
                INSERT INTO $TABLE_NODES_FTS(rowid, content) VALUES (new.id, new.content)
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS nodes_ad AFTER DELETE ON $TABLE_NODES BEGIN
                INSERT INTO $TABLE_NODES_FTS($TABLE_NODES_FTS, rowid, content)
                VALUES('delete', old.id, old.content)
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS nodes_au AFTER UPDATE ON $TABLE_NODES BEGIN
                INSERT INTO $TABLE_NODES_FTS($TABLE_NODES_FTS, rowid, content)
                VALUES('delete', old.id, old.content);
                INSERT INTO $TABLE_NODES_FTS(rowid, content) VALUES (new.id, new.content)
            END
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // CHM v1: no prior versions exist. A future schema change goes here.
    }

    // ---- Node row mapping -------------------------------------------------

    internal fun nodeFromCursor(c: Cursor): MemoryNode = MemoryNode(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        kind = MemoryKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),
        trust = c.getFloat(c.getColumnIndexOrThrow("trust")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        expiredAt = c.getColumnIndexOrThrow("expired_at").takeIf { !c.isNull(it) }?.let(c::getLong),
        validAt = c.getLong(c.getColumnIndexOrThrow("valid_at")),
        invalidAt = c.getColumnIndexOrThrow("invalid_at").takeIf { !c.isNull(it) }?.let(c::getLong),
        salience = c.getFloat(c.getColumnIndexOrThrow("salience")),
        coActivationCount = c.getInt(c.getColumnIndexOrThrow("co_activation_count")),
        lastCoactivatedAt = c.getLong(c.getColumnIndexOrThrow("last_coactivated_at")),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
        pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) != 0,
    )

    internal fun edgeFromCursor(c: Cursor): MemoryEdge = MemoryEdge(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        sourceId = c.getLong(c.getColumnIndexOrThrow("source_id")),
        targetId = c.getLong(c.getColumnIndexOrThrow("target_id")),
        fact = c.getString(c.getColumnIndexOrThrow("fact")),
        trustScore = c.getFloat(c.getColumnIndexOrThrow("trust_score")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        expiredAt = c.getColumnIndexOrThrow("expired_at").takeIf { !c.isNull(it) }?.let(c::getLong),
        validAt = c.getLong(c.getColumnIndexOrThrow("valid_at")),
        invalidAt = c.getColumnIndexOrThrow("invalid_at").takeIf { !c.isNull(it) }?.let(c::getLong),
        coActivationCount = c.getInt(c.getColumnIndexOrThrow("co_activation_count")),
        lastCoactivatedAt = c.getLong(c.getColumnIndexOrThrow("last_coactivated_at")),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
    )

    internal fun frameFromCursor(c: Cursor): EpisodicFrame = EpisodicFrame(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        frameId = c.getString(c.getColumnIndexOrThrow("frame_id")),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
        type = FrameType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))),
        tool = c.getString(c.getColumnIndexOrThrow("tool")),
        argsDigest = c.getString(c.getColumnIndexOrThrow("args_digest")),
        resultDigest = c.getString(c.getColumnIndexOrThrow("result_digest")),
        provenance = c.getString(c.getColumnIndexOrThrow("provenance"))
            .split(PROVENANCE_SEP).filter { it.isNotBlank() },
        salience = c.getFloat(c.getColumnIndexOrThrow("salience")),
        linkedPlanStep = c.getColumnIndexOrThrow("linked_plan_step").takeIf { !c.isNull(it) }?.let(c::getString),
        outcome = FrameOutcome.valueOf(c.getString(c.getColumnIndexOrThrow("outcome"))),
    )

    internal fun nodeValues(n: MemoryNode, includeId: Boolean): ContentValues = ContentValues().apply {
        if (includeId) put("id", n.id)
        put("content", n.content)
        put("kind", n.kind.name)
        put("trust", n.trust)
        put("created_at", n.createdAt)
        put("expired_at", n.expiredAt)
        put("valid_at", n.validAt)
        put("invalid_at", n.invalidAt)
        put("salience", n.salience)
        put("co_activation_count", n.coActivationCount)
        put("last_coactivated_at", n.lastCoactivatedAt)
        put("task_id", n.taskId)
        put("pinned", if (n.pinned) 1 else 0)
    }

    internal fun edgeValues(e: MemoryEdge, includeId: Boolean): ContentValues = ContentValues().apply {
        if (includeId) put("id", e.id)
        put("source_id", e.sourceId)
        put("target_id", e.targetId)
        put("fact", e.fact)
        put("trust_score", e.trustScore)
        put("created_at", e.createdAt)
        put("expired_at", e.expiredAt)
        put("valid_at", e.validAt)
        put("invalid_at", e.invalidAt)
        put("co_activation_count", e.coActivationCount)
        put("last_coactivated_at", e.lastCoactivatedAt)
        put("task_id", e.taskId)
    }

    internal fun frameValues(f: EpisodicFrame, includeId: Boolean): ContentValues = ContentValues().apply {
        if (includeId) put("id", f.id)
        put("frame_id", f.frameId)
        put("task_id", f.taskId)
        put("timestamp", f.timestamp)
        put("type", f.type.name)
        put("tool", f.tool)
        put("args_digest", f.argsDigest)
        put("result_digest", f.resultDigest)
        put("provenance", f.provenance.joinToString(PROVENANCE_SEP))
        put("salience", f.salience)
        put("linked_plan_step", f.linkedPlanStep)
        put("outcome", f.outcome.name)
    }
}

/** Provenance pointers are a flat list persisted as one delimited string. */
internal const val PROVENANCE_SEP: String = "\u241F"
