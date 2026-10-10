package io.github.polarfox0317.sikboonge

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.core.content.FileProvider

/** Test seam for Bot media access; local playback uses the production content resolver. */
internal object StreamingTestHooks {
    @Volatile var tdlibAccessOverride: TdlibFileAccess? = null
}

@OptIn(UnstableApi::class)
class StreamingAudioService : MediaSessionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionTicker = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            publishPlayerState(activePlayer)
            if (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(this, POSITION_UPDATE_MS)
            }
        }
    }
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var hasSelectedAudio = false
    private var currentBotFileId: Int? = null
    private var currentTitle = "Telegram 오디오"
    private var currentLocalAudioPath: String? = null
    private var currentLocalAudioMime: String? = null
    private var currentArtworkPath: String? = null
    private var currentArtworkBase64: String = ""
    @Volatile private var localMediaUri: Uri? = null
    private var currentSourceBytes = 0L
    private var leasedBotFileId: Int? = null
    private var botAccess: TdlibFileAccess = BotRuntime

    override fun onCreate() {
        super.onCreate()
        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        trackSelector = selector
        botAccess = StreamingTestHooks.tdlibAccessOverride ?: BotRuntime
        val localFactory = DefaultDataSource.Factory(this)
        val sourceFactory = DataSource.Factory {
            StreamingDataSource(TdlibMediaDataSource.Factory(botAccess), localFactory) { uri ->
                uri == localMediaUri
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                MIN_STREAM_BUFFER_MS,
                MAX_STREAM_BUFFER_MS,
                STARTUP_BUFFER_MS,
                REBUFFER_STARTUP_BUFFER_MS
            )
            .build()
        val activePlayer = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
            }
        player = activePlayer
        val sessionPlayer = object : ForwardingPlayer(activePlayer) {
            override fun play() {
                if (activePlayer.playbackState == Player.STATE_ENDED) activePlayer.seekToDefaultPosition()
                super.play()
            }

            override fun stop() {
                stopPlayback()
            }
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val createdSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(appPendingIntent)
            .setCallback(mediaSessionCallback)
            .build()
        mediaSession = createdSession
        addSession(createdSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppDiagnostics.event("playback", "action=${intent?.action.orEmpty()}")
        val baseResult = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_LOCAL -> beginLocalAudio(intent)
            BotRuntime.ACTION_PLAY_TDLIB -> beginBot(intent)
            BotRuntime.ACTION_STOP_TDLIB -> if (currentBotFileId != null) stopPlayback()
            ACTION_PAUSE -> player?.pause()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> player?.let { activePlayer ->
                val requested = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
                val duration = activePlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                activePlayer.seekTo(duration?.let { requested.coerceAtMost(it) } ?: requested)
            }
        }
        val handled = when (intent?.action) {
            ACTION_PLAY_LOCAL, ACTION_PAUSE, ACTION_RESUME, ACTION_STOP, ACTION_SEEK, BotRuntime.ACTION_PLAY_TDLIB, BotRuntime.ACTION_STOP_TDLIB -> true
            else -> false
        }
        return if (handled) START_NOT_STICKY else baseResult
    }

    private fun beginBot(intent: Intent) {
        val fileId = intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0)
        val bytes = intent.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0)
        if (fileId <= 0 || bytes < 0L) {
            fail("봇 영상 정보를 확인할 수 없어요."); return
        }
        player?.apply { stop(); clearMediaItems() }
        releaseBotLease()
        currentBotFileId = fileId
        currentLocalAudioPath = null
        currentLocalAudioMime = null
        currentArtworkPath = intent.getStringExtra(EXTRA_ARTWORK_PATH)
        currentArtworkBase64 = intent.getStringExtra(EXTRA_ARTWORK_BASE64).orEmpty()
        localMediaUri = null
        currentTitle = intent.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME).orEmpty().ifBlank { "Telegram 영상" }
        currentSourceBytes = bytes
        botAccess.beginPlayback(fileId)
        leasedBotFileId = fileId
        startMedia(Uri.parse("tdlib://file/$fileId"))
    }

    private fun beginLocalAudio(intent: Intent) {
        val file = LocalPlaybackPolicy.ownedAudioFile(
            filesDir,
            intent.getStringExtra(EXTRA_LOCAL_AUDIO_PATH)
        )
        if (file == null) {
            stopPlayback()
            StreamPlayback.publish(StreamingState(
                source = PlaybackSource.LOCAL_AUDIO,
                title = "Telegram 오디오",
                error = LocalPlaybackPolicy.INVALID_AUDIO_ERROR
            ))
            return
        }
        val contentUri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }.getOrNull()
        if (contentUri == null) {
            stopPlayback()
            StreamPlayback.publish(StreamingState(
                source = PlaybackSource.LOCAL_AUDIO,
                title = file.name,
                error = LocalPlaybackPolicy.INVALID_AUDIO_ERROR
            ))
            return
        }

        player?.apply { stop(); clearMediaItems() }
        releaseBotLease()
        currentBotFileId = null
        currentLocalAudioPath = file.absolutePath
        currentLocalAudioMime = LocalPlaybackPolicy.audioMime(intent.getStringExtra(EXTRA_LOCAL_AUDIO_MIME))
        currentArtworkPath = intent.getStringExtra(EXTRA_ARTWORK_PATH)
        currentArtworkBase64 = ""
        localMediaUri = contentUri
        currentTitle = intent.getStringExtra(EXTRA_LOCAL_AUDIO_TITLE)?.takeIf { it.isNotBlank() } ?: file.name
        currentSourceBytes = file.length()
        hasSelectedAudio = false
        startMedia(contentUri, currentLocalAudioMime)
    }

    private fun releaseBotLease() {
        leasedBotFileId?.let { botAccess.endPlayback(it) }
        leasedBotFileId = null
    }

    private fun startMedia(mediaItemUri: Uri, mimeType: String? = null) {
        val activePlayer = player ?: return
        hasSelectedAudio = false
        StreamPlayback.publish(StreamingState(
            source = if (currentBotFileId != null) PlaybackSource.BOT else PlaybackSource.LOCAL_AUDIO,
            title = currentTitle,
            buffering = true,
            botFileId = currentBotFileId,
            sourceBytes = currentSourceBytes,
            localAudioPath = currentLocalAudioPath,
            localAudioMime = currentLocalAudioMime,
            artworkPath = currentArtworkPath,
            artworkBase64 = currentArtworkBase64,
        ))
        val itemBuilder = MediaItem.Builder()
            .setUri(mediaItemUri)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(currentTitle)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build())
        mimeType?.let(itemBuilder::setMimeType)
        val item = itemBuilder.build()
        activePlayer.setMediaItem(item)
        activePlayer.prepare()
        activePlayer.play()
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.post(positionTicker)
    }

    private fun resumePlayback() {
        val activePlayer = player ?: return
        if (activePlayer.mediaItemCount > 0) {
            if (activePlayer.playbackState == Player.STATE_ENDED) activePlayer.seekToDefaultPosition()
            activePlayer.play()
            schedulePositionTicker()
            return
        }
        val lastState = StreamPlayback.state.value
        if (lastState.botFileId != null) {
            beginBot(Intent().putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, lastState.botFileId)
                .putExtra(BotRuntime.EXTRA_TDLIB_NAME, lastState.title)
                .putExtra(BotRuntime.EXTRA_TDLIB_SIZE, lastState.sourceBytes)
                .putExtra(EXTRA_ARTWORK_PATH, lastState.artworkPath)
                .putExtra(EXTRA_ARTWORK_BASE64, lastState.artworkBase64))
            return
        }
        if (lastState.localAudioPath != null) {
            beginLocalAudio(Intent()
                .putExtra(EXTRA_LOCAL_AUDIO_PATH, lastState.localAudioPath)
                .putExtra(EXTRA_LOCAL_AUDIO_MIME, lastState.localAudioMime))
            return
        }
    }

    private fun stopPlayback() {
        player?.apply { stop(); clearMediaItems() }
        releaseBotLease()
        currentBotFileId = null
        currentLocalAudioPath = null
        currentLocalAudioMime = null
        currentArtworkPath = null
        currentArtworkBase64 = ""
        localMediaUri = null
        currentTitle = "Telegram 오디오"
        currentSourceBytes = 0L
        mainHandler.removeCallbacks(positionTicker)
        hasSelectedAudio = false
        StreamPlayback.publish(StreamingState())
        stopSelf()
    }

    private fun fail(message: String) {
        player?.apply { stop(); clearMediaItems() }
        releaseBotLease()
        mainHandler.removeCallbacks(positionTicker)
        hasSelectedAudio = false
        StreamPlayback.publish(
            StreamPlayback.state.value.copy(
                resolving = false,
                playing = false,
                buffering = false,
                ready = false,
                error = message
            )
        )
        stopSelf()
    }

    private fun publishPlayerState(activePlayer: ExoPlayer?) {
        if (activePlayer == null) return
        val previous = StreamPlayback.state.value
        val duration = activePlayer.duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L
        val position = when {
            duration > 0L && activePlayer.playbackState == Player.STATE_ENDED -> duration
            duration > 0L -> activePlayer.currentPosition.coerceIn(0L, duration)
            else -> activePlayer.currentPosition.coerceAtLeast(0L)
        }
        StreamPlayback.publish(
            previous.copy(
                source = when {
                    currentBotFileId != null -> PlaybackSource.BOT
                    currentLocalAudioPath != null -> PlaybackSource.LOCAL_AUDIO
                    else -> previous.source
                },
                title = currentTitle,
                playing = activePlayer.isPlaying && hasSelectedAudio,
                buffering = activePlayer.playbackState == Player.STATE_BUFFERING,
                ready = hasSelectedAudio && activePlayer.playbackState != Player.STATE_IDLE,
                positionMs = position,
                durationMs = duration,
                localAudioPath = currentLocalAudioPath,
                localAudioMime = currentLocalAudioMime,
                artworkPath = currentArtworkPath,
                artworkBase64 = currentArtworkBase64,
                ended = activePlayer.playbackState == Player.STATE_ENDED
            )
        )
    }

    private fun schedulePositionTicker() {
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.post(positionTicker)
    }

    private fun hasAudioTrack(tracks: Tracks): Boolean = tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_AUDIO && group.isSelected
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val activePlayer = player ?: return
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                hasSelectedAudio = hasAudioTrack(activePlayer.currentTracks)
                if (!hasSelectedAudio) {
                    fail("이 영상에는 오디오가 없어요.")
                    return
                }
            }
            publishPlayerState(activePlayer)
            if (playbackState == Player.STATE_BUFFERING || activePlayer.isPlaying) schedulePositionTicker()
            else mainHandler.removeCallbacks(positionTicker)
            if (playbackState == Player.STATE_ENDED) mainHandler.removeCallbacks(positionTicker)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val activePlayer = player ?: return
            val hasAnyAudioTrack = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
            if (tracks.groups.isNotEmpty() && !hasAnyAudioTrack) {
                fail("이 영상에는 오디오가 없어요.")
                return
            }
            if (activePlayer.playbackState == Player.STATE_READY || activePlayer.playbackState == Player.STATE_ENDED) {
                hasSelectedAudio = hasAudioTrack(tracks)
                if (!hasSelectedAudio) {
                    fail("이 영상에는 오디오가 없어요.")
                    return
                }
            }
            publishPlayerState(activePlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            player?.let { activePlayer ->
                publishPlayerState(activePlayer)
                if (isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING) schedulePositionTicker()
                else mainHandler.removeCallbacks(positionTicker)
            }
        }

        override fun onEvents(eventPlayer: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                publishPlayerState(player)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppDiagnostics.failure("playback", error)
            fail(if (currentLocalAudioPath != null) {
                LocalPlaybackPolicy.INVALID_AUDIO_ERROR
            } else {
                "오디오를 재생하지 못했어요. Telegram 연결을 확인하고 다시 시도해 주세요."
            })
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(positionTicker)
        player?.removeListener(playerListener)
        StreamPlayback.publish(
            StreamPlayback.state.value.copy(
                resolving = false,
                playing = false,
                buffering = false,
                ready = false
            )
        )
        player?.release()
        releaseBotLease()
        player = null
        mediaSession?.release()
        mediaSession = null
        trackSelector = null
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_PLAY_LOCAL = "io.github.polarfox0317.sikboonge.stream.PLAY_LOCAL"
        internal const val ACTION_PAUSE = "io.github.polarfox0317.sikboonge.stream.PAUSE"
        internal const val ACTION_RESUME = "io.github.polarfox0317.sikboonge.stream.RESUME"
        internal const val ACTION_STOP = "io.github.polarfox0317.sikboonge.stream.STOP"
        internal const val ACTION_SEEK = "io.github.polarfox0317.sikboonge.stream.SEEK"
        internal const val EXTRA_POSITION = "position_ms"
        internal const val EXTRA_LOCAL_AUDIO_PATH = "local_audio_path"
        internal const val EXTRA_LOCAL_AUDIO_MIME = "local_audio_mime"
        internal const val EXTRA_LOCAL_AUDIO_TITLE = "local_audio_title"
        internal const val EXTRA_ARTWORK_PATH = "artwork_path"
        internal const val EXTRA_ARTWORK_BASE64 = "artwork_base64"
        private const val POSITION_UPDATE_MS = 250L
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val MIN_STREAM_BUFFER_MS = 5_000
        private const val MAX_STREAM_BUFFER_MS = 30_000
        private const val STARTUP_BUFFER_MS = 1_000
        private const val REBUFFER_STARTUP_BUFFER_MS = 1_500
        internal fun localAudioIntent(context: Context, path: String, mime: String, artworkPath: String? = null, title: String? = null): Intent =
            Intent(context, StreamingAudioService::class.java)
                .setAction(ACTION_PLAY_LOCAL)
                .putExtra(EXTRA_LOCAL_AUDIO_PATH, path)
                .putExtra(EXTRA_LOCAL_AUDIO_MIME, mime)
                .putExtra(EXTRA_ARTWORK_PATH, artworkPath)
                .putExtra(EXTRA_LOCAL_AUDIO_TITLE, title)
    }
}
