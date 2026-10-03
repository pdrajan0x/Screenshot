package com.pdrajan.dot.ml

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.pdrajan.dot.engine.CategoryClassifier
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.EntityExtractor
import com.pdrajan.dot.engine.SourceApp
import com.pdrajan.dot.media.DotLog

data class ScreenshotAnalysis(
    val ocrText: String,
    val cropEmbeddings: List<FloatArray>,
    val categories: List<String>,
    val entities: List<Entity>,
    val sourceApp: String?,
    val durationMs: Long,
    /** OCR was skipped because Play services is still downloading the text model; read it later. */
    val ocrPending: Boolean = false,
)

/** Full on-device pipeline for one image: decode → OCR → CLIP crops → categories → entities. */
class ScreenshotAnalyzer(
    private val context: Context,
    private val clip: ClipModel,
    /** Null when ML Kit couldn't be set up; screenshots are then indexed without text, to be read later. */
    private val reader: TextReader?,
    private val classifier: CategoryClassifier,
    private val extractor: EntityExtractor = EntityExtractor(),
) {
    private var loggedOcrError = false

    fun analyze(uri: Uri, displayName: String, runOcr: Boolean = true, maxCrops: Int = 3): ScreenshotAnalysis {
        val start = System.nanoTime()
        val bitmap = BitmapLoader.load(context.contentResolver, uri)
        try {
            var ocrPending = false
            val textReader = reader
            val text = if (!runOcr) {
                ""
            } else if (textReader == null) {
                ocrPending = true
                ""
            } else try {
                textReader.read(bitmap)
            } catch (e: Exception) {
                // OCR trouble never costs the screenshot its visual index.
                ocrPending = TextReader.isModelUnavailable(e)
                if (!ocrPending && !loggedOcrError) {
                    loggedOcrError = true
                    DotLog.e("ocr: text recognition failed; indexing without text", e)
                }
                ""
            }
            val crops = clip.embedImage(bitmap, maxCrops)
            val app = resolveApp(displayName)
            val categories = classifier.classify(crops, text, app).categories
            val entities = extractor.extract(text)
            return ScreenshotAnalysis(text, crops, categories, entities, app, (System.nanoTime() - start) / 1_000_000, ocrPending)
        } finally {
            bitmap.recycle()
        }
    }

    private fun resolveApp(displayName: String): String? {
        val hint = SourceApp.fromFileName(displayName) ?: return null
        hint.label?.let { return it }
        val pkg = hint.packageName ?: return null
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrElse { SourceApp.labelFromPackage(pkg) }
    }
}
