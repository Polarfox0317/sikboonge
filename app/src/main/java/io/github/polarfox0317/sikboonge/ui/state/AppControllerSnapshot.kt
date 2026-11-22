package io.github.polarfox0317.sikboonge.ui.state

import android.os.Bundle
import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.StreamingState

private const val QUEUE_ROW_COUNT = "queueRowCount"
private const val QUEUE_ROWS = "queueRows"
private const val ARTWORK_SLOT_PREFIX = "queueArtwork:"

/** Bot artwork travels as Base64; keep it outside the per-row bundles so rows stay small. */
internal fun saveQueueRows(bundle: Bundle, queue: List<QueueItemUi>) {
    val rows = Bundle()
    queue.forEachIndexed { index, item ->
        rows.putBundle("r$index", Bundle().apply {
            putString("entryId", item.entryId)
            putString("key", item.key)
            putString("title", item.title)
            putString("source", item.sourceKind.name)
            putString("localPath", item.localAudioPath)
            putLong("messageId", item.botMessageId ?: -1L)
            putLong("chatId", item.botChatId ?: -1L)
            putInt("fileId", item.botFileId ?: -1)
            putString("artworkPath", item.artworkPath)
        })
        if (item.artworkBase64.isNotEmpty()) bundle.putString(ARTWORK_SLOT_PREFIX + index, item.artworkBase64)
    }
    bundle.putInt(QUEUE_ROW_COUNT, queue.size)
    bundle.putBundle(QUEUE_ROWS, rows)
}

internal fun readQueueRows(bundle: Bundle): List<QueueRow> {
    val rows = bundle.getBundle(QUEUE_ROWS) ?: return emptyList()
    val count = bundle.getInt(QUEUE_ROW_COUNT, 0)
    return (0 until count).mapNotNull { index ->
        val row = rows.getBundle("r$index") ?: return@mapNotNull null
        val key = row.getString("key")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val sourceKind = PlaybackSource.entries.firstOrNull { it.name == row.getString("source") }
            ?: return@mapNotNull null
        QueueRow(
            // A saved Bundle from before occurrence ids has none, so its position decides the id.
            entryId = row.getString("entryId")?.takeIf(String::isNotBlank) ?: "bundle-$index",
            key = key,
            title = row.getString("title").orEmpty(),
            sourceKind = sourceKind,
            localAudioPath = row.getString("localPath"),
            botMessageId = row.getLong("messageId", -1L).takeIf { it >= 0L },
            botChatId = row.getLong("chatId", -1L).takeIf { it >= 0L },
            botFileId = row.getInt("fileId", -1).takeIf { it >= 0 },
            artworkPath = row.getString("artworkPath"),
            artworkBase64 = bundle.getString(ARTWORK_SLOT_PREFIX + index).orEmpty(),
        )
    }
}

/** Position shown by the player for a stopped session; an ended track reports its end. */
internal fun playheadPositionMs(stream: StreamingState): Long =
    if (stream.ended && stream.durationMs > 0L) stream.durationMs else stream.positionMs.coerceAtLeast(0L)
