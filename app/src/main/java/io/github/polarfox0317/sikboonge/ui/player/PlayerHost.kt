package io.github.polarfox0317.sikboonge.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.PlayerArtwork
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.UserFacingErrors
import io.github.polarfox0317.sikboonge.ui.components.rememberMediaArtworkShape
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.WindowTier
import java.io.File

// app choice: only genuinely low windows (landscape phones) scroll the artwork/title section.
private val CompactPlayerHeight = 480.dp
// app choice: artwork fits a mini row alongside three accessible controls.
private val MiniArtworkSize = 56.dp
// app choice: emphasize artwork in a full-height player without displacing transport.
private val ExpandedArtworkSize = 240.dp
// app choice: preserve room for transport in low-height windows.
private val CompactArtworkSize = 88.dp
// app choice: reserve extra height for a noninteractive progress line below the mini row.
private val MiniPlayerProgressHeight = 4.dp
private val MiniPlayerProgressBottomPadding = 6.dp
private val MiniPlayerExtraHeight = 8.dp
private val MiniPlayerHeight = MiniArtworkSize + AppDimens.itemSpacing * 2 + MiniPlayerExtraHeight
// app choice: align the shared artwork after the standard accessible header action.
private val PlayerHeaderHeight = 48.dp
// app choice: the old slider closes its thumb/track gap.
private val SeekThumbTrackGap = 0.dp

/** The controller owns this preference; gestures and the shared visual transition stay here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerHost(
    state: AppUiState,
    actions: AppActions,
    tier: WindowTier,
    modifier: Modifier = Modifier,
    miniBottomPadding: Dp = 0.dp,
) {
    val stream = state.stream
    // Same source/active visibility gate as the old Activity, including local-source restoration.
    val visible = state.emptyPlayerVisible || (stream.source != PlaybackSource.NONE &&
        (stream.resolving || stream.ready || stream.error != null || stream.playing ||
            stream.buffering || stream.localAudioPath != null))
    if (!visible) {
        if (state.queueVisible) PlayerQueueSheet(state, actions)
        return
    }
    val timelineBounds = remember { mutableStateOf<Rect?>(null) }
    val collapseBounds = remember { mutableStateOf<Rect?>(null) }
    val collapseScrollBounds = remember { mutableStateOf<Rect?>(null) }
    val titleScrollState = rememberScrollState()
    // Free space left for artwork after title and controls, in the player surface coordinates.
    val artworkSlot = remember { mutableStateOf<Rect?>(null) }
    val hostCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val swipe = rememberPlayerSwipeModifier(
        stream = stream,
        expanded = state.playerExpanded,
        onStop = actions::onStop,
        onCollapse = { actions.setPlayerExpanded(false) },
        timelineBounds = timelineBounds,
        collapseBounds = collapseBounds,
        collapseScrollBounds = collapseScrollBounds,
        canCollapseScroll = { titleScrollState.value == 0 },
    )
    BoxWithConstraints(modifier.fillMaxSize().testTag("streamFeedback")) {
        val compact = maxHeight < CompactPlayerHeight
        val expandedArtworkMaxWidth = maxWidth - AppDimens.sectionSpacing * 2
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        val bottom by animateDpAsState(
            if (state.playerExpanded) 0.dp else miniBottomPadding,
            spatial, label = "playerDockPosition",
        )
        val side by animateDpAsState(
            if (state.playerExpanded) 0.dp else AppDimens.contentHorizontalPadding(tier),
            spatial, label = "playerSurfaceWidth",
        )
        val height by animateDpAsState(
            if (state.playerExpanded) maxHeight else MiniPlayerHeight.coerceAtMost(maxHeight),
            spatial, label = "playerSurfaceHeight",
        )
        val density = LocalDensity.current
        val slot = artworkSlot.value
        val fallbackArtwork = if (compact) CompactArtworkSize else ExpandedArtworkSize.coerceAtMost(expandedArtworkMaxWidth)
        // The largest square that fits the measured free space; small sources are scaled up (Crop).
        val slotSide = slot?.let { minOf(it.width, it.height).coerceAtLeast(0f) }
        val artworkSize by animateDpAsState(
            if (state.playerExpanded) slotSide?.let { with(density) { it.toDp() } } ?: fallbackArtwork
            else MiniArtworkSize,
            spatial, label = "playerArtworkSize",
        )
        val surfaceWidth = (maxWidth - side * 2).coerceAtLeast(0.dp)
        val artworkX by animateDpAsState(
            if (state.playerExpanded) {
                if (slot != null && slotSide != null) with(density) { (slot.left + (slot.width - slotSide) / 2).toDp() }
                else (surfaceWidth - fallbackArtwork) / 2
            } else AppDimens.itemSpacing,
            spatial, label = "playerArtworkX",
        )
        val artworkY by animateDpAsState(
            if (state.playerExpanded) {
                if (slot != null && slotSide != null) with(density) { (slot.top + (slot.height - slotSide) / 2).toDp() }
                else AppDimens.itemSpacing + PlayerHeaderHeight + AppDimens.itemSpacing
            } else AppDimens.itemSpacing,
            spatial, label = "playerArtworkY",
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter)
                // Spring motion can overshoot below zero when collapsing to the edge.
                .padding(
                    start = side.coerceAtLeast(0.dp),
                    end = side.coerceAtLeast(0.dp),
                    bottom = bottom.coerceAtLeast(0.dp),
                )
                .fillMaxWidth().height(height).then(swipe),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { hostCoordinates[0] = it }) {
                Crossfade(
                    targetState = state.playerExpanded,
                    modifier = Modifier.fillMaxSize(),
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "playerContent",
                ) { expanded ->
                    Box(Modifier.fillMaxSize().then(
                        if (expanded != state.playerExpanded) Modifier.clearAndSetSemantics { } else Modifier,
                    )) {
                        if (expanded) {
                            ExpandedPlayer(
                                state, actions, compact,
                                if (compact) CompactArtworkSize else ExpandedArtworkSize.coerceAtMost(expandedArtworkMaxWidth),
                                timelineBounds,
                                collapseBounds = collapseBounds,
                                collapseScrollBounds = collapseScrollBounds,
                                titleScrollState = titleScrollState,
                                artworkSlot = artworkSlot,
                                hostCoordinates = hostCoordinates,
                            )
                        } else {
                            MiniPlayer(stream, actions)
                        }
                    }
                }
                // One artwork composable survives expansion; its bounds and shape follow spatial motion.
                PlayerArtwork(
                    artworkPath = stream.artworkPath,
                    artworkBase64 = stream.artworkBase64,
                    modifier = Modifier.offset(x = artworkX, y = artworkY).size(artworkSize)
                        .clip(rememberMediaArtworkShape(stream.playing))
                        .clickable(
                            enabled = !state.playerExpanded,
                            role = Role.Button,
                            onClickLabel = "플레이어 펼치기",
                        ) { actions.setPlayerExpanded(true) },
                )
            }
        }
    }
    if (state.queueVisible) PlayerQueueSheet(state, actions)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayer(stream: StreamingState, actions: AppActions, modifier: Modifier = Modifier) {
    val empty = stream.source == PlaybackSource.NONE
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "플레이어 펼치기") {
                    actions.setPlayerExpanded(true)
                }
                .padding(start = AppDimens.itemSpacing, end = AppDimens.itemSpacing,
                    top = AppDimens.itemSpacing,
                    bottom = AppDimens.itemSpacing + MiniPlayerExtraHeight - MiniPlayerProgressHeight - MiniPlayerProgressBottomPadding)
                .testTag("streamMiniPlayerRow"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppDimens.selectionBadgeInset),
        ) {
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "플레이어 펼치기") {
                    actions.setPlayerExpanded(true)
                }.testTag("streamMiniExpand"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(MiniArtworkSize + AppDimens.itemSpacing))
                Text(
                    if (empty) "오디오 없음" else stream.title, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PlayerTransport(stream, actions, large = false)
            PlayerIconAction("재생목록", R.drawable.ic_media_queue, actions::onShowQueue,
                modifier = Modifier.testTag("streamMiniQueue"))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = AppDimens.itemSpacing)
            .padding(bottom = MiniPlayerProgressBottomPadding).height(MiniPlayerProgressHeight)
            .clearAndSetSemantics { }) {
            if (stream.ready && stream.durationMs > 0L) {
                LinearProgressIndicator(
                    progress = { playerPosition(stream).toFloat() / stream.durationMs.toFloat() },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                    color = MaterialTheme.colorScheme.primary,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandedPlayer(
    state: AppUiState,
    actions: AppActions,
    compact: Boolean,
    artworkSize: Dp,
    timelineBounds: MutableState<Rect?>,
    collapseBounds: MutableState<Rect?>,
    collapseScrollBounds: MutableState<Rect?>,
    titleScrollState: ScrollState,
    artworkSlot: MutableState<Rect?>,
    hostCoordinates: Array<LayoutCoordinates?>,
    modifier: Modifier = Modifier,
) {
    val reportArtworkSlot = Modifier.onGloballyPositioned { slot ->
        hostCoordinates[0]?.takeIf { it.isAttached }?.let { host ->
            artworkSlot.value = host.localBoundingBoxOf(slot, clipBounds = false)
        }
    }
    val statusBounds = remember { mutableStateOf<Rect?>(null) }
    val titleViewport = remember { mutableStateOf<Rect?>(null) }
    fun reportCollapseRegion() {
        val bottom = statusBounds.value?.bottom ?: return
        val viewport = titleViewport.value
        collapseBounds.value = Rect(0f, 0f, hostCoordinates[0]?.size?.width?.toFloat() ?: viewport?.right ?: statusBounds.value!!.right,
            if (compact && viewport != null) minOf(bottom, viewport.bottom) else bottom)
    }
    val reportStatus = Modifier.onGloballyPositioned { coordinates ->
        hostCoordinates[0]?.takeIf { it.isAttached }?.let { host ->
            statusBounds.value = host.localBoundingBoxOf(coordinates, clipBounds = false)
            reportCollapseRegion()
        }
    }
    val reportTitleViewport = Modifier.onGloballyPositioned { coordinates ->
        hostCoordinates[0]?.takeIf { it.isAttached }?.let { host ->
            val rect = host.localBoundingBoxOf(coordinates, clipBounds = false)
            titleViewport.value = rect
            collapseScrollBounds.value = rect.takeIf { compact }
            reportCollapseRegion()
        }
    }
    val stream = state.stream
    val empty = stream.source == PlaybackSource.NONE
    val canSave = stream.source == PlaybackSource.BOT
    val currentPlayerSourceKey = playerSourceKey(stream)
    val matchingBotDownload = state.downloads.firstOrNull { it.sourceBotFileId == stream.botFileId }
    val saving = state.preparingPlayerSaveSourceKey == currentPlayerSourceKey || matchingBotDownload != null
    val saveProgressText = matchingBotDownload?.let(::libraryDownloadProgressText)
    Column(
        modifier.fillMaxSize().padding(AppDimens.itemSpacing)
            .then(if (empty) Modifier.testTag("streamEmptyPlayer") else Modifier),
        verticalArrangement = Arrangement.spacedBy(AppDimens.selectionBadgeInset),
    ) {
        Row(
            Modifier.fillMaxWidth().height(PlayerHeaderHeight).testTag("streamPlayerHeader"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconAction("플레이어 접기", R.drawable.ic_media_minimize,
                { actions.setPlayerExpanded(false) }, modifier = Modifier.testTag("streamMinimize"))
            Text(if (empty) "오디오 없음" else stream.title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag("streamExpandedHeader"),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Regular windows: artwork takes every bit of height the title and controls leave free.
        if (!compact) Box(Modifier.weight(1f).fillMaxWidth().then(reportArtworkSlot).testTag("streamArtworkSlot"))
        Column(
            (if (compact) Modifier.weight(1f) else Modifier).fillMaxWidth().then(reportTitleViewport)
                .then(if (compact) Modifier.verticalScroll(titleScrollState) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppDimens.selectionBadgeInset),
        ) {
            if (compact) Spacer(Modifier.fillMaxWidth().height(artworkSize).then(reportArtworkSlot))
            Text(if (empty) "재생할 오디오를 선택해 주세요" else stream.title, style = MaterialTheme.typography.headlineSmallEmphasized,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!empty) Text(playerStatus(stream), style = MaterialTheme.typography.bodyMedium,
                modifier = reportStatus,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            stream.error?.let { raw ->
                val context = when {
                    stream.source == PlaybackSource.LOCAL_AUDIO -> UserFacingErrors.Context.LOCAL_MEDIA
                    stream.source == PlaybackSource.BOT -> UserFacingErrors.Context.TELEGRAM
                    else -> UserFacingErrors.Context.GENERAL
                }
                UserFacingErrors.fromErrorString(raw, context)?.let { text ->
                    Text(text, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().testTag("streamPlayerError"))
                }
                TextButton(onClick = actions::onRetryStream, modifier = Modifier.testTag("streamRetry")) {
                    Text("다시 시도")
                }
            }
        }
        // Keep seek and transport outside the scrollable artwork/title section in low windows.
        if (stream.error == null && stream.ready) PlayerTimeline(stream, actions, timelineBounds)
        if (!empty && stream.error == null && (stream.resolving || stream.buffering || !stream.ready)) {
            LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally)
                .size(LoadingIndicatorDefaults.IndicatorSize).testTag("streamProgress"))
        }
        if (!empty && stream.error == null) PlayerTransportGroup(state, actions)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSave) {
                PlayerIconAction(
                    if (saving) "오디오 저장 중" else "오디오 저장", R.drawable.ic_media_save,
                    actions::onSaveStream, modifier = Modifier.testTag("streamSave"),
                    enabled = !saving && stream.ready && stream.botFileId != null,
                    busy = saving, stateDescription = saveProgressText,
                )
            }
            if (!empty) PlayerIconAction("다른 앱으로 재생", R.drawable.ic_media_external, actions::onExternalPlayStream,
                modifier = Modifier.testTag("streamExternalPlay"))
            PlayerIconAction("재생목록", R.drawable.ic_media_queue, actions::onShowQueue,
                modifier = Modifier.testTag("streamQueue"))
        }
        if (!saveProgressText.isNullOrBlank()) Text(
            saveProgressText, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("streamSaveProgress"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerTimeline(
    stream: StreamingState,
    actions: AppActions,
    timelineBounds: MutableState<Rect?>,
    modifier: Modifier = Modifier,
) {
    val duration = stream.durationMs.coerceAtLeast(0L)
    val position = playerPosition(stream)
    val source = playerSourceKey(stream)
    val slider = key(source, duration) {
        rememberSliderState(value = position.toFloat(), trackRange = 0f..duration.toFloat().coerceAtLeast(1f))
    }
    var seeking by remember(source, duration) { mutableStateOf(false) }
    LaunchedEffect(source, duration, position) {
        if (!seeking) slider.value = position.toFloat()
    }
    DisposableEffect(source) { onDispose { timelineBounds.value = null } }
    Column(modifier.fillMaxWidth()
        .onGloballyPositioned { timelineBounds.value = it.boundsInRoot() }.testTag("streamSeekRow")) {
        if (duration > 0L) {
            Slider(
                state = slider,
                onValueChange = { value -> seeking = true; slider.value = value },
                onValueChangeFinished = {
                    actions.onSeek(slider.value.toLong().coerceIn(0L, duration))
                    seeking = false
                },
                enabled = !stream.resolving,
                modifier = Modifier.fillMaxWidth().testTag("streamSeek"),
                track = { sliderState ->
                    SliderDefaults.Track(sliderState = sliderState, thumbTrackGapSize = SeekThumbTrackGap)
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (seeking) slider.value.toLong() else position), style = MaterialTheme.typography.labelSmall)
            Text(if (duration > 0L) formatTime(duration) else "실시간 오디오",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerTransportGroup(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    val stream = state.stream
    val previousSource = remember { MutableInteractionSource() }
    val nextSource = remember { MutableInteractionSource() }
    val backSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    val forwardSource = remember { MutableInteractionSource() }
    val enabled = stream.error == null && stream.ready && !stream.resolving
    val position = playerPosition(stream)
    val back = (position - 10_000L).coerceAtLeast(0L)
    val requestedForward = if (position > Long.MAX_VALUE - 10_000L) Long.MAX_VALUE else position + 10_000L
    val forward = if (stream.durationMs > 0L) requestedForward.coerceAtMost(stream.durationMs) else requestedForward
    ButtonGroup(
        modifier = modifier.fillMaxWidth().testTag("streamPlayerTransportRow"),
        overflowIndicator = { },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 4 x 56dp actions + 80dp transport + 4 x 4dp gaps = 320dp, within the 328dp phone content width.
        customItem(buttonGroupContent = {
            PlayerIconAction("10초 뒤로", R.drawable.ic_media_seek_back_10, { actions.onSeek(back) },
                modifier = Modifier.animateWidth(backSource).size(IconButtonDefaults.mediumContainerSize()).testTag("streamSeekBack10"),
                enabled = enabled, interactionSource = backSource, iconSize = 28.dp)
        }, menuContent = { })
        customItem(buttonGroupContent = {
            PlayerIconAction("이전", R.drawable.ic_media_skip_previous, actions::onPlayPrevious,
                modifier = Modifier.animateWidth(previousSource).size(IconButtonDefaults.mediumContainerSize()).testTag("streamPrevious"),
                enabled = enabled && ((if (state.queueIndex >= 0) state.queueIndex else state.queueResumeIndex) > 0 || position > 3_000L), interactionSource = previousSource, iconSize = 28.dp)
        }, menuContent = { })
        customItem(buttonGroupContent = {
            PlayerTransport(stream, actions, modifier = Modifier.animateWidth(playSource),
                large = true, interactionSource = playSource)
        }, menuContent = { })
        customItem(buttonGroupContent = {
            PlayerIconAction("다음", R.drawable.ic_media_skip_next, actions::onPlayNext,
                modifier = Modifier.animateWidth(nextSource).size(IconButtonDefaults.mediumContainerSize()).testTag("streamNext"),
                enabled = enabled && (if (state.queueIndex >= 0) state.queueIndex + 1 else state.queueResumeIndex) in state.queue.indices,
                interactionSource = nextSource, iconSize = 28.dp)
        }, menuContent = { })
        customItem(buttonGroupContent = {
            PlayerIconAction("10초 앞으로", R.drawable.ic_media_seek_forward_10, { actions.onSeek(forward) },
                modifier = Modifier.animateWidth(forwardSource).size(IconButtonDefaults.mediumContainerSize()).testTag("streamSeekForward10"),
                enabled = enabled, interactionSource = forwardSource, iconSize = 28.dp)
        }, menuContent = { })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerTransport(
    stream: StreamingState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val label = when {
        stream.playing -> "일시정지"
        stream.ended -> "처음부터 재생"
        else -> "계속 듣기"
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
    ) {
        FilledIconToggleButton(
            checked = stream.playing,
            onCheckedChange = { if (it) actions.onResume() else actions.onPause() },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = modifier.then(if (large) Modifier.size(80.dp) else Modifier.size(IconButtonDefaults.mediumContainerSize()))
                .semantics { contentDescription = label; stateDescription = playerStatus(stream) }
                .testTag("streamTransport"),
            enabled = stream.error == null && stream.ready && !stream.resolving,
            interactionSource = interactionSource,
        ) {
            Crossfade(stream.playing, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "playerTransportIcon") { playing ->
                Icon(painterResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play), null,
                    Modifier.size(if (large) IconButtonDefaults.largeIconSize else IconButtonDefaults.mediumIconSize))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerIconAction(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    stateDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
    iconSize: Dp = IconButtonDefaults.smallIconSize,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled && !busy,
            modifier = modifier.semantics {
                contentDescription = label
                stateDescription?.let { this.stateDescription = it }
            }, interactionSource = interactionSource) {
            if (busy) LoadingIndicator(Modifier.size(IconButtonDefaults.smallIconSize))
            else Icon(painterResource(icon), null, Modifier.size(iconSize))
        }
    }
}

private fun playerStatus(stream: StreamingState): String = when {
    stream.playing -> "재생 중"
    stream.ended -> "재생 종료"
    stream.ready -> "일시정지됨"
    else -> "재생 준비 중"
}

private fun playerPosition(stream: StreamingState): Long =
    if (stream.durationMs > 0L) stream.positionMs.coerceIn(0L, stream.durationMs)
    else stream.positionMs.coerceAtLeast(0L)

private fun playerSourceKey(stream: StreamingState): String = when {
    stream.source == PlaybackSource.LOCAL_AUDIO -> stream.localAudioPath?.let { "local:${runCatching { File(it).canonicalPath }.getOrDefault(File(it).absolutePath)}" } ?: "local:invalid"
    stream.source == PlaybackSource.BOT -> stream.botFileId?.let { "bot:$it" } ?: "bot:invalid"
    else -> ""
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(totalSeconds / 60, seconds)
    }
}

private fun libraryDownloadProgressText(download: io.github.polarfox0317.sikboonge.LibraryDownload): String = when {
    download.stage == "대기 중" -> "저장 대기 중"
    download.bytesPerSecond != null -> io.github.polarfox0317.sikboonge.DownloadProgress.format(
        io.github.polarfox0317.sikboonge.DownloadMetrics(
            progress = download.progress,
            bytesPerSecond = download.bytesPerSecond,
            remainingSeconds = download.remainingSeconds,
        ),
    ).let { metrics -> listOfNotNull(metrics.percent, metrics.details).joinToString(" · ").ifBlank { "저장 중" } }
    download.progress != null -> io.github.polarfox0317.sikboonge.DownloadProgress.format(
        io.github.polarfox0317.sikboonge.DownloadMetrics(progress = download.progress),
    ).percent?.let { "저장 중 · $it" } ?: "저장 중"
    else -> download.stage.ifBlank { "저장 중" }
}
