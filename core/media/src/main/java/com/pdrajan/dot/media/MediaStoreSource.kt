package com.pdrajan.dot.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaType { IMAGE, VIDEO }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val displayName: String,
    val mimeType: String?,
    /** Capture time when known, otherwise when the file was added. Millis since epoch. */
    val takenAt: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String?,
    val relativePath: String?,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    /** When MediaStore will purge a trashed item (Android 11+). */
    val expiresAt: Long? = null,
    val addedAt: Long = 0L,
)

/** Reads screenshots from MediaStore. */
class MediaStoreSource(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** All screenshots, newest first. Matches the Screenshots folders every phone maker uses. */
    suspend fun screenshots(): List<MediaItem> = withContext(Dispatchers.IO) {
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)" to
                arrayOf("%Screenshots%", "%Screenshot%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/Screenshot%")
        }
        query(imagesUri(), MediaType.IMAGE, selection, args)
    }

    private fun imagesUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun query(
        collection: Uri,
        type: MediaType,
        selection: String?,
        args: Array<String>?,
        onlyTrashed: Boolean = false,
    ): List<MediaItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.DURATION)
            } else {
                add(MediaStore.Images.ImageColumns.DATE_TAKEN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_FAVORITE)
                add(MediaStore.MediaColumns.IS_TRASHED)
                add(MediaStore.MediaColumns.DATE_EXPIRES)
            }
        }.toTypedArray()

        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bundle = Bundle().apply {
                if (selection != null) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                }
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                if (onlyTrashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            resolver.query(collection, projection, bundle, null)
        } else {
            resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        }

        val out = ArrayList<MediaItem>()
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val takenCol = c.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
            val pathCol = c.getColumnIndex("relative_path")
            val durCol = c.getColumnIndex("duration")
            val favCol = c.getColumnIndex("is_favorite")
            val trashCol = c.getColumnIndex("is_trashed")
            val expiresCol = c.getColumnIndex("date_expires")
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val added = c.getLong(addedCol) * 1000
                val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    type = type,
                    displayName = c.getString(nameCol) ?: "",
                    mimeType = c.getString(mimeCol),
                    takenAt = if (taken > 0) taken else added,
                    modifiedAt = c.getLong(modCol) * 1000,
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    sizeBytes = c.getLong(sizeCol),
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                    bucketId = c.getLong(bucketIdCol),
                    bucketName = c.getString(bucketCol),
                    relativePath = if (pathCol >= 0) c.getString(pathCol) else null,
                    isFavorite = favCol >= 0 && c.getInt(favCol) == 1,
                    isTrashed = trashCol >= 0 && c.getInt(trashCol) == 1,
                    expiresAt = if (expiresCol >= 0 && !c.isNull(expiresCol)) c.getLong(expiresCol) * 1000 else null,
                    addedAt = added,
                )
            }
        }
        return out
    }
}
