package com.pdrajan.dot.engine

/**
 * Turns the AI's answer to "which app is this screen from?" into one of the phone's apps (or the
 * lock / home screen). An answer that names no installed app ("Llama.cpp", a shop or a brand from
 * the content) gives no app at all: better none than a wrong one.
 */
object AppNames {

    const val LOCK_SCREEN = "Lock screen"
    const val HOME_SCREEN = "Home screen"

    data class Choice(val label: String, val packageName: String?)

    /** Common ways the model names an app, mapped to how phones label it. */
    private val ALIASES = mapOf(
        "google chrome" to "chrome",
        "chrome browser" to "chrome",
        "google search" to "google",
        "google app" to "google",
        "gpay" to "google pay",
        "g pay" to "google pay",
        "twitter" to "x",
        "x twitter" to "x",
        "fb" to "facebook",
        "insta" to "instagram",
        "yt" to "youtube",
        "whatsapp messenger" to "whatsapp",
        "google play store" to "play store",
        "google play" to "play store",
        "gmail app" to "gmail",
        "google mail" to "gmail",
        "google maps" to "maps",
        "google photos" to "photos",
        "google drive" to "drive",
        "google calendar" to "calendar",
        "google messages" to "messages",
        "phone pe" to "phonepe",
    )

    fun match(answer: String?, installed: List<Choice>): Choice? {
        val raw = answer?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val n = ALIASES[norm(raw)] ?: norm(raw)
        if (n.isEmpty()) return null
        if ("lock screen" in n || n == "lockscreen" || n == "lock") return Choice(LOCK_SCREEN, null)
        if ("home screen" in n || n == "homescreen" || n == "launcher") return Choice(HOME_SCREEN, null)
        val byName = installed.map { it to norm(it.label) }
        byName.firstOrNull { it.second == n }?.let { return it.first }
        // "Google Photos" vs "Photos", "Maps" vs "Google Maps".
        val bare = n.removePrefix("google ")
        byName.firstOrNull { it.second.removePrefix("google ") == bare }?.let { return it.first }
        // "WhatsApp" vs "WhatsApp Business", "Amazon" vs "Amazon Shopping": the same name, a word longer.
        if (n.length >= 4) {
            byName.filter { (_, l) -> l.length >= 4 && (l.startsWith("$n ") || n.startsWith("$l ")) }
                .minByOrNull { it.second.length }?.let { return it.first }
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
