package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.PlayerArtwork
import io.github.polarfox0317.sikboonge.DownloadMetrics
import io.github.polarfox0317.sikboonge.DownloadProgress
import io.github.polarfox0317.sikboonge.DownloadProgressText
import io.github.polarfox0317.sikboonge.LibraryDownload
import io.github.polarfox0317.sikboonge.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadProgressCard(
    download: LibraryDownload,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    useGridTile: Boolean = false,
) {
    var cancelling by remember(download.id) { mutableStateOf(false) }
    val queued = download.stage == "대기 중"
    val status = when {
        cancelling -> "중지하는 중"
        queued -> "대기 중"
        else -> "다운로드 중"
    }
    val progress = download.progress?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
    val formatted = DownloadProgress.format(
        DownloadMetrics(
            progress = progress,
            bytesPerSecond = download.bytesPerSecond,
            remainingSeconds = download.remainingSeconds,
        ),
    )
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "downloadProgress_${download.id}",
    )
    val accessibleProgress = if (progress == null) {
        ProgressBarRangeInfo.Indeterminate
    } else {
        ProgressBarRangeInfo(progress, 0f..1f)
    }
    val accessibleSummary = listOfNotNull(download.name, status, download.stage, formatted.notification)
        .joinToString(" · ")

    val cardModifier = modifier.semantics {
            contentDescription = accessibleSummary
            stateDescription = status
            if (!queued) {
                progressBarRangeInfo = accessibleProgress
            }
        }
    val thumbnail: @Composable (Modifier) -> Unit = { PlayerArtwork(download.artworkPath, "", it) }
    if (useGridTile) {
        MediaTile(
            title = download.name,
            meta = status,
            selected = false,
            selectionMode = false,
            playing = false,
            onClick = {},
            onLongClick = {},
            thumbnail = thumbnail,
            actions = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadProgressDetails(
                        progress = progress,
                        formatted = formatted,
                        showProgress = !queued,
                        animatedProgress = animatedProgress,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DownloadCancelButton(
                            enabled = !cancelling,
                            onClick = {
                                cancelling = true
                                onCancel(download.id)
                            },
                        )
                    }
                }
            },
            modifier = cardModifier,
        )
    } else {
        MediaCard(
            title = download.name,
            meta = status,
            selected = false,
            selectionMode = false,
            playing = false,
            onClick = {},
            onLongClick = {},
            thumbnail = thumbnail,
            trailing = {
                DownloadCancelButton(
                    enabled = !cancelling,
                    onClick = {
                        cancelling = true
                        onCancel(download.id)
                    },
                )
            },
            actions = {
                DownloadProgressDetails(
                    progress = progress,
                    formatted = formatted,
                    showProgress = !queued,
                    animatedProgress = animatedProgress,
                )
            },
            modifier = cardModifier,
            clickable = false,
            showActions = true,
        )
    }
}

@Composable
private fun DownloadProgressDetails(
    progress: Float?,
    formatted: DownloadProgressText,
    showProgress: Boolean,
    animatedProgress: Float,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showProgress) {
            if (progress == null) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (formatted.percent == null && formatted.details == null) {
            Text(
                "준비 중",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                formatted.percent?.let { percent ->
                    Text(
                        percent,
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                formatted.details?.let { details ->
                    Text(
                        details,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadCancelButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("다운로드 중지") } },
        state = tooltipState,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "다운로드 중지" },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}
