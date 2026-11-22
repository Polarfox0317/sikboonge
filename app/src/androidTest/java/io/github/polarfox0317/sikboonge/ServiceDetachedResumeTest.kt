package io.github.polarfox0317.sikboonge

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device checks for the three resume boundaries: a service that ends without a user close, a cold
 * bot start position, and the service-only rebuild position. Uses the existing local WAV fixture
 * so no bot/TDlib connection is required; the bot path is covered by the request extras.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ServiceDetachedResumeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After fun cleanup() {
        instrumentation.runOnMainSync { StreamPlayback.stop() }
        Thread.sleep(300)
    }

    // (1) and (3): the service ends without a close, the paused item stays, and resuming rebuilds
    // it at the position where it stopped rather than from zero.
    @Test fun detachedLocalSessionKeepsItemAndResumesAtItsPosition() {
        val fixture = createWavFixture()
        try {
            instrumentation.runOnMainSync { StreamPlayback.playLocal(context, fixture.toAudioResult()) }
            await("Fixture did not start") { StreamPlayback.state.value.playing || StreamPlayback.state.value.error != null }
            assertTrue(StreamPlayback.state.value.playing)
            instrumentation.runOnMainSync { StreamPlayback.seekTo(12_000L) }
            await("Fixture did not seek") { StreamPlayback.state.value.positionMs >= 12_000L }
            instrumentation.runOnMainSync { StreamPlayback.pause() }
            await("Fixture did not pause") { !StreamPlayback.state.value.playing }
            val stoppedAt = StreamPlayback.state.value.positionMs
            assertTrue("Paused position was not recorded: $stoppedAt", stoppedAt >= 12_000L)

            // End the service without a user close, as the system may do in the background.
            instrumentation.runOnMainSync { context.stopService(Intent(context, StreamingAudioService::class.java)) }
            val detached = await("Service end did not mark the session resumable") {
                StreamPlayback.state.value.serviceDetached
            }
            assertTrue("Detached session lost its item: ${StreamPlayback.state.value}", StreamPlayback.state.value.localAudioPath != null)
            assertEquals(stoppedAt, StreamPlayback.state.value.positionMs)
            assertTrue(isSeparatedPaused(detached))

            // A user resume rebuilds the same item and starts where it stopped.
            instrumentation.runOnMainSync { StreamPlayback.resume() }
            await("Resume did not start the detached item") {
                StreamPlayback.state.value.playing && !StreamPlayback.state.value.serviceDetached
            }
            val resumedAt = StreamPlayback.state.value.positionMs
            assertTrue("Resume restarted from the beginning instead of $stoppedAt: $resumedAt", resumedAt >= stoppedAt - 1_000L)
        } finally {
            fixture.delete()
        }
    }

    // (2) A bot request that names a start position must hand it to the service, which applies it.
    // The media itself needs a real bot file, so this asserts the request shape only.
    @Test fun coldBotResumeRequestNamesItsStartPosition() {
        val intent = StreamingAudioService.resumeIntent(context, StreamingAudioService.ResumeSpec(
            positionMs = 42_000L,
            title = "봇 영상 2",
            botFileId = 20,
            botBytes = 1_000L,
        ))
        assertEquals(BotRuntime.ACTION_PLAY_TDLIB, intent.action)
        assertEquals(42_000L, intent.getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
        assertEquals(20, intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0))
        // A fresh item with no position must not carry a stale one.
        val fresh = StreamingAudioService.localAudioIntent(context, "/files/a.wav", "audio/wav")
        assertEquals(-1L, fresh.getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
    }

    @Test fun startFromANewItemDoesNotInheritThePreviousPosition() {
        assertFalse(StartPositionPolicy.armedPosition(null) >= 0L)
        assertTrue(StartPositionPolicy.armedPosition(9_000L) == 9_000L)
        assertNotNull(StartPositionPolicy.fromRequest(9_000L))
    }

    private fun isSeparatedPaused(stream: StreamingState): Boolean =
        io.github.polarfox0317.sikboonge.ui.state.isSeparatedPausedStream(stream)

    private fun createWavFixture(): File {
        val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
        val fixture = File(audioDirectory, "qa-detached-${System.nanoTime()}.wav")
        val result = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
            "-c:a", "pcm_s16le", fixture.absolutePath,
        ))
        assertTrue("Could not generate the WAV playback fixture", ReturnCode.isSuccess(result.returnCode))
        return fixture
    }

    private fun File.toAudioResult() = AudioResult(
        path = absolutePath,
        name = name,
        mime = "audio/wav",
        bytes = length(),
        date = System.currentTimeMillis(),
    )

    private fun await(message: String, condition: () -> Boolean): StreamingState {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return StreamPlayback.state.value
            Thread.sleep(100)
        }
        throw AssertionError("$message: ${StreamPlayback.state.value}")
    }
}
