package io.github.polarfox0317.sikboonge

import androidx.media3.common.Player

/** Session commands that the single-item ExoPlayer delegates to the app's playlist owner. */
internal object MediaControlPolicy {
    const val SEEK_INCREMENT_MS = 10_000L

    private val queueNavigationCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )

    fun withQueueNavigation(
        base: Player.Commands,
        canPrevious: Boolean,
        canNext: Boolean,
    ): Player.Commands = base.buildUpon()
        .removeAll(*queueNavigationCommands)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, canPrevious)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, canPrevious)
        .addIf(Player.COMMAND_SEEK_TO_NEXT, canNext)
        .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, canNext)
        .build()
}
