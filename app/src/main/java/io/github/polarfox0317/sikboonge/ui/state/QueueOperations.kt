package io.github.polarfox0317.sikboonge.ui.state

/** Position retained when the playing track is removed without stopping playback. */
internal data class QueueMutation(val queue: List<QueueItemUi>, val index: Int, val resumeIndex: Int)

internal fun removeQueueEntries(queue: List<QueueItemUi>, index: Int, resumeIndex: Int, keys: Set<String>): QueueMutation {
    val currentKey = queue.getOrNull(index)?.key
    val anchor = if (index >= 0) index else resumeIndex.coerceIn(0, queue.size)
    val retained = queue.filterNot { it.key in keys }
    val nextAnchor = (anchor - queue.take(anchor).count { it.key in keys }).coerceIn(0, retained.size)
    return QueueMutation(retained, retained.indexOfFirst { it.key == currentKey }, nextAnchor)
}

internal fun moveQueueEntry(queue: List<QueueItemUi>, index: Int, resumeIndex: Int, from: Int, to: Int): QueueMutation {
    if (from !in queue.indices || to !in queue.indices || from == to) return QueueMutation(queue, index, resumeIndex)
    val currentKey = queue.getOrNull(index)?.key
    val nextKey = queue.getOrNull(resumeIndex)?.key
    val moved = queue.toMutableList().apply { add(to, removeAt(from)) }
    val nextAnchor = nextKey?.let { key -> moved.indexOfFirst { it.key == key } } ?: moved.size
    return QueueMutation(moved, moved.indexOfFirst { it.key == currentKey }, nextAnchor)
}
