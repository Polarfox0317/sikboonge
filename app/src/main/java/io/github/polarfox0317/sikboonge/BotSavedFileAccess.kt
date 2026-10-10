package io.github.polarfox0317.sikboonge

import java.io.File

internal interface BotSavedFileAccess {
    fun awaitCompletedFile(fileId: Int, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File
    fun endSave(fileId: Int)
}

internal object BotSaveTestHooks {
    @Volatile var accessOverride: BotSavedFileAccess? = null
}
