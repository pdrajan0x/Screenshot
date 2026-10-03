package com.pdrajan.dot.ml

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.pdrajan.dot.engine.AppPrompts
import com.pdrajan.dot.engine.SourceApp

/**
 * Which app was on screen when a screenshot was taken, from Android's usage events (the same
 * signal Pixel Screenshots gets from the system). Needs the user to switch on "Usage access" for
 * the app; Android keeps roughly a week of events, so older screenshots fall back to recognition.
 */
class ForegroundAppResolver(private val context: Context) {

    data class ForegroundApp(val packageName: String, val label: String)

    private val usage = context.getSystemService(UsageStatsManager::class.java)

    /** Home screen apps. Settings' "FallbackHome" (shown while the phone boots) doesn't count. */
    val launchers: Set<String> by lazy {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
                .filterNot { it.activityInfo.name.endsWith("FallbackHome") || it.activityInfo.packageName == "com.android.settings" }
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
    }
    private val labels = HashMap<String, String>()
    private val monthlyUsage = HashMap<Long, Map<String, Double>?>()

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The app in front at [timeMillis] ("Home screen" for the launcher), or null when unknown. */
    fun appAt(timeMillis: Long): ForegroundApp? {
        if (timeMillis <= 0 || usage == null || !hasAccess()) return null
        return timeline(timeMillis - WINDOW_BEFORE, timeMillis + SLACK_AFTER)?.appAt(timeMillis)
    }

    /** Which app was in front over a period, read from the system in one pass. */
    inner class Timeline internal constructor(private val times: LongArray, private val packages: Array<String>) {
        val isEmpty: Boolean get() = times.isEmpty()

        fun appAt(timeMillis: Long): ForegroundApp? {
            // The last app to come to the front up to the moment of the screenshot.
            var lo = 0
            var hi = times.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= timeMillis + SLACK_AFTER) lo = mid + 1 else hi = mid
            }
            val i = lo - 1
            if (i < 0 || times[i] < timeMillis - WINDOW_BEFORE) return null
            return app(packages[i])
        }
    }

    fun timeline(fromMillis: Long, toMillis: Long): Timeline? {
        if (usage == null || !hasAccess()) return null
        val events = runCatching { usage.queryEvents(fromMillis, toMillis) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        val times = ArrayList<Long>()
        val packages = ArrayList<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName ?: continue
            if (isOverlay(pkg)) continue
            times += event.timeStamp
            packages += pkg
        }
        return Timeline(times.toLongArray(), packages.toTypedArray())
    }

    private fun app(pkg: String) = ForegroundApp(pkg, if (pkg in launchers) HOME_SCREEN else label(pkg))

    /**
     * Minutes each app was in front during the month around [timeMillis], from Android's usage
     * totals (kept far longer than individual events, in coarser buckets). Null when unknown.
     */
    fun usageMinutes(timeMillis: Long): Map<String, Double>? {
        if (usage == null || timeMillis <= 0) return null
        val month = timeMillis / MONTH
        // Months without data are remembered too (as null), so they're asked about once.
        synchronized(monthlyUsage) { if (monthlyUsage.containsKey(month)) return monthlyUsage[month] }
        if (!hasAccess()) return null
        val minutes = runCatching {
            usage.queryAndAggregateUsageStats(month * MONTH, (month + 1) * MONTH)
                .mapValues { it.value.totalTimeInForeground / 60_000.0 }
                .filterValues { it > 0.0 }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
        synchronized(monthlyUsage) { monthlyUsage[month] = minutes }
        return minutes
    }

    /** System screens that pop up around a screenshot without being what was captured. */
    private fun isOverlay(pkg: String): Boolean =
        pkg == context.packageName || pkg == "android" || pkg.contains("systemui") || pkg.contains("screenshot") ||
            pkg.contains("screencapture") || pkg == "com.google.android.as" || pkg.endsWith(".permissioncontroller")

    fun label(pkg: String): String = synchronized(labels) { labels.getOrPut(pkg) { loadLabel(pkg) } }

    private fun loadLabel(pkg: String): String = run {
        runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrElse { SourceApp.labelFromPackage(pkg) }
    }

    companion object {
        private const val WINDOW_BEFORE = 3 * 60 * 60_000L
        // MediaStore times can be rounded to the second and written just after the capture.
        private const val SLACK_AFTER = 1_500L
        private const val MONTH = 30L * 24 * 60 * 60_000

        /** What screenshots of the launcher are filed under. */
        const val HOME_SCREEN = AppPrompts.HOME_SCREEN

        /** How far back usage events are reliably available. */
        const val HISTORY_MILLIS = 7L * 24 * 60 * 60_000
    }
}
