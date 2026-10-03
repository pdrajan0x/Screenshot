package com.pdrajan.dotgallery.data

import android.content.Context
import androidx.core.content.edit
import com.pdrajan.dot.design.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GallerySettings(context: Context) {
    private val prefs = context.getSharedPreferences("gallery_settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _people = bool("people", true)
    val people: StateFlow<Boolean> = _people.asStateFlow()

    private val _readText = bool("read_text", true)
    val readText: StateFlow<Boolean> = _readText.asStateFlow()

    private val _readHindi = bool("read_hindi", true)
    val readHindi: StateFlow<Boolean> = _readHindi.asStateFlow()

    private val _charging = bool("backlog_charging", true)
    val backlogWhileCharging: StateFlow<Boolean> = _charging.asStateFlow()

    private val _onboarded = bool("onboarded", false)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    private val _columns = MutableStateFlow(prefs.getInt("columns", 4))
    val columns: StateFlow<Int> = _columns.asStateFlow()

    fun setTheme(mode: ThemeMode) { prefs.edit { putString("theme", mode.name) }; _theme.value = mode }
    fun setPeople(v: Boolean) { prefs.edit { putBoolean("people", v) }; _people.value = v }
    fun setReadText(v: Boolean) { prefs.edit { putBoolean("read_text", v) }; _readText.value = v }
    fun setReadHindi(v: Boolean) { prefs.edit { putBoolean("read_hindi", v) }; _readHindi.value = v }
    fun setBacklogWhileCharging(v: Boolean) { prefs.edit { putBoolean("backlog_charging", v) }; _charging.value = v }
    fun setOnboarded() { prefs.edit { putBoolean("onboarded", true) }; _onboarded.value = true }
    fun setColumns(n: Int) { prefs.edit { putInt("columns", n) }; _columns.value = n }
}
