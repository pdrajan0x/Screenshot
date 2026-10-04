package com.pdrajan.dot.ml

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pdrajan.dot.engine.OcrLine
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** Text read from a screenshot: joined top to bottom, plus each line with its position. */
data class OcrResult(val text: String, val lines: List<OcrLine>)

/**
 * On-device OCR with ML Kit through Google Play services: the models are fetched once at install
 * (see the manifest's com.google.mlkit.vision.DEPENDENCIES) and screenshots read before they
 * arrive are read again later ([isModelUnavailable]). The Devanagari recogniser also reads Latin
 * script, so Hindi mode needs a single pass.
 */
class TextReader(val hindi: Boolean) : Closeable {

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        if (hindi) DevanagariTextRecognizerOptions.Builder().build() else TextRecognizerOptions.DEFAULT_OPTIONS,
    )

    /** Blocking; call from a background thread. The screen's text, top to bottom. */
    fun read(bitmap: Bitmap): String = readLines(bitmap).text

    /**
     * Blocking. Every text line with its position (fraction of the image height), sorted top to
     * bottom and left to right. Very tall (scrolling) screenshots are read in overlapping slices.
     */
    fun readLines(bitmap: Bitmap): OcrResult {
        val height = bitmap.height.toFloat()
        val found = ArrayList<Placed>()
        val sliceHeight = bitmap.width * 2
        if (bitmap.height <= sliceHeight + bitmap.width / 2) {
            collect(bitmap, 0, height, found)
        } else {
            var top = 0
            val overlap = bitmap.width / 10
            while (top < bitmap.height) {
                val h = minOf(sliceHeight, bitmap.height - top)
                val slice = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, h)
                try {
                    collect(slice, top, height, found)
                } finally {
                    if (slice !== bitmap) slice.recycle()
                }
                if (top + h >= bitmap.height) break
                top += h - overlap
            }
        }
        // Lines inside a slice overlap were read twice.
        val unique = ArrayList<Placed>(found.size)
        for (p in found.sortedWith(compareBy<Placed> { it.line.top }.thenBy { it.left })) {
            if (unique.none { it.line.text == p.line.text && kotlin.math.abs(it.line.top - p.line.top) < 0.02f }) unique += p
        }
        // Group into visual rows (tops within ~1% of the height), each read left to right.
        val lines = ArrayList<OcrLine>(unique.size)
        var row = ArrayList<Placed>()
        for (p in unique) {
            if (row.isNotEmpty() && p.line.top - row.first().line.top > 0.01f) {
                row.sortedBy { it.left }.mapTo(lines) { it.line }
                row = ArrayList()
            }
            row += p
        }
        row.sortedBy { it.left }.mapTo(lines) { it.line }
        return OcrResult(lines.joinToString("\n") { it.text }, lines)
    }

    private class Placed(val line: OcrLine, val left: Int)

    private fun collect(bitmap: Bitmap, offsetY: Int, fullHeight: Float, into: MutableList<Placed>) {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isEmpty()) continue
                into += Placed(OcrLine(text, (offsetY + box.top) / fullHeight, (offsetY + box.bottom) / fullHeight), box.left)
            }
        }
    }

    private fun readOne(bitmap: Bitmap): String {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        return result.text
    }

    /** Whether the text model is ready, checked with a tiny blank image (no UI, a few ms). */
    fun isModelReady(): Boolean {
        val probe = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            readOne(probe)
            true
        } catch (e: Exception) {
            !isModelUnavailable(e)
        } finally {
            probe.recycle()
        }
    }

    override fun close() = recognizer.close()

    companion object {
        /**
         * True when OCR failed because Play services is still fetching the model: a reason to retry
         * later rather than mark the screenshot as failed.
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
