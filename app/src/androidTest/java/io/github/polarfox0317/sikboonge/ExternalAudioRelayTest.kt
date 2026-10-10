package io.github.polarfox0317.sikboonge

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.Log
import com.arthenica.ffmpegkit.MediaInformationSession
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ExternalAudioRelayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(context.cacheDir, "qa-external-audio-${UUID.randomUUID()}").apply { mkdirs() } }
    private val source by lazy { File(dir, "source-with-video-and-audio.mp4") }

    @Before fun createAudioVideoFixture() {
        val result = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-f", "lavfi", "-i", "color=c=blue:s=32x32:r=10:d=2",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
            "-c:v", "mpeg4", "-c:a", "aac", "-ac", "2", "-shortest", "-movflags", "+faststart", source.absolutePath,
        ))
        assertTrue("Synthetic AV fixture failed: ${result.failStackTrace}", ReturnCode.isSuccess(result.returnCode))
    }

    @After fun cleanup() {
        ExternalBotStreams.cancelAll(context)
        AudioEngine.mediaInformationSessionHookForTests = null
        BotStreamTestHooks.accessOverride = null
        ExternalPlaybackTestHooks.interceptViewForTests = null
        ExternalPlaybackTestHooks.skipRelayServiceForTests = false
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    @Test(timeout = 45_000) fun typedProbeSeparatesWarningLogsFromAudioMetadata() {
        val capturedSession = AtomicReference<MediaInformationSession?>()
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<com.arthenica.ffmpegkit.MediaInformation>?>(null)
        AudioEngine.mediaInformationSessionHookForTests = { session ->
            capturedSession.set(session)
            session.addLog(Log(session.sessionId, Level.AV_LOG_WARNING, "Synthetic warning before probe JSON"))
        }
        val id = AudioEngine.probeMediaInformation(
            source.absolutePath,
            networkInput = false,
            selectAudioOnly = true,
            logLevel = "info",
        ) { _, probe -> result.set(probe); completed.countDown() }
        assertTrue("Typed FFprobe did not complete", completed.await(30, TimeUnit.SECONDS))
        val session = requireNotNull(capturedSession.get())
        assertTrue("The fixture should include an interleaved warning log", session.output.contains("Synthetic warning before probe JSON"))
        val info = requireNotNull(result.get()).getOrThrow()
        assertEquals("audio", info.streams.single().type)
        assertEquals("aac", info.streams.single().codec)
        val probe = AudioEngine.audioProbeFromInformation(info, allowFormatDuration = false)
        assertTrue("Audio duration was not parsed from structured metadata", probe.duration > 0.0)
    }

    @Test(timeout = 60_000) fun inspectBotAsyncAcceptsValidRangeAndClientCloseIsNotInputFailure() {
        val leaseCount = java.util.concurrent.atomic.AtomicInteger()
        val tdFile = testTdlibFile(733, source.length())
        val bridge = ExternalAudioInputBridge.forBot(733, source.length(), testAccess(tdFile, source.length(), leaseCount))
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<AudioProbe>?>(null)
        try {
            val id = AudioEngine.inspectBotAsync(bridge) { result.set(it); completed.countDown() }
            if (!completed.await(30, TimeUnit.SECONDS)) {
                AudioEngine.cancel(id)
                fail("Bot metadata probe did not complete")
            }
            val probe = requireNotNull(result.get()).getOrThrow()
            assertEquals("aac", probe.codec)
            assertTrue(probe.duration > 0.0)
            assertNull("A normal FFprobe range/client close must not be reported as upstream truncation", bridge.failure())
        } finally {
            bridge.close()
        }
        assertEquals("Bot range lease should be balanced", 0, leaseCount.get())
    }

    @Test(timeout = 45_000) fun botBridgeFlagsAdvertisedSizeMismatchAndConversionRefusesIt() {
        val actualSize = source.length()
        val advertisedSize = actualSize + 4096L
        val leaseCount = java.util.concurrent.atomic.AtomicInteger()
        val tdFile = testTdlibFile(734, advertisedSize)
        val availableFile = File(dir, "advertised-size-with-missing-tail.mp4").apply {
            source.copyTo(this)
            java.io.RandomAccessFile(this, "rw").use { it.setLength(advertisedSize) }
        }
        val bridge = ExternalAudioInputBridge.forBot(
            734,
            advertisedSize,
            testAccess(tdFile, advertisedSize, leaseCount, failAtOffset = advertisedSize - 1L, backingFile = availableFile),
        )
        val output = File(dir, "must-not-be-created.m4a")
        try {
            assertNull("No reads have yet disproved the advertised length", bridge.failure())
            val conversion = runCatching {
                AudioEngine.convert(
                    bridge.inputUrl,
                    output,
                    AudioEngine.plan(AudioFormat.ORIGINAL, "aac"),
                    2.0,
                    {},
                    {},
                    externalInputBridge = bridge,
                )
            }
            val failure = requireNotNull(conversion.exceptionOrNull()) {
                "A TDLib source shorter than its advertised length was accepted as a complete conversion"
            }
            assertEquals("Telegram truncated-input guidance must be specific", "Telegram 영상 데이터를 끝까지 읽지 못했어요. 연결을 확인하고 다시 시도해 주세요.", UserFacingErrors.fromThrowable(failure))
            assertNotNull("The missing advertised final byte must be remembered as incomplete input", bridge.failure())
        } finally {
            bridge.close()
            output.delete()
        }
        assertEquals("Bot range lease should be balanced after truncated read", 0, leaseCount.get())
    }

    private fun testTdlibFile(fileId: Int, size: Long) = org.drinkless.tdlib.TdApi.File().apply {
        id = fileId
        this.size = size
        expectedSize = size
        local = org.drinkless.tdlib.TdApi.LocalFile()
        remote = org.drinkless.tdlib.TdApi.RemoteFile()
    }

    private fun testAccess(
        tdFile: org.drinkless.tdlib.TdApi.File,
        availableBytes: Long,
        leaseCount: java.util.concurrent.atomic.AtomicInteger,
        onRead: (Long, Int) -> Unit = { _, _ -> },
        failAtOffset: Long? = null,
        backingFile: File = source,
    ): TdlibFileAccess = object : TdlibFileAccess {
        override fun acquireFile(fileId: Int) = tdFile.also { assertEquals(tdFile.id, fileId) }
        override fun beginPlayback(fileId: Int) { leaseCount.incrementAndGet() }
        override fun endPlayback(fileId: Int) { leaseCount.decrementAndGet() }
        override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
            if (cancelled()) throw java.io.InterruptedIOException("Telegram audio input cancelled")
            onRead(offset, length)
            if (offset == failAtOffset) return -1
            if (offset >= availableBytes) return -1
            return java.io.RandomAccessFile(backingFile, "r").use { input ->
                input.seek(offset)
                input.read(buffer, 0, minOf(length.toLong(), availableBytes - offset).toInt())
            }
        }
    }

    @Test(timeout = 120_000) fun botTdlibBridgeExtractsAudioAndBalancesItsPlaybackLease() {
        val capturedIntent = AtomicReference<Intent?>()
        val reportedError = AtomicReference<String?>()
        val botPayload = File(dir, "bot-bridge-payload.m4a")
        val tdFile = org.drinkless.tdlib.TdApi.File().apply {
            id = 731
            size = source.length()
            expectedSize = source.length()
            local = org.drinkless.tdlib.TdApi.LocalFile()
            remote = org.drinkless.tdlib.TdApi.RemoteFile()
        }
        val leaseCount = java.util.concurrent.atomic.AtomicInteger()
        val rangeReads = java.util.concurrent.atomic.AtomicInteger()
        val largestRead = java.util.concurrent.atomic.AtomicInteger()
        val finalByteReads = java.util.concurrent.atomic.AtomicInteger()
        val firstReadStarted = CountDownLatch(1)
        val allowSourceRead = CountDownLatch(1)
        BotStreamTestHooks.accessOverride = object : TdlibFileAccess {
            override fun acquireFile(fileId: Int) = tdFile.also { assertEquals(731, fileId) }
            override fun beginPlayback(fileId: Int) { leaseCount.incrementAndGet() }
            override fun endPlayback(fileId: Int) { leaseCount.decrementAndGet() }
            override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
                if (cancelled()) throw java.io.InterruptedIOException("Telegram audio input cancelled")
                firstReadStarted.countDown()
                while (allowSourceRead.count != 0L && !cancelled()) allowSourceRead.await(50, TimeUnit.MILLISECONDS)
                if (cancelled()) throw java.io.InterruptedIOException("Telegram audio input cancelled")
                rangeReads.incrementAndGet()
                largestRead.updateAndGet { maxOf(it, length) }
                if (offset == source.length() - 1L && length == 1) finalByteReads.incrementAndGet()
                if (offset >= source.length()) return -1
                return java.io.RandomAccessFile(source, "r").use { input ->
                    input.seek(offset)
                    input.read(buffer, 0, minOf(length, (source.length() - offset).toInt()))
                }
            }
        }
        ExternalPlaybackTestHooks.interceptViewForTests = { intent -> capturedIntent.set(Intent(intent)); true }
        ExternalPlaybackTestHooks.skipRelayServiceForTests = true
        try {
            ExternalPlayback.openBotVideo(context, BotVideo(731, 1, 1, "bot-fixture.mp4", source.length(), 2, 0)) {
                reportedError.set(it)
            }
            val uri = requireNotNull(capturedIntent.get()?.data)
            assertTrue("FFprobe did not reach the TDLib source bridge", firstReadStarted.await(10, TimeUnit.SECONDS))
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null).use { cursor ->
                assertTrue("SIZE query did not return a row", requireNotNull(cursor).moveToFirst())
                assertTrue("An incomplete pipe must not publish a cached size", cursor.isNull(0))
            }
            allowSourceRead.countDown()
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input).use { stream -> botPayload.outputStream().use { stream.copyTo(it, 16 * 1024) } }
            }

            val media = requireNotNull(FFprobeKit.getMediaInformation(botPayload.absolutePath).mediaInformation)
            assertEquals("TDLib bridge payload has no video track", 0, media.streams.count { it.type == "video" })
            assertEquals("TDLib bridge payload has one audio track", 1, media.streams.count { it.type == "audio" })
            assertEquals("aac", media.streams.single { it.type == "audio" }.codec)
            assertDurationPreserved(botPayload)
            assertEquals("AAC samples must be copied through the bot bridge", packets(source), packets(botPayload))
            assertTrue("Telegram input bridge issued no range reads", rangeReads.get() > 0)
            assertTrue("Telegram range reads exceeded the bounded request size", largestRead.get() <= ExternalAudioPolicy.MAX_READ_SEGMENT_BYTES)
            assertEquals("A completed bot extraction should verify only its advertised final byte", 1, finalByteReads.get())
            assertNull("Bot extraction reported an error", reportedError.get())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (leaseCount.get() != 0 && System.nanoTime() < deadline) Thread.sleep(50)
        } finally {
            allowSourceRead.countDown()
            ExternalBotStreams.cancelAll(context)
            BotStreamTestHooks.accessOverride = null
            botPayload.delete()
        }
        assertEquals("TDLib playback lease leaked", 0, leaseCount.get())
    }

    private fun packets(file: File): Pair<Int, String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(audioTrack)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var count = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                digest.update(buffer.array(), 0, size)
                count++
                extractor.advance()
            }
            return count to digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            extractor.release()
        }
    }

    private fun assertDurationPreserved(file: File) {
        val sourceDuration = AudioEngine.inspect(source).second
        val payloadDuration = AudioEngine.inspect(file).second
        assertTrue("Source fixture must advertise its audio duration", sourceDuration > 0.0)
        assertTrue("Audio-only payload must expose a total duration", payloadDuration > 0.0)
        assertEquals("Stream-copy payload duration should match the selected audio track", sourceDuration, payloadDuration, 0.1)
    }
}
