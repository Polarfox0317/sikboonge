package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens

@Composable
internal fun SectionCard(
    title: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(
            Modifier.padding(AppDimens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        ) {
            ProvideTextStyle(MaterialTheme.typography.titleMedium, content = title)
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { body() }
        }
    }
}
