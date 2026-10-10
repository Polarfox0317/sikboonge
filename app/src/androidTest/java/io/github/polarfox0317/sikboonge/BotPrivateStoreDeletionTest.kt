package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BotPrivateStoreDeletionTest {
    @Test
    fun legacyInboxMigratesToV2DeletionWithoutLosingOtherRowsOrPairing() {
        // Keep the app context for SharedPreferences, but isolate state with unique names so this
        // fixture cannot touch the user's inbox, credentials, or account-reset state.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "bot_inbox_deletion_test_$suffix"
        val keyAlias = "io.github.polarfox0317.sikboonge.bot.inbox.deletion.test.$suffix"
        val firstStore = BotPrivateStore(context, preferencesName, keyAlias)
        val old = video(messageId = 41L)
        val unselected = video(messageId = 43L).copy(
            thumbnailRemoteFileId = "synthetic-thumb-remote",
            thumbnailFileId = 19,
            thumbnailBase64 = "synthetic-mini-thumbnail",
            thumbnailPath = "/synthetic/private/thumbnail.webp",
        )
        val forwardedAgain = video(messageId = 42L)

        try {
            firstStore.pairedUserId = 7001L
            firstStore.pairedChatId = old.chatId
            seedLegacyArray(firstStore, listOf(old, unselected))
            assertEquals(listOf(old, unselected), firstStore.videos)
            assertEquals(listOf(old), firstStore.removeVideos(old.chatId, setOf(old.messageId)))

            val reopened = BotPrivateStore(context, preferencesName, keyAlias)
            assertEquals(7001L, reopened.pairedUserId)
            assertEquals(old.chatId, reopened.pairedChatId)
            assertEquals(listOf(unselected), reopened.videos)
            assertEquals("synthetic-thumb-remote", reopened.videos.single().thumbnailRemoteFileId)
            assertEquals(19, reopened.videos.single().thumbnailFileId)
            assertEquals("synthetic-mini-thumbnail", reopened.videos.single().thumbnailBase64)
            assertEquals("/synthetic/private/thumbnail.webp", reopened.videos.single().thumbnailPath)
            assertTrue(BotMessageKey(old.chatId, old.messageId) in reopened.deletedMessageKeys)

            // Simulate a late GetMessage/UpdateChatLastMessage merge of the old row alongside
            // a newly forwarded copy. Persisted tombstones keep the old key hidden.
            reopened.videos = listOf(old, unselected, forwardedAgain)
            assertEquals(listOf(unselected, forwardedAgain), reopened.videos)
        } finally {
            firstStore.clear()
        }
    }

    private fun seedLegacyArray(store: BotPrivateStore, videos: List<BotVideo>) {
        val rows = JSONArray().apply {
            videos.forEach { video ->
                put(JSONObject()
                    .put("id", video.fileId)
                    .put("chat", video.chatId)
                    .put("message", video.messageId)
                    .put("name", video.name)
                    .put("bytes", video.bytes)
                    .put("duration", video.durationSeconds)
                    .put("date", video.date)
                    .put("remote", video.remoteFileId)
                    .put("mime", video.mimeType)
                    .put("thumb_remote", video.thumbnailRemoteFileId)
                    .put("thumb_id", video.thumbnailFileId)
                    .put("thumb_mini", video.thumbnailBase64)
                    .put("thumb_path", video.thumbnailPath.orEmpty()))
            }
        }
        val encrypt = BotPrivateStore::class.java.getDeclaredMethod(
            "writeEncrypted", String::class.java, ByteArray::class.java,
        ).apply { isAccessible = true }
        encrypt.invoke(store, "encrypted_recent_videos", rows.toString().toByteArray(Charsets.UTF_8))
    }

    private fun video(messageId: Long) = BotVideo(
        fileId = 17,
        chatId = 7001L,
        messageId = messageId,
        name = "synthetic.mp4",
        bytes = 1L,
        durationSeconds = 1,
        date = 1L,
        remoteFileId = "synthetic-remote-file",
    )
}
