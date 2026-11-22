package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource

/**
 * What reopening the app should publish, decided without Android dependencies so it can be tested.
 * Restore restores the queue and the saved spot only: it never starts a service, prepares media,
 * resolves a bot file, or expands the player.
 */
internal data class RestoreDecision(
    val queue: List<QueueItemUi>,
    val currentKey: String?,
    val pausedKey: String?,
    val positionMs: Long,
    val durationMs: Long,
    val autoplay: Boolean,
    val expandPlayer: Boolean,
    /** The presentation stream a reopened session shows: finished, stopped, and never loading. */
    val display: RestoreDisplay,
)

/** The paused mini player a restored session shows without any service behind it. */
internal data class RestoreDisplay(
    val source: PlaybackSource,
    val title: String,
    val ready: Boolean,
    val playing: Boolean,
    val detached: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

internal object PlaybackRestorePolicy {
    /**
     * The decision for reopening. [liveSource] is the current source after a late emission, if any;
     * it is deliberately not started, only shown.
     */
    fun reopen(
        snapshot: PlaybackQueueSnapshot?,
        rows: List<QueueItemUi>,
        liveSource: PlaybackSource = PlaybackSource.NONE,
    ): RestoreDecision? {
        if (snapshot == null || rows.isEmpty()) return null
        // The saved occurrence decides the shown title and source; the media key is the legacy fallback.
        val pausedRow = snapshot.pausedEntryId?.let { id -> rows.firstOrNull { it.entryId == id } }
            ?: rows.firstOrNull { it.key == snapshot.pausedKey }
        val source = liveSource.takeIf { it != PlaybackSource.NONE } ?: pausedRow?.sourceKind ?: PlaybackSource.NONE
        return RestoreDecision(
            queue = rows,
            currentKey = snapshot.currentKey,
            pausedKey = snapshot.pausedKey,
            positionMs = snapshot.pausedPositionMs.coerceAtLeast(0L),
            durationMs = snapshot.pausedDurationMs.coerceAtLeast(0L),
            // Reopening never starts playback; only an explicit press can.
            autoplay = false,
            // A reopen always returns to the mini player.
            expandPlayer = false,
            display = RestoreDisplay(
                source = source,
                title = pausedRow?.title.orEmpty(),
                // A restored session is a finished presentation, not an unresolved load.
                ready = true,
                playing = false,
                detached = true,
                positionMs = snapshot.pausedPositionMs.coerceAtLeast(0L),
                durationMs = snapshot.pausedDurationMs.coerceAtLeast(0L),
            ),
        )
    }
}
