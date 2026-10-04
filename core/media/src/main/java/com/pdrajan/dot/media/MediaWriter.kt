package com.pdrajan.dot.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes new files into shared storage (edited copies). */
object MediaWriter {

    /**
     * Saves [file] as a new image in [folder] (relative to shared storage, e.g. "DCIM/Screenshots",
     * so a copy sits next to its original), dated [takenAt] so it shows up beside it in the timeline.
     */
    suspend fun saveImage(context: Context, file: File, displayName: String, mimeType: String, folder: String, takenAt: Long?): Uri? =
        file.inputStream().use { input -> insert(context, displayName, mimeType, folder, takenAt) { out -> input.copyTo(out) } }

    private suspend fun insert(
        context: Context,
        displayName: String,
        mimeType: String,
        folder: String,
        takenAt: Long?,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (takenAt != null) put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder.trimEnd('/') + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStorageDirectory(), folder)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(collection, values) ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("no output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
