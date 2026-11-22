package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.QueueTransportAvailability

/** Position retained when the playing track is removed, and whether that removal must stop playback. */
internal data class QueueMutation(
    val queue: List<QueueItemUi>,
    val index: Int,
    val resumeIndex: Int,
    val removedCurrent: Boolean = false,
)

internal sealed interface QueueNaturalEndAction {
    data class PlayNext(val index: Int) : QueueNaturalEndAction
    data object Stop : QueueNaturalEndAction
}

/** Emits a single event for each transition into the player's ended state. */
internal class QueueNaturalEndEdge(initiallyEnded: Boolean) {
    private var wasEnded = initiallyEnded

    fun observe(ended: Boolean): Boolean {
        val edge = !wasEnded && ended
        wasEnded = ended
        return edge
    }
}

internal fun queueTransportAvailability(
    queue: List<QueueItemUi>,
    index: Int,
    resumeIndex: Int,
    positionMs: Long,
): QueueTransportAvailability {
    val previousIndex = if (index in queue.indices) index - 1 else resumeIndex - 1
    val nextIndex = if (index in queue.indices) index + 1 else resumeIndex
    return QueueTransportAvailability(
        hasPrevious = queue.isNotEmpty() && (positionMs > 3_000L || previousIndex in queue.indices),
        hasNext = nextIndex in queue.indices,
    )
}

/**
 * Returns null for stale/unmatched end events; the final row explicitly stops playback.
 *
 * [currentEntryId] names the exact occurrence that ended. Two queued copies of one media share a
 * media key, so resolving by key could rewind the first row's next action instead of finishing the
 * queue.
 */
internal fun queueNaturalEndAction(
    queue: List<QueueItemUi>,
    currentEntryId: String?,
    endedSourceMatchesCurrent: Boolean,
): QueueNaturalEndAction? {
    if (!endedSourceMatchesCurrent || currentEntryId == null) return null
    val index = queue.indexOfFirst { it.entryId == currentEntryId }
    if (index !in queue.indices) return null
    val nextIndex = index + 1
    return if (nextIndex in queue.indices) QueueNaturalEndAction.PlayNext(nextIndex) else QueueNaturalEndAction.Stop
}

/** A direct play action starts a fresh one-item queue. */
internal fun queueForPlayback(item: QueueItemUi): QueueMutation = QueueMutation(
    queue = listOf(item),
    index = 0,
    resumeIndex = 0,
)

/** A playlist-row play selects that row while preserving every queue entry. */
internal fun selectQueueEntry(queue: List<QueueItemUi>, index: Int): QueueMutation =
    if (index in queue.indices) QueueMutation(queue, index, index)
    else QueueMutation(queue, -1, index.coerceIn(0, queue.size))

/**
 * The explicit Add to playlist action appends a new occurrence at the end, even when the same media
 * is already queued. [item] must be a fresh occurrence; pass the existing row's [QueueItemUi.entryId]
 * when re-resolving one so it does not fork.
 */
internal fun appendQueueEntry(queue: List<QueueItemUi>, item: QueueItemUi): List<QueueItemUi> =
    if (queue.any { it.entryId == item.entryId }) queue else queue + item

/** Removes the exact occurrences in [entryIds]; a duplicate of the same media is a different entry. */
internal fun removeQueueEntries(queue: List<QueueItemUi>, index: Int, resumeIndex: Int, entryIds: Set<String>): QueueMutation {
    val currentEntryId = queue.getOrNull(index)?.entryId
    val anchor = if (index >= 0) index else resumeIndex.coerceIn(0, queue.size)
    val retained = queue.filterNot { it.entryId in entryIds }
    val nextAnchor = (anchor - queue.take(anchor).count { it.entryId in entryIds }).coerceIn(0, retained.size)
    return QueueMutation(retained, retained.indexOfFirst { it.entryId == currentEntryId }, nextAnchor,
        removedCurrent = currentEntryId != null && currentEntryId in entryIds)
}

internal fun moveQueueEntry(queue: List<QueueItemUi>, index: Int, resumeIndex: Int, from: Int, to: Int): QueueMutation {
    if (from !in queue.indices || to !in queue.indices || from == to) return QueueMutation(queue, index, resumeIndex)
    val currentEntryId = queue.getOrNull(index)?.entryId
    val nextEntryId = queue.getOrNull(resumeIndex)?.entryId
    val moved = queue.toMutableList().apply { add(to, removeAt(from)) }
    val nextAnchor = nextEntryId?.let { id -> moved.indexOfFirst { it.entryId == id } } ?: moved.size
    return QueueMutation(moved, moved.indexOfFirst { it.entryId == currentEntryId }, nextAnchor)
}
