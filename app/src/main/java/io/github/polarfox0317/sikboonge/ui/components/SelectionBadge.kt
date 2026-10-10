package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens

@Composable
internal fun SelectionBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Canvas(
            Modifier.align(Alignment.TopStart)
                .padding(AppDimens.selectionBadgeInset)
                .size(IconButtonDefaults.smallIconSize)
                .background(colors.secondaryContainer, MaterialTheme.shapes.extraLarge),
        ) {
            val check = Path().apply {
                moveTo(size.width * AppDimens.checkStartX, size.height * AppDimens.checkStartY)
                lineTo(size.width * AppDimens.checkMiddleX, size.height * AppDimens.checkMiddleY)
                lineTo(size.width * AppDimens.checkEndX, size.height * AppDimens.checkEndY)
            }
            drawPath(check, colors.onSecondaryContainer, style = Stroke(
                width = size.width * AppDimens.checkStrokeRatio,
                cap = StrokeCap.Round,
            ))
        }
    }
}
