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
    /**
     * True only while this state comes from the internal Bot reconnect-and-replay wait, including the
     * error that ends it. The player shows that error inline; toast collectors skip it, so the normal
     * playback error toasts stay exactly as they are for everything else.
     */
    val suppressErrorToast: Boolean = false,
    val localAudioPath: String? = null,
    val ended: Boolean = false,
    val localAudioMime: String? = null,
    val artworkPath: String? = null,
    val artworkBase64: String = "",
    /** Identifies the service that published this state; a new service starts at 1. */
    val serviceGeneration: Int = 0,
    /**
     * The playback service that owned this source ended without a user close, so the item is a
     * resumable paused session. The transport handles stay for the resume request; only this
     * flag marks that no player is serving the source right now.
     */
    val serviceDetached: Boolean = false,
)

enum class PlaybackSource { NONE, BOT, LOCAL_AUDIO }

object StreamPlayback {
    private val mutableState = MutableStateFlow(StreamingState())
    private var appContext: Context? = null
    private var lastServiceGeneration = 0
    val state: StateFlow<StreamingState> = mutableState.asStateFlow()

    /** Called once by each newly created service; state from older services becomes stale. */
    internal fun consumeServiceGeneration(): Int {
        lastServiceGeneration += 1
        return lastServiceGeneration
    }

    /** A generation that arrived after an explicit stop/close must not revive the player. */
    internal fun isCurrentServiceGeneration(generation: Int): Boolean =
        generation == lastServiceGeneration

    fun playBot(context: Context, video: BotVideo, startPositionMs: Long = 0L) {
        requireMainThread()
        appContext = context.applicationContext
        mutableState.value = StreamingState(
            source = PlaybackSource.BOT,
            title = video.displayName,
            resolving = true,
            botFileId = video.fileId,
            sourceBytes = video.bytes,
            buffering = true,
            artworkPath = video.thumbnailPath,
            artworkBase64 = video.thumbnailBase64,
        )
        context.startService(Intent(context, StreamingAudioService::class.java)
            .setAction(BotRuntime.ACTION_PLAY_TDLIB)
            .putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, video.fileId)
            .putExtra(BotRuntime.EXTRA_TDLIB_NAME, video.displayName)
            .putExtra(BotRuntime.EXTRA_TDLIB_SIZE, video.bytes)
            .putExtra(StreamingAudioService.EXTRA_ARTWORK_PATH, video.thumbnailPath)
            .putExtra(StreamingAudioService.EXTRA_ARTWORK_BASE64, video.thumbnailBase64)
            // A resume carries its position inside the start request; the service arms it before
            // prepare, so it is never a follow-up seek on an unprepared player.
            .apply { if (startPositionMs > 0L) putExtra(StreamingAudioService.EXTRA_POSITION, startPositionMs) })
    }

    /** Publish the selected identity while TDLib restores its file asynchronously. */
    internal fun beginBotResolution(context: Context, video: BotVideo) {
        requireMainThread()
        appContext = context.applicationContext
        mutableState.value = StreamingState(
            source = PlaybackSource.BOT,
            title = video.displayName,
            resolving = true,
            buffering = true,
            botFileId = video.fileId,
            sourceBytes = video.bytes,
            artworkPath = video.thumbnailPath,
            artworkBase64 = video.thumbnailBase64,
        )
    }

    internal fun failBotResolution(fileId: Int, message: String) {
        requireMainThread()
        val current = mutableState.value
        if (current.source != PlaybackSource.BOT || current.botFileId != fileId || !current.resolving) return
        mutableState.value = current.copy(resolving = false, buffering = false, error = message)
    }

    fun playLocal(context: Context, result: AudioResult) {
        requireMainThread()
        BotRuntime.cancelPendingPlayback()
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
            resolving = true,
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
    fun resume() {
        requireMainThread()
        if (mutableState.value.resolving) return
        BotRuntime.cancelPendingPlayback()
        command(StreamingAudioService.ACTION_RESUME)
    }

    /**
     * A user resume of a paused session after the app stopped serving it. The request names the
     * media itself, so it works in a fresh process where no service state survived. Nothing is
     * published before the service answers; the identity is only used by the caller.
     */
    internal fun resumeRestored(context: Context, spec: StreamingAudioService.ResumeSpec, identity: StreamingState) {
        requireMainThread()
        BotRuntime.cancelPendingPlayback()
        appContext = context.applicationContext
        if (spec.botFileId != null) {
            // The request carries the same identity the service re-opens (see beginBot).
            mutableState.value = StreamingState(
                source = PlaybackSource.BOT,
                title = spec.title.orEmpty(),
                resolving = true,
                buffering = true,
                botFileId = spec.botFileId,
                sourceBytes = spec.botBytes,
                artworkPath = spec.artworkPath,
                artworkBase64 = spec.artworkBase64,
            )
        } else {
            // A local play request publishes its own preparing state; keep the paused artwork.
            mutableState.value = identity
        }
        context.startService(StreamingAudioService.resumeIntent(context, spec))
    }
    fun stop() {
        requireMainThread()
        BotRuntime.cancelPendingPlayback()
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
