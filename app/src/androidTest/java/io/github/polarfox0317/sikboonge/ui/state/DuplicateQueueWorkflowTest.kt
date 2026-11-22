package io.github.polarfox0317.sikboonge.ui.state

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.polarfox0317.sikboonge.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeated adds of one media are separate queue occurrences.
 *
 * A, B, A, A produces four rows that share two media keys but have four distinct occurrence ids, and
 * closing/reopening restores the exact row that was selected — not the first row carrying that media.
 * The real controller runs against isolated preferences and owned fixture files; no account is used.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateQueueWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val prefix = "dup-queue-${System.nanoTime()}-"
    private val preferences = mutableSetOf<String>()
    private val serviceStarts = mutableListOf<Intent>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferences += prefix + name
            return base.getSharedPreferences(prefix + name, mode)
        }
        override fun startService(intent: Intent): ComponentName? {
            serviceStarts += intent
            return ComponentName(base, StreamingAudioService::class.java)
        }
    }
    private val bot = MutableStateFlow(BotUiState())
    private val created = mutableListOf<File>()
    private lateinit var controller: AppController
    // Each controller owns its scope; clear() ends one before the next begins, as a real death would.
    private var controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var history: MutableStateFlow<List<AudioResult>>
    private lateinit var audioA: AudioResult
    private lateinit var audioB: AudioResult

    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun fixtureAudio(name: String): AudioResult {
        val dir = File(base.filesDir, "audio").apply { mkdirs() }
        val file = File(dir, "$prefix$name.mp3").apply { writeBytes(ByteArray(16)) }
        created += file
        return AudioResult(path = file.absolutePath, name = name, mime = "audio/mpeg", bytes = 16L, date = 1L)
    }

    private fun keyOf(audio: AudioResult): String = "local:${File(audio.path).canonicalPath}"

    private fun state() = main { controller.uiState.value }

    private fun buildController(): AppController = AppController(context, controllerScope, botState = bot,
        libraryState = MutableStateFlow(ConversionState()), libraryHistory = history)

    /** Ends the current controller through its ViewModel lifecycle, as destroying its host would. */
    private fun clearController() {
        main {
            stores.last().let { store ->
                store.put("controller", controller)
                // clear() runs onCleared, which unbinds and cancels the controller's own scope.
                store.clear()
            }
            controllerScope.cancel()
            PlaybackQueueTransport.unbind(controller)
        }
        instrumentation.waitForIdleSync()
    }

    /** Starts a fresh controller with its own scope, reading only what the previous one persisted. */
    private fun startController(store: ViewModelStore = ViewModelStore()) {
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stores += store
        main { controller = buildController() }
        instrumentation.waitForIdleSync()
    }

    @Before fun prepare() {
        serviceStarts.clear()
        audioA = fixtureAudio("a")
        audioB = fixtureAudio("b")
        history = MutableStateFlow(listOf(audioA, audioB))
        main { StreamPlayback.publish(StreamingState()) }
        startController()
    }

    @After fun cleanup() {
        main {
            stores.forEach { it.apply { put("controller", controller); clear() } }
            controllerScope.cancel()
            PlaybackQueueTransport.unbind(controller)
            StreamPlayback.publish(StreamingState())
        }
        preferences.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        created.forEach { it.delete() }
    }

    /** A, B, A, A through the real add path. */
    private fun seedRepeatedAdds() {
        main {
            controller.onAddToQueue(audioA)
            controller.onAddToQueue(audioB)
            controller.onAddToQueue(audioA)
            controller.onAddToQueue(audioA)
        }
        instrumentation.waitForIdleSync()
    }

    // A, B, A, A: four rows, four occurrences, two shared media keys.
    @Test fun repeatedAddsCreateDistinctOccurrencesOfOneMedia() {
        seedRepeatedAdds()
        val queue = state().queue

        assertEquals(4, queue.size)
        assertEquals(listOf(keyOf(audioA), keyOf(audioB), keyOf(audioA), keyOf(audioA)), queue.map { it.key })
        assertEquals("every row is its own occurrence", 4, queue.map { it.entryId }.toSet().size)
        assertTrue("adding must not start playback", serviceStarts.isEmpty())
    }

    // Deleting the third row must leave the fourth in place and keep the current row playing.
    @Test fun deletingOneDuplicateLeavesTheCurrentRowAlone() {
        seedRepeatedAdds()
        main { controller.onPlayQueueItem(3) }
        instrumentation.waitForIdleSync()
        val before = state()
        assertEquals(3, before.queueIndex)
        val currentEntryId = before.queue[3].entryId

        main {
            controller.onToggleQueueEditing()
            controller.onToggleQueueSelection(before.queue[2].entryId)
            controller.onDeleteQueueSelection()
        }
        instrumentation.waitForIdleSync()

        val after = state()
        val survivors = before.queue.filterIndexed { index, _ -> index != 2 }
        assertEquals(3, after.queue.size)
        // The removed row is gone and every other occurrence keeps its own identity, in order.
        assertEquals("the other copy of A survives", survivors.map { it.entryId }, after.queue.map { it.entryId })
        assertEquals(listOf(keyOf(audioA), keyOf(audioB), keyOf(audioA)), after.queue.map { it.key })
        assertEquals(currentEntryId, after.queue[2].entryId)
        assertEquals("the current row is the surviving copy, not removed", 2, after.queueIndex)
    }

    /**
     * The reported case: select the third row (a duplicate of the first), pause, and let the process
     * die. The same occurrence must come back as the current row of the mini player. A pause keeps the
     * session; an explicit close would delete it instead.
     */
    @Test fun pausingAndProcessDeathRestoresTheSelectedDuplicate() {
        seedRepeatedAdds()
        main {
            controller.onPlayQueueItem(2)
            controller.onPause()
        }
        instrumentation.waitForIdleSync()
        val selected = state()
        assertEquals("the selected row stays current through the pause", 2, selected.queueIndex)
        val selectedEntryId = selected.queue[2].entryId
        assertEquals("the selected row is a later copy of A", keyOf(audioA), selected.queue[2].key)
        assertNotEquals(selected.queue[0].entryId, selectedEntryId)

        // The selection itself may have started the service; only the restore below must not.
        serviceStarts.clear()

        // Process death: the controller is torn down with no explicit close, and nothing stays live.
        clearController()
        main { StreamPlayback.publish(StreamingState()) }
        startController()

        val restored = state()
        assertEquals(4, restored.queue.size)
        assertEquals(
            "the same occurrence is restored, not the first copy of A",
            selected.queue.map { it.entryId },
            restored.queue.map { it.entryId },
        )
        assertEquals(2, restored.queueResumeIndex)
        assertEquals(selectedEntryId, restored.queue[restored.queueResumeIndex].entryId)
        assertTrue(restored.playerVisible)
        assertTrue("restoring must never start the service", serviceStarts.isEmpty())
    }

    // An explicit close deletes the session: a new controller starts empty and never resumes it.
    @Test fun closingThePlayerDeletesTheSessionSoANewControllerStartsEmpty() {
        seedRepeatedAdds()
        main { controller.onPlayQueueItem(2) }
        instrumentation.waitForIdleSync()
        val before = state()
        // Keep a Bundle from before the close: it must not resurrect the closed session either.
        val staleBundle = main { controller.saveRestorableState() }

        main { controller.onStop() }
        instrumentation.waitForIdleSync()
        val closed = state()
        assertTrue("the close deletes the queue", closed.queue.isEmpty())
        assertEquals(-1, closed.queueIndex)
        assertEquals(0, closed.queueResumeIndex)
        assertFalse(closed.emptyPlayerVisible)
        assertEquals("the checkpoint is removed", null, PlaybackQueueStore(context).read())
        assertTrue(before.queue.isNotEmpty())

        serviceStarts.clear()
        clearController()
        main { StreamPlayback.publish(StreamingState()) }
        startController()

        val reopened = state()
        assertTrue("the closed session must not come back", reopened.queue.isEmpty())
        assertEquals(-1, reopened.queueIndex)
        assertFalse(reopened.emptyPlayerVisible)
        assertTrue("a cold start must not play", serviceStarts.isEmpty())
        assertFalse(StreamPlayback.state.value.playing)

        // A Bundle captured before the close is stale and must not restore the old rows.
        main { controller.restoreRestorableState(staleBundle) }
        instrumentation.waitForIdleSync()
        val afterStale = state()
        assertTrue("a pre-close Bundle must not resurrect the queue", afterStale.queue.isEmpty())
        assertEquals(-1, afterStale.queueIndex)
        assertFalse(afterStale.emptyPlayerVisible)
        assertTrue(serviceStarts.isEmpty())
    }

    /**
     * The Activity-recreation path: the Bundle carries the paused occurrence, so a duplicate resumes
     * as the same row rather than the first copy of its media.
     */
    @Test fun activityRecreationRestoresTheSelectedDuplicateFromTheBundle() {
        seedRepeatedAdds()
        val queue = state().queue
        val selectedEntryId = queue[2].entryId
        assertNotEquals("the selected row is a later copy of A", queue[0].entryId, selectedEntryId)
        main { controller.onPlayQueueItem(2) }
        instrumentation.waitForIdleSync()
        assertEquals(2, state().queueIndex)

        // A real paused state: the third row is playing from its own file, then paused mid-way.
        main {
            StreamPlayback.publish(StreamingState(
                source = PlaybackSource.LOCAL_AUDIO, ready = true, playing = false,
                positionMs = 7_000L, durationMs = 60_000L, localAudioPath = File(audioA.path).canonicalPath,
                ended = false,
            ))
        }
        instrumentation.waitForIdleSync()
        main { StreamPlayback.publish(StreamPlayback.state.value.copy(serviceDetached = true)) }
        instrumentation.waitForIdleSync()
        val paused = state()
        assertEquals("the third row is the paused occurrence", 2, paused.pausedEntryIndex)
        assertEquals(2, paused.resumableQueueIndex)

        val bundle = main { controller.saveRestorableState() }
        serviceStarts.clear()

        // Process death: the old controller is torn down and no live stream survives it.
        clearController()
        main { StreamPlayback.publish(StreamingState()) }
        startController()
        main { controller.restoreRestorableState(bundle) }
        instrumentation.waitForIdleSync()

        val restored = state()
        assertEquals(4, restored.queue.size)
        assertEquals("the Bundle restores the selected occurrence", selectedEntryId, restored.queue[2].entryId)
        assertEquals("the paused row is the same duplicate", 2, restored.pausedEntryIndex)
        assertEquals(2, restored.resumableQueueIndex)
        assertEquals(selectedEntryId, restored.queue[restored.resumableQueueIndex].entryId)
        assertTrue("recreating from a Bundle must not start the service", serviceStarts.isEmpty())

        // Resuming continues the third row, and its natural end advances to the fourth.
        main { controller.onResume() }
        instrumentation.waitForIdleSync()
        assertEquals(2, state().queueIndex)
        assertEquals(selectedEntryId, state().queue[state().queueIndex].entryId)

        // The end is an ordered edge on the same source: playing (ended = false) first, then ended.
        val resumedPath = File(audioA.path).canonicalPath
        main {
            StreamPlayback.publish(StreamingState(source = PlaybackSource.LOCAL_AUDIO, ready = true,
                playing = true, positionMs = 7_000L, durationMs = 60_000L, localAudioPath = resumedPath,
                ended = false))
        }
        instrumentation.waitForIdleSync()
        main {
            StreamPlayback.publish(StreamingState(source = PlaybackSource.LOCAL_AUDIO, ready = true,
                playing = false, positionMs = 60_000L, durationMs = 60_000L, localAudioPath = resumedPath,
                ended = true))
        }
        instrumentation.waitForIdleSync()
        assertEquals("the fourth row follows the third", 3, state().queueIndex)
    }

    // Deleting one copy of a media keeps the bookmark; deleting the last copy purges it.
    @Test fun deletingADuplicateKeepsTheMediaBookmarkUntilTheLastCopyGoes() {
        seedRepeatedAdds()
        val queue = state().queue
        val mediaKey = keyOf(audioA)
        val remembered = PlaybackBookmark(mediaKey, positionMs = 7_000L, durationMs = 60_000L, completed = false)
        main { PlaybackBookmarkStore(context).write(mapOf(mediaKey to remembered)) }

        // A fresh controller loads the saved bookmark, then the copies are deleted one at a time.
        clearController()
        startController()
        assertEquals(4, state().queue.size)
        // A, B, A, A: three copies of A. Only the final one may purge the media bookmark.
        assertEquals(3, state().queue.count { it.key == mediaKey })
        main { controller.onToggleQueueEditing() }

        repeat(2) { round ->
            main {
                controller.onToggleQueueSelection(state().queue.last { it.key == mediaKey }.entryId)
                controller.onDeleteQueueSelection()
            }
            instrumentation.waitForIdleSync()
            assertEquals("round ${round + 1}: the other copies remain", 2 - round, state().queue.count { it.key == mediaKey })
            assertEquals(
                "round ${round + 1}: a surviving copy still needs the media bookmark",
                7_000L,
                main { PlaybackBookmarkStore(context).read()[mediaKey] }?.positionMs,
            )
        }

        // Deleting the final copy of that media is what purges it.
        main {
            controller.onToggleQueueSelection(state().queue.single { it.key == mediaKey }.entryId)
            controller.onDeleteQueueSelection()
        }
        instrumentation.waitForIdleSync()

        assertTrue(state().queue.none { it.key == mediaKey })
        assertNull("the last copy's deletion purges the bookmark", main { PlaybackBookmarkStore(context).read()[mediaKey] })
    }

    /**
     * A natural end of the third row advances to the fourth, and ending the fourth stops instead of
     * rewinding to the first copy of the same media.
     */
    @Test fun naturalEndAdvancesPastADuplicateAndStopsAtTheEnd() {
        seedRepeatedAdds()
        val queue = state().queue
        val endedOf = { index: Int -> queue[index].entryId }

        assertEquals(
            "the third row advances to the fourth",
            QueueNaturalEndAction.PlayNext(3),
            queueNaturalEndAction(queue, endedOf(2), true),
        )
        assertEquals(
            "the last row stops instead of repeating the first copy",
            QueueNaturalEndAction.Stop,
            queueNaturalEndAction(queue, endedOf(3), true),
        )
        assertEquals(
            "the first row advances to the second, not to itself",
            QueueNaturalEndAction.PlayNext(1),
            queueNaturalEndAction(queue, endedOf(0), true),
        )
    }
}
