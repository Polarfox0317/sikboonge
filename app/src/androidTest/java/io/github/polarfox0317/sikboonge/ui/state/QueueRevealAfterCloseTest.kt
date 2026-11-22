package io.github.polarfox0317.sikboonge.ui.state

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
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
 * Adding to the queue while the player is closed must show the kept queue as a paused mini player.
 *
 * Closing the player keeps the queue but hides it; before the fix, "add to queue" only revealed the
 * mini player when the queue was empty, so the entry point never came back. These run the real
 * controller with isolated preferences and owned fixture files, and count service requests instead of
 * starting playback.
 */
@RunWith(AndroidJUnit4::class)
class QueueRevealAfterCloseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val prefix = "queue-reveal-${System.nanoTime()}-"
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bot = MutableStateFlow(BotUiState())
    private val created = mutableListOf<File>()
    private lateinit var controller: AppController
    private lateinit var history: MutableStateFlow<List<AudioResult>>

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    /**
     * Writes a real audio file the runtime can resolve. The queue looks its result up by canonical
     * path, so the file and the injected history entry must describe the same absolute location.
     */
    private fun fixtureAudio(name: String): AudioResult {
        val dir = File(base.filesDir, "audio").apply { mkdirs() }
        val file = File(dir, "$prefix$name.mp3").apply { writeBytes(ByteArray(16)) }
        created += file
        return AudioResult(
            path = file.absolutePath, name = name, mime = "audio/mpeg", bytes = 16L, date = 1L,
        )
    }

    private fun keyOf(audio: AudioResult): String = "local:${File(audio.path).canonicalPath}"

    private fun state() = main { controller.uiState.value }

    private fun serviceIdle() = serviceStarts.isEmpty() &&
        StreamPlayback.state.value.source == PlaybackSource.NONE

    /** The mini player is showing only when nothing is live and the reveal flag is set. */
    private fun miniVisible(): Boolean = serviceIdle() && state().emptyPlayerVisible

    /**
     * Publishes a live LOCAL source and waits for it to settle.
     *
     * The controller expands the player on a source change, so a test that measures the add must set
     * its size only after this returns.
     */
    private fun publishLivePlayer() {
        main {
            StreamPlayback.publish(StreamingState(
                source = PlaybackSource.LOCAL_AUDIO, ready = true, playing = true,
                positionMs = 1_000L, localAudioPath = history.value[0].path,
            ))
        }
        instrumentation.waitForIdleSync()
    }

    @Before fun prepare() {
        serviceStarts.clear()
        history = MutableStateFlow(listOf(fixtureAudio("a"), fixtureAudio("b"), fixtureAudio("c")))
        main {
            StreamPlayback.publish(StreamingState())
            controller = AppController(context, scope, botState = bot,
                libraryState = MutableStateFlow(ConversionState()), libraryHistory = history)
        }
        instrumentation.waitForIdleSync()
    }

    @After fun cleanup() {
        main {
            PlaybackQueueTransport.unbind(controller)
            scope.cancel()
            StreamPlayback.publish(StreamingState())
        }
        preferences.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        // Only the files this test created are removed.
        created.forEach { it.delete() }
    }

    /**
     * Builds a two-entry queue through the real add path, makes the second row current through the
     * real queue selection, then explicitly closes the player. The close deletes the playback session:
     * the queue, current row and resume point go with it.
     */
    private fun seedQueueThenClose() {
        main {
            controller.onAddToQueue(history.value[0])
            controller.onAddToQueue(history.value[1])
        }
        instrumentation.waitForIdleSync()
        main { controller.onPlayQueueItem(1) }
        instrumentation.waitForIdleSync()
        val seeded = state()
        assertEquals("both rows must still be queued", 2, seeded.queue.size)
        assertEquals(1, seeded.queueIndex)

        // The close stops playback synchronously and clears the session.
        main { controller.onStop() }
        instrumentation.waitForIdleSync()
        serviceStarts.clear()
        assertEquals(PlaybackSource.NONE, StreamPlayback.state.value.source)
    }

    /**
     * Builds a queue and only pauses: no explicit close, so the session must survive for the
     * pause / returning-process contract.
     */
    private fun seedQueueThenPause() {
        main {
            controller.onAddToQueue(history.value[0])
            controller.onAddToQueue(history.value[1])
        }
        instrumentation.waitForIdleSync()
        main {
            controller.onPlayQueueItem(1)
            controller.onPause()
        }
        instrumentation.waitForIdleSync()
        serviceStarts.clear()
    }

    // A close deletes the session; adding one item afterwards shows that item alone, paused.
    @Test fun addingAfterACloseShowsOnlyTheNewItem() {
        seedQueueThenClose()
        val closed = state()
        assertFalse("the close must leave the player hidden", miniVisible())
        assertTrue("the close deletes the queue", closed.queue.isEmpty())
        assertEquals(-1, closed.queueIndex)
        assertNull("the close drops the paused row", PlaybackQueueStore(context).read())

        main { controller.onAddToQueue(history.value[2]) }
        instrumentation.waitForIdleSync()

        val after = state()
        assertTrue("adding must reveal the paused mini player", miniVisible())
        assertEquals("only the new item remains", listOf(keyOf(history.value[2])), after.queue.map { it.key })
        assertFalse(after.playerExpanded)
        assertTrue("adding must not start the service", serviceIdle())
    }

    // Re-adding a media that the close cleared starts a single fresh row, with no "already queued".
    @Test fun reAddingAClosedItemShowsASingleFreshRow() {
        seedQueueThenClose()
        main { controller.onAddToQueue(history.value[0]) }
        instrumentation.waitForIdleSync()

        val after = state()
        assertTrue(miniVisible())
        assertEquals(listOf(keyOf(history.value[0])), after.queue.map { it.key })
        assertEquals("the row is a new occurrence", 1, after.queue.map { it.entryId }.toSet().size)
        assertTrue(serviceIdle())
    }

    @Test fun playingAClosedItemFromItsCardRecordsANewPosition() {
        seedQueueThenClose()
        val audio = history.value[0]
        main { controller.onPlayAudio(audio) }
        instrumentation.waitForIdleSync()
        main {
            StreamPlayback.publish(StreamingState(source = PlaybackSource.LOCAL_AUDIO,
                localAudioPath = audio.path, ready = true, playing = false,
                positionMs = 7_000L, durationMs = 60_000L))
        }
        instrumentation.waitForIdleSync()
        assertEquals(7_000L, PlaybackBookmarkStore(context).read()[keyOf(audio)]?.positionMs)
        assertEquals(listOf(keyOf(audio)), state().queue.map { it.key })
    }

    // A pause is not a close: the queue and its position survive for the returning-process path.
    @Test fun pausingKeepsTheQueueForReturn() {
        seedQueueThenPause()
        val paused = state()
        assertEquals("a pause keeps the queue", 2, paused.queue.size)
        assertNotNull("a pause keeps the persisted session", PlaybackQueueStore(context).read())
    }

    // A live, expanded, playing player keeps its own state; adding must not collapse or restart it.
    @Test fun aLiveExpandedPlayerKeepsItsState() {
        // Settle the live source first: the controller expands on a source change, so setting the size
        // before that would measure the source change instead of the add.
        publishLivePlayer()
        main { controller.setPlayerExpanded(true) }
        instrumentation.waitForIdleSync()
        assertTrue("precondition: the live player is expanded", state().playerExpanded)

        main { controller.onAddToQueue(history.value[0]) }
        instrumentation.waitForIdleSync()

        val ui = state()
        assertTrue("a live player stays expanded", ui.playerExpanded)
        assertFalse("a live player is not the empty reveal state", ui.emptyPlayerVisible)
        assertTrue(StreamPlayback.state.value.playing)
        assertTrue(serviceStarts.isEmpty())
    }

    // The same live player, collapsed, must stay collapsed instead of being forced either way.
    @Test fun aLiveCollapsedPlayerStaysCollapsed() {
        publishLivePlayer()
        main { controller.setPlayerExpanded(false) }
        instrumentation.waitForIdleSync()
        assertFalse("precondition: the live player is collapsed", state().playerExpanded)

        main { controller.onAddToQueue(history.value[0]) }
        instrumentation.waitForIdleSync()

        val ui = state()
        assertFalse("a live player keeps its collapsed size", ui.playerExpanded)
        assertFalse(ui.emptyPlayerVisible)
        assertTrue(StreamPlayback.state.value.playing)
    }

    // A stopped player that is already visible and expanded keeps that expansion.
    @Test fun aVisibleStoppedExpandedPlayerKeepsItsExpansion() {
        main {
            controller.onAddToQueue(history.value[0])
            controller.setPlayerExpanded(true)
        }
        instrumentation.waitForIdleSync()
        val before = state()
        assertTrue(before.emptyPlayerVisible)
        assertTrue(before.playerExpanded)

        main { controller.onAddToQueue(history.value[1]) }
        instrumentation.waitForIdleSync()

        val after = state()
        assertTrue("an already visible player stays visible", after.emptyPlayerVisible)
        assertTrue("already shown expansion is preserved", after.playerExpanded)
        assertEquals(2, after.queue.size)
        assertTrue(serviceIdle())
    }

    // The empty queue first-add keeps its documented meaning: a paused mini player, no playback.
    @Test fun theFirstItemOfAnEmptyQueueShowsThePausedMiniPlayer() {
        assertTrue(state().queue.isEmpty())
        main { controller.onAddToQueue(history.value[0]) }
        instrumentation.waitForIdleSync()

        val ui = state()
        assertTrue(miniVisible())
        assertEquals(listOf(keyOf(history.value[0])), ui.queue.map { it.key })
        assertFalse(ui.playerExpanded)
        assertTrue(serviceIdle())
    }
}
