package io.github.polarfox0317.sikboonge

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImportFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun foreground() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(800)
    }
    private fun waitForResult(): ConversionState {
        val deadline = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = AppStore.state.value
            if (!state.running && (state.result != null || state.error != null)) return state
            Thread.sleep(100)
        }
        context.startService(Intent(context, ConversionService::class.java).setAction("cancel"))
        throw AssertionError("Foreground import timed out")
    }
    @Test fun sharedContentUriImportsThroughForegroundService() {
        val input = File(File(context.filesDir, "audio").apply { mkdirs() }, "qa-share-source.mp4")
        try {
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=32x32:r=10:d=1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:v", "mpeg4", "-c:a", "aac", "-shortest", input.absolutePath)).returnCode))
            foreground()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", input)
            val shared = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("QA 영상", uri) }
            context.startActivity(shared)
            Thread.sleep(300)
            AppStore.state.value = ConversionState()
            val service = Intent(context, ConversionService::class.java).putExtra("uri", uri.toString()).putExtra("format", AudioFormat.ORIGINAL.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("QA 영상", uri) }
            context.startForegroundService(service)
            val result = waitForResult()
            assertNull(result.error)
            assertNotNull(result.result)
            assertEquals("audio/mp4", result.result!!.mime)
            assertTrue(File(result.result!!.path).length() > 1000)
            Thread.sleep(500)
            assertFalse("Late native progress must not reset completion", AppStore.state.value.running)
            assertEquals(result.result!!.path, AppStore.state.value.result?.path)
            AppStore.remove(context, result.result!!)
            AppStore.history.value.filter { it.name.startsWith("qa-share-source-") }.forEach { AppStore.remove(context, it) }
        } finally { input.delete() }
    }
}
