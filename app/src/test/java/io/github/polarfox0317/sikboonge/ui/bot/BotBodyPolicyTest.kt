package io.github.polarfox0317.sikboonge.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Test

class BotBodyPolicyTest {
    @Test
    fun waitsForInitialRuntimeRestoreBeforeShowingSetup() {
        assertEquals(
            BotBody.Loading,
            BotBodyPolicy.resolve(
                initialStateLoaded = false,
                connected = false,
                setupComplete = false,
                paired = false,
                setupRequested = false,
            ),
        )
    }

    @Test
    fun anExplicitSetupRequestTakesPrecedenceWhileDisconnected() {
        assertEquals(
            BotBody.Setup,
            BotBodyPolicy.resolve(
                initialStateLoaded = true,
                connected = false,
                setupComplete = false,
                paired = false,
                setupRequested = true,
            ),
        )
    }

    @Test
    fun aPairedBotStaysOnTheInboxWithOrWithoutRowsDuringReconnect() {
        assertEquals(
            BotBody.Inbox,
            BotBodyPolicy.resolve(
                initialStateLoaded = true,
                connected = false,
                setupComplete = false,
                paired = true,
                setupRequested = false,
            ),
        )
    }

    @Test
    fun aConnectedBotShowsItsInboxBeforeThePrivateChatIsPairedEvenWithAStaleSetupRequest() {
        assertEquals(
            BotBody.Inbox,
            BotBodyPolicy.resolve(
                initialStateLoaded = true,
                connected = true,
                setupComplete = false,
                paired = false,
                setupRequested = true,
            ),
        )
    }

    @Test
    fun anUnpairedConfiguredBotShowsPairing() {
        assertEquals(
            BotBody.Pairing,
            BotBodyPolicy.resolve(
                initialStateLoaded = true,
                connected = false,
                setupComplete = true,
                paired = false,
                setupRequested = false,
            ),
        )
    }
}
