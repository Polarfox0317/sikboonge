package io.github.polarfox0317.sikboonge.ui.components

/** Pixel coordinates are physical left/right; ScrollState is logical start/end. */
internal object CursorEdgeScrollPolicy {
    fun velocity(
        pointerX: Float,
        viewportWidth: Float,
        edgeWidth: Float,
        maxSpeed: Float,
        rtl: Boolean,
    ): Float {
        if (viewportWidth <= 0f || edgeWidth <= 0f) return 0f
        val edge = edgeWidth.coerceAtMost(viewportWidth / 2f)
        val physicalVelocity = when {
            pointerX < edge -> -maxSpeed * ((edge - pointerX) / edge).coerceIn(0f, 1f)
            pointerX > viewportWidth - edge -> maxSpeed * ((pointerX - viewportWidth + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        return if (rtl) -physicalVelocity else physicalVelocity
    }

    fun contentLeft(scroll: Int, maxScroll: Int, rtl: Boolean): Float =
        (if (rtl) maxScroll - scroll else scroll).toFloat()
}
