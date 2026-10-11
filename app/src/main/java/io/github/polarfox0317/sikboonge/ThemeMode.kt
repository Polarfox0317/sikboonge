package io.github.polarfox0317.sikboonge

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    System,
    Light,
    Dark,
}

object ThemeModeStore {
    private const val PREFERENCES_NAME = "sikboonge_appearance"
    private const val MODE_KEY = "theme_mode"
    private val lock = Any()
    private val mutableMode = MutableStateFlow(ThemeMode.System)
    private var initialized = false

    val mode: StateFlow<ThemeMode> = mutableMode.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val appContext = context.applicationContext
            val savedName = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(MODE_KEY, null)
            mutableMode.value = ThemeMode.entries.firstOrNull { it.name == savedName }
                ?: ThemeMode.System
            applyApplicationNightMode(appContext, mutableMode.value)
            initialized = true
        }
    }

    fun set(context: Context, mode: ThemeMode) {
        val appContext = context.applicationContext
        synchronized(lock) {
            mutableMode.value = mode
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(MODE_KEY, mode.name)
                .apply()
            applyApplicationNightMode(appContext, mode)
            initialized = true
        }
    }

    private fun applyApplicationNightMode(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val nightMode = when (mode) {
            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        context.getSystemService(UiModeManager::class.java)
            ?.setApplicationNightMode(nightMode)
    }
}
