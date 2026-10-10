package io.github.polarfox0317.sikboonge

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class BotMediaClassifierTest {
    @Test fun typedVideoIsAcceptedEvenWithBlankNameAndUnexpectedMime() {
        val thumbnailBytes = byteArrayOf(1, 2, 3)
        val thumbnail = tdFile(id = 16, remoteId = "thumb-remote")
        val content = TdApi.MessageVideo().apply {
            video = TdApi.Video().apply {
                duration = 42
                fileName = ""
                mimeType = "application/octet-stream"
                minithumbnail = TdApi.Minithumbnail().apply { data = thumbnailBytes }
                this.thumbnail = TdApi.Thumbnail().apply { file = thumbnail }
                video = tdFile(id = 15)
            }
        }

        val media = BotMediaClassifier.classify(content)

        assertNotNull(media)
        assertEquals("Telegram 영상", media?.name)
        assertEquals(42, media?.durationSeconds)
        assertEquals(15, media?.file?.id)
        assertEquals("video/mp4", media?.mimeType)
        assertEquals(16, media?.thumbnailFile?.id)
        assertEquals("thumb-remote", media?.thumbnailFile?.remote?.id)
        assertEquals(listOf<Byte>(1, 2, 3), media?.minithumbnail?.toList())
    }

    @Test fun videoNoteAndVideoAnimationPreserveTheirFileAndDuration() {
        val videoNote = TdApi.MessageVideoNote().apply {
            videoNote = TdApi.VideoNote().apply {
                duration = 17
                video = tdFile(id = 21)
            }
        }
        val animation = TdApi.MessageAnimation().apply {
            animation = TdApi.Animation().apply {
                duration = 9
                fileName = ""
                mimeType = "video/mp4"
                animation = tdFile(id = 28)
            }
        }

        val noteMedia = BotMediaClassifier.classify(videoNote)
        val animationMedia = BotMediaClassifier.classify(animation)

        assertEquals("Telegram 원형 영상", noteMedia?.name)
        assertEquals(17, noteMedia?.durationSeconds)
        assertEquals(21, noteMedia?.file?.id)
        assertEquals("Telegram 애니메이션", animationMedia?.name)
        assertEquals(9, animationMedia?.durationSeconds)
        assertEquals(28, animationMedia?.file?.id)
    }

    @Test fun gifAnimationAndNonVideoDocumentAreRejected() {
        val gif = TdApi.MessageAnimation().apply {
            animation = TdApi.Animation().apply {
                fileName = "sticker.gif"
                mimeType = "image/gif"
                animation = tdFile(id = 30)
            }
        }
        val image = TdApi.MessageDocument().apply {
            document = TdApi.Document().apply {
                fileName = "photo.jpg"
                mimeType = "image/jpeg"
                document = tdFile(id = 31)
            }
        }

        assertNull(BotMediaClassifier.classify(gif))
        assertNull(BotMediaClassifier.classify(image))
    }

    @Test fun videoDocumentCanUseVideoMimeOrKnownExtension() {
        val mimeOnly = document("private attachment", "video/mp4", 40)
        val extensionOnly = document("private clip.MP4", "application/octet-stream", 41)

        assertEquals(40, BotMediaClassifier.classify(mimeOnly)?.file?.id)
        assertEquals(41, BotMediaClassifier.classify(extensionOnly)?.file?.id)
        assertEquals("video/x-matroska", BotMediaClassifier.classify(document("private clip.mkv", "application/octet-stream", 42))?.mimeType)
    }

    @Test fun knownDownloadedSizeTakesPrecedenceAndExpectedSizeIsFallback() {
        assertEquals(123L, BotMediaClassifier.listedFileSize(tdFile(id = 1, size = 123L, expectedSize = 456L)))
        assertEquals(456L, BotMediaClassifier.listedFileSize(tdFile(id = 2, size = 0L, expectedSize = 456L)))
    }

    @Test fun duplicateRestoredMessageKeepsItsCachedThumbnailButChangedMediaDoesNot() {
        val cached = BotVideo(
            fileId = 12, chatId = 7, messageId = 90, name = "clip.mp4", bytes = 100, durationSeconds = 3, date = 1,
            remoteFileId = "video-remote", thumbnailRemoteFileId = "thumb-remote", thumbnailFileId = 18,
            thumbnailBase64 = "mini", thumbnailPath = "/private/thumb.img",
        )

        val sameMedia = cached.copy(fileId = 31, thumbnailFileId = 0, thumbnailBase64 = "", thumbnailPath = null)
            .mergeThumbnailMetadata(cached)
        assertEquals("thumb-remote", sameMedia.thumbnailRemoteFileId)
        assertEquals("mini", sameMedia.thumbnailBase64)
        assertEquals("/private/thumb.img", sameMedia.thumbnailPath)

        val changedThumbnail = cached.copy(
            fileId = 32, thumbnailRemoteFileId = "new-thumb", thumbnailFileId = 19,
            thumbnailBase64 = "new-mini", thumbnailPath = null,
        ).mergeThumbnailMetadata(cached)
        assertEquals("new-thumb", changedThumbnail.thumbnailRemoteFileId)
        assertEquals("new-mini", changedThumbnail.thumbnailBase64)
        assertNull(changedThumbnail.thumbnailPath)

        val changedMedia = cached.copy(
            fileId = 33, remoteFileId = "new-video", thumbnailRemoteFileId = "",
            thumbnailFileId = 0, thumbnailBase64 = "", thumbnailPath = null,
        )
            .mergeThumbnailMetadata(cached)
        assertNull(changedMedia.thumbnailPath)
        assertEquals("", changedMedia.thumbnailRemoteFileId)
    }

    @Test fun thumbnailRefreshSelectsOnlyRowsWithoutRenderableCacheAndMatchesSharedFileIds() {
        val first = BotVideo(fileId = 1, chatId = 7, messageId = 90, name = "a", bytes = 1, durationSeconds = 0, date = 1, thumbnailFileId = 18)
        val second = BotVideo(fileId = 2, chatId = 7, messageId = 91, name = "b", bytes = 1, durationSeconds = 0, date = 1, thumbnailFileId = 18)
        val third = BotVideo(fileId = 3, chatId = 7, messageId = 92, name = "c", bytes = 1, durationSeconds = 0, date = 1, thumbnailFileId = 19)

        assertEquals(listOf(second, third), BotThumbnailPolicy.rowsNeedingMetadata(listOf(first, second, third)) { it == first })
        assertEquals(listOf(first, second), BotThumbnailPolicy.rowsForCompletedFile(listOf(first, second, third), 18))
        assertEquals(emptyList<BotVideo>(), BotThumbnailPolicy.rowsForCompletedFile(listOf(first, second, third), 0))
    }

    private fun document(name: String, mime: String, id: Int) = TdApi.MessageDocument().apply {
        document = TdApi.Document().apply {
            fileName = name
            mimeType = mime
            document = tdFile(id)
        }
    }

    private fun tdFile(id: Int, size: Long = 0L, expectedSize: Long = 0L, remoteId: String = "") = TdApi.File().apply {
        this.id = id
        this.size = size
        this.expectedSize = expectedSize
        if (remoteId.isNotBlank()) remote = TdApi.RemoteFile().apply { this.id = remoteId }
    }
}
