package io.github.polarfox0317.sikboonge.ui.welcome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ThemeMode
import io.github.polarfox0317.sikboonge.ThemeModeStore
import io.github.polarfox0317.sikboonge.ui.bot.BotApiHashField
import io.github.polarfox0317.sikboonge.ui.bot.BotApiIdField
import io.github.polarfox0317.sikboonge.ui.bot.BotPairButton
import io.github.polarfox0317.sikboonge.ui.bot.BotStatus
import io.github.polarfox0317.sikboonge.ui.bot.BotTokenField
import io.github.polarfox0317.sikboonge.ui.bot.botCanConnect
import io.github.polarfox0317.sikboonge.ui.bot.connectBot
import io.github.polarfox0317.sikboonge.ui.components.ThemeModeSelector
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.rememberWindowTier
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.unit.lerp as lerpDp

/** Stable step identity; the visited stack stores these names. */
private enum class WelcomeStep {
    Welcome,
    Features,
    Theme,
    Notifications,
    BotChoice,
    BotCreate,
    BotApi,
    BotPair,
    Done,
}

/** Depth tells AnimatedContent whether the move was forward or back. */
private data class StackEntry(val depth: Int, val step: WelcomeStep)

private val WelcomeStackSaver = Saver<MutableState<List<WelcomeStep>>, ArrayList<String>>(
    save = { state -> ArrayList(state.value.map { it.name }) },
    restore = { saved -> mutableStateOf(saved.map(WelcomeStep::valueOf)) },
)

private val IntroIconStartSize = 192.dp
private val IntroIconFinalSize = 128.dp
private val HeaderIconSize = 56.dp
private val GroupGap = 24.dp
private val PageEdgeSpace = 24.dp
private val TextIntroOffset = 24.dp
// Adaptive icon foreground canvas; the visible circle is 72dp of it (108/72 = 1.5).
private val AdaptiveIconCanvas = 108.dp
private const val AdaptiveForegroundRatio = 1.5f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WelcomeGuide(
    state: AppUiState,
    actions: AppActions,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val baseSteps = remember {
        buildList {
            add(WelcomeStep.Welcome)
            add(WelcomeStep.Features)
            add(WelcomeStep.Theme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(WelcomeStep.Notifications)
            add(WelcomeStep.BotChoice)
            add(WelcomeStep.Done)
        }
    }
    var stack by rememberSaveable(saver = WelcomeStackSaver) { mutableStateOf(listOf(WelcomeStep.Welcome)) }
    val current = stack.last()
    val path = remember(baseSteps) {
        baseSteps.flatMap { step ->
            if (step == WelcomeStep.BotChoice) {
                listOf(WelcomeStep.BotChoice, WelcomeStep.BotCreate, WelcomeStep.BotApi, WelcomeStep.BotPair)
            } else {
                listOf(step)
            }
        }
    }

    val push: (WelcomeStep) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    val goNext: () -> Unit = {
        when (val step = stack.last()) {
            WelcomeStep.BotChoice -> when {
                state.bot.paired -> push(WelcomeStep.Done)
                state.bot.connected -> push(WelcomeStep.BotPair)
                else -> push(WelcomeStep.BotCreate)
            }
            WelcomeStep.BotCreate -> push(WelcomeStep.BotApi)
            WelcomeStep.BotApi -> push(WelcomeStep.BotPair)
            WelcomeStep.BotPair -> push(WelcomeStep.Done)
            WelcomeStep.Done -> onFinished()
            else -> push(baseSteps[baseSteps.indexOf(step) + 1])
        }
    }

    var notificationGranted by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    var notificationDeniedInGuide by rememberSaveable { mutableStateOf(false) }
    val themeMode by ThemeModeStore.mode.collectAsStateWithLifecycle()
    val horizontalPadding = AppDimens.contentHorizontalPadding(rememberWindowTier())
    // Progress bar disabled by root decision
    // val pathIndexForProgress = path.indexOf(current).coerceAtLeast(0)
    // val progress by animateFloatAsState(
    //     targetValue = (pathIndexForProgress + 1f) / path.size,
    //     animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    //     label = "welcomeProgress",
    // )
    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted = isNotificationPermissionGranted(context)
                if (notificationGranted) notificationDeniedInGuide = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // D2 -> D3 when the connection becomes true while D2 is shown.
    val connectedNow by rememberUpdatedState(state.bot.connected)
    LaunchedEffect(current) {
        if (current != WelcomeStep.BotApi) return@LaunchedEffect
        snapshotFlow { connectedNow }.drop(1).filter { it }.first()
        push(WelcomeStep.BotPair)
    }

    // Intro: plays once per guide instance, the first time step 0 is shown.
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    val animationsOff = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val iconProgress = remember { Animatable(if (introPlayed) 1f else 0f) }
    val textAlpha = remember { Animatable(if (introPlayed) 1f else 0f) }
    val textOffsetDp = remember { Animatable(if (introPlayed) 0f else TextIntroOffset.value) }
    val footerAlpha = remember { Animatable(if (introPlayed) 1f else 0f) }
    var slotCenter by remember { mutableStateOf<Offset?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val slowSpatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val defaultSpatialFloatSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val defaultEffectsFloatSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(Unit) {
        if (introPlayed) return@LaunchedEffect
        if (animationsOff) {
            iconProgress.snapTo(1f)
            textAlpha.snapTo(1f)
            textOffsetDp.snapTo(0f)
            footerAlpha.snapTo(1f)
            introPlayed = true
            return@LaunchedEffect
        }
        delay(300L)
        snapshotFlow { slotCenter }.filterNotNull().first()
        coroutineScope {
            val iconJob = launch { iconProgress.animateTo(1f, slowSpatialSpec) }
            delay(120L)
            val alphaJob = launch { textAlpha.animateTo(1f, defaultEffectsFloatSpec) }
            val offsetJob = launch { textOffsetDp.animateTo(0f, defaultSpatialFloatSpec) }
            alphaJob.join()
            offsetJob.join()
            footerAlpha.animateTo(1f, defaultEffectsFloatSpec)
            iconJob.join()
        }
        introPlayed = true
    }

    NavigationBackHandler(
        state = backState,
        isBackEnabled = stack.size > 1,
        onBackCompleted = { pop() },
    )

    Surface(
        modifier = modifier.fillMaxSize().testTag("welcomeGuide"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                overlayOrigin = coordinates.positionInRoot()
                overlaySize = coordinates.size
            },
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Progress bar disabled by root decision
                // LinearWavyProgressIndicator(
                //     progress = { progress },
                //     modifier = Modifier.fillMaxWidth()
                //         .padding(horizontal = horizontalPadding)
                //         .padding(top = 16.dp)
                //         // Hidden (space kept) on step 0; visible from step 1.
                //         .graphicsLayer { alpha = if (current == WelcomeStep.Welcome) 0f else 1f }
                //         .semantics {
                //             stateDescription = "단계 ${pathIndexForProgress + 1}/${path.size}"
                //         },
                // )

                val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                val destinationShift = with(LocalDensity.current) { AppDimens.itemSpacing.roundToPx() }
                AnimatedContent(
                    targetState = StackEntry(stack.size, current),
                    transitionSpec = {
                        val sign = if (targetState.depth >= initialState.depth) 1 else -1
                        (fadeIn(effectsSpec) + slideInHorizontally(spatialSpec) { sign * destinationShift })
                            .togetherWith(fadeOut(effectsSpec) + slideOutHorizontally(spatialSpec) { -sign * destinationShift })
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "welcomeStep",
                ) { entry ->
                    val step = entry.step
                    val introTextModifier = if (step == WelcomeStep.Welcome) {
                        Modifier.graphicsLayer {
                            alpha = textAlpha.value
                            translationY = textOffsetDp.value.dp.toPx()
                        }
                    } else {
                        Modifier
                    }
                    WelcomePageFrame(
                        horizontalPadding = horizontalPadding,
                        modifier = Modifier.testTag("welcomeStep_${path.indexOf(step).coerceAtLeast(0)}"),
                        hero = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                when (step) {
                                    WelcomeStep.Welcome -> {
                                        Box(
                                            Modifier.size(IntroIconFinalSize).onGloballyPositioned { coordinates ->
                                                val position = coordinates.positionInRoot()
                                                slotCenter = Offset(
                                                    position.x + coordinates.size.width / 2f,
                                                    position.y + coordinates.size.height / 2f,
                                                )
                                            },
                                        ) {
                                            if (introPlayed) AppIcon(IntroIconFinalSize)
                                        }
                                        Spacer(Modifier.height(24.dp))
                                    }
                                    WelcomeStep.Done -> {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check_circle),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(HeaderIconSize),
                                        )
                                        Spacer(Modifier.height(24.dp))
                                    }
                                    else -> Unit
                                }
                                Text(
                                    text = step.headline(),
                                    modifier = Modifier.fillMaxWidth().then(introTextModifier),
                                    style = MaterialTheme.typography.headlineLargeEmphasized,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        },
                        details = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val body = step.body()
                                if (body != null) {
                                    Text(
                                        text = body,
                                        modifier = Modifier.fillMaxWidth().then(introTextModifier),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                if (step.hasContent()) {
                                    if (body != null) Spacer(Modifier.height(32.dp))
                                    StepContent(
                                        step = step,
                                        state = state,
                                        actions = actions,
                                        onBotConnect = goNext,
                                        onBotLater = { push(WelcomeStep.Done) },
                                        themeSelected = themeMode,
                                        onThemeSelected = { ThemeModeStore.set(context, it) },
                                        notificationGranted = notificationGranted,
                                        notificationDeniedInGuide = notificationDeniedInGuide,
                                        onRequestNotification = {
                                            actions.onRequestNotificationPermissionThen { granted ->
                                                notificationGranted = granted || isNotificationPermissionGranted(context)
                                                notificationDeniedInGuide = !notificationGranted
                                            }
                                        },
                                        onTokenDone = { if (state.botToken.isNotBlank()) goNext() },
                                    )
                                }
                            }
                        },
                    )
                }

                WelcomeFooter(
                    current = current,
                    isFirst = stack.size == 1,
                    state = state,
                    horizontalPadding = horizontalPadding,
                    interactive = introPlayed,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .graphicsLayer { alpha = footerAlpha.value },
                    onSkip = onFinished,
                    onBack = pop,
                    onPrimary = {
                        if (current == WelcomeStep.BotApi && !state.bot.connected) connectBot(state, actions) else goNext()
                    },
                )
            }

            if (!introPlayed && overlaySize != IntSize.Zero) {
                val windowCenter = Offset(overlaySize.width / 2f, overlaySize.height / 2f)
                val target = slotCenter?.let { it - overlayOrigin } ?: windowCenter
                val fraction = iconProgress.value
                val center = lerpOffset(windowCenter, target, fraction)
                val iconSize = lerpDp(IntroIconStartSize, IntroIconFinalSize, fraction)
                AppIcon(
                    diameter = iconSize,
                    modifier = Modifier.offset {
                        val half = iconSize.toPx() / 2f
                        IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt())
                    },
                )
            }
        }
    }
}

/** Scrollable page whose hero ends 12dp above the viewport center and details start 12dp below it. */
@Composable
private fun WelcomePageFrame(
    horizontalPadding: Dp,
    hero: @Composable () -> Unit,
    details: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            CenterAnchoredPage(
                viewportHeight = viewportHeight,
                hero = hero,
                details = details,
                modifier = Modifier.widthIn(max = AppDimens.maxFormWidth)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .then(modifier),
            )
        }
    }
}

@Composable
private fun CenterAnchoredPage(
    viewportHeight: Dp,
    hero: @Composable () -> Unit,
    details: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(hero, details), modifier = modifier) { (heroMeasurables, detailsMeasurables), constraints ->
        val childConstraints = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
        val heroPlaceables = heroMeasurables.map { it.measure(childConstraints) }
        val detailsPlaceables = detailsMeasurables.map { it.measure(childConstraints) }
        val heroHeight = heroPlaceables.sumOf { it.height }
        val detailsHeight = detailsPlaceables.sumOf { it.height }
        val viewport = viewportHeight.roundToPx()
        val edge = PageEdgeSpace.roundToPx()
        val gap = GroupGap.roundToPx()
        val groupHeight = heroHeight + gap + detailsHeight
        val heroTop = max(edge, (viewport - groupHeight) / 2)
        val detailsTop = heroTop + heroHeight + gap
        val totalHeight = max(viewport, heroTop + groupHeight + edge)
        layout(constraints.maxWidth, totalHeight) {
            var y = heroTop
            heroPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            y = detailsTop
            detailsPlaceables.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepContent(
    step: WelcomeStep,
    state: AppUiState,
    actions: AppActions,
    onBotConnect: () -> Unit,
    onBotLater: () -> Unit,
    themeSelected: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    notificationGranted: Boolean,
    notificationDeniedInGuide: Boolean,
    onRequestNotification: () -> Unit,
    onTokenDone: () -> Unit,
) {
    when (step) {
        WelcomeStep.Features -> FeatureList()
        WelcomeStep.Theme -> ThemeModeSelector(
            selected = themeSelected,
            onSelect = onThemeSelected,
            modifier = Modifier.fillMaxWidth(),
        )
        WelcomeStep.Notifications -> when {
            notificationGranted -> Text(
                "알림이 허용됐어요.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            notificationDeniedInGuide -> Text(
                "알림이 꺼져 있어요. 나중에 기기 설정에서 켤 수 있어요.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            else -> MediumTonalButton("알림 허용", onClick = onRequestNotification)
        }
        WelcomeStep.BotChoice -> Column(Modifier.fillMaxWidth()) {
            Button(
                onClick = onBotConnect,
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = ButtonDefaults.MediumContainerHeight)
                    .testTag("welcomeBotConnect"),
            ) {
                Text("연결하기", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onBotLater,
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = ButtonDefaults.MediumContainerHeight)
                    .testTag("welcomeBotLater"),
            ) {
                Text("나중에", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
        WelcomeStep.BotCreate -> Column(Modifier.fillMaxWidth()) {
            MediumTonalButton(
                "Telegram BotFather 열기",
                onClick = { actions.onOpenExternalUrl("https://t.me/BotFather") },
            )
            Spacer(Modifier.height(16.dp))
            BotTokenField(
                state = state,
                actions = actions,
                imeAction = ImeAction.Done,
                onImeAction = onTokenDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        WelcomeStep.BotApi -> {
            val apiHashFocus = remember { FocusRequester() }
            Column(Modifier.fillMaxWidth()) {
                MediumTonalButton(
                    "Telegram API 앱 페이지 열기",
                    onClick = { actions.onOpenExternalUrl("https://my.telegram.org/apps") },
                )
                Spacer(Modifier.height(16.dp))
                BotApiIdField(
                    state = state,
                    actions = actions,
                    imeAction = ImeAction.Next,
                    onImeAction = { apiHashFocus.requestFocus() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                BotApiHashField(
                    state = state,
                    actions = actions,
                    imeAction = ImeAction.Done,
                    onImeAction = { if (botCanConnect(state)) connectBot(state, actions) },
                    modifier = Modifier.fillMaxWidth().focusRequester(apiHashFocus),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "token과 api_hash는 비밀번호처럼 취급하세요. 이 기기의 암호화 저장소에 보관돼요.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    BotStatus(state.bot)
                }
            }
        }
        WelcomeStep.BotPair -> if (!state.bot.paired) {
            BotPairButton(state.bot, actions, Modifier.fillMaxWidth())
        } else {
            Text(
                "연결이 끝났어요.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediumTonalButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
    ) {
        Text(label, style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WelcomeFooter(
    current: WelcomeStep,
    isFirst: Boolean,
    state: AppUiState,
    horizontalPadding: Dp,
    interactive: Boolean,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // On D2, an existing connection turns the primary action into "다음" (always enabled).
    val connectAction = current == WelcomeStep.BotApi && !state.bot.connected
    // D0 has its own actions; the primary button stays in layout (invisible) to keep the row height.
    val primaryVisible = current != WelcomeStep.BotChoice
    val primaryEnabled = when (current) {
        WelcomeStep.BotCreate -> state.botToken.isNotBlank()
        WelcomeStep.BotApi -> if (connectAction) botCanConnect(state) else true
        else -> true
    }
    Row(
        modifier = modifier.widthIn(max = AppDimens.maxFormWidth)
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 16.dp)
            .then(if (interactive) Modifier else Modifier.blockInput().clearAndSetSemantics { }),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { if (isFirst) onSkip() else onBack() },
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
                .testTag(if (isFirst) "welcomeSkip" else "welcomeBack"),
        ) {
            Text(
                if (isFirst) "건너뛰기" else "이전",
                style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
            )
        }
        Button(
            onClick = onPrimary,
            enabled = primaryVisible && primaryEnabled,
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
                .then(
                    if (primaryVisible) {
                        Modifier.testTag("welcomeNext")
                    } else {
                        Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { }
                    },
                ),
        ) {
            if (connectAction && state.bot.busy) {
                LoadingIndicator(Modifier.size(LoadingIndicatorDefaults.IndicatorSize))
            } else {
                Text(
                    when {
                        current == WelcomeStep.Done -> "시작하기"
                        connectAction -> "연결"
                        else -> "다음"
                    },
                    style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                )
            }
        }
    }
}

/** Consumes pointer input so the hidden footer cannot be tapped during the intro. */
private fun Modifier.blockInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The launcher icon as the system splash draws it: an icon_background circle with the adaptive
 * foreground scaled from its 108dp canvas to 1.5x the diameter. The foreground resource is an
 * inset drawable, which painterResource cannot load, so it is drawn through the platform canvas.
 */
@Composable
private fun AppIcon(diameter: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val foreground = remember(context) {
        requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground_safe))
    }
    val background = colorResource(R.color.icon_background)
    Canvas(modifier.size(diameter).clip(CircleShape)) {
        drawRect(background)
        val foregroundSize = size.minDimension * AdaptiveForegroundRatio
        val canvasPx = AdaptiveIconCanvas.toPx()
        val scale = foregroundSize / canvasPx
        val left = (size.width - foregroundSize) / 2f
        val top = (size.height - foregroundSize) / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val checkpoint = native.save()
            native.translate(left, top)
            native.scale(scale, scale)
            foreground.setBounds(0, 0, canvasPx.roundToInt(), canvasPx.roundToInt())
            foreground.draw(native)
            native.restoreToCount(checkpoint)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeatureList() {
    val features = remember {
        listOf(
            Triple("봇", "Telegram 봇에 보낸 영상을 받아 오디오로 저장해요.", R.drawable.ic_smart_toy),
            Triple("변환", "기기에 있는 영상에서 오디오를 추출해요.", R.drawable.ic_menu_audio_format),
            Triple("보관함", "저장한 오디오를 듣고 관리해요.", R.drawable.ic_library_list),
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        features.forEachIndexed { index, (title, supporting, icon) ->
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, features.size),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth(),
                content = { Text(title) },
                supportingContent = { Text(supporting) },
                leadingContent = {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
        }
    }
}

private fun WelcomeStep.headline(): String = when (this) {
    WelcomeStep.Welcome -> "싴붕이에 오신 걸 환영해요"
    WelcomeStep.Features -> "이렇게 쓸 수 있어요"
    WelcomeStep.Theme -> "화면 모드를 골라 주세요"
    WelcomeStep.Notifications -> "알림을 허용해 주세요"
    WelcomeStep.BotChoice -> "Telegram 봇을 연결할까요?"
    WelcomeStep.BotCreate -> "BotFather에서 봇을 만들어 주세요"
    WelcomeStep.BotApi -> "API 정보를 입력해 주세요"
    WelcomeStep.BotPair -> "내 Telegram 계정을 연결해 주세요"
    WelcomeStep.Done -> "준비가 끝났어요"
}

private fun WelcomeStep.body(): String? = when (this) {
    WelcomeStep.Welcome -> "영상에서 오디오를 뽑아 저장하고 바로 들을 수 있어요. 몇 가지만 정하면 시작할 수 있어요."
    WelcomeStep.Features -> null
    WelcomeStep.Theme -> "언제든 설정에서 바꿀 수 있어요."
    WelcomeStep.Notifications -> "저장과 변환이 백그라운드에서 진행될 때 진행 상황과 완료를 알림으로 알려 드려요."
    WelcomeStep.BotChoice -> "봇 탭에서 API ID, API Hash, 봇 토큰을 입력하면 봇에 보낸 영상을 받아 저장할 수 있어요. 지금 연결하지 않아도 변환과 보관함은 바로 쓸 수 있어요."
    WelcomeStep.BotCreate -> "Telegram에서 @BotFather를 열고 /newbot을 보내세요. 안내에 따라 봇 이름과 고유한 username을 정한 뒤, 발급된 token을 복사하세요."
    WelcomeStep.BotApi -> "my.telegram.org에 로그인해 API development tools에서 앱을 등록하고 api_id와 api_hash를 확인하세요."
    WelcomeStep.BotPair -> "내 Telegram 계정에 연결하려면 봇과의 비공개 대화를 시작해 주세요."
    WelcomeStep.Done -> "화면 모드와 저장 방식은 설정에서 바꿀 수 있어요."
}

private fun WelcomeStep.hasContent(): Boolean = when (this) {
    WelcomeStep.Welcome, WelcomeStep.Done -> false
    else -> true
}

private fun isNotificationPermissionGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
