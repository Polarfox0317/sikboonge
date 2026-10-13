package io.github.polarfox0317.sikboonge.security

import android.content.Context

internal class AppLockPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    /** Commit before exposing a changed setting so a process death cannot lose an authenticated change. */
    fun setEnabled(enabled: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()

    private companion object {
        const val FILE_NAME = "app_lock"
        const val KEY_ENABLED = "enabled"
    }
}
