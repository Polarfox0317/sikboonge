package io.github.polarfox0317.sikboonge.ui.bot

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.BotCredentials
import io.github.polarfox0317.sikboonge.BotUiState
import io.github.polarfox0317.sikboonge.BotVideoThumbnail
import io.github.polarfox0317.sikboonge.BotVideoTitlePolicy
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.TRANSIENT_FEEDBACK_TIMEOUT_MS
import io.github.polarfox0317.sikboonge.UserFacingErrors
import io.github.polarfox0317.sikboonge.ui.components.ConfirmDeleteDialog
import io.github.polarfox0317.sikboonge.ui.components.CenteredLoading
import io.github.polarfox0317.sikboonge.ui.components.EmptyState
import io.github.polarfox0317.sikboonge.ui.components.FormColumn
import io.github.polarfox0317.sikboonge.ui.components.MediaCard
import io.github.polarfox0317.sikboonge.ui.components.MediaCardActions
import io.github.polarfox0317.sikboonge.ui.components.ListControls
import io.github.polarfox0317.sikboonge.ui.components.MediaTile
import io.github.polarfox0317.sikboonge.ui.components.MediaMenuItem
import io.github.polarfox0317.sikboonge.ui.components.RenameDialog
import io.github.polarfox0317.sikboonge.ui.components.SectionCard
import io.github.polarfox0317.sikboonge.ui.components.ScrollJumpBubbles
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.state.Destination
import io.github.polarfox0317.sikboonge.ui.state.ListLayout
import io.github.polarfox0317.sikboonge.ui.state.ListQuery
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.WindowTier
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BotScreen(
    state: AppUiState,
    actions: AppActions,
    tier: WindowTier,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reselectionToken: Int = 0,
    onScrollJump: (toTop: Boolean) -> Unit = {},
) {
    // The saveable grid state remains alive while another Bot body is visible.
    val gridState = rememberLazyGridState()
    var editingMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(reselectionToken) {
        if (reselectionToken != 0) gridState.scrollToItem(0)
    }
    val body = BotBodyPolicy.resolve(
        initialStateLoaded = state.bot.initialStateLoaded,
        connected = state.bot.connected,
        setupComplete = state.bot.setupComplete,
        paired = state.bot.paired,
        setupRequested = state.botSetupRequested,
        hasCachedVideos = state.bot.videos.isNotEmpty(),
    )
    Crossfade(
        targetState = body,
        modifier = modifier.fillMaxSize(),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "botBody",
    ) { target ->
        Box(Modifier.fillMaxSize().then(
            if (target != body) Modifier.clearAndSetSemantics { } else Modifier,
        )) {
            when (target) {
                BotBody.Loading -> CenteredLoading(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    label = "봇 연결 상태를 확인하고 있어요.",
                )
                BotBody.Setup -> BotSetup(state, actions, contentPadding)
                BotBody.Pairing -> BotPairing(state, actions, contentPadding)
                BotBody.Inbox -> BotInbox(state, actions, gridState, tier, contentPadding, onRename = { editingMessageId = it }, onScrollJump = onScrollJump)
            }
        }
    }
    state.bot.videos.firstOrNull { it.messageId == editingMessageId }?.let { video ->
        RenameDialog(
            initial = video.displayName,
            maxLength = BotVideoTitlePolicy.MAX_TITLE_CHARS,
            onConfirm = { actions.onRenameBotVideo(video, it); editingMessageId = null },
            onDismiss = { editingMessageId = null },
        )
    }
    state.pendingBotDelete?.takeIf { it.isNotEmpty() }?.let { targets ->
        ConfirmDeleteDialog(targets.size, { actions.onConfirmBotDelete(targets) }, { actions.onRequestBotDelete(emptySet()) }, bot = true)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BotSetup(state: AppUiState, actions: AppActions, contentPadding: PaddingValues) {
    val bot = state.bot
    val fieldsEnabled = !bot.connected && !bot.busy
    val parsedApiId = state.botApiIdText.toIntOrNull()
    val tokenFocusRequester = remember { FocusRequester() }
    val apiIdFocusRequester = remember { FocusRequester() }
    val apiHashFocusRequester = remember { FocusRequester() }
    val canConnect = !bot.busy && parsedApiId != null && parsedApiId > 0 &&
        state.botApiHash.isNotBlank() && state.botToken.isNotBlank()
    val connectBot: () -> Unit = {
        parsedApiId?.let { actions.onConnectBot(BotCredentials(it, state.botApiHash.trim(), state.botToken.trim())) }
    }
    FormColumn(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).testTag("botContent")) {
        Text("내 봇으로 받은 영상 듣기", style = MaterialTheme.typography.titleMedium)
        Text("내가 만든 Telegram 봇의 비공개 대화로 전달한 영상만 표시돼요. 앱을 열어 연결해 둔 동안 새 영상을 받아요. 큰 영상도 바로 들을 수 있어요. 앱을 종료한 동안에는 수신하지 않으니, 다시 받을 때 앱을 열고 연결해 주세요. 24시간 서버 수신은 제공하지 않아요. Telegram에서 전달을 막은 보호 콘텐츠는 받을 수 없어요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SectionCard(
            title = { Text("Telegram BotFather 열기") },
            body = {
                Text("1. Telegram에서 @BotFather를 열고 /newbot을 보내세요. 안내에 따라 봇 이름과 고유한 username을 정한 뒤, 발급된 token을 복사하세요.")
                TextButton(onClick = { actions.onOpenExternalUrl("https://t.me/BotFather") }) { Text("Telegram BotFather 열기") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            title = { Text("Telegram API 앱 페이지 열기") },
            body = {
                Text("2. my.telegram.org에 로그인해 API development tools에서 앱을 등록하고 api_id와 api_hash를 확인하세요.")
                TextButton(onClick = { actions.onOpenExternalUrl("https://my.telegram.org/apps") }) { Text("Telegram API 앱 페이지 열기") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            title = { Text("처음 한 번 설정") },
            body = {
                Text("3. 아래 칸에 세 값을 입력하고 연결하세요. token과 api_hash는 비밀번호처럼 취급하세요. 이 기기의 암호화 저장소에 보관돼요.")
                OutlinedTextField(
                    value = state.botToken,
                    onValueChange = { actions.onBotTokenChanged(it.trim()) },
                    label = { Text("봇 token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { apiIdFocusRequester.requestFocus() }),
                    singleLine = true,
                    enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().focusRequester(tokenFocusRequester).testTag("botToken"),
                )
                OutlinedTextField(
                    value = state.botApiIdText,
                    onValueChange = { actions.onBotApiIdChanged(it.filter(Char::isDigit).take(10)) },
                    label = { Text("api_id") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { apiHashFocusRequester.requestFocus() }),
                    singleLine = true,
                    enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().focusRequester(apiIdFocusRequester).testTag("botApiId"),
                )
                OutlinedTextField(
                    value = state.botApiHash,
                    onValueChange = actions::onBotApiHashChanged,
                    label = { Text("api_hash") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canConnect) connectBot() }),
                    singleLine = true,
                    enabled = fieldsEnabled,
                    modifier = Modifier.fillMaxWidth().focusRequester(apiHashFocusRequester).testTag("botApiHash"),
                )
                BotStatus(bot)
                Button(
                    onClick = connectBot,
                    enabled = canConnect,
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight).testTag("botConnect"),
                ) {
                    if (bot.busy) LoadingIndicator(Modifier.size(LoadingIndicatorDefaults.IndicatorSize))
                    else Text("암호화해 저장하고 연결", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BotPairing(state: AppUiState, actions: AppActions, contentPadding: PaddingValues) {
    val bot = state.bot
    FormColumn(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).testTag("botContent")) {
        BotPairingCard(bot, actions, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BotPairingCard(bot: BotUiState, actions: AppActions, modifier: Modifier = Modifier) {
    SectionCard(
        title = { Text("내 Telegram 계정 연결") },
        body = {
            BotStatus(bot)
            Text("내 계정에서 봇의 비공개 대화를 시작해야 영상 목록이 연결돼요.")
            Button(
                onClick = actions::onPairBot,
                enabled = !bot.busy && !bot.username.isNullOrBlank(),
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight).testTag("botPair"),
            ) { Text("내 Telegram 계정 연결", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            bot.pairingUrl?.let { url ->
                OutlinedButton(
                    onClick = { actions.onOpenExternalUrl(url) },
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                ) { Text("Telegram에서 Start 누르기", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BotInbox(
    state: AppUiState,
    actions: AppActions,
    gridState: LazyGridState,
    tier: WindowTier,
    contentPadding: PaddingValues,
    onRename: (Long) -> Unit,
    onScrollJump: (toTop: Boolean) -> Unit,
) {
    val bot = state.bot
    val refreshEnabled = bot.connected && bot.paired && !bot.busy && !bot.refreshing && !state.selectionMode
    val showReconnectButton = bot.needsReconnect && bot.paired && !state.botSetupRequested && !state.selectionMode
    val pullState = rememberPullToRefreshState()
    val horizontalPadding = AppDimens.contentHorizontalPadding(tier)
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val gridPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction) + horizontalPadding,
        top = contentPadding.calculateTopPadding() + AppDimens.itemSpacing,
        end = contentPadding.calculateEndPadding(direction) + horizontalPadding,
        bottom = contentPadding.calculateBottomPadding() + AppDimens.itemSpacing +
            if (showReconnectButton) ButtonDefaults.MediumContainerHeight + AppDimens.itemSpacing else 0.dp,
    )
    val conversionRunning = state.conversion.running || state.preparingBotSaveMessageId != null
    val fontScale = LocalDensity.current.fontScale
    val minimumColumnWidth = when (state.botLayout) {
        ListLayout.List -> AppDimens.minimumGridColumnWidth(fontScale)
        ListLayout.Grid -> AppDimens.minimumTileWidth(fontScale)
    }
    PullToRefreshBox(
        isRefreshing = state.bot.refreshing,
        onRefresh = actions::onRefreshBot,
        enabled = refreshEnabled,
        state = pullState,
        modifier = Modifier.fillMaxSize().testTag("botPullRefresh"),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState, isRefreshing = state.bot.refreshing,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = contentPadding.calculateTopPadding()),
            )
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minimumColumnWidth),
            state = gridState,
            modifier = Modifier.fillMaxSize().consumeWindowInsets(contentPadding).testTag("botContent"),
            contentPadding = gridPadding,
            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        ) {
            if (!bot.connected) {
                item(key = "bot-disconnected-notice", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "연결 끊김 · 저장된 영상은 표시되며, 재생과 저장은 다시 연결한 뒤 사용할 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("botDisconnectedNotice"),
                    )
                }
            }
            item(key = "listControls", span = { GridItemSpan(maxLineSpan) }) {
                ListControls(
                    query = state.botQuery,
                    onQueryChange = actions::onBotQueryChanged,
                    layout = state.botLayout,
                    onLayoutChange = { actions.onLayoutChanged(Destination.Bot, it) },
                    placeholder = "\uBD07 \uC601\uC0C1 \uAC80\uC0C9",
                    tagPrefix = "bot",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.selectionMode && bot.videos.isNotEmpty()) {
                item(key = "bot-selection-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "삭제할 영상을 체크해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("botSelectionHint"),
                    )
                }
            }
            if (bot.videos.isEmpty()) {
                item(key = "bot-empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = "연결된 비공개 대화의 영상 · ${bot.videos.size}개",
                        body = if (bot.connected) {
                            "봇과의 비공개 대화에 영상을 전달해 주세요. 전달된 영상이 여기에 표시돼요."
                        } else {
                            "봇과의 비공개 대화에 영상을 전달하면 여기에 표시돼요."
                        },
                    )
                }
            }
            if (bot.videos.isNotEmpty() && state.visibleBotVideos.isEmpty()) {
                item(key = "bot_no_results", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("botNoResults"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                    ) {
                        EmptyState(title = "\uAC80\uC0C9 \uACB0\uACFC\uAC00 \uC5C6\uC5B4\uC694.", body = "\uAC80\uC0C9\uC5B4\uB098 \uD544\uD130\uB97C \uBC14\uAFD4 \uBCF4\uC138\uC694.")
                        TextButton(onClick = { actions.onBotQueryChanged(ListQuery()) }) {
                            Text("\uAC80\uC0C9\u00B7\uD544\uD130 \uCD08\uAE30\uD654")
                        }
                    }
                }
            }
            items(state.visibleBotVideos, key = { it.messageId }) { video ->
                val available = bot.connected && !bot.busy && !video.needsForward
                Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
                    val itemModifier = Modifier.fillMaxWidth()
                        .testTag(if (state.selectionMode) "botSelect_${video.messageId}" else "botVideo_${video.messageId}")
                        .semantics { if (state.selectionMode) contentDescription = "\uC601\uC0C1 \uC120\uD0DD: ${video.displayName}" }
                    val menuItems = listOf(
                        MediaMenuItem("\uC7AC\uC0DD\uBAA9\uB85D\uC5D0 \uCD94\uAC00", R.drawable.ic_playlist_add, enabled = available, onClick = { actions.onAddBotVideoToQueue(video) }, testTag = "botAddToQueue"),
                        MediaMenuItem("\uC601\uC0C1 \uC81C\uBAA9 \uC218\uC815", R.drawable.ic_bot_edit, onClick = { onRename(video.messageId) }, testTag = "botRenameIcon_${video.messageId}"),
                        MediaMenuItem("\uB2E4\uB978 \uC571\uC73C\uB85C \uC7AC\uC0DD", R.drawable.ic_bot_open_in_new, enabled = available, onClick = { actions.onExternalPlayBotVideo(video) }, testTag = "botExternalPlay"),
                        MediaMenuItem("\uC601\uC0C1 \uC0AD\uC81C", R.drawable.ic_bot_delete, destructive = true, onClick = { actions.onRequestBotDelete(setOf(video.messageId)) }, testTag = "botDelete_${video.messageId}"),
                    )
                    val itemActions: @Composable (Boolean) -> Unit = { compact ->
                        MediaCardActions(
                            compact = compact,
                            onListen = { actions.onPlayBotVideo(video) }, listenEnabled = available,
                            onSave = { actions.onSaveBotVideo(video, state.saveFormat) },
                            saveEnabled = available && !conversionRunning, saveBusy = conversionRunning,
                            modifier = Modifier.fillMaxWidth().testTag("botCardActions"),
                            listenModifier = Modifier.testTag("botListen").semantics { contentDescription = "\uB4E3\uAE30" },
                            saveModifier = Modifier.testTag("botSaveToLibrary").semantics {
                                contentDescription = "\uBCF4\uAD00\uD568\uC5D0 \uC800\uC7A5 (" + state.saveFormat.title + ")"
                                if (conversionRunning) stateDescription = "\uC800\uC7A5 \uC911"
                            },
                            moreModifier = Modifier.testTag("botVideoMenu_${video.messageId}"),
                            menuModifier = Modifier.testTag("botVideoMenuContent_${video.messageId}"),
                            menuItems = menuItems,
                        )
                    }
                    val itemMeta = formatBytes(video.bytes) + " · " + formatDuration(video.durationSeconds)
                    val itemThumbnail: @Composable (Modifier) -> Unit = { BotVideoThumbnail(video, it) }
                    Box(if (state.selectionMode) Modifier.testTag("botVideo_${video.messageId}") else Modifier) {
                    if (state.botLayout == ListLayout.Grid) {
                        MediaTile(
                            title = video.displayName, meta = itemMeta,
                            selected = video.messageId in state.selectedBotMessageIds,
                            selectionMode = state.selectionMode,
                            playing = state.stream.botFileId == video.fileId && state.stream.playing,
                            onClick = { if (state.selectionMode) actions.onToggleBotSelection(video.messageId) },
                            onLongClick = { if (!state.selectionMode) actions.onBeginSelection(); actions.onToggleBotSelection(video.messageId) },
                            thumbnail = itemThumbnail, actions = { itemActions(true) }, modifier = itemModifier,
                        )
                    } else {
                        MediaCard(
                            title = video.displayName, meta = itemMeta,
                            selected = video.messageId in state.selectedBotMessageIds,
                            selectionMode = state.selectionMode,
                            playing = state.stream.botFileId == video.fileId && state.stream.playing,
                            onClick = { if (state.selectionMode) actions.onToggleBotSelection(video.messageId) },
                            onLongClick = { if (!state.selectionMode) actions.onBeginSelection(); actions.onToggleBotSelection(video.messageId) },
                            thumbnail = itemThumbnail, actions = { itemActions(false) }, modifier = itemModifier,
                        )
                    }
                    }
                    if (video.needsForward) Text("이전 연결의 영상이에요. 다시 전달하면 들을 수 있어요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showReconnectButton) {
            Button(
                onClick = { actions.onOpenBotSetup() },
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = contentPadding.calculateBottomPadding() + AppDimens.itemSpacing)
                    .heightIn(min = ButtonDefaults.MediumContainerHeight)
                    .testTag("botReconnect"),
            ) {
                Text("연결하기", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
        ScrollJumpBubbles(
            state = gridState,
            enabled = !state.selectionMode,
            onJump = onScrollJump,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(
                    end = contentPadding.calculateEndPadding(direction) + 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp +
                        if (showReconnectButton) ButtonDefaults.MediumContainerHeight + AppDimens.itemSpacing else 0.dp,
                ),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BotStatus(bot: BotUiState) {
    Text("${bot.status}${bot.username?.let { " · @$it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
    BotInlineStatus(bot)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BotInlineStatus(bot: BotUiState) {
    if (bot.busy) LoadingIndicator(Modifier.size(LoadingIndicatorDefaults.IndicatorSize))
    val message = remember(bot.error) { UserFacingErrors.fromErrorString(bot.error, UserFacingErrors.Context.TELEGRAM) }
    var errorVisible by remember { mutableStateOf(message != null) }
    LaunchedEffect(message) {
        errorVisible = message != null
        if (message != null) { delay(TRANSIENT_FEEDBACK_TIMEOUT_MS); errorVisible = false }
    }
    if (errorVisible && message != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
            modifier = Modifier.fillMaxWidth().testTag("botTransientError"),
        ) { Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(AppDimens.itemSpacing)) }
    }
}

private fun formatBytes(value: Long): String = when {
    value <= 0L -> "크기 확인 중"
    value < 1024L -> "$value B"
    value < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
    value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
    else -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
}

private fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

@Composable
internal fun BotInboxSubtitle(bot: BotUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing / 2),
    ) {
        Text(
            "연결된 비공개 대화의 영상 · ${bot.videos.size}개",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("botInbox"),
        )
        Text(
            "다른 재생 앱에는 오디오만 전달해요. 처음 여는 데 잠시 걸릴 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
