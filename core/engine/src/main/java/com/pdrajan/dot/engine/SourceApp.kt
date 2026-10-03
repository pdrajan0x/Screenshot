package com.pdrajan.dot.engine

/**
 * Pulls the source app out of a screenshot's file name, where the phone maker includes it:
 *  - Samsung:         `Screenshot_20240229_103000_YouTube.jpg`          → "YouTube"
 *  - Xiaomi/HyperOS:  `Screenshot_2024-02-29-10-30-00-123_com.google.android.youtube.jpg`
 *                                                                       → package name
 *  - Motorola & some: `Screenshot_20240229-103000_Chrome.png`           → "Chrome"
 *  - Pixel/AOSP:      `Screenshot_20240229-103000.png`                  → null
 */
object SourceApp {

    data class Hint(val label: String?, val packageName: String?)

    private val PREFIX = Regex("^(?i)(screenshot|screen_shot|scr|capture)[_-]?")
    private val DATE_CHUNK = Regex("^[0-9][0-9_.\\-]{5,}[0-9](?:[_\\-]|$)")
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+){1,}$")

    fun fromFileName(fileName: String): Hint? {
        val base = fileName.substringBeforeLast('.')
        if (!PREFIX.containsMatchIn(base)) return null
        var rest = base.replaceFirst(PREFIX, "")
        val date = DATE_CHUNK.find(rest) ?: return null
        rest = rest.substring(date.range.last + 1).trim('_', '-', ' ')
        if (rest.isEmpty() || rest.all { it.isDigit() || it == '_' || it == '-' }) return null
        // Some phones append a hash or counter after the app; drop pure-hex tails.
        rest = rest.replace(Regex("[_-][0-9a-f]{6,}$"), "")
        return if (PACKAGE.matches(rest)) Hint(label = null, packageName = rest)
        else Hint(label = rest.replace('_', ' '), packageName = null)
    }

    /** Best-effort readable name from a package when the app isn't installed. */
    fun labelFromPackage(pkg: String): String {
        KNOWN[pkg]?.let { return it }
        val last = pkg.split('.').lastOrNull { it !in GENERIC } ?: pkg
        return last.replaceFirstChar { it.uppercase() }
    }

    private val GENERIC = setOf("android", "app", "apps", "mobile", "client", "main", "lite", "com", "in", "org")

    private val KNOWN = mapOf(
        "com.google.android.youtube" to "YouTube",
        "com.instagram.android" to "Instagram",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.android.chrome" to "Chrome",
        "com.google.android.apps.maps" to "Maps",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.swiggy.android" to "Swiggy",
        "com.application.zomato" to "Zomato",
        "in.amazon.mShop.android.shopping" to "Amazon",
        "com.flipkart.android" to "Flipkart",
        "com.myntra.android" to "Myntra",
        "com.netflix.mediaclient" to "Netflix",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "in.startv.hotstar" to "Hotstar",
        "com.jio.media.ondemand" to "JioCinema",
        "com.spotify.music" to "Spotify",
        "com.twitter.android" to "X",
        "com.facebook.katana" to "Facebook",
        "com.snapchat.android" to "Snapchat",
        "com.linkedin.android" to "LinkedIn",
        "com.reddit.frontpage" to "Reddit",
        "cris.org.in.prs.ima" to "IRCTC",
        "com.makemytrip" to "MakeMyTrip",
        "com.ubercab" to "Uber",
        "com.olacabs.customer" to "Ola",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.messaging" to "Messages",
        "com.google.android.apps.photos" to "Photos",
        "com.miui.home" to "Home screen",
        "com.android.settings" to "Settings",
    )
}
