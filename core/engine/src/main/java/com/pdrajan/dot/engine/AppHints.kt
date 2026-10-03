package com.pdrajan.dot.engine

/**
 * Words an app's own interface puts on screen ("Pull requests" and "Fork" in GitHub, "Repost" and
 * "• 2nd" in LinkedIn), read from a screenshot's text and handed to the AI as a hint for naming the
 * app. Brand and shop names are left out on purpose: they show up in the content of other apps
 * (Amazon links in a Google search, a brand's post in Pinterest).
 */
object AppHints {

    private class Phrase(val text: String, val weight: Int)

    private fun p(vararg pairs: Pair<String, Int>) = pairs.map { Phrase(it.first, it.second) }

    /** A hint needs this much: two ordinary interface words, or one that only that app uses. */
    private const val ENOUGH = 2

    private val UI: Map<String, List<Phrase>> = linkedMapOf(
        "WhatsApp" to p(
            "end-to-end encrypted" to 2, "tap here for contact info" to 2, "this message was deleted" to 2, "you deleted this message" to 2,
            "missed voice call" to 1, "missed video call" to 1, "last seen" to 1, "forwarded" to 1, "typing…" to 1, "typing..." to 1,
        ),
        "Instagram" to p("liked by" to 1, "view all comments" to 2, "add a comment" to 1, "suggested for you" to 1, "original audio" to 1, "follow back" to 1),
        "YouTube" to p("subscribe" to 1, "shorts" to 1, "remix" to 1, "views ·" to 1, "subscribers" to 1),
        "Google Pay" to p("google transaction id" to 2, "upi transaction id" to 1),
        "GitHub" to p("pull requests" to 1, "issues" to 1, "fork" to 1, "forks" to 1, "contributors" to 1, "readme.md" to 1, "repositories" to 1, "commits" to 1),
        "LinkedIn" to p("repost" to 1, "reposts" to 1, "• 1st" to 2, "• 2nd" to 2, "• 3rd" to 2, "my network" to 2, "connections" to 1, "endorse" to 1),
        "X" to p("retweet" to 2, "for you" to 1, "following" to 1, "quote" to 1, "reposts" to 1),
        "Reddit" to p("upvote" to 2, "r/" to 1, "u/" to 1, "join" to 1, "award" to 1),
        "Gmail" to p("reply all" to 1, "to me" to 1, "inbox" to 1, "promotions" to 1, "compose" to 1),
        "Google" to p("people also ask" to 2, "about this result" to 2, "all images videos" to 2, "shopping images" to 1, "sponsored" to 1),
        "Spotify" to p("liked songs" to 2, "your library" to 1, "now playing" to 1),
        "Maps" to p("directions" to 1, "start navigation" to 2, "avoid tolls" to 2, "commute" to 1),
        "Telegram" to p("last seen recently" to 2, "members" to 1, "subscribers" to 1),
    )

    /** Weekday-and-date line ("Friday, 3 October") the lock screen shows under or over the clock. */
    private val LOCK_DATE = Regex("^(monday|tuesday|wednesday|thursday|friday|saturday|sunday),? \\d{1,2} [a-z]+$|^(mon|tue|wed|thu|fri|sat|sun),? [a-z]+ \\d{1,2}$")
    private val CLOCK = Regex("^\\d{1,2}[.:]\\d{2}$")

    /** Up to two apps whose interface words are on screen, best first; "Lock screen" for the lock screen. */
    fun fromText(text: String): List<String> {
        val lower = text.lowercase()
        val lines = lower.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size <= 14 && lines.any { LOCK_DATE.matches(it) } && lines.any { CLOCK.matches(it) }) return listOf(AppNames.LOCK_SCREEN)
        return UI.mapNotNull { (app, phrases) ->
            val score = phrases.sumOf { if (CategoryClassifier.containsKeyword(lower, it.text)) it.weight else 0 }
            if (score >= ENOUGH) app to score else null
        }.sortedByDescending { it.second }.take(2).map { it.first }
    }
}
