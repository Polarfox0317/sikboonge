package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native ABI/codec/error-path checks using only generated media in this test's cache directory. */
@RunWith(AndroidJUnit4::class)
class NativeDependencySmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun succeeds(vararg arguments: String) {
        val session = FFmpegKit.executeWithArguments(arrayOf(*arguments))
        assertTrue("FFmpeg failed with ${session.returnCode}: ${session.failStackTrace}",
            ReturnCode.isSuccess(session.returnCode))
    }

    @Test fun generatedAudioEncodesAndDecodesAacAndMp3() {
        val directory = File(context.cacheDir, "native-upgrade-${System.nanoTime()}").apply { mkdirs() }
        val wave = File(directory, "source.wav")
        val aac = File(directory, "audio.m4a")
        val mp3 = File(directory, "audio.mp3")
        try {
            succeeds("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "pcm_s16le", wave.path)
            succeeds("-i", wave.path, "-c:a", "aac", aac.path)
            succeeds("-i", wave.path, "-c:a", "libmp3lame", mp3.path)
            for (encoded in listOf(aac, mp3)) {
                assertTrue("Empty encoded file: ${encoded.name}", encoded.length() > 100L)
                succeeds("-i", encoded.path, "-f", "null", "-")
            }
        } finally {
            listOf(wave, aac, mp3).forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun invalidFilterReturnsAnErrorAndTheNextSessionStillWorks() {
        val failed = FFmpegKit.executeWithArguments(arrayOf(
            "-f", "lavfi", "-i", "sine=duration=0.1",
            "-filter_complex", "sikboonge_nonexistent_filter", "-f", "null", "-",
        ))
        assertFalse("Invalid filter unexpectedly succeeded", ReturnCode.isSuccess(failed.returnCode))
        succeeds("-f", "lavfi", "-i", "sine=duration=0.1", "-f", "null", "-")
    }
}
