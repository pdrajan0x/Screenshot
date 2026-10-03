package com.pdrajan.dot.media

import android.content.SharedPreferences

/**
 * When the apps may do their heavy work (reading new items, the AI model): set by the user in
 * Settings → Processing.
 */
data class ProcessingPolicy(
    /** Keep working while the app is closed. */
    val background: Boolean = true,
    /** Also work on battery; otherwise only while charging. */
    val onBattery: Boolean = false,
    /** On battery, only above this charge (%). */
    val minBattery: Int = 50,
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_BACKGROUND, background).putBoolean(KEY_ON_BATTERY, onBattery).putInt(KEY_MIN_BATTERY, minBattery).apply()
    }

    companion object {
        private const val KEY_BACKGROUND = "processing_background"
        private const val KEY_ON_BATTERY = "processing_on_battery"
        private const val KEY_MIN_BATTERY = "processing_min_battery"

        /** [legacyChargingOnlyKey]: the older "only while charging" switch, carried over the first time. */
        fun load(prefs: SharedPreferences, legacyChargingOnlyKey: String): ProcessingPolicy = ProcessingPolicy(
            background = prefs.getBoolean(KEY_BACKGROUND, true),
            onBattery = prefs.getBoolean(KEY_ON_BATTERY, !prefs.getBoolean(legacyChargingOnlyKey, true)),
            minBattery = prefs.getInt(KEY_MIN_BATTERY, 50).coerceIn(10, 95),
        )
    }
}
