package io.github.polarfox0317.sikboonge

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.MediaInformationSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AudioFormat(val title: String, val detail: String) {
    ORIGINAL("원본 유지", "오디오를 그대로 추출해요. 추가 압축 손실이 없어요."),
    WAV("WAV", "압축 없이 저장해요. 파일이 크며 기존 손실은 복원되지 않아요."),
    MP3("MP3", "호환성이 좋아요. 다시 압축하므로 추가 손실이 생길 수 있어요.")
}

data class AudioPlan(val extension: String, val mime: String, val codecArgs: List<String>)
data class AudioProbe(val codec: String, val duration: Double, val size: Long?)

object AudioEngine {
    private const val NETWORK_TIMEOUT_US = "15000000"
    private val externalInputBridges = java.util.concurrent.ConcurrentHashMap<Long, ExternalAudioInputBridge>()
    private val cancelledSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    @Volatile internal var mediaInformationSessionHookForTests: ((MediaInformationSession) -> Unit)? = null

    /** Uses FFmpegKit's typed media-info parser, which consumes probe metadata rather than mixed session logs. */
    internal fun probeMediaInformation(
        input: String,
        networkInput: Boolean,
        selectAudioOnly: Boolean,
        logLevel: String = "error",
        complete: (Long, Result<MediaInformation>) -> Unit,
    ): Long {
        val args = buildList {
            addAll(listOf("-v", logLevel, "-hide_banner"))
            if (networkInput) addAll(listOf("-rw_timeout", NETWORK_TIMEOUT_US, "-protocol_whitelist", "http,tcp"))
            addAll(listOf("-probesize", "5000000", "-analyzeduration", "5000000"))
            if (selectAudioOnly) addAll(listOf("-select_streams", "a:0"))
            addAll(listOf(
                "-print_format", "json",
                "-show_entries", "stream=codec_type,codec_name,duration,duration_ts,time_base:format=duration,size",
                input,
            ))
        }.toTypedArray()
        val completed = AtomicBoolean(false)
        val session = try {
            MediaInformationSession.create(args) { finished ->
                if (!completed.compareAndSet(false, true)) return@create
                if (!ReturnCode.isSuccess(finished.returnCode)) {
                    complete(finished.sessionId, Result.failure(IllegalArgumentException("Media probe failed")))
                } else {
                    val info = finished.mediaInformation
                    if (info == null) {
                        complete(finished.sessionId, Result.failure(IllegalArgumentException("Media probe returned no metadata")))
                    } else if (info.streams.none { it.type == "audio" }) {
                        complete(finished.sessionId, Result.failure(IllegalArgumentException("이 영상에는 오디오가 없어요.")))
                    } else {
                        complete(finished.sessionId, Result.success(info))
                    }
                }
            }
        } catch (error: Exception) {
            complete(0L, Result.failure(error))
            return 0L
        }
        try {
            mediaInformationSessionHookForTests?.invoke(session)
            FFmpegKitConfig.asyncGetMediaInformationExecute(session, 5_000)
        } catch (error: Exception) {
            if (completed.compareAndSet(false, true)) complete(session.sessionId, Result.failure(error))
        }
        return session.sessionId
    }

    fun cancel(sessionId: Long) {
        if (externalInputBridges.containsKey(sessionId)) cancelledSessions.add(sessionId)
        externalInputBridges.remove(sessionId)?.close()
        FFmpegKit.cancel(sessionId)
    }

    fun plan(format: AudioFormat, codec: String): AudioPlan = when (format) {
        AudioFormat.WAV -> AudioPlan("wav", "audio/wav", listOf("-c:a", "pcm_f32le"))
        AudioFormat.MP3 -> AudioPlan("mp3", "audio/mpeg", listOf("-c:a", "libmp3lame", "-q:a", "0"))
        AudioFormat.ORIGINAL -> when (codec) {
            "aac", "alac" -> AudioPlan("m4a", "audio/mp4", listOf("-c:a", "copy"))
            "mp3" -> AudioPlan("mp3", "audio/mpeg", listOf("-c:a", "copy"))
            "flac" -> AudioPlan("flac", "audio/flac", listOf("-c:a", "copy"))
            "opus", "vorbis" -> AudioPlan("ogg", "audio/ogg", listOf("-c:a", "copy"))
            else -> throw IllegalArgumentException("이 오디오 형식은 원본 그대로 추출하기 어려워요. 추가 압축 없는 WAV를 선택해 주세요.")
        }
    }

    fun inspect(input: File): Pair<String, Double> {
        val info = FFprobeKit.getMediaInformation(input.absolutePath).mediaInformation
            ?: throw IllegalArgumentException("영상을 읽을 수 없어요. 다른 파일을 선택해 주세요.")
        val stream = info.streams.firstOrNull { it.type == "audio" }
            ?: throw IllegalArgumentException("이 영상에는 오디오가 없어요.")
        return (stream.codec ?: "unknown") to (info.duration?.toDoubleOrNull() ?: 0.0)
    }

    /** Probes a bot-backed loopback range bridge while leaving its lease open for the following conversion. */
    internal fun inspectBotAsync(bridge: ExternalAudioInputBridge, complete: (Result<AudioProbe>) -> Unit): Long {
        val finished = AtomicBoolean(false)
        val sessionId = probeMediaInformation(
            bridge.inputUrl,
            networkInput = true,
            selectAudioOnly = true,
        ) { id, result ->
            finished.set(true)
            externalInputBridges.remove(id, bridge)
            val sourceFailure = bridge.failure()
            if (sourceFailure != null) {
                complete(Result.failure(sourceFailure))
                return@probeMediaInformation
            }
            if (cancelledSessions.remove(id)) {
                complete(Result.failure(InterruptedException("Bot audio probe cancelled")))
                return@probeMediaInformation
            }
            complete(result.mapCatching { info -> audioProbeFromInformation(info, allowFormatDuration = false) })
        }
        if (sessionId > 0L && !finished.get()) externalInputBridges[sessionId] = bridge
        if (bridge.isClosed || finished.get()) externalInputBridges.remove(sessionId, bridge)
        return sessionId
    }

    internal fun audioProbeFromInformation(info: MediaInformation, allowFormatDuration: Boolean): AudioProbe {
        val stream = info.streams.firstOrNull { it.type == "audio" }
            ?: throw IllegalArgumentException("이 영상에는 오디오가 없어요.")
        val codec = stream.codec?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("오디오 코덱 정보를 읽을 수 없어요. 파일을 확인한 뒤 다시 시도해 주세요.")
        val streamDuration = ExternalAudioPolicy.audioDurationSeconds(
            stream.getStringProperty("duration").takeIf { !it.isNullOrBlank() && it != "N/A" },
            stream.getStringProperty("duration_ts").takeIf { !it.isNullOrBlank() && it != "N/A" },
            stream.timeBase.takeIf { !it.isNullOrBlank() && it != "N/A" },
        )
        val duration = streamDuration ?: if (allowFormatDuration) info.duration?.toDoubleOrNull() else null
        val size = info.size?.toLongOrNull()
        return AudioProbe(codec, duration ?: 0.0, size)
    }

    fun convert(
        input: File,
        output: File,
        plan: AudioPlan,
        duration: Double,
        progress: (Float) -> Unit,
        sessionReady: (Long) -> Unit,
        timeoutSeconds: Long = 600
    ) = convert(input.absolutePath, output, plan, duration, progress, sessionReady, timeoutSeconds)

    /** Starts FFmpeg from a local path or the bot media loopback bridge. */
    internal fun convert(
        input: String,
        output: File,
        plan: AudioPlan,
        duration: Double,
        progress: (Float) -> Unit,
        sessionReady: (Long) -> Unit,
        timeoutSeconds: Long = 600,
        externalInputBridge: ExternalAudioInputBridge? = null,
        maxOutputBytes: Long? = null,
    ) {
        require(externalInputBridge == null || externalInputBridge.inputUrl == input) { "Bot input bridge URL mismatch" }
        externalInputBridge?.failure()?.let { throw it }
        val networkInput = externalInputBridge != null
        val args = buildList {
            addAll(listOf("-y", "-nostdin"))
            if (networkInput) addAll(listOf("-rw_timeout", NETWORK_TIMEOUT_US, "-protocol_whitelist", "http,tcp"))
            addAll(listOf("-i", externalInputBridge?.inputUrl ?: input, "-map", "0:a:0", "-vn"))
            addAll(plan.codecArgs)
            maxOutputBytes?.let { limit ->
                require(limit > 0L)
                addAll(listOf("-fs", limit.toString()))
            }
            add(output.absolutePath)
        }.toTypedArray()
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        var inputFailure: Throwable? = null
        var cancelled = false
        val finished = AtomicBoolean(false)
        val session = FFmpegKit.executeWithArgumentsAsync(args, { result ->
            cancelled = cancelledSessions.remove(result.sessionId) || ReturnCode.isCancel(result.returnCode)
            inputFailure = externalInputBridge?.failure()
            if (inputFailure == null && !cancelled && ReturnCode.isSuccess(result.returnCode) && externalInputBridge != null) {
                try {
                    externalInputBridge.verifyComplete { cancelled || cancelledSessions.contains(result.sessionId) }
                } catch (error: Exception) {
                    inputFailure = error
                }
            }
            cancelled = cancelled || cancelledSessions.remove(result.sessionId) || ReturnCode.isCancel(result.returnCode)
            if (externalInputBridge != null) externalInputBridges.remove(result.sessionId, externalInputBridge)
            if (inputFailure != null && !cancelled) {
                failure = inputFailure
            } else if (!ReturnCode.isSuccess(result.returnCode) && !cancelled) {
                failure = IllegalArgumentException("오디오 추출에 실패했어요. 다른 파일이나 WAV 형식으로 다시 시도해 주세요.")
            }
            finished.set(true)
            latch.countDown()
        }, null, { stats ->
            if (duration > 0) progress((stats.time / (duration * 1000)).toFloat().coerceIn(0f, 0.99f))
        })
        if (externalInputBridge != null) {
            externalInputBridges[session.sessionId] = externalInputBridge
            if (finished.get()) externalInputBridges.remove(session.sessionId, externalInputBridge)
        }
        sessionReady(session.sessionId)
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                cancel(session.sessionId)
                latch.await(15, TimeUnit.SECONDS)
                throw IllegalArgumentException("오디오 추출 시간이 초과됐어요. 다시 시도해 주세요.")
            }
        } catch (e: InterruptedException) {
            cancel(session.sessionId)
            latch.await(15, TimeUnit.SECONDS)
            throw e
        }
        if (cancelled) throw InterruptedException()
        inputFailure = inputFailure ?: externalInputBridge?.failure()
        inputFailure?.let { throw it }
        failure?.let { throw it }
        if (!output.exists() || output.length() == 0L) {
            throw IllegalArgumentException("오디오 추출에 실패했어요. 다른 파일이나 WAV 형식으로 다시 시도해 주세요.")
        }
        if (maxOutputBytes != null && output.length() >= (maxOutputBytes - OUTPUT_LIMIT_COMPLETION_MARGIN_BYTES).coerceAtLeast(1L)) {
            throw IllegalArgumentException("오디오 파일이 저장 한도에 가까워 완전한지 확인할 수 없어 저장을 중단했어요. 저장 공간을 확보하거나 다른 형식을 선택해 주세요.")
        }
    }

    private const val OUTPUT_LIMIT_COMPLETION_MARGIN_BYTES = 1024L * 1024L
}
