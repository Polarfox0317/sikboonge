package io.github.polarfox0317.sikboonge.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.polarfox0317.sikboonge.AudioFormat
import io.github.polarfox0317.sikboonge.DownloadExecutionMode
import io.github.polarfox0317.sikboonge.ThemeModeStore
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ui.components.DisconnectDialog
import io.github.polarfox0317.sikboonge.ui.components.FormColumn
import io.github.polarfox0317.sikboonge.ui.components.SectionCard
import io.github.polarfox0317.sikboonge.ui.components.ThemeModeSelector
import io.github.polarfox0317.sikboonge.ui.shell.LocalUpdateController
import io.github.polarfox0317.sikboonge.ui.shell.ReleaseNotesBody
import io.github.polarfox0317.sikboonge.ui.shell.releaseNotes
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.WindowTier
import io.github.polarfox0317.sikboonge.update.UpdateController
import io.github.polarfox0317.sikboonge.update.UpdateStatus

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsScreen(
    state: AppUiState,
    actions: AppActions,
    tier: WindowTier,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var confirmingDisconnect by rememberSaveable { mutableStateOf(false) }
    var githubOpenError by remember { mutableStateOf(false) }
    val botName = state.bot.botName?.takeIf(String::isNotBlank)
        ?: state.bot.username?.takeIf(String::isNotBlank)?.let { "@$it" }
    val context = LocalContext.current
    val updateController = LocalUpdateController.current
    val updateStatus by updateController.status.collectAsStateWithLifecycle()
    val autoCheckEnabled by updateController.autoCheckEnabled.collectAsStateWithLifecycle()
    val themeMode by ThemeModeStore.mode.collectAsStateWithLifecycle()

    FormColumn(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
        SectionCard(
            title = { Text("\uC624\uB514\uC624 \uC800\uC7A5 \uD615\uC2DD") },
            body = {
                Text(
                    "\uBCC0\uD658\uC758 \uC601\uC0C1 \uD30C\uC77C \uCD94\uCD9C\uACFC \uBD07 \uC800\uC7A5\uC5D0 \uD568\uAED8 \uC801\uC6A9\uB3FC\uC694.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.selectableGroup()) {
                    AudioFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = state.saveFormat == format,
                                    onClick = { actions.onSaveFormatChanged(format) },
                                    enabled = !state.conversion.running,
                                    role = Role.RadioButton,
                                )
                                .testTag("settingsFormat_${format.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.saveFormat == format,
                                onClick = null,
                                enabled = !state.conversion.running,
                            )
                            Column(Modifier.padding(start = AppDimens.itemSpacing)) {
                                Text(format.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    format.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (state.conversion.running) {
                    Text(
                        "\uBCC0\uD658 \uC911\uC5D0\uB294 \uD615\uC2DD\uC744 \uBC14\uAFC0 \uC218 \uC5C6\uC5B4\uC694.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            title = { Text("저장 방식") },
            body = {
                Text(
                    if (state.downloadExecutionMode == DownloadExecutionMode.Sequential) {
                        "차례로 저장하면 저장을 누른 순서대로 한 건씩 다운로드해요. 일시 중지한 항목은 건너뛰고 다음 항목을 진행해요."
                    } else {
                        "동시에 저장하면 최대 3건을 함께 다운로드해요."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.selectableGroup()) {
                    listOf(
                        DownloadExecutionMode.Sequential to "차례로 저장",
                        DownloadExecutionMode.Parallel to "동시에 저장",
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = state.downloadExecutionMode == mode,
                                    onClick = { actions.onDownloadExecutionModeChanged(mode) },
                                    role = Role.RadioButton,
                                )
                                .testTag("settingsDownloadMode_${mode.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.downloadExecutionMode == mode,
                                onClick = null,
                            )
                            Text(label, modifier = Modifier.padding(start = AppDimens.itemSpacing),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            title = { Text("화면 모드") },
            body = {
                Text(
                    "라이트, 다크 또는 기기 설정에 맞춰 표시해요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThemeModeSelector(
                    selected = themeMode,
                    onSelect = { ThemeModeStore.set(context, it) },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            title = { Text("\uBD07") },
            body = {
                SettingsInfoRow(
                    title = "\uBD07 \uC5F0\uACB0",
                    value = if (state.bot.connected) "\uC608" else "\uC544\uB2C8\uC624",
                    tag = "settingsBotConnected",
                )
                botName?.let { name ->
                    SettingsInfoRow(
                        title = "\uBD07 \uC774\uB984",
                        value = name,
                        tag = "settingsBotName",
                    )
                }
                if (state.bot.connected && !state.bot.paired) {
                    Text(
                        "내 Telegram 계정에 연결하려면 봇과의 비공개 대화를 시작해 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = actions::onPairBot,
                        enabled = !state.bot.busy && !state.bot.username.isNullOrBlank(),
                        shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = ButtonDefaults.MediumContainerHeight)
                            .testTag("settingsBotPair"),
                    ) {
                        Text("내 Telegram 계정 연결", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                    }
                }
                if (state.bot.setupComplete || state.bot.connected) {
                    FilledTonalButton(
                        onClick = { confirmingDisconnect = true },
                        shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = ButtonDefaults.MediumContainerHeight)
                            .testTag("botDisconnect"),
                    ) {
                        Text(
                            if (state.bot.connected) "\uC5F0\uACB0 \uD574\uC81C\u00B7\uC815\uBCF4 \uC0AD\uC81C" else "\uC5F0\uACB0 \uCD08\uAE30\uD654\u00B7\uC800\uC7A5 \uC815\uBCF4 \uC0AD\uC81C",
                            style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            title = { Text("\uC815\uBCF4") },
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
                    SettingsInfoRow(
                        title = "\uC571 \uBC84\uC804",
                        value = updateController.currentVersionName,
                        tag = "settingsAppVersion",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(
                                onClickLabel = "\uC624\uD508\uC18C\uC2A4 \uB77C\uC774\uC120\uC2A4 \uC5F4\uAE30",
                                role = Role.Button,
                                onClick = actions::onOpenLicenses,
                            )
                            .testTag("openSourceLicenses"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("\uC624\uD508\uC18C\uC2A4 \uB77C\uC774\uC120\uC2A4", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "\uC0AC\uC6A9\uD55C \uB77C\uC774\uBE0C\uB7EC\uB9AC\uC640 \uB77C\uC774\uC120\uC2A4\uB97C \uD655\uC778\uD574\uC694.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).graphicsLayer(rotationZ = -90f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(
                                onClickLabel = "\uC678\uBD80 \uBE0C\uB77C\uC6B0\uC800\uC5D0\uC11C \uC5F4\uAE30",
                                role = Role.Button,
                                onClick = {
                                    githubOpenError = false
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                                    } catch (_: ActivityNotFoundException) {
                                        githubOpenError = true
                                    } catch (_: SecurityException) {
                                        githubOpenError = true
                                    }
                                },
                            )
                            .testTag("githubShortcut"),
                        horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Github",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_media_external),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (githubOpenError) {
                        Text(
                            "\uC5F4 \uC218 \uC788\uB294 \uBE0C\uB77C\uC6B0\uC800\uAC00 \uC5C6\uC5B4\uC694.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("settingsAutoCheck"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("\uC790\uB3D9 \uC5C5\uB370\uC774\uD2B8 \uD655\uC778", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "\uC0C8 \uBC84\uC804\uC744 \uC870\uC6A9\uD788 \uD655\uC778\uD574\uC694.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoCheckEnabled,
                            onCheckedChange = updateController::setAutoCheckEnabled,
                        )
                    }
                    FilledTonalButton(
                        onClick = updateController::checkManually,
                        enabled = updateStatus !is UpdateStatus.Checking &&
                            updateStatus !is UpdateStatus.Downloading &&
                            updateStatus !is UpdateStatus.PreparingInstall,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = ButtonDefaults.MediumContainerHeight)
                            .testTag("checkForUpdate"),
                    ) {
                        Text("\uC0C8 \uBC84\uC804 \uD655\uC778")
                    }
                    UpdateStatusContent(
                        updateStatus,
                        onDownload = {
                            actions.onRequestNotificationPermissionThen { updateController.downloadAndInstall() }
                        },
                        onRetry = {
                            actions.onRequestNotificationPermissionThen { updateController.retry() }
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (confirmingDisconnect) {
        DisconnectDialog(
            onConfirm = { confirmingDisconnect = false; actions.onDisconnectBot() },
            onDismiss = { confirmingDisconnect = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun UpdateStatusContent(status: UpdateStatus, onDownload: () -> Unit, onRetry: () -> Unit) {
    when (status) {
        UpdateStatus.Idle -> Unit
        UpdateStatus.Checking -> Row(
            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("\uD655\uC778 \uC911", style = MaterialTheme.typography.bodyMedium)
        }
        UpdateStatus.UpToDate -> Text(
            "\uCD5C\uC2E0 \uBC84\uC804\uC774\uC5D0\uC694.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UpdateStatus.NoPublishedRelease -> Text(
            "\uC544\uC9C1 \uACF5\uAC1C\uB41C \uB9B4\uB9AC\uC988\uAC00 \uC5C6\uC5B4\uC694.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is UpdateStatus.Available -> Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
            ReleaseNotesBody(
                versionLine = stringResource(
                    R.string.update_version_transition,
                    status.installedVersion,
                    status.release.version,
                ),
                content = releaseNotes(
                    status.release.notes,
                    status.release.title,
                    status.release.version,
                    status.release.releaseUrl,
                ),
                scrollNotes = false,
            )
            FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) { Text("\uB2E4\uC6B4\uB85C\uB4DC \uBC0F \uC124\uCE58") }
        }
        is UpdateStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
            Text(
                "${status.release.version} APK \uB2E4\uC6B4\uB85C\uB4DC \uC911",
                style = MaterialTheme.typography.bodyLarge,
            )
            val progress = if (status.totalBytes > 0L) {
                (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
            val animatedProgress by animateFloatAsState(
                targetValue = progress ?: 0f,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "update-download-progress",
            )
            if (progress != null) {
                LinearWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            ) {
                progress?.let {
                    Text(
                        stringResource(R.string.settings_update_progress, (it * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.settings_update_received, formatBytes(status.downloadedBytes)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = UpdateController::cancelDownload) { Text("\uCDE8\uC18C") }
        }
        is UpdateStatus.PreparingInstall -> Text(
            if (status.waitingForUnknownSourcesPermission) {
                "\uC124\uC815\uC5D0\uC11C \uC774 \uC571\uC758 APK \uC124\uCE58\uB97C \uD5C8\uC6A9\uD574 \uC8FC\uC138\uC694. \uB3CC\uC544\uC624\uBA74 \uC124\uCE58\uB97C \uC774\uC5B4\uAC08\uAC8C\uC694."
            } else {
                "APK\uB97C \uD655\uC778\uD588\uC5B4\uC694. \uC2DC\uC2A4\uD15C \uC124\uCE58 \uD654\uBA74\uC744 \uC5F4\uACE0 \uC788\uC5B4\uC694."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is UpdateStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
            Text(status.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("\uB2E4\uC2DC \uC2DC\uB3C4") }
        }
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String, tag: String) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / (1024f * 1024f))
    value >= 1024L -> "%.0f KB".format(value / 1024f)
    else -> "$value B"
}

private const val GITHUB_URL = "https://github.com/Polarfox0317/sikboonge"
