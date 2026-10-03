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

/** Camera details for the info panel. */
data class PhotoInfo(
    val camera: String? = null,
    val lens: String? = null,
    val aperture: String? = null,
    val exposure: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

object MediaInfo {

    suspend fun read(context: Context, uri: Uri): PhotoInfo = withContext(Dispatchers.IO) {
        runCatching {
            // Android 10+ strips GPS from EXIF unless the app holds ACCESS_MEDIA_LOCATION and asks for the original.
            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationAccess(context)) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }
            context.contentResolver.openInputStream(source)?.use { input ->
                val exif = ExifInterface(input)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                val camera = when {
                    model == null -> make
                    make == null || model.startsWith(make, ignoreCase = true) -> model
                    else -> "$make $model"
                }
                val latLong = exif.latLong
                PhotoInfo(
                    camera = camera,
                    lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
                    aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "ƒ/%.1f".format(it) },
                    exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                        if (it >= 1) "%.1fs".format(it) else "1/${Math.round(1 / it)}"
                    },
                    iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
                    focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.1f mm".format(it) },
                    latitude = latLong?.getOrNull(0),
                    longitude = latLong?.getOrNull(1),
                )
            } ?: PhotoInfo()
        }.getOrDefault(PhotoInfo())
    }

    fun hasLocationAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
}
