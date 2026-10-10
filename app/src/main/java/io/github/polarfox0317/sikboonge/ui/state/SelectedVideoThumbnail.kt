package io.github.polarfox0317.sikboonge.ui.state

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import io.github.polarfox0317.sikboonge.BotVideoThumbnailData
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded Convert artwork cache shared by real selections and the debug sample. */
internal object SelectedVideoThumbnail {
    private const val MaximumSide = 512
    private val cacheLock = Any()

    // Call from a worker dispatcher: providers and frame decoding can block.
    fun create(context: Context, uri: Uri): String? = synchronized(cacheLock) {
        runCatching {
            val directory = File(context.cacheDir, "convert-thumbnails")
            check(directory.isDirectory || directory.mkdirs())
            val hash = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val output = File(directory, "$hash.jpg")
            val frame = if (Build.VERSION.SDK_INT >= 29) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(MaximumSide, MaximumSide), null) }.getOrNull()
            } else null
            val bitmap = frame ?: retrieveFrame(context, uri) ?: return@synchronized null
            val scale = MaximumSide.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(MaximumSide)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                else bitmap
            val temporary = File(directory, "$hash.tmp")
            try {
                temporary.outputStream().use { stream -> check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)) }
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                output.absolutePath
            } finally {
                temporary.delete()
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun retrieveFrame(context: Context, uri: Uri): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") retriever.setDataSource(requireNotNull(uri.path))
            else retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    fun isCachedFile(context: Context, path: String?): Boolean = runCatching {
        val file = File(requireNotNull(path)).canonicalFile
        file.parentFile == File(context.cacheDir, "convert-thumbnails").canonicalFile && file.isFile
    }.getOrDefault(false)

    fun delete(context: Context, path: String?, shouldDelete: () -> Boolean = { true }) = synchronized(cacheLock) {
        if (shouldDelete() && isCachedFile(context, path)) runCatching { File(requireNotNull(path)).delete() }
        Unit
    }

    /** Validated bytes for the shared PlayerArtwork loader. */
    fun decodeFile(context: Context, path: String?): ByteArray? = synchronized(cacheLock) {
        if (!isCachedFile(context, path)) return@synchronized null
        runCatching {
            val file = File(requireNotNull(path))
            require(file.length() in 1..BotVideoThumbnailData.MAX_BYTES.toLong())
            file.readBytes()
        }.getOrNull()
    }
}
