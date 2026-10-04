package com.pdrajan.dotscreenshots.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.net.toUri
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.EntityType
import com.pdrajan.dot.engine.FtsQuery
import com.pdrajan.dot.engine.PictureWords
import com.pdrajan.dot.engine.PreciseSearch
import com.pdrajan.dot.media.MediaItem
import com.pdrajan.dot.ml.ScreenshotAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ShotsRepository(private val database: ShotsDatabase) {

    private val db: SQLiteDatabase get() = database.writableDatabase
    private val changes = MutableStateFlow(0L)

    private fun changed() = changes.update { it + 1 }

    /**
     * Re-runs [block] when the data changes, at most every [OBSERVE_GAP_MS] while screenshots are
     * being processed (each one is a change), so screens aren't rebuilt several times a second.
     */
    private fun <T> observe(block: () -> T): Flow<T> =
        changes.transform { emit(it); delay(OBSERVE_GAP_MS) }.map { block() }.flowOn(Dispatchers.IO).conflate().distinctUntilChanged()

    // ---------------------------------------------------------------- reads

    fun observeShots(): Flow<List<Shot>> = observe {
        db.rawQuery("SELECT $SHOT_COLUMNS FROM shots ORDER BY taken_at DESC", null).use { it.shots() }
    }

    fun observeCategory(category: String): Flow<List<Shot>> = observe {
        db.rawQuery("SELECT $SHOT_COLUMNS FROM shots WHERE categories LIKE ? ORDER BY taken_at DESC", arrayOf("%,$category,%")).use { it.shots() }
    }

    fun observeFavorites(): Flow<List<Shot>> = observe {
        db.rawQuery("SELECT $SHOT_COLUMNS FROM shots WHERE favorite = 1 ORDER BY taken_at DESC", null).use { it.shots() }
    }

    fun observeCollectionShots(collectionId: Long): Flow<List<Shot>> = observe {
        db.rawQuery(
            "SELECT ${SHOT_COLUMNS.split(", ").joinToString(", ") { "s.$it" }} FROM shots s " +
                "JOIN collection_items ci ON ci.shot_id = s.id WHERE ci.collection_id = ? ORDER BY ci.added_at DESC",
            arrayOf(collectionId.toString()),
        ).use { it.shots() }
    }

    fun observeCategoryCounts(): Flow<Map<String, Int>> = observe {
        val counts = HashMap<String, Int>()
        db.rawQuery("SELECT categories FROM shots WHERE categories != ''", null).use { c ->
            while (c.moveToNext()) {
                c.getString(0).split(',').filter { it.isNotEmpty() }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        counts
    }

    fun observeCollections(): Flow<List<ShotCollection>> = observe { collections() }

    fun observeCollection(id: Long): Flow<ShotCollection?> = observe { collections().firstOrNull { it.id == id } }

    private fun collections(): List<ShotCollection> {
        val sql = """
            SELECT c.id, c.name,
                (SELECT COUNT(*) FROM collection_items ci WHERE ci.collection_id = c.id),
                (SELECT s.uri FROM collection_items ci JOIN shots s ON s.id = ci.shot_id
                    WHERE ci.collection_id = c.id ORDER BY ci.added_at DESC LIMIT 1)
            FROM collections c ORDER BY c.created_at DESC
        """.trimIndent()
        return db.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ShotCollection(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3)?.toUri()))
                }
            }
        }
    }

    fun observeDetail(id: Long): Flow<ShotDetail?> = observe { detail(id) }

    private fun detail(id: Long): ShotDetail? {
        val row = db.rawQuery(
            "SELECT $SHOT_COLUMNS, ocr_text, entities, note, keywords, page_url, app_source, description, objects FROM shots WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            DetailRow(
                shot = c.shot(),
                text = c.getString(SHOT_COLUMN_COUNT) ?: "",
                entities = c.getString(SHOT_COLUMN_COUNT + 1),
                note = c.getString(SHOT_COLUMN_COUNT + 2) ?: "",
                keywords = c.getString(SHOT_COLUMN_COUNT + 3),
                pageUrl = c.getString(SHOT_COLUMN_COUNT + 4),
                appSource = c.getString(SHOT_COLUMN_COUNT + 5),
                description = c.getString(SHOT_COLUMN_COUNT + 6),
                objects = c.getString(SHOT_COLUMN_COUNT + 7),
            )
        }
        val collections = db.rawQuery(
            """
            SELECT c.id, c.name,
                (SELECT COUNT(*) FROM collection_items x WHERE x.collection_id = c.id), NULL
            FROM collections c JOIN collection_items ci ON ci.collection_id = c.id
            WHERE ci.shot_id = ? ORDER BY c.name
            """.trimIndent(),
            arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(ShotCollection(c.getLong(0), c.getString(1), c.getInt(2), null)) } }
        return ShotDetail(
            shot = row.shot,
            text = row.text,
            entities = decodeEntities(row.entities),
            note = row.note,
            collections = collections,
            keywords = (row.keywords.orEmpty() + "," + row.objects.orEmpty()).split(',').filter { it.isNotEmpty() }.distinct(),
            pageUrl = row.pageUrl,
            appSource = row.appSource,
            description = row.description?.takeIf { it.isNotBlank() },
        )
    }

    private data class DetailRow(
        val shot: Shot,
        val text: String,
        val entities: String?,
        val note: String,
        val keywords: String?,
        val pageUrl: String?,
        val appSource: String?,
        val description: String?,
        val objects: String?,
    )

    fun observeCounts(): Flow<IndexCounts> = observe { counts() }

    fun counts(): IndexCounts {
        var total = 0; var indexed = 0; var pending = 0; var failed = 0
        db.rawQuery("SELECT state, COUNT(*) FROM shots GROUP BY state", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1)
                total += n
                when (IndexState.of(c.getInt(0))) {
                    IndexState.PENDING -> pending += n
                    IndexState.INDEXED -> indexed += n
                    IndexState.FAILED -> failed += n
                }
            }
        }
        val updating = db.rawQuery(
            "SELECT COUNT(*) FROM shots WHERE state = ? AND describe_version < ? AND ocr_pending = 0",
            arrayOf(IndexState.INDEXED.code.toString(), ShotsDatabase.DESCRIBE_VERSION.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return IndexCounts(total, indexed, pending, failed, updating)
    }

    /** Screenshots indexed while the text model was still downloading. */
    fun ocrPendingCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM shots WHERE ocr_pending = 1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Queues those screenshots again now that text can be read. */
    suspend fun requeueOcrPending(): Int = withContext(Dispatchers.IO) {
        val n = db.compileStatement("UPDATE shots SET state = ${IndexState.PENDING.code}, ocr_pending = 0 WHERE ocr_pending = 1")
            .use { it.executeUpdateDelete() }
        if (n > 0) changed()
        n
    }

    suspend fun shotsByIds(ids: List<Long>): Map<Long, Shot> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        ids.chunked(500).flatMap { chunk ->
            db.rawQuery("SELECT $SHOT_COLUMNS FROM shots WHERE id IN (${chunk.joinToString(",")})", null).use { it.shots() }
        }.associateBy { it.id }
    }

    /** The words of each screenshot, for search snippets; the description only when it may be shown. */
    suspend fun textsByIds(ids: List<Long>, withDescription: Boolean): Map<Long, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val description = if (withDescription) "COALESCE(description, '') || ' ' || " else ""
        ids.chunked(500).flatMap { chunk ->
            db.rawQuery("SELECT id, $description COALESCE(ocr_text, '') || ' ' || COALESCE(note, '') FROM shots WHERE id IN (${chunk.joinToString(",")})", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
        }.toMap()
    }

    /** Ids still waiting for analysis, newest first. */
    suspend fun pending(limit: Int, since: Long = 0L): List<PendingShot> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, uri, name, taken_at FROM shots WHERE state = ? AND taken_at >= ? ORDER BY taken_at DESC LIMIT ?",
            arrayOf(IndexState.PENDING.code.toString(), since.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(PendingShot(c.getLong(0), c.getString(1).toUri(), c.getString(2), c.getLong(3))) } }
    }

    data class PendingShot(val id: Long, val uri: android.net.Uri, val name: String, val takenAt: Long)

    // ---------------------------------------------------------------- search

    /**
     * Precise text search (see [PreciseSearch]): every word must match; a match in the app, a
     * heading, a picture keyword or object, or the user's note counts most, then the description,
     * then the screen text. Word beginnings ("zom") only when nothing matches whole words.
     */
    suspend fun textSearch(query: String, words: PictureWords?): PreciseSearch.Ranked = withContext(Dispatchers.IO) {
        val none = PreciseSearch.Ranked(emptyList(), emptyList())
        val terms = PreciseSearch.terms(query, words)
        fun docs(match: String?): List<PreciseSearch.SearchDoc> {
            if (match == null) return emptyList()
            return try {
                db.rawQuery(
                    "SELECT s.id, COALESCE(s.app,'') || ' ' || COALESCE(s.headline,'') || ' ' || COALESCE(s.note,'') || ' ' || " +
                        "REPLACE(s.keywords || s.objects, ',', ' '), COALESCE(s.ocr_text,''), s.taken_at, COALESCE(s.description,'') " +
                        "FROM shots_fts f JOIN shots s ON s.id = f.docid WHERE shots_fts MATCH ? LIMIT 2000",
                    arrayOf(match),
                ).use { c ->
                    buildList { while (c.moveToNext()) add(PreciseSearch.SearchDoc(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))) }
                }
            } catch (_: SQLiteException) {
                // Odd input the FTS parser rejects.
                emptyList()
            }
        }
        val ranked = PreciseSearch.rank(terms, docs(PreciseSearch.ftsMatch(terms)))
        if (!ranked.isEmpty) return@withContext ranked
        val prefix = docs(FtsQuery.build(terms.joinToString(" ") { it.word })).sortedByDescending { it.takenAt }.map { it.id }
        if (prefix.isNotEmpty()) return@withContext PreciseSearch.Ranked(prefix, emptyList())
        // Scripts the tokenizer splits oddly: a plain substring match.
        val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        if (query.isBlank()) return@withContext none
        val ids = db.rawQuery(
            "SELECT id FROM shots WHERE ocr_text LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\' " +
                "OR description LIKE ? ESCAPE '\\' ORDER BY taken_at DESC LIMIT 500",
            arrayOf(like, like, like, like),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        PreciseSearch.Ranked(ids, emptyList())
    }

    /** Screenshots taken in a date range ("last week"), newest first. */
    suspend fun idsTakenBetween(start: Long, end: Long): List<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id FROM shots WHERE taken_at >= ? AND taken_at < ? ORDER BY taken_at DESC", arrayOf(start.toString(), end.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    suspend fun categoryShots(category: String): List<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id FROM shots WHERE categories LIKE ? ORDER BY taken_at DESC", arrayOf("%,$category,%")).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    suspend fun recentSearches(): List<String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT query FROM recent_searches ORDER BY at DESC LIMIT 8", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    suspend fun addRecentSearch(query: String) = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext
        db.insertWithOnConflict("recent_searches", null, ContentValues().apply {
            put("query", q)
            put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY at DESC LIMIT 20)")
    }

    suspend fun clearRecentSearches() = withContext(Dispatchers.IO) { db.delete("recent_searches", null, null); Unit }

    // ---------------------------------------------------------------- index writes

    data class SyncResult(val added: Int, val removed: Int)

    /** Mirrors MediaStore's screenshot list into the database. */
    suspend fun sync(items: List<MediaItem>): SyncResult = withContext(Dispatchers.IO) {
        val existing = HashMap<Long, String>()
        db.rawQuery("SELECT id, uri FROM shots", null).use { c -> while (c.moveToNext()) existing[c.getLong(0)] = c.getString(1) }
        val current = items.associateBy { it.id }
        var added = 0
        val removed = existing.keys - current.keys
        db.beginTransaction()
        try {
            for (item in items) {
                if (item.id in existing) continue
                db.insertWithOnConflict("shots", null, ContentValues().apply {
                    put("id", item.id)
                    put("uri", item.uri.toString())
                    put("name", item.displayName)
                    put("taken_at", item.takenAt)
                    put("width", item.width)
                    put("height", item.height)
                    put("size", item.sizeBytes)
                    put("state", IndexState.PENDING.code)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                added++
            }
            for (id in removed) deleteRow(id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0 || removed.isNotEmpty()) changed()
        SyncResult(added, removed.size)
    }

    private fun deleteRow(id: Long) {
        val arg = arrayOf(id.toString())
        db.delete("shots", "id = ?", arg)
        db.delete("shots_fts", "docid = ?", arg)
        db.delete("collection_items", "shot_id = ?", arg)
    }

    suspend fun saveAnalysis(id: Long, analysis: ScreenshotAnalysis) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            val previousSource = db.rawQuery("SELECT app_source FROM shots WHERE id = ?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            // Re-reading a screenshot never undoes the user's choice, or an exact app the usage history no longer has.
            val keepApp = previousSource == "user" || (previousSource == "usage" && analysis.appSource?.code != "usage")
            db.update("shots", ContentValues().apply {
                put("state", IndexState.INDEXED.code)
                put("ocr_text", analysis.ocrText)
                if (!keepApp) put("app", analysis.sourceApp)
                put("categories", if (analysis.categories.isEmpty()) "" else analysis.categories.joinToString(",", ",", ","))
                put("entities", encodeEntities(analysis.entities))
                put("index_version", ShotsDatabase.INDEX_VERSION)
                put("ocr_pending", if (analysis.ocrPending) 1 else 0)
                put("indexed_at", System.currentTimeMillis())
                if (!keepApp) {
                    put("app_source", analysis.appSource?.code)
                    put("app_package", analysis.appPackage)
                }
                put("page_url", analysis.pageUrl)
                put("headline", analysis.headline)
            }, "id = ?", arrayOf(id.toString()))
            rewriteFts(id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    suspend fun markFailed(id: Long) = withContext(Dispatchers.IO) {
        db.execSQL(
            "UPDATE shots SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= 3 THEN ? ELSE ? END WHERE id = ?",
            arrayOf<Any>(IndexState.FAILED.code, IndexState.PENDING.code, id),
        )
        changed()
    }

    /** Re-analyse everything (e.g. after switching OCR language). Notes and collections are kept. */
    suspend fun requeueAll() = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE shots SET state = ?, attempts = 0", arrayOf<Any>(IndexState.PENDING.code))
        changed()
    }

    private fun rewriteFts(id: Long) {
        db.delete("shots_fts", "docid = ?", arrayOf(id.toString()))
        db.execSQL(
            "INSERT INTO shots_fts(docid, ${ShotsDatabase.FTS_COLUMNS}) SELECT id, ${ShotsDatabase.FTS_SOURCE} FROM shots WHERE id = ?",
            arrayOf<Any>(id),
        )
    }

    // ---------------------------------------------------------------- user edits

    suspend fun setNote(id: Long, note: String) = withContext(Dispatchers.IO) {
        db.update("shots", ContentValues().apply { put("note", note.trim().ifEmpty { null }) }, "id = ?", arrayOf(id.toString()))
        rewriteFts(id)
        changed()
    }

    suspend fun toggleFavorite(id: Long) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE shots SET favorite = 1 - favorite WHERE id = ?", arrayOf<Any>(id))
        changed()
    }

    suspend fun createCollection(name: String): Long = withContext(Dispatchers.IO) {
        db.insert("collections", null, ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }).also { changed() }
    }

    suspend fun renameCollection(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.update("collections", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    suspend fun deleteCollection(id: Long) = withContext(Dispatchers.IO) {
        db.delete("collection_items", "collection_id = ?", arrayOf(id.toString()))
        db.delete("collections", "id = ?", arrayOf(id.toString()))
        changed()
    }

    suspend fun addToCollection(collectionId: Long, shotIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            shotIds.forEach { id ->
                db.insertWithOnConflict("collection_items", null, ContentValues().apply {
                    put("collection_id", collectionId)
                    put("shot_id", id)
                    put("added_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    suspend fun removeFromCollection(collectionId: Long, shotIds: Collection<Long>) = withContext(Dispatchers.IO) {
        shotIds.forEach { db.delete("collection_items", "collection_id = ? AND shot_id = ?", arrayOf(collectionId.toString(), it.toString())) }
        changed()
    }

    /** Removes rows right away after the user deleted files (MediaStore sync would catch up later anyway). */
    suspend fun forget(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            ids.forEach { deleteRow(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    // ---------------------------------------------------------------- descriptions

    /** A screenshot to describe, with the text read from it (to check the description's quotes against). */
    class DescribeJob(val id: Long, val uri: android.net.Uri, val text: String, val app: String?)

    /** Read screenshots not described yet by the current model, newest first ([since]: taken from then on). */
    suspend fun describeQueue(limit: Int, since: Long = 0L): List<DescribeJob> = withContext(Dispatchers.IO) {
        db.rawQuery(
            // Not before its text is read: the description is checked against it.
            "SELECT id, uri, COALESCE(ocr_text,''), app FROM shots WHERE state = ? AND describe_version < ? AND ocr_pending = 0 AND taken_at >= ? ORDER BY taken_at DESC LIMIT ?",
            arrayOf(IndexState.INDEXED.code.toString(), ShotsDatabase.DESCRIBE_VERSION.toString(), since.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(DescribeJob(c.getLong(0), c.getString(1).toUri(), c.getString(2), c.getString(3))) } }
    }

    /** Saves a description and its objects; [categories] (from the text and description) replace the stored ones when given. */
    suspend fun saveDescription(id: Long, description: String?, objects: List<String>, categories: List<String>? = null) = withContext(Dispatchers.IO) {
        db.update("shots", ContentValues().apply {
            put("description", description?.takeIf { it.isNotBlank() })
            put("objects", if (objects.isEmpty()) "" else objects.joinToString(",", ",", ","))
            put("describe_version", ShotsDatabase.DESCRIBE_VERSION)
            if (categories != null) put("categories", if (categories.isEmpty()) "" else categories.joinToString(",", ",", ","))
        }, "id = ?", arrayOf(id.toString()))
        rewriteFts(id)
        changed()
    }

    // ---------------------------------------------------------------- source app

    /** The user picked the app: it's certain from now on. */
    suspend fun setAppByUser(id: Long, label: String, packageName: String?) = withContext(Dispatchers.IO) {
        setApp(id, label, packageName, "user")
        changed()
    }

    private fun setApp(id: Long, label: String, packageName: String?, source: String) {
        db.execSQL("UPDATE shots SET app = ?, app_package = ?, app_source = ? WHERE id = ?", arrayOf<Any?>(label, packageName, source, id))
        rewriteFts(id)
    }

    /** Every app name in the library with its screenshot count, for the "which app" picker. */
    suspend fun appLabels(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT app, COUNT(*) FROM shots WHERE app IS NOT NULL GROUP BY app ORDER BY COUNT(*) DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }
    }

    fun databaseSizeBytes(): Long = database.readableDatabase.path?.let { java.io.File(it).length() } ?: 0L

    // ---------------------------------------------------------------- mapping

    private fun Cursor.shot(): Shot = Shot(
        id = getLong(0),
        uri = getString(1).toUri(),
        name = getString(2),
        takenAt = getLong(3),
        width = getInt(4),
        height = getInt(5),
        sizeBytes = getLong(6),
        state = IndexState.of(getInt(7)),
        app = getString(8),
        categories = getString(9).split(',').filter { it.isNotEmpty() },
        favorite = getInt(10) == 1,
    )

    private fun Cursor.shots(): List<Shot> = buildList(count) { while (moveToNext()) add(shot()) }

    companion object {
        private const val SHOT_COLUMNS = "id, uri, name, taken_at, width, height, size, state, app, categories, favorite"
        private const val SHOT_COLUMN_COUNT = 11
        private const val OBSERVE_GAP_MS = 300L


        fun encodeEntities(entities: List<Entity>): String = JSONArray().apply {
            entities.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.type.name)
                    put("x", e.text)
                    put("v", e.value)
                    e.epochMillis?.let { put("e", it) }
                    if (e.hasTime) put("h", true)
                })
            }
        }.toString()

        fun decodeEntities(json: String?): List<Entity> {
            if (json.isNullOrEmpty()) return emptyList()
            return runCatching {
                val a = JSONArray(json)
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    Entity(
                        type = EntityType.valueOf(o.getString("t")),
                        text = o.getString("x"),
                        value = o.getString("v"),
                        epochMillis = if (o.has("e")) o.getLong("e") else null,
                        hasTime = o.optBoolean("h", false),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
