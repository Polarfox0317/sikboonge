package io.github.polarfox0317.sikboonge.ui.feedback

import android.content.Context
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

internal class MessageController {
    private val lifetime = MessageLifetimePolicy()
    internal var current by mutableStateOf<MessageLifetimePolicy.Message?>(null)
        private set

    /**
     * One-time notices are raised as an OS toast on every invocation that carries text, and are
     * never retained: the toast is the whole lifetime, so the same action repeated later announces
     * again. Snapshot-driven callers (LaunchedEffect on a state value) only invoke this when the
     * underlying source actually changes, so recomposition cannot replay a notice.
     *
     * Persistent messages keep the in-app host, which owns their dismissal.
     */
    fun show(message: String, persistent: Boolean = false, context: Context? = null) {
        if (!persistent) {
            if (message.isNotBlank() && context != null) AppToast.show(context, message)
            return
        }
        lifetime.show(message, persistent = true)
        current = lifetime.current
    }

    /**
     * The job-preparation line is also a one-time notice: it is announced once when a new job
     * episode starts, and later progress updates neither re-toast nor restart it.
     */
    fun updateProgress(jobActive: Boolean, text: String, context: Context? = null) {
        val announced = lifetime.updateProgress(jobActive, text)
        current = lifetime.current
        if (announced != null && context != null) AppToast.show(context, announced)
    }

    fun dismiss(expectedId: Long? = null) {
        lifetime.dismiss(expectedId)
        current = lifetime.current
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MessageHost(
    controller: MessageController,
    modifier: Modifier = Modifier,
) {
    // One-time notices are shown by the OS toast, so this host only renders messages that must
    // stay on screen. Transient events therefore never appear twice.
    val message = controller.current?.takeIf { it.persistent }
    var lastMessage by remember(controller) { mutableStateOf(message) }
    LaunchedEffect(controller, message?.id) {
        if (message != null) lastMessage = message
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
