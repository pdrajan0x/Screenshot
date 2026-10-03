package com.pdrajan.dot.engine

/**
 * Guesses which app a screenshot came from when the system didn't tell us (no Usage access, or
 * the screenshot is older than Android's usage history): what the screen looks like (CLIP) plus
 * tell-tale text ("last seen", "Google transaction ID", …).
 */
object AppRecognizer {

    /** CLIP prompts per app. Generic classes soak up look-alikes and are never reported as apps. */
    val VISUAL: Map<String, List<String>> = linkedMapOf(
        "WhatsApp" to listOf("a screenshot of a WhatsApp chat", "a WhatsApp conversation with message bubbles on a doodle wallpaper"),
        "Telegram" to listOf("a screenshot of a Telegram chat"),
        "Instagram" to listOf("a screenshot of an Instagram post", "a screenshot of an Instagram reel"),
        "YouTube" to listOf("a screenshot of a YouTube video", "a screenshot of the YouTube app"),
        "Gmail" to listOf("a screenshot of an email in Gmail"),
        "Google Maps" to listOf("a screenshot of Google Maps"),
        "LinkedIn" to listOf("a screenshot of a LinkedIn post"),
        "X" to listOf("a screenshot of a tweet"),
        "Spotify" to listOf("a screenshot of the Spotify music player"),
        // Generic look-alikes: absorb probability, never returned.
        "~browser" to listOf("a screenshot of a web page in a mobile browser"),
        "~payment" to listOf("a screenshot of a UPI payment confirmation"),
        "~shopping" to listOf("a screenshot of a shopping app product page"),
        "~gallery" to listOf("a screenshot of a photo gallery app grid"),
        "~settings" to listOf("a screenshot of phone settings"),
        "~other" to listOf("a screenshot of a phone app", "a screenshot", "a photo"),
    )

    private class Clue(val phrase: String, val weight: Float)
    private fun clues(vararg pairs: Pair<String, Float>) = pairs.map { Clue(it.first, it.second) }

    /** Text clues per app; a weight of 3 alone is enough to name the app. */
    private val TEXT: Map<String, List<Clue>> = linkedMapOf(
        "WhatsApp" to clues(
            "whatsapp" to 3f, "end-to-end encrypted" to 3f, "tap here for contact info" to 3f, "this message was deleted" to 3f,
            "you deleted this message" to 3f, "missed voice call" to 2f, "missed video call" to 2f, "last seen" to 1.5f,
            "typing…" to 1f, "typing..." to 1f, "forwarded" to 1f, "online" to 0.5f,
        ),
        "Telegram" to clues("telegram" to 3f, "subscribers" to 1f, "members" to 0.5f),
        "Instagram" to clues(
            "instagram" to 3f, "liked by" to 2f, "view all comments" to 2f, "original audio" to 1.5f, "add a comment" to 1f,
            "suggested for you" to 1f, "send message" to 1f, "reels" to 1f,
        ),
        "YouTube" to clues("youtube" to 3f, "subscribe" to 1.5f, "shorts" to 1f, "views" to 0.5f, "remix" to 1f),
        "Google Pay" to clues("google pay" to 3f, "google transaction id" to 3f, "gpay" to 3f),
        "PhonePe" to clues("phonepe" to 3f, "transaction successful" to 1f),
        "Paytm" to clues("paytm" to 3f),
        "Swiggy" to clues("swiggy" to 3f, "instamart" to 3f),
        "Zomato" to clues("zomato" to 3f),
        "Blinkit" to clues("blinkit" to 3f),
        "Zepto" to clues("zepto" to 3f),
        "Amazon" to clues("amazon.in" to 3f, "deliver to" to 1.5f, "add to cart" to 1f, "buy now" to 1f, "amazon" to 1.5f),
        "Flipkart" to clues("flipkart" to 3f, "special price" to 1.5f),
        "Myntra" to clues("myntra" to 3f),
        "Gmail" to clues("gmail" to 2f, "to me" to 2f, "reply all" to 1.5f, "inbox" to 1f),
        "Google Maps" to clues("google maps" to 3f, "directions" to 1f, "start navigation" to 2f),
        "LinkedIn" to clues("linkedin" to 3f, "connections" to 1f, "• 1st" to 2f, "• 2nd" to 2f, "• 3rd" to 2f, "repost" to 1f),
        "X" to clues("retweet" to 2f, "reposts" to 1f, "for you following" to 2f),
        "Spotify" to clues("spotify" to 3f),
        "Netflix" to clues("netflix" to 3f),
        "Uber" to clues("uber" to 3f),
        "Ola" to clues("ola cabs" to 3f),
        "Rapido" to clues("rapido" to 3f),
        "IRCTC" to clues("irctc" to 3f, "pnr" to 1.5f),
        "Truecaller" to clues("truecaller" to 3f),
        "Snapchat" to clues("snapchat" to 3f),
        "Facebook" to clues("facebook" to 3f),
        "ChatGPT" to clues("chatgpt" to 3f),
    )

    /** Every app with text clues. */
    val TEXT_APPS: Set<String> get() = TEXT.keys

    data class Guess(val app: String, val score: Float)

    /**
     * @param visual CLIP probabilities over [VISUAL]'s keys (softmax), or null if unavailable.
     * @param modelGuess the summary model's "App:" answer, a weak extra hint.
     */
    fun recognize(text: String, visual: Map<String, Float>?, modelGuess: String? = null): Guess? {
        val scores = HashMap(textScores(text.lowercase()))
        visual?.forEach { (label, p) -> if (!label.startsWith("~")) scores.merge(label, p * 1.5f, Float::plus) }
        modelGuess?.let { g -> canonical(g)?.let { scores.merge(it, 0.4f, Float::plus) } }
        val best = scores.maxByOrNull { it.value } ?: return null
        val second = scores.filterKeys { it != best.key }.values.maxOrNull() ?: 0f
        return if (best.value >= 1f && best.value - second >= 0.25f) Guess(best.key, best.value) else null
    }

    /** Text evidence per known app; 1 means a decisive clue. [lower] must be lowercase. */
    fun textScores(lower: String): Map<String, Float> {
        val scores = HashMap<String, Float>()
        TEXT.forEach { (app, list) ->
            val s = list.sumOf { c -> if (CategoryClassifier.containsKeyword(lower, c.phrase)) c.weight.toDouble() else 0.0 }.toFloat()
            if (s > 0f) scores[app] = s / 3f
        }
        return scores
    }

    /** Maps a free-form app name ("whatsapp messenger", "Google Pay (GPay)") onto a known one. */
    fun canonical(name: String): String? {
        val n = name.lowercase().trim()
        val keys = TEXT.keys + VISUAL.keys.filterNot { it.startsWith("~") }
        keys.firstOrNull { it.lowercase() == n }?.let { return it }
        // Whole words only: "Netflix" is not "X", "Motorola" is not "Ola".
        return keys.filter { CategoryClassifier.containsKeyword(n, it.lowercase()) }.maxByOrNull { it.length }
    }

    private val BROWSERS = setOf(
        "chrome", "firefox", "brave", "edge", "opera", "samsung internet", "internet", "duckduckgo", "vivaldi",
        "kiwi", "via", "uc browser", "browser", "mi browser", "jiosphere",
    )
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx", "com.opera.browser",
        "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
    )

    fun isBrowser(appLabel: String?, packageName: String? = null): Boolean =
        (packageName != null && packageName in BROWSER_PACKAGES) || (appLabel != null && appLabel.lowercase() in BROWSERS)
}

/** CLIP "which app does this look like" over [AppRecognizer.VISUAL]: max over prompts of the mean over crops. */
class AppLookClassifier(private val prompts: Map<String, List<FloatArray>>, private val logitScale: Float) {

    fun probabilities(cropEmbeddings: List<FloatArray>): Map<String, Float> {
        if (cropEmbeddings.isEmpty() || prompts.isEmpty()) return emptyMap()
        val labels = prompts.keys.toList()
        val logits = labels.map { label ->
            prompts.getValue(label).maxOf { p -> cropEmbeddings.map { VectorMath.dot(it, p) }.average().toFloat() } * logitScale
        }
        val max = logits.max()
        val exp = logits.map { kotlin.math.exp((it - max).toDouble()) }
        val sum = exp.sum()
        return labels.indices.associate { labels[it] to (exp[it] / sum).toFloat() }
    }
}
