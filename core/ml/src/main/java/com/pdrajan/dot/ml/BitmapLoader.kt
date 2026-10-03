package com.pdrajan.dot.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Decodes a software ARGB_8888 bitmap sized for OCR + CLIP (≤ [maxWidth] wide, ≤ [maxPixels]). */
object BitmapLoader {

    fun load(resolver: ContentResolver, uri: Uri, maxWidth: Int = 1080, maxPixels: Int = 10_000_000): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val (w, h) = targetSize(info.size.width, info.size.height, maxWidth, maxPixels)
                if (w != info.size.width) decoder.setTargetSize(w, h)
            }.let { ensureArgb(it) }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val (w, h) = targetSize(bounds.outWidth, bounds.outHeight, maxWidth, maxPixels)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Could not decode $uri")
            if (decoded.width > w) Bitmap.createScaledBitmap(decoded, w, h, true).also { decoded.recycle() } else decoded
        }
    }

    private fun targetSize(width: Int, height: Int, maxWidth: Int, maxPixels: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        var scale = 1.0
        if (width > maxWidth) scale = maxWidth.toDouble() / width
        val pixels = width * scale * height * scale
        if (pixels > maxPixels) scale *= sqrt(maxPixels / pixels)
        if (scale >= 1.0) return width to height
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
}
