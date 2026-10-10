package io.github.polarfox0317.sikboonge.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppController
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.state.rememberHostEffects
import io.github.polarfox0317.sikboonge.ui.theme.AppTheme
import io.github.polarfox0317.sikboonge.update.UpdateController
import io.github.polarfox0317.sikboonge.update.UpdateStatus

internal val LocalUpdateController = staticCompositionLocalOf { UpdateController }

@Composable
internal fun AppRoot(
    controller: AppController,
    stateOverride: AppUiState? = null,
    actionsOverride: AppActions? = null,
) {
    UpdateController.initialize(LocalContext.current)
    val updateStatus by UpdateController.status.collectAsStateWithLifecycle()
    val pendingChangelog by UpdateController.pendingChangelog.collectAsStateWithLifecycle()
    val activityLaunch by UpdateController.activityLaunch.collectAsStateWithLifecycle()
    val installerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        UpdateController.onSystemActivityResult()
    }

    LaunchedEffect(UpdateController, stateOverride == null) {
        if (stateOverride == null) UpdateController.checkAtStartupOnce()
    }
    LaunchedEffect(activityLaunch?.id) {
        val request = activityLaunch ?: return@LaunchedEffect
        UpdateController.markActivityLaunchDispatched(request)
        runCatching { installerLauncher.launch(UpdateController.activityIntent(request)) }
            .onFailure { UpdateController.onActivityLaunchFailed(request) }
    }

    AppTheme {
        val host = rememberHostEffects(controller)
        CompositionLocalProvider(LocalUpdateController provides UpdateController) {
            Box {
                AppShell(
                    controller,
                    host,
                    stateOverride = stateOverride,
                    actionsOverride = actionsOverride,
                )
                if (stateOverride == null && pendingChangelog != null) {
                    UpdateCompletedDialog(requireNotNull(pendingChangelog), UpdateController::acknowledgeChangelog)
                } else if (stateOverride == null) {
                    val available = updateStatus as? UpdateStatus.Available
                    if (available != null && available.promptOnLaunch) {
                        UpdateAvailableDialog(
                            release = available.release,
                            installedVersion = available.installedVersion,
                            onDismiss = UpdateController::dismissLaunchPrompt,
                            onUpdate = {
                                UpdateController.dismissLaunchPrompt()
                                controller.actions.onRequestNotificationPermissionThen {
                                    UpdateController.downloadAndInstall()
                                }
                            },
                            onSkip = UpdateController::skipLaunchPromptForVersion,
                        )
                    }
                }
            }
        }
    }
}
