package io.github.polarfox0317.sikboonge.ui.state

import android.content.Context
import io.github.polarfox0317.sikboonge.BotRuntime
import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.ReconnectStart

/** Runtime boundary for the controller's reconnect-and-replay operation. */
internal interface BotPlaybackRecovery {
    fun isAuthenticated(): Boolean
    fun hasClient(): Boolean
    fun isReconnecting(): Boolean
    fun reconnect(context: Context): ReconnectStart
    fun play(context: Context, video: BotVideo, positionMs: Long)
}

internal object TelegramBotPlaybackRecovery : BotPlaybackRecovery {
    override fun isAuthenticated() = BotRuntime.isAuthenticated()
    override fun hasClient() = BotRuntime.hasClient()
    override fun isReconnecting() = BotRuntime.isReconnecting()
    override fun reconnect(context: Context) = BotRuntime.reconnectForPlayback(context)
    override fun play(context: Context, video: BotVideo, positionMs: Long) =
        BotRuntime.play(context, video, positionMs)
}
