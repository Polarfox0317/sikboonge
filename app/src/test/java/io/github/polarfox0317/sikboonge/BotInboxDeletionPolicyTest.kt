package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotInboxDeletionPolicyTest {
    @Test
    fun tombstonesHideOldMessageButAllowAForwardWithANewMessageId() {
        val chatId = 7001L
        val old = video(chatId, messageId = 41L)
        val forwardedAgain = video(chatId, messageId = 42L)
        val deleted = BotInboxDeletionPolicy.keys(chatId, setOf(old.messageId))

        assertEquals(listOf(forwardedAgain), BotInboxDeletionPolicy.visibleRows(listOf(old, forwardedAgain), deleted))
        assertTrue(BotInboxDeletionPolicy.visibleRows(listOf(old), deleted).isEmpty())
    }

    @Test
    fun invalidIdsDoNotCreateTombstones() {
        assertTrue(BotInboxDeletionPolicy.keys(0L, setOf(1L)).isEmpty())
        assertTrue(BotInboxDeletionPolicy.keys(7L, setOf(0L, -1L)).isEmpty())
    }

    @Test
    fun refreshRowsAreLimitedToPairedChatAndExcludePendingOrDeletedMessages() {
        val chatId = 7001L
        val rows = listOf(video(chatId, 41L), video(chatId, 42L), video(7002L, 43L))
        val blocked = setOf(BotMessageKey(chatId, 41L))

        assertEquals(listOf(rows[1]), BotInboxDeletionPolicy.refreshRows(rows, chatId, blocked, limit = 1))
        assertTrue(BotInboxDeletionPolicy.refreshRows(rows, chatId, blocked, limit = 0).isEmpty())
    }

    private fun video(chatId: Long, messageId: Long) = BotVideo(
        fileId = 17,
        chatId = chatId,
        messageId = messageId,
        name = "synthetic.mp4",
        bytes = 1L,
        durationSeconds = 1,
        date = 1L,
        remoteFileId = "synthetic-remote-file",
    )
}
