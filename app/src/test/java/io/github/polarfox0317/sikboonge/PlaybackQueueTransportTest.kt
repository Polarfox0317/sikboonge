package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTransportTest {
    @Test fun higherPriorityOwnerControlsAvailabilityAndTransportUntilUnbound() {
        val mainOwner = Any()
        val reviewOwner = Any()
        var mainNextCalls = 0
        var reviewNextCalls = 0
        try {
            PlaybackQueueTransport.bind(
                mainOwner, priority = 0,
                availability = QueueTransportAvailability(hasNext = true),
                previous = { false },
                next = { mainNextCalls++; true },
            )
            assertEquals(QueueTransportAvailability(hasNext = true), PlaybackQueueTransport.availability.value)
            assertTrue(PlaybackQueueTransport.next())

            PlaybackQueueTransport.bind(
                reviewOwner, priority = 1,
                availability = QueueTransportAvailability(hasPrevious = true),
                previous = { true },
                next = { reviewNextCalls++; true },
            )
            assertEquals(QueueTransportAvailability(hasPrevious = true), PlaybackQueueTransport.availability.value)
            assertFalse(PlaybackQueueTransport.next())
            assertTrue(PlaybackQueueTransport.previous())

            PlaybackQueueTransport.unbind(reviewOwner)
            assertEquals(QueueTransportAvailability(hasNext = true), PlaybackQueueTransport.availability.value)
            assertTrue(PlaybackQueueTransport.next())
            assertEquals(2, mainNextCalls)
            assertEquals(0, reviewNextCalls)
        } finally {
            PlaybackQueueTransport.unbind(reviewOwner)
            PlaybackQueueTransport.unbind(mainOwner)
        }
        assertEquals(QueueTransportAvailability(), PlaybackQueueTransport.availability.value)
    }
}
