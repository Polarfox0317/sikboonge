package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotVideoTitlePolicyTest {
    @Test
    fun displayNamePreservesCustomDotsAndFallsBackToOriginalTelegramName() {
        assertEquals("빗소리.2026", video().copy(customTitle = " 빗소리.2026 ").displayName)
        assertEquals("original.mp4", video().copy(customTitle = "  ").displayName)
        assertEquals("Telegram 영상", video().copy(name = "", customTitle = null).displayName)
    }

    @Test
    fun normalizationClearsBlankInputAndRemovesControlsWithoutChangingPathCharactersOrDots() {
        assertNull(BotVideoTitlePolicy.normalize(" \n\t "))
        assertEquals("빗소리.2026 / 밤?", BotVideoTitlePolicy.normalize("\u0000 빗소리.2026 / 밤? \u0007"))
    }

    @Test
    fun titleLengthIsBoundedBeforePersistence() {
        assertEquals("x".repeat(BotVideoTitlePolicy.MAX_TITLE_CHARS), BotVideoTitlePolicy.normalize("x".repeat(BotVideoTitlePolicy.MAX_TITLE_CHARS)))
        val overLimit = runCatching { BotVideoTitlePolicy.normalize("x".repeat(BotVideoTitlePolicy.MAX_TITLE_CHARS + 1)) }
        assertTrue(overLimit.isFailure)
    }

    @Test
    fun incomingMetadataRefreshRetainsTheLocalTitleForTheSameMessage() {
        val previous = video().copy(customTitle = "빗소리.2026", thumbnailBase64 = "mini")
        val refreshed = video().copy(name = "updated-from-telegram.mp4", bytes = 20L).mergeThumbnailMetadata(previous)

        assertEquals("updated-from-telegram.mp4", refreshed.name)
        assertEquals("빗소리.2026", refreshed.customTitle)
        assertEquals("mini", refreshed.thumbnailBase64)
        assertNull(video(messageId = 42L).mergeThumbnailMetadata(previous).customTitle)
    }

    private fun video(messageId: Long = 41L) = BotVideo(
        fileId = 17,
        chatId = 7001L,
        messageId = messageId,
        name = "original.mp4",
        bytes = 1L,
        durationSeconds = 1,
        date = 1L,
        remoteFileId = "synthetic-remote-file",
    )
}
