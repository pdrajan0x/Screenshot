package com.pdrajan.dot.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File details for the viewer's info panel; null where the phone doesn't record it. */
data class FileDetails(
    /** Folder relative to shared storage, e.g. "DCIM/Screenshots". */
    val folder: String? = null,
    val mimeType: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

object MediaInfo {

    suspend fun read(context: Context, uri: Uri): FileDetails = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var folder: String? = null
        var mime: String? = null
        runCatching {
            val columns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE)
            } else {
                @Suppress("DEPRECATION")
                arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE)
            }
            resolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val path = c.getString(0)
                    folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) path?.trimEnd('/')
                    else path?.substringBeforeLast('/')?.substringAfter("/0/")
                    mime = c.getString(1)
                }
            }
        }
        val latLong = runCatching {
            // Android 10+ strips GPS from EXIF unless the app holds ACCESS_MEDIA_LOCATION and asks for the original.
            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationAccess(context)) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }
            resolver.openInputStream(source)?.use { ExifInterface(it).latLong }
        }.getOrNull()
        FileDetails(folder, mime, latLong?.getOrNull(0), latLong?.getOrNull(1))
    }

    fun hasLocationAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
}
