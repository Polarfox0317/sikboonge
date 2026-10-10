package io.github.polarfox0317.sikboonge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Thumbnail input limits apply before decoding, and decoded images are downsampled off the UI thread. */
internal object BotVideoThumbnailData {
    const val MAX_BYTES = 512 * 1024
    const val MAX_EDGE = 4096
    const val MAX_PIXELS = 16_000_000L
    const val MINI_BASE64_LIMIT = 100_000

    fun encodeMini(data: ByteArray?): String {
        if (data == null || data.isEmpty() || data.size > 64 * 1024 || !hasRenderableImage(data)) return ""
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decodeMini(encoded: String): ByteArray? {
        if (encoded.isBlank() || encoded.length > MINI_BASE64_LIMIT) return null
        val data = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return data.takeIf { it.size <= 64 * 1024 && hasRenderableImage(it) }
    }

    fun decodeFile(context: Context, path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val base = File(context.filesDir, "telegram-bot/thumbnails").canonicalFile
            val file = File(path).canonicalFile
            require(file.path.startsWith(base.path + File.separator))
            require(file.isFile && file.length() in 1..MAX_BYTES.toLong())
            file.readBytes().takeIf(::hasSafeBounds)
        }.getOrNull()
    }

    fun hasRenderableCache(context: Context, path: String?): Boolean {
        val bytes = decodeFile(context, path) ?: return false
        val bitmap = decodeSampled(bytes) ?: return false
        bitmap.recycle()
        return true
    }

    fun cache(context: Context, messageId: Long, source: File): String? = runCatching {
        require(source.isFile && source.length() in 1..MAX_BYTES.toLong())
        val bytes = source.readBytes()
        require(hasRenderableImage(bytes))
        val dir = File(context.filesDir, "telegram-bot/thumbnails")
        check(dir.mkdirs() || dir.isDirectory)
        val canonicalDir = dir.canonicalFile
        val target = File(canonicalDir, "video_${messageId}_${UUID.randomUUID()}.img").canonicalFile
        require(target.parentFile == canonicalDir)
        try {
            target.writeBytes(bytes)
            target.absolutePath
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }.getOrNull()

    fun decodeSampled(bytes: ByteArray): Bitmap? {
        if (bytes.size > MAX_BYTES || !hasSafeBounds(bytes)) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val edge = maxOf(options.outWidth, options.outHeight)
        var sample = 1
        while (edge / sample > 256) sample *= 2
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun hasRenderableImage(bytes: ByteArray): Boolean {
        val bitmap = decodeSampled(bytes) ?: return false
        bitmap.recycle()
        return true
    }

    private fun hasSafeBounds(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth in 1..MAX_EDGE && options.outHeight in 1..MAX_EDGE &&
            options.outWidth.toLong() * options.outHeight <= MAX_PIXELS
    }
}

internal object BotThumbnailPolicy {
    fun rowsNeedingMetadata(videos: List<BotVideo>, hasRenderableCache: (BotVideo) -> Boolean): List<BotVideo> =
        videos.filterNot(hasRenderableCache)

    fun rowsForCompletedFile(videos: List<BotVideo>, fileId: Int): List<BotVideo> =
        if (fileId <= 0) emptyList() else videos.filter { it.thumbnailFileId == fileId }
}

/** TDLib stores image thumbnails beside its database, separately from media under files/. */
internal object BotThumbnailPathPolicy {
    fun isOwnedSource(ownRoot: File, candidate: File): Boolean = runCatching {
        val root = ownRoot.canonicalFile
        val path = candidate.canonicalFile
        val allowedRoots = listOf(File(root, "files"), File(root, "tdlib/thumbnails"))
        allowedRoots.any { allowed ->
            val canonicalAllowed = allowed.canonicalFile
            path.path.startsWith(canonicalAllowed.path + File.separator)
        }
    }.getOrDefault(false)
}

@Composable
fun BotVideoThumbnail(video: BotVideo, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(null, video.thumbnailPath, video.thumbnailBase64) {
        value = withContext(Dispatchers.IO) {
            val full = BotVideoThumbnailData.decodeFile(context, video.thumbnailPath)
            val fullBitmap = full?.let(BotVideoThumbnailData::decodeSampled)
            fullBitmap ?: BotVideoThumbnailData.decodeMini(video.thumbnailBase64)
                ?.let(BotVideoThumbnailData::decodeSampled)
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "영상 미리보기", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("▶", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
    }
}
