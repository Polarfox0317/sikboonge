package io.github.polarfox0317.sikboonge.ui.state

import android.content.Context
import io.github.polarfox0317.sikboonge.PlaybackSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * Per-item resume point. Identity is stable: a canonical local path, or a bot chat/message pair.
 * The bot file id is a playback handle that changes between sessions, so it is never the identity
 * and never stored here. No artwork, token or bitmap is kept either; the owner supplies display
 * data.
 */
internal data class PlaybackBookmark(
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
) {
    /** A saved spot the user can be offered to continue from. */
    val resumable: Boolean get() = !completed && positionMs > 0L
}

/**
 * Bookmark bookkeeping. A bookmark is only marked complete when playback actually reached the end;
 * there is deliberately no "almost done" threshold.
 */
internal object PlaybackBookmarkPolicy {
    fun record(
        existing: PlaybackBookmark?,
        key: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): PlaybackBookmark? {
        if (key.isBlank()) return existing
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (ended) return PlaybackBookmark(key, safePosition, safeDuration, completed = true)
        // Completion belongs to the finished playback only. Any later position that is not the end
        // is a new, unfinished playback, so it is recorded as incomplete. A position left at the
        // end of a completed item stays completed, so an end-of-track stop does not re-ask.
        val completed = existing?.key == key && existing.completed && safePosition >= safeDuration
        return PlaybackBookmark(key, safePosition, safeDuration, completed)
    }

    /** Starting over clears the completed flag and the saved spot. */
    fun restarted(key: String, durationMs: Long): PlaybackBookmark? =
        if (key.isBlank()) null else PlaybackBookmark(key, 0L, durationMs.coerceAtLeast(0L), completed = false)

    /**
     * Whether an explicit play should ask the user where to start.
     *
     * [sameItemLoadedPaused] is only true for a live service session that the user paused. A
     * restored session that has no service behind it is not a continuation of live playback, so it
     * still asks even when it is the item on screen.
     */
    fun shouldOffer(
        bookmark: PlaybackBookmark?,
        sameItemLoadedPaused: Boolean,
    ): Boolean {
        if (bookmark == null || !bookmark.resumable) return false
        // Continuing the same item that is already loaded and paused needs no question.
        return !sameItemLoadedPaused
    }
}

internal object PlaybackBookmarkCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_BOOKMARKS = 10_000

    fun encode(bookmarks: Collection<PlaybackBookmark>): String {
        require(bookmarks.size <= MAX_BOOKMARKS)
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(bookmarks.size)
                bookmarks.forEach { bookmark ->
                    output.writeUTF(bookmark.key)
                    output.writeLong(bookmark.positionMs.coerceAtLeast(0L))
                    output.writeLong(bookmark.durationMs.coerceAtLeast(0L))
                    output.writeBoolean(bookmark.completed)
                }
            }
            buffer.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String): List<PlaybackBookmark>? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val count = input.readInt()
            require(count in 0..MAX_BOOKMARKS)
            List(count) {
                PlaybackBookmark(
                    key = input.readUTF(),
                    positionMs = input.readLong().coerceAtLeast(0L),
                    durationMs = input.readLong().coerceAtLeast(0L),
                    completed = input.readBoolean(),
                )
            }.also { require(input.available() == 0) }
        }
    }.getOrNull()

    /**
     * Migration from a queue snapshot that only knew about one paused item: that item adopts the
     * recorded pause point, with no completion claim. Anything else keeps its saved bookmark.
     */
    fun seedFromSnapshot(
        existing: Map<String, PlaybackBookmark>,
        pausedKey: String?,
        pausedPositionMs: Long,
        pausedDurationMs: Long,
    ): Map<String, PlaybackBookmark> {
        if (pausedKey.isNullOrBlank() || pausedPositionMs <= 0L) return existing
        if (existing.containsKey(pausedKey)) return existing
        val seeded = PlaybackBookmark(
            key = pausedKey,
            positionMs = pausedPositionMs.coerceAtLeast(0L),
            durationMs = pausedDurationMs.coerceAtLeast(0L),
            completed = false,
        )
        return existing + (pausedKey to seeded)
    }
}

/**
 * The resume bookkeeping without Android dependencies: identity, the saved position, completion,
 * and the explicit-play question. The controller owns one instance and persists it through
 * [encodeWith]; tests drive this same logic instead of a UI constructor.
 */
internal class PlaybackBookmarkSession(initial: Map<String, PlaybackBookmark> = emptyMap()) {
    private val entries = LinkedHashMap<String, PlaybackBookmark>(initial)
    // Deleted rows stay out even if a delayed playback state still names them. Re-adding the row
    // explicitly clears its tombstone, so the item can be bookmarked again.
    private val deleted = mutableSetOf<String>()

    fun encodeWith(): String = PlaybackBookmarkCodec.encode(entries.values.toList())

    fun snapshot(): Map<String, PlaybackBookmark> = entries.toMap()

    fun of(key: String): PlaybackBookmark? = entries[key]

    /** An explicit play of the row again clears its tombstone so it can be bookmarked once more. */
    fun revive(key: String) {
        deleted.remove(key)
    }

    /**
     * Records one observed playhead sample; returns true when the stored value changed. A key that
     * was explicitly deleted is ignored until it is followed by an explicit play again.
     */
    fun record(key: String, positionMs: Long, durationMs: Long, ended: Boolean): Boolean {
        if (key.isBlank() || key in deleted) return false
        val next = PlaybackBookmarkPolicy.record(entries[key], key, positionMs, durationMs, ended) ?: return false
        if (entries[key] == next) return false
        entries[key] = next
        return true
    }

    /** Starting over forgets the saved spot so the next play does not ask again. */
    fun restart(key: String, durationMs: Long) {
        val next = PlaybackBookmarkPolicy.restarted(key, durationMs)
        if (next == null || key in deleted) return
        entries[key] = next
    }

    /** Explicit deletion or real media deletion is the only thing that removes an entry. */
    fun purge(keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return false
        var changed = false
        keys.forEach { key ->
            if (entries.remove(key) != null) changed = true
            if (deleted.add(key)) changed = true
        }
        return changed
    }

    /**
     * Whether this explicit play must ask where to start. [sameLiveItemPaused] is only true for a
     * live service pause; a restored session without a service still asks.
     */
    fun asks(key: String, sameLiveItemPaused: Boolean): Boolean {
        if (key in deleted) return false
        return PlaybackBookmarkPolicy.shouldOffer(entries[key], sameLiveItemPaused)
    }
}

/** Persists per-item resume points. It never starts playback and never deletes on a player close. */
internal class PlaybackBookmarkStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): Map<String, PlaybackBookmark> = preferences.getString(BOOKMARK_KEY, null)
        ?.let(PlaybackBookmarkCodec::decode)
        ?.associateBy { it.key }
        .orEmpty()

    fun write(bookmarks: Map<String, PlaybackBookmark>) {
        preferences.edit().run {
            if (bookmarks.isEmpty()) remove(BOOKMARK_KEY)
            else putString(BOOKMARK_KEY, PlaybackBookmarkCodec.encode(bookmarks.values.toList()))
        }.apply()
    }

    /** Only real media deletion purges a bookmark. */
    fun purge(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = read()
        val remaining = current.filterKeys { it !in keys }
        if (remaining.size != current.size) write(remaining)
    }

    private companion object {
        const val FILE_NAME = "playback_bookmarks"
        const val BOOKMARK_KEY = "bookmarks_v1"
    }
}

/** Display text for the resume prompt, e.g. `1:23` or `1:02:03`. */
internal fun bookmarkPositionLabel(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** Whether a bookmark belongs to a queue row of the given kind; used to validate a pending choice. */
internal fun PlaybackBookmark.matchesSource(source: PlaybackSource): Boolean = when (source) {
    PlaybackSource.LOCAL_AUDIO -> key.startsWith("local:")
    PlaybackSource.BOT -> key.startsWith("bot:")
    PlaybackSource.NONE -> false
}
