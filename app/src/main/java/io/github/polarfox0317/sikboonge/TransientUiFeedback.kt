package io.github.polarfox0317.sikboonge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal const val TRANSIENT_FEEDBACK_TIMEOUT_MS = 5_000L

/**
 * In-app transient notice that carries a user action. It stays on screen because the action must
 * be reachable; callers without an action use [io.github.polarfox0317.sikboonge.ui.feedback.AppToast].
 */
@Composable
internal fun TransientNoticeBanner(
    message: String,
    modifier: Modifier = Modifier,
    testTag: String = "transientNotice",
    eventKey: Any? = message,
    action: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(message, eventKey) {
        visible = true
        delay(TRANSIENT_FEEDBACK_TIMEOUT_MS)
        visible = false
    }
    if (visible) Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            action?.invoke()
        }
    }
}
