package io.github.polarfox0317.sikboonge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Linearizes pause/cancel against persistence. Only a successful persistence action commits. */
internal class BotSaveCommitGate {
    val cancelled = AtomicBoolean(false)
    val stopRequested = AtomicBoolean(false)
    @Volatile var committed = false
        private set

    @Synchronized fun requestPause(): Boolean {
        if (committed || cancelled.get()) return false
        return stopRequested.compareAndSet(false, true)
    }
    @Synchronized fun requestCancel(): Boolean {
        if (committed) return false
        return cancelled.compareAndSet(false, true)
    }
    @Synchronized fun beginRun() {
        check(!committed && !cancelled.get())
        stopRequested.set(false)
    }
    @Synchronized fun commit(persist: () -> Unit) {
        if (committed || cancelled.get() || stopRequested.get()) throw InterruptedException()
        persist()
        committed = true
    }
}

/** Only running time counts; queued and paused time never consumes the bot-save budget. */
internal class BotSaveActiveClock(private val nowNanos: () -> Long = System::nanoTime) {
    private var spentNanos = 0L
    private var runningSince: Long? = null

    @Synchronized fun start() { check(runningSince == null); runningSince = nowNanos() }
    @Synchronized fun stop() {
        runningSince?.let { spentNanos += (nowNanos() - it).coerceAtLeast(0L) }
        runningSince = null
    }
    @Synchronized fun remainingSeconds(): Long {
        val spent = spentNanos + (runningSince?.let { (nowNanos() - it).coerceAtLeast(0L) } ?: 0L)
        return BotAudioSavePolicy.remainingSeconds(0L, spent)
    }
}

/** Never release a slot on a cancellation grace timeout while native callbacks are still live. */
internal fun awaitBotWorkerStopped(latch: CountDownLatch) {
    var interrupted = false
    while (true) {
        try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

/** Persistent monotonically allocated notification IDs; no Long.hashCode aliasing or wrap. */
internal object BotSaveNotificationIds {
    const val FIRST = 0x60000000
    const val LAST = 0x6FFFFFFF
    fun next(previous: Int): Int {
        require(previous in (FIRST - 1) until LAST) { "Audio notification identity space exhausted" }
        return previous + 1
    }
}
