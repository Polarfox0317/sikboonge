package io.github.polarfox0317.sikboonge

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BotSaveFlowTest {
    @Test(timeout = 60_000) fun legacyCompletedFileAccessRemainsSupportedForExistingCallers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "qa-bot-legacy-${System.nanoTime()}.mp4")
        val released = CountDownLatch(1)
        var saved: AudioResult? = null
        try {
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "aac", source.absolutePath)).returnCode))
            BotSaveTestHooks.accessOverride = object : BotSavedFileAccess {
                override fun awaitCompletedFile(fileId: Int, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
                    assertEquals(6, fileId)
                    assertFalse(cancelled())
                    progress(source.length(), source.length())
                    return source
                }
                override fun endSave(fileId: Int) {
                    assertEquals(6, fileId)
                    assertTrue(source.isFile)
                    assertNotNull(AppStore.state.value.result)
                    released.countDown()
                }
            }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(800)
            AppStore.state.value = ConversionState()
            startSave(context, 6, "qa-bot-legacy", source.length())
            assertTrue("Legacy completed-file lease was not released", released.await(30, TimeUnit.SECONDS))
            saved = AppStore.state.value.result
            assertNotNull(saved)
            assertEquals("audio/mp4", saved!!.mime)
        } finally {
            BotSaveTestHooks.accessOverride = null
            saved?.let { AppStore.remove(context, it) }
            source.delete()
        }
    }

    @Test(timeout = 120_000) fun explicitBotSaveStreamsRangesWithoutVideoStagingAndPersistsAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "qa-bot-save-${System.nanoTime()}.mp4")
        var saved: AudioResult? = null
        try {
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "aac", "-movflags", "+faststart", source.absolutePath)).returnCode))
            val stagedBefore = context.cacheDir.listFiles()?.filter { it.name.startsWith("input-") && it.extension == "video" }?.map { it.name }?.toSet().orEmpty()
            val access = TestRangeAccess(source)
            BotAudioSaveTestHooks.accessOverride = access
            AppStore.load(context)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(800)
            AppStore.state.value = ConversionState()
            val existingHistoryPaths = AppStore.history.value.mapTo(mutableSetOf()) { it.path }
            val customTitle = "빗소리.2026 / night? " + "long".repeat(20)
            val renamedVideo = BotVideo(
                fileId = 7,
                chatId = 7001L,
                messageId = 71L,
                name = "original-telegram-name.mp4",
                bytes = 2_324_555_125L,
                durationSeconds = 3,
                date = 1L,
                customTitle = customTitle,
            )
            startSave(context, 7, renamedVideo.displayName, listedSize = 2_324_555_125L)
            assertTrue("Bot audio save did not finish", awaitState { !it.running && (it.botSaveCompletionId != null || it.error != null) })
            val completedState = AppStore.state.value
            assertNull(completedState.error)
            assertEquals(ConversionOrigin.BOT_SAVE, completedState.origin)
            assertNull("Bot saves must not publish a local conversion result", completedState.result)
            saved = AppStore.history.value.firstOrNull { it.path !in existingHistoryPaths }
            assertNotNull(saved)
            assertEquals("audio/mp4", saved!!.mime)
            val safeTitle = customTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70)
            assertTrue("The saved filename should start with the custom card title", saved!!.name.startsWith("$safeTitle-"))
            assertTrue("Dots in a custom title must be preserved", saved!!.name.startsWith("빗소리.2026"))
            assertTrue("The output extension should come from the original AAC codec", saved!!.name.endsWith(".m4a"))
            val audio = File(saved.path)
            assertTrue(audio.length() > 0)
            val media = requireNotNull(FFprobeKit.getMediaInformation(audio.absolutePath).mediaInformation)
            assertEquals("The saved payload must contain audio only", 0, media.streams.count { it.type == "video" })
            assertEquals("Original AAC should remain compressed", "aac", media.streams.first { it.type == "audio" }.codec)
            assertEquals("ORIGINAL must preserve the encoded AAC samples", packets(source), packets(audio))
            assertTrue("The bridge should issue TDLib range reads", access.readCount.get() > 0)
            assertTrue("Range reads must stay within the bridge segment bound", access.largestRead.get() <= ExternalAudioPolicy.MAX_READ_SEGMENT_BYTES)
            assertEquals("The source lease should start once", 1, access.beginCount.get())
            assertEquals("The source lease must end once", 1, access.endCount.get())
            assertEquals("The source lease must be released", 0, access.leaseCount.get())
            val stagedAfter = context.cacheDir.listFiles()?.filter { it.name.startsWith("input-") && it.extension == "video" }?.map { it.name }?.toSet().orEmpty()
            assertEquals("Bot audio save created a full-video staging file", stagedBefore, stagedAfter)
            assertTrue("Completed audio should be in the library", AppStore.history.value.any { it.path == saved!!.path })
            AppStore.load(context)
            assertTrue("Audio history must survive a reload", AppStore.history.value.any { it.path == saved!!.path })
        } finally {
            BotAudioSaveTestHooks.accessOverride = null
            saved?.let { AppStore.remove(context, it) }
            AppStore.load(context)
            source.delete()
        }
    }

    @Test(timeout = 120_000) fun cancellingBotSaveUnblocksRangeReadReleasesLeaseAndDeletesPartialAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "qa-bot-cancel-${System.nanoTime()}.mp4")
        try {
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "aac", "-movflags", "+faststart", source.absolutePath)).returnCode))
            val access = TestRangeAccess(source)
            BotAudioSaveTestHooks.accessOverride = access
            BotAudioSaveTestHooks.beforeConvert = {
                assertNotNull("The bot source bridge must be active during conversion", it)
                access.blockReads.set(true)
            }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(800)
            AppStore.state.value = ConversionState()
            startSave(context, 8, "qa-bot-cancel", listedSize = 2_324_555_125L)
            assertTrue("Conversion did not reach its audio-writing stage", awaitState { it.running && it.stage == "Telegram 오디오 저장 중" })
            assertTrue("Conversion did not request a TDLib range", access.blockedRead.await(15, TimeUnit.SECONDS))
            context.startService(Intent(context, ConversionService::class.java).setAction("cancel_bot"))
            assertTrue("Bot cancellation did not finish", awaitState { !it.running && it.stage == "취소했어요" })
            assertTrue("The blocked range reader did not observe cancellation", access.cancelledRead.await(10, TimeUnit.SECONDS))
            assertEquals("Cancellation must release the source lease", 0, access.leaseCount.get())
            assertEquals("Cancellation must end the lease exactly once", 1, access.endCount.get())
            assertFalse("Cancelled audio must not appear in history", AppStore.history.value.any { it.name.startsWith("qa-bot-cancel-") })
            assertFalse("Cancelled partial output must be removed", File(context.filesDir, "audio").listFiles().orEmpty().any { it.name.startsWith("qa-bot-cancel-") })
        } finally {
            BotAudioSaveTestHooks.accessOverride = null
            BotAudioSaveTestHooks.beforeConvert = null
            source.delete()
        }
    }

    private fun startSave(context: android.content.Context, fileId: Int, name: String, listedSize: Long) {
        context.startForegroundService(Intent(context, ConversionService::class.java)
            .putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, fileId)
            .putExtra(BotRuntime.EXTRA_TDLIB_NAME, name)
            .putExtra(BotRuntime.EXTRA_TDLIB_SIZE, listedSize)
            .putExtra("format", AudioFormat.ORIGINAL.name))
    }

    private fun awaitState(predicate: (ConversionState) -> Boolean): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < until) {
            if (predicate(AppStore.state.value)) return true
            Thread.sleep(100)
        }
        return predicate(AppStore.state.value)
    }

    private fun packets(file: File): Pair<Int, String> {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(audioTrack)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
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

    private class TestRangeAccess(private val source: File) : TdlibFileAccess {
        val leaseCount = AtomicInteger()
        val beginCount = AtomicInteger()
        val endCount = AtomicInteger()
        val readCount = AtomicInteger()
        val largestRead = AtomicInteger()
        val blockReads = java.util.concurrent.atomic.AtomicBoolean(false)
        val blockedRead = CountDownLatch(1)
        val cancelledRead = CountDownLatch(1)

        override fun acquireFile(fileId: Int): TdApi.File {
            assertTrue("Unexpected bot file id", fileId == 7 || fileId == 8)
            return TdApi.File().apply {
                id = fileId
                size = source.length()
                expectedSize = source.length()
                local = TdApi.LocalFile()
                remote = TdApi.RemoteFile()
            }
        }

        override fun beginPlayback(fileId: Int) {
            beginCount.incrementAndGet()
            leaseCount.incrementAndGet()
        }

        override fun endPlayback(fileId: Int) {
            endCount.incrementAndGet()
            leaseCount.decrementAndGet()
        }

        override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
            if (blockReads.get()) {
                blockedRead.countDown()
                while (!cancelled()) {
                    try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
                cancelledRead.countDown()
                throw java.io.InterruptedIOException("Test range read cancelled")
            }
            if (cancelled()) throw java.io.InterruptedIOException("Test range read cancelled")
            readCount.incrementAndGet()
            largestRead.updateAndGet { maxOf(it, length) }
            if (offset >= source.length()) return -1
            return RandomAccessFile(source, "r").use { input ->
                input.seek(offset)
                input.read(buffer, 0, minOf(length.toLong(), source.length() - offset).toInt())
            }
        }
    }
}
