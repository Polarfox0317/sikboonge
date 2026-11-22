package io.github.polarfox0317.sikboonge

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.github.polarfox0317.sikboonge.ui.state.AppController
import io.github.polarfox0317.sikboonge.ui.state.PlaybackBookmark
import io.github.polarfox0317.sikboonge.ui.state.PlaybackBookmarkStore
import io.github.polarfox0317.sikboonge.ui.state.PlaybackQueueSnapshot
import io.github.polarfox0317.sikboonge.ui.state.PlaybackQueueStore
import io.github.polarfox0317.sikboonge.ui.state.QueueItemUi
import io.github.polarfox0317.sikboonge.ui.state.QueueRow
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ask-dialog choice and the mini player resume on the REAL [StreamingAudioService] and player,
 * driven through the real [AppController].
 *
 * Only preferences are isolated (a unique namespace on a delegating context wrapper); `startService`
 * reaches the actual service, so these cases check player state and the saved position instead of
 * only asserting request intent. A local WAV fixture keeps the run offline. The bot resolve path needs a
 * live TDLib client and is not covered here.
 */
@RunWith(AndroidJUnit4::class)
class ResumeRealServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val preferenceNames = mutableSetOf<String>()
    private val prefix = "resume-real-${System.nanoTime()}-"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            preferenceNames += isolated
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableListOf<AppController>()
    private var fixture: File? = null
    private var seededAudio: AudioResult? = null

    @Before fun setup() {
        stopServiceFully()
        main { StreamPlayback.publish(StreamingState()) }
    }

    @After fun cleanup() {
        main {
            controllers.forEach { PlaybackQueueTransport.unbind(it) }
            scope.cancel()
            StreamPlayback.publish(StreamingState())
        }
        stopServiceFully()
        preferenceNames.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        fixture?.delete()
        fixture = null
    }

    /** A list-card tap asks, then the confirmed choice reaches real playback near the saved spot. */
    @Test fun continueChoicePlaysAtTheSavedPosition() {
        val audio = seedLibraryItem(savedPositionMs = 20_000L)
        val controller = create()
        main { controller.onPlayAudio(audio) }
        assertNotNull("A list-card tap must ask", state(controller).resumePrompt)

        main { controller.onResumeFromBookmark() }
        val playing = await("Continue did not start real playback") {
            state.error != null || (state.playing && state.ready)
        }
        assertNull("Continue failed: ${playing.error}", playing.error)
        await("Continue did not reach the saved position near 20 s") {
            state.positionMs >= 19_000L || state.error != null
        }
        assertNull("Playback failed after starting: ${state.error}", state.error)
        assertTrue("Session is not playing", state.playing)
        assertTrue("Continue landed at ${state.positionMs}, not near 20 s", state.positionMs in 19_000L..25_000L)
    }

    /** The mini player resumes the paused item through the real controller, with no dialog. */
    @Test fun miniResumesThePausedItemWithoutAsking() {
        val audio = seedLibraryItem(savedPositionMs = 20_000L)
        val controller = create()
        main { controller.onResume() }
        assertNull("The mini player must not ask", state(controller).resumePrompt)
        val playing = await("The mini player did not start real playback") {
            state.error != null || (state.playing && state.ready)
        }
        assertNull("Mini resume failed: ${playing.error}", playing.error)
        assertTrue("Mini resume did not play", state.playing)
        assertEquals(File(audio.path).canonicalPath, state.localAudioPath?.let { File(it).canonicalPath })
    }

    private val state get() = StreamPlayback.state.value

    /**
     * Writes an owned audio file plus the queue and bookmark rows pointing at it, then returns its
     * [AudioResult]. Only this test's preferences and fixture files are touched.
     */
    private fun seedLibraryItem(savedPositionMs: Long): AudioResult {
        val audioDirectory = File(base.filesDir, "audio").apply { mkdirs() }
        val file = File(audioDirectory, "qa-resume-${System.nanoTime()}.wav")
        val result = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
            "-c:a", "pcm_s16le", file.absolutePath,
        ))
        check(ReturnCode.isSuccess(result.returnCode)) { "Could not generate the resume fixture" }
        fixture = file
        val audio = AudioResult(file.absolutePath, file.name, "audio/wav", file.length(), 1L)
        seededAudio = audio
        val rows = listOf(QueueItemUi(
            key = "local:${file.canonicalPath}", title = audio.name,
            sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path,
        ))
        PlaybackQueueStore(context).write(PlaybackQueueSnapshot(
            rows = rows.map { QueueRow(it.key, it.title, it.sourceKind, localAudioPath = it.localAudioPath) },
            currentKey = rows[0].key, resumeKey = rows[0].key, pausedKey = rows[0].key,
            pausedPositionMs = savedPositionMs, pausedDurationMs = 60_000L,
            playerExpanded = false, emptyPlayerVisible = false,
        ))
        PlaybackBookmarkStore(context).write(rows.associate {
            it.key to PlaybackBookmark(it.key, savedPositionMs, 60_000L, completed = false)
        })
        return audio
    }

    private fun create(): AppController = main {
        AppController(context, scope,
            botState = MutableStateFlow(BotUiState(initialStateLoaded = true, connected = true)),
            libraryState = MutableStateFlow(ConversionState()),
            libraryHistory = MutableStateFlow(listOfNotNull(seededAudio)),
        ).also { controllers += it }
    }.also { instrumentation.waitForIdleSync() }

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun state(controller: AppController): io.github.polarfox0317.sikboonge.ui.state.AppUiState {
        instrumentation.waitForIdleSync()
        return main { controller.uiState.value }
    }

    private fun await(message: String, condition: () -> Boolean): StreamingState {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return StreamPlayback.state.value
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun stopServiceFully() {
        instrumentation.runOnMainSync {
            StreamPlayback.stop()
            base.stopService(Intent(base, StreamingAudioService::class.java))
        }
        @Suppress("DEPRECATION")
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val running = runCatching {
                base.getSystemService(ActivityManager::class.java)
                    .getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == StreamingAudioService::class.java.name }
            }.getOrDefault(true)
            if (!running) return
            Thread.sleep(100)
        }
    }

    private companion object {
        const val TIMEOUT_NANOS = 30_000_000_000L
    }
}
