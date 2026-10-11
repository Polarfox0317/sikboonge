package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadExecutionSchedulerTest {
    @Test
    fun asynchronousPreparationCannotStartLaterClickBeforeHeadFinishes() {
        val scheduler = DownloadExecutionScheduler<() -> Unit>()
        val preparing = CountDownLatch(1)
        val finishPreparation = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<Long>())
        scheduler.enqueue(1L, "file:1") { order.add(1L); preparing.countDown(); finishPreparation.await() }
        scheduler.enqueue(2L, "file:2") { order.add(2L) }
        val first = scheduler.startAvailable(DownloadExecutionMode.Sequential).single()
        val worker = Thread {
            first.value()
            scheduler.finish(first.downloadId)
        }.apply { start() }
        try {
            assertTrue(preparing.await(2, TimeUnit.SECONDS))
            assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
            assertEquals(listOf(1L), order.toList())
        } finally { finishPreparation.countDown(); worker.join(2000) }
        assertTrue(!worker.isAlive)
        scheduler.startAvailable(DownloadExecutionMode.Sequential).single().value()
        assertEquals(listOf(1L, 2L), order.toList())
    }

    @Test
    fun queuedPauseSkipsAndResumeKeepsOriginalRankWithoutPreemption() {
        val scheduler = DownloadExecutionScheduler<String>()
        repeat(4) { scheduler.enqueue(it + 1L, "file:$it", "$it") }
        scheduler.pause(1L)
        assertEquals(listOf(2L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        scheduler.resume(1L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.finish(2L)
        assertEquals(listOf(1L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
    }

    @Test
    fun activePauseRetainsSlotUntilWorkerAcknowledgesStop() {
        val scheduler = DownloadExecutionScheduler<String>()
        repeat(3) { scheduler.enqueue(it + 1L, "file:$it", "$it") }
        scheduler.startAvailable(DownloadExecutionMode.Sequential)
        assertTrue(scheduler.pause(1L))
        assertTrue(scheduler.isPaused(1L))
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.requeueAfterStop(1L)
        assertEquals(listOf(2L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        scheduler.resume(1L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.finish(2L)
        assertEquals(listOf(1L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        assertEquals(listOf(3L), scheduler.queuedEntries().map { it.downloadId })
    }

    @Test
    fun resumeDuringStopCannotLaunchOverlappingWorker() {
        val scheduler = DownloadExecutionScheduler<String>()
        scheduler.enqueue(1L, "file:1", "one")
        scheduler.enqueue(2L, "file:2", "two")
        scheduler.startAvailable(DownloadExecutionMode.Sequential)
        scheduler.pause(1L)
        scheduler.resume(1L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.requeueAfterStop(1L)
        assertEquals(listOf(1L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        assertEquals(1, scheduler.activeEntries().size)
    }

    @Test
    fun pausedIdentitiesStillDeduplicateAndCancellationRemovesThem() {
        val scheduler = DownloadExecutionScheduler<String>()
        scheduler.enqueue(1L, "file:1", "one")
        scheduler.pause(1L)
        assertEquals(DownloadExecutionScheduler.EnqueueResult.DuplicateSource, scheduler.enqueue(2L, "file:1", "alias"))
        assertEquals(DownloadExecutionScheduler.EnqueueResult.DuplicateId, scheduler.enqueue(1L, "file:2", "alias"))
        assertEquals(1L, scheduler.cancelQueued(1L)?.downloadId)
        assertTrue(!scheduler.isPaused(1L))
        assertTrue(!scheduler.resume(1L))
        assertTrue(!scheduler.hasWork)
        assertEquals(DownloadExecutionScheduler.EnqueueResult.Added, scheduler.enqueue(2L, "file:1", "new"))
    }

    @Test
    fun parallelPauseDoesNotFreeCapacityBeforeAcknowledgementAndSequentialDrains() {
        val scheduler = DownloadExecutionScheduler<String>()
        repeat(5) { scheduler.enqueue(it + 1L, "file:$it", "$it") }
        scheduler.startAvailable(DownloadExecutionMode.Parallel)
        scheduler.pause(2L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Parallel).isEmpty())
        scheduler.requeueAfterStop(2L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.finish(1L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.finish(3L)
        assertEquals(listOf(4L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        scheduler.resume(2L)
        scheduler.finish(4L)
        assertEquals(listOf(2L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
    }

    @Test
    fun sequentialModeAcceptsQueueAndStartsOnlyAfterCurrentJobFinishes() {
        val scheduler = DownloadExecutionScheduler<String>(maxParallelDownloads = 3)
        assertEquals(DownloadExecutionScheduler.EnqueueResult.Added, scheduler.enqueue(1L, "file:1", "one"))
        assertEquals(DownloadExecutionScheduler.EnqueueResult.Added, scheduler.enqueue(2L, "file:2", "two"))

        assertEquals(listOf(1L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        assertEquals(listOf(2L), scheduler.queuedEntries().map { it.downloadId })

        scheduler.finish(1L)
        assertEquals(listOf(2L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
    }

    @Test
    fun parallelModeStartsUpToCapacityAndModeChangeFillsNewCapacity() {
        val scheduler = DownloadExecutionScheduler<String>(maxParallelDownloads = 3)
        repeat(4) { index -> scheduler.enqueue(index + 1L, "file:${index + 1}", "job:$index") }

        assertEquals(listOf(1L, 2L, 3L), scheduler.startAvailable(DownloadExecutionMode.Parallel).map { it.downloadId })
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.finish(2L)
        assertTrue(scheduler.startAvailable(DownloadExecutionMode.Sequential).isEmpty())
        scheduler.finish(1L)
        scheduler.finish(3L)
        assertEquals(listOf(4L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        assertEquals(1, scheduler.activeEntries().size)
    }

    @Test
    fun switchingFromSequentialToParallelImmediatelyStartsQueuedJobs() {
        val scheduler = DownloadExecutionScheduler<String>(maxParallelDownloads = 3)
        repeat(4) { index -> scheduler.enqueue(index + 1L, "file:${index + 1}", "job:$index") }

        assertEquals(listOf(1L), scheduler.startAvailable(DownloadExecutionMode.Sequential).map { it.downloadId })
        assertEquals(listOf(2L, 3L), scheduler.startAvailable(DownloadExecutionMode.Parallel).map { it.downloadId })
        assertEquals(listOf(4L), scheduler.queuedEntries().map { it.downloadId })
    }

    @Test
    fun duplicateTdlibSourceIsRejectedAcrossQueuedAndActiveJobs() {
        val scheduler = DownloadExecutionScheduler<String>()
        assertEquals(DownloadExecutionScheduler.EnqueueResult.Added, scheduler.enqueue(1L, "tdlib-file:42", "first alias"))
        assertEquals(DownloadExecutionScheduler.EnqueueResult.DuplicateSource, scheduler.enqueue(2L, "tdlib-file:42", "second alias"))

        scheduler.startAvailable(DownloadExecutionMode.Parallel)
        assertEquals(DownloadExecutionScheduler.EnqueueResult.DuplicateSource, scheduler.enqueue(3L, "tdlib-file:42", "third alias"))
        assertEquals(DownloadExecutionScheduler.EnqueueResult.DuplicateId, scheduler.enqueue(1L, "tdlib-file:99", "reused id"))
    }

    @Test
    fun cancellingQueuedJobLeavesOtherWorkAndActiveJobUntouched() {
        val scheduler = DownloadExecutionScheduler<String>()
        scheduler.enqueue(1L, "file:1", "active")
        scheduler.enqueue(2L, "file:2", "cancel me")
        scheduler.enqueue(3L, "file:3", "keep queued")
        scheduler.startAvailable(DownloadExecutionMode.Sequential)

        assertEquals("cancel me", scheduler.cancelQueued(2L)?.value)
        assertEquals(listOf(1L), scheduler.activeEntries().map { it.downloadId })
        assertEquals(listOf(3L), scheduler.queuedEntries().map { it.downloadId })
    }
}
