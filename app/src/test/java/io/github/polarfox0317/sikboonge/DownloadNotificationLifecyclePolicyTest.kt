package io.github.polarfox0317.sikboonge

import io.github.polarfox0317.sikboonge.DownloadNotificationLifecyclePolicy.Entry
import io.github.polarfox0317.sikboonge.DownloadNotificationLifecyclePolicy.StopBehavior
import org.junit.Assert.*
import org.junit.Test

class DownloadNotificationLifecyclePolicyTest {
    private val policy = DownloadNotificationLifecyclePolicy
    private val firstId = 0x60000001
    private val secondId = 0x60000002

    /** Android ignores cancel(id) while that id represents the foreground service. */
    private class Notifications {
        var foregroundId: Int? = null
        val rows = mutableMapOf<Int, String>()
        val calls = mutableListOf<String>()

        fun transition(previous: Entry?, next: Entry?) {
            val plan = DownloadNotificationLifecyclePolicy.transition(previous, next)
            plan.stop?.let { behavior ->
                calls.add("stop:$behavior:$foregroundId")
                if (behavior == StopBehavior.Remove) foregroundId?.let(rows::remove)
                foregroundId = null
            }
            plan.promoteId?.let { id ->
                calls.add("start:$id")
                foregroundId = id
                rows[id] = "foreground"
            }
        }

        fun cancel(entry: Entry) {
            if (!DownloadNotificationLifecyclePolicy.shouldRemoveCancelled(entry, foregroundId)) return
            calls.add("cancel:${entry.notificationId}")
            // Keep the framework limitation here so an incorrect remove-before-rotate plan fails.
            if (foregroundId != entry.notificationId) rows.remove(entry.notificationId)
        }

        fun progress(entry: Entry) {
            if (DownloadNotificationLifecyclePolicy.canPostProgress(entry)) rows[entry.notificationId] = "progress"
        }
    }

    @Test fun cancellingRepresentativeRotatesBeforeRemovalAndKeepsOtherDownloadForeground() {
        val first = Entry(firstId, active = true)
        val second = Entry(secondId, active = true)
        val notifications = Notifications()
        notifications.transition(null, first)
        notifications.progress(second)
        notifications.calls.clear()
        val cancelled = first.copy(cancelled = true, pending = false)
        val selected = policy.selectForeground(listOf(cancelled, second), emptyList())
        assertSame(second, selected)
        notifications.transition(cancelled, selected)
        notifications.cancel(cancelled)
        assertEquals(listOf("stop:Remove:$firstId", "start:$secondId", "cancel:$firstId"), notifications.calls)
        assertEquals(secondId, notifications.foregroundId)
        assertFalse(notifications.rows.containsKey(firstId))
        assertTrue(notifications.rows.containsKey(secondId))
        notifications.progress(cancelled) // queued before cancel, delivered after the rotation
        assertFalse(notifications.rows.containsKey(firstId))
    }

    @Test fun nonrepresentativeCancellationRemovesOnlyThatRow() {
        val first = Entry(firstId, active = true)
        val second = Entry(secondId, active = true)
        val notifications = Notifications()
        notifications.transition(null, first)
        notifications.progress(second)
        val cancelled = second.copy(cancelled = true, pending = false)
        notifications.calls.clear()
        notifications.transition(first, policy.selectForeground(listOf(first, cancelled), emptyList()))
        notifications.cancel(cancelled)
        assertEquals(listOf("cancel:$secondId"), notifications.calls)
        assertEquals(firstId, notifications.foregroundId)
        assertFalse(notifications.rows.containsKey(secondId))
    }

    @Test fun lastCancelledWorkerKeepsRequiredStoppingRowUntilAcknowledgementThenRemovesBotId() {
        val first = Entry(firstId, active = true)
        val cancelled = first.copy(cancelled = true, pending = false)
        val notifications = Notifications()
        notifications.transition(null, first)
        assertSame(cancelled, policy.selectForeground(listOf(cancelled), emptyList()))
        notifications.transition(cancelled, cancelled)
        notifications.cancel(cancelled)
        assertEquals(firstId, notifications.foregroundId)
        assertTrue(notifications.rows.containsKey(firstId))
        assertFalse(policy.canPostProgress(cancelled))
        val acknowledged = cancelled.copy(active = false)
        notifications.transition(acknowledged, policy.selectForeground(emptyList(), emptyList()))
        assertNull(notifications.foregroundId)
        assertFalse(notifications.rows.containsKey(firstId))
        assertTrue(notifications.calls.contains("stop:Remove:$firstId"))
    }

    @Test fun runnableQueueCanRepresentServiceWhileCancelledWorkerStillOwnsSlot() {
        val cancelled = Entry(firstId, active = true, cancelled = true, pending = false)
        val waiting = Entry(secondId)
        assertSame(waiting, policy.selectForeground(listOf(cancelled), listOf(waiting)))
        assertTrue(cancelled.active) // no scheduler finish/slot release is implied by notification rotation
        assertEquals(StopBehavior.Remove, policy.transition(cancelled, waiting).stop)
    }

    @Test fun queuedAndPausedCancellationCannotBecomeForegroundOrRepublish() {
        for (paused in listOf(false, true)) {
            val cancelled = Entry(firstId, cancelled = true, paused = paused, pending = false)
            assertNull(policy.selectForeground(emptyList(), listOf(cancelled)))
            assertFalse(policy.canPostProgress(cancelled))
            assertTrue(policy.shouldRemoveCancelled(cancelled, null))
            assertEquals(StopBehavior.Remove, policy.transition(cancelled, null).stop)
        }
    }

    @Test fun allPausedDetachesPendingRowButCancellingItRemovesDetachedRow() {
        val pausing = Entry(firstId, active = true, paused = true, pausing = true)
        val paused = pausing.copy(active = false, pausing = false)
        val notifications = Notifications()
        notifications.transition(null, pausing)
        assertSame(pausing, policy.selectForeground(listOf(pausing), emptyList()))
        val next = policy.selectForeground(emptyList(), listOf(paused))
        assertNull(next)
        assertEquals(StopBehavior.Detach, policy.transition(paused, next).stop)
        notifications.transition(paused, next)
        assertNull(notifications.foregroundId)
        assertTrue(notifications.rows.containsKey(firstId))
        notifications.cancel(paused.copy(cancelled = true, pending = false))
        assertFalse(notifications.rows.containsKey(firstId))
    }

    @Test fun ordinaryRotationKeepsOtherLiveProgressAndPausedRows() {
        val first = Entry(firstId, active = true, pausing = true, paused = true)
        val second = Entry(secondId, active = true)
        val notifications = Notifications()
        notifications.transition(null, first)
        val next = policy.selectForeground(listOf(first, second), emptyList())
        assertSame(second, next)
        assertEquals(StopBehavior.Detach, policy.transition(first, next).stop)
        notifications.transition(first, next)
        assertEquals(secondId, notifications.foregroundId)
        assertEquals(setOf(firstId, secondId), notifications.rows.keys)
    }

    @Test fun completingRepresentativeRemovesProgressBeforeCompletionAndLaterCancelKeepsCompletion() {
        val first = Entry(firstId, active = true)
        val second = Entry(secondId, active = true)
        val notifications = Notifications()
        notifications.transition(null, first)
        val committed = first.copy(committed = true, active = false, pending = false)
        notifications.transition(committed, second)
        assertFalse(notifications.rows.containsKey(firstId))
        notifications.rows[firstId] = "completed"
        notifications.cancel(committed)
        notifications.progress(committed)
        notifications.progress(committed.copy(pending = true))
        assertEquals("completed", notifications.rows[firstId])
        assertEquals(secondId, notifications.foregroundId)
        assertFalse(policy.shouldRemoveCancelled(committed.copy(cancelled = true), secondId))
    }

    @Test fun cancelledAcknowledgementAndAllIdleLeavePreviousCompletionsUntouched() {
        val previousCompletionId = 0x60000000
        val cancelled = Entry(firstId, active = true, cancelled = true, pending = false)
        val notifications = Notifications()
        notifications.rows[previousCompletionId] = "completed"
        notifications.transition(null, cancelled)
        notifications.transition(cancelled.copy(active = false), null)
        notifications.cancel(cancelled)
        assertEquals(mapOf(previousCompletionId to "completed"), notifications.rows)
        assertNull(notifications.foregroundId)
    }

    @Test fun missingPendingRowCannotBeRecreatedByLateProgress() {
        assertFalse(policy.canPostProgress(Entry(firstId, active = true, pending = false)))
        assertFalse(policy.canPostProgress(Entry(firstId, active = false, committed = true)))
    }

    @Test fun localConversionSharedIdentityDoesNotRemoveNewRepresentative() {
        val cancelledLocal = Entry(1, active = true, cancelled = true, pending = false)
        val waitingLocal = Entry(1)
        val notifications = Notifications()
        notifications.transition(null, cancelledLocal)
        assertSame(waitingLocal, policy.selectForeground(listOf(cancelledLocal), listOf(waitingLocal)))
        notifications.transition(cancelledLocal, waitingLocal)
        notifications.cancel(cancelledLocal)
        assertEquals(1, notifications.foregroundId)
        assertTrue(notifications.rows.containsKey(1))
    }
}
