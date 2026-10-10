package io.github.polarfox0317.sikboonge

import android.content.Context
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class StreamingState(
    val source: PlaybackSource = PlaybackSource.NONE,
    val title: String = "Telegram 오디오",
    val resolving: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ready: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val botFileId: Int? = null,
    val sourceBytes: Long = 0,
    val error: String? = null,
    val localAudioPath: String? = null,
    val ended: Boolean = false,
    val localAudioMime: String? = null,
    val artworkPath: String? = null,
    val artworkBase64: String = "",
)

enum class PlaybackSource { NONE, BOT, LOCAL_AUDIO }

object StreamPlayback {
    private val mutableState = MutableStateFlow(StreamingState())
    private var appContext: Context? = null
    val state: StateFlow<StreamingState> = mutableState.asStateFlow()

    fun playBot(context: Context, video: BotVideo) {
        requireMainThread()
        appContext = context.applicationContext
        mutableState.value = StreamingState(
            source = PlaybackSource.BOT,
            title = video.name,
            botFileId = video.fileId,
            sourceBytes = video.bytes,
            buffering = true,
            artworkPath = video.thumbnailPath,
            artworkBase64 = video.thumbnailBase64,
        )
        context.startService(Intent(context, StreamingAudioService::class.java)
            .setAction(BotRuntime.ACTION_PLAY_TDLIB)
            .putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, video.fileId)
            .putExtra(BotRuntime.EXTRA_TDLIB_NAME, video.name)
            .putExtra(BotRuntime.EXTRA_TDLIB_SIZE, video.bytes)
            .putExtra(StreamingAudioService.EXTRA_ARTWORK_PATH, video.thumbnailPath)
            .putExtra(StreamingAudioService.EXTRA_ARTWORK_BASE64, video.thumbnailBase64))
    }

    fun playLocal(context: Context, result: AudioResult) {
        requireMainThread()
        appContext = context.applicationContext
        val file = LocalPlaybackPolicy.ownedAudioFile(context.filesDir, result.path)
        if (file == null) {
            val title = safeDisplayName(result.name)
            mutableState.value = StreamingState(
                source = PlaybackSource.LOCAL_AUDIO,
                title = title,
                error = LocalPlaybackPolicy.INVALID_AUDIO_ERROR
            )
            try {
                context.startService(StreamingAudioService.localAudioIntent(
                    context,
                    result.path,
                    LocalPlaybackPolicy.audioMime(result.mime)
                ))
            } catch (_: Exception) {
                runCatching { context.stopService(Intent(context, StreamingAudioService::class.java)) }
                mutableState.value = StreamingState(
                    source = PlaybackSource.LOCAL_AUDIO,
                    title = title,
                    error = LocalPlaybackPolicy.INVALID_AUDIO_ERROR
                )
            }
            return
        }
        val mime = LocalPlaybackPolicy.audioMime(result.mime)
        val title = safeDisplayName(result.name)
        mutableState.value = StreamingState(
            source = PlaybackSource.LOCAL_AUDIO,
            title = title,
            buffering = true,
            localAudioPath = file.absolutePath,
            localAudioMime = mime,
            sourceBytes = file.length(),
            artworkPath = result.artworkPath,
        )
        try {
            context.startService(StreamingAudioService.localAudioIntent(context, file.absolutePath, mime, result.artworkPath, title))
        } catch (error: Exception) {
            AppDiagnostics.failure("local-playback-start", error)
            runCatching { context.stopService(Intent(context, StreamingAudioService::class.java)) }
            mutableState.value = StreamingState(
                source = PlaybackSource.LOCAL_AUDIO,
                title = title,
                localAudioPath = file.absolutePath,
                localAudioMime = mime,
                artworkPath = result.artworkPath,
                error = error.message ?: "오디오 재생을 시작하지 못했어요. 다시 시도해 주세요."
            )
        }
    }

    fun pause() = command(StreamingAudioService.ACTION_PAUSE)
    fun resume() = command(StreamingAudioService.ACTION_RESUME)
    fun stop() {
        requireMainThread()
        mutableState.value = StreamingState()
        command(StreamingAudioService.ACTION_STOP)
    }

    fun seekTo(positionMs: Long) {
        requireMainThread()
        appContext?.let { context -> context.startService(
            Intent(context, StreamingAudioService::class.java)
                .setAction(StreamingAudioService.ACTION_SEEK)
                .putExtra(StreamingAudioService.EXTRA_POSITION, positionMs.coerceAtLeast(0L))
        ) }
    }

    private fun command(action: String) {
        requireMainThread()
        val context = appContext ?: return
        context.startService(Intent(context, StreamingAudioService::class.java).setAction(action))
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "StreamPlayback methods must be called on the main thread." }
    }

    internal fun publish(value: StreamingState) {
        mutableState.value = value
    }
}
