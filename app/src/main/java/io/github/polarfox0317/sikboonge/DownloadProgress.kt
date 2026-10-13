package io.github.polarfox0317.sikboonge

import java.util.TreeMap
import kotlin.math.ceil

/** Progress values suitable for both the foreground notification and the pending library row. */
data class DownloadMetrics(
    val progress: Float? = null,
    val receivedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val remainingSeconds: Long? = null,
)

data class DownloadProgressText(
    val percent: String?,
    val details: String?,
    val notification: String,
)

/**
 * Tracks unique source ranges for received-byte coverage while measuring effective delivered
 * throughput separately. Conversion progress and ETA come only from monotonic FFmpeg progress.
 */
internal class DownloadProgressTracker(
    initialTotalBytes: Long? = null,
    initialDownloadedSize: Long? = null,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val ranges = TreeMap<Long, Long>() // half-open, disjoint intervals
    private var uniqueBytes = 0L
    private var speedBytesPerSecond: Long? = null
    private var speedMeasuredAt = nowNanos()
    private var totalBytes: Long? = initialTotalBytes?.takeIf { it > 0L }
    private var lastPlatformDownloadedSize = initialDownloadedSize?.takeIf { it >= 0L }
    private var platformSampleAt = nowNanos()
    private var conversionProgress: Float? = null
    private var progressSample: Float? = null
    private var progressSampleAt = nowNanos()
    private var progressPerSecond: Double? = null
    private var remainingSeconds: Long? = null

    @Synchronized
    fun addRange(offset: Long, length: Long, total: Long?): DownloadMetrics {
        if (total != null && total > 0L) totalBytes = total
        if (offset >= 0L && length > 0L) {
            val end = safeAdd(offset, length)
            val added = insertUniqueRange(offset, end)
            uniqueBytes = safeAdd(uniqueBytes, added)
        }
        return snapshot()
    }

    /** File-download fallback: the reported length is cumulative and monotonic. */
    @Synchronized
    fun observeFileDownload(received: Long, total: Long): DownloadMetrics {
        if (total > 0L) {
            totalBytes = total
            addRange(0L, received.coerceIn(0L, total), total)
            observePlatformDownloadedSize(received, total)
            observeProgress((received.toDouble() / total).toFloat().coerceIn(0f, 0.99f))
        }
        return snapshot()
    }

    /** Sample TDLib's local.downloadedSize; range reads alone may be cached or repeated. */
    @Synchronized
    fun observePlatformDownloadedSize(downloadedSize: Long, total: Long?): DownloadMetrics {
        if (total != null && total > 0L) totalBytes = total
        if (downloadedSize < 0L) return snapshot()
        val now = nowNanos()
        val previous = lastPlatformDownloadedSize
        if (previous == null) {
            lastPlatformDownloadedSize = downloadedSize
            platformSampleAt = now
            return snapshot(now)
        }
        if (downloadedSize < previous) {
            lastPlatformDownloadedSize = downloadedSize
            platformSampleAt = now
            speedBytesPerSecond = null
            speedMeasuredAt = now
            return snapshot(now)
        }
        val elapsed = now - platformSampleAt
        if (elapsed >= SAMPLE_INTERVAL_NANOS) {
            val delta = downloadedSize - previous
            if (delta > 0L && elapsed > 0L) {
                val measured = delta.toDouble() * 1_000_000_000.0 / elapsed
                val old = speedBytesPerSecond?.takeIf { now - speedMeasuredAt <= METRIC_STALE_NANOS }?.toDouble()
                speedBytesPerSecond = (if (old == null) measured else old * 0.65 + measured * 0.35)
                    .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
                speedMeasuredAt = now
            }
            lastPlatformDownloadedSize = downloadedSize
            platformSampleAt = now
        }
        return snapshot(now)
    }

    /** Progress is supplied by FFmpeg and clamped below 100% until the job commits to history. */
    @Synchronized
    fun observeProgress(value: Float?): DownloadMetrics {
        val reported = value?.takeIf { it.isFinite() }?.coerceIn(0f, 0.99f) ?: return snapshot()
        val progress = maxOf(reported, progressSample ?: 0f)
        val now = nowNanos()
        val previous = progressSample
        val elapsed = now - progressSampleAt
        if (previous != null && progress > previous && elapsed > 0L) {
            val rate = (progress - previous).toDouble() * 1_000_000_000.0 / elapsed.toDouble()
            if (rate.isFinite() && rate > 0.0) {
                val previousRate = progressPerSecond?.takeIf { now - progressSampleAt <= METRIC_STALE_NANOS }
                progressPerSecond = if (previousRate == null) rate else previousRate * 0.65 + rate * 0.35
                remainingSeconds = ceil((1.0 - progress) / requireNotNull(progressPerSecond)).takeIf { it.isFinite() && it >= 0.0 }
                    ?.coerceAtMost(Long.MAX_VALUE.toDouble())?.toLong()?.coerceAtLeast(1L)
            }
        }
        if (previous == null || progress > previous) {
            progressSample = progress
            progressSampleAt = now
        }
        conversionProgress = progress
        return snapshot(now)
    }

    @Synchronized
    fun snapshot(now: Long = nowNanos()): DownloadMetrics = DownloadMetrics(
        progress = conversionProgress,
        receivedBytes = uniqueBytes.takeIf { it > 0L },
        totalBytes = totalBytes,
        bytesPerSecond = speedBytesPerSecond?.takeIf { it > 0L && now - speedMeasuredAt <= METRIC_STALE_NANOS },
        remainingSeconds = remainingSeconds?.takeIf { now - progressSampleAt <= METRIC_STALE_NANOS },
    )

    /** Extraction restarts on resume; keep verified range coverage, reset processing/speed/ETA. */
    @Synchronized
    fun restartProcessing(platformDownloadedSize: Long? = null): DownloadMetrics {
        conversionProgress = null
        progressSample = null
        progressPerSecond = null
        remainingSeconds = null
        speedBytesPerSecond = null
        val now = nowNanos()
        progressSampleAt = now
        platformSampleAt = now
        speedMeasuredAt = now
        lastPlatformDownloadedSize = platformDownloadedSize?.takeIf { it >= 0L }
        return snapshot(now)
    }

    /** A fresh extraction attempt clears old range coverage and restarts progress below completion. */
    @Synchronized
    fun retryProcessing(platformDownloadedSize: Long? = null): DownloadMetrics {
        uniqueBytes = 0L
        ranges.clear()
        restartProcessing(platformDownloadedSize)
        return snapshot()
    }

    private fun insertUniqueRange(start: Long, endExclusive: Long): Long {
        var mergedStart = start
        var mergedEnd = endExclusive
        var overlap = 0L
        val entries = ranges.subMap(start, true, endExclusive, false).entries.toList() +
            ranges.floorEntry(start)?.takeIf { it.value > start }?.let(::listOf).orEmpty()
        entries.distinctBy { it.key }.forEach { (existingStart, existingEnd) ->
            overlap = safeAdd(overlap, (minOf(endExclusive, existingEnd) - maxOf(start, existingStart)).coerceAtLeast(0L))
            mergedStart = minOf(mergedStart, existingStart)
            mergedEnd = maxOf(mergedEnd, existingEnd)
            ranges.remove(existingStart)
        }
        // Adjacent ranges merge as well, preventing unbounded interval growth for sequential reads.
        val prior = ranges.floorEntry(mergedStart)
        if (prior != null && prior.value >= mergedStart) {
            mergedStart = prior.key
            mergedEnd = maxOf(mergedEnd, prior.value)
            ranges.remove(prior.key)
        }
        val next = ranges.ceilingEntry(mergedStart)
        if (next != null && next.key <= mergedEnd) {
            mergedEnd = maxOf(mergedEnd, next.value)
            ranges.remove(next.key)
        }
        ranges[mergedStart] = mergedEnd
        return (endExclusive - start - overlap).coerceAtLeast(0L)
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    private companion object {
        const val SAMPLE_INTERVAL_NANOS = 250_000_000L
        const val METRIC_STALE_NANOS = 2_000_000_000L
    }
}

/** Shared compact metric labels for notifications and pending-download UI. */
object DownloadProgress {
    @JvmStatic
    fun format(metrics: DownloadMetrics): DownloadProgressText {
        val percent = metrics.progress?.takeIf { it.isFinite() }
            ?.let { "${(it.coerceIn(0f, 1f) * 100f).toInt()}%" }
        val details = buildList {
            metrics.bytesPerSecond?.takeIf { it > 0L }?.let { add(formatSpeed(it)) }
            metrics.remainingSeconds?.takeIf { it >= 0L }?.let { add("약 ${formatDuration(it)} 남음") }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        val notification = listOfNotNull(percent, details).joinToString(" · ").ifBlank { "준비 중" }
        return DownloadProgressText(percent = percent, details = details, notification = notification)
    }

    @JvmStatic
    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "속도 확인 중"
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        val decimals = if (value >= 100.0 || unit == 0) 0 else 1
        return "${"%.${decimals}f".format(java.util.Locale.ROOT, value)} ${units[unit]}"
    }

    @JvmStatic
    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val remainder = safe % 60L
        return if (hours > 0L) "${hours}시간 ${minutes}분"
        else if (minutes > 0L) "${minutes}분 ${remainder}초"
        else "${remainder}초"
    }
}
