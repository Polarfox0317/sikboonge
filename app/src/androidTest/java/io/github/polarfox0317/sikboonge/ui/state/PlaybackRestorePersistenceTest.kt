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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold starts and warm re-entries must preserve the same paused session.
 *
 * Seed → pause → dispose → restore (no playback) → dispose → restore again. The second restore is
 * where the regression appeared: the first restore publishes a paused shim with no service handles,
 * and a checkpoint writer that trusted the degraded source key (`bot:invalid`/`local:invalid`)
 * replaced the current row with nulls. Asserting only the row count would pass while the mini player
 * and queue entry are gone, so both rounds assert the visible player, the current row and playhead.
 *
 * A warm round covers the case where the Activity closes while the process survives: the leftover
 * paused shim must be adopted by a brand-new controller. Each controller owns its scope and is torn
 * down by cancelling the scope's children, which is what a real disposal does.
 *
 * No service is started and no audio plays: [startService] is recorded, not delivered.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRestorePersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val requests = mutableListOf<Intent>()
    private val preferenceNames = mutableSetOf<String>()
    private val prefix = "restore-persist-${System.nanoTime()}-"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            preferenceNames += isolated
            return base.getSharedPreferences(isolated, mode)
        }
        override fun startService(service: Intent): ComponentName {
            requests += Intent(service)
            return ComponentName(base, StreamingAudioService::class.java)
        }
    }
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var fixture: File
    private lateinit var audio: AudioResult
    private val bot = BotVideo(31, 7L, 99L, "bot row", 4096L, 180, 1L)
    private lateinit var rows: List<QueueItemUi>

    @Before fun setup() {
        fixture = File(File(base.filesDir, "audio").apply { mkdirs() }, "restore-${System.nanoTime()}.wav")
        fixture.writeBytes(byteArrayOf(1, 2, 3, 4))
        audio = AudioResult(fixture.path, fixture.name, "audio/wav", fixture.length(), 1L)
        rows = listOf(
            QueueItemUi(key = "local:${fixture.canonicalPath}", title = audio.name,
                sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path),
            QueueItemUi(key = "bot:${bot.chatId}:${bot.messageId}", title = bot.name,
                sourceKind = PlaybackSource.BOT, botChatId = bot.chatId,
                botMessageId = bot.messageId, botFileId = bot.fileId),
        )
        PlaybackQueueStore(context).write(PlaybackQueueSnapshot(
            rows = rows.map { it.toRow() },
            currentKey = rows[1].key, resumeKey = rows[1].key, pausedKey = rows[1].key,
            pausedPositionMs = 17_000L, pausedDurationMs = 180_000L,
            playerExpanded = false, emptyPlayerVisible = false,
        ))
        PlaybackBookmarkStore(context).write(rows.associate {
            it.key to PlaybackBookmark(it.key, 17_000L, 180_000L, completed = false)
        })
        main { StreamPlayback.publish(StreamingState()) }
    }

    @After fun cleanup() {
        main {
            scopes.forEach { it.cancel() }
            StreamPlayback.publish(StreamingState())
        }
        preferenceNames.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        fixture.delete()
    }

    /** Two cold starts keep the paused session, its current row and its playhead. */
    @Test fun twoColdStartsKeepThePausedSessionAndCurrentRow() {
        repeat(2) { round ->
            val controller = create()
            settle(controller)
            assertRestored(controller, "cold round $round")
            dispose(controller)
        }
        assertCheckpointSurvives("two cold starts")
    }

    /** A warm re-entry must adopt the paused shim a previous controller left in the stream. */
    @Test fun aWarmReentryAdoptsTheLeftoverPausedSession() {
        val first = create()
        settle(first)
        assertRestored(first, "first")
        // Dispose only the controller: the published paused shim stays in the process state, which
        // is exactly what an Activity close with a surviving process leaves behind.
        dispose(first)
        assertTrue("precondition: the paused shim must outlive its controller",
            StreamPlayback.state.value.source != PlaybackSource.NONE &&
                !StreamPlayback.state.value.playing)

        val second = create()
        settle(second)
        assertRestored(second, "warm re-entry")
        assertNoPlaybackStarted()
        dispose(second)
        assertCheckpointSurvives("a warm re-entry")
    }

    private fun assertRestored(controller: AppController, label: String) {
        assertNoPlaybackStarted()
        val stream = StreamPlayback.state.value
        assertTrue("$label: a paused session must be published, not a live one",
            stream.source != PlaybackSource.NONE && !stream.playing)

        val ui = main { controller.uiState.value }
        assertEquals("$label: both rows must survive", 2, ui.queue.size)
        assertEquals("$label: the current row must survive", rows[1].key,
            ui.queue.getOrNull(ui.pausedEntryIndex)?.key)
        // The mini player is what the user sees; the internal empty-player flag is incidental.
        assertTrue("$label: the player must be visible", ui.playerVisible)
        assertEquals("$label: the saved playhead must be shown", 17_000L, stream.positionMs)
    }

    private fun assertNoPlaybackStarted() =
        assertEquals("restoring must never start playback", emptyList<Intent>(), requests)

    private fun assertCheckpointSurvives(label: String, expectedKey: String = rows[1].key) {
        val saved = PlaybackQueueStore(context).read()
        assertNotNull("$label: the checkpoint must survive", saved)
        assertEquals("$label: both rows must survive", 2, saved!!.rows.size)
        assertEquals("$label: the current row must not be nulled", expectedKey, saved.currentKey)
        assertEquals("$label: the paused row must not be nulled", expectedKey, saved.pausedKey)
        assertEquals("$label: the playhead must survive", 17_000L, saved.pausedPositionMs)

        val bookmarks = PlaybackBookmarkStore(context).read()
        assertEquals("$label: both bookmarks must survive", 2, bookmarks.size)
        assertEquals("$label: the current bookmark must survive", 17_000L,
            bookmarks.getValue(expectedKey).positionMs)
        assertFalse("$label: a restore must not mark the item complete",
            bookmarks.getValue(expectedKey).completed)
    }

    /** A late empty owner emission must not purge rows that are still restoring. */
    @Test fun anEmptyOwnerEmissionDoesNotPurgeTheRestoringQueue() {
        val controller = create(botLoaded = true, botVideos = emptyList())
        settle(controller)
        assertNoPlaybackStarted()

        val ui = main { controller.uiState.value }
        assertEquals("a loaded-but-empty bot list must not delete its row", 2, ui.queue.size)
        dispose(controller)
        assertCheckpointSurvives("an empty owner emission")
    }

    /**
     * A checkpoint already damaged by the old writer holds its rows but null current/paused keys.
     * The very first launch after the fix must bring the session back, not one launch later.
     */
    @Test fun corruptedLegacyCheckpointWithRowsNullCurrentKeysReappears() {
        // Exactly what the old writer left: rows intact, keys nulled, mini player flag cleared.
        PlaybackQueueStore(context).write(PlaybackQueueSnapshot(
            rows = rows.map { it.toRow() },
            currentKey = null, resumeKey = rows[0].key, pausedKey = null,
            pausedPositionMs = 17_000L, pausedDurationMs = 180_000L,
            playerExpanded = false, emptyPlayerVisible = false,
        ))

        val controller = create()
        settle(controller)
        val stream = StreamPlayback.state.value
        val ui = main { controller.uiState.value }

        assertNoPlaybackStarted()
        assertEquals("the rows must still be there", 2, ui.queue.size)
        assertTrue("the first launch must show the player again", ui.playerVisible)
        assertTrue("a paused session must be published, not a live one",
            stream.source != PlaybackSource.NONE && !stream.playing)
        // The recovered current row is the saved resume row; its existing bookmark supplies the spot.
        assertEquals(rows[0].key, ui.queue.getOrNull(ui.pausedEntryIndex)?.key)
        assertEquals("the saved playhead must come back", 17_000L, stream.positionMs)

        dispose(controller)
        // The healed checkpoint keeps the recovered identity, so the next launch is normal again.
        assertCheckpointSurvives("a healed legacy checkpoint", expectedKey = rows[0].key)
    }

    private fun create(
        botLoaded: Boolean = true,
        botVideos: List<BotVideo> = listOf(bot),
    ): AppController {
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scopes += controllerScope
        return main {
            AppController(context, controllerScope,
                botState = MutableStateFlow(BotUiState(initialStateLoaded = botLoaded, connected = true, videos = botVideos)),
                libraryState = MutableStateFlow(ConversionState()),
                libraryHistory = MutableStateFlow(listOf(audio)),
            )
        }.also {
            instrumentation.waitForIdleSync()
            settle(it)
        }
    }

    /** Cancels the controller's own scope children, which is how a real disposal tears it down. */
    private fun dispose(controller: AppController) {
        main {
            val owner = scopes.lastOrNull()
            owner?.coroutineContext?.get(Job)?.children?.toList()?.forEach { it.cancel() }
            PlaybackQueueTransport.unbind(controller)
        }
        Thread.sleep(120)
        instrumentation.waitForIdleSync()
    }

    private fun settle(controller: AppController) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val stream = StreamPlayback.state.value
            val ui = main { controller.uiState.value }
            if (ui.queue.size == 2 && stream.source != PlaybackSource.NONE) break
            Thread.sleep(50)
        }
        // Give the persistence and bookmark collectors a turn after the restore state lands.
        repeat(6) {
            Thread.sleep(50)
            instrumentation.waitForIdleSync()
        }
    }

    private fun QueueItemUi.toRow() = QueueRow(key, title, sourceKind,
        localAudioPath = localAudioPath, botMessageId = botMessageId,
        botChatId = botChatId, botFileId = botFileId, entryId = entryId)

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private companion object {
        const val TIMEOUT_NANOS = 10_000_000_000L
    }
}
