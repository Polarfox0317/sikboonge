package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaCard(
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
    trailing: (@Composable () -> Unit)? = null,
    clickable: Boolean = true,
    showActions: Boolean = true,
    tags: List<String> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mediaCardContainer",
    )
    val outline by animateColorAsState(
        if (selected) colors.secondary else colors.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mediaCardOutline",
    )
    val artworkShape = rememberMediaArtworkShape(playing)
    Card(
        modifier = modifier
            .then(
                if (clickable) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                }
            )
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(AppDimens.mediaThumbnailSize)) {
                    thumbnail(Modifier.size(AppDimens.mediaThumbnailSize).clip(artworkShape))
                    if (selected) SelectionBadge(Modifier.align(Alignment.TopStart))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(meta, style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant)
                    if (tags.isNotEmpty()) {
                        Text(tags.joinToString(" ") { "#$it" }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = colors.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                trailing?.invoke()
            }
            if (showActions && !selectionMode) actions()
        }
    }
}
