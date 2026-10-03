package com.pdrajan.dot.engine

/** What the vision model wrote about a photo. */
data class PhotoDescription(val description: String, val keywords: List<String>)

/**
 * Instructions and output grammars for the on-device vision model (LFM2.5-VL 1.6B), which writes
 * every description, summary and keyword in both apps. The grammars keep its answers in a fixed,
 * parseable shape: a small model otherwise runs fields together or copies the instructions.
 */
object VisionPrompts {

    /** 4-12 short lowercase keywords, comma separated. */
    private const val KEYWORD_RULES =
        "kws ::= kw (\", \" kw){3,11}\n" +
            "kw ::= [a-z0-9] [a-z0-9 '.-]{1,23}\n"

    const val PHOTO =
        "Answer in exactly this format:\n" +
            "Description: <one sentence describing the photo>\n" +
            "Keywords: <8 to 12 lowercase words for what is in the photo, comma separated>"

    /** No colons in the description, so it can't swallow the Keywords line; 5-12 short keywords. */
    const val PHOTO_GRAMMAR =
        "root ::= \"Description: \" desc \"\\nKeywords: \" kws \"\\n\"?\n" +
            "desc ::= [^\\n:]{10,220}\n" +
            KEYWORD_RULES

    /**
     * Screenshots: the model sees the image; the OCR text (often sharper than the shrunk image) comes
     * along, plus [hints]: apps whose own interface words are on screen (see [AppHints]).
     */
    fun screenshot(screenText: String, knownApp: String?, hints: List<String> = emptyList()): String = buildString {
        append("This is a phone screenshot.")
        knownApp?.takeIf { it.isNotBlank() }?.let { append(" It was taken in ").append(it).append('.') }
        val text = clip(screenText.trim(), MAX_SCREEN_CHARS)
        if (text.isNotEmpty()) append(" Text read from it:\n").append(text)
        if (knownApp.isNullOrBlank() && hints.isNotEmpty()) {
            append("\nThe screen has words typical of: ").append(hints.joinToString(" or ")).append('.')
        }
        append("\n\nAnswer in exactly this format:\n")
        append("Title: <3-8 words: what this is>\n")
        append("Summary: <one sentence with the key facts: names, amounts in ₹, dates, items>\n")
        append("App: <the app whose screen this is, like WhatsApp, Instagram, Google Pay, Chrome, YouTube, Gmail; not a brand or site in the content>\n")
        append("Keywords: <6 to 10 lowercase search words about the content, no times>")
    }

    const val SCREENSHOT_GRAMMAR =
        "root ::= \"Title: \" title \"\\nSummary: \" summary \"\\nApp: \" app \"\\nKeywords: \" kws \"\\n\"?\n" +
            "title ::= [^\\n:]{3,60}\n" +
            "summary ::= [^\\n]{10,260}\n" +
            "app ::= [^\\n,:]{2,30}\n" +
            KEYWORD_RULES

    /** Most screens fit; long scrolling captures are cut at a line boundary to keep it quick. */
    const val MAX_SCREEN_CHARS = 1000

    /**
     * How many tokens a picture becomes. Photos: 64 (a ~256 px view) describes them as well as 128
     * in tests, about 30% faster. Screenshots: 128, since a dense screen needs the detail; their text
     * comes from OCR anyway.
     */
    const val PHOTO_IMAGE_TOKENS = 64
    const val SCREENSHOT_IMAGE_TOKENS = 128

    /** Pixels per image token (16 px patches, merged 2×2): the picture size that fills a token budget. */
    const val PIXELS_PER_IMAGE_TOKEN = 1024

    /** Room for the longest grammar-shaped answer. */
    const val MAX_TOKENS = 140

    /** Cuts long text at a line boundary near [max] characters. */
    internal fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max).takeIf { it > max / 2 } ?: max
        return text.substring(0, cut)
    }

    fun parsePhoto(output: String): PhotoDescription? {
        var description = ""
        var keywords = ""
        output.lineSequence().forEach { line ->
            val l = line.trim()
            when {
                l.startsWith("Description:", ignoreCase = true) -> description = l.substringAfter(':').trim()
                l.startsWith("Keywords:", ignoreCase = true) -> keywords = l.substringAfter(':').trim()
            }
        }
        description = description.replace(Regex("\\s+"), " ").trim()
        if (description.isEmpty()) return null
        if (description.last() !in ".!?") description += "."
        return PhotoDescription(description, cleanKeywords(keywords))
    }

    /** Lowercase, distinct, no times, amounts or bare numbers ("10.42", "6.42pm", "1250"). */
    fun cleanKeywords(raw: String): List<String> =
        raw.split(',', ';')
            .map { it.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ").trim('#', ' ', '.', '-', '\'') }
            .filter { it.length in 2..32 && it.any { c -> c.isLetter() } && !TIME.matches(it) }
            .distinct()
            .take(12)

    private val TIME = Regex("\\d{1,2}[.:]\\d{2}\\s*(am|pm)?")
}
