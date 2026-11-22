package io.github.polarfox0317.sikboonge.ui.player

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.polarfox0317.sikboonge.ui.state.ResumePrompt
import io.github.polarfox0317.sikboonge.ui.state.bookmarkPositionLabel

/**
 * **사용자 지정:** an explicit play of an unfinished item asks where to start. It shows the saved
 * time and the title, and offers continuing or starting over. Back and an outside tap cancel.
 *
 * Nothing has been started when this appears: the caller opens no service and queues nothing until
 * the user chooses, so cancelling leaves the queue and bookmarks untouched.
 */
@Composable
internal fun ResumePlaybackDialog(
    prompt: ResumePrompt,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("resumePlaybackDialog"),
        title = { Text("이어서 재생할까요?") },
        text = {
            Text(
                text = "${bookmarkPositionLabel(prompt.positionMs)}에서 멈췄어요.\n ${prompt.title}",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue, modifier = Modifier.testTag("resumePlaybackContinue")) {
                Text("이어서 재생")
            }
        },
        dismissButton = {
            TextButton(onClick = onRestart, modifier = Modifier.testTag("resumePlaybackRestart")) {
                Text("처음부터 재생")
            }
        },
    )
}
