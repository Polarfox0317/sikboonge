package io.github.polarfox0317.sikboonge

/** Notification lifetime is separate from scheduler lifetime: a stopping worker still owns a slot. */
internal object DownloadNotificationLifecyclePolicy {
    data class Entry(
        val notificationId: Int,
        val active: Boolean = false,
        val cancelled: Boolean = false,
        val committed: Boolean = false,
        val paused: Boolean = false,
        val pausing: Boolean = false,
        val pending: Boolean = true,
    )

    enum class StopBehavior { Remove, Detach }
    data class Transition(val stop: StopBehavior? = null, val promoteId: Int? = null)

    fun canPostProgress(entry: Entry): Boolean = entry.pending && !entry.cancelled && !entry.committed

    fun selectForeground(active: List<Entry>, queued: List<Entry>): Entry? =
        active.firstOrNull { canPostProgress(it) && !it.pausing }
            ?: active.firstOrNull(::canPostProgress)
            ?: queued.firstOrNull { canPostProgress(it) && !it.paused }
            // Deliberately retain a required stopping row ONLY if no live work can represent it.
            // This does not authorize ordinary progress callbacks to republish a cancelled job.
            ?: active.firstOrNull { it.active }

    fun transition(previous: Entry?, next: Entry?): Transition {
        if (previous?.notificationId == next?.notificationId) return Transition()
        return Transition(
            stop = previous?.let { if (canPostProgress(it)) StopBehavior.Detach else StopBehavior.Remove },
            promoteId = next?.notificationId,
        )
    }

    fun shouldRemoveCancelled(entry: Entry, foregroundId: Int?): Boolean =
        entry.cancelled && !entry.committed && entry.notificationId != foregroundId
}
