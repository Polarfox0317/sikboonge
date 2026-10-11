package io.github.polarfox0317.sikboonge

import android.content.Context

enum class DownloadExecutionMode {
    Sequential,
    Parallel,
}

object DownloadExecutionPreferences {
    internal const val PREFERENCES_NAME = "sikboonge_download_execution"
    internal const val MODE_KEY = "mode"

    fun load(context: Context): DownloadExecutionMode {
        val savedName = preferences(context).getString(MODE_KEY, null)
        return DownloadExecutionMode.entries.firstOrNull { it.name == savedName }
            ?: DownloadExecutionMode.Parallel
    }

    fun save(context: Context, mode: DownloadExecutionMode) {
        preferences(context).edit().putString(MODE_KEY, mode.name).apply()
    }

    internal fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
