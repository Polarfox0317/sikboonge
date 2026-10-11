package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.LibraryDownload

internal fun BotVideo.hasActiveSave(downloads: List<LibraryDownload>): Boolean {
    return downloads.any { it.sourceBotFileId == fileId }
}
