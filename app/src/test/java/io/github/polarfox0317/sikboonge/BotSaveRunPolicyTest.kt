package io.github.polarfox0317.sikboonge

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BotSaveRunPolicyTest {
    @Test fun queueAndPausedTimeDoNotConsumeActiveBudget() {
        var now = 0L
        val clock = BotSaveActiveClock { now }
        now += TimeUnit.HOURS.toNanos(2)
        clock.start()
        now += TimeUnit.SECONDS.toNanos(15)
        clock.stop()
        now += TimeUnit.HOURS.toNanos(2)
        assertEquals(BotAudioSavePolicy.MAX_JOB_SECONDS - 15, clock.remainingSeconds())
        clock.start()
        now += TimeUnit.SECONDS.toNanos(20)
        assertEquals(BotAudioSavePolicy.MAX_JOB_SECONDS - 35, clock.remainingSeconds())
    }

    @Test fun pauseAndCancelCannotPersistOrComplete() {
        for (cancel in listOf(false, true)) {
            val gate = BotSaveCommitGate()
            if (cancel) gate.requestCancel() else gate.requestPause()
            var calls = 0
            assertThrows(InterruptedException::class.java) { gate.commit { calls++ } }
            assertEquals(0, calls)
            assertFalse(gate.committed)
        }
    }

    @Test fun failedPersistenceNeverCommitsAndSuccessCommitsOnlyOnce() {
        val gate = BotSaveCommitGate()
        assertThrows(IllegalStateException::class.java) { gate.commit { error("disk failure") } }
        assertFalse(gate.committed)
        var calls = 0
        gate.commit { calls++ }
        assertTrue(gate.committed)
        assertThrows(InterruptedException::class.java) { gate.commit { calls++ } }
        assertEquals(1, calls)
        assertFalse(gate.requestPause())
        assertFalse(gate.requestCancel())
    }

    @Test fun persistenceWinsConcurrentLatePauseAndCancel() {
        val gate = BotSaveCommitGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val commit = Thread { gate.commit { entered.countDown(); release.await() } }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val paused = AtomicBoolean(true)
        val pause = Thread { paused.set(gate.requestPause()) }.apply { start() }
        release.countDown()
        commit.join(2000); pause.join(2000)
        assertFalse(commit.isAlive); assertFalse(pause.isAlive)
        assertTrue(gate.committed)
        assertFalse(paused.get())
        assertFalse(gate.requestCancel())
    }

    @Test fun resumedRunClearsStopButNeverRevivesCancellation() {
        val gate = BotSaveCommitGate()
        gate.requestPause()
        gate.beginRun() // caller must first acknowledge worker stop through the scheduler
        assertFalse(gate.stopRequested.get())
        gate.requestCancel()
        assertThrows(IllegalStateException::class.java) { gate.beginRun() }
    }

    @Test fun stopAcknowledgementWaitsForCallbackDespiteInterrupt() {
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val acknowledged = AtomicBoolean(false)
        val worker = Thread {
            Thread.currentThread().interrupt()
            entered.countDown()
            awaitBotWorkerStopped(latch)
            acknowledged.set(true)
        }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(acknowledged.get())
        latch.countDown(); worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(acknowledged.get())
    }

    @Test fun resumedExtractionKeepsInputCoverageButResetsProcessingMetrics() {
        var now = 0L
        val tracker = DownloadProgressTracker(1000L, 0L) { now }
        tracker.addRange(0, 500, 1000)
        tracker.observeProgress(.5f)
        now += 1_000_000_000L
        tracker.observeProgress(.6f)
        tracker.observePlatformDownloadedSize(500, 1000)
        val restarted = tracker.restartProcessing(500)
        assertEquals(500L, restarted.receivedBytes)
        assertNull(restarted.progress); assertNull(restarted.bytesPerSecond); assertNull(restarted.remainingSeconds)
        assertEquals(.1f, tracker.observeProgress(.1f).progress!!, 0f)
        assertEquals(500L, tracker.addRange(0, 500, 1000).receivedBytes)
    }

    @Test fun notificationIdsRemainDistinctForHashAliasesAndPersistedRestartSeed() {
        assertEquals(1L.hashCode(), 0x100000000L.hashCode())
        val first = BotSaveNotificationIds.next(BotSaveNotificationIds.FIRST - 1)
        val persisted = first
        val nextService = BotSaveNotificationIds.next(persisted)
        assertNotEquals(first, nextService)
        assertEquals(first + 1, nextService)
        assertThrows(IllegalArgumentException::class.java) { BotSaveNotificationIds.next(BotSaveNotificationIds.LAST) }
    }
}
