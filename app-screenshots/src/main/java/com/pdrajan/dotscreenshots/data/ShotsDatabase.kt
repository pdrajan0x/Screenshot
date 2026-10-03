package com.pdrajan.dotscreenshots.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite (no code generation). Text search uses an FTS4 table whose docid is the
 * screenshot id; image embeddings are int8 blobs, one row per crop.
 */
class ShotsDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE shots(
                id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                name TEXT NOT NULL,
                taken_at INTEGER NOT NULL,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size INTEGER NOT NULL DEFAULT 0,
                state INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                ocr_text TEXT,
                app TEXT,
                categories TEXT NOT NULL DEFAULT '',
                entities TEXT,
                note TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                index_version INTEGER NOT NULL DEFAULT 0,
                ocr_pending INTEGER NOT NULL DEFAULT 0,
                indexed_at INTEGER,
                app_source TEXT,
                app_package TEXT,
                page_url TEXT,
                title TEXT,
                summary TEXT,
                tags TEXT,
                summary_state INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX shots_taken ON shots(taken_at DESC)")
        db.execSQL("CREATE INDEX shots_state ON shots(state)")
        createFts(db)
        db.execSQL(
            "CREATE TABLE embeddings(shot_id INTEGER NOT NULL, crop INTEGER NOT NULL, vec BLOB NOT NULL, PRIMARY KEY(shot_id, crop))",
        )
        db.execSQL("CREATE TABLE collections(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE collection_items(collection_id INTEGER NOT NULL, shot_id INTEGER NOT NULL, added_at INTEGER NOT NULL, " +
                "PRIMARY KEY(collection_id, shot_id))",
        )
        db.execSQL("CREATE INDEX collection_items_shot ON collection_items(shot_id)")
        db.execSQL("CREATE TABLE recent_searches(query TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE shots ADD COLUMN ocr_pending INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) {
            listOf("app_source TEXT", "app_package TEXT", "page_url TEXT", "title TEXT", "summary TEXT", "tags TEXT",
                "summary_state INTEGER NOT NULL DEFAULT 0").forEach { db.execSQL("ALTER TABLE shots ADD COLUMN $it") }
            // The search index gains the summary columns: rebuild it from the rows.
            db.execSQL("DROP TABLE IF EXISTS shots_fts")
            createFts(db)
            db.execSQL("INSERT INTO shots_fts(docid, $FTS_COLUMNS) SELECT id, $FTS_SOURCE FROM shots")
        }
    }

    private fun createFts(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE shots_fts USING fts4($FTS_COLUMNS, tokenize=unicode61)")
    }

    companion object {
        const val NAME = "shots.db"
        const val VERSION = 3

        /** Full-text columns, and the shots expressions that fill them (same order). */
        const val FTS_COLUMNS = "ocr_text, note, app, title, summary, tags"
        const val FTS_SOURCE =
            "COALESCE(ocr_text,''), COALESCE(note,''), COALESCE(app,''), COALESCE(title,''), COALESCE(summary,''), COALESCE(tags,'')"

        /**
         * Bump when the analysis pipeline changes in a way that makes old results stale
         * (new model, new OCR language). Rows with a lower version get re-indexed.
         */
        const val INDEX_VERSION = 1
    }
}
