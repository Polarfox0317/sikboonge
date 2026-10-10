package io.github.polarfox0317.sikboonge.ui.bot

internal enum class BotBody { Loading, Setup, Pairing, Inbox }

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
        connected -> BotBody.Inbox
        setupRequested -> BotBody.Setup
        paired || setupComplete || hasCachedVideos -> BotBody.Inbox
        !initialStateLoaded -> BotBody.Loading
        !setupComplete -> BotBody.Setup
        else -> BotBody.Pairing
    }
}
