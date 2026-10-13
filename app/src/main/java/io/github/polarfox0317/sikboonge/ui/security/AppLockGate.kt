package io.github.polarfox0317.sikboonge.ui.security

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.security.AppLockController
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.rememberWindowTier

internal val LocalAppLockController = staticCompositionLocalOf<AppLockController?> { null }

/** Removes all app windows while locked, retaining the app's saveable navigation and list state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppLockGate(controller: AppLockController?, content: @Composable () -> Unit) {
    if (controller == null) {
        content()
        return
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val savedContent = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = LocalContext.current as? ComponentActivity
    val back = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)

    LaunchedEffect(state.locked) {
        if (state.locked) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    NavigationBackHandler(
        state = back,
        isBackEnabled = state.locked,
        onBackCompleted = { activity?.moveTaskToBack(true) },
    )
    CompositionLocalProvider(LocalAppLockController provides controller) {
        if (!state.locked) {
            savedContent.SaveableStateProvider("unlockedApp", content)
        } else {
            Surface(Modifier.fillMaxSize().testTag("appLockScreen")) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().safeDrawingPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.widthIn(max = AppDimens.maxFormWidth).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(
                                horizontal = AppDimens.contentHorizontalPadding(rememberWindowTier()),
                                vertical = AppDimens.sectionSpacing,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                        ) {
                            Box(
                                Modifier.size(AppDimens.emptyStateShapeSize)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                                contentAlignment = Alignment.Center,
                            ) {
                                // app choice: lock glyph sized to the empty-state shape
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                            Text(
                                "앱이 잠겨 있어요",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "기기에 등록된 지문·얼굴 또는 PIN·패턴·비밀번호로 잠금을 해제해 주세요.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            state.message?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center)
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(top = AppDimens.sectionSpacing),
                            verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                        ) {
                            Button(
                                onClick = controller::unlock,
                                enabled = !state.authenticating && state.deviceSecure,
                                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = ButtonDefaults.MediumContainerHeight)
                                    .testTag("appLockUnlock"),
                            ) {
                                Text(
                                    if (state.authenticating) "인증 중" else "잠금 해제",
                                    style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                                )
                            }
                            if (!state.deviceSecure) {
                                FilledTonalButton(
                                    onClick = controller::openSecuritySettings,
                                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                                    modifier = Modifier.fillMaxWidth()
                                        .heightIn(min = ButtonDefaults.MediumContainerHeight)
                                        .testTag("appLockDeviceSettings"),
                                ) {
                                    Text("기기 잠금 설정", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
