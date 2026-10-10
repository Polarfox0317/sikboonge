package io.github.polarfox0317.sikboonge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class ExternalAudioJob(
    context: Context,
    private val input: String,
    private val sourceBridge: ExternalAudioInputBridge?,
    token: String,
    private val title: String,
    private val onStateChanged: (State, String?) -> Unit,
) : AutoCloseable {
    enum class State { PROBING, STREAMING, COMPLETE, FAILED, CANCELLED }

    private val appContext = context.applicationContext
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val probeSessionId = AtomicLong(0L)
    private val probeSessionLock = Any()
    private val ffmpegSessionId = AtomicLong(0L)
    private val outputFile = File(File(appContext.cacheDir, "external-audio"), "${token}.spool")
    private val outputPipe = ExternalAudioOutputPipe(outputFile, ::onPipeFinished, ::onSizeChanged)
    @Volatile private var state = State.PROBING
    @Volatile private var plan: ExternalAudioPlan? = null
    @Volatile private var displayName = ExternalAudioPolicy.displayName(title, null)
    @Volatile private var ffmpegFailure: Throwable? = null
    @Volatile private var audioDurationSeconds: Double? = null

    val outputUrl: String get() = outputPipe.outputUrl
    val currentSize: Long get() = outputPipe.currentSize()
    val currentMime: String get() = plan?.mime ?: "audio/*"
    val currentDisplayName: String get() = displayName
    val finalSize: Long? get() = currentSize.takeIf { state == State.COMPLETE }
    val currentState: State get() = state
    val isCancelled: Boolean get() = cancelled.get()

    fun start() {
        notifyState(State.PROBING, null)
        try {
            val probeFinished = AtomicBoolean(false)
            val sessionId = AudioEngine.probeMediaInformation(
                input,
                networkInput = sourceBridge != null,
                selectAudioOnly = true,
            ) { id, result ->
                synchronized(probeSessionLock) {
                    probeFinished.set(true)
                    probeSessionId.compareAndSet(id, 0L)
                }
                if (cancelled.get()) return@probeMediaInformation
                val sourceFailure = sourceBridge?.failure()
                if (sourceFailure != null) {
                    fail(sourceFailure, UserFacingErrors.fromThrowable(sourceFailure, sourceFailure.context))
                    return@probeMediaInformation
                }
                val probe = result.mapCatching { info -> AudioEngine.audioProbeFromInformation(info, allowFormatDuration = false) }
                val probeFields = probe.getOrElse { error ->
                    val message = if (error.message == "이 영상에는 오디오가 없어요.") {
                        error.message!!
                    } else UserFacingErrors.fromThrowable(error, sourceContext())
                    fail(error, message)
                    return@probeMediaInformation
                }
                val codec = probeFields.codec
                val selectedPlan = try { ExternalAudioPolicy.plan(codec) } catch (error: UnsupportedAudioCodecException) {
                    fail(error, error.message ?: "이 오디오 코덱은 외부 플레이어용 원본 추출을 지원하지 않아요.")
                    return@probeMediaInformation
                }
                if (cancelled.get()) return@probeMediaInformation
                plan = selectedPlan
                audioDurationSeconds = probeFields.duration
                displayName = ExternalAudioPolicy.displayName(title, selectedPlan.extension)
                startFfmpeg(selectedPlan)
            }
            synchronized(probeSessionLock) {
                if (!probeFinished.get() && sessionId > 0L) probeSessionId.set(sessionId)
            }
            if (cancelled.get() && sessionId > 0L) FFmpegKit.cancel(sessionId)
        } catch (error: Exception) {
            fail(error, UserFacingErrors.fromThrowable(error, sourceContext()))
        }
    }

    fun waitForInitialBytes(): Long = outputPipe.waitForInitialBytes()

    fun readAt(offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int =
        outputPipe.readAt(offset, buffer, length, cancelled)

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        state = State.CANCELLED
        val probe = probeSessionId.get()
        val ffmpeg = ffmpegSessionId.get()
        if (probe > 0) runCatching { FFmpegKit.cancel(probe) }
        if (ffmpeg > 0) runCatching { FFmpegKit.cancel(ffmpeg) }
        sourceBridge?.close()
        outputPipe.markProducerResult(false, IOException("External audio was cancelled"))
        outputPipe.close()
    }

    private fun startFfmpeg(selectedPlan: ExternalAudioPlan) {
        if (cancelled.get()) return
        if (selectedPlan.muxer == "mp4") audioDurationSeconds?.let(outputPipe::setInitialMp4Duration)
        state = State.STREAMING
        notifyState(State.STREAMING, null)
        val args = buildList {
            addAll(listOf("-y", "-nostdin", "-v", "error"))
            if (sourceBridge != null) addAll(listOf("-rw_timeout", NETWORK_TIMEOUT_US, "-protocol_whitelist", "http,tcp"))
            addAll(listOf("-i", input))
            addAll(ExternalAudioPolicy.outputArgs(outputUrl, selectedPlan))
        }.toTypedArray()
        try {
            val session = FFmpegKit.executeWithArgumentsAsync(args) { result ->
                val sourceFailure = sourceBridge?.failure()
                if (sourceFailure != null) {
                    ffmpegFailure = sourceFailure
                    sourceBridge.close()
                    outputPipe.markProducerResult(false, sourceFailure)
                } else if (ReturnCode.isSuccess(result.returnCode)) {
                    try {
                        sourceBridge?.verifyComplete { cancelled.get() }
                        if (cancelled.get()) {
                            outputPipe.markProducerResult(false, IOException("External audio was cancelled"))
                        } else {
                            outputPipe.markProducerResult(true)
                        }
                    } catch (error: Exception) {
                        if (!cancelled.get()) {
                            ffmpegFailure = error
                            sourceBridge?.close()
                            outputPipe.markProducerResult(false, error)
                        }
                    }
                } else if (cancelled.get() || ReturnCode.isCancel(result.returnCode)) {
                    outputPipe.markProducerResult(false, IOException("External audio was cancelled"))
                } else {
                    ffmpegFailure = IOException("FFmpeg audio-only extraction failed")
                    sourceBridge?.close()
                    outputPipe.markProducerResult(false, ffmpegFailure)
                }
            }
            ffmpegSessionId.set(session.sessionId)
            if (cancelled.get()) FFmpegKit.cancel(session.sessionId)
        } catch (error: Exception) {
            fail(error, "외부 오디오를 생성하지 못했어요.")
        }
    }

    private fun onPipeFinished(failure: Throwable?) {
        if (cancelled.get()) return
        if (state == State.FAILED) return
        sourceBridge?.close()
        val finalFailure = failure ?: ffmpegFailure ?: sourceBridge?.failure()
        if (finalFailure == null && currentSize > 0L) {
            state = State.COMPLETE
            notifyState(State.COMPLETE, null)
        } else {
            fail(finalFailure ?: IOException("Empty audio output"), safeFailure(finalFailure))
        }
    }

    private fun onSizeChanged(size: Long) {
        // The listener uses the main queue so a player query never observes partial shared state.
    }

    private fun fail(error: Throwable, userMessage: String) {
        if (cancelled.get() || state == State.FAILED || state == State.COMPLETE) return
        state = State.FAILED
        sourceBridge?.close()
        val probe = probeSessionId.get()
        val ffmpeg = ffmpegSessionId.get()
        if (probe > 0) runCatching { FFmpegKit.cancel(probe) }
        if (ffmpeg > 0) runCatching { FFmpegKit.cancel(ffmpeg) }
        outputPipe.markProducerResult(false, error)
        notifyState(State.FAILED, userMessage)
    }

    private fun sourceContext(): UserFacingErrors.Context = sourceBridge?.failureContext
        ?: UserFacingErrors.Context.GENERAL

    private fun safeFailure(error: Throwable?): String = when (error) {
        is UnsupportedAudioCodecException -> error.message ?: "이 오디오 코덱은 외부 플레이어용 원본 추출을 지원하지 않아요."
        null -> "외부 오디오를 생성하지 못했어요. 원본 미디어를 확인해 주세요."
        else -> UserFacingErrors.fromThrowable(error, sourceContext())
    }

    private fun notifyState(next: State, message: String?) {
        callbackHandler.post { onStateChanged(next, message) }
    }

    override fun close() {
        cancel()
        runCatching { outputFile.delete() }
    }

    companion object {
        private const val NETWORK_TIMEOUT_US = "20000000"
    }
}
