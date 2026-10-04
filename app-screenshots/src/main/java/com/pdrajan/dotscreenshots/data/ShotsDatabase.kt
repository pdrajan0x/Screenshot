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
                headline TEXT,
                keywords TEXT NOT NULL DEFAULT '',
                words_version INTEGER NOT NULL DEFAULT 0,
                clip_version INTEGER NOT NULL DEFAULT 0
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
            // (The search index is rebuilt by step 6, with the columns it has now.)
        }
        // How sure a guessed source app is (null when it is certain).
        if (oldVersion < 4) db.execSQL("ALTER TABLE shots ADD COLUMN app_confidence REAL")
        if (oldVersion < 5) {
            // The app the vision model named, one signal for the app guess.
            db.execSQL("ALTER TABLE shots ADD COLUMN model_app TEXT")
            // Summaries now come from the vision model (it sees the screen, not only its text): redo them all.
            // The old ones stay visible until then.
            db.execSQL("UPDATE shots SET summary_state = 0")
        }
        if (oldVersion < 6) {
            // No AI model any more: summaries go, and apps it named are named again from the screen's words.
            // Headings (big text) and picture keywords become searchable. The old columns stay unused.
            db.execSQL("ALTER TABLE shots ADD COLUMN headline TEXT")
            db.execSQL("ALTER TABLE shots ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE shots ADD COLUMN words_version INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE shots SET title = NULL, summary = NULL, tags = NULL")
            db.execSQL("UPDATE shots SET app = NULL, app_package = NULL, app_source = NULL WHERE app_source IN ('model', 'guess', 'visual')")
            db.execSQL("DROP TABLE IF EXISTS shots_fts")
            createFts(db)
            db.execSQL("INSERT INTO shots_fts(docid, $FTS_COLUMNS) SELECT id, $FTS_SOURCE FROM shots")
        }
        // Which image model made a row's embeddings: older ones are read again with the current one.
        if (oldVersion < 7) db.execSQL("ALTER TABLE shots ADD COLUMN clip_version INTEGER NOT NULL DEFAULT 0")
    }

    private fun createFts(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE shots_fts USING fts4($FTS_COLUMNS, tokenize=unicode61)")
    }

    companion object {
        const val NAME = "shots.db"
        const val VERSION = 7

        /** Full-text columns, and the shots expressions that fill them (same order). */
        const val FTS_COLUMNS = "ocr_text, note, app, headline, keywords"
        const val FTS_SOURCE =
            "COALESCE(ocr_text,''), COALESCE(note,''), COALESCE(app,''), COALESCE(headline,''), REPLACE(keywords, ',', ' ')"

        /**
         * Bump when the analysis pipeline changes in a way that makes old results stale
         * (new model, new OCR language). Rows with a lower version get re-indexed.
         */
        const val INDEX_VERSION = 1

        /** Bump when picture keywords or app words change: stored rows get them again, without re-reading images. */
        const val WORDS_VERSION = 1

        /**
         * The image model behind the stored embeddings (1 = MobileCLIP2-S0, 2 = MobileCLIP2-S2). Rows
         * made by an older one are kept out of picture search until their picture is read again.
         */
        const val CLIP_VERSION = 2
    }
}
