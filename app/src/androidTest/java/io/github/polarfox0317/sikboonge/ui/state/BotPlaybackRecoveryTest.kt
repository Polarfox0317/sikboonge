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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller routing with a deterministic Telegram boundary, never a live account. */
@RunWith(AndroidJUnit4::class)
class BotPlaybackRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val prefix = "bot-recovery-${System.nanoTime()}-"
    private val preferences = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferences += prefix + name
            return base.getSharedPreferences(prefix + name, mode)
        }
        override fun startService(intent: Intent) = ComponentName(base, StreamingAudioService::class.java)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val first = BotVideo(31, 7L, 42L, "first", 4096L, 180, 1L)
    private val second = BotVideo(32, 7L, 99L, "second", 4096L, 180, 1L)
    private val bot = MutableStateFlow(BotUiState(
        setupComplete = true, paired = true, initialStateLoaded = true,
        connected = false, videos = listOf(first, second),
    ))
    private lateinit var controller: AppController
    private val notices = mutableListOf<String>()
    private val fake = FakeRecovery()

    private inner class FakeRecovery : BotPlaybackRecovery {
        var authenticated = false
        var client = false
        var connecting = false
        var reconnects = 0
        var unavailable = false
        val starts = mutableListOf<Pair<BotVideo, Long>>()
        override fun isAuthenticated() = authenticated
        override fun hasClient() = client
        override fun isReconnecting() = connecting
        override fun reconnect(context: Context): ReconnectStart {
            reconnects++
            if (unavailable) return ReconnectStart.UNAVAILABLE
            connecting = true
            bot.value = bot.value.copy(busy = true)
            return ReconnectStart.STARTED
        }
        override fun play(context: Context, video: BotVideo, positionMs: Long) {
            starts += video to positionMs
            StreamPlayback.publish(StreamingState(source = PlaybackSource.BOT, botFileId = video.fileId,
                ready = true, playing = true, positionMs = positionMs, durationMs = 180_000L))
        }
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    @Before fun prepare() {
        val rows = listOf(first, second).map {
            QueueRow("bot:${it.chatId}:${it.messageId}", it.name, PlaybackSource.BOT,
                botChatId = it.chatId, botMessageId = it.messageId, botFileId = it.fileId)
        }
        PlaybackQueueStore(context).write(PlaybackQueueSnapshot(rows = rows,
            currentKey = rows[0].key, resumeKey = rows[0].key, pausedKey = rows[0].key,
            pausedPositionMs = 42_000L, pausedDurationMs = 180_000L,
            playerExpanded = false, emptyPlayerVisible = false))
        main {
            StreamPlayback.publish(StreamingState())
            controller = AppController(context, scope, botState = bot,
                libraryState = MutableStateFlow(ConversionState()), libraryHistory = MutableStateFlow(emptyList()))
            controller.botPlaybackRecovery = fake
            scope.launch { controller.messages.collect { notices += it.text } }
        }
        instrumentation.waitForIdleSync()
        main { StreamPlayback.publish(StreamingState(source = PlaybackSource.BOT, botFileId = 31,
            positionMs = 42_000L, durationMs = 180_000L, error = "offline", suppressErrorToast = true)) }
        instrumentation.waitForIdleSync()
        notices.clear()
    }

    @After fun cleanup() {
        main {
            PlaybackQueueTransport.unbind(controller)
            scope.cancel()
            StreamPlayback.publish(StreamingState())
        }
        preferences.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun ready(videos: List<BotVideo> = bot.value.videos) {
        main {
            fake.authenticated = true
            fake.client = true
            fake.connecting = false
            bot.value = bot.value.copy(connected = true, busy = false, videos = videos)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun retryReconnectsAndPlaysTheReissuedFileOnceAtItsPositionWithoutNotices() {
        main { controller.onRetryStream(); controller.onRetryStream() }
        assertEquals(1, fake.reconnects)
        assertTrue(StreamPlayback.state.value.resolving)
        ready(listOf(first.copy(fileId = 999), second))
        assertEquals(listOf(999 to 42_000L), fake.starts.map { it.first.fileId to it.second })
        assertEquals(1, fake.starts.size)
        assertTrue(notices.isEmpty())
    }

    @Test fun miniPlayerResumeRepairsTheConnectionWithoutAPrompt() {
        main { controller.onResume() }
        ready()
        assertEquals(42_000L, fake.starts.single().second)
        assertNull(main { controller.uiState.value.resumePrompt })
    }

    @Test fun retryCanReconnectBeforeTheInboxHasLoaded() {
        main {
            bot.value = bot.value.copy(videos = emptyList(), initialStateLoaded = false)
            controller.onRetryStream()
        }
        assertEquals(1, fake.reconnects)
        assertTrue(StreamPlayback.state.value.resolving)
        ready(listOf(first.copy(fileId = 999), second))
        assertEquals(listOf(999 to 42_000L), fake.starts.map { it.first.fileId to it.second })
    }

    @Test fun switchingTargetsWhileConnectingPlaysOnlyTheNewTarget() {
        main { controller.onRetryStream(); controller.onPlayBotVideo(second) }
        ready()
        assertEquals(1, fake.reconnects)
        assertEquals(listOf(99L to 0L), fake.starts.map { it.first.messageId to it.second })
    }

    @Test fun pauseThenReadyDoesNotAutoplay() {
        main { controller.onRetryStream(); controller.onPause() }
        assertTrue(StreamPlayback.state.value.ready)
        assertFalse(StreamPlayback.state.value.resolving)
        ready()
        assertTrue(fake.starts.isEmpty())
    }

    @Test fun aListedResumeChoiceAlsoRepairsAnOfflineConnection() {
        main { controller.onPlayBotVideo(first) }
        instrumentation.waitForIdleSync()
        assertNotNull(main { controller.uiState.value.resumePrompt })
        main { controller.onResumeFromBookmark() }
        ready()
        assertEquals(42_000L, fake.starts.single().second)
        assertTrue(notices.isEmpty())
    }

    @Test fun diagnosticsAndConnectionErrorsDuringRecoveryAreNotNotices() {
        main {
            controller.onRetryStream()
            bot.value = bot.value.copy(error = "internal connection failure", receiveDiagnostic = "internal retry")
        }
        instrumentation.waitForIdleSync()
        assertTrue(notices.isEmpty())
        main { controller.onPause() }
        assertTrue(StreamPlayback.state.value.ready)
    }

    @Test fun platformStopThenReadyDoesNotAutoplay() {
        main { controller.onRetryStream(); StreamPlayback.stop() }
        instrumentation.waitForIdleSync()
        ready()
        assertTrue(fake.starts.isEmpty())
    }

    @Test fun disposingTheControllerLeavesAPausedSessionInsteadOfAnOrphanedSpinner() {
        main {
            controller.onRetryStream()
            ViewModelStore().apply { put("controller", controller); clear() }
        }
        assertFalse(StreamPlayback.state.value.resolving)
        assertTrue(StreamPlayback.state.value.ready)
        assertTrue(StreamPlayback.state.value.serviceDetached)
        ready()
        assertTrue(fake.starts.isEmpty())
    }

    @Test fun deletingThePendingQueueRowPreventsLatePlayback() {
        main {
            controller.onRetryStream()
            controller.onToggleQueueEditing()
            // The queue keeps both restored rows, so the pending one is found by its stable media key
            // and its own occurrence id.
            val pendingRow = requireNotNull(
                controller.uiState.value.queue.firstOrNull { it.botMessageId == first.messageId && it.botChatId == first.chatId },
            ) { "expected the pending bot row in the queue" }
            controller.onToggleQueueSelection(pendingRow.entryId)
            controller.onDeleteQueueSelection()
        }
        ready()
        assertTrue(fake.starts.isEmpty())
    }

    @Test fun timedOutRequestClearsTheSpinnerAndCanBeRetriedSilently() {
        main { controller.botPlaybackWaitMs = 100L; controller.onRetryStream() }
        val deadline = System.currentTimeMillis() + 5_000L
        while (StreamPlayback.state.value.resolving && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        instrumentation.waitForIdleSync()
        assertFalse(StreamPlayback.state.value.resolving)
        assertNotNull(StreamPlayback.state.value.error)
        assertTrue(StreamPlayback.state.value.suppressErrorToast)
        assertTrue(notices.isEmpty())
        ready()
        assertTrue(fake.starts.isEmpty())
        main { controller.onRetryStream() }
        assertEquals(42_000L, fake.starts.single().second)
    }

    @Test fun unavailableReconnectEndsWithOnlyAnInlineError() {
        main { fake.unavailable = true; controller.onRetryStream() }
        instrumentation.waitForIdleSync()
        assertFalse(StreamPlayback.state.value.resolving)
        assertNotNull(StreamPlayback.state.value.error)
        assertTrue(notices.isEmpty())
    }
}
