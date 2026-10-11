package io.github.polarfox0317.sikboonge

import java.util.LinkedHashMap

/** Owns queued and active bot-save identities so scheduling and cancellation stay per download. */
internal class DownloadExecutionScheduler<T>(
    private val maxParallelDownloads: Int = DEFAULT_MAX_PARALLEL_DOWNLOADS,
) {
    internal enum class EnqueueResult { Added, DuplicateId, DuplicateSource }

    internal data class Entry<T>(
        val downloadId: Long,
        val sourceKey: String,
        val value: T,
        val enqueueRank: Long = 0L,
    )

    private val lock = Any()
    private val queued = mutableListOf<Entry<T>>()
    private val active = LinkedHashMap<Long, Entry<T>>()
    private val paused = mutableSetOf<Long>()
    private var enqueueRank = 0L

    init {
        require(maxParallelDownloads > 0)
    }

    fun enqueue(downloadId: Long, sourceKey: String, value: T): EnqueueResult = synchronized(lock) {
        require(downloadId > 0L)
        require(sourceKey.isNotBlank())
        if (active.containsKey(downloadId) || queued.any { it.downloadId == downloadId }) {
            return@synchronized EnqueueResult.DuplicateId
        }
        if (active.values.any { it.sourceKey == sourceKey } || queued.any { it.sourceKey == sourceKey }) {
            return@synchronized EnqueueResult.DuplicateSource
        }
        queued.add(Entry(downloadId, sourceKey, value, ++enqueueRank))
        EnqueueResult.Added
    }

    /** Parallel starts up to the configured cap; Sequential waits for every current job to drain. */
    fun startAvailable(mode: DownloadExecutionMode): List<Entry<T>> = synchronized(lock) {
        val capacity = when (mode) {
            DownloadExecutionMode.Sequential -> if (active.isEmpty()) 1 else 0
            DownloadExecutionMode.Parallel -> (maxParallelDownloads - active.size).coerceAtLeast(0)
        }
        buildList {
            queued.filterNot { it.downloadId in paused }.take(capacity).forEach { entry ->
                queued.remove(entry)
                active[entry.downloadId] = entry
                add(entry)
            }
        }
    }

    fun cancelQueued(downloadId: Long): Entry<T>? = synchronized(lock) {
        val entry = queued.firstOrNull { it.downloadId == downloadId } ?: return@synchronized null
        queued.remove(entry)
        paused.remove(downloadId)
        entry
    }

    fun removeQueuedWhere(predicate: (T) -> Boolean): List<Entry<T>> = synchronized(lock) {
        val removed = queued.filter { predicate(it.value) }
        queued.removeAll(removed.toSet())
        paused.removeAll(removed.map { it.downloadId }.toSet())
        removed
    }

    fun active(downloadId: Long): Entry<T>? = synchronized(lock) { active[downloadId] }

    fun activeEntries(): List<Entry<T>> = synchronized(lock) { active.values.toList() }

    fun queuedEntries(): List<Entry<T>> = synchronized(lock) { queued.toList() }

    /** Mark intent only. An active entry retains its slot until its worker acknowledges stop. */
    fun pause(downloadId: Long): Boolean = synchronized(lock) {
        if (downloadId !in active && queued.none { it.downloadId == downloadId }) false
        else paused.add(downloadId)
    }

    /** Restores eligibility at the original rank, without preempting active work. */
    fun resume(downloadId: Long): Boolean = synchronized(lock) { paused.remove(downloadId) }

    fun isPaused(downloadId: Long): Boolean = synchronized(lock) { downloadId in paused }

    /** Call ONLY after the previous worker and its network/native work have stopped. */
    fun requeueAfterStop(downloadId: Long): Entry<T>? = synchronized(lock) {
        val entry = active.remove(downloadId) ?: return@synchronized null
        queued.add(entry)
        queued.sortBy { it.enqueueRank }
        entry
    }

    fun finish(downloadId: Long): Entry<T>? = synchronized(lock) {
        paused.remove(downloadId)
        active.remove(downloadId)
    }

    fun clearQueued(): List<Entry<T>> = synchronized(lock) {
        queued.toList().also { entries ->
            queued.clear()
            paused.removeAll(entries.map { it.downloadId }.toSet())
        }
    }

    val hasWork: Boolean get() = synchronized(lock) { queued.isNotEmpty() || active.isNotEmpty() }

    companion object {
        const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 3
    }
}
