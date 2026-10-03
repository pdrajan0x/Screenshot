package com.pdrajan.dotscreenshots.data

import android.content.Context
import androidx.core.content.edit
import com.pdrajan.dot.design.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _readHindi = MutableStateFlow(prefs.getBoolean(KEY_HINDI, true))
    val readHindi: StateFlow<Boolean> = _readHindi.asStateFlow()

    private val _backlogWhileCharging = MutableStateFlow(prefs.getBoolean(KEY_CHARGING, true))
    val backlogWhileCharging: StateFlow<Boolean> = _backlogWhileCharging.asStateFlow()

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _summaries = MutableStateFlow(prefs.getBoolean(KEY_SUMMARIES, true))
    /** Write AI summaries (once the model is downloaded). */
    val summariesEnabled: StateFlow<Boolean> = _summaries.asStateFlow()

    private val _tipDismissed = MutableStateFlow(prefs.getBoolean(KEY_TIP, false))
    /** The home screen's "make search smarter" card was dismissed. */
    val smartTipDismissed: StateFlow<Boolean> = _tipDismissed.asStateFlow()

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

    fun setBacklogWhileCharging(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CHARGING, enabled) }
        _backlogWhileCharging.value = enabled
    }

    fun setOnboardingDone() {
        prefs.edit { putBoolean(KEY_ONBOARDED, true) }
        _onboardingDone.value = true
    }

    fun setSummariesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SUMMARIES, enabled) }
        _summaries.value = enabled
    }

    fun dismissSmartTip() {
        prefs.edit { putBoolean(KEY_TIP, true) }
        _tipDismissed.value = true
    }

    fun setGridColumns(columns: Int) {
        prefs.edit { putInt(KEY_COLUMNS, columns) }
        _gridColumns.value = columns
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_HINDI = "read_hindi"
        const val KEY_CHARGING = "backlog_while_charging"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_COLUMNS = "grid_columns"
        const val KEY_AVG_MS = "avg_index_ms"
        const val KEY_SUMMARIES = "summaries"
        const val KEY_TIP = "smart_tip_dismissed"
    }
}
