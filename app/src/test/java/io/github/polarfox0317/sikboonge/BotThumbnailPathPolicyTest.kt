package io.github.polarfox0317.sikboonge

import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotThumbnailPathPolicyTest {
    @Test fun acceptsTdlibThumbnailAndOwnedFilesButRejectsDatabaseCacheAndEscapes() {
        val ownRoot = File(System.getProperty("java.io.tmpdir"), "bot-thumb-path-${UUID.randomUUID()}")

        assertTrue(BotThumbnailPathPolicy.isOwnedSource(ownRoot, File(ownRoot, "tdlib/thumbnails/preview.jpg")))
        assertTrue(BotThumbnailPathPolicy.isOwnedSource(ownRoot, File(ownRoot, "files/thumbnails/preview.jpg")))
        assertFalse(BotThumbnailPathPolicy.isOwnedSource(ownRoot, File(ownRoot, "tdlib/database.sqlite")))
        assertFalse(BotThumbnailPathPolicy.isOwnedSource(ownRoot, File(ownRoot, "thumbnails/cached-preview.jpg")))
        assertFalse(BotThumbnailPathPolicy.isOwnedSource(ownRoot, File(ownRoot, "tdlib/thumbnails/../../outside.jpg")))
        assertFalse(BotThumbnailPathPolicy.isOwnedSource(ownRoot, File(System.getProperty("java.io.tmpdir"), "outside.jpg")))
    }
}
