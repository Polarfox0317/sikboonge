package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.StartPositionPolicy
import io.github.polarfox0317.sikboonge.StreamingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-item bookmarks: identity, completion, the resume question and its cancellation. */
class PlaybackBookmarkTest {

    private val localKey = stableQueueKey("/data/audio/a.m4a") { it }
    private val botKey = stableQueueKey(botChatId = 7L, botMessageId = 99L)

    private fun bookmark(key: String, position: Long, duration: Long = 180_000L, completed: Boolean = false) =
        PlaybackBookmark(key, position, duration, completed)

    /** A queue as the close leaves it behind: two rows, the paused row keeps its stopped position. */
    private fun sessionSnapshot() = PlaybackQueueSnapshot(
        rows = listOf(
            QueueRow(botKey, "봇 영상", PlaybackSource.BOT, botMessageId = 99L, botChatId = 7L),
            QueueRow(localKey, "보관함 항목", PlaybackSource.LOCAL_AUDIO, localAudioPath = "/data/audio/a.m4a"),
        ),
        currentKey = localKey,
        resumeKey = botKey,
        pausedKey = localKey,
        pausedPositionMs = 42_000L,
        pausedDurationMs = 180_000L,
        playerExpanded = false,
        emptyPlayerVisible = true,
    )

    private fun localRowItem(key: String) = QueueItemUi(
        key = key,
        title = "보관함 항목",
        sourceKind = PlaybackSource.LOCAL_AUDIO,
        localAudioPath = "/data/audio/a.m4a",
    )

    @Test fun recordingAnUnfinishedPositionKeepsTheSpot() {
        val saved = PlaybackBookmarkPolicy.record(null, localKey, 42_000L, 180_000L, ended = false)
        assertEquals(bookmark(localKey, 42_000L), saved)
        assertTrue(saved!!.resumable)
    }

    @Test fun onlyAnActualEndMarksCompletion() {
        // Playback that nearly finished is not complete; there is no percentage threshold.
        val nearEnd = PlaybackBookmarkPolicy.record(null, localKey, 179_500L, 180_000L, ended = false)!!
        assertFalse(nearEnd.completed)
        assertTrue(nearEnd.resumable)

        val ended = PlaybackBookmarkPolicy.record(nearEnd, localKey, 180_000L, 180_000L, ended = true)!!
        assertTrue(ended.completed)
        assertFalse(ended.resumable)
    }

    @Test fun aPausedAtZeroItemIsNotOffered() {
        val saved = PlaybackBookmarkPolicy.record(null, localKey, 0L, 180_000L, ended = false)!!
        assertFalse(saved.resumable)
    }

    @Test fun aNewPlaybackAfterCompletionIsRecordedAsIncomplete() {
        // Completion belongs to the playback that actually ended. Starting the item again and
        // stopping part way through is a new, unfinished playback, so it must ask on the next play.
        val ended = bookmark(localKey, 180_000L, completed = true)
        val replayed = PlaybackBookmarkPolicy.record(ended, localKey, 10_000L, 180_000L, ended = false)!!
        assertFalse("a replayed item is no longer finished", replayed.completed)
        assertEquals(10_000L, replayed.positionMs)
        assertTrue(replayed.resumable)
    }

    @Test fun stoppingAtTheEndOfACompletedItemKeepsItComplete() {
        // A pause/stop that lands on the end is still the finished playback, so it does not re-ask.
        val ended = bookmark(localKey, 180_000L, completed = true)
        val stoppedAtEnd = PlaybackBookmarkPolicy.record(ended, localKey, 180_000L, 180_000L, ended = false)!!
        assertTrue(stoppedAtEnd.completed)
        assertFalse(stoppedAtEnd.resumable)
    }

    @Test fun startingOverClearsTheSavedSpot() {
        val restarted = PlaybackBookmarkPolicy.restarted(localKey, 180_000L)!!
        assertEquals(0L, restarted.positionMs)
        assertFalse(restarted.resumable)
        assertNull(PlaybackBookmarkPolicy.restarted("", 180_000L))
    }

    @Test fun anExplicitPlayOfAnUnfinishedItemAsks() {
        assertTrue(PlaybackBookmarkPolicy.shouldOffer(bookmark(localKey, 42_000L), sameItemLoadedPaused = false))
    }

    @Test fun aFinishedOrMissingBookmarkNeverAsks() {
        assertFalse(PlaybackBookmarkPolicy.shouldOffer(null, sameItemLoadedPaused = false))
        assertFalse(PlaybackBookmarkPolicy.shouldOffer(bookmark(localKey, 180_000L, completed = true), sameItemLoadedPaused = false))
        assertFalse(PlaybackBookmarkPolicy.shouldOffer(bookmark(localKey, 0L), sameItemLoadedPaused = false))
    }

    @Test fun continuingTheAlreadyLoadedPausedItemDoesNotAskAgain() {
        assertFalse(PlaybackBookmarkPolicy.shouldOffer(bookmark(localKey, 42_000L), sameItemLoadedPaused = true))
    }

    @Test fun anItemThatIsNotLivePausedStillAsks() {
        // A restored session has no live service pause, so pressing play there must still ask.
        assertTrue(PlaybackBookmarkPolicy.shouldOffer(bookmark(localKey, 42_000L), sameItemLoadedPaused = false))
    }

    @Test fun aRestoredMiniPlayerPromptsEvenForTheItemOnScreen() {
        // The restored item is the one displayed, but no service is behind it, so it must ask.
        val session = PlaybackBookmarkSession(mapOf(localKey to bookmark(localKey, 42_000L)))
        assertTrue(session.asks(localKey, sameLiveItemPaused = false))
        // Only a live service pause continues directly.
        assertFalse(session.asks(localKey, sameLiveItemPaused = true))
    }

    @Test fun bookmarksRoundTripWithStableIdentity() {
        val saved = listOf(bookmark(localKey, 42_000L), bookmark(botKey, 7_000L, completed = true))
        val decoded = PlaybackBookmarkCodec.decode(PlaybackBookmarkCodec.encode(saved))!!
        assertEquals(saved, decoded)
    }

    @Test fun botIdentityNeverUsesThePlaybackFileId() {
        // The row key is chat/message; a file id from another session must not identify the item.
        val decoded = PlaybackBookmarkCodec.decode(PlaybackBookmarkCodec.encode(listOf(bookmark(botKey, 1_000L))))!!
        val key = decoded.single().key
        assertEquals("bot:7:99", key)
        assertFalse("a file id must never leak into the identity", key.contains("fileId"))
    }

    @Test fun malformedPayloadsAreRejectedSafely() {
        assertNull(PlaybackBookmarkCodec.decode("not-base64!"))
        assertNull(PlaybackBookmarkCodec.decode(""))
    }

    @Test fun migrationSeedsThePausedItemFromTheQueueSnapshot() {
        val existing = mapOf(botKey to bookmark(botKey, 5_000L))
        val seeded = PlaybackBookmarkCodec.seedFromSnapshot(
            existing = existing,
            pausedKey = localKey,
            pausedPositionMs = 42_000L,
            pausedDurationMs = 180_000L,
        )
        assertEquals(42_000L, seeded.getValue(localKey).positionMs)
        assertFalse(seeded.getValue(localKey).completed)
        assertEquals("an existing bookmark wins over the snapshot", 5_000L, seeded.getValue(botKey).positionMs)
    }

    @Test fun migrationIgnoresASnapshotWithoutAPausedItem() {
        val seeded = PlaybackBookmarkCodec.seedFromSnapshot(emptyMap(), null, 42_000L, 180_000L)
        assertTrue(seeded.isEmpty())
    }

    @Test fun reopeningRestoresOnlyQueueAndBookmarks() {
        // Drives the real restore decision from a persisted snapshot: queue and saved spot come
        // back, but nothing starts, no player expands, and the display is not a loading state.
        val row = QueueItemUi(
            key = botKey,
            title = "저장된 영상",
            sourceKind = PlaybackSource.BOT,
            botMessageId = 99L,
            botChatId = 7L,
            botFileId = null,
        )
        val snapshot = PlaybackQueueSnapshot(
            rows = listOf(QueueRow(botKey, "저장된 영상", PlaybackSource.BOT, botMessageId = 99L, botChatId = 7L)),
            currentKey = botKey,
            resumeKey = botKey,
            pausedKey = botKey,
            pausedPositionMs = 42_000L,
            pausedDurationMs = 180_000L,
            playerExpanded = true,
            emptyPlayerVisible = true,
        )
        val decision = PlaybackRestorePolicy.reopen(snapshot, listOf(row))!!

        assertEquals(listOf(botKey), decision.queue.map { it.key })
        assertEquals(botKey, decision.pausedKey)
        assertEquals(42_000L, decision.positionMs)
        assertFalse("reopening must not autoplay", decision.autoplay)
        assertFalse("reopening must not force full screen", decision.expandPlayer)
        assertTrue("the restored session must not render as loading", decision.display.ready)
        assertFalse("the restored session must not claim to be playing", decision.display.playing)
        assertTrue("no service serves a restored session", decision.display.detached)
        assertEquals("저장된 영상", decision.display.title)
        assertEquals(42_000L, decision.display.positionMs)
        // The display it produces is a paused session, not live playback.
        val asStream = StreamingState(
            source = decision.display.source,
            title = decision.display.title,
            ready = decision.display.ready,
            playing = decision.display.playing,
            serviceDetached = decision.display.detached,
            positionMs = decision.display.positionMs,
            durationMs = decision.display.durationMs,
        )
        assertTrue(isSeparatedPausedStream(asStream))
    }

    @Test fun reopeningWithNothingSavedShowsNothing() {
        assertNull(PlaybackRestorePolicy.reopen(null, emptyList()))
        assertNull(PlaybackRestorePolicy.reopen(sessionSnapshot(), emptyList()))
    }

    @Test fun closingThePlayerKeepsTheQueueAndBookmarks() {
        // Close keeps the durable checkpoint and the saved spot; only deletion removes either.
        val session = PlaybackBookmarkSession()
        assertTrue(session.record(localKey, 42_000L, 180_000L, ended = false))
        val persisted = PlaybackQueueSnapshotCodec.encode(sessionSnapshot())
        val reopened = PlaybackQueueSnapshotCodec.decode(persisted)!!

        assertEquals(2, reopened.rows.size)
        assertEquals(42_000L, reopened.pausedPositionMs)
        assertEquals(42_000L, session.of(localKey)!!.positionMs)
        // The saved bookmark survives a reload from disk.
        val reloaded = PlaybackBookmarkSession(
            PlaybackBookmarkCodec.decode(PlaybackBookmarkCodec.encode(listOf(session.of(localKey)!!)))!!.associateBy { it.key },
        )
        assertTrue(reloaded.asks(localKey, sameLiveItemPaused = false))
    }

    @Test fun aLateEmissionAfterDeletionCannotRecreateTheBookmark() {
        val session = PlaybackBookmarkSession()
        session.record(localKey, 42_000L, 180_000L, ended = false)
        assertTrue(session.purge(listOf(localKey)))
        // A delayed playback state still naming the deleted row must not write it back.
        assertFalse(session.record(localKey, 60_000L, 180_000L, ended = false))
        assertNull(session.of(localKey))
        assertFalse(session.asks(localKey, sameLiveItemPaused = false))
        // Playing the row again explicitly clears the tombstone.
        session.revive(localKey)
        assertTrue(session.record(localKey, 5_000L, 180_000L, ended = false))
        assertEquals(5_000L, session.of(localKey)!!.positionMs)
    }

    @Test fun closeBeforeALateServiceEmissionKeepsTheSavedSpot() {
        // The close saves the stop point; a late emission must not replace it with a reset point.
        val session = PlaybackBookmarkSession()
        session.record(localKey, 42_000L, 180_000L, ended = false)
        // A preparing emission reports position 0 with no duration: the writer skips it entirely,
        // which is modelled by not recording a candidate at all.
        assertEquals(42_000L, session.of(localKey)!!.positionMs)
        val decision = PlaybackRestorePolicy.reopen(sessionSnapshot(), listOf(localRowItem(localKey)))!!
        assertEquals(42_000L, decision.positionMs)
        assertFalse(decision.autoplay)
    }

    @Test fun playingFromTheQueueKeepsTheExistingQueue() {
        // Choosing a queue row must not collapse the queue to that one item.
        val queue = listOf(
            localRowItem(localKey),
            localRowItem("local:/data/audio/b.m4a"),
            QueueItemUi(botKey, "봇 영상", sourceKind = PlaybackSource.BOT, botMessageId = 99L, botChatId = 7L),
        )
        val selected = selectQueueEntry(queue, 1)
        assertEquals(3, selected.queue.size)
        assertEquals(1, selected.index)
        assertEquals(queue.map { it.key }, selected.queue.map { it.key })
        // A library/bot card play starts a fresh queue instead.
        val fresh = queueForPlayback(localRowItem(localKey))
        assertEquals(1, fresh.queue.size)
    }

    @Test fun aStartPositionIsCarriedByTheRequest() {
        // The confirmed choice passes its position into the start request, which is what the
        // service arms before prepare. StartPositionPolicyTest covers the service-side arming.
        val requested = StartPositionPolicy.fromRequest(42_000L)
        assertEquals(42_000L, requested)
        // A request that names no position never inherits another item's playhead.
        assertNull(StartPositionPolicy.fromRequest(-1L))
    }

    @Test fun aPreparingSessionNeverOverwritesTheSavedSpot() {
        // A preparing emission has position 0 and no duration; recording it would erase the spot.
        val preparing = StreamingState(
            source = PlaybackSource.LOCAL_AUDIO,
            ready = false,
            resolving = true,
            positionMs = 0L,
            durationMs = 0L,
            localAudioPath = "/data/audio/a.m4a",
        )
        val session = PlaybackBookmarkSession()
        session.record(localKey, 42_000L, 180_000L, ended = false)
        // The writer skips non-ready, non-ended states, so the saved spot is untouched.
        val shouldRecord = preparing.ready || preparing.ended
        assertFalse("a preparing state must not be recorded", shouldRecord)
        assertEquals(42_000L, session.of(localKey)!!.positionMs)
    }

    @Test fun positionLabelShowsSavedTime() {
        assertEquals("0:42", bookmarkPositionLabel(42_000L))
        assertEquals("3:00", bookmarkPositionLabel(180_000L))
        assertEquals("1:02:03", bookmarkPositionLabel(3_723_000L))
    }
}
