package io.github.polarfox0317.sikboonge.ui.feedback

internal const val TRANSIENT_MESSAGE_TIMEOUT_MS = 3500L

/** Message identity owns expiry; background job snapshots do not own dismissal. */
internal class MessageLifetimePolicy {
    data class Message(val id: Long, val text: String, val persistent: Boolean)

    var current: Message? = null
        private set
    private var nextId = 0L
    private var progressAnnounced = false

    fun show(text: String, persistent: Boolean = false) {
        if (text.isBlank()) return
        if (current?.text == text && current?.persistent == persistent) return
        current = Message(++nextId, text, persistent)
    }

    fun updateProgress(jobActive: Boolean, text: String) {
        if (!jobActive) {
            // Preparation can finish before the queued worker starts. Only rearm here.
            progressAnnounced = false
        } else if (!progressAnnounced && text.isNotBlank()) {
            show(text)
            progressAnnounced = true
        }
    }

    fun dismiss(expectedId: Long? = null) {
        if (expectedId == null || current?.id == expectedId) current = null
    }
}
