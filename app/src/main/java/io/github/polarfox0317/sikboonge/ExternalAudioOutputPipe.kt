package io.github.polarfox0317.sikboonge

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Receives FFmpeg's non-seekable audio container output and exposes a bounded growing spool. */
internal class ExternalAudioOutputPipe(
    cacheFile: File,
    private val onFinished: (Throwable?) -> Unit,
    private val onSizeChanged: (Long) -> Unit,
) : AutoCloseable {
    private val lock = Object()
    private val cacheFile = cacheFile.apply { parentFile?.mkdirs(); delete(); createNewFile() }
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private var socket: Socket? = null
    private var written = 0L
    private var wantedEnd = 0L
    private var producerResult: Boolean? = null
    private var producerFailure: Throwable? = null
    private var finished = false
    @Volatile private var initialMp4DurationSeconds: Double? = null
    val outputUrl: String = "tcp://127.0.0.1:${server.localPort}?tcp_nodelay=1"

    init {
        Thread({ receive() }, "external-audio-spool").apply { isDaemon = true; start() }
    }

    fun currentSize(): Long = synchronized(lock) { written }
    fun isFinished(): Boolean = synchronized(lock) { finished }

    /** Configures a bounded initial-header patch before FFmpeg opens the output socket. */
    fun setInitialMp4Duration(seconds: Double) {
        require(seconds.isFinite() && seconds > 0.0)
        synchronized(lock) {
            check(written == 0L && socket == null) { "MP4 duration must be set before output begins" }
            initialMp4DurationSeconds = seconds
        }
    }

    fun waitForInitialBytes(minimum: Long = INITIAL_HEADER_BYTES): Long = synchronized(lock) {
        while (written < minimum && !finished && !closed.get()) lock.wait()
        throwIfFailedLocked()
        if (closed.get()) throw IOException("External audio session was cancelled")
        written
    }

    /** Returns -1 only after a successful final EOF, never just because the producer is behind. */
    fun readAt(offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean = { false }): Int {
        require(offset >= 0L && length >= 0 && length <= buffer.size)
        if (length == 0) return 0
        val end = ExternalAudioPolicy.rangeEnd(offset, length)
        synchronized(lock) {
            wantedEnd = maxOf(wantedEnd, end)
            lock.notifyAll()
            while (written < end && !finished && !closed.get() && !cancelled()) lock.wait(250L)
            if (cancelled()) throw java.io.InterruptedIOException("External audio consumer closed")
            if (closed.get()) throw IOException("External audio session was cancelled")
            if (offset >= written) {
                throwIfFailedLocked()
                return -1
            }
            val available = (written - offset).coerceAtMost(length.toLong()).toInt()
            if (available < length && !finished) throw IOException("Audio producer ended before the requested range")
            if (available == 0) return -1
            RandomAccessFile(cacheFile, "r").use { input ->
                input.seek(offset)
                input.readFully(buffer, 0, available)
            }
            if (available == length) return available
            throwIfFailedLocked()
            return available
        }
    }

    fun markProducerResult(success: Boolean, failure: Throwable? = null) {
        synchronized(lock) {
            if (producerResult != null) return
            producerResult = success
            producerFailure = failure
            lock.notifyAll()
        }
        if (!success) {
            runCatching { socket?.close() }
            runCatching { server.close() }
        }
    }

    private fun receive() {
        var failure: Throwable? = null
        try {
            val accepted = server.accept()
            socket = accepted
            accepted.soTimeout = 0
            val headerPatcher = initialMp4DurationSeconds?.let(::ExternalAudioMp4DurationPatcher)
            RandomAccessFile(cacheFile, "rw").use { output -> accepted.getInputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        headerPatcher?.finish()?.let { writeChunk(output, it, 0, it.size) }
                        break
                    }
                    if (count == 0) continue
                    val outputBytes = if (headerPatcher == null) buffer.copyOf(count) else headerPatcher.append(buffer, count)
                    outputBytes?.let { writeChunk(output, it, 0, it.size) }
                }
            } }
        } catch (error: Throwable) {
            if (!closed.get()) failure = error
        } finally {
            runCatching { socket?.close() }
            runCatching { server.close() }
            val resultFailure: Throwable?
            synchronized(lock) {
                while (producerResult == null && !closed.get()) lock.wait()
                finished = true
                resultFailure = failure ?: producerFailure.takeUnless { producerResult == true }
                lock.notifyAll()
            }
            onFinished(resultFailure)
        }
    }

    private fun writeChunk(output: RandomAccessFile, bytes: ByteArray, offset: Int, length: Int) {
        var copied = 0
        while (copied < length && !closed.get()) {
            val allowed = awaitWritable()
            if (allowed <= 0) break
            val part = minOf(length - copied, allowed)
            output.seek(written)
            output.write(bytes, offset + copied, part)
            synchronized(lock) {
                written = Math.addExact(written, part.toLong())
                lock.notifyAll()
            }
            copied += part
            onSizeChanged(currentSize())
        }
    }

    /** Applies output backpressure once the spool is eight MiB ahead of the last requested range. */
    private fun awaitWritable(): Int = synchronized(lock) {
        while (!closed.get() && producerResult != false) {
            val leadLimit = if (Long.MAX_VALUE - wantedEnd < ExternalAudioPolicy.MAX_OUTPUT_LEAD_BYTES) Long.MAX_VALUE
            else wantedEnd + ExternalAudioPolicy.MAX_OUTPUT_LEAD_BYTES
            val spaceLimit = minOf(ExternalAudioPolicy.MAX_SESSION_OUTPUT_BYTES, written + cacheFile.usableSpace - ExternalAudioPolicy.CACHE_RESERVE_BYTES)
            val writableEnd = minOf(leadLimit, spaceLimit)
            val remaining = writableEnd - written
            if (remaining > 0L) return@synchronized minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            if (spaceLimit <= written) throw IOException("기기 저장 공간이 부족해 외부 오디오 생성을 중단했어요.")
            lock.wait()
        }
        0
    }

    private fun throwIfFailedLocked() {
        if (producerResult == false || (finished && producerFailure != null)) {
            throw IOException("외부 오디오를 생성하지 못했어요.", producerFailure)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { lock.notifyAll() }
        runCatching { socket?.close() }
        runCatching { server.close() }
        runCatching { cacheFile.delete() }
    }

    companion object {
        private const val INITIAL_HEADER_BYTES = 64L * 1024L
    }
}
