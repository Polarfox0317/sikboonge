package io.github.polarfox0317.sikboonge.ui.state

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.github.polarfox0317.sikboonge.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed, reopen1 and reopen2 in separate instrumentation processes, with force-stop between. */
@RunWith(AndroidJUnit4::class)
class RepeatedProcessRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val runId = InstrumentationRegistry.getArguments().getString("restoreRunId")
        ?.also { require(it.matches(Regex("[A-Za-z0-9-]{1,48}"))) }
        ?: error("Pass a unique restoreRunId")
    private val phase = InstrumentationRegistry.getArguments().getString("restorePhase") ?: error("Pass restorePhase")
    private val prefix = "repeat-restore-$runId-"
    private val preferenceNames = setOf("playback_queue_session", "playback_bookmarks", "sikboonge_ui_preferences", "download_execution")
    private val touchedPreferences = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            touchedPreferences += name
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val first = File(File(base.filesDir, "audio"), "qa-repeat-$runId-a.wav")
    private val second = File(File(base.filesDir, "audio"), "qa-repeat-$runId-b.wav")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val botState = MutableStateFlow(BotUiState(initialStateLoaded = false))
    private var controller: AppController? = null
    private fun audio(file: File) = AudioResult(file.path, file.name, "audio/wav", file.length(), 1L)

    @Test fun pauseThenReopenTwiceWithoutPlaying() {
        if (phase == "cleanup") {
            (preferenceNames + touchedPreferences).forEach { base.getSharedPreferences(prefix + it, Context.MODE_PRIVATE).edit().clear().commit() }
            first.delete()
            second.delete()
            return
        }
        try {
            main { StreamPlayback.publish(StreamingState()) }
            if (phase == "seed") {
                assertFalse("Test run id already has a fixture", first.exists())
                first.parentFile!!.mkdirs()
                val result = FFmpegKit.executeWithArguments(arrayOf("-f", "lavfi", "-i", "sine=frequency=440:duration=60", "-c:a", "pcm_s16le", first.path))
                assertTrue(ReturnCode.isSuccess(result.returnCode))
                first.copyTo(second, overwrite = false)
            }
            assertTrue("Persistent test media missing", first.isFile && second.isFile)
            controller = main {
                AppController(context, scope, botState = botState,
                    libraryState = MutableStateFlow(ConversionState()),
                    libraryHistory = MutableStateFlow(listOf(audio(first), audio(second))))
            }
            if (phase == "seed") {
                main { controller!!.onPlayAudio(audio(first)); controller!!.onAddToQueue(audio(second)) }
                await("Generated media never played") { StreamPlayback.state.value.playing }
                main { controller!!.onSeek(17_000L) }
                await("Seek did not reach 17 seconds") { StreamPlayback.state.value.positionMs >= 17_000L }
                main { controller!!.onPause() }
                await("Pause did not settle") { !StreamPlayback.state.value.playing && StreamPlayback.state.value.ready }
            } else {
                require(phase == "reopen1" || phase == "reopen2")
                await("Player/queue did not restore") { main { controller!!.uiState.value.playerVisible && controller!!.uiState.value.queue.size == 2 } }
                assertFalse("Restore started playback", StreamPlayback.state.value.playing)
                assertFalse("Restore forced fullscreen", main { controller!!.uiState.value.playerExpanded })
                // A completed empty inbox load must not erase unrelated saved queue state.
                botState.value = BotUiState(initialStateLoaded = true, videos = emptyList())
            }
            val expectedKey = "local:${first.canonicalPath}"
            await("Durable current item or position was lost") {
                val saved = PlaybackQueueStore(context).read()
                saved?.rows?.size == 2 && saved.currentKey == expectedKey && saved.pausedKey == expectedKey &&
                    saved.pausedPositionMs >= 17_000L && saved.pausedDurationMs == 60_000L
            }
            instrumentation.waitForIdleSync()
            val saved = PlaybackQueueStore(context).read()!!
            assertEquals(listOf(expectedKey, "local:${second.canonicalPath}"), saved.rows.map { it.key })
            val bookmark = PlaybackBookmarkStore(context).read().getValue(expectedKey)
            assertTrue(bookmark.positionMs >= 17_000L)
            assertTrue(main { controller!!.uiState.value.playerVisible })
            if (phase != "seed") assertTrue(main { controller!!.uiState.value.playerDisplayStream.positionMs >= 17_000L })
            // Observe actual asynchronous disk completion; do not force a commit to mask writes.
            val encoded = PlaybackQueueSnapshotCodec.encode(saved)
            val disk = File(File(base.applicationInfo.dataDir, "shared_prefs"), prefix + "playback_queue_session.xml")
            await("Checkpoint was not written to disk") { runCatching { disk.readText().contains(encoded) }.getOrDefault(false) }
            println("REPEAT_RESTORE phase=$phase pid=${android.os.Process.myPid()} rows=${saved.rows.size} currentKeyPreserved=true positionMs=${saved.pausedPositionMs} durationMs=${saved.pausedDurationMs} playing=${StreamPlayback.state.value.playing}")
        } finally {
            main {
                controller?.let { PlaybackQueueTransport.unbind(it) }
                scope.cancel()
            }
            if (phase == "seed") {
                // The controller has finished saving the pause; release only this test's service.
                main { StreamPlayback.stop(); base.stopService(Intent(base, StreamingAudioService::class.java)) }
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 12_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("$message; snapshot=${PlaybackQueueStore(context).read()?.let { "rows=${it.rows.size}, current=${it.currentKey}, paused=${it.pausedKey}" }}")
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
