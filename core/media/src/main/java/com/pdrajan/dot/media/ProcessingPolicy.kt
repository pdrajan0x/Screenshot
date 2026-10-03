package com.pdrajan.dot.media

import android.content.SharedPreferences

/**
 * When the apps may do their heavy work (reading new items, the AI model): set by the user in
 * Settings → Processing.
 */
data class ProcessingPolicy(
    /** Keep working while the app is closed. */
    val background: Boolean = true,
    /** Work through older items on battery too; otherwise only while charging (new items never wait). */
    val onBattery: Boolean = false,
    /** On battery, only above this charge (%). */
    val minBattery: Int = 30,
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_BACKGROUND, background).putBoolean(KEY_ON_BATTERY, onBattery).putInt(KEY_MIN_BATTERY, minBattery).apply()
    }

    companion object {
        // v2: new items are always done (above minBattery on battery); earlier saved choices don't carry over.
        private const val KEY_BACKGROUND = "processing2_background"
        private const val KEY_ON_BATTERY = "processing2_on_battery"
        private const val KEY_MIN_BATTERY = "processing2_min_battery"

        fun load(prefs: SharedPreferences): ProcessingPolicy {
            val d = ProcessingPolicy()
            return ProcessingPolicy(
                background = prefs.getBoolean(KEY_BACKGROUND, d.background),
                onBattery = prefs.getBoolean(KEY_ON_BATTERY, d.onBattery),
                minBattery = prefs.getInt(KEY_MIN_BATTERY, d.minBattery).coerceIn(10, 95),
            )
        }
    }
}
