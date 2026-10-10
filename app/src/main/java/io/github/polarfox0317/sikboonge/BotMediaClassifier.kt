package io.github.polarfox0317.sikboonge

import org.drinkless.tdlib.TdApi

/** Telegram message content normalized for the audio playback/save flow. */
internal data class BotMedia(
    val file: TdApi.File,
    val name: String,
    val durationSeconds: Int,
    val mimeType: String = "video/mp4",
    val thumbnailFile: TdApi.File? = null,
    val minithumbnail: ByteArray = byteArrayOf(),
)

internal object BotMediaClassifier {
    private val videoExtensions = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "mpeg", "mpg")

    fun classify(content: TdApi.MessageContent): BotMedia? = when (content) {
        is TdApi.MessageVideo -> content.video?.let { video ->
            video.video?.let { BotMedia(it, video.fileName.nonBlankOr("Telegram 영상"), video.duration.coerceAtLeast(0), playbackMimeType(video.mimeType, video.fileName), video.thumbnail?.file, video.minithumbnail?.data ?: byteArrayOf()) }
        }
        is TdApi.MessageVideoNote -> content.videoNote?.let { note ->
            note.video?.let { BotMedia(it, "Telegram 원형 영상", note.duration.coerceAtLeast(0), "video/mp4", note.thumbnail?.file, note.minithumbnail?.data ?: byteArrayOf()) }
        }
        is TdApi.MessageAnimation -> content.animation?.let { animation ->
            if (!looksLikeVideo(animation.mimeType, animation.fileName)) return@let null
            animation.animation?.let {
                BotMedia(it, animation.fileName.nonBlankOr("Telegram 애니메이션"), animation.duration.coerceAtLeast(0), playbackMimeType(animation.mimeType, animation.fileName), animation.thumbnail?.file, animation.minithumbnail?.data ?: byteArrayOf())
            }
        }
        is TdApi.MessageDocument -> content.document?.let { document ->
            if (!looksLikeVideo(document.mimeType, document.fileName)) return@let null
            document.document?.let { BotMedia(it, document.fileName.nonBlankOr("Telegram 영상"), 0, playbackMimeType(document.mimeType, document.fileName), document.thumbnail?.file, document.minithumbnail?.data ?: byteArrayOf()) }
        }
        else -> null
    }

    /** Only show a rejection hint for file-like media that this inbox intentionally examines. */
    fun rejectionReason(content: TdApi.MessageContent): String? = when (content) {
        is TdApi.MessageVideo -> "영상 메시지의 파일을 확인하지 못했어요. 다시 전달해 주세요."
        is TdApi.MessageVideoNote -> "동영상 메시지의 파일을 확인하지 못했어요. 다시 전달해 주세요."
        is TdApi.MessageAnimation -> "이 애니메이션 형식은 오디오 재생 대상이 아니에요."
        is TdApi.MessageDocument -> "이 파일은 영상으로 확인되지 않았어요. MP4 등 영상 파일로 다시 전달해 주세요."
        else -> null
    }

    fun contentKind(content: TdApi.MessageContent?): String = when (content) {
        is TdApi.MessageVideo -> "영상 메시지"
        is TdApi.MessageVideoNote -> "원형 영상 메시지"
        is TdApi.MessageAnimation -> "애니메이션 메시지"
        is TdApi.MessageDocument -> "파일 메시지"
        is TdApi.MessageText -> "텍스트 메시지"
        null -> "메시지"
        else -> "기타 메시지"
    }

    fun listedFileSize(file: TdApi.File): Long = file.size.takeIf { it > 0L } ?: file.expectedSize

    private fun looksLikeVideo(mimeType: String?, fileName: String?): Boolean =
        mimeType?.startsWith("video/", ignoreCase = true) == true ||
            fileName.orEmpty().substringAfterLast('.', "").lowercase() in videoExtensions

    private fun playbackMimeType(mimeType: String?, fileName: String?): String {
        mimeType?.trim()?.takeIf { it.startsWith("video/", ignoreCase = true) }?.let { return it }
        val byExtension = when (fileName.orEmpty().substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mpeg", "mpg" -> "video/mpeg"
            else -> null
        }
        return byExtension ?: "video/mp4"
    }

    private fun String?.nonBlankOr(fallback: String): String = this?.trim()?.takeIf(String::isNotEmpty) ?: fallback
}
