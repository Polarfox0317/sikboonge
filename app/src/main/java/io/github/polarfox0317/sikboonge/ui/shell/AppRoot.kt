package io.github.polarfox0317.sikboonge.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
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
                if (stateOverride == null) {
                    AutomaticUpdateDialog(updateStatus)
                }
            }
        }
    }
}

@Composable
private fun AutomaticUpdateDialog(status: UpdateStatus) {
    val available = status as? UpdateStatus.Available ?: return
    if (!available.promptOnLaunch) return
    val release = available.release
    AlertDialog(
        onDismissRequest = UpdateController::dismissLaunchPrompt,
        title = { Text("새 버전이 있어요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
                Text("버전 ${release.version}을(를) 다운로드할 수 있어요.")
                if (release.notes.isNotBlank()) {
                    Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = UpdateController::skipLaunchPromptForVersion) {
                    Text("이 버전 건너뛰기")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    UpdateController.dismissLaunchPrompt()
                    UpdateController.downloadAndInstall()
                },
            ) { Text("업데이트") }
        },
        dismissButton = {
            TextButton(onClick = UpdateController::dismissLaunchPrompt) {
                Text("나중에")
            }
        },
    )
}
