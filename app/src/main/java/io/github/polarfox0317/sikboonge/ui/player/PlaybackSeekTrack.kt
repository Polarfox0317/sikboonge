package io.github.polarfox0317.sikboonge.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The stock Slider's own geometry, measured from the installed Material 1.5.0-beta01
 * (`SliderKt.thumbTrackGapSize = 6.dp`, `thumbSize = 4.dp x 44.dp`). Both are private there, so they
 * are restated as an app choice to reproduce the stock gap instead of approximating it.
 */
private val SeekThumbTrackGap = 6.dp
private val SeekThumbWidth = 4.dp

/**
 * **App design choice (user-selected):** the seek bar's played segment waves while playback runs and
 * flattens when it pauses, buffers or ends. Motion comes from the shared [PlayerWaveMotion], so the
 * same wave continues in the mini player instead of restarting on switch.
 *
 * This is a custom *track*: the `Slider` still owns the gesture, keyboard, thumb, hit area and
 * accessibility semantics, and this draws underneath it carrying no progress semantics of its own.
 *
 * The stock Slider insets its line by the thumb plus [SeekThumbTrackGap] on both sides. This draws
 * full width and clips that same band out, so the wave and the flat track share one gap. Clipping
 * (not painting) keeps the surface behind the thumb untouched.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WavySeekTrack(
    state: SliderState,
    motion: PlayerWaveMotion,
    localScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = SliderDefaults.colors()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val geometry = rememberPlayerWaveGeometry()
    val halfGapAroundThumb = with(LocalDensity.current) { (SeekThumbWidth / 2 + SeekThumbTrackGap).toPx() }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            // The official wave envelope height, used in both the wavy and the flat state so the
            // row never changes size when playback pauses.
            .height(geometry.height)
            .clearAndSetSemantics { },
    ) {
        // Read inside the draw lambda: animation frames then only dirty drawing, not composition.
        val fraction = state.coercedValueAsFraction
        val amplitude = motion.amplitude.value
        val phase = motion.phase
        val thumbCenter = size.width * (if (rtl) 1f - fraction else fraction)
        val cutout = Path().apply {
            addRect(
                Rect(
                    left = thumbCenter - halfGapAroundThumb,
                    top = 0f,
                    right = thumbCenter + halfGapAroundThumb,
                    bottom = size.height,
                ),
            )
        }
        // Everything but the thumb's own band, so the gap matches in both states.
        clipPath(cutout, clipOp = ClipOp.Difference) {
            drawPlaybackWave(
                progressFraction = fraction,
                phase = phase,
                amplitude = amplitude,
                localScale = localScale,
                geometry = geometry,
                color = colors.activeTrackColor,
                trackColor = colors.inactiveTrackColor,
                rtl = rtl,
            )
        }
    }
}
