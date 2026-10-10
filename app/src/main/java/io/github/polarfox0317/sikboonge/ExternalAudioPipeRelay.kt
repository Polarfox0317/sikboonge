package io.github.polarfox0317.sikboonge

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Pumps the growing spool into a non-seekable ContentResolver pipe with cancellable writes. */
internal class ExternalAudioPipeRelay(
    private val reader: BotStreamSessions.ReadLease,
    private val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    fun start() {
        Thread({ pump() }, "external-audio-content-pipe").apply { isDaemon = true; start() }
    }

    private fun pump() {
        var failure: String? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) configureNonBlockingWriter()
            val buffer = ByteArray(32 * 1024)
            var offset = 0L
            while (!stopped.get() && !reader.isCancelled()) {
                val count = reader.read(offset, buffer, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                var sent = 0
                while (sent < count && !stopped.get() && !reader.isCancelled()) {
                    try {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !awaitLegacyPipeWritable()) break
                        val chunk = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            minOf(count - sent, LEGACY_ATOMIC_PIPE_WRITE_BYTES)
                        } else count - sent
                        val written = Os.write(writeSide.fileDescriptor, buffer, sent, chunk)
                        if (written > 0) sent += written else Thread.sleep(25L)
                    } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN) Thread.sleep(25L)
                        else throw error
                    }
                }
                offset = ExternalAudioPolicy.rangeEnd(offset, count)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = "audio read cancelled"
        } catch (_: ErrnoException) {
            if (!stopped.get()) failure = "audio pipe write failed"
        } catch (_: IOException) {
            if (!stopped.get()) failure = "audio read failed"
        } catch (_: Exception) {
            if (!stopped.get()) failure = "audio pipe stopped"
        } finally {
            finished.set(true)
            if (failure == null && !stopped.get()) runCatching { writeSide.close() }
            else runCatching { writeSide.closeWithError(failure ?: "audio session cancelled") }
            reader.close()
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { writeSide.closeWithError("audio session cancelled") }
        runCatching { readSide.close() }
        reader.close()
    }

    fun isFinished(): Boolean = finished.get()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun configureNonBlockingWriter() {
        Os.fcntlInt(writeSide.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
    }

    /** API 26–29 lack public fcntlInt; readiness plus atomic pipe writes keeps writes cancellable. */
    private fun awaitLegacyPipeWritable(): Boolean {
        val descriptor = StructPollfd().apply {
            fd = writeSide.fileDescriptor
            events = OsConstants.POLLOUT.toShort()
        }
        val descriptors = arrayOf(descriptor)
        while (!stopped.get() && !reader.isCancelled()) {
            descriptor.revents = 0
            Os.poll(descriptors, LEGACY_POLL_TIMEOUT_MS)
            val events = descriptor.revents.toInt()
            if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                throw IOException("audio pipe reader closed")
            }
            if (events and OsConstants.POLLOUT != 0) return true
        }
        return false
    }

    companion object {
        private const val LEGACY_ATOMIC_PIPE_WRITE_BYTES = 512
        private const val LEGACY_POLL_TIMEOUT_MS = 100
    }
}
