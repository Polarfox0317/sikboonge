package io.github.polarfox0317.sikboonge

/** Small state decisions for restoring the private inbox around TDLib connection changes. */
internal object BotInboxRecoveryPolicy {
    fun initialStateLoaded(state: BotUiState): BotUiState =
        if (state.initialStateLoaded) state else state.copy(initialStateLoaded = true)

    fun restoreRows(
        rows: List<BotVideo>,
        tombstones: Set<BotMessageKey>,
        limit: Int,
    ): List<BotVideo> = BotInboxDeletionPolicy.visibleRows(rows, tombstones)
        .takeLast(limit.coerceAtLeast(0))
        .map { video ->
            video.copy(
                needsForward = video.remoteFileId.isBlank(),
                thumbnailFileId = 0,
            )
        }

    fun connectingState(state: BotUiState, paired: Boolean, cachedRows: List<BotVideo>): BotUiState =
        state.copy(
            status = "Telegram에 연결하는 중",
            busy = true,
            connected = false,
            paired = paired,
            setupComplete = paired,
            videos = cachedRows,
        )

    fun shouldRefreshAfterReconnect(
        hasBeenReady: Boolean,
        wasReady: Boolean,
        isReady: Boolean,
    ): Boolean = hasBeenReady && !wasReady && isReady

    fun shouldReconnectOnRefresh(
        isConnected: Boolean,
        reconnectInFlight: Boolean,
    ): Boolean = !isConnected && !reconnectInFlight

    fun isCurrentRefresh(
        requestOwnerIsCurrent: Boolean,
        requestGeneration: Long,
        currentGeneration: Long,
        requestId: Long,
        currentRequestId: Long,
        requestChatId: Long,
        currentChatId: Long,
        requestUserId: Long,
        currentUserId: Long,
        ready: Boolean,
    ): Boolean = requestOwnerIsCurrent && requestGeneration == currentGeneration &&
        requestId == currentRequestId && requestChatId == currentChatId &&
        requestUserId == currentUserId && ready
}
