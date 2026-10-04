package com.pdrajan.dot.ml

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.pdrajan.dot.engine.AppHints
import com.pdrajan.dot.engine.AppNames
import com.pdrajan.dot.engine.Browsers
import com.pdrajan.dot.engine.CategoryClassifier
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.EntityExtractor
import com.pdrajan.dot.engine.Headline
import com.pdrajan.dot.engine.PageLink
import com.pdrajan.dot.engine.PictureTagger
import com.pdrajan.dot.engine.SourceApp
import com.pdrajan.dot.media.DotLog

/** Where the source app came from, best first. */
enum class AppSource(val code: String) {
    /** The system's usage history (earlier versions, with Usage access): exact. */
    USAGE("usage"),
    /** The phone maker put it in the file name. */
    FILE("file"),
    /** Recognised from words the app's own interface shows ("Join the conversation" → Reddit). */
    VISUAL("visual"),
    /** Earlier versions: an AI model named it. */
    MODEL("model"),
    /** Earlier versions: a look-alike guess. */
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
    /** Lines in clearly bigger text (titles, names, headlines). */
    val headline: String = "",
    /** What MobileCLIP sees in pictures on the screen ("shoes", "beach"); empty for ordinary app screens. */
    val keywords: List<String> = emptyList(),
)

/**
 * Full on-device pipeline for one image: decode → OCR (with line positions) → CLIP crops →
 * source app (the file name when the phone puts it there, else the screen's interface words) →
 * headings, picture keywords, categories, entities and page link.
 */
class ScreenshotAnalyzer(
    private val context: Context,
    private val clip: ClipModel,
    /** Null when ML Kit couldn't be set up; screenshots are then indexed without text, to be read later. */
    private val reader: TextReader?,
    private val classifier: CategoryClassifier,
    private val tagger: PictureTagger,
    /** The phone's apps, so a recognised app shows under the name the phone uses. */
    private val installed: List<AppNames.Choice>,
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
            val app = appFromFileName(displayName) ?: appFromWords(text)
            val browser = Browsers.isBrowser(app?.label, app?.packageName)
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
                headline = Headline.from(ocr.lines),
                keywords = tagger.keywords(crops),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private class ResolvedApp(val label: String, val packageName: String?, val source: AppSource)

    /** The app named by the screen's own interface words, under the phone's name for it when installed. */
    private fun appFromWords(text: String): ResolvedApp? {
        val name = AppHints.best(text) ?: return null
        val choice = AppNames.match(name, installed) ?: AppNames.Choice(name, null)
        return ResolvedApp(choice.label, choice.packageName, AppSource.VISUAL)
    }

    /** Some phone makers put the app in the screenshot's file name: then it's certain. */
    private fun appFromFileName(displayName: String): ResolvedApp? {
        val hint = SourceApp.fromFileName(displayName) ?: return null
        hint.label?.let { return ResolvedApp(it, null, AppSource.FILE) }
        return hint.packageName?.let { ResolvedApp(label(it), it, AppSource.FILE) }
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
