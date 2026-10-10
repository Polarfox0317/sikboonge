package io.github.polarfox0317.sikboonge

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class ReviewSample(
    val video: File,
    val audio: File,
    val libraryAudio: List<AudioResult> = emptyList(),
)

/** Builds a tiny, clearly labeled local sample for the debug-only UI review screen. */
internal object ReviewSampleMedia {
    suspend fun prepare(context: Context): Result<ReviewSample> = withContext(Dispatchers.IO) {
        val video = File(context.cacheDir, "local-review-tone.mp4")
        val audio = File(File(context.filesDir, "audio").apply { mkdirs() }, ".local-review-tone.m4a")
        runCatching {
            if (!(video.isFile && audio.isFile && video.length() > 0 && audio.length() > 0)) {
                video.delete()
                audio.delete()
                try {
                    val generated = FFmpegKit.executeWithArguments(arrayOf(
                        "-y", "-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10:d=12",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=12",
                        "-c:v", "mpeg4", "-c:a", "aac", "-ac", "2", "-shortest", video.absolutePath,
                    ))
                    if (!ReturnCode.isSuccess(generated.returnCode)) {
                        android.util.Log.e("ReviewSampleMedia", "Video generation failed: ${generated.failStackTrace.orEmpty()} ${generated.allLogsAsString}")
                        error("로컬 테스트 영상을 만들지 못했어요. 다시 열어 시도해 주세요.")
                    }
                    val extracted = FFmpegKit.executeWithArguments(arrayOf(
                        "-y", "-i", video.absolutePath, "-map", "0:a:0", "-vn", "-c:a", "copy", audio.absolutePath,
                    ))
                    if (!ReturnCode.isSuccess(extracted.returnCode) || !video.isFile || !audio.isFile || video.length() <= 0 || audio.length() <= 0) {
                        android.util.Log.e("ReviewSampleMedia", "Audio extraction failed: ${extracted.failStackTrace.orEmpty()} ${extracted.allLogsAsString}")
                        error("로컬 테스트 오디오를 만들지 못했어요. 다시 열어 시도해 주세요.")
                    }
                } catch (error: Exception) {
                    video.delete()
                    audio.delete()
                    throw error
                }
            }
            val librarySamples = listOf("목록 예시 1", "목록 예시 2", "긴 제목 말줄임 확인 예시").mapIndexed { index, title ->
                val sampleFile = File(audio.parentFile, "review-fixture-${UUID.randomUUID()}.m4a")
                audio.copyTo(sampleFile)
                AudioResult(
                    path = sampleFile.absolutePath,
                    name = "로컬 테스트 톤 · $title.m4a",
                    mime = "audio/mp4",
                    bytes = sampleFile.length(),
                    date = System.currentTimeMillis() - index * 60_000L,
                )
            }
            ReviewSample(video, audio, librarySamples)
        }
    }

    suspend fun extract(
        sample: ReviewSample,
        format: AudioFormat,
        onProgress: (Float) -> Unit,
        onSessionReady: (Long) -> Unit,
    ): Result<AudioResult> = withContext(Dispatchers.IO) {
        try {
            val (codec, duration) = AudioEngine.inspect(sample.video)
            val plan = AudioEngine.plan(format, codec)
            val output = File(sample.audio.parentFile, "review-fixture-extract-${UUID.randomUUID()}.${plan.extension}")
            try {
                AudioEngine.convert(sample.video, output, plan, duration, onProgress, onSessionReady)
                check(output.isFile && output.length() > 0L) { "로컬 테스트 오디오를 만들지 못했어요." }
                Result.success(AudioResult(output.absolutePath, "로컬 테스트 톤.${plan.extension}", plan.mime, output.length(), System.currentTimeMillis()))
            } catch (error: Exception) {
                output.delete()
                throw error
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun cleanupStaleInputs(context: Context) {
        if (AppStore.state.value.running) return
        val now = System.currentTimeMillis()
        val audioFolder = File(context.filesDir, "audio")
        audioFolder.listFiles()?.filter {
            it.isDirectory && it.name.startsWith(".review-") &&
                it.lastModified() > 0L && now - it.lastModified() >= STALE_REVIEW_INPUT_AGE_MS
        }?.forEach { folder ->
            folder.listFiles()?.forEach(File::delete)
            folder.delete()
        }
    }

    private const val STALE_REVIEW_INPUT_AGE_MS = 60L * 60L * 1000L
}
