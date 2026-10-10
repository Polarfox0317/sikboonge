package io.github.polarfox0317.sikboonge.ui.bot

internal enum class BotBody { Loading, Setup, Inbox }

/** Resolves the visible bot destination independently from Compose rendering. */
internal object BotBodyPolicy {
    fun resolve(
        initialStateLoaded: Boolean,
        connected: Boolean,
        setupComplete: Boolean,
        paired: Boolean,
        setupRequested: Boolean,
        hasCachedVideos: Boolean = false,
    ): BotBody = when {
        paired || setupComplete || hasCachedVideos -> BotBody.Inbox
        connected || setupRequested -> BotBody.Setup
        !initialStateLoaded -> BotBody.Loading
        else -> BotBody.Setup
    }
}
