package io.github.polarfox0317.sikboonge.ui.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.polarfox0317.sikboonge.PlayerArtwork
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ui.components.rememberMediaArtworkShape
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerQueueSheet(state: AppUiState, actions: AppActions) {
    val listState = rememberLazyListState()
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    var pendingMoveTo by remember { mutableStateOf<Int?>(null) }
    var previousPointerY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgeSize = with(density) { 64.dp.toPx() }
    val maximumScrollStep = with(density) { 12.dp.toPx() }
    val elevationPx = with(density) { 6.dp.toPx() }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    fun finishDrag() { draggedKey = null; pendingMoveTo = null }
    fun moveAcrossNeighbor() {
        val key = draggedKey ?: return
        val from = latestState.queue.indexOfFirst { it.key == key }
        if (from < 0) { finishDrag(); return }
        pendingMoveTo?.let { if (from != it) return else pendingMoveTo = null }
        val draggedInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        if (draggedInfo.index != from) return
        val center = dragTop + dragHeight / 2f
        val neighbor = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != key && ((item.index == from + 1 && center > item.offset + item.size / 2f) ||
                (item.index == from - 1 && center < item.offset + item.size / 2f))
        } ?: return
        pendingMoveTo = neighbor.index
        latestActions.onMoveQueueItem(from, neighbor.index)
    }

    LaunchedEffect(Unit) {
        if (state.queueIndex in state.queue.indices) listState.scrollToItem(state.queueIndex)
    }
    LaunchedEffect(state.queueEditing) { if (!state.queueEditing) finishDrag() }
    LaunchedEffect(draggedKey) {
        while (draggedKey != null) {
            withFrameNanos { }
            val layout = listState.layoutInfo
            val center = dragTop + dragHeight / 2f
            val start = layout.viewportStartOffset.toFloat()
            val end = layout.viewportEndOffset.toFloat()
            val speed = when {
                center < start + edgeSize -> -maximumScrollStep * ((start + edgeSize - center) / edgeSize).coerceIn(0f, 1f)
                center > end - edgeSize -> maximumScrollStep * ((center - end + edgeSize) / edgeSize).coerceIn(0f, 1f)
                else -> 0f
            }
            if (abs(speed) > 0f) listState.scrollBy(speed)
            moveAcrossNeighbor()
        }
    }
    // ModalBottomSheet owns system insets; the content adds no navigation padding.
    ModalBottomSheet(
        onDismissRequest = actions::onHideQueue,
        sheetGesturesEnabled = draggedKey == null,
        modifier = Modifier.testTag("queueSheet"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppDimens.itemSpacing, vertical = AppDimens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                verticalAlignment = Alignment.CenterVertically) {
                Text("재생목록", style = MaterialTheme.typography.titleLarge)
                Text("${state.queue.size}개", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.queueEditing) TextButton(
                onClick = actions::onDeleteQueueSelection,
                enabled = state.queueSelectedKeys.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("삭제") }
            TextButton(onClick = actions::onToggleQueueEditing) { Text(if (state.queueEditing) "완료" else "수정") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(state.queue, key = { _, item -> item.key }) { index, item ->
                val current = index == state.queueIndex
                val dragging = draggedKey == item.key
                val colors = MaterialTheme.colorScheme
                val rowShape = MaterialTheme.shapes.medium
                var handleCoordinates by remember(item.key) { mutableStateOf<LayoutCoordinates?>(null) }
                val accessibilityMoves = if (state.queueEditing) buildList {
                    if (index > 0) add(CustomAccessibilityAction("위로 이동") { actions.onMoveQueueItem(index, index - 1); true })
                    if (index < state.queue.lastIndex) add(CustomAccessibilityAction("아래로 이동") { actions.onMoveQueueItem(index, index + 1); true })
                } else emptyList()
                ListItem(
                    onClick = { if (state.queueEditing) actions.onToggleQueueSelection(item.key) else actions.onPlayQueueItem(index) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .animateItem(fadeInSpec = effects, placementSpec = if (dragging) null else spatial, fadeOutSpec = effects)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.key }?.offset
                            translationY = if (dragging && offset != null) dragTop - offset else 0f
                            shadowElevation = if (dragging) elevationPx else 0f
                            shape = rowShape
                        }
                        .semantics { customActions = accessibilityMoves }
                        .testTag("queueItem_$index"),
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.queueEditing) Checkbox(checked = item.key in state.queueSelectedKeys, onCheckedChange = null)
                            PlayerArtwork(item.artworkPath, item.artworkBase64,
                                Modifier.size(40.dp).clip(rememberMediaArtworkShape(current && state.stream.playing)))
                        }
                    },
                    trailingContent = if (state.queueEditing) ({
                        Box(Modifier.size(48.dp)
                            .semantics { contentDescription = "순서 바꾸기"; customActions = accessibilityMoves }
                            .onGloballyPositioned { handleCoordinates = it }
                            .pointerInput(item.key) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        previousPointerY = handleCoordinates?.takeIf { it.isAttached }?.localToRoot(start)?.y ?: start.y
                                        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.key }
                                        if (info != null) {
                                            draggedKey = item.key; dragTop = info.offset.toFloat()
                                            dragHeight = info.size.toFloat(); pendingMoveTo = null
                                        }
                                    },
                                    onDragEnd = ::finishDrag,
                                    onDragCancel = ::finishDrag,
                                    onDrag = { change, delta ->
                                        change.consume()
                                        if (draggedKey == item.key) {
                                            // Root coordinates stay stable while the dragged row translates beneath the pointer.
                                            val pointerY = handleCoordinates?.takeIf { it.isAttached }?.localToRoot(change.position)?.y
                                            dragTop += if (pointerY != null) pointerY - previousPointerY else delta.y
                                            if (pointerY != null) previousPointerY = pointerY
                                            moveAcrossNeighbor()
                                        }
                                    },
                                )
                            }, contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_drag_handle), null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                        }
                    }) else if (current) ({ Text("재생 중", style = MaterialTheme.typography.labelMedium) }) else null,
                    colors = when {
                        dragging -> ListItemDefaults.colors(containerColor = colors.surfaceContainerHighest)
                        current -> ListItemDefaults.colors(containerColor = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer, leadingContentColor = colors.onSecondaryContainer,
                            trailingContentColor = colors.onSecondaryContainer)
                        else -> ListItemDefaults.colors()
                    },
                ) { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
