package com.pdrajan.dotscreenshots.data

import android.content.Context
import androidx.core.content.edit
import com.pdrajan.dot.design.ThemeMode
import com.pdrajan.dot.media.ProcessingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _readHindi = MutableStateFlow(prefs.getBoolean(KEY_HINDI, true))
    val readHindi: StateFlow<Boolean> = _readHindi.asStateFlow()

    private val _processing = MutableStateFlow(ProcessingPolicy.load(prefs))
    /** Settings → Processing: in the background or not, only while charging or above a battery level. */
    val processing: StateFlow<ProcessingPolicy> = _processing.asStateFlow()

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _gridColumns = MutableStateFlow(prefs.getInt(KEY_COLUMNS, 3))
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    /** Running average of per-screenshot analysis time, for the settings screen. */
    var avgIndexMillis: Long
        get() = prefs.getLong(KEY_AVG_MS, 0)
        set(value) = prefs.edit { putLong(KEY_AVG_MS, value) }

    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _theme.value = mode
    }

    fun setReadHindi(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HINDI, enabled) }
        _readHindi.value = enabled
    }

    fun setProcessing(policy: ProcessingPolicy) {
        policy.save(prefs)
        _processing.value = policy
    }

    fun setOnboardingDone() {
        prefs.edit { putBoolean(KEY_ONBOARDED, true) }
        _onboardingDone.value = true
    }

    fun setGridColumns(columns: Int) {
        prefs.edit { putInt(KEY_COLUMNS, columns) }
        _gridColumns.value = columns
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_HINDI = "read_hindi"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_COLUMNS = "grid_columns"
        const val KEY_AVG_MS = "avg_index_ms"
    }
}
