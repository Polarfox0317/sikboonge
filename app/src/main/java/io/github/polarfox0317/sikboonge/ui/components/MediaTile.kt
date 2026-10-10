package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaTile(
    title: String,
    meta: String,
    selected: Boolean,
    selectionMode: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    thumbnail: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mediaTileContainer",
    )
    val outline by animateColorAsState(
        if (selected) colors.secondary else colors.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mediaTileOutline",
    )
    val artworkShape = rememberMediaArtworkShape(playing)
    Card(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { if (selectionMode) this.selected = selected },
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        ),
        border = if (selected) BorderStroke(CardDefaults.outlinedCardBorder().width, outline) else null,
    ) {
        Column(
            Modifier.padding(AppDimens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        ) {
            Box(Modifier.fillMaxWidth()) {
                thumbnail(Modifier.fillMaxWidth().aspectRatio(1f).clip(artworkShape))
                if (selected) SelectionBadge(Modifier)
            }
            Text(title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!selectionMode) actions()
        }
    }
}
