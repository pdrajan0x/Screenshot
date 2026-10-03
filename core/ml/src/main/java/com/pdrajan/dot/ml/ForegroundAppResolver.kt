package com.pdrajan.dot.ml

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.pdrajan.dot.engine.SourceApp

/**
 * Which app was on screen when a screenshot was taken, from Android's usage events (the same
 * signal Pixel Screenshots gets from the system). Needs the user to switch on "Usage access" for
 * the app; Android keeps roughly a week of events, so older screenshots fall back to recognition.
 */
class ForegroundAppResolver(private val context: Context) {

    data class ForegroundApp(val packageName: String, val label: String)

    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val launchers: Set<String> by lazy {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
    }
    private val labels = HashMap<String, String>()

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

    /** The app in front at [timeMillis], or null when unknown or the home screen was showing. */
    fun appAt(timeMillis: Long): ForegroundApp? {
        if (timeMillis <= 0 || usage == null || !hasAccess()) return null
        val events = runCatching { usage.queryEvents(timeMillis - WINDOW_BEFORE, timeMillis + SLACK_AFTER) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var current: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp > timeMillis + SLACK_AFTER) break
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName ?: continue
            if (isOverlay(pkg)) continue
            current = pkg
        }
        val pkg = current ?: return null
        if (pkg in launchers) return null
        return ForegroundApp(pkg, label(pkg))
    }

    /** System screens that pop up around a screenshot without being what was captured. */
    private fun isOverlay(pkg: String): Boolean =
        pkg == context.packageName || pkg == "android" || pkg.contains("systemui") || pkg.contains("screenshot") ||
            pkg.contains("screencapture") || pkg == "com.google.android.as" || pkg.endsWith(".permissioncontroller")

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
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

        /** How far back usage events are reliably available. */
        const val HISTORY_MILLIS = 7L * 24 * 60 * 60_000
    }
}
