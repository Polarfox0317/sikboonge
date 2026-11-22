package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.StreamingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun botKey(messageId: Long, chatId: Long = 7L) = stableQueueKey(chatId, messageId)
private fun localKey(path: String) = "local:$path"

private fun botQueueItem(messageId: Long, chatId: Long = 7L) = QueueItemUi(
    key = botKey(messageId, chatId),
    title = "봇 영상 $messageId",
    sourceKind = PlaybackSource.BOT,
    botMessageId = messageId,
    botChatId = chatId,
    botFileId = (messageId * 10).toInt(),
)

private fun localQueueItem(path: String) = QueueItemUi(
    key = localKey(path),
    title = "보관함 $path",
    sourceKind = PlaybackSource.LOCAL_AUDIO,
    localAudioPath = path,
)

private fun pausedBotStream(positionMs: Long = 42_000L) = StreamingState(
    source = PlaybackSource.BOT,
    title = "봇 영상 2",
    ready = false,
    playing = false,
    buffering = false,
    positionMs = positionMs,
    durationMs = 180_000L,
    botFileId = null,
    localAudioPath = null,
    artworkPath = "/thumb/2.jpg",
    artworkBase64 = "QVJUV09SSw==",
)

class PlayerNavigationPolicyTest {
    @Test fun switchingTopLevelTabCollapsesExpandedPlayer() {
        assertTrue(shouldCollapsePlayerForTopLevelNavigation(
            currentDestination = Destination.Library,
            currentTopLevelDestination = Destination.Library,
            selectedDestination = Destination.Bot,
            playerExpanded = true,
        ))
    }

    @Test fun reselectingCurrentTabDoesNotChangePlayerSize() {
        assertFalse(shouldCollapsePlayerForTopLevelNavigation(
            currentDestination = Destination.Library,
            currentTopLevelDestination = Destination.Library,
            selectedDestination = Destination.Library,
            playerExpanded = true,
        ))
    }

    @Test fun openingAnotherTabFromSettingsPreservesHiddenPlayerSize() {
        assertFalse(shouldCollapsePlayerForTopLevelNavigation(
            currentDestination = Destination.Settings,
            currentTopLevelDestination = Destination.Library,
            selectedDestination = Destination.Bot,
            playerExpanded = true,
        ))
    }

    @Test fun alreadyCollapsedPlayerStaysCollapsed() {
        assertFalse(shouldCollapsePlayerForTopLevelNavigation(
            currentDestination = Destination.Library,
            currentTopLevelDestination = Destination.Library,
            selectedDestination = Destination.Bot,
            playerExpanded = false,
        ))
    }
}

class PlaybackSnapshotTest {
    // A BOT entry that lost its fileId/ready flag after the service process died is still a
    // resumable paused session; a bare source alone used to make the player disappear.
    @Test fun separatedPausedBotStreamIsDisplayedAsPausedEntry() {
        val stream = pausedBotStream()
        assertTrue(isSeparatedPausedStream(stream))
        val queue = listOf(botQueueItem(1), botQueueItem(2), botQueueItem(3))
        val state = AppUiState(
            stream = stream,
            queue = queue,
            queueIndex = -1,
            queueResumeIndex = 1,
            emptyPlayerVisible = false,
            pausedDisplay = stream,
            pausedEntryIndex = 1,
        )
        assertTrue("separated paused BOT state must keep the player visible", state.playerVisible)
        assertEquals(1, state.resumableQueueIndex)
        assertEquals(botKey(2), state.playerPreview?.key)
        assertEquals(stream, state.playerDisplayStream)
    }

    // A 0-second pause must count as paused; position is never the condition.
    @Test fun zeroSecondPauseStillDisplaysPausedEntry() {
        val stream = pausedBotStream(positionMs = 0L)
        assertTrue(isSeparatedPausedStream(stream))
        val state = AppUiState(
            stream = stream,
            queue = listOf(botQueueItem(1)),
            pausedDisplay = stream,
            pausedEntryIndex = 0,
        )
        assertTrue(state.playerVisible)
        assertEquals(0, state.resumableQueueIndex)
    }

    // Rendering selected (BOT fileId published, or a local path published while preparing) is live
    // playback, not a paused session. Presentation flags do not classify ownership.
    @Test fun activeOrPreparingStreamIsNotPaused() {
        val playing = StreamingState(source = PlaybackSource.BOT, title = "x", ready = true, playing = true, botFileId = 5)
        val localPreparing = StreamingState(source = PlaybackSource.LOCAL_AUDIO, title = "y", resolving = true, localAudioPath = "/data/audio/a.mp3")
        val botResolving = StreamingState(source = PlaybackSource.BOT, title = "z", resolving = true, botFileId = 9)
        val preparedDisplay = StreamingState(source = PlaybackSource.BOT, title = "w", ready = true, botFileId = null)
        assertFalse(isSeparatedPausedStream(playing))
        assertFalse(isSeparatedPausedStream(localPreparing))
        assertFalse(isSeparatedPausedStream(botResolving))
        // PlayerHost passes ready = true for a prepared paused display; it stays paused.
        assertTrue(isSeparatedPausedStream(preparedDisplay))
        assertFalse(isSeparatedPausedStream(StreamingState()))
    }

    // A stream with no queue entry cannot be resumed or displayed by the paused path.
    @Test fun pausedStreamWithoutResumableEntryIsNotVisible() {
        val stream = pausedBotStream()
        val state = AppUiState(stream = stream, queue = emptyList(), pausedDisplay = stream, pausedEntryIndex = -1)
        assertFalse(state.playerVisible)
        assertEquals(-1, state.resumableQueueIndex)
        assertNull(state.playerPreview)
    }

    // The explicit-close / empty session and an unrelated empty stream stay hidden.
    @Test fun emptyStreamStaysHiddenWithoutStoppedEntry() {
        val state = AppUiState(stream = StreamingState(), queue = emptyList())
        assertFalse(state.playerVisible)
    }

    // Resolving against a loaded bot list takes the live entry (so a title edit wins).
    @Test fun loadedBotRowUsesTheLiveEntry() {
        val saved = listOf(QueueRow(botKey(2), "이전 제목", PlaybackSource.BOT, botMessageId = 2L, botChatId = 7L, botFileId = 20, artworkPath = "/thumb/2.jpg", artworkBase64 = "QQ=="))
        val rows = resolveRestoreQueue(saved, ownerLoaded = { true }) { botQueueItem(2).copy(title = "새 제목") }
        val entries = rows.toQueueEntries()
        assertEquals(1, entries.size)
        assertEquals("새 제목", entries.single().title)
        assertFalse(rows.hasPendingRows())
    }

    // A loaded owner missing the row must NOT delete it; only an explicit delete may remove rows.
    @Test fun loadedOwnerWithoutRowKeepsTheSavedDescriptor() {
        val saved = listOf(QueueRow(botKey(9), "삭제됨", PlaybackSource.BOT, botMessageId = 9L, botChatId = 7L))
        val rows = resolveRestoreQueue(saved, ownerLoaded = { true }) { null }
        assertTrue(rows.single() is RestoredRow.Pending)
        // The descriptor survives, so a transient empty owner emission cannot drop the row.
        assertEquals(listOf(botKey(9)), rows.toQueueEntries().map { it.key })
    }

    // Before the bot inbox loads, the row is kept as its saved descriptor (not dropped).
    @Test fun unloadedBotRowStaysPendingWithSavedIdentity() {
        val saved = listOf(QueueRow(botKey(2), "봇 영상 2", PlaybackSource.BOT, botMessageId = 2L, botChatId = 7L, botFileId = 20, artworkBase64 = "QQ=="))
        val rows = resolveRestoreQueue(saved, ownerLoaded = { false }) { null }
        assertTrue(rows.hasPendingRows())
        val entry = rows.toQueueEntries().single()
        assertEquals(botKey(2), entry.key)
        assertEquals(20, entry.botFileId)
        assertEquals("QQ==", entry.artworkBase64)
    }

    // Mixed queue keeps the saved order, not the order in which owners happen to load.
    @Test fun mixedQueueKeepsSavedOrderWhileOwnersLoadAtDifferentTimes() {
        val saved = listOf(
            QueueRow(botKey(1), "봇 1", PlaybackSource.BOT, botMessageId = 1L, botChatId = 7L),
            QueueRow(localKey("/data/a.mp3"), "로컬 a", PlaybackSource.LOCAL_AUDIO, localAudioPath = "/data/a.mp3"),
            QueueRow(botKey(2), "봇 2", PlaybackSource.BOT, botMessageId = 2L, botChatId = 7L),
        )
        val libraryLoaded = true
        val botLoaded = false
        val observed = resolveRestoreQueue(saved, ownerLoaded = { it == PlaybackSource.LOCAL_AUDIO && libraryLoaded }) { row ->
            if (row.key == localKey("/data/a.mp3")) localQueueItem("/data/a.mp3") else null
        }.toQueueEntries()
        assertEquals(listOf(botKey(1), localKey("/data/a.mp3"), botKey(2)), observed.map { it.key })
    }

    // The resumed/current entry resolves by saved identity even when the live list never publishes a current index.
    @Test fun resumeIndexFollowsCurrentPausedRowThenSavedResumeRow() {
        val queue = listOf(botQueueItem(1), localQueueItem("/data/a.mp3"), botQueueItem(2))
        assertEquals(2, restoreResumeIndex(queue, currentKey = botKey(2), resumeKey = localKey("/data/a.mp3"), fallback = 0))
        assertEquals(1, restoreResumeIndex(queue, currentKey = null, resumeKey = localKey("/data/a.mp3"), fallback = 0))
        assertEquals(3, restoreResumeIndex(queue, currentKey = null, resumeKey = null, fallback = 99))
    }

    // Removing the current paused entry clears the paused record instead of reviving it.
    @Test fun removingCurrentEntryClearsResumePreference() {
        val queue = listOf(botQueueItem(1), botQueueItem(2))
        val mutation = removeQueueEntries(queue, index = 1, resumeIndex = 1, entryIds = setOf(queue[1].entryId))
        assertTrue(mutation.removedCurrent)
        assertEquals(listOf(botKey(1)), mutation.queue.map { it.key })
        assertEquals(-1, mutation.index)
    }

    // A paused media that appears twice resumes the copy at the saved index, not the first match.
    @Test fun pausedIndexPrefersTheSavedIndexOverTheFirstCopyOfTheSameMedia() {
        val base = localQueueItem("/data/a.mp3")
        val queue = listOf(base, localQueueItem("/data/b.mp3"), base.copy(entryId = "entry-second-copy"))

        // No usable current index: the resume index still selects the second copy.
        assertEquals(2, pausedQueueIndex(queue, queueIndex = -1, queueResumeIndex = 2, pausedKey = localKey("/data/a.mp3")))
        // A current index that already points at the paused media wins.
        assertEquals(2, pausedQueueIndex(queue, queueIndex = 2, queueResumeIndex = 0, pausedKey = localKey("/data/a.mp3")))
        // Neither index matches the media, so the first copy is the fallback.
        assertEquals(0, pausedQueueIndex(queue, queueIndex = 1, queueResumeIndex = 1, pausedKey = localKey("/data/a.mp3")))
        // An unknown paused media keeps the current index.
        assertEquals(1, pausedQueueIndex(queue, queueIndex = 1, queueResumeIndex = 2, pausedKey = localKey("/data/z.mp3")))
    }

    // The occurrence id is the row identity across a restore, so a duplicate keeps its own pointer.
    @Test fun restoreResumeIndexPrefersTheSavedOccurrenceOverTheSharedMediaKey() {
        val base = localQueueItem("/data/a.mp3")
        val queue = listOf(base, base.copy(entryId = "entry-second-copy"))

        assertEquals(1, restoreResumeIndex(queue, currentKey = localKey("/data/a.mp3"), resumeKey = null,
            fallback = 0, currentEntryId = "entry-second-copy"))
        // A checkpoint without occurrence ids still resolves through its media key.
        assertEquals(0, restoreResumeIndex(queue, currentKey = localKey("/data/a.mp3"), resumeKey = null, fallback = 0))
    }

    // A live owner describes the media; the saved occurrence id must win so duplicates stay distinct.
    @Test fun resolvedRowKeepsItsSavedOccurrenceId() {
        val saved = listOf(QueueRow(botKey(2), "봇 2", PlaybackSource.BOT, botMessageId = 2L, botChatId = 7L,
            entryId = "entry-saved"))
        val entry = resolveRestoreQueue(saved, ownerLoaded = { true }) { botQueueItem(2) }.toQueueEntries().single()

        assertEquals("entry-saved", entry.entryId)
    }
}
class PausedIdentityPolicyTest {
    private val queue = listOf(botQueueItem(1), botQueueItem(2), localQueueItem("/data/a.mp3"))

    // A played bot stream carries a fileId; the saved identity must be the row key, not "bot:<fileId>".
    @Test fun playedBotStreamResolvesToTheStableQueueRow() {
        val playing = StreamingState(source = PlaybackSource.BOT, title = "t", ready = true, playing = true, botFileId = 20)
        assertEquals(botKey(2), pausedQueueKey(playing, queue))
        // Restored identity therefore finds the same row and resumes it.
        val row = queue.first { it.key == pausedQueueKey(playing, queue) }
        assertEquals(2L, row.botMessageId)
        assertEquals(20, row.botFileId)
    }

    // A live handle without its row (e.g. played outside the queue) still yields an identity.
    @Test fun playedBotStreamWithoutRowKeepsHandleIdentity() {
        val playing = StreamingState(source = PlaybackSource.BOT, ready = true, playing = true, botFileId = 999)
        assertEquals("bot:999", pausedQueueKey(playing, queue))
    }

    // Local playback uses a canonical path identity and matches its own row.
    @Test fun playedLocalStreamResolvesToItsCanonicalRow() {
        val playing = StreamingState(source = PlaybackSource.LOCAL_AUDIO, ready = true, playing = true, localAudioPath = "/data/a.mp3")
        assertEquals(localKey("/data/a.mp3"), pausedQueueKey(playing, queue))
        assertEquals(localKey("/data/a.mp3"), stableQueueKey("/data/a.mp3") { it })
    }

    // (iii) A resume turns the restored row into a real request: real file id, position, title, artwork.
    @Test fun botResumeRequestCarriesFileIdPositionAndMetadata() {
        val row = botQueueItem(2)
        val request = restoredPlaybackRequest(row, queue, positionMs = 42_000L, localAudioAvailable = false, botVideoAvailable = true)
        assertEquals(20, request?.item?.botFileId)
        assertEquals(42_000L, request?.positionMs)
        assertEquals(botKey(2), request?.item?.key)
        assertEquals("봇 영상 2", request?.item?.title)
        // Without a resolved bot video there is no start request; the identity is kept instead.
        assertNull(restoredPlaybackRequest(row, queue, 1L, localAudioAvailable = false, botVideoAvailable = false))
    }

    @Test fun localResumeRequestNeedsItsOwnedFile() {
        val row = localQueueItem("/data/a.mp3")
        val request = restoredPlaybackRequest(row, queue, positionMs = 5_000L, localAudioAvailable = true, botVideoAvailable = false)
        assertEquals("/data/a.mp3", request?.item?.localAudioPath)
        assertEquals(5_000L, request?.positionMs)
        assertNull(restoredPlaybackRequest(row, queue, 5_000L, localAudioAvailable = false, botVideoAvailable = false))
    }

    // Negative positions never reach the service as a backwards seek.
    @Test fun resumePositionIsNeverNegative() {
        val request = restoredPlaybackRequest(botQueueItem(1), queue, positionMs = -30L, localAudioAvailable = false, botVideoAvailable = true)
        assertEquals(0L, request?.positionMs)
    }

    // A saved descriptor without a handle takes the handle of a row with the same stable key.
    // Another chat/message identity never supplies it.
    @Test fun resumeResolvesHandleFromQueueIdentity() {
        val handleless = botQueueItem(2).copy(botFileId = null)
        val others = listOf(botQueueItem(1), botQueueItem(2))
        val request = restoredPlaybackRequest(handleless, others, 10L, localAudioAvailable = false, botVideoAvailable = true)
        assertEquals(20, request?.item?.botFileId)
        assertEquals(botKey(2), request?.item?.key)
        // No other row carries this identity: nothing may borrow the neighbouring item's handle.
        assertNull(restoredPlaybackRequest(handleless, listOf(botQueueItem(1)), 10L, localAudioAvailable = false, botVideoAvailable = true))
        assertNull(restoredPlaybackRequest(handleless, listOf(botQueueItem(2, chatId = -100L), botQueueItem(3)), 10L, localAudioAvailable = false, botVideoAvailable = true))
    }

    // (iv) Refreshing metadata never reorders or re-keys the queue.
    @Test fun refreshedRowsKeepIdentityAndOrder() {
        val saved = listOf(botQueueItem(2), localQueueItem("/data/a.mp3"))
        val refreshed = listOf(botQueueItem(2).copy(title = "새 제목", botFileId = 77), localQueueItem("/data/a.mp3"))
        assertEquals(saved.map { it.key }, refreshed.map { it.key })
        assertFalse(sameRestoredRows(saved, refreshed))
        assertTrue(sameRestoredRows(saved, saved))
    }
}
class ServiceDetachedPolicyTest {
    private val queue = listOf(botQueueItem(1), botQueueItem(2), localQueueItem("/data/a.mp3"))

    // (1) A BOT service that ended without a close keeps its fileId; the explicit flag, not a
    // missing handle, must classify it as a paused session.
    @Test fun detachedBotServiceIsPausedEvenWithItsHandle() {
        val detached = StreamingState(
            source = PlaybackSource.BOT, title = "봇 영상 2", ready = false, playing = false,
            positionMs = 42_000L, durationMs = 180_000L, botFileId = 20, serviceDetached = true,
        )
        assertTrue(isSeparatedPausedStream(detached))
        assertFalse("a live handle alone must not look paused", isSeparatedPausedStream(detached.copy(serviceDetached = false)))
        // Display resolves through the stable row, not the handle.
        val state = AppUiState(stream = detached, queue = queue, pausedDisplay = detached, pausedEntryIndex = 1)
        assertTrue(state.playerVisible)
        assertEquals(botKey(2), state.playerPreview?.key)
        assertEquals(42_000L, state.playerDisplayStream.positionMs)
    }

    @Test fun detachedLocalServiceIsPausedEvenWithItsPath() {
        val detached = StreamingState(
            source = PlaybackSource.LOCAL_AUDIO, title = "보관함 a", localAudioPath = "/data/a.mp3",
            localAudioMime = "audio/mp4", positionMs = 7_000L, serviceDetached = true,
        )
        assertTrue(isSeparatedPausedStream(detached))
        assertFalse(isSeparatedPausedStream(detached.copy(serviceDetached = false)))
        val state = AppUiState(stream = detached, queue = queue, pausedDisplay = detached, pausedEntryIndex = 2)
        assertTrue(state.playerVisible)
        assertEquals(2, state.resumableQueueIndex)
    }

    // Preparing is not "detached": a request that is resolving still owns its source.
    @Test fun preparingIsNotDetached() {
        val preparing = StreamingState(source = PlaybackSource.BOT, resolving = true, buffering = true, botFileId = 20)
        assertFalse(isSeparatedPausedStream(preparing))
        assertTrue(preparing.isPlayerVisible())
    }

    // A detached session with no queue row left must not display a player entry point.
    @Test fun detachedSessionWithoutRowIsNotDisplayed() {
        val detached = StreamingState(source = PlaybackSource.BOT, botFileId = 20, serviceDetached = true)
        val state = AppUiState(stream = detached, queue = emptyList(), pausedDisplay = detached, pausedEntryIndex = -1)
        assertFalse(state.playerVisible)
        assertEquals(-1, state.resumableQueueIndex)
    }

    // The resume of a detached session still names the row's real media and the stopped position.
    @Test fun detachedSessionResumesItsRowAtTheStoppedPosition() {
        val detached = StreamingState(
            source = PlaybackSource.BOT, botFileId = 20, positionMs = 33_000L, durationMs = 180_000L, serviceDetached = true,
        )
        val key = pausedQueueKey(detached, queue)
        assertEquals(botKey(2), key)
        val row = queue.first { it.key == key }
        val request = restoredPlaybackRequest(row, queue, playheadPositionMs(detached), localAudioAvailable = false, botVideoAvailable = true)
        assertEquals(20, request?.item?.botFileId)
        assertEquals(33_000L, request?.positionMs)
    }
}
