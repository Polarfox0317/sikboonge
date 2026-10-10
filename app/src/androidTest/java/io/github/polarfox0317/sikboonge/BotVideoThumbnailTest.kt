package io.github.polarfox0317.sikboonge

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotVideoThumbnailTest {
    private val malformedPng = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a2cAAAAASUVORK5CYII=",
        Base64.DEFAULT,
    )

    @Test fun validThumbnailCachesInsidePrivateRootAndRejectsOutsidePath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDir = File(context.cacheDir, "bot-thumb-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(sourceDir, "input.png").apply { writeBytes(encodeFixture(Bitmap.CompressFormat.PNG)) }
        val malformed = File(sourceDir, "malformed.png").apply { writeBytes(malformedPng) }
        val cached = BotVideoThumbnailData.cache(context, Long.MIN_VALUE, source)

        try {
            assertNotNull(cached)
            assertNotNull(BotVideoThumbnailData.decodeFile(context, cached))
            assertTrue(BotVideoThumbnailData.hasRenderableCache(context, cached))
            assertNotNull(BotVideoThumbnailData.decodeSampled(source.readBytes()))
            assertNull(BotVideoThumbnailData.decodeSampled(malformedPng))
            assertNull(BotVideoThumbnailData.cache(context, Long.MIN_VALUE, malformed))
            assertNull(BotVideoThumbnailData.decodeFile(context, source.absolutePath))
            assertNull(BotVideoThumbnailData.decodeMini("A".repeat(BotVideoThumbnailData.MINI_BASE64_LIMIT + 1)))
            val replacement = BotVideoThumbnailData.cache(context, Long.MIN_VALUE, source)
            assertNotNull(replacement)
            assertTrue(cached != replacement)
            replacement?.let { File(it).delete() }
        } finally {
            cached?.let { File(it).delete() }
            source.delete()
            malformed.delete()
            sourceDir.delete()
        }
    }

    @Test fun pngJpegAndWebpProduceRealBitmapsAndMiniJpegDecodes() {
        val formats = listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)
        val encoded = formats.map { format -> format to encodeFixture(format) }

        encoded.forEach { (format, bytes) ->
            val bitmap = BotVideoThumbnailData.decodeSampled(bytes)
            assertNotNull("$format must decode into an actual bitmap", bitmap)
            assertTrue(requireNotNull(bitmap).width <= 256)
            bitmap.recycle()
        }

        val mini = BotVideoThumbnailData.encodeMini(encoded.first { it.first == Bitmap.CompressFormat.JPEG }.second)
        val decodedMini = BotVideoThumbnailData.decodeMini(mini)
        assertNotNull(decodedMini)
        assertNotNull(requireNotNull(decodedMini).let(BotVideoThumbnailData::decodeSampled))
        assertNull(BotVideoThumbnailData.decodeSampled(byteArrayOf(0, 1, 2, 3)))
    }

    private fun encodeFixture(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 90, 180)) }
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(format, 90, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
