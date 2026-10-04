package com.pdrajan.dot.engine

/**
 * An automatic screenshot category, from [keywords] in the screen's text (and its description)
 * and the source [apps]. [synonyms] let a search for "movies" pull in the whole category.
 */
data class Category(
    val id: String,
    val label: String,
    val keywords: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
)

object Categories {

    val ALL: List<Category> = listOf(
        Category(
            "shopping", "Shopping",
            keywords = listOf(
                "add to cart", "buy now", "add to bag", "wishlist", "free delivery", "mrp", "% off",
                "in stock", "size chart", "inclusive of all taxes", "cash on delivery", "ratings", "offers",
            ),
            apps = listOf("amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "snapdeal", "blinkit", "zepto", "bigbasket", "tata cliq", "croma"),
            synonyms = listOf("shopping", "shop"),
        ),
        Category(
            "movies", "Movies & TV",
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
            keywords = listOf("swiggy", "zomato", "menu", "recipe", "ingredients", "restaurant", "dine", "delivery fee", "biryani", "pizza", "add item"),
            apps = listOf("swiggy", "zomato", "domino", "eatsure", "magicpin"),
            synonyms = listOf("food", "recipe", "recipes", "cooking"),
        ),
        Category(
            "payments", "Payments & bills",
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
            keywords = listOf("typing…", "typing...", "online", "last seen", "forwarded", "message", "reply", "you deleted this message"),
            apps = listOf("whatsapp", "telegram", "messages", "signal", "messenger"),
            synonyms = listOf("chat", "chats", "message", "messages", "conversation", "conversations"),
        ),
        Category(
            "travel", "Travel & tickets",
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
            keywords = listOf("invite", "invitation", "rsvp", "event", "venue", "save the date", "wedding", "birthday", "meeting", "webinar", "zoom", "google meet"),
            apps = listOf("calendar", "eventbrite", "meet", "zoom", "district"),
            synonyms = listOf("event", "events", "invite", "invites", "invitation", "invitations"),
        ),
        Category(
            "social", "Social posts",
            keywords = listOf("likes", "followers", "following", "retweet", "repost", "comments", "reel", "views", "subscribe"),
            apps = listOf("instagram", "twitter", "facebook", "threads", "snapchat", "linkedin", "reddit", "youtube", "x"),
            synonyms = listOf("post", "posts", "social"),
        ),
        Category(
            "music", "Music",
            keywords = listOf("now playing", "lyrics", "album", "playlist", "artist", "song", "spotify", "jiosaavn", "gaana"),
            apps = listOf("spotify", "jiosaavn", "gaana", "wynk", "youtube music", "apple music"),
            synonyms = listOf("music", "song", "songs", "playlist", "playlists"),
        ),
        Category(
            "maps", "Places & maps",
            keywords = listOf("directions", "route", "navigate", "location", "address", "pincode", "pin code", "near me"),
            apps = listOf("maps", "google maps"),
            synonyms = listOf("map", "maps", "place", "places", "location", "locations", "directions"),
        ),
        Category(
            "code", "Code & tech",
            keywords = listOf("error", "exception", "function", "import ", "class ", "const ", "npm ", "git ", "stack trace", "null", "undefined"),
            apps = listOf("github", "termux", "vs code"),
            synonyms = listOf("code", "programming", "terminal"),
        ),
        Category(
            "documents", "Documents & notes",
            keywords = listOf("pdf", "document", "aadhaar", "pan card", "certificate", "form", "page 1", "notes"),
            apps = listOf("drive", "docs", "adobe", "keep", "notes", "word"),
            synonyms = listOf("document", "documents", "notes", "note", "doc", "docs"),
        ),
        Category(
            "news", "News & articles",
            keywords = listOf("breaking", "reported", "according to", "min read", "minutes read", "news"),
            apps = listOf("inshorts", "dailyhunt", "google news", "news"),
            synonyms = listOf("news", "article", "articles", "headline"),
        ),
        Category(
            "memes", "Memes",
            keywords = listOf("meme", "😂", "🤣"),
            synonyms = listOf("meme", "memes", "funny", "joke", "jokes"),
        ),
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
     * "whatsapp") is found by its text, description and app instead, never by pulling in the
     * whole category.
     */
    fun matchQuery(query: String): List<String> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in QUERY_FILLER }
        if (words.isEmpty() || words.size > 3) return emptyList()
        return ALL.filter { c -> words.all { it in c.synonyms } || c.label.lowercase() == query.trim().lowercase() }.map { it.id }
    }

    private val QUERY_FILLER = setOf("my", "the", "a", "an", "all", "of", "and", "in", "from", "screenshot", "screenshots")
}

/** Categories of a screenshot from its words (screen text, description) and app. */
object CategoryClassifier {

    fun classify(text: String, sourceApp: String?): List<String> {
        val words = text.lowercase()
        val app = sourceApp?.lowercase()
        return Categories.ALL.mapNotNull { c ->
            val keywordHits = c.keywords.count { containsKeyword(words, it) }
            val appHit = app != null && c.apps.any { Categories.appMatches(app, it) }
            val score = keywordHits.coerceAtMost(4) * 0.6f + (if (appHit) 2f else 0f)
            if (appHit || keywordHits >= 2) c.id to score else null
        }.sortedByDescending { it.second }.take(3).map { it.first }
    }

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

