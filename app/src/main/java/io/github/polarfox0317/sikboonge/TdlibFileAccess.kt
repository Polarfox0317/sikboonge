package io.github.polarfox0317.sikboonge

import org.drinkless.tdlib.TdApi

/** Injectable boundary for range reads; production implementation is BotRuntime. */
internal interface TdlibFileAccess {
    fun acquireFile(fileId: Int): TdApi.File
    fun beginPlayback(fileId: Int)
    fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int
    fun endPlayback(fileId: Int)
}
