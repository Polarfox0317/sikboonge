package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotInboxRecoveryPolicyTest {
    @Test
    fun initialStateLoadSignalIsFalseUntilBootstrapFinishesAndThenStaysLoaded() {
        val initial = BotUiState()
        assertFalse(initial.initialStateLoaded)

        val loaded = BotInboxRecoveryPolicy.initialStateLoaded(initial)
        assertTrue(loaded.initialStateLoaded)
        assertEquals(loaded, BotInboxRecoveryPolicy.initialStateLoaded(loaded))
    }

    @Test
    fun startupPublishesStoredRowsBeforeTheConnectionCompletes() {
        val cached = video(41L)
        val restored = BotInboxRecoveryPolicy.restoreRows(listOf(cached), emptySet(), limit = 50)

        val state = BotInboxRecoveryPolicy.connectingState(BotUiState(), paired = true, cachedRows = restored)

        assertEquals(1, state.videos.size)
        assertEquals(cached.messageId, state.videos.single().messageId)
        assertEquals(cached.thumbnailBase64, state.videos.single().thumbnailBase64)
        assertEquals(0, state.videos.single().thumbnailFileId)
        assertTrue(state.busy)
        assertFalse(state.connected)
        assertTrue(state.paired)
    }

    @Test
    fun onlyARealReconnectAfterAReadyConnectionRequestsAnotherSnapshot() {
        assertFalse(BotInboxRecoveryPolicy.shouldRefreshAfterReconnect(false, false, true))
        assertFalse(BotInboxRecoveryPolicy.shouldRefreshAfterReconnect(true, true, true))
        assertFalse(BotInboxRecoveryPolicy.shouldRefreshAfterReconnect(true, false, false))
        assertTrue(BotInboxRecoveryPolicy.shouldRefreshAfterReconnect(true, false, true))
    }

    @Test
    fun refreshRetriesAnyDisconnectedSessionUnlessReconnectIsAlreadyInFlight() {
        assertFalse(
            BotInboxRecoveryPolicy.shouldReconnectOnRefresh(
                isConnected = true,
                reconnectInFlight = false,
            ),
        )
        assertTrue(
            BotInboxRecoveryPolicy.shouldReconnectOnRefresh(
                isConnected = false,
                reconnectInFlight = false,
            ),
        )
        assertFalse(
            BotInboxRecoveryPolicy.shouldReconnectOnRefresh(
                isConnected = false,
                reconnectInFlight = true,
            ),
        )
    }

    @Test
    fun staleRefreshResponsesCannotCompleteANewerConnectionRequest() {
        fun isCurrent(requestId: Long, generation: Long, ownerIsCurrent: Boolean = true) =
            BotInboxRecoveryPolicy.isCurrentRefresh(
                requestOwnerIsCurrent = ownerIsCurrent,
                requestGeneration = generation,
                currentGeneration = 9L,
                requestId = requestId,
                currentRequestId = 12L,
                requestChatId = 7001L,
                currentChatId = 7001L,
                requestUserId = 7001L,
                currentUserId = 7001L,
                ready = true,
            )

        assertTrue(isCurrent(requestId = 12L, generation = 9L))
        assertFalse(isCurrent(requestId = 11L, generation = 9L))
        assertFalse(isCurrent(requestId = 12L, generation = 8L))
        assertFalse(isCurrent(requestId = 12L, generation = 9L, ownerIsCurrent = false))
    }

    private fun video(messageId: Long) = BotVideo(
        fileId = 17,
        chatId = 7001L,
        messageId = messageId,
        name = "cached.mp4",
        bytes = 1L,
        durationSeconds = 1,
        date = 1L,
        remoteFileId = "synthetic-remote-file",
        thumbnailFileId = 19,
        thumbnailBase64 = "cached-mini-thumbnail",
    )
}
