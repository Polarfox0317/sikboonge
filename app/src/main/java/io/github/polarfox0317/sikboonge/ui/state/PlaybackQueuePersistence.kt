package io.github.polarfox0317.sikboonge.ui.state

import android.content.Context
import io.github.polarfox0317.sikboonge.PlaybackSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * Small, private app-data checkpoint for the resumable player session.
 *
 * [currentKey]/[resumeKey]/[pausedKey] are media keys kept for bookmark and legacy compatibility.
 * The `*EntryId` fields name the exact occurrence, so two queued copies of one media restore the one
 * the user actually selected. A v1 checkpoint has no entry ids and resolves them to its row.
 */
internal data class PlaybackQueueSnapshot(
    val rows: List<QueueRow>,
    val currentKey: String?,
    val resumeKey: String?,
    val pausedKey: String?,
    val pausedPositionMs: Long,
    val pausedDurationMs: Long,
    val playerExpanded: Boolean,
    val emptyPlayerVisible: Boolean,
    val currentEntryId: String? = null,
    val resumeEntryId: String? = null,
    val pausedEntryId: String? = null,
)

internal object PlaybackQueueSnapshotCodec {
    /** The version this writer emits; version 1 checkpoints are still readable. */
    private const val FORMAT_VERSION = 2
    private const val LEGACY_FORMAT_VERSION = 1
    private const val MAX_ROWS = 10_000

    fun encode(snapshot: PlaybackQueueSnapshot): String {
        require(snapshot.rows.size <= MAX_ROWS)
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(snapshot.rows.size)
                snapshot.rows.forEach { row ->
                    output.writeUTF(row.entryId)
                    output.writeUTF(row.key)
                    output.writeUTF(row.title)
                    output.writeUTF(row.sourceKind.name)
                    output.writeNullableString(row.localAudioPath)
                    output.writeLong(row.botMessageId ?: -1L)
                    output.writeLong(row.botChatId ?: -1L)
                    output.writeInt(row.botFileId ?: -1)
                    output.writeNullableString(row.artworkPath)
                }
                output.writeNullableString(snapshot.currentKey)
                output.writeNullableString(snapshot.resumeKey)
                output.writeNullableString(snapshot.pausedKey)
                output.writeLong(snapshot.pausedPositionMs.coerceAtLeast(0L))
                output.writeLong(snapshot.pausedDurationMs.coerceAtLeast(0L))
                output.writeBoolean(snapshot.playerExpanded)
                output.writeBoolean(snapshot.emptyPlayerVisible)
                output.writeNullableString(snapshot.currentEntryId)
                output.writeNullableString(snapshot.resumeEntryId)
                output.writeNullableString(snapshot.pausedEntryId)
            }
            buffer.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String): PlaybackQueueSnapshot? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == FORMAT_VERSION || version == LEGACY_FORMAT_VERSION)
            val count = input.readInt()
            require(count in 0..MAX_ROWS)
            val rows = List(count) { index ->
                // A legacy row has no occurrence id, so its saved position decides the id. A blank one
                // heals the same way instead of colliding with another blank row.
                val entryId = if (version >= 2) input.readUTF().takeIf(String::isNotBlank) ?: "legacy-$index" else "legacy-$index"
                val key = input.readUTF()
                val title = input.readUTF()
                val sourceName = input.readUTF()
                val source = PlaybackSource.entries.firstOrNull { it.name == sourceName }
                    ?.takeIf { it != PlaybackSource.NONE } ?: error("Invalid queue source")
                QueueRow(
                    entryId = entryId,
                    key = key,
                    title = title,
                    sourceKind = source,
                    localAudioPath = input.readNullableString(),
                    botMessageId = input.readLong().takeIf { it >= 0L },
                    botChatId = input.readLong().takeIf { it >= 0L },
                    botFileId = input.readInt().takeIf { it >= 0 },
                    artworkPath = input.readNullableString(),
                )
            }
            val currentKey = input.readNullableString()
            val resumeKey = input.readNullableString()
            val pausedKey = input.readNullableString()
            val pausedPositionMs = input.readLong().coerceAtLeast(0L)
            val pausedDurationMs = input.readLong().coerceAtLeast(0L)
            val playerExpanded = input.readBoolean()
            val emptyPlayerVisible = input.readBoolean()
            // A v1 checkpoint only knows media keys; resolve each to the saved row it names.
            fun legacyEntryId(mediaKey: String?): String? =
                mediaKey?.let { key -> rows.firstOrNull { it.key == key }?.entryId }
            PlaybackQueueSnapshot(
                rows = rows,
                currentKey = currentKey,
                resumeKey = resumeKey,
                pausedKey = pausedKey,
                pausedPositionMs = pausedPositionMs,
                pausedDurationMs = pausedDurationMs,
                playerExpanded = playerExpanded,
                emptyPlayerVisible = emptyPlayerVisible,
                currentEntryId = if (version >= 2) input.readNullableString() else legacyEntryId(currentKey),
                resumeEntryId = if (version >= 2) input.readNullableString() else legacyEntryId(resumeKey),
                pausedEntryId = if (version >= 2) input.readNullableString() else legacyEntryId(pausedKey),
            ).also { require(input.available() == 0) }
        }
    }.getOrNull()

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readUTF() else null
}

/** Persists only resumable queue identity and playhead; it never starts playback on its own. */
internal class PlaybackQueueStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): PlaybackQueueSnapshot? = preferences.getString(SNAPSHOT_KEY, null)
        ?.let(PlaybackQueueSnapshotCodec::decode)

    fun write(snapshot: PlaybackQueueSnapshot?) {
        preferences.edit().run {
            if (snapshot == null || snapshot.rows.isEmpty()) remove(SNAPSHOT_KEY)
            else putString(SNAPSHOT_KEY, PlaybackQueueSnapshotCodec.encode(snapshot))
        }.apply()
    }

    /** The number of explicit closes so far; a Bundle from an older epoch is a stale session. */
    fun closeGeneration(): Int = preferences.getInt(CLOSE_GENERATION_KEY, 0)

    /**
     * The explicit close: the checkpoint is gone from memory and disk before the caller clears the
     * UI, so a process death right after the close cannot restore the dismissed session. The epoch is
     * bumped in the same commit, which also retires every Bundle written before the close.
     */
    fun clearBlocking() {
        preferences.edit()
            .remove(SNAPSHOT_KEY)
            .putInt(CLOSE_GENERATION_KEY, closeGeneration() + 1)
            .commit()
    }

    private companion object {
        const val FILE_NAME = "playback_queue_session"
        const val SNAPSHOT_KEY = "snapshot_v1"
        const val CLOSE_GENERATION_KEY = "close_generation"
    }
}
