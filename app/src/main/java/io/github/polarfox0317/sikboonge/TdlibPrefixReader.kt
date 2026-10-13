package io.github.polarfox0317.sikboonge

import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile

/** Null means the requested prefix isn't downloaded yet; EOF is valid only for a complete local file. */
internal object TdlibPrefixReader {
    fun read(file: TdApi.File, root: File, offset: Long, buffer: ByteArray, length: Int): Int? {
        val local = file.local
        if (local.path.isBlank()) return null
        val path = File(local.path).canonicalFile
        require(path.path.startsWith(root.canonicalPath + File.separator)) { "Telegram file path is outside the private cache" }
        val complete = local.isDownloadingCompleted
        val physicalSize = path.length()
        // expectedSize can be approximate; only size is authoritative for completed-file EOF.
        val declaredSize = file.size.takeIf { it > 0L }
        if (complete && declaredSize != null && offset >= declaredSize) return -1
        val start = if (complete) 0L else local.downloadOffset
        val end = if (complete) minOf(physicalSize, declaredSize ?: physicalSize) else start + local.downloadedPrefixSize
        if (complete && offset >= end) {
            // A completed flag with a short physical file is a missing range, not true EOF.
            if (declaredSize != null && physicalSize < declaredSize && offset < declaredSize) return null
            return -1
        }
        if (offset < start || offset >= end) return null
        val count = RandomAccessFile(path, "r").use { input ->
            input.seek(offset)
            input.read(buffer, 0, minOf(length.toLong(), end - offset).toInt())
        }
        if (count >= 0) return count
        if (!complete) return null
        return if (declaredSize != null && path.length() < declaredSize && offset < declaredSize) null else -1
    }
}
