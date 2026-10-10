package io.github.polarfox0317.sikboonge.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.github.polarfox0317.sikboonge.R

internal data class MediaMenuItem(
    val label: String,
    @param:DrawableRes val iconRes: Int,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
    val testTag: String? = null,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaCardActions(
    onListen: () -> Unit,
    listenEnabled: Boolean,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
    saveBusy: Boolean = false,
    menuItems: List<MediaMenuItem> = emptyList(),
    moreMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    listenModifier: Modifier = Modifier,
    saveModifier: Modifier = Modifier,
    moreModifier: Modifier = Modifier,
    menuModifier: Modifier = Modifier,
    listenLabel: String = "듣기",
    @DrawableRes listenIconRes: Int = R.drawable.ic_media_play,
    compact: Boolean = false,
) {
    val listenSource = remember { MutableInteractionSource() }
    val saveSource = remember { MutableInteractionSource() }
    val moreSource = remember { MutableInteractionSource() }
    val smallHeight = ButtonDefaults.MinHeight // beta01's public Small preset.
    if (compact) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaActionTooltip(listenLabel) {
                FilledTonalIconButton(
                    onClick = onListen,
                    modifier = listenModifier,
                    enabled = listenEnabled,
                ) {
                    Icon(painterResource(listenIconRes), contentDescription = listenLabel,
                        modifier = Modifier.size(IconButtonDefaults.smallIconSize))
                }
            }
            if (onSave != null) {
                MediaActionTooltip("저장") {
                    IconButton(
                        onClick = onSave,
                        modifier = saveModifier,
                        enabled = saveEnabled && !saveBusy,
                    ) {
                        if (saveBusy) {
                            LoadingIndicator(modifier = Modifier.size(IconButtonDefaults.smallIconSize))
                        } else {
                            Icon(painterResource(R.drawable.ic_media_save), contentDescription = "저장",
                                modifier = Modifier.size(IconButtonDefaults.smallIconSize))
                        }
                    }
                }
            }
            MediaMoreMenuButton(
                modifier = moreModifier,
                menuModifier = menuModifier,
                menuItems = menuItems,
                moreMenu = moreMenu,
                interactionSource = moreSource,
            )
        }
        return
    }
    val overflowItems = buildList {
        add(MediaMenuItem(listenLabel, listenIconRes,
            enabled = listenEnabled, onClick = onListen))
        if (onSave != null) add(MediaMenuItem("저장", R.drawable.ic_media_save,
            enabled = saveEnabled && !saveBusy, onClick = onSave))
        addAll(menuItems)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButtonGroup(
            // Own an Expressive popup; do not open ButtonGroup's legacy overflow menu.
            expandedRatio = 0f,
            overflowIndicator = {
                MediaMoreMenuButton(
                    modifier = moreModifier,
                    menuModifier = menuModifier,
                    menuItems = overflowItems,
                    moreMenu = if (moreMenu == null) null else { dismiss ->
                        MediaMenuItems(
                            items = overflowItems.take(if (onSave == null) 1 else 2),
                            dismiss = dismiss,
                        )
                        moreMenu(dismiss)
                    },
                )
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    Button(
                        onClick = onListen,
                        modifier = listenModifier.animateWidth(listenSource),
                        shapes = ButtonDefaults.shapesFor(smallHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(smallHeight, hasStartIcon = true),
                        interactionSource = listenSource,
                        enabled = listenEnabled,
                    ) {
                        Icon(painterResource(listenIconRes), null,
                            Modifier.size(ButtonDefaults.iconSizeFor(smallHeight)))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(listenLabel, style = ButtonDefaults.textStyleFor(smallHeight), maxLines = 1, softWrap = false)
                    }
                },
                menuContent = { state ->
                    MediaMenuItems(
                        items = listOf(overflowItems.first()),
                        dismiss = state::dismiss,
                    )
                },
            )
            if (onSave != null) {
                customItem(
                    buttonGroupContent = {
                        FilledTonalButton(
                            onClick = onSave,
                            modifier = saveModifier.animateWidth(saveSource),
                            shapes = ButtonDefaults.shapesFor(smallHeight),
                            contentPadding = ButtonDefaults.contentPaddingFor(smallHeight, hasStartIcon = true),
                            interactionSource = saveSource,
                            enabled = saveEnabled && !saveBusy,
                        ) {
                            if (saveBusy) {
                                LoadingIndicator(
                                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(smallHeight)),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            } else {
                                Icon(painterResource(R.drawable.ic_media_save), null,
                                    Modifier.size(ButtonDefaults.iconSizeFor(smallHeight)))
                            }
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("저장", style = ButtonDefaults.textStyleFor(smallHeight), maxLines = 1, softWrap = false)
                        }
                    },
                    menuContent = { state ->
                        MediaMenuItems(items = listOf(overflowItems[1]), dismiss = state::dismiss)
                    },
                )
            }
            customItem(
                buttonGroupContent = {
                    MediaMoreMenuButton(
                        modifier = moreModifier.animateWidth(moreSource),
                        menuModifier = menuModifier,
                        menuItems = menuItems,
                        moreMenu = moreMenu,
                        interactionSource = moreSource,
                    )
                },
                menuContent = { state ->
                    if (moreMenu != null) moreMenu(state::dismiss)
                    else MediaMenuItems(items = menuItems, dismiss = state::dismiss)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaActionTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, style = MaterialTheme.typography.bodySmall) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaMoreMenuButton(
    menuItems: List<MediaMenuItem>,
    modifier: Modifier = Modifier,
    moreMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    menuModifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val dismiss = { expanded = false }
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = {
                PlainTooltip { Text("더 보기", style = MaterialTheme.typography.bodySmall) }
            },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = { expanded = true },
                modifier = modifier,
                interactionSource = interactionSource,
            ) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "더 보기",
                    modifier = Modifier.size(IconButtonDefaults.smallIconSize))
            }
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = dismiss, modifier = menuModifier) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                if (moreMenu != null) moreMenu(dismiss)
                else MediaMenuItems(items = menuItems, dismiss = dismiss)
            }
        }
    }
}

@Composable
internal fun MediaMenuItems(
    items: List<MediaMenuItem>,
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    items.forEachIndexed { index, item ->
        val colors = if (item.destructive) {
            MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            )
        } else MenuDefaults.itemColors()
        DropdownMenuItem(
            onClick = { dismiss(); item.onClick() },
            text = { Text(item.label, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                Icon(painterResource(item.iconRes), contentDescription = null)
            },
            shape = MenuDefaults.itemShape(index, items.size).shape,
            colors = colors,
            enabled = item.enabled,
            modifier = modifier.then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier),
        )
    }
}
