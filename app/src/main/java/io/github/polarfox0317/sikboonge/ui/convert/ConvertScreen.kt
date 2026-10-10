package io.github.polarfox0317.sikboonge.ui.convert

import android.os.Build
import io.github.polarfox0317.sikboonge.ConversionState
import io.github.polarfox0317.sikboonge.PlayerArtwork
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ui.components.FormColumn
import io.github.polarfox0317.sikboonge.ui.components.MediaCard
import io.github.polarfox0317.sikboonge.ui.components.ProgressRow
import io.github.polarfox0317.sikboonge.ui.components.SectionCard
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.state.Destination
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.util.Locale

private const val PROGRESS_CHECKING_TEXT = "확인 중"
private const val CLEAR_VIDEO_CONTENT_DESCRIPTION = "선택한 영상 지우기"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConvertScreen(
    state: AppUiState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reselectionToken: Int = 0,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(reselectionToken) {
        if (reselectionToken != 0) scrollState.scrollTo(0)
    }
    val conversion = state.conversion

    FormColumn(modifier = modifier.padding(contentPadding), scrollState = scrollState) {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
            FilledTonalButton(
                onClick = actions::onPickVideo,
                enabled = !conversion.running,
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(
                    ButtonDefaults.MediumContainerHeight,
                    hasStartIcon = true,
                ),
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(painterResource(R.drawable.ic_menu_open), contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (state.videoSelected) "다른 영상 선택" else "영상 파일 선택")
            }
            if (state.reviewSampleVideoAvailable) {
                TextButton(
                    onClick = actions::onSelectReviewSampleVideo,
                    enabled = !conversion.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("로컬 테스트 영상 선택") }
            }

            if (state.videoSelected) {
                MediaCard(
                    title = state.videoName.ifBlank { "선택한 영상" },
                    meta = videoMetadata(state.videoSizeBytes, state.videoDurationMs) ?: "",
                    selected = false,
                    selectionMode = false,
                    playing = false,
                    onClick = {},
                    onLongClick = {},
                    thumbnail = { PlayerArtwork(state.videoArtworkPath, "", it) },
                    actions = {},
                    modifier = Modifier.fillMaxWidth().testTag("selectedVideoCard"),
                    trailing = {
                        IconButton(
                            onClick = actions::onClearVideo,
                            enabled = !conversion.running,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = CLEAR_VIDEO_CONTENT_DESCRIPTION,
                            )
                        }
                    },
                    clickable = false,
                    showActions = false,
                )
            }


            Button(
                onClick = actions::onExtractVideo,
                enabled = state.videoSelected && !conversion.running,
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.LargeContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(
                    ButtonDefaults.LargeContainerHeight,
                    hasStartIcon = true,
                ),
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = ButtonDefaults.LargeContainerHeight)
                    .testTag("extractVideoAction"),
            ) {
                Icon(painterResource(R.drawable.ic_media_audio), contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(
                    if (conversion.running) "추출 중…" else "오디오 추출",
                    style = ButtonDefaults.textStyleFor(ButtonDefaults.LargeContainerHeight),
                )
            }

            if (conversion.running) ConversionProgress(state, conversion, actions)

            conversion.result?.let { result ->
                SectionCard(
                    title = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_check_circle),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text("추출 완료", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    body = {
                        MediaCard(
                            title = result.name,
                            meta = "%.1f MB · %s".format(
                                result.bytes / 1048576.0,
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT,
                                ).format(java.util.Date(result.date)),
                            ),
                            selected = false,
                            selectionMode = false,
                            playing = false,
                            onClick = {},
                            onLongClick = {},
                            thumbnail = { PlayerArtwork(result.artworkPath, "", it) },
                            actions = {},
                            modifier = Modifier.fillMaxWidth(),
                            clickable = false,
                            showActions = false,
                        )
                        FilledTonalButton(
                            onClick = { actions.onTopLevelSelected(Destination.Library) },
                            shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                            contentPadding = ButtonDefaults.contentPaddingFor(
                                ButtonDefaults.MediumContainerHeight,
                                hasStartIcon = true,
                            ),
                            modifier = Modifier.align(Alignment.End)
                                .heightIn(min = ButtonDefaults.MediumContainerHeight)
                                .testTag("openLibraryAfterConversion"),
                        ) {
                            Icon(painterResource(R.drawable.ic_library_list), contentDescription = null)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(
                                "보관함에서 보기",
                                style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConversionProgress(
    state: AppUiState,
    conversion: ConversionState,
    actions: AppActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("conversionProgress"),
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        Text(conversion.stage, style = MaterialTheme.typography.bodyMedium)
        ProgressRow(
            progress = conversion.progress?.takeIf { it.isFinite() },
            label = "",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            conversionProgressText(conversion) ?: PROGRESS_CHECKING_TEXT,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("conversionTransferDetails"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        ) {
            TextButton(onClick = actions::onCancelConversion) { Text("취소") }
            if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) {
                TextButton(onClick = actions::onRequestNotificationPermission) {
                    Text("백그라운드 알림 켜기")
                }
            }
        }
    }
}

private fun videoMetadata(sizeBytes: Long?, durationMs: Long?): String? {
    val details = buildList {
        sizeBytes?.takeIf { it >= 0L }?.let { add(formatBytes(it)) }
        durationMs?.takeIf { it > 0L }?.let { add(formatDuration(it)) }
    }
    return details.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.KOREA, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.KOREA, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.KOREA, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds / 60L % 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}

private fun conversionProgressText(state: ConversionState): String? {
    val progress = state.progress?.takeIf(Float::isFinite)
    val hasTransfer = state.receivedBytes != null || state.bytesPerSecond != null
    if (progress == null && !hasTransfer) return null
    val percentage = progress?.let { "${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: "진행률 확인 중"
    if (!hasTransfer) return percentage
    val speed = state.bytesPerSecond?.coerceAtLeast(0L)
    val rate = when {
        speed == null -> "속도 확인 중"
        speed >= 1_000_000L -> String.format(Locale.KOREA, "%.1f MB/s", speed / 1_000_000.0)
        speed >= 1_000L -> String.format(Locale.KOREA, "%.1f KB/s", speed / 1_000.0)
        else -> "$speed B/s"
    }
    return "$percentage · $rate"
}
