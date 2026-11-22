package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending Bot play lifecycle: the real engine the controller uses, driven through its production
 * decisions (request, observe, expire, cancel). No copied policy and no Android dependency.
 */
class BotReconnectPolicyTest {
    private var clock = 1_000L
    private val engine = BotPendingPlaybackEngine(nowMs = { clock })

    private fun observe(
        ready: Boolean = true,
        connected: Boolean = true,
        hasClient: Boolean = true,
        busy: Boolean = false,
        setupComplete: Boolean = true,
        paired: Boolean = true,
        loaded: Boolean = true,
        videos: List<PendingVideoRow> = listOf(PendingVideoRow(7L, 42L)),
    ) = engine.observe(ready, connected, hasClient, busy, setupComplete, paired, loaded, videos)

    private fun request(messageId: Long = 42L, positionMs: Long = 5_000L) =
        engine.request("bot:7:$messageId", 7L, messageId, positionMs)

    private fun video(fileId: Int = 31, messageId: Long = 42L, needsForward: Boolean = false) =
        BotVideo(fileId, 7L, messageId, "v$messageId", 1L, 60, 1L, needsForward = needsForward)

    // --- policy -----------------------------------------------------------------------------------

    private fun block(
        ready: Boolean = true,
        connected: Boolean = true,
        hasClient: Boolean = true,
        reconnecting: Boolean = false,
        busy: Boolean = false,
        setupComplete: Boolean = true,
        paired: Boolean = true,
    ) = BotReconnectPolicy.block(ready, connected, hasClient, reconnecting, busy, setupComplete, paired)

    @Test fun aConnectedSessionPlaysImmediately() = assertEquals(BotPlaybackBlock.NONE, block())

    @Test fun authenticatedButOfflineIsStillRecoverable() =
        assertEquals(BotPlaybackBlock.NETWORK_NOT_READY, block(connected = false))

    @Test fun aMissingClientIsRecoverable() =
        assertEquals(BotPlaybackBlock.NOT_CONNECTED, block(ready = false, connected = false, hasClient = false))

    @Test fun setupProblemsNeverReconnect() {
        assertEquals(BotPlaybackBlock.SETUP_REQUIRED, block(setupComplete = false, connected = false))
        assertFalse(BotReconnectPolicy.canWait(BotPlaybackBlock.SETUP_REQUIRED))
    }

    // Busy must not shadow a running authentication, or an explicit play is refused during join.
    @Test fun aRunningAttemptJoinsEvenWhileBusy() =
        assertEquals(BotPlaybackBlock.ALREADY_RECONNECTING, block(connected = false, reconnecting = true, busy = true))

    // --- lifecycle --------------------------------------------------------------------------------

    // The core path: it waits while offline, then starts once at the saved position on ready.
    @Test fun waitsThenPlaysOnceAtTheSavedPositionAfterReady() {
        request(positionMs = 5_000L)
        assertTrue(engine.isPending)
        assertEquals(BotPendingOutcome.Wait, observe(connected = false, ready = false))
        val outcome = observe()
        assertTrue(outcome is BotPendingOutcome.Play)
        assertEquals(5_000L, (outcome as BotPendingOutcome.Play).target.positionMs)
        assertFalse("a second observation must not replay", engine.isPending)
        assertEquals(BotPendingOutcome.Wait, observe())
    }

    // A repeat tap on the same item joins it: one request, refreshed playhead, no second reconnect.
    @Test fun aRepeatTapOnTheSameItemOnlyRefreshesItsPosition() {
        val first = request(positionMs = 1_000L)
        assertTrue(engine.isPendingItem(7L, 42L))
        val second = request(positionMs = 9_000L)
        assertEquals(first.generation + 1L, second.generation)
        val outcome = observe()
        assertEquals(9_000L, (outcome as BotPendingOutcome.Play).target.positionMs)
    }

    // A different item replaces the target: the wrong item must never play.
    @Test fun aDifferentItemReplacesThePendingTarget() {
        request(messageId = 42L, positionMs = 5_000L)
        request(messageId = 99L, positionMs = 0L)
        assertFalse(engine.isPendingItem(7L, 42L))
        val outcome = observe(videos = listOf(PendingVideoRow(7L, 42L), PendingVideoRow(7L, 99L)))
        assertEquals(99L, (outcome as BotPendingOutcome.Play).target.messageId)
        assertEquals(0L, outcome.target.positionMs)
    }

    // A ready that arrives after the bound must not autoplay; the wait already ended.
    @Test fun aLateReadyAfterTimeoutNeverAutoplays() {
        val pending = request()
        clock += BotPendingPlaybackEngine.DEFAULT_TIMEOUT_MS
        assertEquals(BotPendingOutcome.Fail(BotPendingPlaybackEngine.TIMED_OUT), engine.expire(pending.generation))
        assertFalse(engine.isPending)
        // Even a fully usable connection cannot revive it.
        assertEquals(BotPendingOutcome.Wait, observe())
    }

    // The observe path checks the deadline before the ready branch, so it cannot start late either.
    @Test fun observesDeadlineBeforePlaying() {
        request()
        clock += BotPendingPlaybackEngine.DEFAULT_TIMEOUT_MS
        assertEquals(BotPendingOutcome.Fail(BotPendingPlaybackEngine.TIMED_OUT), observe())
    }

    // A retry after a timeout starts a fresh wait that can still succeed.
    @Test fun retryAfterTimeoutCanSucceed() {
        request()
        clock += BotPendingPlaybackEngine.DEFAULT_TIMEOUT_MS
        assertTrue(observe() is BotPendingOutcome.Fail)
        request()
        assertTrue(observe() is BotPendingOutcome.Play)
    }

    // Expiring a superseded generation must not kill the newer request.
    @Test fun aStaleExpireDoesNotCancelANewerRequest() {
        val first = request(messageId = 42L)
        request(messageId = 99L)
        assertEquals(BotPendingOutcome.Wait, engine.expire(first.generation))
        assertTrue(engine.isPending)
    }

    // Cancel is what prevents the delayed autoplay after stop/pause/delete/local switch.
    @Test fun cancelBeforeReadyNeverPlays() {
        request()
        engine.cancel()
        assertFalse(engine.isPending)
        assertEquals(BotPendingOutcome.Wait, observe())
    }

    // --- observation sources ----------------------------------------------------------------------

    // The list arriving late must still settle instead of leaving a spinner forever.
    @Test fun aLateListIsObserved() {
        request(messageId = 42L)
        assertEquals(BotPendingOutcome.Wait, observe(loaded = false, videos = emptyList()))
        // Not final yet, so the missing row is not treated as a deletion.
        assertEquals(BotPendingOutcome.Wait, observe(loaded = false, videos = emptyList()))
        assertEquals(BotPendingOutcome.Fail(BotPendingPlaybackEngine.MISSING_ITEM), observe(loaded = true, videos = emptyList()))
        assertFalse(engine.isPending)
    }

    @Test fun aDeletedItemFailsOnceTheListIsFinal() {
        request(messageId = 42L)
        assertEquals(BotPendingOutcome.Fail(BotPendingPlaybackEngine.MISSING_ITEM),
            observe(videos = listOf(PendingVideoRow(7L, 99L))))
    }

    @Test fun aVanishedSetupFails() {
        request()
        assertEquals(BotPendingOutcome.Fail(BotPendingPlaybackEngine.SETUP_GONE), observe(setupComplete = false))
        assertFalse(engine.isPending)
    }

    // A still-busy runtime keeps waiting rather than failing, since a transfer may finish in time.
    @Test fun busyKeepsWaiting() {
        request()
        assertEquals(BotPendingOutcome.Wait, observe(busy = true))
        assertTrue(engine.isPending)
    }

    // A file id reissued by the reconnect must not forfeit a still-valid row.
    @Test fun aReissuedFileIdStillPlays() {
        request(messageId = 42L)
        assertTrue(observe(videos = listOf(PendingVideoRow(7L, 42L))) is BotPendingOutcome.Play)
    }

    // --- identity resolution ----------------------------------------------------------------------

    // The core fix: a reconnect changes the file id, so the message identity must still find the row.
    @Test fun theTargetIsFoundByMessageAfterTheFileIdChanges() {
        val row = QueueItemUi(
            key = "bot:7:42", title = "v42", sourceKind = PlaybackSource.BOT,
            botChatId = 7L, botMessageId = 42L, botFileId = 31,
        )
        assertEquals(999, resolvedBotRetryTarget(listOf(video(fileId = 999)), row, botFileId = 31)?.fileId)
    }

    @Test fun aRowWithoutIdentityFallsBackToAMatchingFileId() {
        val row = QueueItemUi(
            key = "bot:31", title = "v42", sourceKind = PlaybackSource.BOT, botFileId = 31,
        )
        assertEquals(31, resolvedBotRetryTarget(listOf(video(fileId = 31)), row, botFileId = 31)?.fileId)
    }

    @Test fun aForwardRequiredVideoIsNotATarget() {
        assertNull(botVideoForRow(listOf(video(needsForward = true)), chatId = 7L, messageId = 42L))
        assertNull(resolvedBotRetryTarget(listOf(video(needsForward = true)), row = null, botFileId = 31))
    }

    @Test fun pendingRowsSkipForwardRequiredVideos() {
        val rows = pendingVideoRows(listOf(video(needsForward = true), video(messageId = 43L)))
        assertEquals(1, rows.size)
        assertEquals(43L, rows.single().messageId)
    }
}
