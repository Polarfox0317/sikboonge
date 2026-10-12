package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPlaybackRequestGenerationTest {
    @Test fun newerBotPlayInvalidatesAnOlderPrepareCompletion() {
        val requests = BotPlaybackRequestGeneration()
        val first = requests.issue()
        val second = requests.issue()

        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
    }

    @Test fun stoppingPlaybackInvalidatesAStillPendingPrepareCompletion() {
        val requests = BotPlaybackRequestGeneration()
        val pending = requests.issue()

        requests.invalidate()

        assertFalse(requests.isCurrent(pending))
    }
}
