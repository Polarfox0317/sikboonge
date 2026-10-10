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
        val start = if (local.isDownloadingCompleted) 0L else local.downloadOffset
        val end = if (local.isDownloadingCompleted) path.length() else start + local.downloadedPrefixSize
        if (local.isDownloadingCompleted && offset >= end) return -1
        if (offset < start || offset >= end) return null
        return RandomAccessFile(path, "r").use { input ->
            input.seek(offset)
            input.read(buffer, 0, minOf(length.toLong(), end - offset).toInt())
        }.takeIf { it >= 0 }
    }
}
