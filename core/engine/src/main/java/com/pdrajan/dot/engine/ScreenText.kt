package com.pdrajan.dot.engine

/** One line of OCR text with its vertical position, as a fraction of the image height (0 = top). */
data class OcrLine(val text: String, val top: Float, val bottom: Float)

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

    /** The page a browser screenshot shows, from its stored text (OCR keeps screen order, the address bar first). */
    fun inText(text: String): String? =
        find(text.lineSequence().filter { it.isNotBlank() }.take(4).map { OcrLine(it, 0.05f, 0.08f) }.toList(), browser = true)
}

/**
 * A screen's headings: the lines in clearly bigger text than the rest (a page or product title, a
 * chat's contact name, a post's headline). Search counts a word there more than one in small print.
 */
object Headline {

    private val CLOCK = Regex("^\\d{1,2}[.:]\\d{2}(\\s?[ap]\\.?m\\.?)?$", RegexOption.IGNORE_CASE)

    fun from(lines: List<OcrLine>, max: Int = 6): String {
        val usable = lines.filter { l -> l.bottom > l.top && l.text.count { it.isLetter() } >= 3 && !CLOCK.matches(l.text.trim()) }
        // Too little text to tell big from normal.
        if (usable.size < 3) return ""
        val heights = usable.map { it.bottom - it.top }.sorted()
        val median = heights[heights.size / 2]
        return usable.filter { it.bottom - it.top >= median * 1.4f }
            .sortedByDescending { it.bottom - it.top }
            .take(max)
            .sortedBy { it.top }
            .joinToString("\n") { it.text }
    }
}
