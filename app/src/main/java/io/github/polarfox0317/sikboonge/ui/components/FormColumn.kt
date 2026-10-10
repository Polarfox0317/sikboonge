package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.rememberWindowTier

@Composable
internal fun FormColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val padding = AppDimens.contentHorizontalPadding(rememberWindowTier())
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = AppDimens.maxFormWidth).fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = padding, vertical = AppDimens.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
            content = content,
        )
    }
}
