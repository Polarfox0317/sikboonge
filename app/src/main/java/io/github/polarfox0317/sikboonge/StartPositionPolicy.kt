package io.github.polarfox0317.sikboonge

import androidx.media3.common.C

/**
 * Where one media request starts. A request that names a position owns it for that item only; a
 * fresh item must never inherit the previous item's position, and a live player needs no seek.
 */
internal object StartPositionPolicy {
    /** Sentinel the service uses for "this item has no pending start position". */
    fun armedPosition(requestedMs: Long?): Long = requestedMs?.coerceAtLeast(0L) ?: C.TIME_UNSET

    /** A request's own position, or null when it names none (or names an absent extra). */
    fun fromRequest(extraMs: Long): Long? = extraMs.takeIf { it >= 0L }?.coerceAtLeast(0L)

    /** The resume rule for a service that has to rebuild its last item. */
    fun fallbackResumeMs(
        requestedMs: Long?,
        lastPositionMs: Long,
        lastEnded: Boolean,
        sentinel: Long = C.TIME_UNSET,
    ): Long? {
        if (requestedMs != null && requestedMs != sentinel) return requestedMs.coerceAtLeast(0L)
        return lastPositionMs.takeIf { !lastEnded && it > 0L }
    }
}
