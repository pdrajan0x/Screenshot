package com.pdrajan.dot.llm

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.pdrajan.dot.media.ProcessingPolicy

/**
 * When the on-device model (and background work in general) may run, from the user's
 * [ProcessingPolicy] plus what the phone says: each description or summary is seconds of full CPU,
 * so on battery it needs the user's go-ahead and enough charge, never runs with battery saver on or
 * on a warm phone, and nothing at all runs on a hot one.
 */
class PowerGate(context: Context, private val policy: () -> ProcessingPolicy) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    val isCharging: Boolean get() = runCatching { battery.isCharging }.getOrDefault(false)

    val level: Int get() = runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(100)

    private val powerSave: Boolean get() = runCatching { power.isPowerSaveMode }.getOrDefault(false)

    private val thermal: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { power.currentThermalStatus }.getOrDefault(0) else 0

    /**
     * Why work can't run right now, or null when it can. [foreground]: the app is open (work started
     * from the screen). [userAsked] ("Do it now") skips the battery rules, but nothing runs on a hot
     * phone. On battery new items still go ahead (a few a day); older ones wait, see [backlogAllowed].
     */
    fun blocker(userAsked: Boolean = false, foreground: Boolean = true): String? {
        val p = policy()
        val t = thermal
        if (t >= PowerManager.THERMAL_STATUS_SEVERE) return "phone is hot"
        if (!foreground && !p.background) return "background processing is off"
        if (isCharging || userAsked) return null
        return when {
            powerSave -> "battery saver is on"
            level < p.minBattery -> "battery below ${p.minBattery}%"
            t >= PowerManager.THERMAL_STATUS_MODERATE -> "phone is warm"
            else -> null
        }
    }

    /**
     * Whether older items (the library from before) may be worked through now: while charging, when
     * the user chose "also on battery", or asked ("Do it now"). New items don't wait for this.
     */
    fun backlogAllowed(userAsked: Boolean = false): Boolean = userAsked || isCharging || policy().onBattery

    /** Items newer than this are "new": done right away, even on battery. */
    fun since(userAsked: Boolean = false): Long = if (backlogAllowed(userAsked)) 0L else System.currentTimeMillis() - RECENT_MILLIS

    /**
     * Run the AI gently (background priority, efficient cores) so the phone stays smooth: always on
     * battery, unless the user asked for it ("Do it now", "Describe now").
     */
    fun gentle(userAsked: Boolean = false): Boolean = !userAsked && !isCharging

    /** One thread per fast core while charging; at most two on battery (slower, but cooler and lighter). */
    fun threads(): Int = if (isCharging) LlamaEngine.defaultThreads() else minOf(2, LlamaEngine.defaultThreads())

    private companion object {
        const val RECENT_MILLIS = 2L * 24 * 60 * 60_000
    }
}
