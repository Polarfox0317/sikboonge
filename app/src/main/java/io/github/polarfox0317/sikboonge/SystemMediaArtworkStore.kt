package io.github.polarfox0317.sikboonge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preference for publishing imported artwork to Android's system media controls. */
internal object SystemMediaArtworkStore {
    private const val PREFERENCES_NAME = "system_media_artwork"
    private const val KEY_ENABLED = "enabled"

    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        mutableEnabled.value = preferences.getBoolean(KEY_ENABLED, false)
    }

    fun set(context: Context, enabled: Boolean) {
        mutableEnabled.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
