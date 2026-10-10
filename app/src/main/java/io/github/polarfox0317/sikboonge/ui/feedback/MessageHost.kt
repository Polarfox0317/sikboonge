package io.github.polarfox0317.sikboonge.ui.feedback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

internal class MessageController {
    internal data class Message(val id: Long, val text: String, val persistent: Boolean)
    internal var current by mutableStateOf<Message?>(null)
        private set
    private var nextId = 0L

    fun show(message: String, persistent: Boolean = false) {
        current = Message(++nextId, message, persistent)
    }

    fun dismiss() {
        current = null
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MessageHost(
    controller: MessageController,
    modifier: Modifier = Modifier,
) {
    val message = controller.current
    var lastMessage by remember(controller) { mutableStateOf(message) }
    LaunchedEffect(controller, message) {
        if (message != null) {
            lastMessage = message
            if (!message.persistent) {
                // app choice: 3.5s per user instruction.
                delay(3500L)
                if (controller.current?.id == message.id) controller.dismiss()
            }
        }
    }
    val displayMessage = message ?: lastMessage
    val fastEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val defaultSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter =
            slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) +
                fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()) +
                scaleIn(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    initialScale = 0.9f,
                ),
        exit =
            slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) +
                fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleOut(
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    targetScale = 0.9f,
                ),
    ) {
        Snackbar(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            action = null,
            dismissAction = null,
        ) {
            displayMessage?.let { shownMessage ->
                AnimatedContent(
                    targetState = shownMessage,
                    transitionSpec = {
                        (fadeIn(animationSpec = fastEffectsSpec) togetherWith
                            fadeOut(animationSpec = fastEffectsSpec)) using
                            SizeTransform { _, _ -> defaultSpatialSpec }
                    },
                    label = "messageContent",
                ) { animatedMessage ->
                    Text(
                        animatedMessage.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
