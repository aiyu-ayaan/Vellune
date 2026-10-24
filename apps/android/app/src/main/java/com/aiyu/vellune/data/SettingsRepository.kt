package com.aiyu.vellune.data

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.core.content.edit
import com.aiyu.vellune.core.input.InputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val inputMode: InputMode,
    val palmSizeMm: Float = DEFAULT_PALM_MM,
    val lockoutEnabled: Boolean = true,
    val lockoutMs: Int = 400,
    val prediction: Boolean = true,
    val showHud: Boolean = false,
    val recordTraces: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
) {
    companion object {
        const val DEFAULT_PALM_MM = 20f
    }
}

/** Small key-value settings; SharedPreferences is enough until the Rust store lands. */
class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        save(_settings.value)
    }

    /** True when any attached input device reports a stylus source. */
    fun hasStylus(): Boolean {
        val im = context.getSystemService(InputManager::class.java) ?: return false
        return im.inputDeviceIds.any { id ->
            val source = im.getInputDevice(id)?.sources ?: 0
            source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
        }
    }

    private fun load(): AppSettings {
        // docs/05: "Pen only" by default when a stylus is detected, otherwise "Finger only".
        val defaultMode = if (hasStylus()) InputMode.PenOnly else InputMode.FingerOnly
        return AppSettings(
            inputMode = prefs.getString(KEY_MODE, null)?.let { runCatching { InputMode.valueOf(it) }.getOrNull() } ?: defaultMode,
            palmSizeMm = prefs.getFloat(KEY_PALM, AppSettings.DEFAULT_PALM_MM),
            lockoutEnabled = prefs.getBoolean(KEY_LOCKOUT, true),
            lockoutMs = prefs.getInt(KEY_LOCKOUT_MS, 400),
            prediction = prefs.getBoolean(KEY_PREDICTION, true),
            showHud = prefs.getBoolean(KEY_HUD, false),
            recordTraces = prefs.getBoolean(KEY_TRACES, false),
            themeMode = prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        )
    }

    private fun save(s: AppSettings) = prefs.edit {
        putString(KEY_MODE, s.inputMode.name)
        putFloat(KEY_PALM, s.palmSizeMm)
        putBoolean(KEY_LOCKOUT, s.lockoutEnabled)
        putInt(KEY_LOCKOUT_MS, s.lockoutMs)
        putBoolean(KEY_PREDICTION, s.prediction)
        putBoolean(KEY_HUD, s.showHud)
        putBoolean(KEY_TRACES, s.recordTraces)
        putString(KEY_THEME, s.themeMode.name)
        putBoolean(KEY_DYNAMIC, s.dynamicColor)
    }

    private companion object {
        const val KEY_MODE = "input_mode"
        const val KEY_PALM = "palm_mm"
        const val KEY_LOCKOUT = "lockout"
        const val KEY_LOCKOUT_MS = "lockout_ms"
        const val KEY_PREDICTION = "prediction"
        const val KEY_HUD = "hud"
        const val KEY_TRACES = "record_traces"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
    }
}
