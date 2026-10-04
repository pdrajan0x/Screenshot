package com.pdrajan.dot.engine

/**
 * Names a screenshot's app from words that app's own interface puts on screen ("Pull requests"
 * and "Fork" in GitHub, "Repost" and "• 2nd" in LinkedIn, "Join the conversation" in Reddit), the
 * way a person recognises it without seeing a logo. Brand and shop names are left out on purpose:
 * they show up in the content of other apps (Amazon links in a Google search, a brand's post in
 * Pinterest). Only a clear winner names the app: better no app than a wrong one.
 */
object AppHints {

    /** [text] must appear (substring; short words as whole words). "a+b+c": every part must appear. */
    private class Phrase(val parts: List<String>, val weight: Int)

    private fun p(vararg pairs: Pair<String, Int>) = pairs.map { (t, w) -> Phrase(t.split('+').map { if (it.startsWith(' ')) it else it.trim() }, w) }

    /** A clear answer needs this much: two ordinary interface words, or one only that app uses. */
    private const val ENOUGH = 2

    private val UI: Map<String, List<Phrase>> = linkedMapOf(
        "WhatsApp" to p(
            "end-to-end encrypted" to 2, "tap here for contact info" to 2, "this message was deleted" to 2, "you deleted this message" to 2,
            "type a message" to 2, "chats+updates+communities+calls" to 3, "missed voice call" to 1, "missed video call" to 1,
            "last seen" to 1, "forwarded" to 1, "typing…" to 1, "typing..." to 1, "view once" to 1, "disappearing messages" to 2,
        ),
        "Instagram" to p(
            "liked by" to 1, "view all comments" to 2, "add a comment" to 1, "suggested for you" to 1, "original audio" to 1,
            "follow back" to 1, "edit profile+share profile" to 2, "posts+followers+following" to 2, "add to story" to 1,
            "your story" to 1, "send message" to 1,
        ),
        "YouTube" to p(
            "subscribe" to 1, "subscribers" to 1, "shorts" to 1, "remix" to 1, "home+shorts+subscriptions" to 3,
            "up next" to 1, "add a comment..." to 1,
        ),
        "GitHub" to p(
            "pull requests" to 1, "issues" to 1, "fork" to 1, "forks" to 1, "contributors" to 1, "readme.md" to 1,
            "repositories" to 1, "commits" to 1, "code+issues+pull requests" to 2,
        ),
        "LinkedIn" to p(
            "repost" to 1, "reposts" to 1, "• 1st" to 2, "• 2nd" to 2, "• 3rd" to 2, "my network" to 2, "connections" to 1,
            "endorse" to 1, "promoted" to 1, "home+post+notifications+jobs" to 3,
        ),
        "X" to p("retweet" to 2, "post your reply" to 2, "for you+following" to 1, "quote" to 1, "reposts" to 1),
        "Reddit" to p("join the conversation" to 2, "upvote" to 2, " r/" to 1, " u/" to 1, "join" to 1, "award" to 1, "sort by" to 1),
        "Facebook" to p("what's on your mind" to 2, "like+comment+share" to 2, "marketplace" to 1, "add friend" to 1),
        "Snapchat" to p("send a chat" to 2, "spotlight" to 1, "snap map" to 2),
        "Telegram" to p("last seen recently" to 2, "members" to 1, "subscribers" to 1, "broadcast" to 1),
        "Gmail" to p("reply all" to 1, "to me" to 1, "inbox" to 1, "promotions" to 1, "compose" to 1, "search in mail" to 2),
        "Google" to p("people also ask" to 2, "about this result" to 2, "ai overview" to 2, "all images videos" to 2, "sponsored" to 1, "search labs" to 1),
        "ChatGPT" to p("message chatgpt" to 2, "chatgpt can make mistakes" to 2),
        "Gemini" to p("ask gemini" to 2, "gemini can make mistakes" to 2),
        "Google Pay" to p("google transaction id" to 2, "upi transaction id" to 1, "paid to" to 1),
        "PhonePe" to p("phonepe transaction id" to 2, "transaction successful" to 1, "debited from" to 1),
        "Paytm" to p("paytm upi" to 2, "paid successfully" to 1),
        "Amazon" to p("frequently bought together" to 2, "deliver to" to 1, "add to cart+buy now" to 2, "fulfilled" to 1),
        "Flipkart" to p("flipkart assured" to 2, "special price" to 1, "bank offer" to 1),
        "Myntra" to p("add to bag+wishlist" to 2, "myntra insider" to 2),
        "Zomato" to p("zomato gold" to 2),
        "Swiggy" to p("swiggy one" to 2, "instamart" to 1),
        "Spotify" to p("liked songs" to 2, "your library" to 1, "now playing" to 1),
        "Maps" to p("directions" to 1, "start navigation" to 2, "avoid tolls" to 2, "commute" to 1, "your timeline" to 2),
        "Netflix" to p("new & hot" to 2, "my list" to 1, "continue watching" to 1),
        "Play Store" to p("about this app" to 2, "data safety" to 2, "ratings and reviews" to 1, "install" to 1),
        "IRCTC" to p("chart prepared" to 2, "pnr" to 1, "berth" to 1),
        "Truecaller" to p("identified by truecaller" to 2, "spam reports" to 1),
        "Discord" to p("message #" to 2, "direct messages" to 1),
    )

    /** Weekday-and-date line ("Friday, 3 October") the lock screen shows under or over the clock. */
    private val LOCK_DATE = Regex("^(monday|tuesday|wednesday|thursday|friday|saturday|sunday),? \\d{1,2} [a-z]+$|^(mon|tue|wed|thu|fri|sat|sun),? [a-z]+ \\d{1,2}$")
    private val CLOCK = Regex("^\\d{1,2}[.:]\\d{2}$")

    /** Up to two apps whose interface words are on screen, best first; "Lock screen" for the lock screen. */
    fun fromText(text: String): List<String> {
        if (isLockScreen(text)) return listOf(AppNames.LOCK_SCREEN)
        return scores(text).filter { it.second >= ENOUGH }.take(2).map { it.first }
    }

    /** The app, only when its interface words clearly win (ahead of the next by at least 2); otherwise null. */
    fun best(text: String): String? {
        if (isLockScreen(text)) return AppNames.LOCK_SCREEN
        val s = scores(text)
        val top = s.firstOrNull()?.takeIf { it.second >= ENOUGH } ?: return null
        val second = s.getOrNull(1)?.second ?: 0
        return if (top.second - second >= 2 || second < ENOUGH && top.second > second) top.first else null
    }

    private fun isLockScreen(text: String): Boolean {
        val lines = text.lowercase().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.size <= 14 && lines.any { LOCK_DATE.matches(it) } && lines.any { CLOCK.matches(it) }
    }

    private fun scores(text: String): List<Pair<String, Int>> {
        // One line, led by a space, so " r/" only matches at a word start.
        val lower = " " + text.lowercase().replace('\n', ' ')
        return UI.mapNotNull { (app, phrases) ->
            val score = phrases.sumOf { ph -> if (ph.parts.all { CategoryClassifier.containsKeyword(lower, it) }) ph.weight else 0 }
            if (score > 0) app to score else null
        }.sortedByDescending { it.second }
    }
}
