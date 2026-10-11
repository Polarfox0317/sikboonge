package io.github.polarfox0317.sikboonge.ui.welcome

import android.content.Context
import java.io.File

internal object WelcomePreferences {
    private const val PREFERENCES_NAME = "sikboonge_welcome"
    private const val KEY_STATE = "state"
    private const val STATE_PENDING = "pending"
    private const val STATE_COMPLETED = "completed"

    fun evaluateOnLaunch(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_STATE)) return

        val sharedPreferencesDirectory = File(context.applicationInfo.dataDir, "shared_prefs")
        val hasOtherPreferences = sharedPreferencesDirectory.listFiles()
            .orEmpty()
            .any { it.isFile && it.name != "$PREFERENCES_NAME.xml" }
        val hasPersistedAppStoreData = File(context.filesDir, "history.json").exists()
        val state = if (hasOtherPreferences || hasPersistedAppStoreData) STATE_COMPLETED else STATE_PENDING

        preferences.edit()
            .remove("completed")
            .putString(KEY_STATE, state)
            .commit()
    }

    fun shouldShow(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) == STATE_PENDING

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("completed")
            .putString(KEY_STATE, STATE_COMPLETED)
            .commit()
    }
}
