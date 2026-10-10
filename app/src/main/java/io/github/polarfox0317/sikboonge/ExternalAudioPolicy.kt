package io.github.polarfox0317.sikboonge

/** Container and command policy for an external payload that contains audio only. */
internal data class ExternalAudioPlan(
    val extension: String,
    val mime: String,
    val muxer: String,
    val outputArgs: List<String>,
)

internal object ExternalAudioPolicy {
    fun plan(codec: String): ExternalAudioPlan = when (codec.lowercase()) {
        "aac", "alac" -> ExternalAudioPlan(
            "m4a", "audio/mp4", "mp4",
            listOf("-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-frag_duration", "1000000"),
        )
        "mp3" -> ExternalAudioPlan("mp3", "audio/mpeg", "mp3", emptyList())
        "flac" -> ExternalAudioPlan("flac", "audio/flac", "flac", emptyList())
        "opus", "vorbis" -> ExternalAudioPlan("ogg", "audio/ogg", "ogg", emptyList())
        else -> throw UnsupportedAudioCodecException()
    }

    fun outputArgs(output: String, plan: ExternalAudioPlan): List<String> = buildList {
        addAll(listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-c:a", "copy"))
        addAll(plan.outputArgs)
        addAll(listOf("-flush_packets", "1", "-f", plan.muxer, output))
    }

    /** Uses only selected-audio-stream metadata; container/video duration can be longer than audio. */
    fun audioDurationSeconds(duration: String?, durationTs: String?, timeBase: String?): Double? {
        val ticks = durationTs?.toLongOrNull()
        val ratio = timeBase?.split('/', limit = 2)
        val numerator = ratio?.getOrNull(0)?.toLongOrNull()
        val denominator = ratio?.getOrNull(1)?.toLongOrNull()
        if (ticks != null && ticks > 0L && numerator != null && numerator > 0L && denominator != null && denominator > 0L) {
            val seconds = ticks.toDouble() * numerator.toDouble() / denominator.toDouble()
            if (seconds.isFinite() && seconds > 0.0) return seconds
        }
        return duration?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun displayName(title: String, extension: String?): String {
        val base = safeDisplayName(title).substringBeforeLast('.', safeDisplayName(title)).trim().ifBlank { "Telegram audio" }
        return if (extension.isNullOrBlank()) base.take(150) else "${base.take(150)}.$extension"
    }

    fun rangeEnd(offset: Long, length: Int): Long {
        require(offset >= 0L && length >= 0)
        return if (Long.MAX_VALUE - offset < length.toLong()) Long.MAX_VALUE else offset + length
    }

    const val MAX_OUTPUT_LEAD_BYTES = 8L * 1024L * 1024L
    const val MAX_READ_SEGMENT_BYTES = 1024 * 1024
    const val CACHE_RESERVE_BYTES = 256L * 1024L * 1024L
    const val MAX_SESSION_OUTPUT_BYTES = 8L * 1024L * 1024L * 1024L
}

internal class UnsupportedAudioCodecException : IllegalArgumentException("이 오디오 코덱은 외부 플레이어용 원본 추출을 지원하지 않아요.")
