package io.github.polarfox0317.sikboonge.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.UserFacingErrors
import io.github.polarfox0317.sikboonge.ui.bot.BotScreen
import io.github.polarfox0317.sikboonge.ui.bot.BotInboxSubtitle
import io.github.polarfox0317.sikboonge.ui.components.SelectionToolbar
import io.github.polarfox0317.sikboonge.ui.feedback.MessageController
import io.github.polarfox0317.sikboonge.ui.feedback.MessageHost
import io.github.polarfox0317.sikboonge.ui.library.LibraryScreen
import io.github.polarfox0317.sikboonge.ui.convert.ConvertScreen
import io.github.polarfox0317.sikboonge.ui.player.PlayerHost
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppController
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.state.Destination
import io.github.polarfox0317.sikboonge.ui.state.SettingsPage
import io.github.polarfox0317.sikboonge.ui.state.HostEffects
import io.github.polarfox0317.sikboonge.ui.settings.SettingsScreen
import io.github.polarfox0317.sikboonge.ui.settings.licenses.OpenSourceLicenses
import io.github.polarfox0317.sikboonge.ui.settings.licenses.OpenSourceLicenseListScreen
import io.github.polarfox0317.sikboonge.ui.settings.licenses.OpenSourceLicenseDetailScreen
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.WindowTier
import io.github.polarfox0317.sikboonge.ui.theme.rememberWindowTier
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// app choice: matches PlayerHost MiniArtworkSize (56dp) plus its two AppDimens.itemSpacing insets.
private val MiniPlayerContentHeight = 88.dp
// app choice: reserve the floating toolbar touch row plus its outer spacing in scrolling content.
private val SelectionToolbarContentHeight = 80.dp
private const val RAIL_EXPAND_LABEL = "내비게이션 레일 펼치기"
private const val RAIL_COLLAPSE_LABEL = "내비게이션 레일 접기"
private const val BOT_SAVE_PREPARATION_TEXT = "보관함에 저장할 오디오를 준비하고 있어요."
private const val CONVERSION_COMPLETE_TEXT = "추출 완료 · 보관함에서 재생, 저장, 공유할 수 있어요."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppShell(
    controller: AppController,
    host: HostEffects,
    modifier: Modifier = Modifier,
    stateOverride: AppUiState? = null,
    actionsOverride: AppActions? = null,
) {
    val liveState by controller.uiState.collectAsStateWithLifecycle()
    val state = stateOverride ?: liveState
    val actions = actionsOverride ?: controller.actions
    val tier = rememberWindowTier()
    var menuExpanded by remember { mutableStateOf(false) }
    var convertReselection by rememberSaveable { mutableIntStateOf(0) }
    var libraryReselection by rememberSaveable { mutableIntStateOf(0) }
    var botReselection by rememberSaveable { mutableIntStateOf(0) }
    val savedScreens = rememberSaveableStateHolder()
    val convertAppBar = rememberTopAppBarState()
    val libraryAppBar = rememberTopAppBarState()
    val botAppBar = rememberTopAppBarState()
    val settingsAppBar = rememberTopAppBarState()
    val licensesAppBar = rememberTopAppBarState()
    val licenseDetailAppBar = rememberTopAppBarState()
    val appBarState = when (state.destination) {
        Destination.Convert -> convertAppBar
        Destination.Library -> libraryAppBar
        Destination.Bot -> botAppBar
        Destination.Settings -> when (state.settingsPage) {
            SettingsPage.Root -> settingsAppBar
            SettingsPage.Licenses -> licensesAppBar
            is SettingsPage.LicenseDetail -> licenseDetailAppBar
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = appBarState,
        snapAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
    val appBarJumpScope = rememberCoroutineScope()
    val appBarJumpSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val onAppBarScrollJump: (Boolean) -> Unit = { toTop ->
        appBarJumpScope.launch {
            val topAppBarState = scrollBehavior.state
            if (toTop) topAppBarState.contentOffset = 0f
            animate(
                initialValue = topAppBarState.heightOffset,
                targetValue = if (toTop) 0f else topAppBarState.heightOffsetLimit,
                animationSpec = appBarJumpSpec,
            ) { value, _ -> topAppBarState.heightOffset = value }
        }
    }
    val railState = rememberWideNavigationRailState()
    var expandedRailPreference by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tier, expandedRailPreference) {
        if (tier == WindowTier.Expanded && expandedRailPreference) railState.expand()
        else railState.collapse()
    }
    val playerVisible = state.emptyPlayerVisible || state.stream.isPlayerVisible()
    val miniReserve = if (playerVisible && !state.playerExpanded) MiniPlayerContentHeight else 0.dp
    val selectionReserve = if (state.selectionMode) SelectionToolbarContentHeight else 0.dp
    val messages = rememberShellMessages(controller, state)
    val imeVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    val selectDestination: (Destination) -> Unit = { destination ->
        menuExpanded = false
        if (actions.onTopLevelSelected(destination)) {
            if (state.destination == destination) onAppBarScrollJump(true)
            when (destination) {
                Destination.Convert -> convertReselection++
                Destination.Library -> libraryReselection++
                Destination.Bot -> botReselection++
                Destination.Settings -> Unit
            }
        }
    }
    LaunchedEffect(state.destination, state.selectionMode) { menuExpanded = false }
    val groups = shellMenuGroups(state, actions, host.diagnosticsAvailable && state.diagnosticsAvailable)
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val destinationShift = with(LocalDensity.current) { AppDimens.itemSpacing.roundToPx() }
    val direction = LocalLayoutDirection.current

    Row(modifier.fillMaxSize()) {
        if (tier != WindowTier.Compact) {
            WideNavigationRail(
                state = railState,
                header = if (tier == WindowTier.Expanded) {
                    {
                        val railExpanded = railState.targetValue == WideNavigationRailValue.Expanded
                        val railToggleLabel = if (railExpanded) RAIL_COLLAPSE_LABEL else RAIL_EXPAND_LABEL
                        ShellTooltip(railToggleLabel) {
                            IconButton(
                                onClick = { expandedRailPreference = !expandedRailPreference },
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.testTag("railToggle")
                                    .semantics { stateDescription = railToggleLabel },
                            ) {
                                Icon(
                                    painterResource(if (railExpanded) R.drawable.ic_menu_open else R.drawable.ic_menu),
                                    contentDescription = railToggleLabel,
                                )
                            }
                        }
                    }
                } else null,
                modifier = Modifier.testTag("mainNavigationBar"),
            ) {
                ShellDestinations.forEach { destination ->
                    WideNavigationRailItem(
                        selected = state.topLevelDestination == destination.route,
                        onClick = { selectDestination(destination.route) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(destination.label) },
                        railExpanded = railState.targetValue == WideNavigationRailValue.Expanded,
                        modifier = Modifier.testTag(destination.tag),
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f).imePadding(),
            topBar = {
                ShellTopBar(
                    state = state,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                    menuGroups = groups,
                )
            },
            bottomBar = {
                if (tier == WindowTier.Compact) {
                    ShortNavigationBar(modifier = Modifier.testTag("mainNavigationBar")) {
                        ShellDestinations.forEach { destination ->
                            ShortNavigationBarItem(
                                selected = state.topLevelDestination == destination.route,
                                onClick = { selectDestination(destination.route) },
                                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                                label = { Text(destination.label) },
                                modifier = Modifier.testTag(destination.tag),
                            )
                        }
                    }
                }
            },
            snackbarHost = {
                Column(
                    Modifier.padding(
                        start = AppDimens.contentHorizontalPadding(tier),
                        end = AppDimens.contentHorizontalPadding(tier),
                        bottom = miniReserve + selectionReserve,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                ) {
                    if (messages.progress.current != null) {
                        MessageHost(
                            messages.progress,
                            Modifier.testTag(if (messages.notice.current == null) "conversionFeedback" else "conversionProgressFeedback"),
                        )
                    }
                    if (messages.notice.current != null) {
                        MessageHost(messages.notice, Modifier.testTag("conversionFeedback"))
                    }
                    if (messages.stream.current != null) {
                        MessageHost(messages.stream, Modifier.testTag("streamTransientError"))
                    }
                }
            },
        ) { innerPadding ->
            val screenPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(direction),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(direction),
                bottom = innerPadding.calculateBottomPadding() + miniReserve + selectionReserve,
            )
            Box(
                Modifier.fillMaxSize().consumeWindowInsets(innerPadding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                AnimatedContent(
                    targetState = state.destination,
                    transitionSpec = {
                        val sign = if (targetState.order() >= initialState.order()) 1 else -1
                        (fadeIn(effectsSpec) + slideInHorizontally(spatialSpec) { sign * destinationShift })
                            .togetherWith(fadeOut(effectsSpec) + slideOutHorizontally(spatialSpec) { -sign * destinationShift })
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it.key() },
                    label = "appDestination",
                ) { destination ->
                    Box(Modifier.fillMaxSize().then(
                        if (destination != state.destination) Modifier.clearAndSetSemantics { } else Modifier,
                    )) {
                        savedScreens.SaveableStateProvider(destination.key()) {
                            val reselection = when (destination) {
                                Destination.Convert -> convertReselection
                                Destination.Library -> libraryReselection
                                Destination.Bot -> botReselection
                                Destination.Settings -> 0
                            }
                            val deliveredReselection = entryReselectionToken(reselection)
                            when (destination) {
                                Destination.Convert -> ConvertScreen(
                                    state, actions,
                                    modifier = Modifier.fillMaxSize().testTag("convertContent")
                                        .semantics { stateDescription = if (imeVisible) "IME visible" else "IME hidden" },
                                    contentPadding = screenPadding,
                                    reselectionToken = deliveredReselection,
                                )
                                Destination.Library -> LibraryScreen(
                                    state, actions, tier,
                                    contentPadding = screenPadding,
                                    reselectionToken = deliveredReselection,
                                    onScrollJump = onAppBarScrollJump,
                                )
                                Destination.Bot -> BotScreen(
                                    state, actions, tier,
                                    contentPadding = screenPadding,
                                    reselectionToken = deliveredReselection,
                                    onScrollJump = onAppBarScrollJump,
                                )
                                Destination.Settings -> {
                                    val settingsPages = rememberSaveableStateHolder()
                                    AnimatedContent(
                                        targetState = state.settingsPage,
                                        contentKey = { it.saveableKey() },
                                        transitionSpec = {
                                            val sign = if (targetState.depth() >= initialState.depth()) 1 else -1
                                            (fadeIn(effectsSpec) + slideInHorizontally(spatialSpec) { sign * destinationShift })
                                                .togetherWith(fadeOut(effectsSpec) + slideOutHorizontally(spatialSpec) { -sign * destinationShift })
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        label = "settingsPage",
                                    ) { page ->
                                        Box(Modifier.fillMaxSize().then(
                                            if (page != state.settingsPage) Modifier.clearAndSetSemantics { } else Modifier,
                                        )) {
                                            settingsPages.SaveableStateProvider(page.saveableKey()) {
                                                when (page) {
                                                    SettingsPage.Root -> SettingsScreen(
                                                        state, actions, tier, modifier = Modifier.fillMaxSize(), contentPadding = screenPadding,
                                                    )
                                                    SettingsPage.Licenses -> OpenSourceLicenseListScreen(
                                                        onOpenLicense = actions::onOpenLicense,
                                                        modifier = Modifier.fillMaxSize(), contentPadding = screenPadding,
                                                    )
                                                    is SettingsPage.LicenseDetail -> {
                                                        val entry = OpenSourceLicenses.find(page.id)
                                                        if (entry != null) OpenSourceLicenseDetailScreen(
                                                            entry = entry, onFeedback = controller::onHostFeedback,
                                                            modifier = Modifier.fillMaxSize(), contentPadding = screenPadding,
                                                        ) else OpenSourceLicenseListScreen(
                                                            onOpenLicense = actions::onOpenLicense,
                                                            modifier = Modifier.fillMaxSize(), contentPadding = screenPadding,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Kept outside destination composition so tab changes never recreate the player.
                PlayerHost(
                    state = state,
                    actions = actions,
                    tier = tier,
                    modifier = Modifier.fillMaxSize()
                        .padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = if (state.playerExpanded) innerPadding.calculateBottomPadding() else 0.dp,
                        ),
                    miniBottomPadding = innerPadding.calculateBottomPadding(),
                )
                if (state.selectionMode) {
                    SelectionToolbar(
                        selectedCount = state.selectedCount(),
                        allSelected = state.allVisibleSelected(),
                        onToggleAll = actions::onSelectAllCurrentItems,
                        onDelete = { state.requestSelectedDelete(actions) },
                        onClose = actions::onClearSelection,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(
                            bottom = innerPadding.calculateBottomPadding() + miniReserve + AppDimens.itemSpacing,
                        ),
                    )
                }
            }
        }
    }
    NavigationBackHandler(
        state = backState,
        isBackEnabled = imeVisible || menuExpanded || state.selectionMode ||
            (playerVisible && state.playerExpanded) || state.destination != Destination.Bot,
        onBackCompleted = {
            when {
                imeVisible -> { keyboard?.hide(); focusManager.clearFocus() }
                menuExpanded -> menuExpanded = false
                state.selectionMode -> actions.onClearSelection()
                state.destination == Destination.Bot && state.botSetupRequested -> actions.onCloseBotSetup()
                state.destination == Destination.Settings -> actions.onBack()
                playerVisible && state.playerExpanded -> actions.setPlayerExpanded(false)
                else -> actions.onBack()
            }
        },
    )
}

/** A saved consumed token prevents scrolling again when a saved destination is restored. */
@Composable
private fun entryReselectionToken(token: Int): Int {
    var consumed by rememberSaveable { mutableIntStateOf(0) }
    var delivered by remember { mutableIntStateOf(0) }
    LaunchedEffect(token) {
        if (token != consumed) { consumed = token; delivered = token }
    }
    return delivered
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShellTopBar(
    state: AppUiState,
    actions: AppActions,
    scrollBehavior: TopAppBarScrollBehavior,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuGroups: List<ShellMenuGroup>,
    modifier: Modifier = Modifier,
) {
    val subtitle: (@Composable () -> Unit)? = when {
        state.destination == Destination.Bot && (state.bot.connected || state.bot.paired) &&
            (!state.botSetupRequested || state.bot.connected) && !state.selectionMode ->
            { { BotInboxSubtitle(state.bot) } }
        state.destination == Destination.Convert && !state.selectionMode ->
            { { ConvertSubtitle() } }
        state.destination == Destination.Library && !state.selectionMode ->
            { { LibrarySubtitle(state.history.size) } }
        else -> null
    }
    ShellFlexibleTopAppBar(
        subtitle = subtitle,
        title = {
            Text(
                when {
                    state.selectionMode -> "${state.selectedCount()}개 선택"
                    state.destination == Destination.Settings -> when (val page = state.settingsPage) {
                        SettingsPage.Root -> "설정"
                        SettingsPage.Licenses -> "오픈소스 라이선스"
                        is SettingsPage.LicenseDetail -> OpenSourceLicenses.find(page.id)?.name ?: "오픈소스 라이선스"
                    }
                    state.destination == Destination.Library -> "보관함"
                    state.destination == Destination.Bot -> "봇"
                    else -> "변환"
                },
                modifier = when {
                    state.selectionMode -> Modifier.testTag("selectionCount")
                    state.destination == Destination.Library -> Modifier.testTag("libraryTitle")
                    else -> Modifier
                },
            )
        },
        modifier = modifier,
        navigationIcon = {
            when {
                state.selectionMode -> ShellTooltip("선택 종료") {
                    IconButton(
                        onClick = actions::onClearSelection,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("exitSelection"),
                    ) { Icon(painterResource(R.drawable.ic_close), contentDescription = "선택 종료") }
                }
                state.destination == Destination.Settings -> ShellTooltip("이전 화면으로") {
                    IconButton(
                        onClick = { actions.onBack() },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("settingsTopBack"),
                    ) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "이전 화면으로") }
                }
            }
        },
        actions = {
            if (!state.selectionMode && menuGroups.isNotEmpty()) {
                Box {
                    ShellTooltip("탭 메뉴") {
                        IconButton(
                            onClick = { onMenuExpandedChange(true) },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.testTag("tabMenu"),
                        ) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "탭 메뉴") }
                    }
                    DropdownMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                        modifier = Modifier.testTag("tabMenuDropdown"),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(MenuDefaults.GroupSpacing)) {
                            menuGroups.forEachIndexed { groupIndex, group ->
                                DropdownMenuGroup(
                                    shapes = MenuDefaults.groupShape(groupIndex, menuGroups.size),
                                    modifier = Modifier.testTag(group.tag),
                                ) {
                                    group.items.forEachIndexed { itemIndex, item ->
                                        DropdownMenuItem(
                                            onClick = { onMenuExpandedChange(false); item.onClick() },
                                            text = { Text(item.label) },
                                            shape = MenuDefaults.itemShape(itemIndex, group.items.size).shape,
                                            leadingIcon = { Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.testTag(item.iconTag)) },
                                            enabled = item.enabled,
                                            modifier = Modifier.testTag(item.tag),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun ConvertSubtitle() {
    Text(
        "기기에 있는 영상에서 오디오를 추출해 보관함에 저장해요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("convertSubtitle"),
    )
}

@Composable
private fun LibrarySubtitle(count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing / 2)) {
        Text(
            "저장한 오디오 · ${count}개",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("librarySubtitle"),
        )
        Text(
            "저장한 오디오를 듣고 관리해요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Public expanded slots keep the inbox description out of the collapsed app-bar row. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShellFlexibleTopAppBar(
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)?,
    modifier: Modifier,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    if (subtitle == null) {
        LargeFlexibleTopAppBar(
            title = title, modifier = modifier, navigationIcon = navigationIcon,
            actions = actions, scrollBehavior = scrollBehavior,
        )
    } else {
        TwoRowsTopAppBar(
            title = { expanded ->
                ProvideTextStyle(if (expanded) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge) {
                    title()
                }
            },
            subtitle = { expanded ->
                if (expanded) ProvideTextStyle(MaterialTheme.typography.titleMedium) { subtitle() }
            },
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
            expandedHeight = TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight,
            scrollBehavior = scrollBehavior,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellTooltip(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

private data class ShellDestination(val route: Destination, val label: String, @param:DrawableRes val icon: Int, val tag: String)
private val ShellDestinations = listOf(
    ShellDestination(Destination.Bot, "봇", R.drawable.ic_smart_toy, "botTab"),
    ShellDestination(Destination.Convert, "변환", R.drawable.ic_menu_audio_format, "convertTab"),
    ShellDestination(Destination.Library, "보관함", R.drawable.ic_library_list, "libraryTab"),
)
private data class ShellMenuItem(
    val label: String,
    @param:DrawableRes val icon: Int,
    val tag: String,
    val iconTag: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)
private data class ShellMenuGroup(val tag: String, val items: List<ShellMenuItem>)

private fun shellMenuGroups(state: AppUiState, actions: AppActions, diagnosticsAvailable: Boolean): List<ShellMenuGroup> {
    if (state.selectionMode || state.destination == Destination.Settings) return emptyList()
    return buildList {
        when (state.destination) {
            Destination.Library -> {
                add(ShellMenuGroup("menuSection_library", listOf(ShellMenuItem(
                    "항목 선택", R.drawable.ic_menu_select, "menuSelectItems", "menuIcon_selectItems",
                    enabled = state.visibleHistory.isNotEmpty(), onClick = actions::onBeginSelection,
                ))))
            }
            Destination.Bot -> {
                add(ShellMenuGroup("menuSection_bot", listOf(
                    ShellMenuItem("새로고침", R.drawable.ic_menu_refresh, "menuRefreshBot", "menuIcon_refresh",
                        enabled = !state.bot.refreshing,
                        onClick = actions::onRefreshBot),
                )))
                add(ShellMenuGroup("menuSection_botList", listOf(ShellMenuItem(
                    "항목 선택", R.drawable.ic_menu_select, "menuSelectItems", "menuIcon_selectItems",
                    enabled = state.visibleBotVideos.isNotEmpty(), onClick = actions::onBeginSelection,
                ))))
            }
            else -> Unit
        }
        add(ShellMenuGroup("menuSection_settings", listOf(ShellMenuItem(
            "설정", R.drawable.ic_menu_settings, "menuSettings", "menuIcon_settings",
            onClick = actions::onOpenSettings,
        ))))
        if (diagnosticsAvailable) add(ShellMenuGroup("menuSection_diagnostics", listOf(ShellMenuItem(
            "진단 로그 저장", R.drawable.ic_media_save, "menuDiagnosticLogs", "menuIcon_diagnostics",
            onClick = actions::onExportDiagnostics,
        ))))
    }
}

private class ShellMessages {
    val notice = MessageController()
    val stream = MessageController()
    val progress = MessageController()
}

@Composable
private fun rememberShellMessages(controller: AppController, state: AppUiState): ShellMessages {
    val messages = remember(controller) { ShellMessages() }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.messages.collect { message ->
                val snapshot = controller.uiState.value
                // Domain snapshots below also cover errors present before the SharedFlow collector attached.
                val streamError = snapshot.stream.safeError()
                val botError = UserFacingErrors.fromErrorString(snapshot.bot.error, UserFacingErrors.Context.TELEGRAM)
                val conversionError = UserFacingErrors.fromErrorString(snapshot.conversion.error)
                when {
                    message.isError && message.text == streamError -> Unit
                    message.isError && message.text == botError -> Unit // BotInlineStatus owns botTransientError.
                    message.isError && message.text == conversionError -> Unit
                    message.text == BOT_SAVE_PREPARATION_TEXT && snapshot.preparingBotSaveMessageIds.isNotEmpty() -> Unit
                    else -> messages.notice.show(message.text, message.persistent)
                }
            }
        }
    }
    LaunchedEffect(state.stream.error, state.stream.localAudioPath, state.stream.botFileId) {
        state.stream.safeError()?.let { messages.stream.show(it) } ?: messages.stream.dismiss()
    }
    LaunchedEffect(state.conversion.error) {
        UserFacingErrors.fromErrorString(state.conversion.error)?.let { messages.notice.show(it) }
    }
    LaunchedEffect(state.conversion.result?.path, state.conversion.stage == "취소했어요", state.conversion.running) {
        if (!state.conversion.running && state.conversion.error == null) {
            when {
                state.conversion.result != null -> messages.notice.show(CONVERSION_COMPLETE_TEXT)
                state.conversion.stage == "취소했어요" -> messages.notice.show(state.conversion.stage)
            }
        }
    }
    LaunchedEffect(state.conversion.running, state.conversion.stage, state.preparingBotSaveMessageIds) {
        val jobActive = state.conversion.running || state.preparingBotSaveMessageIds.isNotEmpty()
        val text = when {
            state.preparingBotSaveMessageIds.isNotEmpty() -> BOT_SAVE_PREPARATION_TEXT
            state.conversion.stage.isNotBlank() -> state.conversion.stage
            else -> BOT_SAVE_PREPARATION_TEXT
        }
        messages.progress.updateProgress(jobActive, text)
    }
    return messages
}

private fun StreamingState.safeError(): String? = UserFacingErrors.fromErrorString(error, when {
    source == PlaybackSource.LOCAL_AUDIO -> UserFacingErrors.Context.LOCAL_MEDIA
    source == PlaybackSource.BOT -> UserFacingErrors.Context.TELEGRAM
    else -> UserFacingErrors.Context.GENERAL
})

private fun StreamingState.isPlayerVisible(): Boolean =
    source != PlaybackSource.NONE &&
        (resolving || ready || error != null || playing || buffering || localAudioPath != null)

private fun SettingsPage.saveableKey(): String = when (this) {
    SettingsPage.Root -> "settings-root"
    SettingsPage.Licenses -> "settings-licenses"
    is SettingsPage.LicenseDetail -> "settings-license-$id"
}

private fun SettingsPage.depth(): Int = when (this) {
    SettingsPage.Root -> 0
    SettingsPage.Licenses -> 1
    is SettingsPage.LicenseDetail -> 2
}

private fun Destination.key(): String = when (this) {
    Destination.Convert -> "convert"
    Destination.Library -> "library"
    Destination.Bot -> "bot"
    Destination.Settings -> "settings"
}
private fun Destination.order(): Int = when (this) {
    Destination.Bot -> 0
    Destination.Convert -> 1
    Destination.Library -> 2
    Destination.Settings -> 4
}
private fun AppUiState.selectedCount(): Int = when (topLevelDestination) {
    Destination.Library -> visibleHistory.count { it.path in selectedAudioPaths }
    Destination.Bot -> visibleBotVideos.count { it.messageId in selectedBotMessageIds }
    else -> 0
}
private fun AppUiState.allVisibleSelected(): Boolean = when (topLevelDestination) {
    Destination.Library -> visibleHistory.let { visible -> visible.isNotEmpty() && visible.all { it.path in selectedAudioPaths } }
    Destination.Bot -> visibleBotVideos.let { visible -> visible.isNotEmpty() && visible.all { it.messageId in selectedBotMessageIds } }
    else -> false
}
private fun AppUiState.requestSelectedDelete(actions: AppActions) {
    when (topLevelDestination) {
        Destination.Library -> actions.onRequestLibraryDelete(visibleHistory.filter { it.path in selectedAudioPaths })
        Destination.Bot -> actions.onRequestBotDelete(visibleBotVideos.filter { it.messageId in selectedBotMessageIds }.mapTo(mutableSetOf()) { it.messageId })
        else -> Unit
    }
}
