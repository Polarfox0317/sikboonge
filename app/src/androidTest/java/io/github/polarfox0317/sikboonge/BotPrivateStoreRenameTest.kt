package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BotPrivateStoreRenameTest {
    @Test
    fun customTitleSurvivesReopenAndTombstonesRejectLateRename() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "bot_inbox_rename_test_$suffix"
        val keyAlias = "io.github.polarfox0317.sikboonge.bot.inbox.rename.test.$suffix"
        val store = BotPrivateStore(context, preferencesName, keyAlias)
        val original = BotVideo(
            fileId = 17,
            chatId = 7001L,
            messageId = 41L,
            name = "original-telegram-name.mp4",
            bytes = 1L,
            durationSeconds = 1,
            date = 1L,
            remoteFileId = "synthetic-remote-file",
        )

        try {
            store.pairedUserId = 7001L
            store.pairedChatId = original.chatId
            store.videos = listOf(original)

            val renamed = store.renameVideo(original.chatId, original.messageId, "  빗소리.2026 / night?  ")
            assertEquals(original.name, renamed?.name)
            assertEquals("빗소리.2026 / night?", renamed?.customTitle)

            val reopened = BotPrivateStore(context, preferencesName, keyAlias)
            assertEquals(original.name, reopened.videos.single().name)
            assertEquals("빗소리.2026 / night?", reopened.videos.single().customTitle)
            assertEquals("빗소리.2026 / night?", reopened.videos.single().displayName)
            assertNull(reopened.renameVideo(original.chatId + 1L, original.messageId, "wrong chat"))

            reopened.removeVideos(original.chatId, setOf(original.messageId))
            assertNull(reopened.renameVideo(original.chatId, original.messageId, "late update"))
            assertTrue(reopened.videos.isEmpty())
        } finally {
            store.clear()
        }
    }
}
