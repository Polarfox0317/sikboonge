package io.github.polarfox0317.sikboonge

import org.drinkless.tdlib.TdApi
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serves seekable ranges to FFmpeg over loopback without staging the source video. */
internal class ExternalAudioInputBridge private constructor(
    private val source: Source,
    private val onBytesTransferred: ((Long, Long, Long?) -> Unit)? = null,
) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val route = "/${newOpaqueToken()}"
    val inputUrl: String = "http://127.0.0.1:${server.localPort}$route"
    private val closed = AtomicBoolean(false)
    private val firstFailure = java.util.concurrent.atomic.AtomicReference<IncompleteMediaInputException?>(null)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)
    private val workerLock = Object()
    private val workers = mutableSetOf<Thread>()
    private var responseRequestsSealed = false
    private val closeFinished = java.util.concurrent.CountDownLatch(1)

    val isClosed: Boolean get() = closed.get()
    val failureContext: UserFacingErrors.Context get() = source.context
    fun failure(): IncompleteMediaInputException? = firstFailure.get()

    /** Confirms a known-length bot input reached its advertised final byte after extraction. */
    fun verifyComplete(cancelled: () -> Boolean = { false }) {
        awaitResponsesFinished(cancelled)
        failure()?.let { throw it }
        try {
            val verifiedBytes = source.verifyComplete(cancelled)
            if (verifiedBytes > 0L) runCatching {
                val totalBytes = source.size()
                val startByte = totalBytes?.minus(verifiedBytes)?.coerceAtLeast(0L) ?: 0L
                onBytesTransferred?.invoke(startByte, verifiedBytes, totalBytes)
            }
        } catch (error: IncompleteMediaInputException) {
            firstFailure.compareAndSet(null, error)
            throw firstFailure.get() ?: error
        }
        failure()?.let { throw it }
    }

    /** FFmpeg may finish before a loopback worker reports that its HTTP body ended short. */
    private fun awaitResponsesFinished(cancelled: () -> Boolean) {
        synchronized(workerLock) {
            responseRequestsSealed = true
            runCatching { server.close() }
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESPONSE_DRAIN_TIMEOUT_SECONDS)
            while ((workers.isNotEmpty() || sockets.isNotEmpty()) && !closed.get() && !cancelled()) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                val waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
                try {
                    workerLock.wait(minOf(waitMillis, RESPONSE_DRAIN_WAIT_SLICE_MS))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Telegram audio input verification was interrupted").apply {
                        initCause(interrupted)
                    }
                }
            }
            if (cancelled() || closed.get()) {
                throw java.io.InterruptedIOException("Telegram audio input was cancelled")
            }
            if (workers.isNotEmpty() || sockets.isNotEmpty()) {
                val incomplete = IncompleteMediaInputException(source.context)
                firstFailure.compareAndSet(null, incomplete)
                sockets.forEach { runCatching { it.close() } }
                throw firstFailure.get() ?: incomplete
            }
        }
    }

    init {
        Thread({
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { runCatching { socket.close() }; continue }
                sockets.add(socket)
                synchronized(workerLock) {
                    if (closed.get() || responseRequestsSealed) {
                        sockets.remove(socket)
                        socket.close()
                        slots.release()
                        workerLock.notifyAll()
                    }
                    else {
                        val worker = Thread({
                            try { serve(socket) } finally {
                                sockets.remove(socket)
                                slots.release()
                                synchronized(workerLock) {
                                    workers.remove(Thread.currentThread())
                                    workerLock.notifyAll()
                                }
                            }
                        }, "external-audio-input").apply { isDaemon = true }
                        workers.add(worker)
                        worker.start()
                    }
                }
            }
        }, "external-audio-loopback").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = SOURCE_READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val request = readLine(input).split(' ')
            if (request.size < 2 || request[0] !in setOf("GET", "HEAD") || request[1] != route) {
                respond(socket, 404, "Not Found", emptyMap())
                return
            }
            var rangeHeader: String? = null
            var headers = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                require(++headers <= 50) { "Too many input headers" }
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
            }
            if (closed.get()) throw IOException("External audio input was cancelled")
            val range = try { parseRange(rangeHeader, source.size()) } catch (_: UnsatisfiableRangeException) {
                respond(socket, 416, "Range Not Satisfiable", mapOf("Content-Length" to "0"))
                return
            }
            val response = source.open(request[0], range)
            val rangeStart = response.headers["Content-Range"]
                ?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() ?: 0L
            val out = socket.getOutputStream()
            val headersText = StringBuilder("HTTP/1.1 ${response.status} ${response.reason}\r\nConnection: close\r\nAccept-Ranges: bytes\r\n")
            response.headers.forEach { (name, value) -> headersText.append(name).append(": ").append(value).append("\r\n") }
            headersText.append("\r\n")
            out.write(headersText.toString().toByteArray(Charsets.US_ASCII))
            if (request[0] == "GET") response.body?.use { body ->
                val buffer = ByteArray(32 * 1024)
                val expectedBytes = response.headers["Content-Length"]?.toLongOrNull()
                val totalBytes = response.headers["Content-Range"]
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: expectedBytes?.takeIf { it > 0L }
                var receivedBytes = 0L
                while (!closed.get()) {
                    val count = try { body.read(buffer) } catch (error: Exception) {
                        if (!closed.get() && expectedBytes != null && receivedBytes < expectedBytes) {
                            firstFailure.compareAndSet(null, IncompleteMediaInputException(source.context))
                        }
                        throw error
                    }
                    if (count < 0) {
                        if (expectedBytes != null && receivedBytes != expectedBytes) {
                            val failure = IncompleteMediaInputException(source.context)
                            firstFailure.compareAndSet(null, failure)
                            throw failure
                        }
                        break
                    }
                    if (count == 0) continue
                    receivedBytes += count
                    out.write(buffer, 0, count)
                    runCatching { onBytesTransferred?.invoke(rangeStart + receivedBytes - count, count.toLong(), totalBytes) }
                }
            }
            out.flush()
        } catch (error: Exception) {
            if (!closed.get() && error is IncompleteMediaInputException) firstFailure.compareAndSet(null, error)
            // Close the input socket; FFmpeg reports the source read failure to the session.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseRange(value: String?, size: Long?): ByteRange? {
        if (value == null) return null
        val match = RANGE.matchEntire(value) ?: throw IOException("Invalid source range")
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull()?.takeIf { it > 0L } ?: throw IOException("Invalid source range")
            val known = size ?: throw IOException("Source size is unknown")
            return ByteRange((known - suffix).coerceAtLeast(0L), known - 1L)
        }
        val start = first.toLongOrNull() ?: throw IOException("Invalid source range")
        val end = last.toLongOrNull() ?: (size?.minus(1L) ?: Long.MAX_VALUE)
        if (start < 0L || end < start || (size != null && start >= size)) throw UnsatisfiableRangeException()
        return ByteRange(start, minOf(end, size?.minus(1L) ?: end))
    }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HEADER_LINE_BYTES) {
            val byte = input.read()
            if (byte < 0 || byte == 10) break
            if (byte != 13) bytes.write(byte)
        }
        if (bytes.size() >= MAX_HEADER_LINE_BYTES) throw IOException("Input header too large")
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private fun respond(socket: Socket, status: Int, reason: String, headers: Map<String, String>) {
        val out = socket.getOutputStream()
        val text = StringBuilder("HTTP/1.1 $status $reason\r\nConnection: close\r\n")
        headers.forEach { (name, value) -> text.append(name).append(": ").append(value).append("\r\n") }
        text.append("\r\n")
        out.write(text.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            awaitBotWorkerStopped(closeFinished)
            return
        }
        try {
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            runCatching { source.close() }
            val readers = synchronized(workerLock) { workers.toList() }
            var interrupted = false
            readers.filterNot { it === Thread.currentThread() }.forEach { reader ->
                while (reader.isAlive) {
                    try { reader.join() } catch (_: InterruptedException) { interrupted = true }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        } finally { closeFinished.countDown() }
    }

    internal data class ByteRange(val start: Long, val endInclusive: Long)
    internal data class SourceResponse(val status: Int, val reason: String, val headers: Map<String, String>, val body: java.io.InputStream?)

    private interface Source : AutoCloseable {
        val context: UserFacingErrors.Context
        fun size(): Long?
        fun open(method: String, range: ByteRange?): SourceResponse
        fun verifyComplete(cancelled: () -> Boolean): Long = 0L
    }

    private class BotSource(
        private val fileId: Int,
        private val listedSize: Long,
        private val access: TdlibFileAccess,
    ) : Source {
        override val context = UserFacingErrors.Context.TELEGRAM
        private val lock = Any()
        private var fileSize: Long? = null
        private var leaseStarted = false
        @Volatile private var sourceClosed = false

        private fun ensureLease(): Long = synchronized(lock) {
            if (sourceClosed) throw IOException("Telegram audio input was cancelled")
            fileSize?.let { return it }
            val file: TdApi.File = access.acquireFile(fileId)
            if (!leaseStarted) {
                access.beginPlayback(fileId)
                leaseStarted = true
            }
            val size = file.size.takeIf { it > 0L }
                ?: file.expectedSize.takeIf { it > 0L }
                ?: listedSize.takeIf { it > 0L }
                ?: throw IOException("Telegram video size is unavailable")
            fileSize = size
            size
        }

        override fun size(): Long = ensureLease()

        override fun verifyComplete(cancelled: () -> Boolean): Long {
            val size = ensureLease()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(FINAL_BYTE_CHECK_TIMEOUT_SECONDS)
            fun interrupted(): Boolean = synchronized(lock) { sourceClosed } || cancelled() || Thread.currentThread().isInterrupted
            if (interrupted()) throw java.io.InterruptedIOException("Telegram audio input was cancelled")
            val lastByte = ByteArray(1)
            val count = try {
                access.readAt(fileId, size - 1L, lastByte, 1) {
                    interrupted() || System.nanoTime() >= deadlineNanos
                }
            } catch (error: Exception) {
                if (System.nanoTime() >= deadlineNanos && !interrupted()) throw IncompleteMediaInputException(context)
                throw error
            }
            if (interrupted()) throw java.io.InterruptedIOException("Telegram audio input was cancelled")
            if (System.nanoTime() >= deadlineNanos) throw IncompleteMediaInputException(context)
            if (count < 0) throw IncompleteMediaInputException(context)
            if (count != 1) throw IncompleteMediaInputException(context)
            return 1L
        }

        override fun open(method: String, range: ByteRange?): SourceResponse {
            val size = ensureLease()
            val selected = range?.let { ByteRange(it.start, minOf(it.endInclusive, size - 1L)) }
            if (selected != null && (selected.start >= size || selected.endInclusive < selected.start)) {
                return SourceResponse(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"), null)
            }
            val start = selected?.start ?: 0L
            val end = selected?.endInclusive ?: size - 1L
            val headers = linkedMapOf("Content-Length" to (end - start + 1L).toString(), "Content-Type" to "application/octet-stream")
            if (selected != null) headers["Content-Range"] = "bytes $start-$end/$size"
            val body = if (method == "GET") object : java.io.InputStream() {
                private var cursor = start
                override fun read(): Int {
                    val one = ByteArray(1)
                    val count = read(one, 0, 1)
                    return if (count < 0) -1 else one[0].toInt() and 0xff
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (cursor > end) return -1
                    if (sourceClosed) throw java.io.InterruptedIOException("Telegram audio input cancelled")
                    val wanted = minOf(length.toLong(), end - cursor + 1L, ExternalAudioPolicy.MAX_READ_SEGMENT_BYTES.toLong()).toInt()
                    val scratch = ByteArray(wanted)
                    val count = access.readAt(fileId, cursor, scratch, wanted) { synchronized(lock) { sourceClosed } }
                    if (count < 0) throw IncompleteMediaInputException(UserFacingErrors.Context.TELEGRAM)
                    if (count == 0 || count > wanted) throw IOException("Telegram range read failed")
                    System.arraycopy(scratch, 0, buffer, offset, count)
                    cursor += count
                    return count
                }
            } else null
            return SourceResponse(if (selected == null) 200 else 206, if (selected == null) "OK" else "Partial Content", headers, body)
        }

        override fun close() {
            val shouldRelease = synchronized(lock) {
                if (sourceClosed) false else { sourceClosed = true; leaseStarted }
            }
            if (shouldRelease) runCatching { access.endPlayback(fileId) }
        }
    }

    companion object {
        private const val MAX_HEADER_LINE_BYTES = 8192
        private const val SOURCE_READ_TIMEOUT_MS = 20_000
        private const val RESPONSE_DRAIN_TIMEOUT_SECONDS = 25L
        private const val RESPONSE_DRAIN_WAIT_SLICE_MS = 250L
        private const val FINAL_BYTE_CHECK_TIMEOUT_SECONDS = 10L
        private val RANGE = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)

        private class UnsatisfiableRangeException : IOException("Unsatisfiable source range")

        fun forBot(
            fileId: Int,
            listedSize: Long,
            access: TdlibFileAccess,
            onBytesTransferred: ((Long, Long, Long?) -> Unit)? = null,
        ): ExternalAudioInputBridge {
            require(fileId > 0)
            return ExternalAudioInputBridge(BotSource(fileId, listedSize.coerceAtLeast(0L), access), onBytesTransferred)
        }

        private fun newOpaqueToken(): String = android.util.Base64.encodeToString(
            ByteArray(24).also(java.security.SecureRandom()::nextBytes),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }
}
