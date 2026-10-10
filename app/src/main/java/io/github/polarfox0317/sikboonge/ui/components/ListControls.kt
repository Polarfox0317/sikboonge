package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonSize
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ui.state.DateRange
import io.github.polarfox0317.sikboonge.ui.state.ListLayout
import io.github.polarfox0317.sikboonge.ui.state.ListQuery
import io.github.polarfox0317.sikboonge.ui.state.SortKey
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListControls(
    query: ListQuery,
    onQueryChange: (ListQuery) -> Unit,
    layout: ListLayout,
    onLayoutChange: (ListLayout) -> Unit,
    placeholder: String,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query.text,
                    onQueryChange = { onQueryChange(query.copy(text = it)) },
                    onSearch = { keyboardController?.hide() },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}Search"),
                    placeholder = { Text(placeholder) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    },
                    trailingIcon = if (query.text.isNotEmpty()) {
                        {
                            ListControlTooltip("검색어 지우기") {
                                IconButton(onClick = { onQueryChange(query.copy(text = "")) }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "검색어 지우기")
                                }
                            }
                        }
                    } else null,
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth(),
        ) {}
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SortControls(query, onQueryChange, tagPrefix)
            LayoutModeButtons(layout, onLayoutChange, tagPrefix)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SortControls(
    query: ListQuery,
    onQueryChange: (ListQuery) -> Unit,
    tagPrefix: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val rangeLabel = if (query.range == DateRange.All) "" else "(${query.range.label})"
    val criterionLabel = "${query.sortKey.label}$rangeLabel"
    val directionLabel = if (query.ascending) "오름차순" else "내림차순"
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        customItem(
            buttonGroupContent = {
                Box {
                    ListControlTooltip(criterionLabel) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                            modifier = Modifier.testTag("${tagPrefix}SortKey"),
                            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
                        ) {
                            Text(criterionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Icon(
                                painterResource(R.drawable.ic_arrow_drop_down),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)),
                            )
                        }
                    }
                    DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Column(verticalArrangement = Arrangement.spacedBy(MenuDefaults.GroupSpacing)) {
                            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                                SortKey.entries.forEachIndexed { index, value ->
                                    val isSelected = query.sortKey == value
                                    DropdownMenuItem(
                                        onClick = {
                                            expanded = false
                                            onQueryChange(query.copy(sortKey = value))
                                        },
                                        text = { Text(value.label) },
                                        shape = MenuDefaults.itemShape(index, SortKey.entries.size).shape,
                                        leadingIcon = if (isSelected) {
                                            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                                        } else null,
                                        modifier = Modifier
                                            .testTag("${tagPrefix}SortKey_${value.name}")
                                            .semantics { selected = isSelected },
                                    )
                                }
                            }
                            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                                DateRange.entries.forEachIndexed { index, value ->
                                    val isSelected = query.range == value
                                    DropdownMenuItem(
                                        onClick = {
                                            expanded = false
                                            onQueryChange(query.copy(range = value))
                                        },
                                        text = { Text(value.label) },
                                        shape = MenuDefaults.itemShape(index, DateRange.entries.size).shape,
                                        leadingIcon = if (isSelected) {
                                            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                                        } else null,
                                        modifier = Modifier
                                            .testTag("${tagPrefix}Range_${value.name}")
                                            .semantics { selected = isSelected },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                ListControlTooltip(directionLabel) {
                    OutlinedButton(
                        onClick = { onQueryChange(query.copy(ascending = !query.ascending)) },
                        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                        modifier = Modifier
                            .testTag("${tagPrefix}SortDirection")
                            .semantics { stateDescription = directionLabel },
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
                    ) {
                        Icon(
                            painterResource(
                                if (query.ascending) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward,
                            ),
                            contentDescription = directionLabel,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)),
                        )
                    }
                }
            },
            menuContent = {},
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LayoutModeButtons(
    layout: ListLayout,
    onLayoutChange: (ListLayout) -> Unit,
    tagPrefix: String,
) {
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        customItem(
            buttonGroupContent = {
                ListControlTooltip("목록 보기") {
                    ToggleButton(
                        checked = layout == ListLayout.List,
                        onCheckedChange = { onLayoutChange(ListLayout.List) },
                        buttonSize = ToggleButtonSize.Small,
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        modifier = Modifier.testTag("${tagPrefix}LayoutList"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_view_agenda),
                            contentDescription = "목록 보기",
                            modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                        )
                    }
                }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                ListControlTooltip("격자 보기") {
                    ToggleButton(
                        checked = layout == ListLayout.Grid,
                        onCheckedChange = { onLayoutChange(ListLayout.Grid) },
                        buttonSize = ToggleButtonSize.Small,
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.testTag("${tagPrefix}LayoutGrid"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_grid_view),
                            contentDescription = "격자 보기",
                            modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                        )
                    }
                }
            },
            menuContent = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListControlTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
