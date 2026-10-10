package io.github.polarfox0317.sikboonge

data class BotVideo(
    val fileId: Int,
    val chatId: Long,
    val messageId: Long,
    val name: String,
    val bytes: Long,
    val durationSeconds: Int,
    val date: Long,
    val remoteFileId: String = "",
    val needsForward: Boolean = false,
    val mimeType: String = "video/mp4",
    val thumbnailRemoteFileId: String = "",
    val thumbnailFileId: Int = 0,
    val thumbnailBase64: String = "",
    val thumbnailPath: String? = null,
    val customTitle: String? = null,
) {
    val displayName: String
        get() = customTitle?.trim()?.takeIf(String::isNotBlank)
            ?: name.trim().takeIf(String::isNotBlank)
            ?: "Telegram 영상"
}

internal object BotVideoTitlePolicy {
    const val MAX_TITLE_CHARS = 120

    /** Blank input clears a custom title; control characters are removed before persistence. */
    fun normalize(value: String): String? {
        val title = value.filterNot { Character.isISOControl(it) }.trim()
        require(title.length <= MAX_TITLE_CHARS) { "제목은 ${MAX_TITLE_CHARS}자 이하로 입력해 주세요." }
        return title.takeIf(String::isNotBlank)
    }
}

/** Retain useful preview metadata when TDLib emits another copy of an already indexed message. */
internal fun BotVideo.mergeThumbnailMetadata(previous: BotVideo?): BotVideo {
    if (previous == null || previous.chatId != chatId || previous.messageId != messageId) return this
    val retainedTitle = previous.customTitle
    val sameMedia = when {
        remoteFileId.isNotBlank() && previous.remoteFileId.isNotBlank() -> remoteFileId == previous.remoteFileId
        else -> fileId == previous.fileId
    }
    if (!sameMedia) return copy(customTitle = retainedTitle)

    val thumbnailRemoteId = thumbnailRemoteFileId.ifBlank { previous.thumbnailRemoteFileId }
    val sameThumbnail = when {
        thumbnailRemoteFileId.isNotBlank() && previous.thumbnailRemoteFileId.isNotBlank() ->
            thumbnailRemoteFileId == previous.thumbnailRemoteFileId
        thumbnailRemoteFileId.isBlank() && previous.thumbnailRemoteFileId.isNotBlank() -> true
        thumbnailFileId > 0 && previous.thumbnailFileId > 0 -> thumbnailFileId == previous.thumbnailFileId
        else -> thumbnailRemoteId.isBlank() && previous.thumbnailRemoteFileId.isBlank()
    }
    return copy(
        customTitle = retainedTitle,
        thumbnailRemoteFileId = thumbnailRemoteId,
        thumbnailFileId = thumbnailFileId.takeIf { it > 0 } ?: previous.thumbnailFileId.takeIf { sameThumbnail } ?: 0,
        thumbnailBase64 = thumbnailBase64.takeIf(String::isNotBlank)
            ?: previous.thumbnailBase64.takeIf { sameThumbnail }.orEmpty(),
        thumbnailPath = thumbnailPath ?: previous.thumbnailPath.takeIf { sameThumbnail },
    )
}
