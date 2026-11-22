package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.QueueTransportAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOperationsTest {
    @Test fun naturalEndAdvancesFromTheCurrentEntryAndStopsAtTheFinalEntry() {
        val queue = listOf(item("first"), item("second"))

        assertEquals(QueueNaturalEndAction.PlayNext(1), queueNaturalEndAction(queue, "entry-first", true))
        assertEquals(QueueNaturalEndAction.Stop, queueNaturalEndAction(queue, "entry-second", true))
    }

    @Test fun naturalEndIgnoresMissingCurrentEntriesAndUnmatchedSources() {
        val queue = listOf(item("first"), item("second"))

        assertEquals(null, queueNaturalEndAction(queue, null, true))
        assertEquals(null, queueNaturalEndAction(queue, "entry-removed", true))
        assertEquals(null, queueNaturalEndAction(queue, "entry-first", false))
    }

    // Two queued copies of one media: the ended occurrence decides, not the shared media key.
    @Test fun naturalEndAdvancesPastADuplicateWithoutRewinding() {
        val first = item("media")
        val second = first.copy(entryId = "entry-duplicate")

        assertEquals(QueueNaturalEndAction.PlayNext(1), queueNaturalEndAction(listOf(first, second), first.entryId, true))
        assertEquals(QueueNaturalEndAction.Stop, queueNaturalEndAction(listOf(first, second), second.entryId, true))
    }

    @Test fun naturalEndEdgeIsHandledOnceUntilPlaybackLeavesEndedState() {
        val edge = QueueNaturalEndEdge(initiallyEnded = false)

        assertFalse(edge.observe(false))
        assertTrue(edge.observe(true))
        assertFalse(edge.observe(true))
        assertFalse(edge.observe(false))
        assertTrue(edge.observe(true))
    }

    @Test fun queueTransportAvailabilityReflectsQueueBoundsAndRestartPosition() {
        val queue = listOf(item("first"), item("second"))

        assertEquals(QueueTransportAvailability(hasNext = true), queueTransportAvailability(queue, 0, 0, 0L))
        assertEquals(QueueTransportAvailability(hasPrevious = true), queueTransportAvailability(queue, 1, 1, 0L))
        assertEquals(QueueTransportAvailability(hasPrevious = true, hasNext = true), queueTransportAvailability(queue, 0, 0, 3_001L))
        assertEquals(QueueTransportAvailability(), queueTransportAvailability(emptyList(), -1, 0, 0L))
    }

    @Test fun directPlayReplacesTheQueueWithOnlyTheSelectedItem() {
        val selected = item("selected")

        val playback = queueForPlayback(selected)

        assertEquals(listOf(selected), playback.queue)
        assertEquals(0, playback.index)
        assertEquals(0, playback.resumeIndex)
        assertFalse(playback.removedCurrent)
    }

    @Test fun playingAPlaylistRowKeepsTheQueueAndSelectsItsPosition() {
        val queue = listOf(item("first"), item("selected"), item("last"))

        val playback = selectQueueEntry(queue, 1)

        assertSame(queue, playback.queue)
        assertEquals(1, playback.index)
        assertEquals(1, playback.resumeIndex)
    }

    // The user's rule: the same media may be queued again.
    @Test fun appendingASecondCopyOfTheSameMediaAddsANewOccurrence() {
        val first = item("media")
        val queue = listOf(item("other"))

        val added = appendQueueEntry(queue, first)
        val duplicate = first.copy(entryId = "entry-second-copy")

        assertEquals(listOf(queue.single(), first), added)
        assertEquals(listOf(queue.single(), first, duplicate), appendQueueEntry(added, duplicate))
    }

    @Test fun appendingTheSameOccurrenceTwiceDoesNotFork() {
        val queue = listOf(item("first"))

        assertSame(queue, appendQueueEntry(queue, queue.single()))
    }

    @Test fun removingTheCurrentEntrySignalsStopAndPreservesTheNextAnchor() {
        val queue = listOf(item("first"), item("current"), item("last"))

        val removed = removeQueueEntries(queue, index = 1, resumeIndex = 1, entryIds = setOf(queue[1].entryId))

        assertEquals(listOf(queue[0], queue[2]), removed.queue)
        assertEquals(-1, removed.index)
        assertEquals(1, removed.resumeIndex)
        assertTrue(removed.removedCurrent)
    }

    @Test fun removingANoncurrentEntryKeepsTheCurrentEntryAndDoesNotSignalStop() {
        val queue = listOf(item("first"), item("current"), item("last"))

        val removed = removeQueueEntries(queue, index = 1, resumeIndex = 1, entryIds = setOf(queue[2].entryId))

        assertEquals(listOf(queue[0], queue[1]), removed.queue)
        assertEquals(1, removed.index)
        assertEquals(1, removed.resumeIndex)
        assertFalse(removed.removedCurrent)
    }

    // Deleting one copy must leave the other copy of the same media playing.
    @Test fun removingOneDuplicateKeepsTheOtherOccurrenceAndDoesNotSignalStop() {
        val first = item("media")
        val second = first.copy(entryId = "entry-second-copy")

        val removed = removeQueueEntries(listOf(first, second), index = 1, resumeIndex = 1, entryIds = setOf(first.entryId))

        assertEquals(listOf(second), removed.queue)
        assertEquals(0, removed.index)
        assertFalse(removed.removedCurrent)
    }

    @Test fun movingADuplicateKeepsBothOccurrencesAndTheCurrentPointer() {
        val first = item("media")
        val second = first.copy(entryId = "entry-second-copy")
        val queue = listOf(first, second, item("other"))

        val moved = moveQueueEntry(queue, index = 1, resumeIndex = 1, from = 1, to = 2)

        assertEquals(listOf(first, queue[2], second), moved.queue)
        assertEquals(2, moved.index)
        assertEquals(2, moved.resumeIndex)
    }

    private fun item(key: String) = QueueItemUi(
        key = key,
        title = key,
        sourceKind = PlaybackSource.LOCAL_AUDIO,
        localAudioPath = key,
        entryId = "entry-$key",
    )
}
