package io.github.polarfox0317.sikboonge.ui.state

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.SaveAudioContract
import io.github.polarfox0317.sikboonge.rememberDiagnosticLogExporter

/**
 * Call once in the Activity's root composition, before presenting the shell.
 * The same Activity-scoped controller receives results after configuration changes.
 */
@Composable
fun rememberHostEffects(controller: AppController): HostEffects {
    val controllerSaver = remember(controller) {
        Saver<AppController, Bundle>(
            save = { it.saveRestorableState() },
            restore = { controller.restoreRestorableState(it); controller },
        )
    }
    // Registers Bundle saving only: no draft strings, playback commands or operation restarts.
    rememberSaveable(saver = controllerSaver) { controller }
    val context = LocalContext.current
    val currentController = rememberUpdatedState(controller)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { /* Some providers grant read access without persistence. */ }
        }
        currentController.value.actions.onVideoPicked(uri)
    }
    // Existing contract carries the selected result's MIME and filename, including ORIGINAL output.
    val save = rememberLauncherForActivityResult(SaveAudioContract()) { uri ->
        currentController.value.actions.onSaveDestination(uri)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        currentController.value.actions.onNotificationPermissionResult(allowed)
    }
    // Both source-set implementations have the same nullable main-source-compatible signature.
    val diagnostics = rememberDiagnosticLogExporter { text, error -> currentController.value.onHostFeedback(text, error) }
    val currentDiagnostics = rememberUpdatedState(diagnostics)
    val availableDiagnostics = diagnostics != null
    val effects = remember(context, controller, pick, save, notifications, availableDiagnostics) {
        object : HostEffects {
            override val diagnosticsAvailable: Boolean = availableDiagnostics
            override fun pickVideo() { pick.launch(arrayOf("video/*")) }
            override fun saveAudioCopy(result: AudioResult) { save.launch(result) }
            override fun requestNotificationPermission() {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else currentController.value.actions.onNotificationPermissionResult(true)
            }
            override fun exportDiagnostics() { currentDiagnostics.value?.invoke() }
            override fun startShare(intent: Intent) { context.startActivity(intent) }
            override fun openExternalUrl(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
    DisposableEffect(controller, effects) {
        controller.bindHostEffects(effects)
        onDispose { controller.unbindHostEffects(effects) }
    }
    return effects
}
