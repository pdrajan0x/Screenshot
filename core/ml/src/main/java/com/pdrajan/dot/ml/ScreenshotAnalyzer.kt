package com.pdrajan.dot.ml

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.pdrajan.dot.engine.AppLookClassifier
import com.pdrajan.dot.engine.AppRecognizer
import com.pdrajan.dot.engine.CategoryClassifier
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.EntityExtractor
import com.pdrajan.dot.engine.PageLink
import com.pdrajan.dot.engine.SourceApp
import com.pdrajan.dot.media.DotLog

/** Where the source app came from, best first. */
enum class AppSource(val code: String) {
    /** The system's usage history (earlier versions, with Usage access): exact. */
    USAGE("usage"),
    /** The phone maker put it in the file name. */
    FILE("file"),
    /** Recognised from how the screen looks and its text. */
    VISUAL("visual"),
    /** Earlier versions: the old summary model named it. */
    MODEL("model"),
    /** Best guess from everything known (see AppIdentifier), with a confidence. */
    GUESS("guess"),
    /** Picked by the user. */
    USER("user");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

data class ScreenshotAnalysis(
    val ocrText: String,
    val cropEmbeddings: List<FloatArray>,
    val categories: List<String>,
    val entities: List<Entity>,
    val sourceApp: String?,
    val durationMs: Long,
    /** OCR was skipped because Play services is still downloading the text model; read it later. */
    val ocrPending: Boolean = false,
    val appSource: AppSource? = null,
    val appPackage: String? = null,
    /** For browser screenshots: the page in the address bar. */
    val pageUrl: String? = null,
)

/**
 * Full on-device pipeline for one image: decode → OCR (with line positions) → CLIP crops →
 * source app (file name, or recognised; the library-wide guess comes later) → categories,
 * entities and page link.
 */
class ScreenshotAnalyzer(
    private val context: Context,
    private val clip: ClipModel,
    /** Null when ML Kit couldn't be set up; screenshots are then indexed without text, to be read later. */
    private val reader: TextReader?,
    private val classifier: CategoryClassifier,
    private val appLook: AppLookClassifier? = null,
    private val extractor: EntityExtractor = EntityExtractor(),
) {
    private var loggedOcrError = false

    fun analyze(uri: Uri, displayName: String, runOcr: Boolean = true, maxCrops: Int = 3): ScreenshotAnalysis {
        val start = System.nanoTime()
        val bitmap = BitmapLoader.load(context.contentResolver, uri)
        try {
            var ocrPending = false
            val textReader = reader
            val ocr = if (!runOcr) {
                OcrResult("", emptyList())
            } else if (textReader == null) {
                ocrPending = true
                OcrResult("", emptyList())
            } else try {
                textReader.readLines(bitmap)
            } catch (e: Exception) {
                // OCR trouble never costs the screenshot its visual index.
                ocrPending = TextReader.isModelUnavailable(e)
                if (!ocrPending && !loggedOcrError) {
                    loggedOcrError = true
                    DotLog.e("ocr: text recognition failed; indexing without text", e)
                }
                OcrResult("", emptyList())
            }
            val text = ocr.text
            val crops = clip.embedImage(bitmap, maxCrops)
            val app = resolveApp(displayName, text, crops)
            val browser = AppRecognizer.isBrowser(app?.label, app?.packageName)
            val pageUrl = PageLink.find(ocr.lines, browser)
            val categories = classifier.classify(crops, text, app?.label).categories
            val entities = extractor.extract(text)
            return ScreenshotAnalysis(
                ocrText = text,
                cropEmbeddings = crops,
                categories = categories,
                entities = entities,
                sourceApp = app?.label,
                durationMs = (System.nanoTime() - start) / 1_000_000,
                ocrPending = ocrPending,
                appSource = app?.source,
                appPackage = app?.packageName,
                pageUrl = pageUrl,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private class ResolvedApp(val label: String, val packageName: String?, val source: AppSource)

    private fun resolveApp(displayName: String, text: String, crops: List<FloatArray>): ResolvedApp? {
        SourceApp.fromFileName(displayName)?.let { hint ->
            hint.label?.let { return ResolvedApp(it, null, AppSource.FILE) }
            hint.packageName?.let { return ResolvedApp(label(it), it, AppSource.FILE) }
        }
        val look = appLook?.probabilities(crops)
        return AppRecognizer.recognize(text, look)?.let { ResolvedApp(it.app, null, AppSource.VISUAL) }
    }

    private fun label(pkg: String): String = runCatching {
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
