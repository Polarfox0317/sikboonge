package io.github.polarfox0317.sikboonge

import org.junit.Assert.*
import org.junit.Test

class BotInboxPolicyTest {
    @Test fun onlyFreshExactPairingPayloadsAreAccepted() {
        assertTrue(BotInboxPolicy.pairingMatches("/start nonce", "nonce", 1_000, 2_000))
        assertFalse(BotInboxPolicy.pairingMatches("/start wrong", "nonce", 1_000, 2_000))
        assertFalse(BotInboxPolicy.pairingMatches("/start nonce", "nonce", 1_000, 301_001))
        assertFalse(BotInboxPolicy.pairingMatches("/start nonce", null, 1_000, 2_000))
    }
    @Test fun onlyPairedPrivateIncomingSaveableMediaIsAccepted() {
        assertTrue(BotInboxPolicy.acceptsVideo(7, 7, 7, 7, true, false, true))
        assertFalse(BotInboxPolicy.acceptsVideo(8, 8, 7, 7, true, false, true))
        assertFalse(BotInboxPolicy.acceptsVideo(7, 7, 0, 0, true, false, true))
        assertFalse(BotInboxPolicy.acceptsVideo(7, 7, 7, 7, false, false, true))
        assertFalse(BotInboxPolicy.acceptsVideo(7, 7, 7, 7, true, true, true))
        assertFalse(BotInboxPolicy.acceptsVideo(7, 7, 7, 7, true, false, false))
    }

    @Test fun pairingStillRequiresTheOwnIncomingPrivateChat() {
        assertTrue(BotInboxPolicy.acceptsPairedPrivateIncoming(7, 7, 7, 7, true, false))
        assertFalse(BotInboxPolicy.acceptsPairedPrivateIncoming(8, 8, 7, 7, true, false))
        assertFalse(BotInboxPolicy.acceptsPairedPrivateIncoming(7, 7, 7, 7, false, false))
        assertFalse(BotInboxPolicy.acceptsPairedPrivateIncoming(7, 7, 7, 7, true, true))
    }

    @Test fun onlyPrivatePostLinksReceiveThePrivatePostHint() {
        assertTrue(BotInboxPolicy.containsPrivatePostLink("https://t.me/c/123456/78"))
        assertTrue(BotInboxPolicy.containsPrivatePostLink("t.me/c/123456/78?single"))
        assertFalse(BotInboxPolicy.containsPrivatePostLink("https://t.me/publicchannel/78"))
        assertFalse(BotInboxPolicy.containsPrivatePostLink("random text"))
    }

    @Test fun lastMessageAndNewMessagePathsDoNotAdmitDuplicateIndexedMedia() {
        val indexed = setOf(77L to 18)
        assertTrue(BotInboxPolicy.isDuplicateMedia(77L, 18, indexed))
        assertFalse(BotInboxPolicy.isDuplicateMedia(78L, 18, indexed))
        assertFalse(BotInboxPolicy.isDuplicateMedia(77L, 19, indexed))
    }
}
