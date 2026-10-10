package io.github.polarfox0317.sikboonge

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/** Read-only source checks and a single bounded thumbnail-file probe for live diagnosis. */
class BotThumbnailDeviceCheckTest {
    @Test fun reportStoredPreviewMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = BotPrivateStore(context).videos
        Log.i(TAG, "storedRows=${rows.size}")
        rows.forEachIndexed { index, video ->
            val cachePresent = video.thumbnailPath?.let(::File)?.isFile == true
            val renderable = hasRenderablePreview(context, video)
            Log.i(
                TAG,
                "row=$index mediaRemote=${video.remoteFileId.isNotBlank()} bytes=${video.bytes} " +
                    "duration=${video.durationSeconds} thumbRemote=${video.thumbnailRemoteFileId.isNotBlank()} " +
                    "thumbFile=${video.thumbnailFileId > 0} miniChars=${video.thumbnailBase64.length} " +
                    "cachePresent=$cachePresent renderable=$renderable",
            )
        }
    }

    @Test fun largeStoredVideosHaveRenderablePreviews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val largeRows = BotPrivateStore(context).videos.filter { it.bytes >= TWO_GIB }
        assertTrue("No stored video at least 2 GiB was found", largeRows.isNotEmpty())
        largeRows.forEachIndexed { index, video ->
            assertTrue(
                "Large stored video row $index has no decodable cached thumbnail or minithumbnail",
                hasRenderablePreview(context, video),
            )
        }
    }

    @Test(timeout = 90_000) fun reportLiveThumbnailSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        BotRuntime.setup(context)

        val connectionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!BotRuntime.state.value.connected && System.nanoTime() < connectionDeadline) Thread.sleep(100)
        assertTrue("Existing bot runtime did not connect within 45 seconds", BotRuntime.state.value.connected)
        Thread.sleep(8_000)

        val rows = BotPrivateStore(context).videos
        Log.i(TAG, "liveStoredRows=${rows.size}")
        assertTrue("No previously stored bot videos were found", rows.isNotEmpty())
        val client = existingRuntimeClient()
        assertTrue("The connected BotRuntime client was unavailable for diagnostic requests", client != null)

        val pending = CountDownLatch(rows.size)
        val probedThumbnailFileIds = mutableSetOf<Int>()
        rows.forEachIndexed { index, row ->
            try {
                requireNotNull(client).send(TdApi.GetMessage(row.chatId, row.messageId)) { result ->
                    val message = result as? TdApi.Message
                    if (message == null) {
                        Log.i(TAG, "row=$index stage=get-message result=${result.javaClass.simpleName} errorCode=${(result as? TdApi.Error)?.code ?: 0}")
                        pending.countDown()
                    } else {
                        val preview = previewOf(message.content)
                        val thumbnail = preview.thumbnail
                        if (thumbnail == null) {
                            Log.i(TAG, "row=$index stage=get-message result=Message thumbnailPresent=false miniBytes=${preview.miniBytes}")
                            pending.countDown()
                        } else {
                            val file = thumbnail.file
                            Log.i(
                                TAG,
                                "row=$index stage=get-message result=Message thumbnailPresent=true " +
                                    "format=${thumbnail.format?.javaClass?.simpleName ?: "none"} " +
                                    "width=${thumbnail.width} height=${thumbnail.height} miniBytes=${preview.miniBytes} " +
                                    "fileIdPositive=${file.id > 0} remotePresent=${!file.remote?.id.isNullOrBlank()} " +
                                    "tdlibSize=${file.size} expectedSize=${file.expectedSize} " + localFileSummary(context, file),
                            )
                            if (file.id <= 0) {
                                pending.countDown()
                            } else {
                                try {
                                    val tdClient = requireNotNull(client)
                                    tdClient.send(TdApi.GetFile(file.id)) { fileResult ->
                                        val refreshed = fileResult as? TdApi.File
                                        if (refreshed == null) {
                                            Log.i(
                                                TAG,
                                                "row=$index stage=get-file result=${fileResult.javaClass.simpleName} " +
                                                    "errorCode=${(fileResult as? TdApi.Error)?.code ?: 0} " +
                                                    "errorKind=${(fileResult as? TdApi.Error)?.let(::knownErrorKind) ?: "none"}",
                                            )
                                            pending.countDown()
                                        } else {
                                            Log.i(
                                                TAG,
                                                "row=$index stage=get-file result=File format=${thumbnail.format?.javaClass?.simpleName ?: "none"} " +
                                                    "width=${thumbnail.width} height=${thumbnail.height} " +
                                                    "tdlibSize=${refreshed.size} expectedSize=${refreshed.expectedSize} " +
                                                    localFileSummary(context, refreshed),
                                            )
                                            val boundedSize = BotMediaClassifier.listedFileSize(refreshed)
                                            val firstForThisFile = probedThumbnailFileIds.add(refreshed.id)
                                            if (!refreshed.local.isDownloadingCompleted && boundedSize <= BotVideoThumbnailData.MAX_BYTES &&
                                                firstForThisFile) {
                                                try {
                                                    tdClient.send(
                                                        TdApi.DownloadFile(
                                                            refreshed.id,
                                                            1,
                                                            0L,
                                                            BotVideoThumbnailData.MAX_BYTES.toLong(),
                                                            true,
                                                        ),
                                                    ) { downloadResult ->
                                                        val downloaded = downloadResult as? TdApi.File
                                                        if (downloaded == null) {
                                                            val error = downloadResult as? TdApi.Error
                                                            Log.i(
                                                                TAG,
                                                                "row=$index stage=download result=${downloadResult.javaClass.simpleName} " +
                                                                    "limit=${BotVideoThumbnailData.MAX_BYTES} errorCode=${error?.code ?: 0} " +
                                                                    "errorKind=${error?.let(::knownErrorKind) ?: "none"}",
                                                            )
                                                        } else {
                                                            Log.i(
                                                                TAG,
                                                                "row=$index stage=download result=File limit=${BotVideoThumbnailData.MAX_BYTES} " +
                                                                    "tdlibSize=${downloaded.size} expectedSize=${downloaded.expectedSize} " +
                                                                    localFileSummary(context, downloaded),
                                                            )
                                                        }
                                                        pending.countDown()
                                                    }
                                                } catch (_: Exception) {
                                                    Log.i(
                                                        TAG,
                                                        "row=$index stage=download result=request-failed " +
                                                            "limit=${BotVideoThumbnailData.MAX_BYTES} errorCode=0 errorKind=OTHER",
                                                    )
                                                    pending.countDown()
                                                }
                                            } else {
                                                Log.i(
                                                    TAG,
                                                    "row=$index stage=download result=not-requested completed=${refreshed.local.isDownloadingCompleted} " +
                                                        "sizeWithinLimit=${boundedSize <= BotVideoThumbnailData.MAX_BYTES} " +
                                                        "sharedFile=${!firstForThisFile}",
                                                )
                                                pending.countDown()
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    Log.i(TAG, "row=$index stage=get-file result=request-failed errorCode=0")
                                    pending.countDown()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                Log.i(TAG, "row=$index stage=get-message result=request-failed errorCode=0")
                pending.countDown()
            }
        }
        assertTrue("Timed out waiting for live thumbnail metadata", pending.await(25, TimeUnit.SECONDS))
    }

    private fun hasRenderablePreview(context: Context, video: BotVideo): Boolean {
        if (BotVideoThumbnailData.hasRenderableCache(context, video.thumbnailPath)) return true
        val miniBytes = BotVideoThumbnailData.decodeMini(video.thumbnailBase64) ?: return false
        val bitmap: Bitmap = BotVideoThumbnailData.decodeSampled(miniBytes) ?: return false
        bitmap.recycle()
        return true
    }

    private fun existingRuntimeClient(): Client? = runCatching {
        BotRuntime::class.java.getDeclaredField("client").apply { isAccessible = true }.get(BotRuntime) as? Client
    }.getOrNull()

    private data class Preview(val thumbnail: TdApi.Thumbnail?, val miniBytes: Int)

    private fun previewOf(content: TdApi.MessageContent): Preview = when (content) {
        is TdApi.MessageVideo -> Preview(content.video?.thumbnail, content.video?.minithumbnail?.data?.size ?: 0)
        is TdApi.MessageVideoNote -> Preview(content.videoNote?.thumbnail, content.videoNote?.minithumbnail?.data?.size ?: 0)
        is TdApi.MessageAnimation -> Preview(content.animation?.thumbnail, content.animation?.minithumbnail?.data?.size ?: 0)
        is TdApi.MessageDocument -> Preview(content.document?.thumbnail, content.document?.minithumbnail?.data?.size ?: 0)
        else -> Preview(null, 0)
    }

    private fun localFileSummary(context: Context, file: TdApi.File): String {
        val path = file.local.path
        val underRoot = BotThumbnailPathPolicy.isOwnedSource(File(context.filesDir, "telegram-bot"), File(path))
        val localFile = if (underRoot) runCatching { File(path).canonicalFile }.getOrNull() else null
        val exists = underRoot && localFile?.isFile == true
        val byteCount = localFile?.takeIf { exists }?.length() ?: 0L
        var header4 = "none"
        var bitmapDecoded = "not_checked"
        if (exists && byteCount > 0L) {
            val bytes = readBounded(localFile!!, BotVideoThumbnailData.MAX_BYTES + 1)
            header4 = bytes.take(4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }.ifEmpty { "none" }
            if (byteCount <= BotVideoThumbnailData.MAX_BYTES && bytes.size <= BotVideoThumbnailData.MAX_BYTES) {
                val bitmap = BotVideoThumbnailData.decodeSampled(bytes)
                bitmapDecoded = (bitmap != null).toString()
                bitmap?.recycle()
            }
        }
        return "localCompleted=${file.local.isDownloadingCompleted} downloading=${file.local.isDownloadingActive} " +
            "downloadOffset=${file.local.downloadOffset} downloadedPrefix=${file.local.downloadedPrefixSize} " +
            "downloadedSize=${file.local.downloadedSize} filePathPresent=${path.isNotBlank()} " +
            "pathUnderAllowedThumbnailRoots=$underRoot localFileExists=$exists localFileBytes=$byteCount " +
            "header4=$header4 bitmapDecoded=$bitmapDecoded"
    }

    private fun readBounded(file: File, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, file.length().toInt().coerceAtLeast(0)))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        return output.toByteArray()
    }

    private fun knownErrorKind(error: TdApi.Error): String {
        val message = error.message.uppercase()
        return when {
            "FILE_REFERENCE_EXPIRED" in message || "FILE REFERENCE EXPIRED" in message -> "FILE_REFERENCE_EXPIRED"
            "FILE_REFERENCE_INVALID" in message || "FILE REFERENCE INVALID" in message -> "FILE_REFERENCE_INVALID"
            "FILE_ID_INVALID" in message || "WRONG FILE IDENTIFIER" in message -> "FILE_ID_INVALID"
            "FILE_TOKEN_INVALID" in message || "FILE TOKEN INVALID" in message -> "FILE_TOKEN_INVALID"
            "FILE_PART_0_MISSING" in message -> "FILE_PART_0_MISSING"
            else -> "OTHER"
        }
    }

    private companion object {
        const val TAG = "VideoAudioThumbQA"
        const val TWO_GIB = 2L * 1024L * 1024L * 1024L
    }
}
