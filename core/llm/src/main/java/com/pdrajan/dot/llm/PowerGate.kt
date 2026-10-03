package com.pdrajan.dot.llm

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * When an on-device model may run without costing much battery or heating the phone. Each summary
 * or photo description is seconds of full CPU, so on battery it only runs when the phone is cool,
 * battery saver is off and there's charge to spare; background work waits for the charger.
 */
class PowerGate(context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    val isCharging: Boolean get() = runCatching { battery.isCharging }.getOrDefault(false)

    private val level: Int get() = runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(100)

    private val powerSave: Boolean get() = runCatching { power.isPowerSaveMode }.getOrDefault(false)

    private val thermal: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { power.currentThermalStatus }.getOrDefault(0) else 0

    /**
     * Why summaries can't run right now, or null when they can. [userAsked] ("Process now") skips
     * the battery checks, but nothing runs on a hot phone.
     */
    fun blocker(userAsked: Boolean = false): String? {
        val t = thermal
        if (t >= PowerManager.THERMAL_STATUS_SEVERE) return "phone is hot"
        if (isCharging || userAsked) return null
        return when {
            powerSave -> "battery saver is on"
            level < MIN_BATTERY -> "battery below $MIN_BATTERY%"
            t >= PowerManager.THERMAL_STATUS_MODERATE -> "phone is warm"
            else -> null
        }
    }

    /** Fewer threads on battery: a little slower, but the big cores aren't all at full power. */
    fun threads(): Int = if (isCharging) LlamaEngine.defaultThreads() else 2

    private companion object {
        const val MIN_BATTERY = 30
    }
}
