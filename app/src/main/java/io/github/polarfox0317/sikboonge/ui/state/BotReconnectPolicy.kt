package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.BotVideo

/**
 * Why an explicit Bot playback request cannot start, and whether reconnecting can repair it.
 *
 * The cases are distinct on purpose: Telegram can be authenticated while its network is down, the
 * client may never have been built (a reconnect from saved settings can help), or an attempt is
 * already running and must be joined. Setup problems never trigger a reconnect.
 */
internal enum class BotPlaybackBlock {
    /** Authenticated and connected: play now. */
    NONE,

    /** Authenticated, but the Telegram network connection is not ready. */
    NETWORK_NOT_READY,

    /** No usable client; a reconnect from saved settings may repair it. */
    NOT_CONNECTED,

    /** A connection attempt is already running and this request can join it. */
    ALREADY_RECONNECTING,

    /** Credentials or pairing are missing, so reconnecting cannot help. */
    SETUP_REQUIRED,

    /** A download/move is running; playback will not start mid-transfer. */
    BUSY,
}

internal object BotReconnectPolicy {
    /**
     * [ready] is TDLib authentication, which survives a network drop; [connected] is the network
     * connection itself. A running attempt is checked before busy, so an explicit play that arrives
     * while authentication is still starting joins it instead of being refused.
     */
    fun block(
        ready: Boolean,
        connected: Boolean,
        hasClient: Boolean,
        reconnecting: Boolean,
        busy: Boolean,
        setupComplete: Boolean,
        paired: Boolean,
    ): BotPlaybackBlock = when {
        !setupComplete || !paired -> BotPlaybackBlock.SETUP_REQUIRED
        reconnecting -> BotPlaybackBlock.ALREADY_RECONNECTING
        hasClient && ready && connected -> BotPlaybackBlock.NONE
        busy -> BotPlaybackBlock.BUSY
        hasClient && ready -> BotPlaybackBlock.NETWORK_NOT_READY
        else -> BotPlaybackBlock.NOT_CONNECTED
    }

    /** Whether a pending play may wait on this state instead of failing immediately. */
    fun canWait(block: BotPlaybackBlock): Boolean = when (block) {
        BotPlaybackBlock.ALREADY_RECONNECTING,
        BotPlaybackBlock.NETWORK_NOT_READY,
        BotPlaybackBlock.NOT_CONNECTED,
        BotPlaybackBlock.BUSY,
        -> true
        else -> false
    }
}

/** What the owner of a pending Bot play must do after [BotPendingPlaybackEngine.observe]. */
internal sealed interface BotPendingOutcome {
    /** Keep waiting. */
    data object Wait : BotPendingOutcome

    /** Telegram is usable: start this play exactly once; the pending is now cleared. */
    data class Play(val target: PendingBotPlayback) : BotPendingOutcome

    /** Stop waiting, show [message], and let the user tap retry again. */
    data class Fail(val message: String) : BotPendingOutcome
}

/**
 * The Bot playback the user asked for while Telegram was disconnected.
 *
 * It stores the stable identity (chat + message) rather than a file id, because a reconnect can
 * reissue the file id for the same message. [generation] grows with every request and cancel, so a
 * stale completion can never start playback after the user switched, cancelled, or deleted.
 */
internal data class PendingBotPlayback(
    val queueKey: String,
    val chatId: Long,
    val messageId: Long,
    val positionMs: Long,
    val generation: Long,
    /** The queue occurrence this play belongs to, so deleting another copy does not cancel it. */
    val entryId: String? = null,
)

/** The minimum a pending play needs to know about a listed video. */
internal data class PendingVideoRow(
    val chatId: Long,
    val messageId: Long,
)

/**
 * Owns at most one pending Bot play and decides when it starts or fails.
 *
 * Time is injected so the bounded wait is exercised without a real clock. The engine has no Android
 * or TDLib dependency: the controller feeds it connection observations and it answers with an
 * outcome, which makes the whole pending lifecycle testable.
 */
internal class BotPendingPlaybackEngine(
    private val nowMs: () -> Long,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private var pending: PendingBotPlayback? = null
    private var deadlineMs = 0L
    private var generation = 0L
    private var listSettled = false

    val current: PendingBotPlayback? get() = pending

    /** Whether a play is waiting, so the controller can tell a join from a new request. */
    val isPending: Boolean get() = pending != null

    /** The generation of the current wait, captured by the controller's timeout job. */
    val currentGeneration: Long get() = pending?.generation ?: 0L

    /**
     * Records an explicit request. A different item replaces the pending one and takes its own
     * position and identity; the same item only refreshes the playhead. Both issue a new generation
     * and restart the bounded wait, so an older completion cannot start the wrong item.
     */
    fun request(queueKey: String, chatId: Long, messageId: Long, positionMs: Long, entryId: String? = null): PendingBotPlayback {
        generation += 1L
        val sameItem = isPendingItem(chatId, messageId)
        if (!sameItem) listSettled = false
        val next = PendingBotPlayback(queueKey, chatId, messageId, positionMs, generation, entryId)
        pending = next
        deadlineMs = nowMs() + timeoutMs
        return next
    }

    /** Whether [chatId]/[messageId] is already the pending play, so a repeat tap must not restart it. */
    fun isPendingItem(chatId: Long, messageId: Long): Boolean =
        pending?.let { it.chatId == chatId && it.messageId == messageId } == true

    /** Drops the pending play: the user stopped, deleted, switched to local, or signed out. */
    fun cancel() {
        if (pending == null) return
        pending = null
        generation += 1L
    }

    /**
     * Ends the wait once its bound has passed. The deadline is checked before any ready branch, so a
     * connection that arrives after the bound can never start playback on its own.
     */
    fun expire(generation: Long): BotPendingOutcome {
        val target = pending ?: return BotPendingOutcome.Wait
        if (target.generation != generation) return BotPendingOutcome.Wait
        return end(target, TIMED_OUT)
    }

    /**
     * Decides the pending play's fate from one observation.
     *
     * The deadline is checked first, then a usable connection plays once. A missing row fails only
     * after the list became final, and a vanished setup fails at once, so the wait always ends.
     */
    fun observe(
        ready: Boolean,
        connected: Boolean,
        hasClient: Boolean,
        busy: Boolean,
        setupComplete: Boolean,
        paired: Boolean,
        initialStateLoaded: Boolean,
        videos: List<PendingVideoRow>,
    ): BotPendingOutcome {
        val target = pending ?: return BotPendingOutcome.Wait
        if (initialStateLoaded) listSettled = true

        // Checked before everything else: a late connection must not revive an expired wait.
        if (nowMs() >= deadlineMs) return end(target, TIMED_OUT)
        if (!setupComplete || !paired) return end(target, SETUP_GONE)

        if (hasClient && ready && connected && !busy) {
            if (videos.none { it.chatId == target.chatId && it.messageId == target.messageId }) {
                // The list is final and no longer holds the item: a deletion must not autoplay.
                return if (listSettled) end(target, MISSING_ITEM) else BotPendingOutcome.Wait
            }
            pending = null
            generation += 1L
            return BotPendingOutcome.Play(target)
        }
        return BotPendingOutcome.Wait
    }

    private fun end(target: PendingBotPlayback, message: String): BotPendingOutcome {
        if (pending?.generation != target.generation) return BotPendingOutcome.Wait
        pending = null
        generation += 1L
        return BotPendingOutcome.Fail(message)
    }

    internal companion object {
        /** Matches the runtime's reconnect bound; the wait never outlives one reconnect attempt. */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        const val TIMED_OUT = "재생을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."
        const val SETUP_GONE = "재생할 수 없어요. 봇 설정을 확인해 주세요."
        const val MISSING_ITEM = "항목을 찾을 수 없어요. 목록에서 다시 선택해 주세요."
    }
}

/** Adapts listed videos into the pending engine's view, excluding rows that still need forwarding. */
internal fun pendingVideoRows(videos: List<BotVideo>): List<PendingVideoRow> =
    videos.filter { !it.needsForward }.map { PendingVideoRow(it.chatId, it.messageId) }

/**
 * Finds the row a retry should use: the stable message identity first, then the file id only when it
 * still matches, because a reconnect may have replaced the file id.
 */
internal fun resolvedBotRetryTarget(videos: List<BotVideo>, row: QueueItemUi?, botFileId: Int?): BotVideo? {
    botVideoForRow(videos, row?.botChatId, row?.botMessageId)?.let { return it }
    return botFileId?.let { fileId -> videos.firstOrNull { it.fileId == fileId && !it.needsForward } }
}

/** A listed video for this message, regardless of which file id a reconnect assigned it. */
internal fun botVideoForRow(videos: List<BotVideo>, chatId: Long?, messageId: Long?): BotVideo? {
    if (chatId == null || messageId == null) return null
    return videos.firstOrNull { it.chatId == chatId && it.messageId == messageId && !it.needsForward }
}
