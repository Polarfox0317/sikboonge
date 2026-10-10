package io.github.polarfox0317.sikboonge

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata as PlatformMediaMetadata
import android.media.session.MediaController as PlatformMediaController
import android.media.session.MediaSession as PlatformMediaSession
import android.media.session.MediaSessionManager
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LocalPlaybackSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After fun cleanup() {
        foreground()
        instrumentation.runOnMainSync { StreamPlayback.stop() }
        Thread.sleep(300)
    }

    @Test fun savedAudioUsesSystemMetadataControlsSeekReplayAndStop() {
        val fixture = createWavFixture()
        var controller: MediaController? = null
        try {
            foreground()
            instrumentation.runOnMainSync {
                StreamPlayback.playLocal(context, fixture.toAudioResult())
            }
            await("Saved audio did not start") { StreamPlayback.state.value.playing || StreamPlayback.state.value.error != null }
            assertTrue("Saved audio failed: ${StreamPlayback.state.value}", StreamPlayback.state.value.playing)

            controller = connectController()
            assertTrue(onMainSync { controller!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) })
            assertTrue(onMainSync { controller!!.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
            assertTrue(onMainSync { controller!!.isCommandAvailable(Player.COMMAND_STOP) })
            assertFalse(onMainSync { controller!!.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) })
            assertEquals(
                fixture.name,
                onMainSync { controller!!.currentMediaItem?.mediaMetadata?.title?.toString() }
            )
            val localUri = requireNotNull(onMainSync { controller!!.currentMediaItem?.localConfiguration?.uri })
            assertEquals("content", localUri.scheme)
            assertFalse("Raw app-private path leaked into session URI", localUri.toString().contains(fixture.canonicalPath))
            assertEquals("audio/wav", StreamPlayback.state.value.localAudioMime)

            instrumentation.runOnMainSync { controller!!.pause() }
            await("MediaController pause failed") { !StreamPlayback.state.value.playing }
            val pausedAt = StreamPlayback.state.value.positionMs
            Thread.sleep(500)
            assertTrue("Paused saved audio advanced", StreamPlayback.state.value.positionMs <= pausedAt + 250)

            instrumentation.runOnMainSync { controller!!.seekTo(15_000) }
            await("Paused seek did not update shared position") { StreamPlayback.state.value.positionMs >= 15_000 }
            assertFalse("Paused seek resumed playback", StreamPlayback.state.value.playing)
            instrumentation.runOnMainSync { controller!!.play() }
            await("MediaController resume failed") { StreamPlayback.state.value.playing }

            instrumentation.runOnMainSync { controller!!.seekTo(59_000) }
            await("Saved audio did not reach ended state") { StreamPlayback.state.value.ended }
            instrumentation.runOnMainSync { StreamPlayback.resume() }
            await("Resume after end did not replay from the start") {
                StreamPlayback.state.value.playing && !StreamPlayback.state.value.ended && StreamPlayback.state.value.positionMs < 5_000
            }

            instrumentation.runOnMainSync { controller!!.stop() }
            await("System Stop did not clear the local session") { StreamPlayback.state.value == StreamingState() }
        } finally {
            instrumentation.runOnMainSync { controller?.release() }
            fixture.delete()
        }
    }

    @Test fun platformSessionAndMediaNotificationWorkWithoutMedia3ControllerBinding() {
        val fixture = createWavFixture()
        var platformController: PlatformMediaController? = null
        try {
            instrumentation.runOnMainSync {
                context.stopService(Intent(context, StreamingAudioService::class.java))
            }
            Thread.sleep(300)
            foreground()
            instrumentation.runOnMainSync { StreamPlayback.playLocal(context, fixture.toAudioResult()) }
            await("Saved audio did not start") { StreamPlayback.state.value.playing || StreamPlayback.state.value.error != null }
            assertTrue("Saved audio failed: ${StreamPlayback.state.value}", StreamPlayback.state.value.playing)

            val sessionNotification = awaitMediaSessionNotification(fixture.name)
            assertEquals(Notification.CATEGORY_TRANSPORT, sessionNotification.category)
            @Suppress("DEPRECATION")
            val platformToken = requireNotNull(
                sessionNotification.extras.getParcelable<PlatformMediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            ) { "MediaStyle notification did not publish its platform session token" }

            @Suppress("DEPRECATION")
            val runningService = context.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .firstOrNull { it.service.className == StreamingAudioService::class.java.name }
            assertNotNull("MediaSessionService was not started", runningService)
            assertTrue("MediaSessionService did not become foreground", requireNotNull(runningService).foreground)

            if (Build.VERSION.SDK_INT >= 37) {
                @Suppress("NewApi")
                val activeTokens = context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessionsForPackage(context.packageName)
                assertTrue("MediaSessionManager did not list our playing session", activeTokens.contains(platformToken))
            }

            platformController = PlatformMediaController(context, platformToken)
            assertEquals(fixture.name, platformController!!.metadata?.getString(PlatformMediaMetadata.METADATA_KEY_TITLE))
            assertEquals(android.media.session.PlaybackState.STATE_PLAYING, platformController!!.playbackState?.state)

            platformController!!.transportControls.pause()
            await("Platform MediaController pause failed") { !StreamPlayback.state.value.playing }
            platformController!!.transportControls.seekTo(12_000L)
            await("Platform MediaController seek did not update shared position") {
                StreamPlayback.state.value.positionMs >= 12_000L
            }
            platformController!!.transportControls.play()
            await("Platform MediaController play failed") { StreamPlayback.state.value.playing }
            platformController!!.transportControls.stop()
            await("Platform MediaController stop did not clear the service session") {
                StreamPlayback.state.value == StreamingState()
            }
        } finally {
            fixture.delete()
        }
    }

    @Test fun invalidPathStopsPlaybackAfterLocalSession() {
        val localFixture = createWavFixture()
        val outsideFixture = File(context.cacheDir, "qa-invalid-local-${System.nanoTime()}.wav")
        var controller: MediaController? = null
        try {
            foreground()
            instrumentation.runOnMainSync { StreamPlayback.playLocal(context, localFixture.toAudioResult()) }
            await("Saved audio did not start") { StreamPlayback.state.value.playing || StreamPlayback.state.value.error != null }
            assertTrue("Saved audio failed: ${StreamPlayback.state.value}", StreamPlayback.state.value.playing)
            controller = connectController()
            assertEquals("content", onMainSync { controller!!.currentMediaItem?.localConfiguration?.uri?.scheme })

            outsideFixture.writeBytes(byteArrayOf(1, 2, 3, 4))
            instrumentation.runOnMainSync {
                StreamPlayback.playLocal(context, outsideFixture.toAudioResult())
            }
            await("Invalid path left local playback running behind its error") {
                StreamPlayback.state.value.error == LocalPlaybackPolicy.INVALID_AUDIO_ERROR &&
                    !StreamPlayback.state.value.playing &&
                    StreamPlayback.state.value.localAudioPath == null
            }
            await("The system player stayed active after invalid-path rejection") {
                onMainSync { !controller!!.isPlaying }
            }
            assertFalse("The invalid-path request left the system player active", onMainSync { controller!!.isPlaying })
        } finally {
            instrumentation.runOnMainSync { controller?.release() }
            localFixture.delete()
            outsideFixture.delete()
        }
    }

    private fun createWavFixture(): File {
        val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }
        val fixture = File(audioDirectory, "qa-local-${System.nanoTime()}.wav")
        val result = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
            "-c:a", "pcm_s16le", fixture.absolutePath
        ))
        assertTrue("Could not generate the WAV playback fixture", ReturnCode.isSuccess(result.returnCode))
        return fixture
    }

    private fun awaitMediaSessionNotification(title: String): Notification {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val notification = notificationManager.activeNotifications
                .asSequence()
                .filter { it.packageName == context.packageName }
                .map { it.notification }
                .firstOrNull { candidate ->
                    candidate.category == Notification.CATEGORY_TRANSPORT &&
                        candidate.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title &&
                        candidate.extras?.getParcelable<PlatformMediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) != null
                }
            if (notification != null) return notification
            Thread.sleep(100)
        }
        fail("MediaSessionService did not publish a MediaStyle notification with a session token: ${StreamPlayback.state.value}")
        error("unreachable")
    }

    private fun File.toAudioResult() = AudioResult(
        path = absolutePath,
        name = name,
        mime = "audio/wav",
        bytes = length(),
        date = System.currentTimeMillis()
    )

    private fun connectController(): MediaController {
        val connected = CountDownLatch(1)
        var result: MediaController? = null
        instrumentation.runOnMainSync {
            val future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, StreamingAudioService::class.java))
            ).buildAsync()
            future.addListener({ result = future.get(); connected.countDown() }, context.mainExecutor)
        }
        assertTrue("MediaController could not connect", connected.await(10, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private fun <T> onMainSync(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun foreground() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(800)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("$message: ${StreamPlayback.state.value}")
    }

}
