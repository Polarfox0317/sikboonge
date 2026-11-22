package io.github.polarfox0317.sikboonge.ui.feedback

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

    /**
     * Returns the preparation text when it starts a new job episode, so a one-time notice is
     * announced once per episode. Later percentage updates keep the same message and rearm only
     * after the job goes inactive.
     */
    fun updateProgress(jobActive: Boolean, text: String): String? {
        if (!jobActive) {
            // Preparation can finish before the queued worker starts. Only rearm here.
            progressAnnounced = false
            return null
        }
        if (progressAnnounced || text.isBlank()) return null
        show(text)
        progressAnnounced = true
        return text
    }

    fun dismiss(expectedId: Long? = null) {
        if (expectedId == null || current?.id == expectedId) current = null
    }
}
