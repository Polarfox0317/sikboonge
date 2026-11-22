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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual controller/store/request integration, with isolated preferences and no audio or network. */
@RunWith(AndroidJUnit4::class)
class PlaybackContinuityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val requests = mutableListOf<Intent>()
    private val preferenceNames = mutableSetOf<String>()
    private val prefix = "continuity-test-${System.nanoTime()}-"
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableListOf<AppController>()
    private lateinit var audio: AudioResult
    private lateinit var fixture: File
    private val bot = BotVideo(31, 7L, 99L, "local test bot row", 4096L, 180, 1L)
    private lateinit var rows: List<QueueItemUi>
    private lateinit var saved: PlaybackQueueSnapshot

    @Before fun setup() {
        fixture = File(File(base.filesDir, "audio").apply { mkdirs() }, "continuity-${System.nanoTime()}.wav")
        fixture.writeBytes(byteArrayOf(1, 2, 3, 4)) // never opened by a real player
        audio = AudioResult(fixture.path, fixture.name, "audio/wav", fixture.length(), 1L)
        rows = listOf(
            QueueItemUi(key = "local:${fixture.canonicalPath}", title = audio.name,
                sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path),
            QueueItemUi(key = "bot:${bot.chatId}:${bot.messageId}", title = bot.name,
                sourceKind = PlaybackSource.BOT, botChatId = bot.chatId,
                botMessageId = bot.messageId, botFileId = bot.fileId),
        )
        saved = PlaybackQueueSnapshot(
            rows = rows.map { QueueRow(it.key, it.title, it.sourceKind,
                localAudioPath = it.localAudioPath, botChatId = it.botChatId,
                botMessageId = it.botMessageId, botFileId = it.botFileId, entryId = it.entryId) },
            currentKey = rows[0].key, resumeKey = rows[0].key, pausedKey = rows[0].key,
            pausedPositionMs = 42_000L, pausedDurationMs = 180_000L,
            playerExpanded = true, emptyPlayerVisible = false,
        )
        PlaybackQueueStore(context).write(saved)
        PlaybackBookmarkStore(context).write(rows.associate {
            it.key to PlaybackBookmark(it.key, 42_000L, 180_000L, completed = false)
        })
        main { StreamPlayback.publish(StreamingState()) }
    }

    @After fun cleanup() {
        main {
            controllers.forEach { PlaybackQueueTransport.unbind(it) }
            scope.cancel()
            StreamPlayback.publish(StreamingState())
        }
        preferenceNames.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        fixture.delete()
    }

    private fun create(): AppController = main {
        AppController(context, scope,
            botState = MutableStateFlow(BotUiState(initialStateLoaded = true, connected = true, videos = listOf(bot))),
            libraryState = MutableStateFlow(ConversionState()),
            libraryHistory = MutableStateFlow(listOf(audio)),
        ).also { controllers += it }
    }.also { instrumentation.waitForIdleSync() }

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    private fun state(controller: AppController): AppUiState {
        instrumentation.waitForIdleSync()
        return main { controller.uiState.value }
    }

    @Test fun coldRestoreShowsPausedMiniAndStartsNothing() {
        val controller = create()
        val restored = state(controller)
        assertEquals(rows.map { it.key }, restored.queue.map { it.key })
        assertFalse(restored.playerExpanded)
        assertFalse(restored.stream.playing)
        assertTrue(restored.playerDisplayStream.ready)
        assertEquals(42_000L, restored.playerDisplayStream.positionMs)
        assertEquals(180_000L, restored.playerDisplayStream.durationMs)
        assertTrue(requests.isEmpty())
    }

    @Test fun bundleCannotReexpandAColdRestoredPlayer() {
        val controller = create()
        val bundle = main { controller.saveRestorableState().apply { putBoolean("playerExpanded", true) } }
        main { controller.restoreRestorableState(bundle) }
        assertFalse(state(controller).playerExpanded)
        assertTrue(requests.isEmpty())
    }

    @Test fun restoredMiniResumesItsBookmarkWithoutAskingOrReplacingTheQueue() {
        val controller = create()
        main { controller.onResume() }
        // The mini player never asks; the ask dialog belongs to a Bot/Library list card play.
        assertNull(state(controller).resumePrompt)
        assertEquals(rows.map { it.key }, state(controller).queue.map { it.key })
        assertEquals(1, requests.size)
        assertEquals(42_000L, requests.single().getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
        assertEquals(StreamingAudioService.ACTION_PLAY_LOCAL, requests.single().action)
    }

    @Test fun cancellingTheQuestionLeavesTheQueueAndBookmarksUntouched() {
        val controller = create()
        val before = PlaybackBookmarkStore(context).read()
        main { controller.onPlayAudio(audio) }
        assertNotNull(state(controller).resumePrompt)
        main { controller.onDismissResumePrompt() }
        assertNull(state(controller).resumePrompt)
        assertEquals(rows.map { it.key }, state(controller).queue.map { it.key })
        assertEquals(before, PlaybackBookmarkStore(context).read())
        assertTrue(requests.isEmpty())
    }

    @Test fun botChoiceDoesNotSendTheStaleHandleDirectlyToTheService() {
        val controller = create()
        main { controller.onPlayBotVideo(bot) }
        assertNotNull(state(controller).resumePrompt)
        assertTrue(requests.isEmpty())
        main { controller.onResumeFromBookmark() }
        // No live TDLib connection exists here: resolve first, never send the stale row handle.
        assertTrue(requests.none { it.action == BotRuntime.ACTION_PLAY_TDLIB })
    }

    @Test fun restartingFromTheQuestionStartsAtZero() {
        val controller = create()
        main { controller.onPlayAudio(audio); controller.onRestartFromBeginning() }
        state(controller)
        assertEquals(1, requests.size)
        assertEquals(0L, requests.single().getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
        assertEquals(0L, PlaybackBookmarkStore(context).read().getValue(rows[0].key).positionMs)
    }

    @Test fun preparingStateCannotEraseTheBookmark() {
        val controller = create()
        main { StreamPlayback.publish(StreamingState(source = PlaybackSource.LOCAL_AUDIO,
            localAudioPath = audio.path, resolving = true, buffering = true)) }
        state(controller)
        assertEquals(42_000L, PlaybackBookmarkStore(context).read().getValue(rows[0].key).positionMs)
    }

    @Test fun playerCloseClearsTheQueueAndItsPausedBookmark() {
        val controller = create()
        main { controller.onStop() }
        state(controller)
        assertNull(PlaybackQueueStore(context).read())
        assertTrue(state(controller).queue.isEmpty())
        assertNull(PlaybackBookmarkStore(context).read()[rows[0].key])
        assertEquals(PlaybackSource.NONE, StreamPlayback.state.value.source)
    }

    @Test fun pausingTheSecondItemRestoresThatItemAndItsTimeAfterRecreation() {
        val controller = create()
        main { StreamPlayback.publish(StreamingState(source = PlaybackSource.BOT,
            botFileId = bot.fileId, title = bot.name, ready = true,
            positionMs = 56_000L, durationMs = 180_000L)) }
        state(controller)
        main { controller.onPause() }
        state(controller)
        val checkpoint = PlaybackQueueStore(context).read()!!
        assertEquals(rows[1].key, checkpoint.pausedKey)
        assertEquals(56_000L, checkpoint.pausedPositionMs)
        assertEquals(180_000L, checkpoint.pausedDurationMs)
        val recreated = main {
            // Cancel only this controller's parent; a fresh controller gets a new owner scope.
            PlaybackQueueTransport.unbind(controller)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.cancel() }
            StreamPlayback.publish(StreamingState())
        }.let { create() }
        val restored = state(recreated)
        assertEquals(rows.map { it.key }, restored.queue.map { it.key })
        assertEquals(rows[1].title, restored.playerDisplayStream.title)
        assertEquals(56_000L, restored.playerDisplayStream.positionMs)
        assertFalse(restored.playerExpanded)
    }

    @Test fun naturalEndIsRememberedBeforeTheQueueStops() {
        val controller = create()
        main { StreamPlayback.publish(StreamingState(source = PlaybackSource.BOT,
            botFileId = bot.fileId, title = bot.name, ready = true,
            positionMs = 179_000L, durationMs = 180_000L)) }
        state(controller)
        main { StreamPlayback.publish(StreamPlayback.state.value.copy(
            positionMs = 180_000L, ended = true)) }
        state(controller)
        assertTrue(PlaybackBookmarkStore(context).read().getValue(rows[1].key).completed)
    }

    @Test fun systemStopInsideTheThrottleWindowFlushesTheNewestPosition() {
        val controller = create()
        main { StreamPlayback.publish(StreamingState(source = PlaybackSource.LOCAL_AUDIO,
            localAudioPath = audio.path, ready = true, playing = true,
            positionMs = 2_000L, durationMs = 180_000L)) }
        state(controller)
        main { StreamPlayback.publish(StreamPlayback.state.value.copy(positionMs = 3_000L)) }
        state(controller)
        main { StreamPlayback.publish(StreamingState()) }
        state(controller)
        assertEquals(3_000L, PlaybackBookmarkStore(context).read().getValue(rows[0].key).positionMs)
    }

    @Test fun coldRestoreUsesTheBookmarkIncludingAnExplicitRestartAtZero() {
        PlaybackBookmarkStore(context).write(mapOf(rows[0].key to
            PlaybackBookmark(rows[0].key, 8_000L, 180_000L, completed = false)))
        val controller = create()
        assertEquals(8_000L, state(controller).playerDisplayStream.positionMs)
        main {
            PlaybackQueueTransport.unbind(controller)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.cancel() }
            StreamPlayback.publish(StreamingState())
        }
        PlaybackQueueStore(context).write(saved)
        PlaybackBookmarkStore(context).write(mapOf(rows[0].key to
            PlaybackBookmark(rows[0].key, 0L, 180_000L, completed = false)))
        val restarted = create()
        assertEquals(0L, state(restarted).playerDisplayStream.positionMs)
        assertFalse(StreamPlayback.state.value.playing)
        assertTrue(requests.isEmpty())
    }
}
