package io.github.polarfox0317.sikboonge.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.PlaybackSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

// app choice: retain the old distance before horizontal dismissal can stop playback.
private val PlayerDismissDistance = 80.dp
// app choice: retain the old horizontal-dominance rule to avoid accidental dismissal.
private const val PlayerDismissAxisRatio = 1.2f
// app choice: retain the old finger-follow opacity response.
private const val PlayerDragFadeRatio = 0.55f
// app choice: retain enough surface visibility while the finger is on it.
private const val PlayerDragMinimumAlpha = 0.35f
// app choice: the new downward collapse gesture uses the existing dismiss distance.
private val PlayerCollapseDistance = PlayerDismissDistance

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberPlayerSwipeModifier(
    stream: StreamingState,
    expanded: Boolean,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
    timelineBounds: MutableState<Rect?>,
    collapseBounds: MutableState<Rect?>,
    collapseScrollBounds: MutableState<Rect?>,
    canCollapseScroll: () -> Boolean,
    modifier: Modifier = Modifier,
): Modifier {
    val source = remember(stream.source, stream.localAudioPath, stream.botFileId) {
        when (stream.source) {
            PlaybackSource.LOCAL_AUDIO -> stream.localAudioPath?.let { "local:$it" } ?: "local:invalid"
            PlaybackSource.BOT -> stream.botFileId?.let { "bot:$it" } ?: "bot:invalid"
            PlaybackSource.NONE -> ""
        }
    }
    val latestSource by rememberUpdatedState(source)
    val latestStop by rememberUpdatedState(onStop)
    val latestCollapse by rememberUpdatedState(onCollapse)
    val latestCanCollapseScroll by rememberUpdatedState(canCollapseScroll)
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragAlpha by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var width by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val density = LocalDensity.current
    val dismissDistance = with(density) { PlayerDismissDistance.toPx() }
    val collapseDistance = with(density) { PlayerCollapseDistance.toPx() }
    DisposableEffect(source) { onDispose { animationJob?.cancel() } }
    LaunchedEffect(source) {
        dragging = false
        dragX = 0f; dragY = 0f; dragAlpha = 1f
        x.snapTo(0f); y.snapTo(0f); alpha.snapTo(1f)
    }
    fun settle(dismiss: Boolean, collapse: Boolean, gestureSource: String) {
        animationJob?.cancel()
        val startX = dragX
        val startY = dragY
        val startAlpha = dragAlpha
        animationJob = scope.launch {
            x.snapTo(startX); y.snapTo(startY); alpha.snapTo(startAlpha)
            dragging = false
            if (collapse && latestSource == gestureSource) latestCollapse()
            val targetX = if (dismiss) {
                if (startX < 0f) -width.coerceAtLeast(dismissDistance) else width.coerceAtLeast(dismissDistance)
            } else 0f
            coroutineScope {
                launch { x.animateTo(targetX, spatial) }
                launch { y.animateTo(0f, spatial) }
                launch { alpha.animateTo(if (dismiss) 0f else 1f, effects) }
            }
            if (dismiss && latestSource == gestureSource) latestStop()
        }
    }
    // Measure and receive pointers outside the translated layer to avoid feedback during a drag.
    return modifier.onGloballyPositioned {
        bounds = it.boundsInRoot()
        width = it.size.width.toFloat()
    }.pointerInput(source, expanded) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val downInRoot = bounds?.topLeft?.plus(down.position)
            // Neither horizontal dismissal nor downward collapse can consume the seek/time region.
            if (downInRoot != null && timelineBounds.value?.contains(downInRoot) == true) {
                return@awaitEachGesture
            }
            val collapseAllowed = expanded && collapseBounds.value?.contains(down.position) == true &&
                (collapseScrollBounds.value?.contains(down.position) != true || latestCanCollapseScroll())
            animationJob?.cancel()
            animationJob = null
            val startX = if (dragging) dragX else x.value
            val startY = if (dragging) dragY else y.value
            val startAlpha = if (dragging) dragAlpha else alpha.value
            var axis = 0
            var released = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (axis == 0) {
                    when {
                        abs(delta.x) >= slop && abs(delta.x) >= abs(delta.y) * PlayerDismissAxisRatio -> axis = 1
                        abs(delta.y) >= slop && abs(delta.y) >= abs(delta.x) * PlayerDismissAxisRatio -> {
                            if (collapseAllowed && delta.y > 0f) axis = 2
                            else break
                        }
                    }
                    if (axis != 0) dragging = true
                }
                if (axis != 0) {
                    change.consume()
                    dragX = if (axis == 1) startX + delta.x else 0f
                    dragY = if (axis == 2) (startY + delta.y).coerceAtLeast(0f) else 0f
                    dragAlpha = if (axis == 1) {
                        (1f - abs(dragX) / width.coerceAtLeast(dismissDistance) * PlayerDragFadeRatio)
                            .coerceIn(PlayerDragMinimumAlpha, 1f)
                    } else 1f
                }
                if (!change.pressed) { released = true; break }
            }
            if (axis != 0) {
                settle(
                    dismiss = released && axis == 1 && abs(dragX) >= dismissDistance,
                    collapse = released && axis == 2 && dragY >= collapseDistance,
                    gestureSource = source,
                )
            } else if (startX != 0f || startY != 0f || startAlpha != 1f) {
                dragX = startX; dragY = startY; dragAlpha = startAlpha
                settle(dismiss = false, collapse = false, gestureSource = source)
            }
        }
    }.graphicsLayer {
        translationX = if (dragging) dragX else x.value
        translationY = if (dragging) dragY else y.value
        this.alpha = if (dragging) dragAlpha else alpha.value
    }.semantics {
        customActions = listOf(CustomAccessibilityAction("재생 중지") { latestStop(); true })
    }
}
