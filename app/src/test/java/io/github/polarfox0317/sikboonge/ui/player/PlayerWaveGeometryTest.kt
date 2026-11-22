package io.github.polarfox0317.sikboonge.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visible-bounds regression for the shared progress wave: pausing must not change how long the
 * played run looks. These assert the user-visible contract (painted extent, endpoint height, cap
 * behaviour), not the sampling implementation.
 */
class PlayerWaveGeometryTest {
    private val stroke = 4f
    private val width = 200f
    private val centerY = 5f
    private val wavelength = 40f
    private val ramp = 10f

    private fun paintedExtent(activeWidth: Float): ClosedFloatingPointRange<Float> {
        val run = WaveRunGeometry.of(activeWidth, stroke)!!
        return run.paintedStart..run.paintedEnd
    }

    @Test
    fun playedRunLooksIdenticalAtEveryAmplitudeAndPhase() {
        val widths = listOf(1f, 7f, 37f, 120f, 200f)
        for (activeWidth in widths) {
            val expected = paintedExtent(activeWidth)
            assertEquals(0f, expected.start, 0.001f)
            assertEquals(activeWidth, expected.endInclusive, 0.001f)
            for (amplitude in listOf(0f, 0.35f, 1f, -0.11f, 1.1f)) {
                for (phase in listOf(0f, 0.37f, 0.99f)) {
                    val run = WaveRunGeometry.of(activeWidth, stroke)!!
                    val points = wavePoints(run, centerY, 3.6f, amplitude, phase, wavelength, ramp)
                    // Both endpoints stay on the centre line, so the caps reach the same extent.
                    assertEquals(centerY, points.first().second, 0.001f)
                    assertEquals(centerY, points.last().second, 0.001f)
                    assertEquals(expected.start, points.first().first - run.cap, 0.001f)
                    assertEquals(expected.endInclusive, points.last().first + run.cap, 0.001f)
                }
            }
        }
    }

    @Test
    fun passedProgressHasNoActiveRun() {
        assertNull(WaveRunGeometry.of(0f, stroke))
        assertNull(WaveRunGeometry.of(-5f, stroke))
        assertNull(WaveRunGeometry.of(Float.NaN, stroke))
    }

    @Test
    fun veryShortRunIsAStableDotAtTheSamePlace() {
        val activeWidth = 2f
        for (amplitude in listOf(0f, 1f, -0.11f)) {
            val run = WaveRunGeometry.of(activeWidth, stroke)!!
            // A dot: both endpoints coincide, and its round caps still span 0..activeWidth.
            assertEquals(run.start, run.end, 0.001f)
            assertEquals(activeWidth / 2f, run.start, 0.001f)
            assertEquals(0f, run.paintedStart, 0.001f)
            assertEquals(activeWidth, run.paintedEnd, 0.001f)
            val points = wavePoints(run, centerY, 3.6f, amplitude, 0.4f, wavelength, ramp)
            assertTrue(points.isNotEmpty())
        }
    }

    @Test
    fun waveHeightTracksSignedAmplitudeWithoutClipping() {
        val activeWidth = 120f
        val maxCrest = 3.6f
        fun extremesAt(amplitude: Float): Pair<Float, Float> {
            val run = WaveRunGeometry.of(activeWidth, stroke)!!
            val ys = wavePoints(run, centerY, maxCrest, amplitude, 0f, wavelength, ramp).map { it.second }
            return ys.min() to ys.max()
        }
        val flat = extremesAt(0f)
        assertEquals(centerY, flat.first, 0.001f)
        assertEquals(centerY, flat.second, 0.001f)

        val (low, high) = extremesAt(1f)
        assertTrue("wave should rise above the centre line", high > centerY + 1f)
        assertTrue("wave should dip below the centre line", low < centerY - 1f)
        assertTrue("crest must stay inside the reserved envelope", high - centerY <= maxCrest + 0.001f)

        // A negative amplitude inverts the ripple instead of snapping flat.
        val (negativeLow, negativeHigh) = extremesAt(-0.5f)
        assertTrue(negativeHigh > centerY)
        assertTrue(negativeLow < centerY)
        val run = WaveRunGeometry.of(activeWidth, stroke)!!
        val positive = wavePoints(run, centerY, maxCrest, 0.5f, 0.37f, wavelength, ramp)
        val negative = wavePoints(run, centerY, maxCrest, -0.5f, 0.37f, wavelength, ramp)
        positive.zip(negative).forEach { (normal, inverted) ->
            assertEquals(normal.first, inverted.first, 0.001f)
            assertEquals(centerY * 2f - normal.second, inverted.second, 0.001f)
        }
    }

    @Test
    fun rtlMirrorsTheWaveWithoutChangingItsPaintedLength() {
        val run = WaveRunGeometry.of(137f, stroke)!!
        for (amplitude in listOf(0f, 1f, -0.1f)) {
            val ltr = wavePoints(run, centerY, 3f, amplitude, 0.37f, wavelength, ramp)
            val rtl = wavePoints(run, centerY, 3f, amplitude, 0.37f, wavelength, ramp, rtl = true)
            assertEquals(ltr.first().first, rtl.first().first, 0.001f)
            assertEquals(ltr.last().first, rtl.last().first, 0.001f)
            ltr.zip(rtl.reversed()).forEach { (normal, mirrored) ->
                assertEquals(normal.second, mirrored.second, 0.001f)
            }
        }
    }
}
