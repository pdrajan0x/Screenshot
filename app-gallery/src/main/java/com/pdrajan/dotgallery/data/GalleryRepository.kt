package com.pdrajan.dotgallery.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.net.toUri
import com.pdrajan.dot.engine.FaceClusterer
import com.pdrajan.dot.engine.FtsQuery
import com.pdrajan.dot.engine.HybridRanker
import com.pdrajan.dot.engine.ImageQuality
import com.pdrajan.dot.engine.QuantizedVector
import com.pdrajan.dot.engine.VectorHit
import com.pdrajan.dot.engine.VectorIndex
import com.pdrajan.dot.engine.VectorMath
import com.pdrajan.dot.media.MediaItem
import com.pdrajan.dot.media.MediaType
import com.pdrajan.dot.ml.ClipModel
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

/** One analysed face, ready to store. Box is normalised to 0..1. */
class FaceResult(val left: Float, val top: Float, val right: Float, val bottom: Float, val quality: Float, val embedding: FloatArray, val thumbPath: String?)

class GalleryAnalysis(
    val cropEmbeddings: List<FloatArray>,
    val tags: List<String>,
    val ocrText: String,
    val dHash: Long?,
    val sharpness: Double?,
    val faces: List<FaceResult>,
    /** Text wasn't read because Play services is still downloading the text model. */
    val ocrPending: Boolean = false,
)

class GalleryRepository(private val database: GalleryDatabase) {

    private val db: SQLiteDatabase get() = database.writableDatabase
    private val changes = MutableStateFlow(0L)
    private val vectorIndex = VectorIndex()
    private val vectorLoad = Mutex()
    @Volatile private var vectorsLoaded = false
    private val clusterer = FaceClusterer()
    private var people: MutableList<FaceClusterer.Person>? = null

    private fun changed() = changes.update { it + 1 }

    private fun <T> observe(block: () -> T): Flow<T> =
        changes.map { block() }.flowOn(Dispatchers.IO).conflate().distinctUntilChanged()

    // ---------------------------------------------------------------- media lists

    private fun mediaQuery(
        where: String,
        args: Array<String>? = null,
        order: String = "m.taken_at DESC",
        join: String = "",
        distinct: Boolean = false,
    ): List<Media> =
        db.rawQuery("SELECT ${if (distinct) "DISTINCT " else ""}$MEDIA_COLS FROM media m $join WHERE $where ORDER BY $order", args).use { it.mediaList() }

    fun observeTimeline(): Flow<List<Media>> = observe { mediaQuery("m.archived = 0") }
    fun observeFavorites(): Flow<List<Media>> = observe { mediaQuery("m.favorite = 1") }
    fun observeArchived(): Flow<List<Media>> = observe { mediaQuery("m.archived = 1") }
    fun observeVideos(): Flow<List<Media>> = observe { mediaQuery("m.type = 1") }
    fun observeFolder(bucketId: Long): Flow<List<Media>> = observe { mediaQuery("m.bucket_id = ?", arrayOf(bucketId.toString())) }
    /** Photos the AI gave this keyword. */
    fun observeKeyword(keyword: String): Flow<List<Media>> = observe { mediaQuery("m.keywords LIKE ?", arrayOf("%,$keyword,%")) }
    fun observeRecentlyAdded(): Flow<List<Media>> = observe { mediaQuery("1 = 1", order = "m.added_at DESC LIMIT 300") }
    fun observeAlbum(albumId: Long): Flow<List<Media>> = observe {
        mediaQuery("ai.album_id = ?", arrayOf(albumId.toString()), order = "ai.added_at DESC", join = "JOIN album_items ai ON ai.media_id = m.id")
    }
    fun observePerson(personId: Long): Flow<List<Media>> = observe {
        mediaQuery("f.person_id = ?", arrayOf(personId.toString()), join = "JOIN faces f ON f.media_id = m.id", distinct = true)
    }

    fun observeFolders(): Flow<List<Folder>> = observe {
        db.rawQuery(
            "SELECT bucket_id, COALESCE(bucket, 'Unknown'), COUNT(*), uri, type, MAX(taken_at) FROM media GROUP BY bucket_id ORDER BY MAX(taken_at) DESC",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Folder(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3)?.toUri(), c.getInt(4) == 1)) }
        }
    }

    fun observeAlbums(): Flow<List<AlbumSummary>> = observe { albums() }

    private fun albums(): List<AlbumSummary> = db.rawQuery(
        """
        SELECT a.id, a.name,
            (SELECT COUNT(*) FROM album_items ai WHERE ai.album_id = a.id),
            (SELECT m.uri FROM album_items ai JOIN media m ON m.id = ai.media_id WHERE ai.album_id = a.id ORDER BY ai.added_at DESC LIMIT 1)
        FROM albums a ORDER BY a.created_at DESC
        """.trimIndent(),
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(AlbumSummary(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3)?.toUri())) } }

    fun observeAlbumName(id: Long): Flow<String?> = observe { albums().firstOrNull { it.id == id }?.name }

    fun observePeople(includeHidden: Boolean = false): Flow<List<PersonSummary>> = observe { people(includeHidden) }

    private fun people(includeHidden: Boolean): List<PersonSummary> = db.rawQuery(
        """
        SELECT p.id, p.name, COUNT(DISTINCT f.media_id) AS n, (SELECT thumb FROM faces WHERE id = p.cover_face), p.hidden
        FROM people p JOIN faces f ON f.person_id = p.id
        ${if (includeHidden) "" else "WHERE p.hidden = 0"}
        GROUP BY p.id HAVING n >= 2
        ORDER BY (p.name IS NULL), n DESC
        """.trimIndent(),
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(PersonSummary(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4) == 1)) } }

    fun observePersonSummary(id: Long): Flow<PersonSummary?> = observe { people(includeHidden = true).firstOrNull { it.id == id } }

    fun observeDetail(id: Long): Flow<MediaDetail?> = observe { detail(id) }

    private fun detail(id: Long): MediaDetail? {
        var caption: String? = null
        val (media, path, text) = db.rawQuery("SELECT $MEDIA_COLS, m.path, m.ocr_text, m.caption FROM media m WHERE m.id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            caption = c.getString(MEDIA_COL_COUNT + 2)
            Triple(c.media(), c.getString(MEDIA_COL_COUNT), c.getString(MEDIA_COL_COUNT + 1) ?: "")
        }
        val albums = db.rawQuery(
            "SELECT a.id, a.name FROM albums a JOIN album_items ai ON ai.album_id = a.id WHERE ai.media_id = ? ORDER BY a.name",
            arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(AlbumSummary(c.getLong(0), c.getString(1), 0, null)) } }
        val people = db.rawQuery(
            "SELECT DISTINCT p.id, p.name, (SELECT thumb FROM faces WHERE id = p.cover_face), p.hidden FROM people p JOIN faces f ON f.person_id = p.id WHERE f.media_id = ?",
            arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(PersonSummary(c.getLong(0), c.getString(1), 0, c.getString(2), c.getInt(3) == 1)) } }
        return MediaDetail(media, path, text, albums, people, caption)
    }

    fun observeCounts(): Flow<IndexCounts> = observe { counts() }

    fun counts(): IndexCounts {
        var total = 0; var indexed = 0; var pending = 0
        db.rawQuery("SELECT state, COUNT(*) FROM media GROUP BY state", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1)
                total += n
                when (c.getInt(0)) {
                    STATE_PENDING -> pending += n
                    STATE_INDEXED -> indexed += n
                }
            }
        }
        return IndexCounts(total, indexed, pending)
    }

    suspend fun mediaByIds(ids: Collection<Long>): Map<Long, Media> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        ids.chunked(500).flatMap { chunk ->
            db.rawQuery("SELECT $MEDIA_COLS FROM media m WHERE m.id IN (${chunk.joinToString(",")})", null).use { it.mediaList() }
        }.associateBy { it.id }
    }

    suspend fun idsTakenBetween(start: Long, end: Long): Set<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id FROM media WHERE taken_at >= ? AND taken_at < ?", arrayOf(start.toString(), end.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    data class Pending(val id: Long, val uri: android.net.Uri, val isVideo: Boolean, val name: String, val path: String?)

    suspend fun pending(limit: Int): List<Pending> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, uri, type, name, path FROM media WHERE state = ? ORDER BY taken_at DESC LIMIT ?",
            arrayOf(STATE_PENDING.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Pending(c.getLong(0), c.getString(1).toUri(), c.getInt(2) == 1, c.getString(3), c.getString(4))) } }
    }

    // ---------------------------------------------------------------- search

    /**
     * People by name, then whole words in file names, text in photos, descriptions and keywords
     * ("car" finds cars, not carpets); word starts only when nothing matches whole words.
     */
    suspend fun textSearch(query: String): List<Long> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<Long>()
        val terms = FtsQuery.terms(query)
        if (terms.isEmpty()) return@withContext emptyList()
        // A person's name, or one whole word of it ("car" must not find Carol).
        val name = query.trim().lowercase().replace(Regex("[%_]"), "")
        db.rawQuery(
            "SELECT DISTINCT f.media_id FROM faces f JOIN people p ON p.id = f.person_id JOIN media m ON m.id = f.media_id " +
                "WHERE (' ' || LOWER(p.name) || ' ') LIKE ? ORDER BY m.taken_at DESC",
            arrayOf("% $name %"),
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        fun fts(match: String?): List<Long> {
            if (match == null) return emptyList()
            return try {
                db.rawQuery(
                    "SELECT f.docid FROM media_fts f JOIN media m ON m.id = f.docid WHERE media_fts MATCH ? ORDER BY m.taken_at DESC LIMIT 2000",
                    arrayOf(match),
                ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            } catch (_: SQLiteException) {
                emptyList()
            }
        }
        out += fts(FtsQuery.words(query)).ifEmpty { fts(FtsQuery.build(query)) }
        out.toList()
    }

    /** Ids the AI has described: search trusts their descriptions and keywords over look-alike matching. */
    suspend fun describedIds(): Set<Long> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id FROM media WHERE caption_state = ?", arrayOf(CAPTION_DONE.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    /** Look-alike matches (CLIP), only among [filter]ed ids, with a strict similarity floor. */
    suspend fun visualSearch(query: String, clip: ClipModel, filter: ((Long) -> Boolean)? = null): List<VectorHit> = withContext(Dispatchers.Default) {
        ensureVectors()
        if (vectorIndex.size == 0) return@withContext emptyList()
        HybridRanker.filterVisual(vectorIndex.search(clip.embedQuery(query), 300, filter), floor = 0.22f, dropFromTop = 0.04f, max = 120)
    }

    suspend fun similar(id: Long, limit: Int = 18): List<Long> = withContext(Dispatchers.Default) {
        ensureVectors()
        vectorIndex.similarTo(id, limit).filter { it.score >= 0.6f }.map { it.id }
    }

    private suspend fun ensureVectors() {
        if (vectorsLoaded) return
        vectorLoad.withLock {
            if (vectorsLoaded) return
            withContext(Dispatchers.IO) {
                val byId = HashMap<Long, MutableList<QuantizedVector>>()
                db.rawQuery("SELECT media_id, vec FROM embeddings ORDER BY media_id, crop", null).use { c ->
                    while (c.moveToNext()) byId.getOrPut(c.getLong(0)) { ArrayList(2) } += QuantizedVector.fromBlob(c.getBlob(1))
                }
                byId.forEach { (id, v) -> vectorIndex.put(id, v) }
            }
            vectorsLoaded = true
        }
    }

    suspend fun recentSearches(): List<String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT query FROM recent_searches ORDER BY at DESC LIMIT 8", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    suspend fun addRecentSearch(query: String) = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext
        db.insertWithOnConflict("recent_searches", null, ContentValues().apply {
            put("query", q)
            put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun clearRecentSearches() = withContext(Dispatchers.IO) { db.delete("recent_searches", null, null); Unit }

    // ---------------------------------------------------------------- sync + index writes

    suspend fun sync(items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        val existing = HashMap<Long, Long>()
        db.rawQuery("SELECT id, taken_at FROM media", null).use { c -> while (c.moveToNext()) existing[c.getLong(0)] = c.getLong(1) }
        val current = items.associateBy { it.id }
        val removed = existing.keys - current.keys
        var added = 0
        db.beginTransaction()
        try {
            for (item in items) {
                if (item.id in existing) continue
                db.insertWithOnConflict("media", null, ContentValues().apply {
                    put("id", item.id)
                    put("uri", item.uri.toString())
                    put("type", if (item.type == MediaType.VIDEO) 1 else 0)
                    put("name", item.displayName)
                    put("mime", item.mimeType)
                    put("taken_at", item.takenAt)
                    put("added_at", item.addedAt)
                    put("width", item.width)
                    put("height", item.height)
                    put("size", item.sizeBytes)
                    put("duration", item.durationMs)
                    put("bucket_id", item.bucketId)
                    put("bucket", item.bucketName)
                    put("path", item.relativePath)
                    put("favorite", if (item.isFavorite) 1 else 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL(
                    "INSERT INTO media_fts(docid, ${GalleryDatabase.FTS_COLUMNS}) VALUES(?, ?, '', '', '')",
                    arrayOf<Any>(item.id, item.displayName),
                )
                added++
            }
            removed.forEach { deleteRow(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        removed.forEach { vectorIndex.remove(it) }
        if (added > 0 || removed.isNotEmpty()) changed()
        added
    }

    private fun deleteRow(id: Long) {
        val arg = arrayOf(id.toString())
        db.delete("media", "id = ?", arg)
        db.delete("media_fts", "docid = ?", arg)
        db.delete("embeddings", "media_id = ?", arg)
        db.delete("album_items", "media_id = ?", arg)
        db.delete("faces", "media_id = ?", arg)
    }

    suspend fun saveAnalysis(id: Long, a: GalleryAnalysis) = withContext(Dispatchers.IO) {
        val crops = a.cropEmbeddings.map { QuantizedVector.of(it) }
        db.beginTransaction()
        try {
            db.update("media", ContentValues().apply {
                put("state", STATE_INDEXED)
                put("tags", if (a.tags.isEmpty()) "" else a.tags.joinToString(",", ",", ","))
                put("ocr_text", a.ocrText)
                if (a.dHash != null) put("dhash", a.dHash) else putNull("dhash")
                if (a.sharpness != null) put("sharpness", a.sharpness) else putNull("sharpness")
                put("index_version", GalleryDatabase.INDEX_VERSION)
                put("ocr_pending", if (a.ocrPending) 1 else 0)
            }, "id = ?", arrayOf(id.toString()))
            rewriteFts(id)
            db.delete("embeddings", "media_id = ?", arrayOf(id.toString()))
            crops.forEachIndexed { i, q ->
                db.insert("embeddings", null, ContentValues().apply {
                    put("media_id", id)
                    put("crop", i)
                    put("vec", q.toBlob())
                })
            }
            db.delete("faces", "media_id = ?", arrayOf(id.toString()))
            a.faces.forEach { insertFace(id, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (vectorsLoaded) vectorIndex.put(id, crops)
        changed()
    }

    private fun peopleCache(): MutableList<FaceClusterer.Person> = people ?: db.rawQuery("SELECT id, centroid, face_count FROM people", null).use { c ->
        buildList {
            while (c.moveToNext()) add(FaceClusterer.Person(c.getLong(0), QuantizedVector.fromBlob(c.getBlob(1)).toFloats().let(VectorMath::l2Normalize), c.getInt(2)))
        }.toMutableList()
    }.also { people = it }

    private fun insertFace(mediaId: Long, f: FaceResult) {
        val cache = peopleCache()
        val match = clusterer.assign(f.embedding, cache)
        val personId = if (match != null) {
            clusterer.absorb(match, f.embedding)
            db.update("people", ContentValues().apply {
                put("centroid", QuantizedVector.of(match.centroid).toBlob())
                put("face_count", match.count)
            }, "id = ?", arrayOf(match.id.toString()))
            match.id
        } else {
            val newId = db.insert("people", null, ContentValues().apply {
                put("centroid", QuantizedVector.of(f.embedding).toBlob())
                put("face_count", 1)
            })
            cache += FaceClusterer.Person(newId, f.embedding, 1)
            newId
        }
        val faceId = db.insert("faces", null, ContentValues().apply {
            put("media_id", mediaId)
            put("person_id", personId)
            put("l", f.left); put("t", f.top); put("r", f.right); put("b", f.bottom)
            put("quality", f.quality)
            put("vec", QuantizedVector.of(f.embedding).toBlob())
            put("thumb", f.thumbPath)
        })
        db.execSQL(
            "UPDATE people SET cover_face = ? WHERE id = ? AND (cover_face IS NULL OR (SELECT quality FROM faces WHERE id = cover_face) < ?)",
            arrayOf<Any>(faceId, personId, f.quality),
        )
    }

    /** Photos indexed while the text model was still downloading. */
    private fun rewriteFts(id: Long) {
        db.delete("media_fts", "docid = ?", arrayOf(id.toString()))
        db.execSQL(
            "INSERT INTO media_fts(docid, ${GalleryDatabase.FTS_COLUMNS}) SELECT id, ${GalleryDatabase.FTS_SOURCE} FROM media WHERE id = ?",
            arrayOf<Any>(id),
        )
    }

    // ---------------------------------------------------------------- photo descriptions

    data class CaptionJob(val id: Long, val uri: android.net.Uri, val name: String)

    /** Analysed photos (not videos) still waiting for a description, newest first; [since] limits to recent ones. */
    suspend fun captionQueue(limit: Int, since: Long = 0L): List<CaptionJob> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, uri, name FROM media WHERE type = 0 AND state = ? AND caption_state = ? AND taken_at >= ? " +
                "ORDER BY taken_at DESC LIMIT ?",
            arrayOf(STATE_INDEXED.toString(), CAPTION_PENDING.toString(), since.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(CaptionJob(c.getLong(0), c.getString(1).toUri(), c.getString(2))) } }
    }

    suspend fun saveCaption(id: Long, caption: String, keywords: List<String>) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            val kw = if (keywords.isEmpty()) "" else keywords.joinToString(",", ",", ",") { it.replace(",", " ") }
            db.execSQL("UPDATE media SET caption = ?, keywords = ?, caption_state = ? WHERE id = ?", arrayOf<Any>(caption, kw, CAPTION_DONE, id))
            rewriteFts(id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    suspend fun markCaptionFailed(id: Long) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE media SET caption_state = ? WHERE id = ?", arrayOf<Any>(CAPTION_FAILED, id))
        changed()
    }

    /** Described photos, and photos still waiting. */
    fun captionCounts(): Pair<Int, Int> =
        db.rawQuery(
            "SELECT SUM(caption_state = ?), SUM(caption_state = ?) FROM media WHERE type = 0 AND state = ?",
            arrayOf(CAPTION_DONE.toString(), CAPTION_PENDING.toString(), STATE_INDEXED.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0 }

    fun observeCaptionCounts(): Flow<Pair<Int, Int>> = observe { captionCounts() }

    fun ocrPendingCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM media WHERE ocr_pending = 1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Queues those photos again now that text can be read. */
    suspend fun requeueOcrPending(): Int = withContext(Dispatchers.IO) {
        val n = db.compileStatement("UPDATE media SET state = $STATE_PENDING, ocr_pending = 0 WHERE ocr_pending = 1").use { it.executeUpdateDelete() }
        if (n > 0) changed()
        n
    }

    suspend fun markFailed(id: Long) = withContext(Dispatchers.IO) {
        db.execSQL(
            "UPDATE media SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= 3 THEN ? ELSE ? END WHERE id = ?",
            arrayOf<Any>(STATE_FAILED, STATE_PENDING, id),
        )
        changed()
    }

    suspend fun requeueAll() = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE media SET state = ?, attempts = 0", arrayOf<Any>(STATE_PENDING))
        changed()
    }

    // ---------------------------------------------------------------- user edits

    suspend fun setFavorite(ids: Collection<Long>, favorite: Boolean) = updateFlag(ids, "favorite", favorite)
    suspend fun setArchived(ids: Collection<Long>, archived: Boolean) = updateFlag(ids, "archived", archived)

    private suspend fun updateFlag(ids: Collection<Long>, column: String, value: Boolean) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ids.chunked(500).forEach { chunk ->
            db.execSQL("UPDATE media SET $column = ${if (value) 1 else 0} WHERE id IN (${chunk.joinToString(",")})")
        }
        changed()
    }

    suspend fun createAlbum(name: String): Long = withContext(Dispatchers.IO) {
        db.insert("albums", null, ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }).also { changed() }
    }

    suspend fun renameAlbum(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.update("albums", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    suspend fun deleteAlbum(id: Long) = withContext(Dispatchers.IO) {
        db.delete("album_items", "album_id = ?", arrayOf(id.toString()))
        db.delete("albums", "id = ?", arrayOf(id.toString()))
        changed()
    }

    suspend fun addToAlbum(albumId: Long, ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            ids.forEach {
                db.insertWithOnConflict("album_items", null, ContentValues().apply {
                    put("album_id", albumId); put("media_id", it); put("added_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    suspend fun removeFromAlbum(albumId: Long, ids: Collection<Long>) = withContext(Dispatchers.IO) {
        ids.forEach { db.delete("album_items", "album_id = ? AND media_id = ?", arrayOf(albumId.toString(), it.toString())) }
        changed()
    }

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

    suspend fun renamePerson(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.update("people", ContentValues().apply { put("name", name.trim().ifEmpty { null }) }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    suspend fun setPersonHidden(id: Long, hidden: Boolean) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE people SET hidden = ? WHERE id = ?", arrayOf<Any>(if (hidden) 1 else 0, id))
        changed()
    }

    /** Folds [from] into [into]: faces move over and the centroid is re-averaged by face count. */
    suspend fun mergePeople(from: Long, into: Long) = withContext(Dispatchers.IO) {
        if (from == into) return@withContext
        val cache = peopleCache()
        val a = cache.firstOrNull { it.id == from }
        val b = cache.firstOrNull { it.id == into }
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET person_id = ? WHERE person_id = ?", arrayOf<Any>(into, from))
            if (a != null && b != null) {
                val merged = VectorMath.l2Normalize(FloatArray(b.centroid.size) { (b.centroid[it] * b.count + a.centroid[it] * a.count) / (a.count + b.count) })
                b.centroid = merged
                b.count += a.count
                db.update("people", ContentValues().apply {
                    put("centroid", QuantizedVector.of(merged).toBlob())
                    put("face_count", b.count)
                }, "id = ?", arrayOf(into.toString()))
            }
            db.execSQL(
                "UPDATE people SET name = COALESCE(name, (SELECT name FROM people WHERE id = ?)) WHERE id = ?",
                arrayOf<Any>(from, into),
            )
            db.delete("people", "id = ?", arrayOf(from.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cache.removeAll { it.id == from }
        changed()
    }

    // ---------------------------------------------------------------- locked folder

    fun observeLocked(): Flow<List<LockedItem>> = observe {
        db.rawQuery("SELECT id, file, name, mime, type, taken_at, size FROM locked ORDER BY taken_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(LockedItem(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getLong(5), c.getLong(6))) }
        }
    }

    suspend fun addLocked(file: String, media: Media): Long = withContext(Dispatchers.IO) {
        db.insert("locked", null, ContentValues().apply {
            put("file", file)
            put("name", media.name)
            put("mime", media.mime ?: if (media.isVideo) "video/mp4" else "image/jpeg")
            put("type", if (media.isVideo) 1 else 0)
            put("taken_at", media.takenAt)
            put("width", media.width)
            put("height", media.height)
            put("size", media.sizeBytes)
            put("duration", media.durationMs)
            put("added_at", System.currentTimeMillis())
        }).also { changed() }
    }

    suspend fun removeLocked(id: Long) = withContext(Dispatchers.IO) {
        db.delete("locked", "id = ?", arrayOf(id.toString()))
        changed()
    }

    // ---------------------------------------------------------------- utilities

    /** Photos to check for duplicates. Screenshots are left out: two screens of one app look alike but aren't copies. */
    suspend fun duplicateCandidates(): List<ImageQuality.Candidate> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT id, dhash, taken_at, width * height, size, COALESCE(sharpness, 0), favorite, width, height FROM media " +
                "WHERE dhash IS NOT NULL AND type = 0 AND COALESCE(path, '') NOT LIKE '%Screenshot%' AND COALESCE(bucket, '') NOT LIKE '%Screenshot%'",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val h = c.getInt(8)
                    add(
                        ImageQuality.Candidate(
                            id = c.getLong(0), hash = c.getLong(1), takenAt = c.getLong(2), pixels = c.getLong(3), sizeBytes = c.getLong(4),
                            sharpness = c.getDouble(5), favorite = c.getInt(6) == 1, aspect = if (h > 0) c.getInt(7).toFloat() / h else 0f,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Whether two photos look the same to CLIP (cosine at least [min]): the check that keeps two
     * different photos with a similar layout (two dark shots, two white pages) out of a duplicate
     * group. Photos without an embedding are left to the hash.
     */
    suspend fun lookAlike(min: Float = 0.95f): (Long, Long) -> Boolean {
        ensureVectors()
        val cache = HashMap<Long, FloatArray?>()
        fun vector(id: Long): FloatArray? {
            if (id !in cache) cache[id] = vectorIndex.vectorsOf(id)?.takeIf { it.isNotEmpty() }?.let(VectorMath::mean)
            return cache[id]
        }
        return { a, b ->
            val x = vector(a)
            val y = vector(b)
            x == null || y == null || VectorMath.dot(x, y) >= min
        }
    }

    fun observeBlurry(threshold: Double = 60.0): Flow<List<Media>> = observe {
        mediaQuery("m.sharpness IS NOT NULL AND m.sharpness < ? AND m.type = 0", arrayOf(threshold.toString()), order = "m.sharpness ASC")
    }

    fun observeLargeVideos(): Flow<List<Media>> = observe { mediaQuery("m.type = 1 AND m.size > 50000000", order = "m.size DESC") }

    fun observeScreenshots(): Flow<List<Media>> = observe {
        mediaQuery("(m.path LIKE '%Screenshot%' OR m.bucket LIKE '%Screenshot%')")
    }

    // ---------------------------------------------------------------- mapping

    private fun Cursor.media(): Media = Media(
        id = getLong(0),
        uri = getString(1).toUri(),
        type = if (getInt(2) == 1) MediaType.VIDEO else MediaType.IMAGE,
        name = getString(3),
        mime = getString(4),
        takenAt = getLong(5),
        width = getInt(6),
        height = getInt(7),
        sizeBytes = getLong(8),
        durationMs = getLong(9),
        bucketId = getLong(10),
        bucket = getString(11),
        favorite = getInt(12) == 1,
        archived = getInt(13) == 1,
        keywords = getString(14).split(',').filter { it.isNotEmpty() },
        described = getInt(15) == CAPTION_DONE,
    )

    private fun Cursor.mediaList(): List<Media> = buildList(count) { while (moveToNext()) add(media()) }

    companion object {
        const val STATE_PENDING = 0
        const val STATE_INDEXED = 1
        const val STATE_FAILED = 2
        const val CAPTION_PENDING = 0
        const val CAPTION_DONE = 1
        const val CAPTION_FAILED = 2
        private const val MEDIA_COLS =
            "m.id, m.uri, m.type, m.name, m.mime, m.taken_at, m.width, m.height, m.size, m.duration, m.bucket_id, m.bucket, m.favorite, m.archived, " +
                "m.keywords, m.caption_state"
        private const val MEDIA_COL_COUNT = 16
    }
}
