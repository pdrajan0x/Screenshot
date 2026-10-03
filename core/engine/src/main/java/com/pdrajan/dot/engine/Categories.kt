package com.pdrajan.dot.engine

import kotlin.math.exp

/**
 * An automatic screenshot category. Assigned from three signals:
 * visual similarity to [prompts] (CLIP zero-shot), [keywords] in the OCR text, and the
 * source [apps] (from the file name, or once the AI has named it). [synonyms] let a search for "movies" pull in the
 * whole category.
 */
data class Category(
    val id: String,
    val label: String,
    val prompts: List<String>,
    val keywords: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
)

object Categories {

    val ALL: List<Category> = listOf(
        Category(
            "shopping", "Shopping",
            prompts = listOf(
                "a screenshot of an online shopping app showing a product and its price",
                "a product listing on an e-commerce website",
                "clothes or shoes for sale online",
            ),
            keywords = listOf(
                "add to cart", "buy now", "add to bag", "wishlist", "free delivery", "mrp", "% off",
                "in stock", "size chart", "inclusive of all taxes", "cash on delivery", "ratings", "offers",
            ),
            apps = listOf("amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "snapdeal", "blinkit", "zepto", "bigbasket", "tata cliq", "croma"),
            synonyms = listOf("shopping", "shop"),
        ),
        Category(
            "movies", "Movies & TV",
            prompts = listOf(
                "a still frame from a movie",
                "a scene from a film or TV show",
                "a movie poster",
                "a streaming app page showing a movie or series",
            ),
            keywords = listOf(
                "movie", "film", "trailer", "imdb", "director", "netflix", "prime video", "hotstar", "jiocinema",
                "zee5", "sonyliv", "season", "episode", "web series", "box office", "in cinemas", "in theatres",
                "bookmyshow", "ott", "rotten tomatoes", "watchlist", "starring", "release date",
            ),
            apps = listOf("netflix", "prime video", "hotstar", "jiocinema", "zee5", "sonyliv", "mx player", "bookmyshow"),
            synonyms = listOf("movie", "movies", "film", "films", "cinema", "tv", "series", "show", "shows"),
        ),
        Category(
            "food", "Food",
            prompts = listOf("a photo of food on a plate", "a food delivery app showing dishes", "a recipe with ingredients"),
            keywords = listOf("swiggy", "zomato", "menu", "recipe", "ingredients", "restaurant", "dine", "delivery fee", "biryani", "pizza", "add item"),
            apps = listOf("swiggy", "zomato", "domino", "eatsure", "magicpin"),
            synonyms = listOf("food", "recipe", "recipes", "cooking"),
        ),
        Category(
            "payments", "Payments & bills",
            prompts = listOf(
                "a payment successful confirmation screen",
                "a UPI payment receipt",
                "a bank transaction details screen",
                "a bill or invoice",
            ),
            keywords = listOf(
                "paid", "payment successful", "transaction", "txn", "debited", "credited", "a/c", "invoice",
                "receipt", "gst", "upi ref", "utr", "ref no", "bank", "google pay", "phonepe", "paytm", "bhim",
                "amount", "balance",
            ),
            apps = listOf("gpay", "google pay", "phonepe", "paytm", "bhim", "cred", "mobikwik"),
            synonyms = listOf("payment", "payments", "paid", "bill", "bills", "receipt", "receipts", "invoice", "transaction", "transactions", "upi"),
        ),
        Category(
            "chats", "Chats",
            prompts = listOf("a screenshot of a chat conversation in a messaging app", "text message bubbles in a chat"),
            keywords = listOf("typing…", "typing...", "online", "last seen", "forwarded", "message", "reply", "you deleted this message"),
            apps = listOf("whatsapp", "telegram", "messages", "signal", "messenger"),
            synonyms = listOf("chat", "chats", "message", "messages", "conversation", "conversations"),
        ),
        Category(
            "travel", "Travel & tickets",
            prompts = listOf("a flight or train ticket", "a boarding pass", "a hotel booking confirmation", "a cab ride booking screen"),
            keywords = listOf(
                "pnr", "boarding", "flight", "train", "irctc", "departure", "arrival", "gate", "seat", "hotel",
                "check-in", "check in", "booking id", "makemytrip", "goibibo", "indigo", "air india", "redbus",
                "uber", "ola", "rapido", "coach", "berth", "platform",
            ),
            apps = listOf("irctc", "makemytrip", "goibibo", "indigo", "uber", "ola", "rapido", "redbus", "cleartrip", "ixigo", "airbnb"),
            synonyms = listOf("travel", "ticket", "tickets", "trip", "trips", "booking", "bookings"),
        ),
        Category(
            "events", "Events",
            prompts = listOf("an event invitation", "a calendar event", "a concert or show ticket"),
            keywords = listOf("invite", "invitation", "rsvp", "event", "venue", "save the date", "wedding", "birthday", "meeting", "webinar", "zoom", "google meet"),
            apps = listOf("calendar", "eventbrite", "meet", "zoom", "district"),
            synonyms = listOf("event", "events", "invite", "invites", "invitation", "invitations"),
        ),
        Category(
            "social", "Social posts",
            prompts = listOf("a social media post with likes and comments", "an instagram post", "a tweet"),
            keywords = listOf("likes", "followers", "following", "retweet", "repost", "comments", "reel", "views", "subscribe"),
            apps = listOf("instagram", "twitter", "facebook", "threads", "snapchat", "linkedin", "reddit", "youtube", "x"),
            synonyms = listOf("post", "posts", "social"),
        ),
        Category(
            "music", "Music",
            prompts = listOf("a music player app showing a song", "an album cover"),
            keywords = listOf("now playing", "lyrics", "album", "playlist", "artist", "song", "spotify", "jiosaavn", "gaana"),
            apps = listOf("spotify", "jiosaavn", "gaana", "wynk", "youtube music", "apple music"),
            synonyms = listOf("music", "song", "songs", "playlist", "playlists"),
        ),
        Category(
            "maps", "Places & maps",
            prompts = listOf("a map with directions", "a screenshot of a map app with a location pin"),
            keywords = listOf("directions", "route", "navigate", "location", "address", "pincode", "pin code", "near me"),
            apps = listOf("maps", "google maps"),
            synonyms = listOf("map", "maps", "place", "places", "location", "locations", "directions"),
        ),
        Category(
            "code", "Code & tech",
            prompts = listOf("computer code in a text editor", "a terminal window with commands", "an error message dialog"),
            keywords = listOf("error", "exception", "function", "import ", "class ", "const ", "npm ", "git ", "stack trace", "null", "undefined"),
            apps = listOf("github", "termux", "vs code"),
            synonyms = listOf("code", "programming", "terminal"),
        ),
        Category(
            "documents", "Documents & notes",
            prompts = listOf("a document page with paragraphs of text", "a scanned paper document", "handwritten notes"),
            keywords = listOf("pdf", "document", "aadhaar", "pan card", "certificate", "form", "page 1", "notes"),
            apps = listOf("drive", "docs", "adobe", "keep", "notes", "word"),
            synonyms = listOf("document", "documents", "notes", "note", "doc", "docs"),
        ),
        Category(
            "news", "News & articles",
            prompts = listOf("a news article with a headline", "a news website"),
            keywords = listOf("breaking", "reported", "according to", "min read", "minutes read", "news"),
            apps = listOf("inshorts", "dailyhunt", "google news", "news"),
            synonyms = listOf("news", "article", "articles", "headline"),
        ),
        Category(
            "memes", "Memes",
            prompts = listOf("a meme with text over a picture", "a funny meme"),
            keywords = listOf("meme", "😂", "🤣"),
            synonyms = listOf("meme", "memes", "funny", "joke", "jokes"),
        ),
        Category(
            "people", "People",
            prompts = listOf("a photo of a person", "a selfie", "a group photo of people"),
            synonyms = listOf("people", "person", "selfie", "selfies", "face", "faces"),
        ),
    )

    /** Prompts for "none of the above" so ordinary screenshots don't get forced into a category. */
    val BACKGROUND_PROMPTS = listOf(
        "a screenshot of a phone home screen",
        "a screenshot of a settings menu",
        "a screenshot of a mobile app",
        "a blank screen",
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String): Category? = byId[id]

    /** Categories that follow from the app alone (WhatsApp → chats, PhonePe → payments). */
    fun forApp(app: String): List<String> {
        val a = app.lowercase()
        return ALL.filter { c -> c.apps.any { appMatches(a, it) } }.map { it.id }
    }

    // Short names like "x" or "ola" must match a whole word, not "netflix" or "motorola".
    internal fun appMatches(app: String, name: String): Boolean =
        if (name.length >= 5) app.contains(name)
        else app.split(' ', '.', '_', '-').any { it == name }

    /**
     * The categories a search asks for as a whole: only when every word names the category
     * ("payments", "upi receipts", "my chats"). A thing inside a category ("shoes", "train",
     * "whatsapp") is found by its text, summary, app and look instead, never by pulling in the
     * whole category.
     */
    fun matchQuery(query: String): List<String> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in QUERY_FILLER }
        if (words.isEmpty() || words.size > 3) return emptyList()
        return ALL.filter { c -> words.all { it in c.synonyms } || c.label.lowercase() == query.trim().lowercase() }.map { it.id }
    }

    private val QUERY_FILLER = setOf("my", "the", "a", "an", "all", "of", "and", "in", "from", "screenshot", "screenshots")
}

/**
 * Combines visual and text evidence into at most three categories for a screenshot.
 * Prompt embeddings are computed once by the text encoder and passed in.
 */
class CategoryClassifier(
    private val promptEmbeddings: Map<String, List<FloatArray>>,
    private val backgroundEmbeddings: List<FloatArray>,
    private val logitScale: Float = 100f,
) {

    data class Result(val categories: List<String>, val visualProbabilities: Map<String, Float>)

    fun classify(cropEmbeddings: List<FloatArray>, ocrText: String, sourceApp: String?): Result {
        val visual = visualProbabilities(cropEmbeddings)
        val text = ocrText.lowercase()
        val app = sourceApp?.lowercase()

        val scored = Categories.ALL.mapNotNull { c ->
            val keywordHits = c.keywords.count { containsKeyword(text, it) }
            val appHit = app != null && c.apps.any { appMatches(app, it) }
            val p = visual[c.id] ?: 0f
            val score = p * 3f + keywordHits.coerceAtMost(4) * 0.6f + (if (appHit) 2f else 0f)
            val accepted = p >= 0.45f || appHit || keywordHits >= 2 || (keywordHits >= 1 && p >= 0.15f)
            if (accepted) c.id to score else null
        }
        return Result(scored.sortedByDescending { it.second }.take(3).map { it.first }, visual)
    }

    companion object {
        /** Substring match, except short alphanumeric keywords ("ott", "pnr") must be whole words. */
        fun containsKeyword(text: String, keyword: String): Boolean {
            val wholeWord = keyword.length <= 4 && keyword.all { it.isLetterOrDigit() }
            if (!wholeWord) return text.contains(keyword)
            var from = 0
            while (true) {
                val i = text.indexOf(keyword, from)
                if (i < 0) return false
                val before = if (i == 0) ' ' else text[i - 1]
                val afterIdx = i + keyword.length
                val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                from = i + 1
            }
        }
    }

    private fun appMatches(app: String, name: String) = Categories.appMatches(app, name)

    /** Softmax over categories + background, using each item's best crop/prompt pairing. */
    fun visualProbabilities(cropEmbeddings: List<FloatArray>): Map<String, Float> {
        if (cropEmbeddings.isEmpty() || promptEmbeddings.isEmpty()) return emptyMap()
        fun best(prompts: List<FloatArray>): Float {
            var b = Float.NEGATIVE_INFINITY
            for (crop in cropEmbeddings) for (p in prompts) b = maxOf(b, VectorMath.dot(crop, p))
            return b
        }
        val logits = LinkedHashMap<String, Float>()
        for ((id, prompts) in promptEmbeddings) logits[id] = best(prompts) * logitScale
        val bg = if (backgroundEmbeddings.isEmpty()) null else best(backgroundEmbeddings) * logitScale
        val maxLogit = (logits.values + listOfNotNull(bg)).max()
        var sum = 0.0
        val exps = logits.mapValues { (_, l) -> exp((l - maxLogit).toDouble()).also { sum += it } }
        if (bg != null) sum += exp((bg - maxLogit).toDouble())
        return exps.mapValues { (_, e) -> (e / sum).toFloat() }
    }
}
