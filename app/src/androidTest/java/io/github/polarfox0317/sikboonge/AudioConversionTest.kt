package io.github.polarfox0317.sikboonge

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioConversionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "qa-conversion").apply { mkdirs() }
    private lateinit var input: File
    @Before fun fixture() {
        input = File(dir, "sine.mp4")
        val result = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=32x32:r=10:d=2", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2", "-c:v", "mpeg4", "-c:a", "aac", "-ac", "2", "-shortest", input.absolutePath))
        assertTrue("Synthetic video generation: ${result.failStackTrace} ${result.allLogsAsString}", ReturnCode.isSuccess(result.returnCode))
    }
    @After fun cleanup() { dir.listFiles()?.forEach { it.delete() } }
    @Test fun originalAacPacketsAreIdentical() {
        val (codec, duration) = AudioEngine.inspect(input)
        assertEquals("aac", codec)
        val plan = AudioEngine.plan(AudioFormat.ORIGINAL, codec)
        val output = File(dir, "original.m4a")
        AudioEngine.convert(input, output, plan, duration, {}, {})
        assertArrayEquals(packets(input), packets(output))
        assertEquals("aac", AudioEngine.inspect(output).first)
        decode(output, "original")
    }
    @Test fun wavAndMp3ContainRealDecodableAudio() {
        val (codec, duration) = AudioEngine.inspect(input)
        AudioFormat.entries.filter { it != AudioFormat.ORIGINAL }.forEach { format ->
            val plan = AudioEngine.plan(format, codec)
            val output = File(dir, "converted.${plan.extension}")
            AudioEngine.convert(input, output, plan, duration, {}, {})
            assertTrue(output.length() > 1000)
            val info = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(output.absolutePath).mediaInformation.streams.first { it.type == "audio" }
            assertEquals(if (format == AudioFormat.WAV) "pcm_f32le" else "mp3", info.codec)
            assertEquals("48000", info.sampleRate)
            assertEquals(2L, info.getNumberProperty("channels"))
            decode(output, format.name)
            if (format == AudioFormat.WAV) verifyPlayer(output)
        }
    }
    @Test fun rejectsSilentVideoAndInvalidFile() {
        val silent = File(dir, "silent.mp4")
        assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y", "-i", input.absolutePath, "-an", "-c:v", "copy", silent.absolutePath)).returnCode))
        assertThrows(IllegalArgumentException::class.java) { AudioEngine.inspect(silent) }
        val invalid = File(dir, "invalid.video").apply { writeText("not video") }
        assertThrows(IllegalArgumentException::class.java) { AudioEngine.inspect(invalid) }
    }
    @Test fun cancellationStopsNativeSession() {
        val latch = CountDownLatch(1)
        var cancelled = false
        val session = FFmpegKit.executeWithArgumentsAsync(arrayOf("-re", "-f", "lavfi", "-i", "sine=frequency=440:duration=120", "-f", "null", "-"), { cancelled = ReturnCode.isCancel(it.returnCode); latch.countDown() })
        Thread.sleep(300)
        FFmpegKit.cancel(session.sessionId)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(cancelled)
    }
    private fun packets(file: File): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            while (true) { buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break; digest.update(buffer.array(), 0, size); extractor.advance() }
            return digest.digest()
        } finally { extractor.release() }
    }
    private fun decode(file: File, suffix: String) {
        val pcm = File(dir, "$suffix.pcm")
        val result = FFmpegKit.executeWithArguments(arrayOf("-y", "-i", file.absolutePath, "-f", "s16le", "-c:a", "pcm_s16le", pcm.absolutePath))
        assertTrue(ReturnCode.isSuccess(result.returnCode))
        assertTrue(pcm.length() > 300000)
        assertTrue(pcm.readBytes().any { it != 0.toByte() })
    }
    private fun verifyPlayer(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var failure: String? = null
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) latch.countDown() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure = error.toString(); latch.countDown() }
                })
                setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare(); play()
            }
        }
        try { assertTrue("WAV player timeout", latch.await(15, TimeUnit.SECONDS)); assertNull("WAV playback failure", failure) } finally { instrumentation.runOnMainSync { player?.release() } }
    }
}
