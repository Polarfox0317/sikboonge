package io.github.polarfox0317.sikboonge

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartPositionPolicyTest {
    // (2) A cold BOT/LOCAL request that names a position must carry it to the media start.
    @Test fun requestPositionIsApplied() {
        assertEquals(42_000L, StartPositionPolicy.fromRequest(42_000L))
        assertEquals(42_000L, StartPositionPolicy.armedPosition(StartPositionPolicy.fromRequest(42_000L)))
    }

    // A fresh item that names no position must not inherit the previous item's one.
    @Test fun freshItemClearsThePreviousPosition() {
        val previous = StartPositionPolicy.armedPosition(42_000L)
        assertEquals(42_000L, previous)
        assertNull(StartPositionPolicy.fromRequest(-1L))
        assertEquals(C.TIME_UNSET, StartPositionPolicy.armedPosition(StartPositionPolicy.fromRequest(-1L)))
        // The armed value is the sentinel the service checks, so it is treated as "none".
        assertNull(StartPositionPolicy.fallbackResumeMs(C.TIME_UNSET, lastPositionMs = 0L, lastEnded = false))
    }

    // A request naming the start of the item still starts there (no fallback to an old position).
    @Test fun explicitZeroPositionIsHonored() {
        assertEquals(0L, StartPositionPolicy.fromRequest(0L))
        assertEquals(0L, StartPositionPolicy.armedPosition(StartPositionPolicy.fromRequest(0L)))
        assertEquals(0L, StartPositionPolicy.fallbackResumeMs(0L, lastPositionMs = 50_000L, lastEnded = false))
    }

    // (3) A service-only fallback rebuild starts where playback stopped, unless it ended.
    @Test fun fallbackKeepsTheStoppedPosition() {
        assertEquals(33_000L, StartPositionPolicy.fallbackResumeMs(null, lastPositionMs = 33_000L, lastEnded = false))
        assertEquals(54_000L, StartPositionPolicy.fallbackResumeMs(54_000L, lastPositionMs = 33_000L, lastEnded = false))
    }

    @Test fun fallbackDoesNotReplayAnEndedItem() {
        assertNull(StartPositionPolicy.fallbackResumeMs(null, lastPositionMs = 180_000L, lastEnded = true))
        // A named position still wins; an explicit resume may replay from anywhere.
        assertEquals(1_000L, StartPositionPolicy.fallbackResumeMs(1_000L, lastPositionMs = 180_000L, lastEnded = true))
    }

    @Test fun fallbackIgnoresAnUnsetPosition() {
        assertNull(StartPositionPolicy.fallbackResumeMs(null, lastPositionMs = 0L, lastEnded = false))
    }
}
