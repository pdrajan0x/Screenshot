package com.pdrajan.dotgallery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Gallery index. Media rows mirror MediaStore (id = MediaStore id); everything Google Photos
 * keeps server-side (favourites, archive, albums, people) lives here, on the phone.
 */
class GalleryDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media(
                id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                type INTEGER NOT NULL,
                name TEXT NOT NULL,
                mime TEXT,
                taken_at INTEGER NOT NULL,
                added_at INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0,
                bucket_id INTEGER NOT NULL DEFAULT 0,
                bucket TEXT,
                path TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                state INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                tags TEXT NOT NULL DEFAULT '',
                ocr_text TEXT,
                dhash INTEGER,
                sharpness REAL,
                index_version INTEGER NOT NULL DEFAULT 0,
                ocr_pending INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX media_taken ON media(taken_at DESC)")
        db.execSQL("CREATE INDEX media_bucket ON media(bucket_id)")
        db.execSQL("CREATE INDEX media_state ON media(state)")
        db.execSQL("CREATE VIRTUAL TABLE media_fts USING fts4(name, ocr_text, tags, tokenize=unicode61)")
        db.execSQL("CREATE TABLE embeddings(media_id INTEGER NOT NULL, crop INTEGER NOT NULL, vec BLOB NOT NULL, PRIMARY KEY(media_id, crop))")

        db.execSQL("CREATE TABLE albums(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE album_items(album_id INTEGER NOT NULL, media_id INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(album_id, media_id))",
        )
        db.execSQL("CREATE INDEX album_items_media ON album_items(media_id)")

        db.execSQL(
            """
            CREATE TABLE people(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                hidden INTEGER NOT NULL DEFAULT 0,
                centroid BLOB NOT NULL,
                face_count INTEGER NOT NULL DEFAULT 0,
                cover_face INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE faces(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id INTEGER NOT NULL,
                person_id INTEGER,
                l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
                quality REAL NOT NULL,
                vec BLOB NOT NULL,
                thumb TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX faces_media ON faces(media_id)")
        db.execSQL("CREATE INDEX faces_person ON faces(person_id)")

        db.execSQL(
            """
            CREATE TABLE locked(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file TEXT NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                type INTEGER NOT NULL,
                taken_at INTEGER NOT NULL,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE recent_searches(query TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE media ADD COLUMN ocr_pending INTEGER NOT NULL DEFAULT 0")
    }

    companion object {
        const val NAME = "gallery.db"
        const val VERSION = 2
        const val INDEX_VERSION = 1
    }
}
