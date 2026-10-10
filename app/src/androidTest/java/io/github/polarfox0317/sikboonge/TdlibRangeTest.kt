package io.github.polarfox0317.sikboonge

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class TdlibRangeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun prefixReaderDoesNotExposeUndownloadedBytesAndEnforcesOwnedPaths() {
        val root = File(context.cacheDir, "qa-prefix-${System.nanoTime()}").apply { mkdirs() }
        val input = File(root, "part.mp4").apply { writeBytes(ByteArray(256) { it.toByte() }) }
        try {
            val file = tdFile(input, 32, 10, false, 256)
            val buffer = ByteArray(32)
            assertEquals(10, TdlibPrefixReader.read(file, root, 32, buffer, 32))
            assertArrayEquals(ByteArray(10) { (it + 32).toByte() }, buffer.copyOf(10))
            assertNull(TdlibPrefixReader.read(file, root, 31, buffer, 32))
            assertNull(TdlibPrefixReader.read(file, root, 42, buffer, 32))
            file.local.downloadedPrefixSize = 20
            assertEquals(10, TdlibPrefixReader.read(file, root, 42, buffer, 32))
            file.local.isDownloadingCompleted = true
            assertEquals(-1, TdlibPrefixReader.read(file, root, 256, buffer, 32))
            try { TdlibPrefixReader.read(file, File(root, "other"), 0, buffer, 32); fail("Outside-cache path was accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { input.delete(); root.delete() }
    }

    @Test fun exactSizeWinsAndApproximateExpectedSizeNeverTruncatesReads() {
        val file = TdApi.File().apply { id = 7; size = 256; expectedSize = 10; local = TdApi.LocalFile(); remote = TdApi.RemoteFile() }
        val access = object : TdlibFileAccess {
            override fun acquireFile(fileId: Int) = file
            override fun beginPlayback(fileId: Int) { }
            override fun endPlayback(fileId: Int) { }
            override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int =
                if (offset >= 256) -1 else minOf(length, (256 - offset).toInt()).also { n -> repeat(n) { buffer[it] = (offset + it).toByte() } }
        }
        val source = TdlibMediaDataSource(access)
        val spec = DataSpec.Builder().setUri(Uri.parse("tdlib://file/7")).build()
        assertEquals(256L, source.open(spec))
        assertEquals(256, source.read(ByteArray(256), 0, 256))
        source.close()
        file.size = 0
        assertEquals(-1L, source.open(spec))
        assertEquals(256, source.read(ByteArray(256), 0, 256))
        assertEquals(-1, source.read(ByteArray(1), 0, 1))
        source.close()
    }

    @Test fun largeVideosKeepLongOffsetsAndBoundedReads() {
        val file = TdApi.File().apply {
            id = 7
            size = 3L * 1024 * 1024 * 1024
            expectedSize = 4L * 1024 * 1024 * 1024
            local = TdApi.LocalFile()
            remote = TdApi.RemoteFile()
        }
        val start = 2L * 1024 * 1024 * 1024 + 7
        var requestedBytes = 0
        var lastOffset = -1L
        var leases = 0
        val access = object : TdlibFileAccess {
            override fun acquireFile(fileId: Int) = file
            override fun beginPlayback(fileId: Int) { leases++ }
            override fun endPlayback(fileId: Int) { leases-- }
            override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
                lastOffset = offset
                requestedBytes += length
                repeat(length) { buffer[it] = (offset + it).toByte() }
                return length
            }
        }
        val source = TdlibMediaDataSource(access)
        try {
            val spec = DataSpec.Builder().setUri(Uri.parse("tdlib://file/7")).setPosition(start).build()
            assertEquals(file.size - start, source.open(spec))
            assertEquals(1, leases)
            val buffer = ByteArray(32)
            assertEquals(16, source.read(buffer, 4, 16))
            assertEquals(start, lastOffset)
            assertEquals(16, requestedBytes)
            assertArrayEquals(ByteArray(16) { (start + it).toByte() }, buffer.copyOfRange(4, 20))
        } finally { source.close() }
        assertEquals(0, leases)
    }

    @Test fun tdlibUriStartsAudioWithMetadataAboveOneGiBBeforeWholeFixtureIsRead() {
        val root = File(context.cacheDir, "qa-tdlib-${System.nanoTime()}").apply { mkdirs() }
        val input = File(root, "stream.mp4")
        val readBytes = AtomicLong()
        var leases = 0
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60", "-c:a", "aac", "-movflags", "+faststart", input.absolutePath))
            assertTrue(ReturnCode.isSuccess(session.returnCode))
            val file = tdFile(input, 0, 0, false, 3L * 1024 * 1024 * 1024)
            val access = object : TdlibFileAccess {
                override fun acquireFile(fileId: Int) = file
                override fun beginPlayback(fileId: Int) { leases++ }
                override fun endPlayback(fileId: Int) { leases-- }
                override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
                    if (cancelled()) throw java.io.InterruptedIOException()
                    Thread.sleep(50)
                    if (offset >= input.length()) return -1
                    file.local.downloadedPrefixSize = maxOf(file.local.downloadedPrefixSize, minOf(input.length(), offset + 64 * 1024))
                    return requireNotNull(TdlibPrefixReader.read(file, root, offset, buffer, length)).also { if (it > 0) readBytes.addAndGet(it.toLong()) }
                }
            }
            val direct = TdlibMediaDataSource(access)
            direct.open(DataSpec.Builder().setUri(Uri.parse("tdlib://file/7")).setPosition(100).setLength(100).build())
            val actual = ByteArray(100)
            assertEquals(100, direct.read(actual, 0, 100))
            assertArrayEquals(input.readBytes().copyOfRange(100, 200), actual)
            direct.close()
            try { direct.read(actual, 0, 1); fail("Closed source accepted reads") } catch (_: java.io.IOException) { }
            readBytes.set(0)
            StreamingTestHooks.tdlibAccessOverride = access
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(800)
            instrumentation.runOnMainSync { StreamPlayback.playBot(context, BotVideo(7, 7, 7, "QA bot audio", file.expectedSize, 60, 0)) }
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline && !StreamPlayback.state.value.playing && StreamPlayback.state.value.error == null) Thread.sleep(100)
            assertTrue("TDLib Media3 fixture did not play: ${StreamPlayback.state.value}", StreamPlayback.state.value.playing)
            assertEquals(7, StreamPlayback.state.value.botFileId)
            assertEquals("QA bot audio", StreamPlayback.state.value.title)
            assertTrue("Playback read the complete fixture before startup", readBytes.get() < input.length())
            assertFalse("Streaming should not start a full-file Save", AppStore.state.value.running)
        } finally {
            instrumentation.runOnMainSync { StreamPlayback.stop() }
            Thread.sleep(400)
            StreamingTestHooks.tdlibAccessOverride = null
            input.delete(); root.delete()
        }
        assertEquals("Playback leases leaked", 0, leases)
    }

    private fun tdFile(input: File, offset: Long, prefix: Long, completed: Boolean, expected: Long) = TdApi.File().apply {
        id = 7; size = expected; expectedSize = expected
        local = TdApi.LocalFile(input.absolutePath, true, true, true, completed, offset, prefix, prefix)
        remote = TdApi.RemoteFile()
    }
}
