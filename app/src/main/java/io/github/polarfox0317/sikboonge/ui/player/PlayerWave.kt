package io.github.polarfox0317.sikboonge.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * **App design choice (user request):** the played portion of the seek bar and the mini player's
 * progress line render one shared decorative wave, so switching between the expanded and mini
 * player never restarts the motion.
 *
 * The installed Material wavy indicator cannot serve this: its internal amplitude animation ignores
 * a new target while a previous tween runs, starts with a slow acceleration, and setting
 * `waveSpeed` to zero resets the wave phase mid-flatten. The app therefore owns the motion and draws
 * the same wave itself, reusing the official stroke, wavelength, height and colours.
 *
 * This holds only two animated values; it never reads or writes playback state, the queue or
 * persistence.
 */
internal class PlayerWaveMotion(
    /**
     * Signed amplitude: 1f is the full resting wave, 0f is flat. The Material expressive spatial
     * spring overshoots slightly, so this can go below zero, which reads as an inverted ripple that
     * settles. It is never clamped to zero, because clamping would snap to flat and bounce back.
     */
    val amplitude: State<Float>,
) {
    /** Wave phase in wavelengths; kept across the expanded/mini switch so motion never restarts. */
    var phase by mutableFloatStateOf(0f)
        internal set
}

/**
 * The official geometry, resolved once in a composable because the stroke getters are composable
 * and cannot be called from a `DrawScope`.
 */
internal class PlayerWaveGeometry(
    val wavelength: Dp,
    val height: Dp,
    val stroke: Stroke,
    val trackStroke: Stroke,
    /** Official separation between the active and inactive segments, used by the mini row. */
    val trackGap: Dp,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberPlayerWaveGeometry(): PlayerWaveGeometry {
    val stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke
    val trackStroke = WavyProgressIndicatorDefaults.linearTrackStroke
    val wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength
    val height = WavyProgressIndicatorDefaults.LinearContainerHeight
    val trackGap = WavyProgressIndicatorDefaults.LinearIndicatorTrackGapSize
    return remember(stroke.width, trackStroke.width, wavelength, height, trackGap) {
        PlayerWaveGeometry(wavelength, height, stroke, trackStroke, trackGap)
    }
}

private const val WAVE_PHASE_PERIOD_MS = 1_000
// app choice: a short fixed taper at each end so the crests start and end at the centre line,
// independent of progress, while both endpoints stay fixed for every amplitude and phase.
private val WaveEndRamp = 10.dp
private const val WAVE_SAMPLES_PER_WAVE = 32
// app choice, not a Material rule: headroom reserved inside the official height for the spatial
// spring's slight overshoot, so the ripple never clips and the signed amplitude is never clamped flat.
private const val WAVE_OVERSHOOT_HEADROOM = 1.1f

/**
 * Remembers one motion instance above the expanded/mini switch and animates the signed amplitude
 * whenever [playing] changes.
 *
 * **App design choice (not a Material requirement):** the amplitude uses the expressive
 * small-component spatial spring, which reads as a guide-based settle rather than an arbitrary
 * percentage of some other curve. [animateFloatAsState] keeps a single long-lived animation coroutine
 * and retargets it in place, so the current velocity is carried into a pause that interrupts a
 * growing wave. An explicit `LaunchedEffect` + `Animatable` would cancel and reset that
 * velocity to zero first, which is what made the transition look late.
 *
 * The spatial spring overshoots slightly below zero. That is rendered as a signed, inverted ripple
 * instead of being clamped, so the wave cannot snap flat and bounce back.
 *
 * The phase loop runs while a wave exists or is still settling. Compose animations honour the system
 * duration scale, so a zero scale leaves the wave still instead of driving frames.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberPlayerWaveMotion(playing: Boolean): PlayerWaveMotion {
    // Captured in the composable body: the motion scheme is a composable accessor and must not be
    // read inside the animation's remember calculation.
    val amplitude = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "playerWaveAmplitude",
    )
    val motion = remember { PlayerWaveMotion(amplitude) }
    // Phase keeps running while the spring is unsettled, including the negative overshoot and the
    // return through zero. Derived so the caller only recomposes at the settled/unsettled crossing.
    val animating by remember(motion, amplitude, playing) {
        derivedStateOf { playing || abs(amplitude.value) > WAVE_SETTLED_EPSILON }
    }
    LaunchedEffect(motion, animating) {
        if (!animating) return@LaunchedEffect
        var last = motion.phase
        animate(
            initialValue = last,
            targetValue = last + 1f,
            animationSpec = infiniteRepeatable(tween<Float>(WAVE_PHASE_PERIOD_MS, easing = LinearEasing)),
        ) { value, _ ->
            // Keep the phase bounded so it never drifts into precision loss.
            motion.phase = (value % 1f + 1f) % 1f
            last = value
        }
    }
    return motion
}

private const val WAVE_SETTLED_EPSILON = 0.001f

/**
 * Horizontal geometry for the played run, shared by every amplitude and by the expanded and mini
 * rows. Keeping this pure and free of Compose types lets the visible bounds be checked directly.
 *
 * Both endpoints stay fixed at `strokeWidth / 2` and `activeWidth - strokeWidth / 2`, so the round
 * caps always reach exactly `0..activeWidth` whether the wave is at rest, flat or overshooting.
 */
internal data class WaveRunGeometry(
    /** Centre of the start cap. */
    val start: Float,
    /** Centre of the end cap. */
    val end: Float,
    /** Radius of the round caps, so the painted extent is `start - cap`..`end + cap`. */
    val cap: Float,
) {
    val paintedStart: Float get() = start - cap
    val paintedEnd: Float get() = end + cap

    companion object {
        /**
         * A run shorter than one stroke has no room for two caps, so it becomes a dot of the same
         * horizontal extent at the run's centre, identical at every amplitude.
         */
        fun of(activeWidth: Float, strokeWidth: Float): WaveRunGeometry? {
            if (activeWidth <= 0f || !activeWidth.isFinite()) return null
            val cap = strokeWidth / 2f
            val start = cap.coerceAtMost(activeWidth / 2f)
            val end = (activeWidth - cap).coerceAtLeast(start)
            return WaveRunGeometry(start = start, end = end, cap = if (start == end) activeWidth / 2f else cap)
        }
    }
}

/**
 * Samples the wave between two fixed endpoints. The first and last points are exactly on the centre
 * line, so the path's round caps land on the same visible extent at every amplitude and phase.
 *
 * Positive x only; the caller mirrors for RTL. Returns a list of `(x, y)` pairs, which keeps this
 * testable without a `DrawScope`.
 */
internal fun wavePoints(
    run: WaveRunGeometry,
    centerY: Float,
    maxCrest: Float,
    amplitude: Float,
    phase: Float,
    wavelengthPx: Float,
    rampPx: Float,
    samplesPerWave: Int = WAVE_SAMPLES_PER_WAVE,
    rtl: Boolean = false,
): List<Pair<Float, Float>> {
    val a = run.start
    val b = run.end
    if (b <= a) return listOf(a to centerY, b to centerY)
    val wavelengths = if (wavelengthPx.isFinite() && wavelengthPx > 0f) wavelengthPx else 1f
    val ramp = if (rampPx.isFinite() && rampPx > 0f) rampPx else 1f
    val span = b - a
    // One interior sample per step, then the exact end point is appended below.
    val steps = (span / (wavelengths / samplesPerWave.coerceAtLeast(1))).toInt().coerceAtLeast(1)
    val points = ArrayList<Pair<Float, Float>>(steps + 2)
    for (index in 0..steps) {
        val x = a + span * index / steps
        // Taper keeps both ends exactly on the centre line.
        val edge = (minOf(x - a, b - x) / ramp).coerceIn(0f, 1f)
        val logicalX = if (rtl) a + b - x else x
        val y = centerY - sin(2.0 * Math.PI * (logicalX / wavelengths - phase)).toFloat() * maxCrest * amplitude * edge
        points += x to y
    }
    return points
}

/**
 * Draws the inactive remainder as a flat line, then the played portion as a travelling wave.
 *
 * `sin(2π * (distance / wavelength - phase))` is the real wave; the installed `indicatorAmplitude`
 * is a total-progress envelope rather than a per-wave phase, so the ends are tapered with a short
 * fixed ramp instead. That keeps the wave visible at low playback progress (an app choice: the
 * indicator's own curve would stay nearly flat until progress advanced further).
 *
 * One active path serves every amplitude, including flat, so pausing never changes the visible
 * length. The two segments never overlap. [localScale] lets a caller damp motion locally (such as
 * while the thumb is dragged) without retargeting the shared amplitude.
 */
internal fun DrawScope.drawPlaybackWave(
    progressFraction: Float,
    phase: Float,
    amplitude: Float,
    localScale: Float,
    geometry: PlayerWaveGeometry,
    color: Color,
    trackColor: Color,
    rtl: Boolean,
    trackGap: Float = 0f,
) {
    val stroke = geometry.stroke
    val trackStroke = geometry.trackStroke
    val fraction = if (progressFraction.isFinite()) progressFraction.coerceIn(0f, 1f) else 0f
    val centerY = size.height / 2f
    val fullWidth = size.width * fraction
    val activeStart = if (rtl) size.width - fullWidth else 0f
    // Keep the played endpoint at its true horizontal time position. Only the unplayed segment
    // starts beyond the gap; the gap must not change the apparent played length.
    val gap = trackGap.coerceAtLeast(0f)
    val activeWidth = fullWidth

    // Inactive remainder, inset by the round-cap radius so its caps fill 0..width without bleeding
    // into the active run or past the outer ends.
    if (activeWidth < size.width) {
        val cap = trackStroke.width / 2f
        val segmentStart = if (rtl) 0f else activeStart + activeWidth + gap
        val segmentEnd = if (rtl) activeStart - gap else size.width
        if (segmentEnd - segmentStart > trackStroke.width) {
            drawLine(
                color = trackColor,
                start = Offset(segmentStart + cap, centerY),
                end = Offset(segmentEnd - cap, centerY),
                strokeWidth = trackStroke.width,
                cap = trackStroke.cap,
            )
        }
    }
    val run = WaveRunGeometry.of(activeWidth, stroke.width) ?: return
    // RTL grows from the right edge; the shortened run keeps its start edge there.
    val runLeft = if (rtl) size.width - activeWidth else activeStart

    // App choice, not a Material rule: budget headroom inside the official height so the spatial
    // spring's overshoot does not clip.
    val maxCrest = ((size.height - stroke.width) / 2f).coerceAtLeast(0f) / WAVE_OVERSHOOT_HEADROOM
    // Signed: a negative amplitude inverts the ripple instead of being clamped flat.
    val crest = (amplitude * localScale).coerceIn(-WAVE_OVERSHOOT_HEADROOM, WAVE_OVERSHOOT_HEADROOM)
    translate(left = runLeft) {
        if (run.start == run.end) {
            drawCircle(color = color, radius = run.cap, center = Offset(run.start, centerY))
            return@translate
        }
        val points = wavePoints(
            run = run,
            centerY = centerY,
            maxCrest = maxCrest,
            amplitude = crest,
            phase = phase,
            wavelengthPx = geometry.wavelength.toPx(),
            rampPx = WaveEndRamp.toPx(),
            rtl = rtl,
        )
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = stroke)
    }
}
