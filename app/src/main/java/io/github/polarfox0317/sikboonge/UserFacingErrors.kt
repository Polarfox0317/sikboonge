package io.github.polarfox0317.sikboonge

import java.io.IOException
import java.util.Locale

/** Converts internal/native diagnostics into short messages that are safe to show in the UI. */
internal object UserFacingErrors {
    enum class Context { GENERAL, TELEGRAM, LOCAL_MEDIA }

    fun fromThrowable(error: Throwable, context: Context = Context.GENERAL): String {
        if (error is IncompleteMediaInputException) return incompleteInput(error.context)
        return fromErrorString(error.message, context) ?: fallback(context)
    }

    /** Returns null for no error so UI callers can keep their existing no-banner behavior. */
    fun fromErrorString(raw: String?): String? = fromErrorString(raw, Context.GENERAL)

    fun fromErrorString(raw: String?, context: Context): String? {
        val message = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val lower = message.lowercase(Locale.ROOT)
        if (isPrematureInputMessage(lower)) return incompleteInput(context)
        if (isTransportMessage(lower)) return transportFailure(context)
        if (isSafeKoreanMessage(message)) return message
        return fallback(context)
    }

    private fun isPrematureInputMessage(message: String): Boolean = listOf(
        "stream ends prematurely", "unexpected eof", "premature eof", "unexpected end of file",
        "unterminated array", "short read", "ended before", "truncated", "partial file",
        "input ended", "end of file before",
    ).any(message::contains)

    private fun isTransportMessage(message: String): Boolean = listOf(
        "timed out", "timeout", "connection reset", "connection refused", "network is unreachable",
        "failed to connect", "could not resolve", "temporary failure in name resolution", "ssl handshake",
    ).any(message::contains)

    private fun isSafeKoreanMessage(message: String): Boolean {
        if (message.length > MAX_SAFE_MESSAGE_CHARS || '\n' in message || '\r' in message) return false
        if (message.none { it in '\uAC00'..'\uD7A3' }) return false
        if (UNSAFE_MARKER.containsMatchIn(message)) return false
        return message.all { it in '\uAC00'..'\uD7A3' || it in '\u3131'..'\u318E' || it.isLetterOrDigit() || it in SAFE_PUNCTUATION }
    }

    private fun incompleteInput(context: Context): String = when (context) {
        Context.TELEGRAM -> "Telegram 영상 데이터를 끝까지 읽지 못했어요. 연결을 확인하고 다시 시도해 주세요."
        Context.LOCAL_MEDIA -> "선택한 영상 파일을 끝까지 읽지 못했어요. 파일이 온전한지 확인한 뒤 다시 시도해 주세요."
        Context.GENERAL -> "미디어 데이터를 끝까지 읽지 못했어요. 입력 파일을 확인한 뒤 다시 시도해 주세요."
    }

    private fun transportFailure(context: Context): String = when (context) {
        Context.TELEGRAM -> "Telegram 연결이 중단돼 영상을 읽지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요."
        Context.LOCAL_MEDIA -> "영상 파일을 읽지 못했어요. 파일이 온전한지 확인한 뒤 다시 시도해 주세요."
        Context.GENERAL -> "미디어를 읽지 못했어요. 연결이나 입력 파일을 확인하고 다시 시도해 주세요."
    }

    private fun fallback(context: Context): String = when (context) {
        Context.TELEGRAM -> "Telegram 영상 정보를 읽지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요."
        Context.LOCAL_MEDIA -> "선택한 영상 정보를 읽지 못했어요. 파일이 온전한지 확인해 주세요."
        Context.GENERAL -> "오디오를 처리하지 못했어요. 입력을 확인하고 다시 시도해 주세요."
    }

    private const val MAX_SAFE_MESSAGE_CHARS = 180
    private const val SAFE_PUNCTUATION = " .,:·?!'’‘()…-"
    private val UNSAFE_MARKER = Regex(
        "(?i)(https?://|content://|file://|ffmpeg|ffprobe|json|exception|stack trace|0x[0-9a-f]+|" +
            "\\[[^]]*]|[A-Z]:\\\\|/data/|/storage/|\\b(?:error|warning|failed)\\b|" +
            "\\b(?:id|api_hash|access_token|bot_token|token)\\s*=|[?&](?:id|token)=)",
    )
}

internal class IncompleteMediaInputException(
    val context: UserFacingErrors.Context,
) : IOException()
