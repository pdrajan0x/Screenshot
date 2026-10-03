package com.pdrajan.dot.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** An app a screenshot could have come from: installed now, seen before, or a well-known one. */
data class AppCandidate(
    val label: String,
    /** Null for apps that aren't installed (known names, or apps seen in the past). */
    val packageName: String?,
    val prompts: List<String>,
)

/** A screenshot whose app is certain (usage history, file name, or set by the user). */
class KnownShot(val label: String, val packageName: String?, val takenAt: Long, crops: List<FloatArray>) {
    /** Null for screenshots not embedded yet; they still count for timing. */
    val embedding: FloatArray? = if (crops.isEmpty()) null else VectorMath.mean(crops)
}

/**
 * Names the most likely source app for any screenshot, so every one can be found by app, by
 * weighing everything known about it:
 *  - what the screen looks like, against "a screenshot of the {app} app" for every candidate (CLIP);
 *  - tell-tale text and the app's own name on screen;
 *  - the user's own screenshots whose app is certain: near-identical looking ones, and ones taken
 *    within a few minutes (people take screenshots in bursts);
 *  - how much each app was used around that time (Android's usage totals), a gentle prior.
 * The scores are combined in log space; the softmax of the winner is its confidence.
 */
class AppIdentifier(
    candidates: List<AppCandidate>,
    promptVectors: Map<String, FloatArray>,
    logitScale: Float,
    known: List<KnownShot>,
) {
    data class Result(val label: String, val packageName: String?, val confidence: Float)

    private class Entry(val candidate: AppCandidate, val prompts: List<FloatArray>, val canonical: String?, val nameMatcher: String?)

    private val entries: List<Entry>
    private val clipScale = logitScale * CLIP_WEIGHT
    private val known = known.sortedBy { it.takenAt }
    private val knownTimes = this.known.map { it.takenAt }.toLongArray()
    private val knownLooks = known.filter { it.embedding != null }

    init {
        val byKey = LinkedHashMap<String, AppCandidate>()
        for (c in candidates) byKey.putIfAbsent(key(c.label), c)
        // Apps the user has screenshots from but no longer has installed stay possible.
        for (k in known) byKey.putIfAbsent(key(k.label), AppCandidate(k.label, k.packageName, listOf(AppPrompts.generic(k.label))))
        entries = byKey.values.map { c ->
            Entry(
                candidate = c,
                prompts = c.prompts.mapNotNull { promptVectors[it] },
                canonical = AppRecognizer.canonical(c.label),
                nameMatcher = c.label.lowercase().takeIf { it.length >= 3 && it !in GENERIC_NAMES },
            )
        }
    }

    val size: Int get() = entries.size

    /**
     * @param usageMinutes minutes each package was in the foreground around [takenAt] (from
     *   Android's usage totals), or null when unknown.
     */
    fun identify(text: String, crops: List<FloatArray>, takenAt: Long, usageMinutes: Map<String, Double>?): Result? {
        if (entries.isEmpty()) return null
        val lower = text.lowercase()
        val clues = AppRecognizer.textScores(lower)
        // The whole screenshot as one (normalised) embedding: the mean of its crops.
        val mean = if (crops.isEmpty()) null else VectorMath.mean(crops)
        val neighbours = mean?.let { knnVotes(it) }.orEmpty()
        val nearby = timeVotes(takenAt)

        val scores = DoubleArray(entries.size)
        for ((i, e) in entries.withIndex()) {
            var s = 0.0
            if (mean != null && e.prompts.isNotEmpty()) s += clipScale * e.prompts.maxOf { VectorMath.dot(mean, it) }
            e.canonical?.let { s += TEXT_WEIGHT * (clues[it] ?: 0f) }
            if (e.nameMatcher != null && CategoryClassifier.containsKeyword(lower, e.nameMatcher)) s += NAME_ON_SCREEN
            val k = key(e.candidate.label)
            s += neighbours[k] ?: 0.0
            s += nearby[k] ?: 0.0
            s += prior(e.candidate.packageName, usageMinutes)
            scores[i] = s
        }
        val max = scores.max()
        var sum = 0.0
        var best = 0
        for (i in scores.indices) {
            sum += exp(scores[i] - max)
            if (scores[i] > scores[best]) best = i
        }
        val c = entries[best].candidate
        return Result(c.label, c.packageName, (1.0 / sum).toFloat())
    }

    /** Looks like one of the user's screenshots whose app is certain (same app, same screen layout). */
    private fun knnVotes(query: FloatArray): Map<String, Double> {
        if (knownLooks.isEmpty()) return emptyMap()
        val top = knownLooks.map { it to VectorMath.dot(it.embedding!!, query) }
            .filter { it.second >= KNN_MIN_SIM }
            .sortedByDescending { it.second }
            .take(KNN_K)
        val votes = HashMap<String, Double>()
        for ((shot, sim) in top) votes.merge(key(shot.label), (sim - KNN_MIN_SIM) * KNN_WEIGHT, Double::plus)
        votes.replaceAll { _, v -> min(v, KNN_CAP) }
        return votes
    }

    /** Taken within a few minutes of a screenshot whose app is certain. */
    private fun timeVotes(takenAt: Long): Map<String, Double> {
        if (knownTimes.isEmpty() || takenAt <= 0) return emptyMap()
        val votes = HashMap<String, Double>()
        var i = knownTimes.binarySearch(takenAt - NEARBY_MILLIS).let { if (it < 0) -it - 1 else it }
        while (i < knownTimes.size && knownTimes[i] <= takenAt + NEARBY_MILLIS) {
            val dt = abs(knownTimes[i] - takenAt)
            // The same screenshot (already certain) is never asked about, so dt > 0 in practice.
            val v = NEARBY_WEIGHT * (1.0 - dt.toDouble() / NEARBY_MILLIS)
            votes.merge(key(known[i].label), v, ::maxOf)
            i++
        }
        return votes
    }

    private fun prior(packageName: String?, usageMinutes: Map<String, Double>?): Double {
        if (usageMinutes == null) return if (packageName == null) -0.5 else 0.0
        if (packageName == null) return -1.0
        val minutes = usageMinutes[packageName] ?: 0.0
        return if (minutes < 1.0) -2.0 else min(1.5, 0.35 * ln(1.0 + minutes / 10.0))
    }

    companion object {
        private const val CLIP_WEIGHT = 0.6f
        private const val TEXT_WEIGHT = 3.0
        private const val NAME_ON_SCREEN = 1.5
        private const val KNN_K = 5
        private const val KNN_MIN_SIM = 0.80f
        private const val KNN_WEIGHT = 25.0
        private const val KNN_CAP = 6.0
        private const val NEARBY_MILLIS = 3 * 60_000L
        private const val NEARBY_WEIGHT = 2.5

        fun key(label: String) = label.trim().lowercase()

        /** App names that are everyday words: seeing them on screen says little. */
        private val GENERIC_NAMES = setOf(
            "phone", "camera", "clock", "files", "file manager", "settings", "messages", "photos", "gallery", "calendar",
            "notes", "contacts", "calculator", "music", "weather", "recorder", "maps", "store", "mail", "email", "browser",
            "internet", "health", "wallet", "home", "tv", "video", "videos", "radio", "compass", "translate", "assistant",
            "news", "books", "games", "drive", "meet", "keep", "chat", "tasks", "sheets", "docs", "slides", "lens", "play",
            "fit", "pay", "one", "x", "google", "home screen", "community", "themes", "downloads", "magnifier", "feedback",
            "support", "updater", "security", "cleaner", "launcher", "voice", "search", "shop", "app", "apps", "go",
        )
    }
}

/** The CLIP prompts that describe an app's screens. */
object AppPrompts {
    fun generic(label: String) = "a screenshot of the $label app"

    const val HOME_SCREEN = "Home screen"

    private val PAYMENT = listOf("pay", "phonepe", "paytm", "bhim", "upi", "cred", "mobikwik")
    private val SHOPPING = listOf("amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "snapdeal", "tata cliq")
    private val FOOD = listOf("swiggy", "zomato", "blinkit", "zepto", "instamart", "bigbasket")

    fun forApp(label: String, packageName: String?, isLauncher: Boolean = false): List<String> {
        if (isLauncher) return listOf("a screenshot of a phone home screen with app icons and wallpaper")
        val l = label.lowercase()
        val prompts = ArrayList<String>(3)
        prompts += generic(label)
        AppRecognizer.canonical(label)?.let { AppRecognizer.VISUAL[it]?.let(prompts::addAll) }
        when {
            AppRecognizer.isBrowser(label, packageName) -> prompts += "a screenshot of a web page in a mobile browser"
            PAYMENT.any { l.contains(it) } -> prompts += "a screenshot of a UPI payment confirmation"
            SHOPPING.any { l.contains(it) } -> prompts += "a screenshot of a shopping app product page"
            FOOD.any { l.contains(it) } -> prompts += "a screenshot of a food delivery app"
            l == "gallery" || l == "photos" || l == "google photos" -> prompts += "a screenshot of a photo gallery app grid"
            l == "settings" || packageName == "com.android.settings" -> prompts += "a screenshot of phone settings"
            l == "messages" || l == "messaging" || packageName == "com.google.android.apps.messaging" ->
                prompts += "a screenshot of an SMS text message conversation"
            l == "phone" || packageName == "com.google.android.dialer" -> prompts += "a screenshot of a phone call log"
            l == "camera" -> prompts += "a screenshot of a camera viewfinder"
            l.contains("file") -> prompts += "a screenshot of a file manager listing folders"
        }
        return prompts.distinct()
    }
}
