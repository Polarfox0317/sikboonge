package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.StreamingState
import java.io.File

/** Resume data for a queued item whose server-side player no longer exists. */
internal data class PausedSource(
    val key: String,
    val positionMs: Long,
    /** Process-only token: identify a playback the app restored without starting it. */
    val resumeToken: String? = null,
)

/** What the player should display for a separated paused session, and which queue row it is. */
internal data class PausedDisplay(val stream: StreamingState?, val entryIndex: Int)

/**
 * A stream that no live service serves is a paused session, not active playback. Ownership is
 * decided by identity: local playback publishes its file path while preparing, and BOT playback
 * its file id (a resolving BOT request keeps its id). Readiness flags are presentation only. A
 * service that closed without a user close keeps its handles and marks itself detached.
 */
internal fun isSeparatedPausedStream(stream: StreamingState): Boolean = when (stream.source) {
    PlaybackSource.NONE -> false
    PlaybackSource.LOCAL_AUDIO -> stream.serviceDetached || stream.localAudioPath == null
    PlaybackSource.BOT -> stream.serviceDetached || stream.botFileId == null
}

/** One display rule shared by the shell and the player; never infer playback from position alone. */
internal fun StreamingState.isPlayerVisible(): Boolean =
    source != PlaybackSource.NONE &&
        (resolving || ready || error != null || playing || buffering || localAudioPath != null)

/**
 * The queue entry that a paused player resumes; the position comes from the paused record.
 *
 * A media may appear more than once, so an index that already points at the paused media wins over
 * the first match: the player resumes the copy the user was using, not the earliest one.
 */
internal fun pausedQueueIndex(
    queue: List<QueueItemUi>,
    queueIndex: Int,
    queueResumeIndex: Int,
    pausedKey: String?,
): Int {
    if (pausedKey != null) {
        queue.getOrNull(queueIndex)?.takeIf { it.key == pausedKey }?.let { return queueIndex }
        queue.getOrNull(queueResumeIndex)?.takeIf { it.key == pausedKey }?.let { return queueResumeIndex }
    }
    queue.indexOfFirst { it.key == pausedKey }.takeIf { it >= 0 }?.let { return it }
    if (queueIndex in queue.indices) return queueIndex
    return if (queue.isEmpty()) -1 else queueResumeIndex.coerceIn(0, queue.lastIndex)
}

/** Local queue identity: canonical path. Bot queue identity: owning chat and message. */
internal fun stableQueueKey(localAudioPath: String, canonical: (String) -> String): String =
    "local:${canonical(localAudioPath)}"

internal fun stableQueueKey(botChatId: Long, botMessageId: Long): String = "bot:$botChatId:$botMessageId"

/**
 * The queue key a played stream belongs to. A stream identifies its media by a playback handle
 * (the bot file id), while rows use a stable chat/message identity, so the row is matched first
 * and the handle is only a fallback. Local rows match by their canonical path.
 */
internal fun pausedQueueKey(stream: StreamingState, queue: List<QueueItemUi>): String = when (stream.source) {
    PlaybackSource.LOCAL_AUDIO -> {
        val path = stream.localAudioPath
        val row = path?.let { played ->
            queue.firstOrNull { item ->
                item.sourceKind == PlaybackSource.LOCAL_AUDIO && item.localAudioPath?.let { File(it).canonicalPath } == File(played).canonicalPath
            }
        }
        row?.key ?: path?.let { "local:${File(it).canonicalPath}" }.orEmpty()
    }
    PlaybackSource.BOT -> {
        val fileId = stream.botFileId
        val row = if (fileId == null) null
            else queue.firstOrNull { it.sourceKind == PlaybackSource.BOT && it.botFileId == fileId }
        row?.key ?: fileId?.let { "bot:$it" }.orEmpty()
    }
    PlaybackSource.NONE -> ""
}

/** A restored paused row as the real start request: its resolved identity plus saved position. */
internal data class RestoredPlaybackRequest(
    val item: QueueItemUi,
    val positionMs: Long,
)

/**
 * Turns a paused row into a playable request. Null means the app cannot start this row as local
 * media right now; the caller then keeps writing the stable identity for a later attempt.
 */
internal fun restoredPlaybackRequest(
    item: QueueItemUi,
    queue: List<QueueItemUi>,
    positionMs: Long,
    localAudioAvailable: Boolean,
    botVideoAvailable: Boolean,
): RestoredPlaybackRequest? = when (item.sourceKind) {
    PlaybackSource.LOCAL_AUDIO -> item.takeIf { localAudioAvailable }
    PlaybackSource.BOT -> item
        .copy(botFileId = item.resolvedBotFileId(queue))
        .takeIf { it.botFileId != null && it.botFileId > 0 && botVideoAvailable }
    PlaybackSource.NONE -> null
}?.let { RestoredPlaybackRequest(it, positionMs.coerceAtLeast(0L)) }

/**
 * The playback handle for a row when the row itself has none: a saved descriptor keeps the stable
 * identity, so the handle comes from a different row instance that carries the same stable key and
 * an actual handle. Rows with another chat/message identity never supply it.
 */
internal fun QueueItemUi.resolvedBotFileId(queue: List<QueueItemUi>): Int? = botFileId
    ?: queue.firstOrNull { candidate ->
        candidate !== this &&
            candidate.key == key &&
            candidate.sourceKind == PlaybackSource.BOT &&
            (candidate.botFileId ?: 0) > 0
    }?.botFileId

/** Identity and order are unchanged; every other restored field may be refreshed from its owner. */
internal fun sameRestoredRows(current: List<QueueItemUi>, restored: List<QueueItemUi>): Boolean =
    current == restored

/**
 * A saved queue row kept without its live owner. It stays a descriptor while its owner list is
 * still loading, e.g. before the bot inbox finishes its asynchronous restore.
 */
internal data class QueueRow(
    val key: String,
    val title: String,
    val sourceKind: PlaybackSource,
    val localAudioPath: String? = null,
    val botMessageId: Long? = null,
    val botChatId: Long? = null,
    val botFileId: Int? = null,
    val artworkPath: String? = null,
    val artworkBase64: String = "",
    /** The occurrence this saved row is; two rows of one media have different values. */
    val entryId: String = "",
)

/** Everything a process restore needs from one saved snapshot. */
internal data class QueueRestoreSpec(
    val rows: List<QueueRow>,
    val currentKey: String?,
    val resumeKey: String?,
    val pausedKey: String?,
    val pausedPositionMs: Long,
    val pausedArtworkBase64: String = "",
    val pausedDurationMs: Long = 0L,
    /** The exact occurrences the media keys name, so a duplicate restores the selected copy. */
    val currentEntryId: String? = null,
    val resumeEntryId: String? = null,
    val pausedEntryId: String? = null,
)

internal sealed interface RestoredRow {
    data class Live(val item: QueueItemUi) : RestoredRow
    data class Pending(val row: QueueRow) : RestoredRow
    data object Removed : RestoredRow
}

/**
 * Saved order is authoritative. A row whose owner already has it uses the live entry carrying the
 * saved occurrence id; any other row is kept as a descriptor until [live] resolves it, so a partial
 * first emission never drops the rest of the queue.
 *
 * A row missing from a loaded owner is deliberately NOT reported as [RestoredRow.Removed]: a
 * transient empty or partial owner emission (a list still syncing, a late first emission) would then
 * silently delete saved rows, which the product only allows through an explicit delete. Such a row
 * stays pending, so it keeps its descriptor and identity until the owner actually offers it.
 * [RestoredRow.Removed] remains for callers that decide removal themselves.
 */
internal fun resolveRestoreQueue(
    rows: List<QueueRow>,
    ownerLoaded: (PlaybackSource) -> Boolean,
    live: (QueueRow) -> QueueItemUi?,
): List<RestoredRow> = rows.map { row ->
    // The live owner describes the media, not the occurrence; the saved id is this row's identity.
    live(row)?.let { RestoredRow.Live(it.copy(entryId = row.entryId)) } ?: RestoredRow.Pending(row)
}

/** Saved rows rendered as queue entries; pending rows keep their descriptor until resolved. */
internal fun restoredRowItem(row: QueueRow): QueueItemUi = QueueItemUi(
    entryId = row.entryId,
    key = row.key,
    title = row.title,
    artworkPath = row.artworkPath,
    artworkBase64 = row.artworkBase64,
    sourceKind = row.sourceKind,
    localAudioPath = row.localAudioPath,
    botMessageId = row.botMessageId,
    botChatId = row.botChatId,
    botFileId = row.botFileId,
)

internal fun List<RestoredRow>.toQueueEntries(): List<QueueItemUi> = mapNotNull { row ->
    when (row) {
        is RestoredRow.Live -> row.item
        is RestoredRow.Pending -> restoredRowItem(row.row)
        RestoredRow.Removed -> null
    }
}

internal fun List<RestoredRow>.hasPendingRows(): Boolean = any { it is RestoredRow.Pending }

/**
 * Resume index after a restore: the paused/current saved occurrence wins, then the saved resume
 * occurrence, then the same lookup by media key for a legacy checkpoint, then a clamped fallback.
 * Never depend on a live index that a process restore never publishes.
 */
internal fun restoreResumeIndex(
    queue: List<QueueItemUi>,
    currentKey: String?,
    resumeKey: String?,
    fallback: Int,
    currentEntryId: String? = null,
    resumeEntryId: String? = null,
): Int {
    fun indexOf(entryId: String?, key: String?): Int? {
        entryId?.let { id -> queue.indexOfFirst { it.entryId == id }.takeIf { it >= 0 }?.let { return it } }
        return key?.let { mediaKey -> queue.indexOfFirst { it.key == mediaKey }.takeIf { it >= 0 } }
    }

    indexOf(currentEntryId, currentKey)?.let { return it }
    indexOf(resumeEntryId, resumeKey)?.let { return it }
    return fallback.coerceIn(0, queue.size)
}

