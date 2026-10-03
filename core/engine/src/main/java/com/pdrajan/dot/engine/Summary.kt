package com.pdrajan.dot.engine

/** One line of OCR text with its vertical position, as a fraction of the image height (0 = top). */
data class OcrLine(val text: String, val top: Float, val bottom: Float)

/** What the on-device vision model wrote about a screenshot. */
data class ScreenshotSummary(
    val title: String,
    val summary: String,
    /** The model's guess at the source app; null when it said "unknown". */
    val app: String?,
    val tags: List<String>,
)

/** Reads the vision model's labelled answer (Title / Summary / App / Keywords) for a screenshot. */
object SummaryParser {

    private val FIELD = Regex("^\\s*(title|summary|app|tags|keywords)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val NO_APP = setOf("unknown", "none", "n/a", "na", "not known", "unclear", "-")

    fun parse(output: String): ScreenshotSummary? {
        val fields = HashMap<String, String>()
        output.lineSequence().forEach { line ->
            val m = FIELD.find(line) ?: return@forEach
            fields.putIfAbsent(m.groupValues[1].lowercase(), clean(m.groupValues[2]))
        }
        val title = fields["title"].orEmpty()
        val summary = fields["summary"].orEmpty()
        if (title.isEmpty() && summary.isEmpty()) return null
        val app = fields["app"]?.takeIf { it.isNotEmpty() && it.lowercase() !in NO_APP }
        val tags = VisionPrompts.cleanKeywords((fields["keywords"] ?: fields["tags"]).orEmpty().replace('-', ' '))
        return ScreenshotSummary(title.take(90), summary.take(400), app?.take(40), tags)
    }

    private fun clean(s: String) = s.replace("*", "").replace("`", "").trim().trimEnd('.').trim()
}

/**
 * The page a browser screenshot shows, read from the address bar: the first URL-looking token
 * near the top of the screen.
 */
object PageLink {

    private val TOKEN = Regex(
        "^(?:https?://)?(?:www\\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*" +
            "\\.([a-z]{2,12})(?::\\d{2,5})?(?:/\\S*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val COMMON_TLDS = setOf(
        "com", "in", "org", "net", "io", "co", "app", "dev", "me", "ai", "edu", "gov", "info", "xyz", "tv", "ly",
        "gl", "uk", "us", "ca", "au", "de", "fr", "jp", "community", "news", "blog", "shop", "store", "site", "page",
        "tech", "online", "live", "link", "so", "to", "gg", "fm", "be",
    )

    /** @param browser the screenshot is known to come from a browser, so the top area is the address bar. */
    fun find(lines: List<OcrLine>, browser: Boolean): String? {
        val region = if (browser) 0.22f else 0.12f
        for (line in lines.sortedBy { it.top }) {
            if (line.top > region) break
            for (raw in line.text.split(' ', '\t')) {
                val token = raw.replace("🔒", "").trim().trim('|', '•', '·', '"', '\'', '(', ')', '[', ']', '<', '>', ',')
                if (token.length < 4 || '@' in token) continue
                val m = TOKEN.matchEntire(token) ?: continue
                val explicit = token.startsWith("http", ignoreCase = true) || token.startsWith("www.", ignoreCase = true)
                if (!browser && !explicit) continue
                if (!explicit && m.groupValues[1].lowercase() !in COMMON_TLDS) continue
                return if (token.startsWith("http", ignoreCase = true)) token else "https://$token"
            }
        }
        return null
    }
}
