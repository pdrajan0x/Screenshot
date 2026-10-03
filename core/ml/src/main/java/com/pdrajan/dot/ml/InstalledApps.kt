package com.pdrajan.dot.ml

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.pdrajan.dot.engine.SourceApp

/** The apps on this phone, with their names: what a screenshot can have come from. */
class InstalledApps(private val context: Context) {

    data class App(val label: String, val packageName: String)

    private val labels = HashMap<String, String>()

    /** Home screen apps. Settings' "FallbackHome" (shown while the phone boots) doesn't count. */
    val launchers: Set<String> by lazy {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
                .filterNot { it.activityInfo.name.endsWith("FallbackHome") || it.activityInfo.packageName == "com.android.settings" }
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Apps with an icon in the app drawer (not this app, not the launcher), by name. */
    fun launchable(): List<App> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in launchers }
            .map { App(label(it), it) }
            .sortedBy { it.label.lowercase() }
    }

    fun label(pkg: String): String = synchronized(labels) { labels.getOrPut(pkg) { loadLabel(pkg) } }

    private fun loadLabel(pkg: String): String = runCatching {
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
