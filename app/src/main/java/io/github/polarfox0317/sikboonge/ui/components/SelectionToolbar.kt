package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.github.polarfox0317.sikboonge.R

/** The shell composes this only while selection mode is active. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("선택 종료") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("selectionToolbarClose")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "선택 종료")
            }
        }
        TextButton(onClick = onToggleAll, modifier = Modifier.testTag("selectAllVisible")) {
            Text(if (allSelected) "선택 해제" else "전체 선택")
        }
        TextButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("deleteSelection"),
        ) { Text("삭제") }
    }
}
