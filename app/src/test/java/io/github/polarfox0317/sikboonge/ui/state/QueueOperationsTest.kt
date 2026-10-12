package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.QueueTransportAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOperationsTest {
    @Test fun naturalEndAdvancesFromTheCurrentQueueKeyAndStopsAtTheFinalEntry() {
        val queue = listOf(item("first"), item("second"))

        assertEquals(QueueNaturalEndAction.PlayNext(1), queueNaturalEndAction(queue, "first", true))
        assertEquals(QueueNaturalEndAction.Stop, queueNaturalEndAction(queue, "second", true))
    }

    @Test fun naturalEndIgnoresMissingCurrentEntriesAndUnmatchedSources() {
        val queue = listOf(item("first"), item("second"))

        assertEquals(null, queueNaturalEndAction(queue, null, true))
        assertEquals(null, queueNaturalEndAction(queue, "removed", true))
        assertEquals(null, queueNaturalEndAction(queue, "first", false))
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

    @Test fun onlyExplicitAppendAddsAnEntryAndDuplicateAddsAreIgnored() {
        val queue = listOf(item("first"))
        val added = item("added")

        assertEquals(listOf(queue.single(), added), appendQueueEntry(queue, added))
        assertSame(queue, appendQueueEntry(queue, queue.single()))
    }

    @Test fun removingTheCurrentEntrySignalsStopAndPreservesTheNextAnchor() {
        val queue = listOf(item("first"), item("current"), item("last"))

        val removed = removeQueueEntries(queue, index = 1, resumeIndex = 1, keys = setOf("current"))

        assertEquals(listOf(queue[0], queue[2]), removed.queue)
        assertEquals(-1, removed.index)
        assertEquals(1, removed.resumeIndex)
        assertTrue(removed.removedCurrent)
    }

    @Test fun removingANoncurrentEntryKeepsTheCurrentEntryAndDoesNotSignalStop() {
        val queue = listOf(item("first"), item("current"), item("last"))

        val removed = removeQueueEntries(queue, index = 1, resumeIndex = 1, keys = setOf("last"))

        assertEquals(listOf(queue[0], queue[1]), removed.queue)
        assertEquals(1, removed.index)
        assertEquals(1, removed.resumeIndex)
        assertFalse(removed.removedCurrent)
    }

    @Test fun removingEveryEntrySignalsStopWhenTheCurrentEntryIsIncluded() {
        val queue = listOf(item("first"), item("current"))

        val removed = removeQueueEntries(queue, index = 1, resumeIndex = 1, keys = queue.mapTo(mutableSetOf()) { it.key })

        assertTrue(removed.queue.isEmpty())
        assertTrue(removed.removedCurrent)
    }

    private fun item(key: String) = QueueItemUi(
        key = key,
        title = key,
        sourceKind = PlaybackSource.LOCAL_AUDIO,
        localAudioPath = key,
    )
}
