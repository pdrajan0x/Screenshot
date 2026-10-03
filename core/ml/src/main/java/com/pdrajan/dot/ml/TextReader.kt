package com.pdrajan.dot.ml

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * On-device OCR with ML Kit (models come from Google Play services, not the APK).
 * The Devanagari recogniser also reads Latin script, so Hindi mode needs a single pass.
 */
class TextReader(val hindi: Boolean) : Closeable {

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        if (hindi) DevanagariTextRecognizerOptions.Builder().build() else TextRecognizerOptions.DEFAULT_OPTIONS,
    )

    /** Blocking; call from a background thread. Very tall (scrolling) screenshots are read in slices. */
    fun read(bitmap: Bitmap): String {
        val sliceHeight = bitmap.width * 2
        if (bitmap.height <= sliceHeight + bitmap.width / 2) return readOne(bitmap)
        val parts = ArrayList<String>()
        var top = 0
        val overlap = bitmap.width / 10
        while (top < bitmap.height) {
            val h = minOf(sliceHeight, bitmap.height - top)
            val slice = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, h)
            try {
                parts += readOne(slice)
            } finally {
                if (slice !== bitmap) slice.recycle()
            }
            if (top + h >= bitmap.height) break
            top += h - overlap
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun readOne(bitmap: Bitmap): String {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        return result.text
    }

    override fun close() = recognizer.close()

    companion object {
        /**
         * True when OCR failed because Play services hasn't finished downloading the model yet —
         * a reason to retry later rather than mark the screenshot as failed.
         */
        fun isModelUnavailable(error: Throwable): Boolean {
            var e: Throwable? = error
            while (e != null) {
                if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) return true
                e = e.cause
            }
            return false
        }
    }
}
