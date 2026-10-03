package com.pdrajan.dot.engine

/** Whether a screenshot's app is a web browser (its address bar then names the page). */
object Browsers {

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
