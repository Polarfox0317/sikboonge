package io.github.polarfox0317.sikboonge.ui.state

import android.content.Intent
import io.github.polarfox0317.sikboonge.AudioResult

/** Activity-owned operations; implementations must be rebound after recreation. */
interface HostEffects {
    val diagnosticsAvailable: Boolean
    fun pickVideo()
    fun saveAudioCopy(result: AudioResult)
    fun requestNotificationPermission()
    fun exportDiagnostics()
    fun startShare(intent: Intent)
    fun openExternalUrl(url: String)
}

/** Each emission is a new event, including repeated identical notices; the UI owns expiry. */
sealed interface UiMessage {
    val text: String
    val isError: Boolean
    val persistent: Boolean

    data class Text(
        override val text: String,
        override val isError: Boolean = false,
        override val persistent: Boolean = false,
    ) : UiMessage
}
