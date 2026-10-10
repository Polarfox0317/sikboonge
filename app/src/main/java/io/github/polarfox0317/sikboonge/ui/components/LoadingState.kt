package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CenteredLoading(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing, Alignment.CenterVertically),
    ) {
        LoadingIndicator()
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProgressRow(
    progress: Float?,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
        if (progress != null) {
            LinearWavyProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator() }
        }
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
