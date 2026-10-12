package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EdgeHoldOutlinedTextField(
    state: TextFieldState,
    label: @Composable () -> Unit,
    supportingText: @Composable () -> Unit,
    lineLimits: TextFieldLineLimits,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val decorator = OutlinedTextFieldDefaults.decorator(
        state = state,
        enabled = true,
        lineLimits = lineLimits,
        outputTransformation = null,
        interactionSource = interactions,
        label = { label() },
        supportingText = supportingText,
        colors = colors,
    )
    EdgeHoldTextField(
        state = state,
        interactionSource = interactions,
        decorator = decorator,
        textStyle = LocalTextStyle.current.merge(TextStyle(color = if (focused) colors.focusedTextColor else colors.unfocusedTextColor)),
        cursorColor = colors.cursorColor,
        keyboardOptions = keyboardOptions,
        lineLimits = lineLimits,
        // Attached outlined labels have the same 8dp top space as OutlinedTextField.
        modifier = modifier.padding(top = 8.dp).defaultMinSize(
            minWidth = OutlinedTextFieldDefaults.MinWidth,
            minHeight = OutlinedTextFieldDefaults.MinHeight,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EdgeHoldSearchInputField(
    state: TextFieldState,
    onSearch: () -> Unit,
    placeholder: @Composable () -> Unit,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = SearchBarDefaults.inputFieldColors()
    // SearchBar already owns the container; InputField's public Material decorator
    // uses the same empty container and content padding.
    val decorator = TextFieldDefaults.decorator(
        state = state,
        enabled = true,
        lineLimits = TextFieldLineLimits.SingleLine,
        outputTransformation = null,
        interactionSource = interactions,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = colors,
        contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(),
        container = {},
    )
    EdgeHoldTextField(
        state = state,
        interactionSource = interactions,
        decorator = decorator,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (focused) colors.focusedTextColor else colors.unfocusedTextColor),
        cursorColor = colors.cursorColor,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = KeyboardActionHandler { onSearch() },
        modifier = modifier.defaultMinSize(minHeight = SearchBarDefaults.InputFieldHeight),
    )
}

/**
 * The state-based editor keeps IME composition and normal range selection in Foundation.
 * For a single line we own only its collapsed cursor handle. Foundation's handle drag
 * scrolls on pointer updates; this handle also scrolls on frames while the pointer is still.
 * Material's public decorator supplies the existing field geometry and decoration.
 */
@Composable
internal fun EdgeHoldTextField(
    state: TextFieldState,
    interactionSource: MutableInteractionSource,
    decorator: TextFieldDecorator,
    textStyle: TextStyle,
    cursorColor: Color,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    onKeyboardAction: KeyboardActionHandler? = null,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val singleLine = lineLimits == TextFieldLineLimits.SingleLine
    val selectionColors = LocalTextSelectionColors.current
    val tracker = remember(state) { CursorHandleDragTracker() }
    var focused by remember { mutableStateOf(false) }
    var showHandle by remember(state) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var layoutProvider by remember { mutableStateOf<() -> TextLayoutResult?>({ null }) }
    val layout = layoutProvider()
    val collapsed = state.selection.collapsed
    // These are interaction choices for this app, not Material specification defaults.
    val edgeWidth = with(density) { 24.dp.toPx() }
    val maxSpeed = with(density) { 480.dp.toPx() }
    val cursorInset = with(density) { 2.dp.toPx() }

    DisposableEffect(tracker) {
        onDispose {
            tracker.stop()
            viewport = null
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect {
            // Typing dismisses the handle, as in the standard editor. A tap restores it.
            showHandle = false
            tracker.stop()
        }
    }
    LaunchedEffect(tracker.dragging, focused, collapsed, singleLine, rtl, edgeWidth, maxSpeed, cursorInset) {
        if (!tracker.dragging || !focused || !collapsed || !singleLine) {
            tracker.stop()
            return@LaunchedEffect
        }
        // Hold ownership for the whole gesture; there is no delay waiting for more motion.
        scrollState.scroll(MutatePriority.UserInput) {
            var previousFrame = withFrameNanos { it }
            while (tracker.dragging && focused) {
                val frame = withFrameNanos { it }
                val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previousFrame = frame
                val coordinates = viewport
                val textLayout = layoutProvider()
                val anchorWindow = tracker.anchorWindow
                if (coordinates == null || !coordinates.isAttached || textLayout == null || anchorWindow == null) {
                    tracker.stop()
                    break
                }
                val viewportOrigin = coordinates.positionInWindow()
                val x = anchorWindow.x - viewportOrigin.x
                val width = coordinates.size.width.toFloat()
                val velocity = CursorEdgeScrollPolicy.velocity(x, width, edgeWidth, maxSpeed, rtl)
                scrollBy(velocity * seconds)
                // Re-map every frame AFTER scrolling, even when the finger has not moved.
                val contentLeft = CursorEdgeScrollPolicy.contentLeft(scrollState.value, scrollState.maxValue, rtl)
                val textPosition = Offset(
                    // Leave cursor thickness inside the viewport so Foundation's
                    // caret-follow scroll does not compete with the owned frame loop.
                    x.coerceIn(0f, (width - cursorInset).coerceAtLeast(0f)) + contentLeft,
                    (anchorWindow.y - viewportOrigin.y).coerceIn(0f, coordinates.size.height.toFloat()),
                )
                val offset = textLayout.getOffsetForPosition(textPosition).coerceIn(0, state.text.length)
                if (state.selection != TextRange(offset)) {
                    state.edit { selection = TextRange(offset) }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    val coreSelectionColors = if (singleLine && collapsed) {
        TextSelectionColors(Color.Transparent, selectionColors.backgroundColor)
    } else selectionColors
    CompositionLocalProvider(LocalTextSelectionColors provides coreSelectionColors) {
        BasicTextField(
            state = state,
            modifier = modifier
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) {
                        showHandle = false
                        tracker.stop()
                    }
                }
                .pointerInput(state, singleLine) {
                    if (singleLine) awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        if (focused) showHandle = true
                    }
                },
            textStyle = textStyle,
            cursorBrush = SolidColor(cursorColor),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            interactionSource = interactionSource,
            lineLimits = lineLimits,
            scrollState = scrollState,
            onTextLayout = { layoutProvider = it },
            decorator = TextFieldDecorator { innerTextField ->
                decorator.Decoration {
                    // This is the inner editor viewport, excluding label/icons/content padding.
                Box(Modifier.fillMaxWidth().onGloballyPositioned { viewport = it }) { innerTextField() }
                }
            },
        )
    }

    val coordinates = viewport
    if (singleLine && focused && collapsed && showHandle && state.text.isNotEmpty() &&
        coordinates != null && coordinates.isAttached && layout != null
    ) {
        val rect = layout.getCursorRect(state.selection.start.coerceIn(0, state.text.length))
        val contentLeft = CursorEdgeScrollPolicy.contentLeft(scrollState.value, scrollState.maxValue, rtl)
        val localX = rect.left - contentLeft
        // No off-screen popup after a fling, external selection update, or viewport resize.
        if (tracker.dragging || localX in -1f..(coordinates.size.width + 1f)) {
            val origin = coordinates.positionInWindow()
            val tipWindow = origin + Offset(localX.coerceIn(0f, coordinates.size.width.toFloat()), rect.bottom)
            val cursorAnchor = origin + Offset(localX, rect.center.y)
            CursorHandlePopup(tracker, tipWindow, cursorAnchor, selectionColors.handleColor)
        }
    }
}

private class CursorHandleDragTracker {
    var anchorWindow by mutableStateOf<Offset?>(null)
        private set
    val dragging: Boolean get() = anchorWindow != null
    // Popup coordinates are relative to its own window; the provider records its origin
    // in the editor's window. Reconstruct absolute pointer coordinates on each event.
    var popupOrigin: Offset = Offset.Zero
    private var firstFingerWindow = Offset.Zero
    private var firstCursorWindow = Offset.Zero

    fun start(localFinger: Offset, cursorWindow: Offset) {
        firstFingerWindow = popupOrigin + localFinger
        firstCursorWindow = cursorWindow
        anchorWindow = cursorWindow
    }

    fun move(localFinger: Offset) {
        if (dragging) anchorWindow = firstCursorWindow + (popupOrigin + localFinger - firstFingerWindow)
    }

    fun stop() { anchorWindow = null }
}

@Composable
private fun CursorHandlePopup(
    tracker: CursorHandleDragTracker,
    tipWindow: Offset,
    cursorAnchor: Offset,
    color: Color,
) {
    val latestCursorAnchor by rememberUpdatedState(cursorAnchor)
    val positionProvider = remember(tipWindow, tracker) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val position = IntOffset(
                    (tipWindow.x - popupContentSize.width / 2f).roundToInt(),
                    tipWindow.y.roundToInt(),
                )
                tracker.popupOrigin = Offset(position.x.toFloat(), position.y.toFloat())
                return position
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Canvas(
            Modifier.size(48.dp).semantics { contentDescription = "커서 이동" }.pointerInput(tracker) {
                detectDragGestures(
                    onDragStart = { tracker.start(it, latestCursorAnchor) },
                    onDragEnd = tracker::stop,
                    onDragCancel = tracker::stop,
                    onDrag = { change, _ ->
                        change.consume()
                        tracker.move(change.position)
                    },
                )
            },
        ) {
            // Standard Android cursor-handle silhouette with its tip at the caret bottom.
            val width = 20.dp.toPx()
            val height = 24.dp.toPx()
            val centerX = size.width / 2f
            val path = Path().apply {
                moveTo(centerX, 0f)
                cubicTo(centerX + width / 2f, height * 0.35f, centerX + width / 2f, height * 0.5f, centerX + width / 2f, height * 0.62f)
                cubicTo(centerX + width / 2f, height, centerX - width / 2f, height, centerX - width / 2f, height * 0.62f)
                cubicTo(centerX - width / 2f, height * 0.5f, centerX - width / 2f, height * 0.35f, centerX, 0f)
                close()
            }
            drawPath(path, color)
        }
    }
}
