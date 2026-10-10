package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BotPrivateStoreRecoveryTest {
    @Test
    fun startupRestoresCachedInboxAndUnreadableStoreDoesNotEraseIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "bot_inbox_recovery_test_$suffix"
        val keyAlias = "io.github.polarfox0317.sikboonge.bot.inbox.recovery.test.$suffix"
        val writer = BotPrivateStore(context, preferencesName, keyAlias)
        val readerWithUnavailableKey = BotPrivateStore(context, preferencesName, "$keyAlias.unavailable")
        val cached = BotVideo(
            fileId = 17,
            chatId = 7001L,
            messageId = 41L,
            name = "cached.mp4",
            bytes = 1L,
            durationSeconds = 1,
            date = 1L,
            remoteFileId = "synthetic-remote-file",
        )
        val preferences = context.getSharedPreferences(preferencesName, 0)

        try {
            writer.pairedUserId = 7001L
            writer.pairedChatId = cached.chatId
            writer.videos = listOf(cached)
            val encryptedBeforeRead = preferences.getString("encrypted_recent_videos", null)
            assertNotNull(encryptedBeforeRead)

            assertEquals(listOf(cached), writer.videos)
            val readFailure = runCatching { readerWithUnavailableKey.videos }.exceptionOrNull()
            assertTrue(readFailure is IllegalStateException)
            assertEquals(encryptedBeforeRead, preferences.getString("encrypted_recent_videos", null))
            assertEquals(listOf(cached), BotPrivateStore(context, preferencesName, keyAlias).videos)
            assertEquals(7001L, writer.pairedUserId)
            assertEquals(cached.chatId, writer.pairedChatId)
        } finally {
            writer.clear()
            readerWithUnavailableKey.clear()
        }
    }
}
