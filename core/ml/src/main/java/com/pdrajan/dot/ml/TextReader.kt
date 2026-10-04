package com.pdrajan.dot.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pdrajan.dot.engine.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.resume
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

    private val recognizer: TextRecognizer = client(hindi)

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
        private fun client(hindi: Boolean): TextRecognizer = TextRecognition.getClient(
            if (hindi) DevanagariTextRecognizerOptions.Builder().build() else TextRecognizerOptions.DEFAULT_OPTIONS,
        )

        /** Whether Play services has the text model on the phone; null when it can't say (no Play services). Blocking. */
        fun modelInstalled(context: Context, hindi: Boolean): Boolean? {
            val recognizer = client(hindi)
            return try {
                Tasks.await(ModuleInstall.getClient(context).areModulesAvailable(recognizer), 20, TimeUnit.SECONDS).areModulesAvailable()
            } catch (e: Exception) {
                null
            } finally {
                recognizer.close()
            }
        }

        /**
         * Asks Play services to fetch the text model now rather than whenever it gets round to it,
         * and waits for it. [onProgress] gets 0..1 while it downloads (null when unknown). True
         * once it's installed, false if Play services can't get it.
         */
        suspend fun installModel(context: Context, hindi: Boolean, onProgress: (Float?) -> Unit = {}): Boolean {
            if (withContext(Dispatchers.IO) { modelInstalled(context, hindi) } == true) return true
            val recognizer = client(hindi)
            val installer = ModuleInstall.getClient(context)
            try {
                return suspendCancellableCoroutine { cont ->
                    fun finish(ok: Boolean, listener: InstallStatusListener?) {
                        listener?.let { installer.unregisterListener(it) }
                        if (cont.isActive) cont.resume(ok)
                    }
                    val listener = object : InstallStatusListener {
                        override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                            when (update.installState) {
                                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finish(true, this)
                                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> finish(false, this)
                                else -> update.progressInfo?.let { p ->
                                    onProgress(if (p.totalBytesToDownload > 0) p.bytesDownloaded.toFloat() / p.totalBytesToDownload else null)
                                }
                            }
                        }
                    }
                    val request = ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener).build()
                    installer.installModules(request)
                        .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) finish(true, listener) }
                        .addOnFailureListener { finish(false, listener) }
                    cont.invokeOnCancellation { installer.unregisterListener(listener) }
                }
            } finally {
                recognizer.close()
            }
        }

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
