package io.github.polarfox0317.sikboonge

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Random-access Media3 bridge for tdlib://file/<id>. It asks TDLib for bounded one-MiB ranges and
 * exposes only bytes in localFile.downloadOffset..downloadedPrefixSize. It never waits for full media.
 */
@UnstableApi
class TdlibMediaDataSource internal constructor(private val access: TdlibFileAccess = BotRuntime) : BaseDataSource(true) {
    private var currentUri: Uri? = null
    private var fileId: Int = 0
    private var position: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(true)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        if (uri.scheme != "tdlib" || uri.host != "file") throw IOException("Unsupported Telegram media URI")
        fileId = uri.lastPathSegment?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Invalid Telegram media file ID")
        val file = access.acquireFile(fileId)
        currentUri = uri
        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            file.size > 0L -> (file.size - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        closed.set(false)
        access.beginPlayback(fileId)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed.get()) throw IOException("Telegram media source is closed")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val maximum = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val target = if (offset == 0 && maximum == buffer.size) buffer else ByteArray(maximum)
        val count = try {
            access.readAt(fileId, position, target, maximum, closed::get)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Telegram media read interrupted", interrupted)
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (target !== buffer) System.arraycopy(target, 0, buffer, offset, count)
        position += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (!closed.getAndSet(true)) {
            access.endPlayback(fileId)
            currentUri = null
            transferEnded()
        }
    }

    @UnstableApi
    class Factory internal constructor(private val access: TdlibFileAccess = BotRuntime) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdlibMediaDataSource(access)
    }

}
