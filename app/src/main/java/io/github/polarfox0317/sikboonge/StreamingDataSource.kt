package io.github.polarfox0317.sikboonge

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@UnstableApi
internal class StreamingDataSource(
    private val tdlibFactory: DataSource.Factory,
    private val localFactory: DataSource.Factory,
    private val canOpenLocalUri: (Uri) -> Boolean
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; active?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val factory = when (dataSpec.uri.scheme) {
            "tdlib" -> tdlibFactory
            "content" -> if (canOpenLocalUri(dataSpec.uri)) localFactory else throw java.io.IOException("Unsupported audio source")
            else -> throw java.io.IOException("Unsupported audio source")
        }
        return factory.createDataSource().also { source -> active = source; listeners.forEach(source::addTransferListener) }.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = requireNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() { val source = active; active = null; source?.close() }
}
