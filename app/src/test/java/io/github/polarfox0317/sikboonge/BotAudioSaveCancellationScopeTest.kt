package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotAudioSaveCancellationScopeTest {
    @Test
    fun cancellingOneScopeCancelsOnlyItsRegisteredSessions() {
        val first = BotAudioSaveCancellationScope()
        val second = BotAudioSaveCancellationScope()
        val firstCancelled = mutableListOf<Long>()
        val secondCancelled = mutableListOf<Long>()
        first.registerSession(11L) { firstCancelled += it }
        second.registerSession(22L) { secondCancelled += it }

        assertTrue(first.cancel())
        assertFalse(first.cancel())
        assertEquals(listOf(11L), firstCancelled)
        assertTrue(secondCancelled.isEmpty())
        assertFalse(second.isCancelled)
        assertTrue(second.cancel())
        assertEquals(listOf(22L), secondCancelled)
    }

    @Test
    fun cancellationBeforeSessionRegistrationImmediatelyCancelsNewSession() {
        val scope = BotAudioSaveCancellationScope()
        val cancelled = mutableListOf<Long>()
        assertTrue(scope.cancel())

        scope.registerSession(31L) { cancelled += it }

        assertEquals(listOf(31L), cancelled)
    }

    @Test
    fun finishedSessionIsNotCancelledWithTheRemainingJobSessions() {
        val scope = BotAudioSaveCancellationScope()
        val cancelled = mutableListOf<Long>()
        scope.registerSession(41L) { cancelled += it }
        scope.registerSession(42L) { cancelled += it }
        scope.finishSession(41L)

        scope.cancel()

        assertEquals(listOf(42L), cancelled)
    }
}
