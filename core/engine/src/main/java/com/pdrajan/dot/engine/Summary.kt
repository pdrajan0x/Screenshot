package com.pdrajan.dot.engine

/** One line of OCR text with its vertical position, as a fraction of the image height (0 = top). */
data class OcrLine(val text: String, val top: Float, val bottom: Float)

/** What the on-device language model wrote about a screenshot. */
data class ScreenshotSummary(
    val title: String,
    val summary: String,
    /** The model's guess at the source app; null when it said "unknown". */
    val app: String?,
    val tags: List<String>,
)

/**
 * Prompt for the summary model (Qwen3 / Qwen2.5 ChatML). The model sees the source app when we
 * know it and the OCR text top to bottom, and answers in a fixed four-line format enforced by
 * [GRAMMAR].
 */
object SummaryPrompt {

    const val SYSTEM =
        "You write search labels for phone screenshots. You get the source app (if known) and the text read " +
            "from the screen by OCR, top to bottom. OCR can be messy and may mix Hindi, Hinglish and English.\n" +
            "Reply in English, in exactly this format:\n" +
            "Title: <3-8 words: what this is and who or what it is about>\n" +
            "Summary: <1-2 plain sentences with the key facts: names, amounts, dates, places, items, what was said or done>\n" +
            "App: <the app this screenshot is from, or unknown>\n" +
            "Tags: <6-10 lowercase search keywords, comma separated: the app, people, topics and the kind of screen>\n" +
            "Rules: use ₹ for Indian rupees. In a chat, the name at the top is the person being chatted with. " +
            "Only use facts from the screen."

    /** GBNF grammar: the four labelled lines, nothing else. */
    const val GRAMMAR =
        "root ::= \"Title: \" txt \"\\nSummary: \" txt \"\\nApp: \" txt \"\\nTags: \" txt \"\\n\"?\n" +
            "txt ::= [^\\n]+\n"

    /** Most screens fit; long scrolling captures are cut at a line boundary to keep it quick. */
    const val MAX_SCREEN_CHARS = 1400

    fun build(app: String?, screenText: String, thinkingModel: Boolean = true): String {
        val text = clip(screenText.trim(), MAX_SCREEN_CHARS)
        val user = buildString {
            append("App: ").append(app?.takeIf { it.isNotBlank() } ?: "unknown").append('\n')
            append("Screen text:\n").append(text.ifEmpty { "(no text on screen)" })
        }
        return buildString {
            append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
            append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            // Qwen3 thinks out loud by default; an empty think block switches that off.
            if (thinkingModel) append("<think>\n\n</think>\n\n")
        }
    }

    internal fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max).takeIf { it > max / 2 } ?: max
        return text.substring(0, cut)
    }
}

object SummaryParser {

    private val FIELD = Regex("^\\s*(title|summary|app|tags)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
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
        val tags = fields["tags"].orEmpty()
            .split(',', ';')
            .map { it.trim().lowercase().replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim('#', ' ', '.') }
            .filter { it.length in 2..40 }
            .distinct()
            .take(12)
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
