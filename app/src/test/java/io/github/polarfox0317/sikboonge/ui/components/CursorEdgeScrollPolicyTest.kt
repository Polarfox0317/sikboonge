package io.github.polarfox0317.sikboonge.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorEdgeScrollPolicyTest {
    @Test fun stationaryEdgeHoldAdvancesUntilEitherScrollBoundary() {
        var scroll = 0f
        // No new pointer position is supplied during these frames.
        repeat(240) {
            scroll = (scroll + velocity(300f) / 60f).coerceIn(0f, 1000f)
        }
        assertEquals(1000f, scroll, 0f)
        repeat(240) {
            scroll = (scroll + velocity(0f) / 60f).coerceIn(0f, 1000f)
        }
        assertEquals(0f, scroll, 0f)
    }

    @Test fun centerDoesNotScrollAndEdgeSpeedRisesSmoothlyThenSaturates() {
        assertEquals(0f, velocity(150f), 0f)
        assertEquals(0f, velocity(24f), 0f)
        assertEquals(-240f, velocity(12f), 0.001f)
        assertEquals(-480f, velocity(0f), 0f)
        assertEquals(-480f, velocity(-100f), 0f)
        assertEquals(240f, velocity(288f), 0.001f)
        assertEquals(480f, velocity(400f), 0f)
    }

    @Test fun rtlKeepsPhysicalDirectionAndCaretMappingConsistent() {
        assertEquals(-velocity(300f), velocity(300f, rtl = true), 0f)
        val before = CursorEdgeScrollPolicy.contentLeft(800, 1000, rtl = true)
        val after = CursorEdgeScrollPolicy.contentLeft(700, 1000, rtl = true)
        assertTrue(after > before) // Scrolling physically right reveals text farther right.
        assertEquals(0f, CursorEdgeScrollPolicy.contentLeft(1000, 1000, rtl = true), 0f)
        assertEquals(1000f, CursorEdgeScrollPolicy.contentLeft(0, 1000, rtl = true), 0f)
    }

    @Test fun narrowAndMissingViewportsDoNotCreateOverlappingOrInvalidEdges() {
        assertEquals(0f, CursorEdgeScrollPolicy.velocity(0f, 0f, 24f, 480f, false), 0f)
        assertEquals(0f, CursorEdgeScrollPolicy.velocity(0f, 300f, 0f, 480f, false), 0f)
        assertEquals(0f, CursorEdgeScrollPolicy.velocity(10f, 20f, 24f, 480f, false), 0f)
    }

    private fun velocity(x: Float, rtl: Boolean = false): Float =
        CursorEdgeScrollPolicy.velocity(x, 300f, 24f, 480f, rtl)
}
