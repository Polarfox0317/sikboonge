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

/**
 * Resume routing for the ask-dialog choice and the mini player, at the controller/store boundary.
 *
 * These cases assert which path a play takes (resolve first for bot rows, direct request for local
 * audio) and that the position rides inside the start request. They do NOT claim audible playback:
 * that needs the real service and is covered by [ResumeRealServiceTest].
 */
@RunWith(AndroidJUnit4::class)
class ResumeRoutingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val requests = mutableListOf<Intent>()
    private val preferenceNames = mutableSetOf<String>()
    private val prefix = "resume-routing-${System.nanoTime()}-"
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
    private val bot = BotVideo(31, 7L, 99L, "bot row", 4096L, 180, 1L)
    private lateinit var rows: List<QueueItemUi>

    @Before fun setup() {
        fixture = File(File(base.filesDir, "audio").apply { mkdirs() }, "routing-${System.nanoTime()}.wav")
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
            rows = rows.map { QueueRow(it.key, it.title, it.sourceKind,
                localAudioPath = it.localAudioPath, botChatId = it.botChatId,
                botMessageId = it.botMessageId, botFileId = it.botFileId, entryId = it.entryId) },
            currentKey = rows[0].key, resumeKey = rows[0].key, pausedKey = rows[0].key,
            pausedPositionMs = 42_000L, pausedDurationMs = 180_000L,
            playerExpanded = false, emptyPlayerVisible = false,
        ))
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

    /** A bot row resumes through the resolve path, never the direct service request. */
    @Test fun botChoiceReopensThroughTheResolvePathNotTheDirectRequest() {
        val controller = create()
        main { controller.onPlayBotVideo(bot) }
        assertNotNull("A list-card play still asks", state(controller).resumePrompt)
        main { controller.onResumeFromBookmark() }
        instrumentation.waitForIdleSync()
        // The direct resume request names the saved file id; the resolve path must not be skipped
        // by sending it. Without a live TDLib client nothing is started, and that is the point:
        // the stale id is never handed to the service.
        assertTrue(
            "The confirmed choice went straight to the service with the saved file id: " +
                requests.map { "${it.action}" },
            requests.none { it.action == BotRuntime.ACTION_PLAY_TDLIB },
        )
    }

    /** The local choice still uses the direct request, carrying the bookmark inside it. */
    @Test fun localChoiceSendsTheBookmarkInsideTheStartRequest() {
        val controller = create()
        main { controller.onPlayAudio(audio) }
        assertNotNull(state(controller).resumePrompt)
        main { controller.onResumeFromBookmark() }
        assertEquals(1, requests.size)
        assertEquals(StreamingAudioService.ACTION_PLAY_LOCAL, requests.single().action)
        assertEquals(42_000L, requests.single().getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
    }

    /** Choosing "start over" sends position 0 and clears the saved spot. */
    @Test fun restartChoiceSendsZeroAndClearsTheBookmark() {
        val controller = create()
        main { controller.onPlayAudio(audio) }
        main { controller.onRestartFromBeginning() }
        assertEquals(0L, requests.single().getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
        assertEquals(0L, PlaybackBookmarkStore(context).read().getValue(rows[0].key).positionMs)
    }

    /** The mini player resumes silently at its saved position, with no dialog. */
    @Test fun miniResumesTheLocalItemWithoutAsking() {
        val controller = create()
        main { controller.onResume() }
        assertNull("The mini player must not ask", state(controller).resumePrompt)
        assertEquals(1, requests.size)
        assertEquals(StreamingAudioService.ACTION_PLAY_LOCAL, requests.single().action)
        assertEquals(42_000L, requests.single().getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
    }

    /** A queue row resumes silently at its saved spot, carried by the request itself. */
    @Test fun queueRowResumesAtItsBookmarkWithoutAsking() {
        val controller = create()
        main { controller.onPlayQueueItem(0) }
        assertNull("A queue row must not ask", state(controller).resumePrompt)
        assertEquals(1, requests.size)
        assertEquals(StreamingAudioService.ACTION_PLAY_LOCAL, requests.single().action)
        assertEquals(
            "A queue row must resume at the saved spot, not 0 s",
            42_000L,
            requests.single().getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L),
        )
    }

    /** "Next" is a user move, so it reopens the target at its saved spot, never 0 s. */
    @Test fun nextResumesTheTargetAtItsBookmark() {
        val secondFile = File(File(base.filesDir, "audio").apply { mkdirs() }, "routing-n-${System.nanoTime()}.wav")
        secondFile.writeBytes(byteArrayOf(5, 6, 7, 8))
        try {
            val secondAudio = secondFile.toAudioResult()
            val twoRows = listOf(
                QueueItemUi(key = "local:${fixture.canonicalPath}", title = "a",
                    sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path),
                QueueItemUi(key = "local:${secondFile.canonicalPath}", title = "b",
                    sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = secondAudio.path),
            )
            PlaybackQueueStore(context).write(PlaybackQueueSnapshot(
                rows = twoRows.map { QueueRow(it.key, it.title, it.sourceKind, localAudioPath = it.localAudioPath) },
                currentKey = twoRows[0].key, resumeKey = twoRows[0].key, pausedKey = twoRows[0].key,
                pausedPositionMs = 42_000L, pausedDurationMs = 60_000L,
                playerExpanded = false, emptyPlayerVisible = false,
            ))
            PlaybackBookmarkStore(context).write(twoRows.associate {
                it.key to PlaybackBookmark(it.key, 42_000L, 60_000L, completed = false)
            })
            val controller = main {
                AppController(context, scope,
                    botState = MutableStateFlow(BotUiState(initialStateLoaded = true, connected = true)),
                    libraryState = MutableStateFlow(ConversionState()),
                    libraryHistory = MutableStateFlow(listOf(audio, secondAudio)),
                ).also { controllers += it }
            }.also { instrumentation.waitForIdleSync() }

            main { controller.onPlayNext() }
            assertNull("Next must not ask", state(controller).resumePrompt)
            val nextRequest = requests.lastOrNull { it.action == StreamingAudioService.ACTION_PLAY_LOCAL }
            assertNotNull("Next did not request the target row: ${requests.map { it.action }}", nextRequest)
            assertEquals(
                "Next must resume at the target's saved spot",
                42_000L,
                nextRequest!!.getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L),
            )
        } finally {
            secondFile.delete()
        }
    }

    /** Automatic advancement after a natural end keeps starting the successor at 0 s. */
    @Test fun automaticAdvancementStillStartsTheNextItemAtZero() {
        // Two local rows, each with a saved spot; the second must still start at 0 after auto-next.
        val secondFile = File(File(base.filesDir, "audio").apply { mkdirs() }, "routing-b-${System.nanoTime()}.wav")
        secondFile.writeBytes(byteArrayOf(5, 6, 7, 8))
        try {
            val secondAudio = secondFile.toAudioResult()
            val twoRows = listOf(
                QueueItemUi(key = "local:${fixture.canonicalPath}", title = "a",
                    sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path),
                QueueItemUi(key = "local:${secondFile.canonicalPath}", title = "b",
                    sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = secondAudio.path),
            )
            PlaybackQueueStore(context).write(PlaybackQueueSnapshot(
                rows = twoRows.map { QueueRow(it.key, it.title, it.sourceKind, localAudioPath = it.localAudioPath) },
                currentKey = twoRows[0].key, resumeKey = twoRows[0].key, pausedKey = twoRows[0].key,
                pausedPositionMs = 42_000L, pausedDurationMs = 60_000L,
                playerExpanded = false, emptyPlayerVisible = false,
            ))
            PlaybackBookmarkStore(context).write(twoRows.associate {
                it.key to PlaybackBookmark(it.key, 42_000L, 60_000L, completed = false)
            })
            val controller = main {
                AppController(context, scope,
                    botState = MutableStateFlow(BotUiState(initialStateLoaded = true, connected = true)),
                    libraryState = MutableStateFlow(ConversionState()),
                    libraryHistory = MutableStateFlow(listOf(audio, secondAudio)),
                ).also { controllers += it }
            }.also { instrumentation.waitForIdleSync() }

            // Row 0 plays, then ends naturally.
            main { StreamPlayback.publish(StreamingState(
                source = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path,
                ready = true, playing = true, positionMs = 1_000L, durationMs = 60_000L)) }
            state(controller)
            requests.clear()
            main { StreamPlayback.publish(StreamPlayback.state.value.copy(
                positionMs = 60_000L, ended = true)) }
            state(controller)

            val autoRequest = requests.lastOrNull { it.action == StreamingAudioService.ACTION_PLAY_LOCAL }
            assertNotNull("Auto-advance did not start the next row: ${requests.map { it.action }}", autoRequest)
            assertEquals(
                "Automatic advancement must start at 0 s, not the saved spot",
                -1L,
                autoRequest!!.getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L),
            )
        } finally {
            secondFile.delete()
        }
    }

    private fun File.toAudioResult() = AudioResult(
        path = absolutePath, name = name, mime = "audio/wav",
        bytes = length(), date = System.currentTimeMillis(),
    )
}
