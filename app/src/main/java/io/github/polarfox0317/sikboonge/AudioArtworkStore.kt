package io.github.polarfox0317.sikboonge

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Copies source artwork into independent, bounded JPEG files owned by saved audio rows. */
internal object AudioArtworkStore {
    const val EXTRA_ARTWORK_PATH = "artwork_path"
    const val EXTRA_ARTWORK_BASE64 = "artwork_base64"

    private const val MAX_SOURCE_BYTES = 512 * 1024
    private const val MAX_SOURCE_EDGE = 4096
    private const val MAX_SOURCE_PIXELS = 16_000_000L
    private const val OUTPUT_EDGE = 512
    private const val MAX_BASE64_CHARS = 100_000
    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "flac", "ogg")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "wmv", "flv", "3gp", "mpeg", "mpg", "ts")

    /** A safe path for renderers: direct child of the app's library-artwork directory. */
    fun isOwnedArtworkPath(filesDir: File, path: String?): Boolean = artworkFile(filesDir, path)?.let { file ->
        file.isFile && file.length() in 1..MAX_SOURCE_BYTES.toLong()
    } == true

    /** Captures local or base64 artwork; all failures are non-fatal to audio saving. */
    fun capture(context: Context, path: String?, base64: String?): String? {
        if (path.isNullOrBlank() && base64.isNullOrBlank()) return null
        return try {
            val jpeg = readOwnedSource(context.filesDir, path)?.let(::normalizeJpeg)
                ?: decodeBase64(base64)?.let(::normalizeJpeg)
                ?: throw IllegalArgumentException("No usable artwork source was available.")
            writeArtwork(context, jpeg)
        } catch (error: Exception) {
            AppDiagnostics.failure("library-artwork", error)
            null
        }
    }

    /** Extracts a bounded source frame and stores it as owned library artwork. */
    fun captureVideoThumbnail(context: Context, uri: Uri, cachedVideo: File? = null): String? = runCatching {
        val cachedFrame = cachedVideo?.takeIf { it.isFile }?.let(::retrieveVideoFrame)
        val providerThumbnail = if (cachedFrame == null && Build.VERSION.SDK_INT >= 29) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(OUTPUT_EDGE, OUTPUT_EDGE), null) }.getOrNull()
        } else null
        val source = cachedFrame ?: providerThumbnail ?: retrieveVideoFrame(context, uri) ?: return@runCatching null
        try {
            val scale = minOf(1f, OUTPUT_EDGE.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
            val bounded = if (scale < 1f) Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            ) else source
            try {
                val jpeg = ByteArrayOutputStream()
                for (quality in intArrayOf(82, 72, 62, 52, 42)) {
                    jpeg.reset()
                    if (!bounded.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) return@runCatching null
                    if (jpeg.size() in 1..MAX_SOURCE_BYTES) return@runCatching writeArtwork(context, jpeg.toByteArray())
                }
                null
            } finally {
                if (bounded !== source) bounded.recycle()
            }
        } finally {
            source.recycle()
        }
    }.onFailure { runCatching { AppDiagnostics.failure("library-artwork-video", it) } }.getOrNull()

    private fun retrieveVideoFrame(file: File): Bitmap? = retrieveVideoFrame { retriever ->
        retriever.setDataSource(file.absolutePath)
    }

    private fun retrieveVideoFrame(context: Context, uri: Uri): Bitmap? = retrieveVideoFrame { retriever ->
        retriever.setDataSource(context, uri)
    }

    private fun retrieveVideoFrame(setDataSource: (MediaMetadataRetriever) -> Unit): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(retriever)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun writeArtwork(context: Context, jpeg: ByteArray): String? {
        require(jpeg.size in 1..MAX_SOURCE_BYTES)
        val directory = File(context.filesDir, "audio-artwork").apply { check(mkdirs() || isDirectory) }.canonicalFile
        val target = File(directory, "artwork_${UUID.randomUUID()}.jpg").canonicalFile
        check(target.parentFile == directory)
        val temp = File(directory, ".${target.name}.tmp")
        try {
            temp.writeBytes(jpeg)
            Files.move(temp.toPath(), target.toPath())
        } catch (error: Exception) {
            temp.delete()
            target.delete()
            throw error
        }
        return target.absolutePath.takeIf { isOwnedArtworkPath(context.filesDir, it) }
    }

    /** Removes only unreferenced files under audio-artwork; bot thumbnail caches are never targets. */
    fun deleteUnreferenced(context: Context, candidates: Collection<String?>, retained: Collection<String?>) {
        val retainedPaths = retained.mapNotNull { artworkFile(context.filesDir, it)?.path }.toSet()
        candidates.mapNotNull { artworkFile(context.filesDir, it) }
            .distinctBy { it.path }
            .filterNot { it.path in retainedPaths }
            .forEach { it.delete() }
    }

    /** Matches only the generated title-timestamp-extension shape used by ConversionService. */
    fun matchesGeneratedAudioName(audioName: String, videoTitle: String): Boolean {
        val titles = mutableSetOf(sanitizeOutputTitle(videoTitle))
        val videoExtension = videoTitle.substringAfterLast('.', "").lowercase()
        if (videoExtension in VIDEO_EXTENSIONS) {
            titles += sanitizeOutputTitle(videoTitle.substringBeforeLast('.'))
        }
        return titles.any { title ->
            val prefix = "$title-"
            if (!audioName.startsWith(prefix, ignoreCase = false)) return@any false
            val generatedTail = audioName.removePrefix(prefix)
            val timestamp = generatedTail.substringBeforeLast('.', "")
            val extension = generatedTail.substringAfterLast('.', "").lowercase()
            timestamp.isNotEmpty() && timestamp.all { it in '0'..'9' } && extension in AUDIO_EXTENSIONS
        }
    }

    fun sanitizeOutputTitle(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(70)
        .ifBlank { "오디오" }

    private fun readOwnedSource(filesDir: File, path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val allowedDirectories = listOf(
            File(filesDir, "audio-artwork"),
            File(filesDir, "telegram-bot/thumbnails"),
            File(filesDir, "tdlib/thumbnails"),
        )
        if (!allowedDirectories.any { directory ->
                val root = runCatching { directory.canonicalPath }.getOrNull() ?: return@any false
                file.path.startsWith(root + File.separator)
            }) return null
        if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES.toLong()) return null
        return runCatching { file.readBytes().takeIf { it.size <= MAX_SOURCE_BYTES } }.getOrNull()
    }

    private fun decodeBase64(value: String?): ByteArray? {
        if (value.isNullOrBlank() || value.length > MAX_BASE64_CHARS) return null
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size in 1..MAX_SOURCE_BYTES }
    }

    private fun normalizeJpeg(source: ByteArray): ByteArray? {
        if (source.isEmpty() || source.size > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_SOURCE_EDGE || height !in 1..MAX_SOURCE_EDGE || width.toLong() * height > MAX_SOURCE_PIXELS) return null
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > OUTPUT_EDGE) sampleSize *= 2
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            })
        }.getOrNull() ?: return null
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) return null
            output.toByteArray().takeIf { it.size in 1..MAX_SOURCE_BYTES }
        } finally {
            bitmap.recycle()
        }
    }

    private fun artworkFile(filesDir: File, path: String?): File? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val root = File(filesDir, "audio-artwork").canonicalFile
            val file = File(path).canonicalFile
            if (file.parentFile == root && file.extension.equals("jpg", ignoreCase = true)) file else null
        }.getOrNull()
    }
}
