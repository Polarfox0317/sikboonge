package io.github.polarfox0317.sikboonge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import io.github.polarfox0317.sikboonge.ui.state.SelectedVideoThumbnail

/** Reads only bounded artwork from app-owned caches and returns compressed, system-ready bytes. */
internal object SystemMediaArtworkLoader {
    const val MAX_SOURCE_BYTES = 512 * 1024
    const val MAX_BASE64_CHARS = 100_000
    const val MAX_EDGE = 512
    const val MAX_OUTPUT_BYTES = 256 * 1024
    private const val MAX_SOURCE_EDGE = 4096
    private const val MAX_SOURCE_PIXELS = 16_000_000L

    fun load(context: Context, path: String?, base64: String?): ByteArray? {
        return try {
            val source = readOwnedSource(context, path) ?: decodeBase64(base64) ?: return null
            normalize(source)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: LinkageError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readOwnedSource(context: Context, path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val canonicalFile = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (AudioArtworkStore.isOwnedArtworkPath(context.filesDir, path)) {
            return readBoundedFile(canonicalFile)
        }
        BotVideoThumbnailData.decodeFile(context, path)?.let { bytes ->
            return bytes.takeIf { it.size in 1..MAX_SOURCE_BYTES }
        }
        if (SelectedVideoThumbnail.isCachedFile(context, path)) {
            return readBoundedFile(canonicalFile)
        }
        return runCatching {
            val file = canonicalFile
            val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
            val owned = roots.any { root -> file.path.startsWith(root.path + File.separator) }
            if (!owned || !file.isFile || file.length() !in 1..MAX_SOURCE_BYTES.toLong()) return null
            readBoundedFile(file)
        }.getOrNull()
    }

    private fun readBoundedFile(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES.toLong()) return null
        file.inputStream().use { input -> readBounded(input, MAX_SOURCE_BYTES) }
    }.getOrNull()

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun decodeBase64(value: String?): ByteArray? {
        if (value.isNullOrBlank() || value.length > MAX_BASE64_CHARS) return null
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size in 1..MAX_SOURCE_BYTES }
    }

    private fun normalize(source: ByteArray): ByteArray? {
        if (source.isEmpty() || source.size > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_SOURCE_EDGE || height !in 1..MAX_SOURCE_EDGE ||
            width.toLong() * height > MAX_SOURCE_PIXELS
        ) return null

        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > MAX_EDGE) sampleSize *= 2
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            })
        }.getOrNull() ?: return null
        return try {
            compressBounded(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun compressBounded(bitmap: Bitmap): ByteArray? {
        if (bitmap.hasAlpha()) {
            val png = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, png) && png.size() in 1..MAX_OUTPUT_BYTES) {
                return png.toByteArray()
            }
        }

        val output = ByteArrayOutputStream()
        for (quality in intArrayOf(88, 76, 64, 52, 40, 30)) {
            output.reset()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) &&
                output.size() in 1..MAX_OUTPUT_BYTES
            ) return output.toByteArray()
        }
        return null
    }
}
