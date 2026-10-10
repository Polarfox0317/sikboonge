package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScrollJumpBubbles(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onJump: (toTop: Boolean) -> Unit = {},
) {
    var awake by remember(state) { mutableStateOf(false) }
    var jumpInProgress by remember(state) { mutableStateOf(false) }
    var direction by remember(state) { mutableStateOf(ScrollJumpDirection.Down) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state, state.isScrollInProgress, jumpInProgress) {
        if (state.isScrollInProgress || jumpInProgress) {
            awake = true
        } else {
            delay(1_500)
            awake = false
        }
    }

    LaunchedEffect(state) {
        var previousIndex = state.firstVisibleItemIndex
        var previousOffset = state.firstVisibleItemScrollOffset
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val nextDirection = when {
                    index > previousIndex -> ScrollJumpDirection.Down
                    index < previousIndex -> ScrollJumpDirection.Up
                    offset > previousOffset -> ScrollJumpDirection.Down
                    offset < previousOffset -> ScrollJumpDirection.Up
                    else -> null
                }
                if (nextDirection != null && !jumpInProgress) {
                    direction = nextDirection
                }
                previousIndex = index
                previousOffset = offset
            }
    }

    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val canJump = when (direction) {
        ScrollJumpDirection.Down -> state.canScrollForward
        ScrollJumpDirection.Up -> state.canScrollBackward
    }
    AnimatedVisibility(
        visible = enabled && awake && canJump,
        modifier = modifier,
        enter = fadeIn(effectsSpec) + scaleIn(spatialSpec, initialScale = 0.6f),
        exit = fadeOut(effectsSpec) + scaleOut(spatialSpec, targetScale = 0.6f),
    ) {
        SmallFloatingActionButton(
            onClick = {
                val toTop = direction == ScrollJumpDirection.Up
                jumpInProgress = true
                onJump(toTop)
                coroutineScope.launch {
                    try {
                        val target = if (toTop) 0 else state.layoutInfo.totalItemsCount - 1
                        if (target < 0) return@launch
                        val current = state.firstVisibleItemIndex
                        if (abs(target - current) > 30) {
                            val intermediate = if (target < current) {
                                (target + 10).coerceAtMost(current)
                            } else {
                                (target - 10).coerceAtLeast(current)
                            }
                            state.scrollToItem(intermediate)
                        }
                        state.animateScrollToItem(target)
                    } finally {
                        jumpInProgress = false
                    }
                }
            },
            modifier = Modifier.testTag(
                if (direction == ScrollJumpDirection.Up) "scrollJumpTop" else "scrollJumpBottom",
            ),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            AnimatedContent(
                targetState = direction,
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (fadeIn(effectsSpec) + scaleIn(spatialSpec, initialScale = 0.8f)) togetherWith
                        (fadeOut(effectsSpec) + scaleOut(spatialSpec, targetScale = 0.8f))
                },
                label = "scrollJumpDirection",
            ) { targetDirection ->
                val toTop = targetDirection == ScrollJumpDirection.Up
                Icon(
                    painterResource(if (toTop) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward),
                    contentDescription = if (toTop) "맨 위로" else "맨 아래로",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private enum class ScrollJumpDirection { Up, Down }
