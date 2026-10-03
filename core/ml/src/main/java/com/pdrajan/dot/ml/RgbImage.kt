package com.pdrajan.dot.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/** An image as packed RGB bytes, the input the on-device vision model takes. */
class RgbImage(val bytes: ByteArray, val width: Int, val height: Int) {

    companion object {
        /** The image at most [maxSide] px on its longest side. */
        fun load(resolver: ContentResolver, uri: Uri, maxSide: Int): RgbImage {
            val decoded = BitmapLoader.load(resolver, uri, maxWidth = maxSide * 2, maxPixels = maxSide * maxSide * 4)
            val scale = maxSide.toFloat() / max(decoded.width, decoded.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }
            try {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val rgb = ByteArray(w * h * 3)
                var k = 0
                for (p in pixels) {
                    rgb[k++] = (p shr 16).toByte()
                    rgb[k++] = (p shr 8).toByte()
                    rgb[k++] = p.toByte()
                }
                return RgbImage(rgb, w, h)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
