package com.pdrajan.dot.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Writes new files into shared storage (edited copies, exported videos, restored locked items). */
object MediaWriter {

    const val ALBUM_DIR = "Dot Gallery"

    suspend fun saveJpeg(context: Context, bitmap: Bitmap, displayName: String, quality: Int = 95): Uri? =
        insert(context, displayName, "image/jpeg", isVideo = false) { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }

    suspend fun saveFile(context: Context, file: File, displayName: String, mimeType: String): Uri? =
        file.inputStream().use { input -> saveStream(context, input, displayName, mimeType) }

    suspend fun saveStream(context: Context, input: InputStream, displayName: String, mimeType: String): Uri? =
        insert(context, displayName, mimeType, isVideo = mimeType.startsWith("video/")) { out -> input.copyTo(out) }

    private suspend fun insert(
        context: Context,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + ALBUM_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), ALBUM_DIR)
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
