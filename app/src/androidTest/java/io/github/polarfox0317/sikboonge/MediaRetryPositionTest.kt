package io.github.polarfox0317.sikboonge

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the Media3 retry path after a failed open or seek on a streaming source
 * (the upstream #3440 shape: the extractor's stale current input position is written after a new open
 * fails, so the retried read lands on an older byte instead of the requested one).
 *
 * Real chain: StreamingAudioService -> DefaultMediaSourceFactory -> StreamingDataSource ->
 * TdlibMediaDataSource -> injected TdlibFileAccess -> ExoPlayer. Failures are injected only at the
 * bridge's [TdlibFileAccess.acquireFile] step, which runs on ExoPlayer's loading thread from
 * [TdlibMediaDataSource.open] - never during the service's `beginBot` (that method only calls
 * `beginPlayback`), so the injection cannot terminate the service before the extractor runs.
 *
 * The position assertion is two-sided: a forward overshoot past the target is a #3440 failure just as
 * a backwards jump is, so the upper bound allows only the media actually played since the seek.
 *
 * The service captures [StreamingTestHooks.tdlibAccessOverride] when it initializes its resources, so
 * each case stops the service and waits for it to disappear before installing its own access.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MediaRetryPositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val fixtureRoot = File(context.cacheDir, "qa-retry-${System.nanoTime()}")
    private var controller: MediaController? = null

    @Before fun stopServiceBeforeCase() {
        releaseController()
        instrumentation.runOnMainSync {
            StreamPlayback.stop()
            context.stopService(Intent(context, StreamingAudioService::class.java))
        }
        awaitServiceStopped()
        StreamingTestHooks.tdlibAccessOverride = null
    }

    @After fun cleanup() {
        releaseController()
        instrumentation.runOnMainSync {
            StreamPlayback.stop()
            context.stopService(Intent(context, StreamingAudioService::class.java))
        }
        awaitServiceStopped()
        StreamingTestHooks.tdlibAccessOverride = null
        fixtureRoot.deleteRecursively()
    }

    /**
     * A seek to 60 s fails to open twice; Media3 must retry and settle at 60 s.
     *
     * The target sits well beyond the service's 30 s streaming buffer, so Media3 cannot satisfy it
     * from already-loaded bytes and must issue a real load open - the precondition for this test to
     * prove anything. A buffered seek opens nothing, and [verifySettledPosition] fails rather than
     * passing vacuously.
     */
    @Test fun seekFailureIsRetriedAndSettlesAtTheSeekTarget() {
        runScenario(buildFixture("seek", useMp3 = false), targetMs = 60_000L, armFailures = 2)
    }

    /**
     * Contrast case, NOT a #3440 reproduction: a resume request naming 50 s opens the item and then
     * seeks there, and the failing open is the request's first one, so the extractor has no previous
     * input position to leak. It checks that a plain open failure still retries and lands at the
     * requested position; it cannot exercise the stale-input path, which needs the seek to follow an
     * already-loaded input (covered by [seekFailureIsRetriedAndSettlesAtTheSeekTarget]).
     */
    @Test fun resumeFailureIsRetriedAndSettlesAtTheResumePosition() {
        runScenario(
            file = buildFixture("resume", useMp3 = true),
            targetMs = 50_000L,
            armFailures = 1,
            resumeRequest = true,
        )
    }

    /** Control: the same seek with no injected failure must also settle at the target. */
    @Test fun controlRunWithoutFailureReachesTheSameTarget() {
        runScenario(buildFixture("control", useMp3 = false), targetMs = 60_000L, armFailures = 0)
    }

    private fun runScenario(file: File, targetMs: Long, armFailures: Int, resumeRequest: Boolean = false) {
        val access = FailingAccess(file)
        try {
            StreamingTestHooks.tdlibAccessOverride = access
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(800)

            if (resumeRequest) {
                // Arm exactly once, immediately before the request that causes the opening load, so
                // the injected failure lands on this request's own open and no stale flag survives.
                access.markArm()
                access.armNextAcquireFailures(armFailures)
                val requestStartedAt = System.nanoTime()
                instrumentation.runOnMainSync {
                    StreamPlayback.resumeRestored(
                        context,
                        StreamingAudioService.ResumeSpec(
                            positionMs = targetMs,
                            title = "QA retry audio",
                            botFileId = FILE_ID,
                            botBytes = file.length(),
                        ),
                        StreamPlayback.state.value,
                    )
                }
                await("Fixture never started playing") { state.playing || state.error != null }
                assertNull("Playback failed on the resume request", state.error)
                verifySettledPosition(access, targetMs, armFailures, requestStartedAt)
            } else {
                instrumentation.runOnMainSync {
                    StreamPlayback.playBot(context, BotVideo(
                        fileId = FILE_ID,
                        chatId = 7L,
                        messageId = 7L,
                        name = "QA retry audio",
                        bytes = file.length(),
                        durationSeconds = FIXTURE_SECONDS,
                        date = 0L,
                    ))
                }
                await("Fixture never started playing") { state.playing || state.error != null }
                assertNull("Playback failed before the seek", state.error)
                assertTrue("Fixture never started playing", state.playing)

                // The controller must be queried on the main thread; it is not thread safe.
                controller = connectController()
                await("Controller was not ready to seek") {
                    onMain { controller!!.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
                }
                access.markArm()
                access.armNextAcquireFailures(armFailures)
                val seekStartedAt = System.nanoTime()
                instrumentation.runOnMainSync { controller!!.seekTo(targetMs) }
                verifySettledPosition(access, targetMs, armFailures, seekStartedAt)
                if (file.extension == "wav") {
                    // The player can report the requested TIME while consuming a stale BYTE range.
                    // Compare actual first post-seek read against this fixture's PCM seek map too.
                    val expectedByte = pcmBytePosition(file, targetMs)
                    val actualByte = access.offsetsAfterFailure().first()
                    println("RETRY_BYTE_PROBE targetMs=$targetMs expectedByte=$expectedByte actualByte=$actualByte injected=${access.injectedFailures.get()} retries=${access.retryOpens.get()}")
                    assertEquals("Retry consumed stale PCM bytes despite the displayed seek time", expectedByte, actualByte)
                }
            }
        } finally {
            println("[MediaRetryPositionTest] ${access.summary()}")
            releaseController()
        }
    }

    /**
     * Waits for the retry (when one was armed), then for the player to settle, and only then asserts
     * the final position inside a window bounded by the target and by the elapsed wall time.
     *
     * Waiting for `retryOpens` first is deliberate: `StreamPlayback.publish` can pre-announce the seek
     * position, so asserting on the first sight of `position >= target` would pass without any retry.
     */
    private fun verifySettledPosition(access: FailingAccess, targetMs: Long, armFailures: Int, seekStartedAt: Long) {
        if (armFailures > 0) {
            await("No open failure reached the bridge") { access.injectedFailures.get() >= 1 }
            await("Media3 did not retry after the injected failure") { access.retryOpens.get() >= 1 }
        }

        await("Playback did not settle after the retry") {
            val current = state
            current.error != null || (current.ready && current.playing && current.positionMs >= targetMs - POSITION_TOLERANCE_MS)
        }
        assertNull("Playback failed after the retry", state.error)

        // Observe for two seconds and keep the last sample: a settled playhead moves with media time.
        val observeUntil = System.nanoTime() + SETTLE_WINDOW_NANOS
        var lastSeen = state.positionMs
        while (System.nanoTime() < observeUntil) {
            val current = state
            assertNull("Playback failed while settling", current.error)
            assertTrue("Playback stopped while settling", current.playing)
            lastSeen = current.positionMs
            Thread.sleep(100)
        }

        // Inconclusive is a failure: without an open at the target, Media3 served the seek from
        // already-buffered bytes and this run proves nothing about the retry path.
        assertTrue(
            "INCONCLUSIVE: no open occurred at or after $targetMs, so the seek was satisfied from " +
                "buffered data and the retry path was never exercised. Move the target beyond the " +
                "buffer or seek far enough to force a load. ${access.summary()}",
            access.opensAfterArm.get() >= 1,
        )
        if (armFailures > 0) {
            assertTrue(
                "The armed failure never fired: an open happened but none was injected. " +
                    "Check the arm point against the real load open. ${access.summary()}",
                access.injectedFailures.get() >= 1,
            )
            assertTrue(
                "Media3 did not retry after the injected failure. ${access.summary()}",
                access.retryOpens.get() >= 1,
            )
        } else {
            assertEquals("The control run injected a failure", 0, access.injectedFailures.get())
        }

        val elapsedMs = (System.nanoTime() - seekStartedAt) / 1_000_000L
        val lowerBound = targetMs - POSITION_TOLERANCE_MS
        // A forward overshoot is only legitimate for media actually played in the elapsed window.
        val upperBound = targetMs + POSITION_TOLERANCE_MS + elapsedMs
        // Offsets are BYTES while targetMs is playback TIME, so they are never compared directly.
        // The forward seek must skip into the file past the header region the opening read touched.
        val afterArmReads = access.offsetsAfterFailure()
        println(
            "[MediaRetryPositionTest] targetMs=$targetMs afterArmByteOffsets=${afterArmReads.take(8)} " +
                "fixtureBytes=${access.fixtureBytes}",
        )
        assertTrue(
            "No read was requested after the arm point, so the retried load never reached the source " +
                "(byte offsets=$afterArmReads)",
            afterArmReads.isNotEmpty(),
        )
        assertTrue("The nonzero target never caused a nonzero read: $afterArmReads",
            afterArmReads.any { it > 0L })
        assertTrue(
            "Settled position $lastSeen is outside [$lowerBound, $upperBound] for target $targetMs " +
                "after ${elapsedMs}ms: ${access.summary()}",
            lastSeen in lowerBound..upperBound,
        )
    }

    private val state get() = StreamPlayback.state.value

    private fun pcmBytePosition(file: File, targetMs: Long): Long = RandomAccessFile(file, "r").use { input ->
        require(input.readInt() == 0x52494646) // RIFF
        input.skipBytes(4)
        require(input.readInt() == 0x57415645) // WAVE
        var bytesPerSecond = 0L
        var blockAlign = 0L
        while (input.filePointer + 8 <= input.length()) {
            val id = input.readInt()
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            val start = input.filePointer
            if (id == 0x666d7420) { // fmt
                input.skipBytes(8)
                bytesPerSecond = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                blockAlign = java.lang.Short.reverseBytes(input.readShort()).toLong() and 0xffffL
            }
            if (id == 0x64617461) { // data
                require(bytesPerSecond > 0 && blockAlign > 0)
                return@use start + ((targetMs * bytesPerSecond / 1000L) / blockAlign) * blockAlign
            }
            input.seek(start + size + (size and 1L))
        }
        error("No PCM data chunk")
    }

    /**
     * Fails the requested number of opens inside [acquireFile], the data source's own open step, so the
     * exception surfaces to Media3 exactly like a real acquire failure. Every read offset is logged so
     * the report shows which byte the retried read targeted.
     */
    private class FailingAccess(private val media: File) : TdlibFileAccess {
        val injectedFailures = AtomicInteger()
        val retryOpens = AtomicInteger()
        val opensAfterArm = AtomicInteger()
        val fixtureBytes get() = media.length()
        private val opens = AtomicInteger()
        private val firstSuccessfulOpenAfterArm = AtomicInteger()
        private val readOpenOnThread = ThreadLocal<Int>()
        private val generationReads: MutableList<Pair<Int, Long>> = Collections.synchronizedList(mutableListOf())
        private val armed = AtomicInteger()
        private val armPoint = AtomicInteger(-1)
        private val offsets: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val bytes: ByteArray by lazy { media.readBytes() }

        private val tdFile: TdApi.File by lazy {
            TdApi.File().apply {
                id = FILE_ID
                size = media.length()
                expectedSize = media.length()
                local = TdApi.LocalFile(media.absolutePath, true, true, true, true, 0, media.length(), media.length())
                remote = TdApi.RemoteFile()
            }
        }

        fun markArm() {
            armPoint.set(offsets.size)
            firstSuccessfulOpenAfterArm.set(0)
        }

        fun armNextAcquireFailures(count: Int) = armed.set(count)

        override fun acquireFile(fileId: Int): TdApi.File {
            val attempt = opens.incrementAndGet()
            if (armPoint.get() >= 0) opensAfterArm.incrementAndGet()
            if (armed.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                injectedFailures.incrementAndGet()
                throw IOException("Injected open failure on attempt $attempt")
            }
            // An open that follows a failed one is the retry Media3 issued on its own.
            if (injectedFailures.get() > 0) retryOpens.incrementAndGet()
            if (armPoint.get() >= 0) firstSuccessfulOpenAfterArm.compareAndSet(0, attempt)
            readOpenOnThread.set(attempt)
            return tdFile
        }

        override fun beginPlayback(fileId: Int) = Unit
        override fun endPlayback(fileId: Int) = Unit

        override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
            offsets += offset
            generationReads += ((readOpenOnThread.get() ?: -1) to offset)
            if (cancelled()) throw IOException("Cancelled")
            if (offset >= bytes.size) return -1
            val count = minOf(length.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), buffer, 0, count)
            return count
        }

        /** Only reads from the first successful OPEN after arming; excludes old-loader races. */
        fun offsetsAfterFailure(): List<Long> = synchronized(generationReads) {
            val owner = firstSuccessfulOpenAfterArm.get()
            if (owner == 0) emptyList() else generationReads.filter { it.first == owner }.map { it.second }
        }

        fun summary(): String = "opens=${opens.get()} retries=${retryOpens.get()} " +
            "injected=${injectedFailures.get()} firstSuccessfulOpenAfterArm=${firstSuccessfulOpenAfterArm.get()} reads=${offsets.size} " +
            "all=${synchronized(offsets) { offsets.toList() }.take(12)} " +
            "afterArm=${offsetsAfterFailure().take(8)}"
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun connectController(): MediaController {
        val latch = CountDownLatch(1)
        var result: MediaController? = null
        instrumentation.runOnMainSync {
            val future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, StreamingAudioService::class.java)),
            ).buildAsync()
            future.addListener({ result = future.get(); latch.countDown() }, context.mainExecutor)
        }
        assertTrue("MediaController could not connect", latch.await(10, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private fun releaseController() {
        controller?.let { connected -> runCatching { instrumentation.runOnMainSync { connected.release() } } }
        controller = null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun awaitServiceStopped() {
        @Suppress("DEPRECATION")
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val running = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == StreamingAudioService::class.java.name }
            }.getOrDefault(true)
            if (!running) return
            Thread.sleep(100)
        }
        throw AssertionError("StreamingAudioService did not stop before the case started")
    }

    /** A real audio file long enough for every target, owned by this test and removed in cleanup. */
    private fun buildFixture(name: String, useMp3: Boolean): File {
        fixtureRoot.mkdirs()
        val output = File(fixtureRoot, "$name-${System.nanoTime()}.${if (useMp3) "mp3" else "wav"}")
        val codec = if (useMp3) arrayOf("-c:a", "libmp3lame", "-b:a", "128k") else arrayOf("-c:a", "pcm_s16le")
        val args = arrayOf("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$FIXTURE_SECONDS") +
            codec + arrayOf(output.absolutePath)
        val result = FFmpegKit.executeWithArguments(args)
        assertTrue("Could not generate the retry fixture", ReturnCode.isSuccess(result.returnCode))
        assertTrue("Fixture is empty", output.length() > 0L)
        assertTrue(
            "Fixture exceeds the ${MAX_FIXTURE_BYTES / (1024 * 1024)} MB budget",
            output.length() <= MAX_FIXTURE_BYTES,
        )
        return output
    }

    private companion object {
        const val FILE_ID = 7
        const val FIXTURE_SECONDS = 90
        const val MAX_FIXTURE_BYTES = 20L * 1024 * 1024
        const val POSITION_TOLERANCE_MS = 3_000L
        const val SETTLE_WINDOW_NANOS = 2_000_000_000L
        const val TIMEOUT_NANOS = 30_000_000_000L
    }
}
