package io.github.polarfox0317.sikboonge

import java.util.concurrent.atomic.AtomicBoolean

/** Bounds for explicit bot-audio saves; the source itself is never staged as a complete video. */
internal object BotAudioSavePolicy {
    const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L * 1024L
    const val MIN_FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
    const val MAX_JOB_SECONDS = 60L * 60L

    fun outputLimit(usableBytes: Long): Long {
        val available = usableBytes - MIN_FREE_SPACE_RESERVE_BYTES
        require(available > 0L) { "저장 공간이 부족해 오디오를 저장할 수 없어요." }
        return minOf(MAX_OUTPUT_BYTES, available)
    }

    fun remainingSeconds(startedAtNanos: Long, nowNanos: Long = System.nanoTime()): Long {
        val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds((nowNanos - startedAtNanos).coerceAtLeast(0L))
        require(elapsed < MAX_JOB_SECONDS) { "오디오 저장 시간이 초과됐어요. 다시 시도해 주세요." }
        return MAX_JOB_SECONDS - elapsed
    }
}

internal data class TransferProgressSnapshot(
    val receivedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
)

/** Samples actual input bytes at a UI-friendly rate without estimating unreceived media. */
internal class TransferProgressSampler(
    private val sampleIntervalNanos: Long = 250_000_000L,
    private val nowNanos: () -> Long = { System.nanoTime() },
) {
    private var receivedBytes = 0L
    private var totalBytes: Long? = null
    private var sampledBytes = 0L
    private var sampledAtNanos = nowNanos()
    private var lastByteAtNanos = sampledAtNanos
    private var bytesPerSecond: Long? = null

    @Synchronized fun add(bytes: Long, total: Long?): TransferProgressSnapshot? {
        if (bytes <= 0L) return null
        receivedBytes = if (Long.MAX_VALUE - receivedBytes < bytes) Long.MAX_VALUE else receivedBytes + bytes
        if (total != null && total > 0L) totalBytes = total
        val now = nowNanos()
        lastByteAtNanos = now
        val elapsed = now - sampledAtNanos
        if (elapsed < sampleIntervalNanos) return null
        val sampledDelta = receivedBytes - sampledBytes
        bytesPerSecond = if (elapsed > 0L) {
            (sampledDelta.toDouble() * 1_000_000_000.0 / elapsed.toDouble())
                .coerceIn(0.0, Long.MAX_VALUE.toDouble())
                .toLong()
        } else null
        sampledBytes = receivedBytes
        sampledAtNanos = now
        return snapshot(now)
    }

    @Synchronized fun snapshot(now: Long = nowNanos()): TransferProgressSnapshot = TransferProgressSnapshot(
        receivedBytes = receivedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond?.takeIf { now - lastByteAtNanos <= 2_000_000_000L },
    )
}

/** Separate range-source injection point; the legacy completed-file seam remains available. */
internal object BotAudioSaveTestHooks {
    @Volatile var accessOverride: TdlibFileAccess? = null
    @Volatile var beforeConvert: ((ExternalAudioInputBridge?) -> Unit)? = null
}

/** Keeps FFmpeg cancellation callbacks scoped to one bot save, including registration races. */
internal class BotAudioSaveCancellationScope {
    private val lock = Any()
    private val sessions = linkedMapOf<Long, (Long) -> Unit>()
    private val cancelledFlag = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelledFlag.get()

    fun registerSession(sessionId: Long, cancelSession: (Long) -> Unit) {
        if (sessionId <= 0L) return
        val cancelNow = synchronized(lock) {
            if (cancelledFlag.get()) true else {
                sessions[sessionId] = cancelSession
                false
            }
        }
        if (cancelNow) cancelSession(sessionId)
    }

    fun finishSession(sessionId: Long) {
        synchronized(lock) { sessions.remove(sessionId) }
    }

    fun cancelSession(sessionId: Long) {
        val cancel = synchronized(lock) { sessions.remove(sessionId) } ?: return
        cancel(sessionId)
    }

    fun cancel(): Boolean {
        val callbacks = synchronized(lock) {
            if (!cancelledFlag.compareAndSet(false, true)) return false
            sessions.toMap().also { sessions.clear() }
        }
        callbacks.forEach { (sessionId, cancelSession) -> runCatching { cancelSession(sessionId) } }
        return true
    }
}
