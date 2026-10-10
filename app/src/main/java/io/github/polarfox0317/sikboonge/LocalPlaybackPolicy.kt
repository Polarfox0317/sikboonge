package io.github.polarfox0317.sikboonge

import java.io.File

/** Pure path and MIME checks shared by app requests and the exported playback service. */
internal object LocalPlaybackPolicy {
    const val INVALID_AUDIO_ERROR = "저장된 오디오 파일을 찾을 수 없어요."

    fun ownedAudioFile(filesDirectory: File, path: String?): File? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val filesRoot = filesDirectory.canonicalFile
            val audioDirectory = File(filesRoot, "audio").canonicalFile
            require(audioDirectory.parentFile == filesRoot && audioDirectory.isDirectory)
            val candidate = File(path).canonicalFile
            require(candidate.parentFile == audioDirectory && candidate.isFile && candidate.canRead())
            candidate
        }.getOrNull()
    }

    fun audioMime(value: String?): String {
        val mime = value.orEmpty().substringBefore(';').trim().lowercase()
        return if (mime.matches(Regex("audio/[a-z0-9][a-z0-9.+-]*"))) mime else "audio/*"
    }
}
