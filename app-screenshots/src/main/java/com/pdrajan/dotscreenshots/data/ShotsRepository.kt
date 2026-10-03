package com.pdrajan.dotscreenshots.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.net.toUri
import com.pdrajan.dot.engine.AppIdentifier
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.EntityType
import com.pdrajan.dot.engine.FtsQuery
import com.pdrajan.dot.engine.HybridRanker
import com.pdrajan.dot.engine.KnownShot
import com.pdrajan.dot.engine.QuantizedVector
import com.pdrajan.dot.engine.ScreenshotSummary
import com.pdrajan.dot.engine.VectorHit
import com.pdrajan.dot.engine.VectorIndex
import com.pdrajan.dot.media.MediaItem
import com.pdrajan.dot.ml.ClipModel
import com.pdrajan.dot.ml.ScreenshotAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ShotsRepository(private val database: ShotsDatabase) {

    private val db: SQLiteDatabase get() = database.writableDatabase
    private val changes = MutableStateFlow(0L)
    private val vectorIndex = VectorIndex()
    private val vectorLoad = Mutex()
    @Volatile private var vectorsLoaded = false

    private fun changed() = changes.update { it + 1 }

    private fun <T> observe(block: () -> T): Flow<T> =
        changes.map { block() }.flowOn(Dispatchers.IO).conflate().distinctUntilChanged()

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
            "SELECT $SHOT_COLUMNS, ocr_text, entities, note, summary, tags, page_url, app_source, app_confidence FROM shots WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            DetailRow(
                shot = c.shot(),
                text = c.getString(SHOT_COLUMN_COUNT) ?: "",
                entities = c.getString(SHOT_COLUMN_COUNT + 1),
                note = c.getString(SHOT_COLUMN_COUNT + 2) ?: "",
                summary = c.getString(SHOT_COLUMN_COUNT + 3),
                tags = c.getString(SHOT_COLUMN_COUNT + 4),
                pageUrl = c.getString(SHOT_COLUMN_COUNT + 5),
                appSource = c.getString(SHOT_COLUMN_COUNT + 6),
                appConfidence = if (c.isNull(SHOT_COLUMN_COUNT + 7)) null else c.getFloat(SHOT_COLUMN_COUNT + 7),
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
            summary = row.summary,
            tags = row.tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            pageUrl = row.pageUrl,
            appSource = row.appSource,
            appConfidence = row.appConfidence,
        )
    }

    private data class DetailRow(
        val shot: Shot,
        val text: String,
        val entities: String?,
        val note: String,
        val summary: String?,
        val tags: String?,
        val pageUrl: String?,
        val appSource: String?,
        val appConfidence: Float?,
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
        return IndexCounts(total, indexed, pending, failed)
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

    suspend fun textsByIds(ids: List<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        ids.chunked(500).flatMap { chunk ->
            db.rawQuery("SELECT id, COALESCE(ocr_text, '') || ' ' || COALESCE(note, '') FROM shots WHERE id IN (${chunk.joinToString(",")})", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
        }.toMap()
    }

    /** Ids still waiting for analysis, newest first. */
    suspend fun pending(limit: Int): List<PendingShot> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, uri, name, taken_at FROM shots WHERE state = ? ORDER BY taken_at DESC LIMIT ?",
            arrayOf(IndexState.PENDING.code.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(PendingShot(c.getLong(0), c.getString(1).toUri(), c.getString(2), c.getLong(3))) } }
    }

    data class PendingShot(val id: Long, val uri: android.net.Uri, val name: String, val takenAt: Long)

    // ---------------------------------------------------------------- search

    data class TextMatches(val ids: List<Long>, val noteIds: List<Long>)

    suspend fun textSearch(query: String): TextMatches = withContext(Dispatchers.IO) {
        val terms = FtsQuery.terms(query)
        if (terms.isEmpty()) return@withContext TextMatches(emptyList(), emptyList())
        val rows = ArrayList<Triple<Long, String, String>>()
        fun fts(match: String?) {
            if (match == null) return
            try {
                db.rawQuery(
                    "SELECT docid, ocr_text || ' ' || app || ' ' || title || ' ' || summary || ' ' || tags, note " +
                        "FROM shots_fts WHERE shots_fts MATCH ? LIMIT 1000",
                    arrayOf(match),
                ).use { c ->
                    while (c.moveToNext()) rows += Triple(c.getLong(0), c.getString(1), c.getString(2))
                }
            } catch (_: SQLiteException) {
                // Odd input the FTS parser rejects; the substring fallback below still runs.
            }
        }
        // Whole words ("car", "cars"; not "cart" or "Oscar"); word beginnings only when nothing matches.
        fts(FtsQuery.words(query))
        val whole = rows.isNotEmpty()
        if (!whole) fts(FtsQuery.build(query))
        if (rows.isEmpty()) {
            val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            db.rawQuery(
                "SELECT id, COALESCE(ocr_text,'') || ' ' || COALESCE(app,'') || ' ' || COALESCE(title,'') || ' ' || " +
                    "COALESCE(summary,'') || ' ' || COALESCE(tags,''), COALESCE(note,'') FROM shots " +
                    "WHERE ocr_text LIKE ? ESCAPE '\\' OR note LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\' " +
                    "OR title LIKE ? ESCAPE '\\' OR summary LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\' LIMIT 1000",
                arrayOf(like, like, like, like, like, like),
            ).use { c -> while (c.moveToNext()) rows += Triple(c.getLong(0), c.getString(1), c.getString(2)) }
        }
        fun score(text: String) = if (whole) FtsQuery.wordHits(text, terms) else terms.sumOf { t -> occurrences(text.lowercase(), t) }
        val noteIds = rows.filter { (_, _, note) -> score(note) > 0 }.sortedByDescending { score(it.third) }.map { it.first }
        val ids = rows.filter { (_, text, _) -> score(text) > 0 }.sortedByDescending { score(it.second) }.map { it.first }
        TextMatches(ids, noteIds)
    }

    suspend fun categoryShots(category: String): List<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id FROM shots WHERE categories LIKE ? ORDER BY taken_at DESC", arrayOf("%,$category,%")).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    /** CLIP text→image hits, already thresholded. Loads the text encoder on first use. */
    suspend fun visualSearch(query: String, clip: ClipModel, filter: ((Long) -> Boolean)? = null): List<VectorHit> = withContext(Dispatchers.Default) {
        ensureVectors()
        if (vectorIndex.size == 0) return@withContext emptyList()
        val q = clip.embedQuery(query)
        // Screens share a lot of layout, so a merely similar-looking one (a shopping list for "shoes")
        // scores higher than an unrelated photo would: a higher bar than for photos.
        HybridRanker.filterVisual(vectorIndex.search(q, 150, filter), floor = 0.22f, dropFromTop = 0.04f, max = 40)
    }

    /** Ids the AI has summarised: search trusts what it wrote over look-alike matching. */
    suspend fun summarizedIds(): Set<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id FROM shots WHERE summary_state = ?", arrayOf(SUMMARY_DONE.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    suspend fun similar(id: Long, limit: Int = 12): List<Long> = withContext(Dispatchers.Default) {
        ensureVectors()
        vectorIndex.similarTo(id, limit).filter { it.score >= 0.55f }.map { it.id }
    }

    private suspend fun ensureVectors() {
        if (vectorsLoaded) return
        vectorLoad.withLock {
            if (vectorsLoaded) return
            withContext(Dispatchers.IO) {
                val byShot = HashMap<Long, MutableList<QuantizedVector>>()
                db.rawQuery("SELECT shot_id, vec FROM embeddings ORDER BY shot_id, crop", null).use { c ->
                    while (c.moveToNext()) byShot.getOrPut(c.getLong(0)) { ArrayList(3) } += QuantizedVector.fromBlob(c.getBlob(1))
                }
                byShot.forEach { (id, crops) -> vectorIndex.put(id, crops) }
            }
            vectorsLoaded = true
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
        removed.forEach { vectorIndex.remove(it) }
        if (added > 0 || removed.isNotEmpty()) changed()
        SyncResult(added, removed.size)
    }

    private fun deleteRow(id: Long) {
        val arg = arrayOf(id.toString())
        db.delete("shots", "id = ?", arg)
        db.delete("shots_fts", "docid = ?", arg)
        db.delete("embeddings", "shot_id = ?", arg)
        db.delete("collection_items", "shot_id = ?", arg)
    }

    suspend fun saveAnalysis(id: Long, analysis: ScreenshotAnalysis) = withContext(Dispatchers.IO) {
        val crops = analysis.cropEmbeddings.map { QuantizedVector.of(it) }
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
                    // Guessed again by the next identification pass, with everything known by then.
                    putNull("app_confidence")
                }
                put("page_url", analysis.pageUrl)
                // New text: the summary (if any) is rewritten from it.
                put("summary_state", SUMMARY_PENDING)
            }, "id = ?", arrayOf(id.toString()))
            rewriteFts(id)
            db.delete("embeddings", "shot_id = ?", arrayOf(id.toString()))
            crops.forEachIndexed { i, q ->
                db.insert("embeddings", null, ContentValues().apply {
                    put("shot_id", id)
                    put("crop", i)
                    put("vec", q.toBlob())
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (vectorsLoaded) vectorIndex.put(id, crops)
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
        ids.forEach { vectorIndex.remove(it) }
        changed()
    }

    // ---------------------------------------------------------------- summaries (on-device model)

    /** Indexed screenshots still waiting for a summary, newest first; [since] limits to recent ones. */
    suspend fun summaryQueue(limit: Int, since: Long = 0L): List<SummaryJob> = withContext(Dispatchers.IO) {
        db.rawQuery(
            // A shaky guess of the app would only mislead the summary.
            "SELECT id, uri, CASE WHEN app_source IN ('usage', 'file', 'user') OR COALESCE(app_confidence, 0) >= 0.6 THEN app END, " +
                "COALESCE(ocr_text,''), name FROM shots WHERE state = ? AND summary_state = ? AND ocr_pending = 0 AND taken_at >= ? " +
                "ORDER BY taken_at DESC LIMIT ?",
            arrayOf(IndexState.INDEXED.code.toString(), SUMMARY_PENDING.toString(), since.toString(), limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SummaryJob(c.getLong(0), c.getString(1).toUri(), c.getString(2), c.getString(3), c.getString(4))) }
        }
    }

    /**
     * Stores what the vision model wrote. The app it named is kept as one more signal for the app
     * guess: an uncertain app is guessed again with it (see [guessTargets]).
     */
    suspend fun saveSummary(id: Long, summary: ScreenshotSummary) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.update("shots", ContentValues().apply {
                put("title", summary.title.ifBlank { null })
                put("summary", summary.summary.ifBlank { null })
                put("tags", summary.tags.joinToString(", ").ifEmpty { null })
                put("model_app", summary.app)
                put("summary_state", SUMMARY_DONE)
            }, "id = ?", arrayOf(id.toString()))
            if (summary.app != null) {
                db.execSQL("UPDATE shots SET app_confidence = NULL WHERE id = ? AND $UNCERTAIN_APP", arrayOf<Any>(id))
            }
            rewriteFts(id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    suspend fun markSummaryFailed(id: Long) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE shots SET summary_state = ? WHERE id = ?", arrayOf<Any>(SUMMARY_FAILED, id))
        changed()
    }

    fun summaryCounts(): SummaryCounts =
        db.rawQuery(
            "SELECT SUM(summary_state = ?), SUM(summary_state = ?) FROM shots WHERE state = ?",
            arrayOf(SUMMARY_DONE.toString(), SUMMARY_PENDING.toString(), IndexState.INDEXED.code.toString()),
        ).use { c -> if (c.moveToFirst()) SummaryCounts(c.getInt(0), c.getInt(1)) else SummaryCounts(0, 0) }

    fun observeSummaryCounts(): Flow<SummaryCounts> = observe { summaryCounts() }

    // ---------------------------------------------------------------- source app

    /** The user picked the app: it's certain from now on, and teaches the guesses for similar screenshots. */
    suspend fun setAppByUser(id: Long, label: String, packageName: String?) = withContext(Dispatchers.IO) {
        setApp(id, label, packageName, "user")
        changed()
    }

    private fun setApp(id: Long, label: String, packageName: String?, source: String) {
        db.execSQL(
            "UPDATE shots SET summary_state = CASE WHEN summary_state = ? AND (app IS NULL OR app != ?) THEN ? ELSE summary_state END, " +
                "app = ?, app_package = ?, app_source = ?, app_confidence = NULL WHERE id = ?",
            arrayOf<Any?>(SUMMARY_DONE, label, SUMMARY_PENDING, label, packageName, source, id),
        )
        rewriteFts(id)
    }

    /** How many screenshots have a certain app (cheap: no embeddings loaded). */
    fun knownAppCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM shots WHERE app IS NOT NULL AND app_source IN ('usage', 'file', 'user')", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Whether any indexed screenshot still waits for its first app guess (cheap). */
    fun hasUnguessedApps(): Boolean =
        db.rawQuery(
            "SELECT 1 FROM shots WHERE state = ? AND $UNCERTAIN_APP AND app_confidence IS NULL LIMIT 1",
            arrayOf(IndexState.INDEXED.code.toString()),
        ).use { it.moveToFirst() }

    /** Screenshots whose app is certain, with their look, to learn from. */
    suspend fun knownAppShots(): List<KnownShot> {
        ensureVectors()
        return withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT id, app, app_package, taken_at FROM shots WHERE app IS NOT NULL AND app_source IN ('usage', 'file', 'user')",
                null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(KnownShot(c.getString(1), c.getString(2), c.getLong(3), vectorIndex.vectorsOf(c.getLong(0)).orEmpty()))
                    }
                }
            }
        }
    }

    class GuessTarget(val id: Long, val takenAt: Long, val text: String, val crops: List<FloatArray>, val modelApp: String?)

    /**
     * Indexed screenshots whose app isn't certain, by id after [afterId]. With [all] false only
     * the ones never guessed before.
     */
    suspend fun guessTargets(all: Boolean, afterId: Long, limit: Int): List<GuessTarget> {
        ensureVectors()
        return withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT id, taken_at, COALESCE(ocr_text, ''), model_app FROM shots WHERE state = ? AND id > ? AND $UNCERTAIN_APP" +
                    (if (all) "" else " AND app_confidence IS NULL") + " ORDER BY id LIMIT ?",
                arrayOf(IndexState.INDEXED.code.toString(), afterId.toString(), limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        add(GuessTarget(id, c.getLong(1), c.getString(2), vectorIndex.vectorsOf(id).orEmpty(), c.getString(3)))
                    }
                }
            }
        }
    }

    /** Stores best guesses; returns how many screenshots changed app. */
    suspend fun saveGuesses(guesses: List<Pair<Long, AppIdentifier.Result>>): Int = withContext(Dispatchers.IO) {
        if (guesses.isEmpty()) return@withContext 0
        var moved = 0
        db.beginTransaction()
        try {
            for ((id, g) in guesses) {
                val before = db.rawQuery("SELECT app FROM shots WHERE id = ? AND $UNCERTAIN_APP", arrayOf(id.toString())).use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: "") else null
                } ?: continue
                db.execSQL(
                    "UPDATE shots SET app = ?, app_package = ?, app_source = 'guess', app_confidence = ? WHERE id = ?",
                    arrayOf<Any?>(g.label, g.packageName, g.confidence.toDouble(), id),
                )
                if (before != g.label) {
                    rewriteFts(id)
                    moved++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (moved > 0) changed()
        moved
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
        title = getString(11),
        summarized = getInt(12) == SUMMARY_DONE,
    )

    private fun Cursor.shots(): List<Shot> = buildList(count) { while (moveToNext()) add(shot()) }

    companion object {
        const val SUMMARY_PENDING = 0
        const val SUMMARY_DONE = 1
        const val SUMMARY_FAILED = 2

        private const val SHOT_COLUMNS = "id, uri, name, taken_at, width, height, size, state, app, categories, favorite, title, summary_state"
        private const val SHOT_COLUMN_COUNT = 13

        /** Rows whose source app is a guess (or not known at all), not from usage history, file name or the user. */
        private const val UNCERTAIN_APP = "(app_source IS NULL OR app_source NOT IN ('usage', 'file', 'user'))"

        private fun occurrences(text: String, term: String): Int {
            var n = 0
            var i = text.indexOf(term)
            while (i >= 0) {
                n++
                i = text.indexOf(term, i + term.length)
            }
            return n
        }

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
