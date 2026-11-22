package io.github.polarfox0317.sikboonge.ui.state

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.polarfox0317.sikboonge.AppStore
import io.github.polarfox0317.sikboonge.AudioArtworkStore
import io.github.polarfox0317.sikboonge.AudioFormat
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotCredentials
import io.github.polarfox0317.sikboonge.BotRuntime
import io.github.polarfox0317.sikboonge.ReconnectStart
import io.github.polarfox0317.sikboonge.BotPrivateStore
import io.github.polarfox0317.sikboonge.BotUiState
import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.TagPolicy
import io.github.polarfox0317.sikboonge.ConversionService
import io.github.polarfox0317.sikboonge.DownloadExecutionMode
import io.github.polarfox0317.sikboonge.DownloadExecutionPreferences
import io.github.polarfox0317.sikboonge.ui.settings.licenses.OpenSourceLicenses
import io.github.polarfox0317.sikboonge.ConversionState
import io.github.polarfox0317.sikboonge.ExternalPlayback
import io.github.polarfox0317.sikboonge.LibraryDownload
import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.safeDisplayName
import io.github.polarfox0317.sikboonge.PlaybackQueueTransport
import io.github.polarfox0317.sikboonge.QueueTransportAvailability
import io.github.polarfox0317.sikboonge.LocalPlaybackPolicy
import io.github.polarfox0317.sikboonge.StreamPlayback
import io.github.polarfox0317.sikboonge.StreamingAudioService
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.UserFacingErrors
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-scoped state owner. Construction/rebinding never starts playback or conversion. */
class AppController(
    context: Context,
    scope: CoroutineScope? = null,
    private val botState: StateFlow<BotUiState> = BotRuntime.state,
    private val libraryState: StateFlow<ConversionState> = AppStore.state,
    private val libraryHistory: StateFlow<List<AudioResult>> = AppStore.history,
) : ViewModel(), AppActions {
    private val appContext = context.applicationContext
    private val savedUiPreferences = appContext.getSharedPreferences("sikboonge_ui_preferences", Context.MODE_PRIVATE)
    private val playbackQueueStore = PlaybackQueueStore(appContext)
    private val controllerJob = SupervisorJob(scope?.coroutineContext?.get(Job))
    private val controllerScope = CoroutineScope(Dispatchers.Main.immediate + controllerJob)
    private val interaction = MutableStateFlow(InteractionState(
        saveFormat = AudioFormat.entries.firstOrNull { it.name == savedUiPreferences.getString("save_format", null) } ?: AudioFormat.ORIGINAL,
        downloadExecutionMode = DownloadExecutionPreferences.load(appContext),
        libraryLayout = ListLayout.entries.firstOrNull { it.name == savedUiPreferences.getString("library_layout", null) } ?: ListLayout.List,
        botLayout = ListLayout.entries.firstOrNull { it.name == savedUiPreferences.getString("bot_layout", null) } ?: ListLayout.List,
        notificationsAllowed = notificationsAllowed(),
    ))
    private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 64)
    private var hostEffects: HostEffects? = null
    private var handledIncomingEventToken = 0
    private var lastPlayerSourceKey = sourceKey(StreamPlayback.state.value)
    private val naturalEndEdge = QueueNaturalEndEdge(StreamPlayback.state.value.ended)
    private var hostWasBound = false
    private var selectedVideoGeneration = 0L
    private var pendingConversionUri: String? = null
    private var preservePlayerSizeSourceKey: String? = null
    private var preservePlayerSizeBotQueueKey: String? = null
    private var lastAnnouncedDownloadCancellationId = AppStore.cancelledDownloadId.value
    private val pendingNotificationPermissionActions = mutableListOf<(Boolean) -> Unit>()
    private var notificationPermissionRequestInFlight = false
    // Paused positions survive a restarted service until this instance resumes or closes them.
    private val pausedSourcePositions = mutableMapOf<String, PausedSource>()
    /** Media whose resume point was cleared by an explicit close; released by the next queue add. */
    private val closedQueueMediaKeys = mutableSetOf<String>()
    private var pausedCurrentSourceKey: String? = null
    // A resume was sent but the service has not reported playback for it yet.
    private var pendingResumedKey: String? = null
    private var pendingResumedPositionMs: Long = 0L
    private var lastObservedServiceGeneration = StreamPlayback.state.value.serviceGeneration
    // Saved-state restore is resolved lazily while list owners publish rows asynchronously.
    private var restoredQueueSpec: QueueRestoreSpec? = null
    private var restoredQueue: List<QueueItemUi> = emptyList()
    private var lastPersistedPlaybackSnapshot: PlaybackQueueSnapshot? = playbackQueueStore.read()
    // Media resume points survive process death; explicit queue close or deletion purges affected keys.
    private val bookmarkStore = PlaybackBookmarkStore(appContext)
    private val bookmarks = PlaybackBookmarkSession(
        PlaybackBookmarkCodec.seedFromSnapshot(
            existing = bookmarkStore.read(),
            pausedKey = lastPersistedPlaybackSnapshot?.pausedKey,
            pausedPositionMs = lastPersistedPlaybackSnapshot?.pausedPositionMs ?: 0L,
            pausedDurationMs = lastPersistedPlaybackSnapshot?.pausedDurationMs ?: 0L,
        ),
    )
    // The explicit play waiting on a continue/restart choice, kept so a re-entrant tap is ignored.
    private var pendingResumeTarget: QueueItemUi? = null
    // Deleting a queue row tombstones only that occurrence; a queued copy of the same media survives.
    private val deletedEntryIds = mutableSetOf<String>()
    // Deleting the media itself (library/bot removal) tombstones its media key for every occurrence.
    private val deletedMediaKeys = mutableSetOf<String>()
    private var deletedVersion = 0L
    private var lastBookmarkWriteAt = 0L
    // The last sample the collector accepted, kept so a stream -> NONE transition can flush it
    // instead of losing up to one throttle window of playback position.
    private var lastBookmarkCandidate: BookmarkCandidate? = null
    // An explicit Bot play waiting for Telegram to reconnect. The engine owns its single-instance
    // lifecycle, and the one timeout job below bounds the wait so a spinner never outlives it.
    private val pendingBotPlayback = BotPendingPlaybackEngine(
        nowMs = { android.os.SystemClock.elapsedRealtime() },
    )
    private var botPlaybackTimeoutJob: Job? = null
    internal var botPlaybackRecovery: BotPlaybackRecovery = TelegramBotPlaybackRecovery
    internal var botPlaybackWaitMs: Long = BotPendingPlaybackEngine.DEFAULT_TIMEOUT_MS

    /**
     * The current playable item and its playhead. Only a live READY source or a real end counts.
     *
     * A preparing session reports position 0 with no duration yet; recording it would overwrite a
     * real saved spot, so resolving or not-ready states produce no candidate at all.
     */
    private fun bookmarkCandidate(input: InteractionState, stream: StreamingState): BookmarkCandidate? {
        if (stream.source == PlaybackSource.NONE) return null
        if (isSeparatedPausedStream(stream)) {
            val pausedKey = pausedQueueKey(stream, input.queue).ifEmpty { return null }
            val paused = pausedSourcePositions[pausedKey] ?: return null
            if (paused.positionMs <= 0L) return null
            return BookmarkCandidate(pausedKey, paused.positionMs, stream.durationMs, playing = false, ended = false)
        }
        // A real end is always recordable; anything else must be a ready, settled session.
        val ready = stream.ready && !stream.resolving && !stream.buffering
        if (!ready && !stream.ended) return null
        if (!stream.ended && stream.durationMs <= 0L) return null
        val key = pausedQueueKey(stream, input.queue).ifEmpty { return null }
        return BookmarkCandidate(
            key = key,
            positionMs = playheadPositionMs(stream),
            durationMs = stream.durationMs,
            playing = stream.playing,
            ended = stream.ended,
        )
    }

    private fun recordBookmark(candidate: BookmarkCandidate) {
        // A closed queue is final: a late sample from the just-stopped stream cannot recreate it.
        if (candidate.key in closedQueueMediaKeys) return
        if (!bookmarks.record(candidate.key, candidate.positionMs, candidate.durationMs, candidate.ended)) return
        persistBookmarks()
    }

    private fun persistBookmarks() = bookmarkStore.write(bookmarks.snapshot())

    /**
     * Writes the last observed playhead when the stream disappears. This is a pause-shaped sample,
     * not an end: an item that is already marked complete is skipped entirely so a late sample can
     * never clear its completed flag.
     */
    private fun flushBookmark(candidate: BookmarkCandidate) {
        if (bookmarks.of(candidate.key)?.completed == true) return
        recordBookmark(candidate.copy(playing = false, ended = false))
    }

    /** One observable playhead sample for the bookmark writer. */
    private data class BookmarkCandidate(
        val key: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val ended: Boolean,
    )

    val actions: AppActions = this
    val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()
    private val libraryRows = combine(libraryHistory, AppStore.downloads) { history, downloads ->
        history to downloads
    }
    val uiState: StateFlow<AppUiState> = combine(
        interaction, libraryState, libraryRows, StreamPlayback.state, botState,
    ) { input, conversion, library, stream, bot ->
        input.snapshot(conversion, library.first, library.second, stream, bot, pausedDisplayOf(stream))
    }.stateIn(
        controllerScope, SharingStarted.Eagerly,
        interaction.value.snapshot(
            AppStore.state.value,
            AppStore.history.value,
            AppStore.downloads.value,
            StreamPlayback.state.value,
            botState.value,
        ),
    )

    init {
        restorePersistedPlaybackSession()
        PlaybackQueueTransport.bind(
            owner = this,
            priority = 0,
            availability = QueueTransportAvailability(),
            previous = { onPlayPrevious(); true },
            next = { onPlayNext(); true },
        )
        controllerScope.launch {
            combine(interaction, StreamPlayback.state) { input, stream ->
                queueTransportAvailability(input.queue, input.queueIndex, input.queueResumeIndex, stream.positionMs)
            }.collect { PlaybackQueueTransport.update(this@AppController, it) }
        }
        controllerScope.launch {
            combine(interaction, StreamPlayback.state, ::playbackSnapshotForPersistence)
                .distinctUntilChanged()
                .collect { snapshot ->
                    lastPersistedPlaybackSnapshot = snapshot
                    playbackQueueStore.write(snapshot)
                }
        }
        controllerScope.launch {
            // Event-driven bookmarks: every settle/pause/end writes immediately, and ongoing
            // position updates are throttled to one write per window. No new polling loop.
            combine(interaction, StreamPlayback.state, ::bookmarkCandidate)
                .distinctUntilChanged()
                .collect { candidate ->
                    if (candidate == null) {
                        // The stream just went away (stop, teardown or a detached session). Flush the
                        // last accepted sample as a paused position so the playhead is not lost to
                        // the throttle window. A tombstoned key is ignored inside the session, and a
                        // key already marked complete keeps its flag, so this never un-completes a
                        // finished item.
                        lastBookmarkCandidate?.let { pending ->
                            lastBookmarkCandidate = null
                            flushBookmark(pending)
                        }
                        lastBookmarkWriteAt = 0L
                        return@collect
                    }
                    val settled = !candidate.playing || candidate.ended
                    val now = System.currentTimeMillis()
                    // Remember the newest sample before the throttle so the flush uses the latest
                    // position rather than the last written one.
                    lastBookmarkCandidate = candidate
                    if (!settled && now - lastBookmarkWriteAt < BOOKMARK_WRITE_INTERVAL_MS) return@collect
                    lastBookmarkWriteAt = now
                    recordBookmark(candidate)
                }
        }
        controllerScope.launch {
            combine(libraryState, AppStore.downloads, botState, StreamPlayback.state) { conversion, downloads, bot, stream ->
                SavePreparationInputs(conversion, downloads, bot, stream)
            }.collect { inputs ->
                    val conversion = inputs.conversion
                    val downloads = inputs.downloads
                    val bot = inputs.bot
                    val stream = inputs.stream
                    interaction.update { input ->
                        input.copy(
                            preparingBotSaveSources = input.preparingBotSaveSources.filter { (messageId, fileId) ->
                                bot.connected && bot.error == null &&
                                bot.videos.any { it.messageId == messageId && it.fileId == fileId } &&
                                    downloads.none { download -> download.sourceBotFileId == fileId }
                            },
                            preparingPlayerSaveSourceKey = input.preparingPlayerSaveSourceKey.takeIf { pendingSourceKey ->
                                sourceKey(stream) == pendingSourceKey && bot.connected && bot.error == null &&
                                    downloads.none { it.sourceBotFileId == stream.botFileId }
                            },
                            botSetupRequested = input.botSetupRequested && !bot.connected,
                        )
                    }
                }
        }
        controllerScope.launch {
            StreamPlayback.state.collect { stream ->
                // State from a service generation that began after an explicit close is stale.
                if (stream.serviceGeneration != lastObservedServiceGeneration) {
                    lastObservedServiceGeneration = stream.serviceGeneration
                    if (!StreamPlayback.isCurrentServiceGeneration(stream.serviceGeneration)) return@collect
                }
                if (stream.source != PlaybackSource.BOT && pendingBotPlayback.isPending) cancelPendingBotPlayback()
                observePlayback(stream)
                val naturalEnd = naturalEndEdge.observe(stream.ended)
                if (PlaybackQueueTransport.owns(this@AppController) && naturalEnd) {
                    val input = interaction.value
                    val key = input.queue.getOrNull(input.queueIndex)?.key
                    // The ended occurrence decides the next row; a duplicate's media key would rewind.
                    val entryId = input.queue.getOrNull(input.queueIndex)?.entryId
                    when (val action = queueNaturalEndAction(
                        input.queue,
                        entryId,
                        endedSourceMatchesCurrent = key != null && queueItemMatches(input.queue[input.queueIndex], stream),
                    )) {
                        is QueueNaturalEndAction.PlayNext -> {
                            playQueueItem(action.index, direction = 1)
                            return@collect
                        }
                        QueueNaturalEndAction.Stop -> {
                            interaction.update { it.copy(queueIndex = -1, queueResumeIndex = it.queue.size, emptyPlayerVisible = false) }
                            StreamPlayback.stop()
                            return@collect
                        }
                        null -> Unit
                    }
                }
                val key = sourceKey(stream)
                val sourceChanged = key != lastPlayerSourceKey
                val botQueueKey = preservePlayerSizeBotQueueKey
                val selectedBotQueueKey = botQueueKey?.takeIf { queuedKey ->
                    sourceChanged && interaction.value.queue.getOrNull(interaction.value.queueIndex)?.key == queuedKey
                }
                // TDLib can assign a new file ID while resolving the same queued message.
                val sameQueuedBotMessage = botQueueKey != null && stream.source == PlaybackSource.BOT &&
                    (botState.value.videos.any { it.fileId == stream.botFileId && queueItem(it).key == botQueueKey } ||
                        selectedBotQueueKey != null)
                val keepPlayerSize = key == preservePlayerSizeSourceKey || sameQueuedBotMessage
                /*
                 * A restored paused session publishes a detached shim, and that first publication
                 * is a source change like any other. It must not expand the player: only a real
                 * user play may open the full player, so a cold or detached source keeps the mini
                 * player at whatever size the restore decision chose.
                 */
                val restoredOrDetached = stream.serviceDetached
                // Keep the queued identity through TDLib resolution; it may replace fileId after
                // this first source publication, and the resolved state still owns the size handoff.
                if ((keepPlayerSize || sourceChanged) && !stream.resolving) clearQueuePlaybackPreference()
                if (sourceChanged) {
                    lastPlayerSourceKey = key
                    interaction.update { input ->
                        /*
                         * A stop clears the live source but must not move the saved position to
                         * the first row. The resume index stays on the item the close left behind;
                         * deleting rows is the separate, explicit path that changes indices.
                         */
                        if (key.isEmpty()) {
                            val keptIndex = input.queueResumeIndex
                                .takeIf { it in input.queue.indices }
                                ?: lastPersistedPlaybackSnapshot?.pausedKey
                                    ?.let { saved -> input.queue.indexOfFirst { it.key == saved } }
                                    ?.takeIf { it >= 0 }
                                ?: input.queueIndex.coerceIn(0, input.queue.size)
                            input.copy(
                                playerExpanded = true,
                                queueIndex = -1,
                                queueResumeIndex = keptIndex,
                                queueVisible = false,
                                queueEditing = false,
                                queueSelectedKeys = emptySet(),
                            )
                        }
                        else {
                            val resolvedBotKeys = if (stream.source == PlaybackSource.BOT) {
                                botState.value.videos.filter { it.fileId == stream.botFileId }
                                    .mapTo(mutableSetOf()) { queueItem(it).key }
                                    .apply { selectedBotQueueKey?.let(::add) }
                            } else emptySet()
                            val queue = input.queue.map { item ->
                                if (item.key in resolvedBotKeys) item.copy(botFileId = stream.botFileId) else item
                            }
                            val index = input.queueIndex.takeIf { i -> queue.getOrNull(i)?.let { queueItemMatches(it, stream) } == true }
                                ?: queue.indexOfFirst { queueItemMatches(it, stream) }
                            val expanded = if (keepPlayerSize) input.playerExpanded else !restoredOrDetached
                            if (index >= 0) input.copy(
                                playerExpanded = expanded,
                                emptyPlayerVisible = false,
                                queue = queue,
                                queueIndex = index,
                                queueResumeIndex = index,
                            ) else input.copy(
                                playerExpanded = expanded,
                                emptyPlayerVisible = false,
                                queue = queue,
                                queueIndex = -1,
                                queueResumeIndex = input.queueResumeIndex.coerceIn(0, queue.size),
                            )
                        }
                    }
                }
            }
        }
        controllerScope.launch {
            botState.map { it.videos }.distinctUntilChanged().collect { videos ->
                if (videos.isNotEmpty()) withContext(Dispatchers.IO) {
                    // Same migration as the former LaunchedEffect(bot.videos); no fixture branches.
                    runCatching { AppStore.attachMissingArtwork(appContext, videos) }
                }
                resolveRestoredQueue()
            }
        }
        controllerScope.launch {
            // Every signal a waiting Bot play depends on, including the list content and file ids that
            // a reconnect reissues. Watching only counts or a couple of flags would miss real changes.
            botState.collect { observePendingBotPlayback() }
        }
        controllerScope.launch {
            libraryRows.collect {
                resolveRestoredQueue()
            }
        }
        controllerScope.launch {
            // A loaded inbox can carry unchanged videos; the owner must still be marked as loaded.
            botState.map { it.initialStateLoaded }.distinctUntilChanged().collect {
                resolveRestoredQueue()
            }
        }
        controllerScope.launch {
            var initialEmission = true
            val announcedDiagnostics = mutableSetOf<String>()
            botState.map { it.receiveDiagnostic.trim() }.distinctUntilChanged().collect { diagnostic ->
                if (initialEmission) {
                    initialEmission = false
                    if (diagnostic.isNotBlank()) announcedDiagnostics.add(diagnostic)
                } else if (diagnostic.isNotBlank() && announcedDiagnostics.add(diagnostic)) {
                    // Reconnect progress during an internal Bot wait is not a user-facing notice.
                    if (!pendingBotPlayback.isPending && !isSuppressingInternalBotError(StreamPlayback.state.value)) notice("수신 확인 · $diagnostic")
                }
            }
        }
        controllerScope.launch {
            botState.map { it.error }.distinctUntilChanged().collect { error ->
                // The internal reconnect-and-replay wait is never announced; its failure is inline.
                if (pendingBotPlayback.isPending || isSuppressingInternalBotError(StreamPlayback.state.value)) return@collect
                error?.let { notice(it, true, UserFacingErrors.Context.TELEGRAM) }
            }
        }
        controllerScope.launch {
            StreamPlayback.state.map { it.error to (it.localAudioPath != null) }.distinctUntilChanged()
                .collect { (error, local) ->
                    // The Bot wait's own error stays in the player; every other error keeps its toast.
                    if (isSuppressingInternalBotError(StreamPlayback.state.value)) return@collect
                    error?.let {
                        val current = StreamPlayback.state.value
                        val context = when {
                            local || current.source == PlaybackSource.LOCAL_AUDIO -> UserFacingErrors.Context.LOCAL_MEDIA
                            current.source == PlaybackSource.BOT -> UserFacingErrors.Context.TELEGRAM
                            else -> UserFacingErrors.Context.GENERAL
                        }
                        notice(it, true, context)
                    }
                }
        }
        controllerScope.launch {
            AppStore.botSaveCompletions.collect {
                notice("오디오를 보관함에 저장했어요.")
                }
        }
        controllerScope.launch {
            AppStore.cancelledDownloadId.collect { downloadId ->
                if (downloadId != null && downloadId != lastAnnouncedDownloadCancellationId) {
                    lastAnnouncedDownloadCancellationId = downloadId
                    notice("다운로드를 중지했어요.")
                }
            }
        }
    }

    /** Called by HostBindings only; the disposable binding prevents retaining the destroyed Activity. */
    fun bindHostEffects(effects: HostEffects) {
        hostWasBound = true
        hostEffects = effects
        interaction.update { it.copy(diagnosticsAvailable = effects.diagnosticsAvailable, notificationsAllowed = notificationsAllowed()) }
    }

    fun unbindHostEffects(effects: HostEffects) {
        if (hostEffects === effects) hostEffects = null
    }

    /** Presentation preference shared by the shell and player actions. */
    override fun setPlayerExpanded(expanded: Boolean) {
        interaction.update { it.copy(playerExpanded = expanded) }
    }

    /** Diagnostic/launcher feedback passes through the same scrubber as runtime notices. */
    fun onHostFeedback(text: String, isError: Boolean) = notice(text, isError)

    /** Save only the values formerly in rememberSaveable; credentials and operation targets are excluded. */
    internal fun saveRestorableState(): Bundle = Bundle().apply {

        val input = interaction.value
        putString("destination", when (input.destination) {
            Destination.Convert -> "convert"
            Destination.Library -> "library"
            Destination.Bot -> "bot"
            Destination.Settings -> "settings"
        })
        putString("videoUri", input.videoUri)
        putString("videoName", input.videoName)
        putLong("videoSizeBytes", input.videoSizeBytes ?: -1L)
        putLong("videoDurationMs", input.videoDurationMs ?: -1L)
        putString("videoArtworkPath", input.videoArtworkPath?.takeIf { File(it).isFile })
        putString("saveFormat", input.saveFormat.name)
        putString("libraryLayout", input.libraryLayout.name)
        putString("botLayout", input.botLayout.name)
        saveQuery(this, "libraryQuery", input.libraryQuery)
        saveQuery(this, "botQuery", input.botQuery)
        putString("settingsOrigin", when (input.settingsOrigin) {
            Destination.Library -> "library"
            Destination.Bot -> "bot"
            else -> "convert"
        })
        putString("settingsPage", when (input.settingsPage) {
            SettingsPage.Root -> "root"
            SettingsPage.Licenses -> "licenses"
            is SettingsPage.LicenseDetail -> "license_detail"
        })
        putString("settingsLicenseId", (input.settingsPage as? SettingsPage.LicenseDetail)?.id)
        putBoolean("botSetupRequested", input.botSetupRequested)
        putBoolean("playerExpanded", input.playerExpanded)
        putBoolean("emptyPlayerVisible", input.emptyPlayerVisible)
        putString("playerSourceKey", lastPlayerSourceKey)
        putString("playerSaveSourceKey", input.playerSaveSourceKey)
        putStringArrayList("queueKeys", ArrayList(input.queue.map(QueueItemUi::key)))
        // A Bundle is only authoritative for the session that was open when it was written.
        putInt("queueCloseGeneration", playbackQueueStore.closeGeneration())
        putInt("queueIndex", input.queueIndex)
        putInt("queueResumeIndex", input.queueResumeIndex)
        putString("queueCurrentKey", input.queue.getOrNull(input.queueIndex)?.key)
        // The exact rows a later restore continues from, so a duplicate resumes the copy that was used.
        putString("queueCurrentEntryId", input.queue.getOrNull(input.queueIndex)?.entryId)
        putString("queueResumeEntryId", input.queue.getOrNull(input.queueResumeIndex)?.entryId)
        saveQueueRows(this, input.queue)
        val playhead = StreamPlayback.state.value
        val pausedKey = pausedCurrentSourceKey
        putString("queuePausedEntryId", pausedEntryIdOf(input.queue, pausedKey, input.queueIndex, input.queueResumeIndex))
        putLong("queuePausedPositionMs", if (pausedKey != null) recordedPauseMs(playhead) else playheadPositionMs(playhead))
        putString("queuePausedKey", pausedKey)
        putString("queuePausedArtworkBase64", if (pausedKey != null) playhead.artworkBase64 else "")
        putLong("queuePausedDurationMs", if (pausedKey != null) playhead.durationMs else 0L)
        putStringArrayList("queueDeletedEntryIds", ArrayList(deletedEntryIds))
        putStringArrayList("queueDeletedMediaKeys", ArrayList(deletedMediaKeys))
        putStringArrayList("queueDeletedKeys", ArrayList(deletedMediaKeys))
        putLong("queueDeletedVersion", deletedVersion)
        putInt("handledIncomingEventToken", handledIncomingEventToken)
    }


    /**
     * A retained ViewModel is already newer than its Bundle; restore only a new instance. Entries
     * whose live list is not loaded yet stay in order until its owner publishes rows.
     */
    internal fun restoreRestorableState(saved: Bundle) {
        if (hostWasBound) return
        val savedFormat = sequenceOf("saveFormat", "botFormat", "listenFormat")
            .mapNotNull { key -> AudioFormat.entries.firstOrNull { it.name == saved.getString(key) } }
            .firstOrNull() ?: AudioFormat.ORIGINAL
        fun layout(key: String) = ListLayout.entries.firstOrNull { it.name == saved.getString(key) } ?: ListLayout.List
        fun query(key: String): ListQuery = ListQuery(
            text = saved.getString("${key}Text").orEmpty(),
            sortKey = SortKey.entries.firstOrNull { it.name == saved.getString("${key}Sort") } ?: SortKey.Date,
            ascending = saved.getBoolean("${key}Ascending"),
            range = DateRange.entries.firstOrNull { it.name == saved.getString("${key}Range") } ?: DateRange.All,
            tags = saved.getStringArrayList("${key}Tags").orEmpty().mapTo(linkedSetOf(), TagPolicy::key),
        )
        handledIncomingEventToken = saved.getInt("handledIncomingEventToken")
        val currentSourceKey = sourceKey(StreamPlayback.state.value)
        val sameSource = saved.getString("playerSourceKey") == currentSourceKey
        /*
         * Only a live service that is actually playing may carry its expanded size across an
         * Activity/process recreation. A detached, paused or idle session, and a source that no
         * longer matches, reopen as the mini player.
         */
        val restoredStream = StreamPlayback.state.value
        val liveConfigExpanded = sameSource &&
            restoredStream.source != PlaybackSource.NONE &&
            !restoredStream.serviceDetached &&
            restoredStream.playing &&
            saved.getBoolean("playerExpanded", false)
        lastPlayerSourceKey = currentSourceKey
        /*
         * An explicit close retires the session the Bundle describes. A Bundle written before that
         * close still holds the old rows, so its queue, paused state and deletion tombstones are
         * ignored while its unrelated UI values (destination, formats, queries) stay usable. A Bundle
         * from an older app has no epoch and is accepted, as before.
         */
        val sessionStale = saved.getInt("queueCloseGeneration", 0) != playbackQueueStore.closeGeneration()
        if (!sessionStale) saved.getLong("queueDeletedVersion", 0L).takeIf { it > deletedVersion }?.let { version ->
            deletedVersion = version
            // A Bundle written before occurrence ids only knew media keys; it stays a media filter.
            saved.getStringArrayList("queueDeletedKeys").orEmpty().let { legacy ->
                if (saved.getStringArrayList("queueDeletedMediaKeys") == null) deletedMediaKeys.addAll(legacy)
            }
            saved.getStringArrayList("queueDeletedMediaKeys").orEmpty().let { deletedMediaKeys.addAll(it) }
            saved.getStringArrayList("queueDeletedEntryIds").orEmpty().let { deletedEntryIds.addAll(it) }
        }
        val savedRows = if (sessionStale) emptyList() else readQueueRows(saved)
        // A Bundle without occurrence ids names its rows only by media order, so the saved index picks.
        val savedResumeRow = savedRows.getOrNull(saved.getInt("queueResumeIndex"))
        val savedCurrentKey = saved.getString("queueCurrentKey")
        /*
         * A Bundle written by this version names its exact rows. An older one only has the index and
         * the media key, so the row at the saved index wins when it shows that media; only then does
         * the first row of that media stand in. Otherwise a duplicate would resolve to the wrong copy.
         */
        fun savedRow(entryIdKey: String, indexKey: String, mediaKey: String?): QueueRow? {
            saved.getString(entryIdKey)?.takeIf(String::isNotBlank)
                ?.let { id -> savedRows.firstOrNull { it.entryId == id }?.let { return it } }
            savedRows.getOrNull(saved.getInt(indexKey))?.takeIf { row -> row.key == mediaKey }?.let { return it }
            return mediaKey?.let { key -> savedRows.firstOrNull { it.key == key } }
        }
        val savedCurrentRow = savedRow("queueCurrentEntryId", "queueIndex", savedCurrentKey)
        val savedPausedRow = savedRow("queuePausedEntryId", "queueResumeIndex", saved.getString("queuePausedKey"))
        restoredQueueSpec = QueueRestoreSpec(
            rows = savedRows,
            currentKey = savedCurrentKey,
            resumeKey = savedResumeRow?.key,
            pausedKey = saved.getString("queuePausedKey"),
            pausedPositionMs = saved.getLong("queuePausedPositionMs", 0L),
            pausedArtworkBase64 = saved.getString("queuePausedArtworkBase64").orEmpty(),
            pausedDurationMs = saved.getLong("queuePausedDurationMs", 0L),
            // Each pointer restores on its own, so a missing current never loses the paused row.
            currentEntryId = savedCurrentRow?.entryId,
            resumeEntryId = savedResumeRow?.entryId,
            pausedEntryId = savedPausedRow?.entryId,
        )
        restoredQueue = restoreCandidateQueue(savedRows)
        val savedQueueIndex = restoredQueue.indexOfFirst { it.entryId == savedCurrentRow?.entryId }
            .takeIf { it >= 0 }
            ?: restoredQueue.indexOfFirst { it.key == savedCurrentKey }
        val restoredIndex = savedQueueIndex.takeIf { i -> restoredQueue.getOrNull(i)?.let { queueItemMatches(it, StreamPlayback.state.value) } == true }
            ?: restoredQueue.indexOfFirst { queueItemMatches(it, StreamPlayback.state.value) }
        val rowArtworkBase64 = savedRows.getOrNull(saved.getInt("queueResumeIndex"))?.artworkBase64.orEmpty()
        interaction.update { it.copy(
            destination = when (saved.getString("destination")) {
                "convert", "listen" -> Destination.Convert
                "library" -> Destination.Library
                "bot" -> Destination.Bot
                "botSettings" -> Destination.Settings
                "settings" -> Destination.Settings
                else -> Destination.Bot
            },
            videoUri = saved.getString("videoUri")?.takeIf { uri -> Uri.parse(uri).scheme == "content" },
            videoName = saved.getString("videoName").orEmpty(),
            videoSizeBytes = saved.getLong("videoSizeBytes", -1L).takeIf { it >= 0L },
            videoDurationMs = saved.getLong("videoDurationMs", -1L).takeIf { it > 0L },
            videoArtworkPath = saved.getString("videoArtworkPath")?.takeIf { SelectedVideoThumbnail.isCachedFile(appContext, it) },
            saveFormat = savedFormat, libraryLayout = layout("libraryLayout"), botLayout = layout("botLayout"),
            libraryQuery = query("libraryQuery"), botQuery = query("botQuery"),
            settingsOrigin = when (saved.getString("settingsOrigin")) {
                "library" -> Destination.Library
                "bot" -> Destination.Bot
                "convert", "listen" -> Destination.Convert
                else -> Destination.Bot
            },
            settingsPage = when (saved.getString("settingsPage")) {
                "licenses" -> SettingsPage.Licenses
                "license_detail" -> saved.getString("settingsLicenseId")
                    ?.takeIf { OpenSourceLicenses.find(it) != null }
                    ?.let { SettingsPage.LicenseDetail(it) } ?: SettingsPage.Licenses
                else -> SettingsPage.Root
            },
            botSetupRequested = saved.getBoolean("botSetupRequested") && !botState.value.connected,
            // A live service that is really playing keeps the size the user configured. A cold or
            // detached session must not reopen full screen: its shim has no service behind it, so
            // expansion there would be a forced full player rather than restored user intent.
            playerExpanded = liveConfigExpanded,
            // A paused or stopped session keeps its entry as the player's entry point.
            emptyPlayerVisible = currentSourceKey.isEmpty() && restoredQueue.isNotEmpty() &&
                (saved.getBoolean("emptyPlayerVisible") || restoredQueueSpec?.pausedKey != null),
            playerSaveSourceKey = saved.getString("playerSaveSourceKey").takeIf { libraryState.value.running },
            queue = restoredQueue,
            queueIndex = if (currentSourceKey.isNotEmpty()) restoredIndex else -1,
            queueResumeIndex = resolveRestoreResumeIndex(restoredQueue),
        ) }
        // A resumed process restores a paused session without starting a service.
        restorePausedSessionIfIdle(
            key = restoredQueueSpec?.pausedKey,
            title = savedRows.getOrNull(saved.getInt("queueResumeIndex"))?.title.orEmpty(),
            artworkBase64 = saved.getString("queuePausedArtworkBase64").orEmpty().ifBlank { rowArtworkBase64 },
            display = PlaybackRestorePolicy.reopen(
                snapshot = lastPersistedPlaybackSnapshot,
                rows = restoredQueue,
                liveSource = StreamPlayback.state.value.source,
            )?.display,
        )
        resolveRestoredQueue()
    }

    /** Entries resolve at once; entries of a list with no live rows stay until that list publishes. */
    private fun resolveRestoredQueue() {
        val spec = restoredQueueSpec ?: return
        val resolved = restoreCandidateQueue(spec.rows)
        // Identity and order are kept; a changed title or handle is still a change to publish.
        if (sameRestoredRows(restoredQueue, resolved)) return
        restoredQueue = resolved
        // The paused entry is re-read so its latest metadata is what the player shows.
        pausedCurrentSourceKey?.let { key ->
            resolved.firstOrNull { it.key == key }?.let { row ->
                val shown = StreamPlayback.state.value
                if (isSeparatedPausedStream(shown) && (shown.title != row.title || shown.artworkPath != row.artworkPath)) {
                    StreamPlayback.publish(shown.copy(title = row.title, artworkPath = row.artworkPath))
                }
            }
        }
        interaction.update { input -> input.copy(
            queue = resolved,
            queueResumeIndex = resolveRestoreResumeIndex(resolved),
        ) }
    }

    private fun resolveRestoreResumeIndex(queue: List<QueueItemUi>): Int = restoreResumeIndex(
        queue = queue,
        currentKey = restoredQueueSpec?.currentKey,
        resumeKey = restoredQueueSpec?.resumeKey,
        fallback = interaction.value.queueResumeIndex,
        currentEntryId = restoredQueueSpec?.currentEntryId,
        resumeEntryId = restoredQueueSpec?.resumeEntryId,
    )

    /**
     * Live rows replace saved descriptors by their identity; unresolved rows keep the saved
     * order and current identity until their owner list loads. A list that has already loaded
     * without the row means the entry was removed.
     */
    private fun restoreCandidateQueue(rows: List<QueueRow>): List<QueueItemUi> =
        resolveRestoreQueue(
            // A deleted occurrence is gone; a deleted media takes all of its occurrences with it.
            rows = rows.filterNot { it.entryId in deletedEntryIds || it.key in deletedMediaKeys },
            ownerLoaded = { source -> isOwnerLoaded(source) },
            live = { row ->
                if (row.entryId in deletedEntryIds || row.key in deletedMediaKeys) null else when (row.sourceKind) {
                    PlaybackSource.LOCAL_AUDIO -> (libraryHistory.value + listOfNotNull(libraryState.value.result))
                        .firstOrNull { queueItem(it).key == row.key }?.let(::queueItem)
                    PlaybackSource.BOT -> botState.value.videos
                        .firstOrNull { it.messageId == row.botMessageId && it.chatId == row.botChatId }?.let(::queueItem)
                    PlaybackSource.NONE -> null
                }
            },
        ).toQueueEntries()

    /** The library publishes its persisted rows synchronously; the bot inbox restores asynchronously. */
    private fun isOwnerLoaded(source: PlaybackSource): Boolean = when (source) {
        PlaybackSource.LOCAL_AUDIO -> AppStore.history.value.isNotEmpty() || AppStore.state.value.result != null
        PlaybackSource.BOT -> botState.value.initialStateLoaded
        PlaybackSource.NONE -> true
    }

    /**
     * A restored paused session is published as a ready-but-stopped mini player so only a user
     * press resumes it. It is presentation state, not a loading session: `ready` is true so the
     * player shows the saved title and playhead instead of an endless buffering spinner, while
     * `playing` stays false and no service, prepare or bot resolve is started.
     */
    private fun restorePausedSessionIfIdle(
        key: String?,
        title: String,
        artworkBase64: String,
        display: RestoreDisplay? = null,
    ) {
        /*
         * A live or active stream belongs to the service and must be reflected as-is. A leftover
         * paused shim, though, is this process's own presentation state: the Activity may have closed
         * while the process survived, and a new controller still has to adopt it, or it can never set
         * the paused identity, offer a resume or keep the checkpoint current. NONE, a detached stream
         * or a handle-less paused record are all adoptable; resolving/active playback is not.
         */
        if (key == null) return
        val current = StreamPlayback.state.value
        val adoptable = current.source == PlaybackSource.NONE ||
            (isSeparatedPausedStream(current) && !current.resolving && !current.playing)
        if (!adoptable) return
        val spec = restoredQueueSpec ?: return
        // The saved occurrence is adopted when known, so a duplicate does not adopt the wrong copy.
        val row = (spec.pausedEntryId?.let { id -> spec.rows.firstOrNull { it.entryId == id } }
            ?: spec.rows.firstOrNull { it.key == key })
            ?.takeIf { row -> restoredQueue.any { it.entryId == row.entryId } }
            ?: return
        // The bookmark is the owner of the resume point; the queue checkpoint can lag behind it by
        // one throttle window, so restore from whichever of the two is newer. This only reshapes the
        // paused presentation: it starts no service, prepare or resolve.
        val saved = bookmarks.of(key)
        // The bookmark owns the resume point once it exists, including a deliberate 0 ("start from
        // the beginning"): falling back to the checkpoint there would revive an older position.
        val resumePositionMs = when {
            saved == null -> spec.pausedPositionMs
            // A completed item restarts from the beginning; an unfinished one keeps its saved spot.
            saved.completed -> 0L
            else -> saved.positionMs
        }
        val resumeDurationMs = when {
            saved == null -> spec.pausedDurationMs
            resumePositionMs != spec.pausedPositionMs -> saved.durationMs
            else -> maxOf(spec.pausedDurationMs, saved.durationMs)
        }
        pausedSourcePositions[key] = PausedSource(key, resumePositionMs, "restored:$key")
        pausedCurrentSourceKey = key
        StreamPlayback.publish(StreamingState(
            source = display?.source?.takeIf { it != PlaybackSource.NONE } ?: row.sourceKind,
            title = title.ifBlank { row.title },
            // Presentation readiness from the restore decision: never a loading session.
            ready = display?.ready ?: true,
            playing = display?.playing ?: false,
            positionMs = resumePositionMs,
            durationMs = resumeDurationMs,
            localAudioPath = null,
            localAudioMime = null,
            botFileId = null,
            sourceBytes = 0L,
            artworkPath = row.artworkPath,
            artworkBase64 = artworkBase64,
            // Keeps the transport handles absent, so this is a paused session, not live playback.
            serviceDetached = display?.detached ?: true,
        ))
    }

    /** Rehydrates the resumable queue without creating a playback service or starting audio. */
    private fun restorePersistedPlaybackSession() {
        val saved = lastPersistedPlaybackSnapshot ?: return
        if (saved.rows.isEmpty()) {
            playbackQueueStore.write(null)
            lastPersistedPlaybackSnapshot = null
            return
        }
        /*
         * A checkpoint written by an older build can hold its rows while its paused/current key was
         * replaced with null (the degraded `bot:invalid`/`local:invalid` writer did exactly that).
         * The rows are the user's data, so reopening must still show the mini player and the queue
         * entry now, not one launch later: fall back to the saved current row, then the resume row,
         * then the first row, and require the key to name a row that actually exists. The recovered
         * key is written into the spec so the resume path and index selection use the same row. This
         * never plays, never reorders and never clears anything.
         */
        val rowKeys = saved.rows.mapTo(mutableSetOf(), QueueRow::key)
        fun heal(key: String?): String? = key?.takeIf { it in rowKeys }
        /*
         * A saved occurrence names its row exactly; the media key only decides for a legacy
         * checkpoint or a row whose id is gone. Everything still heals to a real row.
         */
        fun healEntry(entryId: String?): String? = entryId?.takeIf { id -> saved.rows.any { it.entryId == id } }
        fun rowOf(entryId: String?): QueueRow? = healEntry(entryId)?.let { id -> saved.rows.firstOrNull { it.entryId == id } }
        val healedCurrentRow = rowOf(saved.currentEntryId) ?: heal(saved.currentKey)?.let { key -> saved.rows.firstOrNull { it.key == key } }
        val healedCurrentEntryId = healedCurrentRow?.entryId
        val healedCurrentKey = healedCurrentRow?.key?.takeIf { it in rowKeys }
            ?: heal(saved.currentKey)
            ?: heal(saved.resumeKey)
            ?: saved.rows.firstOrNull()?.key
        val healedResumeRow = rowOf(saved.resumeEntryId) ?: heal(saved.resumeKey)?.let { key -> saved.rows.firstOrNull { it.key == key } }
        val healedPausedRow = rowOf(saved.pausedEntryId) ?: heal(saved.pausedKey)?.let { key -> saved.rows.firstOrNull { it.key == key } }
        val healedPausedKey = healedPausedRow?.key?.takeIf { it in rowKeys } ?: healedCurrentKey
        restoredQueueSpec = QueueRestoreSpec(
            rows = saved.rows,
            currentKey = healedCurrentKey,
            resumeKey = healedResumeRow?.key?.takeIf { it in rowKeys } ?: healedCurrentKey,
            pausedKey = healedPausedKey,
            // A recovered row must not inherit a different, unidentified item's old playhead.
            // Its own durable bookmark takes precedence when the paused presentation is built.
            pausedPositionMs = if (healedPausedRow != null) saved.pausedPositionMs else 0L,
            pausedDurationMs = saved.pausedDurationMs,
            currentEntryId = healedCurrentEntryId,
            resumeEntryId = healedResumeRow?.entryId,
            pausedEntryId = healedPausedRow?.entryId,
        )
        restoredQueue = restoreCandidateQueue(saved.rows)
        val stream = StreamPlayback.state.value
        val liveSourceKey = sourceKey(stream)
        val savedIndex = healedCurrentKey?.let { key -> restoredQueue.indexOfFirst { it.key == key } } ?: -1
        val liveIndex = savedIndex.takeIf { index ->
            index in restoredQueue.indices && queueItemMatches(restoredQueue[index], stream)
        } ?: restoredQueue.indexOfFirst { queueItemMatches(it, stream) }
        // The reopen rules live in PlaybackRestorePolicy so the regression test drives this path.
        // It gets the healed keys too, so a recovered row decides the display source and playhead.
        val healed = saved.copy(currentKey = healedCurrentKey, pausedKey = healedPausedKey)
        val decision = PlaybackRestorePolicy.reopen(healed, restoredQueue, stream.source)
        interaction.update { input -> input.copy(
            playerExpanded = decision?.expandPlayer ?: false,
            emptyPlayerVisible = liveSourceKey.isEmpty() && restoredQueue.isNotEmpty() &&
                (saved.emptyPlayerVisible || healedPausedKey != null),
            queue = restoredQueue,
            queueIndex = if (liveSourceKey.isNotEmpty()) liveIndex else -1,
            queueResumeIndex = resolveRestoreResumeIndex(restoredQueue),
        ) }
        // A reopen never starts playback; a live service is merely reflected, never re-requested.
        val pausedRow = saved.rows.firstOrNull { it.key == healedPausedKey }
        restorePausedSessionIfIdle(
            key = healedPausedKey,
            title = pausedRow?.title.orEmpty(),
            artworkBase64 = pausedRow?.artworkBase64.orEmpty(),
            display = decision?.display,
        )
        resolveRestoredQueue()
    }

    /** While audio is playing, keep the last durable playhead; pause events write the exact stop point. */
    private fun playbackSnapshotForPersistence(
        input: InteractionState,
        stream: StreamingState,
    ): PlaybackQueueSnapshot? {
        if (input.queue.isEmpty()) return null
        val rowKeys = input.queue.mapTo(mutableSetOf(), QueueItemUi::key)
        /*
         * The current occurrence is chosen first; its media key is derived from it. Falling back to a
         * media key would retarget the checkpoint to whichever copy of that media is first.
         */
        val currentItem = if (stream.source != PlaybackSource.NONE) {
            /*
             * A restored paused session publishes its source and playhead but deliberately no
             * service handles, so the source key degrades to "bot:invalid"/"local:invalid". Writing
             * that would erase the current row from the checkpoint and the player entry would vanish
             * on the next cold start, so the live key is only trusted when it names a queued row.
             * Otherwise the saved row identity wins; a synthetic key is never persisted.
             */
            val liveKey = pausedQueueKey(stream, input.queue).ifEmpty { sourceKey(stream) }
            liveKey.takeIf { it in rowKeys }?.let { key ->
                /*
                 * The pointer among the rows of that media, so a duplicate keeps the copy the user
                 * selected instead of whichever one happens to be first.
                 */
                input.queue.getOrNull(input.queueIndex)?.takeIf { it.key == key }
                    ?: input.queue.getOrNull(input.queueResumeIndex)?.takeIf { it.key == key }
                    ?: input.queue.firstOrNull { it.key == key }
            }
                ?: lastPersistedPlaybackSnapshot?.pausedEntryId?.let { id -> input.queue.firstOrNull { it.entryId == id } }
                ?: input.queue.getOrNull(input.queueIndex)
                ?: input.queue.getOrNull(input.queueResumeIndex)
        } else {
            /*
             * With no live source the checkpoint keeps the occurrence it already had. Falling back to
             * a queue index would silently retarget the saved item to whatever sits at that index.
             */
            lastPersistedPlaybackSnapshot?.pausedEntryId?.let { id -> input.queue.firstOrNull { it.entryId == id } }
                ?: lastPersistedPlaybackSnapshot?.pausedKey?.let { key -> input.queue.firstOrNull { it.key == key } }
                ?: input.queue.getOrNull(input.queueIndex)
                ?: input.queue.getOrNull(input.queueResumeIndex)
        }
        val currentKey = currentItem?.key?.takeIf { it in rowKeys }
        val resumeItem = input.queue.getOrNull(input.queueResumeIndex)
        val previous = lastPersistedPlaybackSnapshot
        val sameCurrent = previous?.pausedEntryId != null && previous.pausedEntryId == currentItem?.entryId ||
            previous?.pausedEntryId == null && previous?.pausedKey != null && previous.pausedKey == currentKey
        val positionMs = when {
            stream.source == PlaybackSource.NONE -> previous?.takeIf { sameCurrent }?.pausedPositionMs ?: 0L
            stream.playing -> previous?.takeIf { sameCurrent }?.pausedPositionMs ?: 0L
            else -> playheadPositionMs(stream)
        }
        /*
         * A stopped service reports duration 0, but that says nothing about the item. Keeping the
         * previous duration for the same current item preserves the saved resume time on close.
         */
        val durationMs = when {
            stream.source == PlaybackSource.NONE -> previous?.takeIf { sameCurrent }?.pausedDurationMs ?: 0L
            else -> stream.durationMs.coerceAtLeast(0L)
        }
        val rows = input.queue.map { item ->
            QueueRow(
                entryId = item.entryId,
                key = item.key,
                title = item.title,
                sourceKind = item.sourceKind,
                localAudioPath = item.localAudioPath,
                botMessageId = item.botMessageId,
                botChatId = item.botChatId,
                botFileId = item.botFileId,
                artworkPath = item.artworkPath,
            )
        }
        return PlaybackQueueSnapshot(
            rows = rows,
            currentKey = currentKey,
            resumeKey = resumeItem?.key,
            pausedKey = currentKey,
            pausedPositionMs = positionMs,
            pausedDurationMs = durationMs,
            playerExpanded = input.playerExpanded,
            emptyPlayerVisible = input.emptyPlayerVisible,
            currentEntryId = currentItem?.entryId,
            resumeEntryId = resumeItem?.entryId,
            pausedEntryId = currentItem?.entryId,
        )
    }

    /** Activity bridge for shared video URIs; the Activity owns its saved event counter. */
    fun acceptSharedIntent(intent: Intent, eventToken: Int) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        onSharedIntent(uri?.toString(), eventToken)
    }

    override fun onTopLevelSelected(destination: Destination): Boolean {
        require(destination == Destination.Convert || destination == Destination.Library || destination == Destination.Bot) { "Expected a top-level destination." }
        val before = interaction.value
        val oldTopLevel = before.let { input -> when (input.destination) {
            Destination.Settings -> input.settingsOrigin
            else -> input.destination
        } }
        interaction.update { input ->
            val collapsePlayer = shouldCollapsePlayerForTopLevelNavigation(
                currentDestination = input.destination,
                currentTopLevelDestination = when (input.destination) {
                    Destination.Settings -> input.settingsOrigin
                    else -> input.destination
                },
                selectedDestination = destination,
                playerExpanded = input.playerExpanded,
            )
            input.copy(
                destination = destination,
                settingsPage = SettingsPage.Root,
                pendingLibraryDelete = null,
                pendingBotDelete = null,
                playerExpanded = if (collapsePlayer) false else input.playerExpanded,
            )
        }
        onClearSelection()
        return oldTopLevel == destination
    }

    override fun onOpenSettings() {
        val origin = interaction.value.destination
        if (origin != Destination.Convert && origin != Destination.Library && origin != Destination.Bot) return
        onClearSelection()
        interaction.update { it.copy(destination = Destination.Settings, settingsOrigin = origin, settingsPage = SettingsPage.Root, pendingLibraryDelete = null, pendingBotDelete = null) }
    }

    override fun onOpenLicenses() {
        if (interaction.value.destination != Destination.Settings) return
        interaction.update { it.copy(settingsPage = SettingsPage.Licenses) }
    }

    override fun onOpenLicense(id: String) {
        if (interaction.value.destination != Destination.Settings) return
        if (OpenSourceLicenses.find(id) == null) {
            notice("라이선스 항목을 찾지 못했어요. 목록에서 다시 선택해 주세요.", true)
            return
        }
        interaction.update { it.copy(settingsPage = SettingsPage.LicenseDetail(id)) }
    }

    override fun onOpenBotSetup() {
        if (interaction.value.destination != Destination.Bot) return
        onClearSelection()
        interaction.update { it.copy(botSetupRequested = true, pendingBotDelete = null) }
    }

    override fun onCloseBotSetup() {
        interaction.update { it.copy(botSetupRequested = false) }
    }

    override fun onBack(): Boolean = when {
        interaction.value.selectionMode -> { onClearSelection(); true }
        interaction.value.destination == Destination.Bot && interaction.value.botSetupRequested -> { onCloseBotSetup(); true }
        interaction.value.destination == Destination.Settings -> {
            interaction.update { input -> when (input.settingsPage) {
                is SettingsPage.LicenseDetail -> input.copy(settingsPage = SettingsPage.Licenses)
                SettingsPage.Licenses -> input.copy(settingsPage = SettingsPage.Root)
                SettingsPage.Root -> input.copy(destination = input.settingsOrigin)
            } }; true
        }
        interaction.value.destination != Destination.Bot -> { onTopLevelSelected(Destination.Bot); true }
        else -> false
    }

    override fun onBotApiIdChanged(value: String) { interaction.update { it.copy(botApiIdText = value.filter(Char::isDigit).take(10)) } }
    override fun onBotApiHashChanged(value: String) { interaction.update { it.copy(botApiHash = value) } }
    override fun onBotTokenChanged(value: String) { interaction.update { it.copy(botToken = value.trim()) } }
    override fun onPickVideo() { hostEffects?.pickVideo() }

    override fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        if (uri.scheme != "content") {
            notice("영상을 파일로 공유하거나 영상 선택으로 다시 열어 주세요.", true)
            return
        }
        val uriKey = uri.toString()
        val generation = ++selectedVideoGeneration
        onTopLevelSelected(Destination.Convert)
        interaction.update { it.copy(
            videoUri = uriKey,
            videoName = "선택한 영상",
            videoSizeBytes = null,
            videoDurationMs = null,
            videoArtworkPath = null,
        ) }
        controllerScope.launch {
            val (metadata, artworkPath) = withContext(Dispatchers.IO) {
                readVideoMetadata(uri) to SelectedVideoThumbnail.create(appContext, uri)
            }
            interaction.update { input ->
                if (input.videoUri != uriKey || generation != selectedVideoGeneration) input else input.copy(
                    videoName = metadata.name,
                    videoSizeBytes = metadata.sizeBytes,
                    videoDurationMs = metadata.durationMs,
                    videoArtworkPath = artworkPath,
                )
            }
            if (interaction.value.videoUri != uriKey) withContext(Dispatchers.IO) {
                SelectedVideoThumbnail.delete(appContext, artworkPath) { interaction.value.videoUri != uriKey }
            }
        }
    }

    override fun onClearVideo() {
        if (AppStore.state.value.running) return
        val old = interaction.value
        selectedVideoGeneration++
        interaction.update { it.copy(videoUri = null, videoName = "", videoSizeBytes = null, videoDurationMs = null, videoArtworkPath = null) }
        controllerScope.launch {
            withContext(Dispatchers.IO) {
                SelectedVideoThumbnail.delete(appContext, old.videoArtworkPath) { interaction.value.videoUri != old.videoUri }
            }
        }
    }

    override fun onSharedIntent(uri: String?, eventToken: Int) {
        if (eventToken == handledIncomingEventToken || uri == null) return
        handledIncomingEventToken = eventToken
        onVideoPicked(Uri.parse(uri))
    }

    override fun onExtractVideo() {
        val input = interaction.value
        val uri = input.videoUri ?: return
        if (pendingConversionUri != null || AppStore.state.value.running) return
        pendingConversionUri = uri
        val request = conversionIntent(input.saveFormat).putExtra("uri", uri)
        request.clipData = ClipData.newRawUri("영상", Uri.parse(uri))
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        onRequestNotificationPermissionThen {
            if (pendingConversionUri != uri) return@onRequestNotificationPermissionThen
            pendingConversionUri = null
            if (interaction.value.videoUri != uri || AppStore.state.value.running) return@onRequestNotificationPermissionThen
            startConversion(request, "추출 준비 중", "추출을 시작하지 못했어요. 앱을 다시 열고 시도해 주세요.")

        }
    }

    override fun onSaveFormatChanged(value: AudioFormat) {
        if (AppStore.state.value.running || interaction.value.preparingBotSaveSources.isNotEmpty()) return
        savedUiPreferences.edit().putString("save_format", value.name).apply()
        interaction.update { it.copy(saveFormat = value) }
    }

    override fun onDownloadExecutionModeChanged(value: DownloadExecutionMode) {
        DownloadExecutionPreferences.save(appContext, value)
        interaction.update { it.copy(downloadExecutionMode = value) }
    }

    override fun onLibraryQueryChanged(query: ListQuery) = updateLibraryQuery(query, AppStore.history.value)

    /** Debug fixtures supply their own data while retaining the controller-owned query and selection. */
    internal fun updateLibraryQuery(query: ListQuery, items: List<AudioResult>) {
        val normalizedQuery = query.copy(tags = query.tags.mapTo(linkedSetOf(), TagPolicy::key))
        val visiblePaths = filterLibrary(items, normalizedQuery).mapTo(mutableSetOf(), AudioResult::path)
        interaction.update { it.copy(libraryQuery = normalizedQuery,
            selectedAudioPaths = if (it.selectionMode) it.selectedAudioPaths.intersect(visiblePaths) else it.selectedAudioPaths) }
    }

    override fun onBotQueryChanged(query: ListQuery) = updateBotQuery(query, botState.value.videos)

    internal fun updateBotQuery(query: ListQuery, items: List<BotVideo>) {
        val normalizedQuery = query.copy(tags = query.tags.mapTo(linkedSetOf(), TagPolicy::key))
        val overrides = interaction.value.botVideoTagsOverrides
        val visibleIds = filterBot(applyBotTagOverrides(items, overrides), normalizedQuery).mapTo(mutableSetOf(), BotVideo::messageId)
        interaction.update { it.copy(botQuery = normalizedQuery,
            selectedBotMessageIds = if (it.selectionMode) it.selectedBotMessageIds.intersect(visibleIds) else it.selectedBotMessageIds) }
    }

    override fun onSetAudioTags(result: AudioResult, tags: List<String>) {
        val normalized = TagPolicy.normalizeTags(tags)
        controllerScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { AppStore.setTags(appContext, result.path, normalized) } }
            outcome.fold(
                onSuccess = { updated ->
                    if (updated == null) {
                        notice("태그를 저장하지 못했어요.", true)
                        return@fold
                    }
                    val rows = AppStore.history.value
                    interaction.update { input -> input.copy(
                        libraryQuery = input.libraryQuery.withAvailableTags(rows.map(AudioResult::tags)),
                        pendingSaveResult = input.pendingSaveResult?.let { pending -> if (pending.path == updated.path) updated else pending },
                    ) }
                    notice("태그를 저장했어요.")
                },
                onFailure = { notice("태그를 저장하지 못했어요.", true) },
            )
        }
    }

    override fun onSetBotVideoTags(video: BotVideo, tags: List<String>) {
        val normalized = TagPolicy.normalizeTags(tags)
        controllerScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { BotPrivateStore(appContext).setVideoTags(video.chatId, video.messageId, normalized) }
            }
            outcome.fold(
                onSuccess = { updated ->
                    if (updated == null) {
                        notice("태그를 저장하지 못했어요.", true)
                        return@fold
                    }
                    interaction.update { input ->
                        val overrides = input.botVideoTagsOverrides + (updated.messageId to updated.tags)
                        val current = applyBotTagOverrides(botState.value.videos, overrides)
                        input.copy(
                            botVideoTagsOverrides = overrides,
                            botQuery = input.botQuery.withAvailableTags(current.map(BotVideo::tags)),
                        )
                    }
                    notice("태그를 저장했어요.")
                },
                onFailure = { notice("태그를 저장하지 못했어요.", true) },
            )
        }
    }

    override fun onLayoutChanged(destination: Destination, layout: ListLayout) {
        val key = when (destination) {
            Destination.Library -> "library_layout"
            Destination.Bot -> "bot_layout"
            else -> null
        }
        if (key != null) savedUiPreferences.edit().putString(key, layout.name).apply()
        interaction.update { when (destination) {
            Destination.Library -> it.copy(libraryLayout = layout)
            Destination.Bot -> it.copy(botLayout = layout)
            else -> it
        } }
    }

    override fun onCancelConversion() { appContext.startService(Intent(appContext, ConversionService::class.java).setAction("cancel")) }
    override fun onCancelDownload(id: Long) {
        appContext.startService(
            Intent(appContext, ConversionService::class.java)
                .setAction(ConversionService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(ConversionService.EXTRA_DOWNLOAD_ID, id),
        )
    }
    override fun onPauseDownload(id: Long) {
        appContext.startService(Intent(appContext, ConversionService::class.java)
            .setAction(ConversionService.ACTION_PAUSE_DOWNLOAD)
            .putExtra(ConversionService.EXTRA_DOWNLOAD_ID, id))
    }
    override fun onResumeDownload(id: Long) {
        appContext.startForegroundService(Intent(appContext, ConversionService::class.java)
            .setAction(ConversionService.ACTION_RESUME_DOWNLOAD)
            .putExtra(ConversionService.EXTRA_DOWNLOAD_ID, id))
    }
    override fun onRequestNotificationPermission() {
        if (notificationsAllowed()) {
            onNotificationPermissionResult(true)
        } else {
            val effects = hostEffects
            if (effects == null) {
                onNotificationPermissionResult(false)
            } else if (!notificationPermissionRequestInFlight) {
                notificationPermissionRequestInFlight = true
                runCatching(effects::requestNotificationPermission)
                    .onFailure { onNotificationPermissionResult(false) }
            }
        }
    }
    override fun onRequestNotificationPermissionThen(onResult: (Boolean) -> Unit) {
        if (notificationsAllowed()) {
            interaction.update { it.copy(notificationsAllowed = true) }
            onResult(true)
            return
        }
        pendingNotificationPermissionActions += onResult
        onRequestNotificationPermission()
    }
    override fun onNotificationPermissionResult(allowed: Boolean) {
        notificationPermissionRequestInFlight = false
        interaction.update { it.copy(notificationsAllowed = allowed) }
        val pending = pendingNotificationPermissionActions.toList()
        pendingNotificationPermissionActions.clear()
        pending.forEach { action -> action(allowed) }
        if (pending.isNotEmpty() && !allowed) {
            notice("알림 권한이 꺼져 있어 진행 알림은 표시되지 않아요. 앱 정보 > 알림에서 허용할 수 있어요. 작업은 계속 진행할게요.")
        }
    }

    override fun onSaveAudioToDevice(result: AudioResult) {
        if (interaction.value.saving || interaction.value.renamingAudioPath != null || interaction.value.pendingSaveResult != null) return
        interaction.update { it.copy(pendingSaveResult = result) }
        try { hostEffects?.saveAudioCopy(result) ?: onSaveDestination(null) }
        catch (_: Exception) {
            interaction.update { it.copy(pendingSaveResult = null) }
            notice("파일을 저장하지 못했어요. 다시 시도해 주세요.", true)
        }
    }

    override fun onSaveDestination(uri: Uri?) {
        val result = interaction.value.pendingSaveResult ?: return
        if (uri == null) { interaction.update { it.copy(pendingSaveResult = null) }; return }
        interaction.update { it.copy(saving = true) }
        controllerScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        File(result.path).inputStream().use { it.copyTo(output) }
                    } ?: throw IOException()
                }.isSuccess
            }
            interaction.update { it.copy(saving = false, pendingSaveResult = null) }
            if (saved) notice("파일을 저장했어요") else notice("파일을 저장하지 못했어요. 다시 시도해 주세요.", true)
        }
    }

    override fun onPlayAudio(result: AudioResult) {
        // A new local play supersedes any Bot play still waiting for a reconnect.
        cancelPendingBotPlayback()
        val stream = StreamPlayback.state.value
        val sameCurrentFile = stream.localAudioPath?.let(::canonicalPath) == canonicalPath(result.path)
        if (sameCurrentFile && stream.playing) {
            StreamPlayback.pause()
            return
        }
        val item = queueItem(result)
        if (offerResumeFor(item, fromQueue = false)) return
        clearQueuePlaybackPreference()
        startFreshQueue(item)
        if (sameCurrentFile && !stream.ended) StreamPlayback.resume() else playAudio(result)
    }

    override fun onPlayBotVideo(video: BotVideo) {
        if (pendingBotPlayback.isPendingItem(video.chatId, video.messageId)) return
        cancelPendingBotPlayback()
        val item = queueItem(video)
        // The continue/restart prompt keeps its own contract, and a fresh card play always starts a
        // fresh queue. Only the connection step is shared with a retry, so a disconnected card is not
        // silently ignored and no new item is attached to the previous queue.
        if (offerResumeFor(item, fromQueue = false)) return
        if (canUseBotVideo(video)) {
            clearQueuePlaybackPreference()
            startFreshQueue(item)
            preservePlayerSizeBotQueueKey = item.key
            playBotVideo(video)
            return
        }
        // A queued restart of the same item must not attach its playhead to a different item.
        val stream = StreamPlayback.state.value
        val streamItem = interaction.value.queue.firstOrNull { it.key == botQueueKeyForStream(stream) }
        val sameAsQueued = streamItem?.key == item.key
        val positionMs = if (sameAsQueued) retryPlayheadMs(stream, item) else 0L
        playBotOrReconnect(video, positionMs, prepareQueue = true)
    }

    /**
     * An explicit play of an unfinished item asks where to start instead of resuming silently.
     * Nothing is mutated while the question is open: no queue change, no service request and no bot
     * resolve. A second tap while the prompt is open is ignored, so it cannot double-start.
     */
    private fun offerResumeFor(item: QueueItemUi, fromQueue: Boolean): Boolean {
        if (pendingResumeTarget != null) return true
        val bookmark = bookmarks.of(item.key) ?: return false
        // Only Bot/Library list cards reach here, and a card tap always asks for an unfinished item,
        // including the one that is currently loaded and paused. The player paths never ask, so
        // there is nothing left for a "same item is already paused" suppression to protect.
        if (!bookmarks.asks(item.key, sameLiveItemPaused = false)) return false
        pendingResumeTarget = item
        interaction.update { input -> input.copy(
            resumePrompt = ResumePrompt(
                key = item.key,
                title = item.title,
                positionMs = bookmark.positionMs,
                durationMs = bookmark.durationMs,
                fromQueue = fromQueue,
            ),
        ) }
        return true
    }

    override fun onResumeFromBookmark() {
        val target = pendingResumeTarget ?: return
        val prompt = interaction.value.resumePrompt ?: return
        pendingResumeTarget = null
        interaction.update { it.copy(resumePrompt = null) }
        // The media may have been deleted while the question was open.
        val stillPresent = itemStillPlayable(target)
        if (!stillPresent) {
            notice(if (target.sourceKind == PlaybackSource.BOT) "봇 목록에서 영상을 다시 선택해 주세요." else LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true)
            return
        }
        playTargetAt(target, prompt.positionMs, fromQueue = prompt.fromQueue)
    }

    override fun onRestartFromBeginning() {
        val target = pendingResumeTarget ?: return
        val prompt = interaction.value.resumePrompt
        val durationMs = prompt?.durationMs ?: 0L
        val fromQueue = prompt?.fromQueue == true
        pendingResumeTarget = null
        interaction.update { it.copy(resumePrompt = null) }
        if (!itemStillPlayable(target)) {
            notice(if (target.sourceKind == PlaybackSource.BOT) "봇 목록에서 영상을 다시 선택해 주세요." else LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true)
            return
        }
        // Starting over clears the saved spot so the next explicit play does not ask again.
        bookmarks.restart(target.key, durationMs)
        persistBookmarks()
        playTargetAt(target, 0L, fromQueue = fromQueue)
    }

    override fun onDismissResumePrompt() {
        pendingResumeTarget = null
        interaction.update { it.copy(resumePrompt = null) }
    }

    private fun itemStillPlayable(item: QueueItemUi): Boolean = when (item.sourceKind) {
        PlaybackSource.BOT -> botVideoFor(item)?.needsForward == false
        PlaybackSource.LOCAL_AUDIO -> libraryResultFor(item.localAudioPath) != null
        PlaybackSource.NONE -> false
    }

    /**
     * Starts a confirmed choice. [fromQueue] is true when the item was already a queue row, so the
     * existing queue and its order are kept; a library or bot card play starts a fresh queue.
     *
     * A non-zero start position travels inside the start request (ResumeSpec/EXTRA_POSITION), never
     * as a follow-up seek: a fresh play has not prepared its player yet, and a bot play resolves its
     * file asynchronously, so an immediate seek would be dropped or applied to the old player.
     */
    private fun playTargetAt(item: QueueItemUi, positionMs: Long, fromQueue: Boolean) {
        clearQueuePlaybackPreference()
        // An explicit play of this row again lifts any deletion tombstone on its bookmark.
        revivePlaybackBookmark(item.key)
        val entry = resolvedEntry(item)
        val startAt = positionMs.coerceAtLeast(0L)
        if (entry.sourceKind == PlaybackSource.BOT) {
            val video = botVideoFor(entry)
            if (video == null) {
                notice("봇 목록에서 영상을 다시 선택해 주세요.", true)
                return
            }
            if (video.needsForward) return
            if (fromQueue && startAt == 0L) {
                // Keep the existing queue: select the row in place, exactly like onPlayQueueItem.
                selectExistingQueueRow(entry, video.fileId)
                preservePlayerSizeSourceKey = "bot:${video.fileId}"
                preservePlayerSizeBotQueueKey = queueItem(video).key
                playBotOrReconnect(video, 0L, prepareQueue = false)
                return
            }
            // A fresh play opens a new queue; a replayed row keeps the existing one and its order.
            if (!fromQueue) startFreshQueue(entry) else selectExistingQueueRow(entry, video.fileId)
            preservePlayerSizeBotQueueKey = entry.key
            // Reopen through the resolve path so a stale saved file id is refreshed first.
            resumeBotAt(entry, startAt)
            return
        }
        val result = libraryResultFor(entry.localAudioPath)
        if (result == null || LocalPlaybackPolicy.ownedAudioFile(appContext.filesDir, result.path) == null) {
            notice(LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true, UserFacingErrors.Context.LOCAL_MEDIA)
            return
        }
        if (fromQueue && startAt == 0L) {
            selectExistingQueueRow(entry, botFileId = null)
            preservePlayerSizeForQueuePlayback(result)
            playAudio(result)
            return
        }
        if (!fromQueue) startFreshQueue(entry) else selectExistingQueueRow(entry, botFileId = null)
        preservePlayerSizeForQueuePlayback(result)
        resumeRestoredAt(entry, startAt, botVideo = null, localResult = result)
    }

    /** Select a row inside the existing queue without replacing the queue or its order. */
    private fun selectExistingQueueRow(item: QueueItemUi, botFileId: Int?) {
        interaction.update { input ->
            // The exact occurrence, so a duplicate of the media selects the row that was tapped.
            val target = input.queue.indexOfFirst { it.entryId == item.entryId }
            if (target < 0) return@update input.copy(emptyPlayerVisible = false)
            val selected = selectQueueEntry(input.queue, target)
            input.copy(
                emptyPlayerVisible = false,
                queue = selected.queue.mapIndexed { i, queued ->
                    if (i == selected.index && botFileId != null) queued.copy(botFileId = botFileId) else queued
                },
                queueIndex = selected.index,
                queueResumeIndex = selected.resumeIndex,
            )
        }
    }

    /** Starts [entry] at [startAt] with the position carried by the service request itself. */
    private fun resumeRestoredAt(
        entry: QueueItemUi,
        startAt: Long,
        botVideo: BotVideo?,
        localResult: AudioResult?,
    ) {
        val localFile = localResult?.let { LocalPlaybackPolicy.ownedAudioFile(appContext.filesDir, it.path) }
        val request = restoredPlaybackRequest(
            item = entry,
            queue = interaction.value.queue + entry,
            positionMs = startAt,
            localAudioAvailable = localFile != null,
            botVideoAvailable = botVideo != null && canUseBotVideo(botVideo),
        ) ?: run {
            notice(if (entry.sourceKind == PlaybackSource.BOT) "봇 목록에서 영상을 다시 선택해 주세요." else LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true)
            return
        }
        clearPausedSession()
        pendingResumedKey = entry.key
        pendingResumedPositionMs = request.positionMs
        val startable = request.item
        val mime = localResult?.let { LocalPlaybackPolicy.audioMime(it.mime) }
        StreamPlayback.resumeRestored(
            context = appContext,
            spec = StreamingAudioService.ResumeSpec(
                positionMs = request.positionMs,
                localAudioPath = startable.localAudioPath?.takeIf { localFile != null },
                mime = mime,
                title = startable.title,
                artworkPath = startable.artworkPath,
                artworkBase64 = startable.artworkBase64,
                botFileId = startable.botFileId?.takeIf { botVideo != null },
                botBytes = botVideo?.bytes ?: 0L,
            ),
            identity = StreamingState(
                source = startable.sourceKind,
                title = startable.title,
                resolving = true,
                positionMs = request.positionMs,
                durationMs = bookmarks.of(entry.key)?.durationMs ?: 0L,
                botFileId = startable.botFileId?.takeIf { botVideo != null },
                localAudioPath = startable.localAudioPath?.takeIf { localFile != null },
                localAudioMime = mime,
                sourceBytes = botVideo?.bytes ?: 0L,
                artworkPath = startable.artworkPath,
                artworkBase64 = startable.artworkBase64,
            ),
        )
    }

    /** A fresh user-chosen play replaces any paused session and its recorded state. */
    private fun playAudio(result: AudioResult) {
        // Every local start — card or queued row — supersedes a Bot play still waiting to reconnect.
        cancelPendingBotPlayback()
        clearPausedSession()
        StreamPlayback.playLocal(appContext, result)
    }

    /** Drops every paused record; a separated paused display returns to an empty session. */
    private fun clearPausedSession() {
        pausedSourcePositions.clear()
        pausedCurrentSourceKey = null
        pendingResumedKey = null
        if (isSeparatedPausedStream(StreamPlayback.state.value)) StreamPlayback.publish(StreamingState())
    }

    private fun playBotVideo(video: BotVideo, startPositionMs: Long = 0L): Boolean {
        if (!canUseBotVideo(video)) return false
        // Any Bot play actually starting supersedes a request still waiting on a reconnect, so a later
        // connection cannot replay the old item over this one.
        pendingBotPlayback.cancel()
        cancelBotPlaybackTimeout()
        // The public resolve path restores the current file id from the remote id before the service
        // request, so a stale saved id never reaches the service. The start position rides along.
        botPlaybackRecovery.play(appContext, video, startPositionMs)
        return true
    }

    /**
     * A bot row reopens through the resolve path so its file id is current, unlike the direct
     * resume request. Returns false when the row has no live bot video to resolve.
     */
    private fun resumeBotAt(item: QueueItemUi, positionMs: Long): Boolean {
        val video = botVideoFor(item) ?: return false
        if (!canUseBotVideo(video)) {
            // A dropped connection is not a reason to refuse: wait it out and resume at this position.
            val bot = botState.value
            val canRecover = BotReconnectPolicy.canWait(
                BotReconnectPolicy.block(
                    ready = botPlaybackRecovery.isAuthenticated(),
                    connected = bot.connected,
                    hasClient = botPlaybackRecovery.hasClient(),
                    reconnecting = botPlaybackRecovery.isReconnecting(),
                    busy = bot.busy,
                    setupComplete = bot.setupComplete,
                    paired = bot.paired,
                ),
            )
            if (!canRecover) return false
        }
        clearPausedSession()
        pendingResumedKey = item.key
        pendingResumedPositionMs = positionMs
        if (canUseBotVideo(video)) return playBotVideo(video, positionMs)
        // Reuse the shared reconnect-and-play path; the queue is already the one being resumed.
        playBotOrReconnect(video, positionMs, prepareQueue = false)
        return true
    }

    private fun revivePlaybackBookmark(key: String) {
        closedQueueMediaKeys.remove(key)
        bookmarks.revive(key)
    }

    private fun startFreshQueue(item: QueueItemUi) {
        // A row played again explicitly is allowed to keep a new bookmark from now on.
        revivePlaybackBookmark(item.key)
        val playback = queueForPlayback(item)
        interaction.update { input ->
            input.copy(
                playerExpanded = true,
                emptyPlayerVisible = false,
                queue = playback.queue,
                queueIndex = playback.index,
                queueResumeIndex = playback.resumeIndex,
            )
        }
    }

    override fun onAddToQueue(result: AudioResult) = appendQueueItem(queueItem(result))
    override fun onAddBotVideoToQueue(video: BotVideo) {
        if (!video.needsForward) appendQueueItem(queueItem(video))
    }
    private fun appendQueueItem(item: QueueItemUi) {
        // The user is queueing this media again, so the close's resume-point guard no longer applies.
        closedQueueMediaKeys.remove(item.key)
        interaction.update { input ->
            // A closed session is empty. Adding its first item shows a stopped mini player without
            // starting playback, resolving media, or opening a connection.
            // A visible player — live or stopped — keeps its own size and expansion.
            val idle = StreamPlayback.state.value.source == PlaybackSource.NONE
            val playerHidden = idle && !input.emptyPlayerVisible
            input.copy(
                // A new occurrence appended at the end. The existing resume anchor already points into
                // the kept queue, so the append never takes the current position.
                queue = appendQueueEntry(input.queue, item),
                emptyPlayerVisible = input.emptyPlayerVisible || idle,
                playerExpanded = if (playerHidden) false else input.playerExpanded,
            )
        }
        // Adding is always allowed, including a second copy of the same media, so there is one message.
        notice("재생목록에 추가했어요")
    }

    override fun onPlayPrevious() {
        if (StreamPlayback.state.value.positionMs > 3_000L) onSeek(0L)
        else interaction.value.let { playQueueItem((if (it.queueIndex >= 0) it.queueIndex else it.queueResumeIndex) - 1, direction = -1, resumeBookmark = true) }
    }
    override fun onPlayNext() = interaction.value.let { playQueueItem(if (it.queueIndex >= 0) it.queueIndex + 1 else it.queueResumeIndex, direction = 1, resumeBookmark = true) }
    // A queue row resumes at its saved spot without asking; only a Bot/Library list card does.
    override fun onPlayQueueItem(index: Int) = playQueueItem(index, direction = 1, resumeBookmark = true)
    override fun onShowQueue() { interaction.update { it.copy(queueVisible = true) } }
    override fun onHideQueue() { interaction.update { it.copy(queueVisible = false, queueEditing = false, queueSelectedKeys = emptySet()) } }
    override fun onToggleQueueEditing() { interaction.update { it.copy(queueEditing = !it.queueEditing, queueSelectedKeys = emptySet()) } }
    override fun onToggleQueueSelection(key: String) {
        interaction.update { input -> if (!input.queueEditing || input.queue.none { it.entryId == key }) input else
            input.copy(queueSelectedKeys = if (key in input.queueSelectedKeys) input.queueSelectedKeys - key else input.queueSelectedKeys + key) }
    }
    override fun onDeleteQueueSelection() {
        removeQueueItems(interaction.value.queueSelectedKeys)
        interaction.update { it.copy(queueSelectedKeys = emptySet()) }
    }
    override fun onMoveQueueItem(from: Int, to: Int) {
        interaction.update { input ->
            val moved = moveQueueEntry(input.queue, input.queueIndex, input.queueResumeIndex, from, to)
            input.copy(queue = moved.queue, queueIndex = moved.index, queueResumeIndex = moved.resumeIndex)
        }
    }

    /**
     * Plays queue [index]. [resumeBookmark] is true for a user-chosen move (a queue row, next,
     * previous, the mini player); those continue at the item's saved spot without asking and never
     * 0 s unless there is no saved spot. Automatic advancement keeps [resumeBookmark] false so a
     * naturally finished item's successor still starts at 0 s, as it always has.
     */
    private fun playQueueItem(index: Int, direction: Int, resumeBookmark: Boolean = false) {
        cancelPendingBotPlayback()
        interaction.value.queue.getOrNull(index)?.let { revivePlaybackBookmark(it.key) }
        var target = index
        while (target in interaction.value.queue.indices) {
            val item = interaction.value.queue[target]
            // A user-chosen move reopens where the listener stopped; an automatic one starts fresh.
            val resumeAt = if (resumeBookmark) resumableBookmarkPosition(item.key) else null
            when (item.sourceKind) {
                PlaybackSource.LOCAL_AUDIO -> {
                    val result = libraryResultFor(item.localAudioPath)
                    if (result != null && LocalPlaybackPolicy.ownedAudioFile(appContext.filesDir, result.path) != null) {
                        interaction.update { input ->
                            val selected = selectQueueEntry(input.queue, target)
                            input.copy(
                                emptyPlayerVisible = false,
                                queue = selected.queue,
                                queueIndex = selected.index,
                                queueResumeIndex = selected.resumeIndex,
                            )
                        }
                        val entry = queueItem(result).let { if (resumeAt != null) item else it }
                        if (resumeAt != null) {
                            preservePlayerSizeForQueuePlayback(result)
                            resumeRestoredAt(entry, resumeAt, botVideo = null, localResult = result)
                        } else {
                            preservePlayerSizeForQueuePlayback(result)
                            playAudio(result)
                        }
                        return
                    }
                    notice(LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true, UserFacingErrors.Context.LOCAL_MEDIA)
                }
                PlaybackSource.BOT -> {
                    val video = botState.value.videos.firstOrNull {
                        if (item.botMessageId != null) it.messageId == item.botMessageId && it.chatId == item.botChatId
                        else it.fileId == item.botFileId
                    }
                    if (video != null && !video.needsForward) {
                        interaction.update { input ->
                            val selected = selectQueueEntry(input.queue, target)
                            input.copy(
                                emptyPlayerVisible = false,
                                queue = selected.queue.mapIndexed { i, queued -> if (i == selected.index) queued.copy(botFileId = video.fileId) else queued },
                                queueIndex = selected.index,
                                queueResumeIndex = selected.resumeIndex,
                            )
                        }
                        preservePlayerSizeSourceKey = "bot:${video.fileId}"
                        preservePlayerSizeBotQueueKey = queueItem(video).key
                        // A dropped connection is not a dead end for an explicit row play: the shared
                        // path reconnects once. The queue is already the one being played.
                        if (canUseBotVideo(video)) {
                            // A saved spot reopens through the resolve path, so the current file id is
                            // restored before the service request carries the position.
                            if (resumeAt != null) resumeBotAt(item, resumeAt) else playBotVideo(video)
                        } else {
                            playBotOrReconnect(video, resumeAt ?: 0L, prepareQueue = false)
                        }
                        return
                    }
                    notice("봇 목록에서 영상을 다시 선택해 주세요.", true, UserFacingErrors.Context.TELEGRAM)
                }
                PlaybackSource.NONE -> return
            }
            // Only this unplayable occurrence goes; another queued copy of the media is untouched.
            removeQueueItems(setOf(item.entryId))
            if (direction < 0) target--
        }
    }

    /** The saved spot a user-chosen queue move continues from, or null when there is none. */
    private fun resumableBookmarkPosition(key: String): Long? =
        bookmarks.of(key)?.takeIf { it.resumable }?.positionMs?.takeIf { it > 0L }

    /**
     * The occurrence a paused player continues from, resolved by [pausedQueueIndex] so the saved row
     * matches the one the player itself would resume.
     */
    private fun pausedEntryIdOf(queue: List<QueueItemUi>, pausedKey: String?, queueIndex: Int, resumeIndex: Int): String? =
        if (pausedKey == null) null
        else queue.getOrNull(pausedQueueIndex(queue, queueIndex, resumeIndex, pausedKey))
            ?.takeIf { it.key == pausedKey }?.entryId


    /** The debug queue uses the same local source transition preference as the production queue. */
    internal fun preservePlayerSizeForQueuePlayback(result: AudioResult) {
        preservePlayerSizeSourceKey = "local:${canonicalPath(result.path)}"
        preservePlayerSizeBotQueueKey = null
    }

    private fun clearQueuePlaybackPreference() {
        preservePlayerSizeSourceKey = null
        preservePlayerSizeBotQueueKey = null
    }

    /**
     * The queue sheet's delete: it removes exactly the selected occurrences. A queued copy of the
     * same media is a different row and survives, so nothing is written to [deletedMediaKeys].
     */
    private fun removeQueueItems(entryIds: Set<String>) {
        if (entryIds.isEmpty()) return
        removedOccurrences(entryIds, purgeBookmark = true)
    }

    /**
     * A real library/bot deletion removes every occurrence of [mediaKeys] and tombstones them, so a
     * delayed list or snapshot emission cannot restore them.
     */
    private fun removeMediaFromQueue(mediaKeys: Set<String>) {
        if (mediaKeys.isEmpty()) return
        val entryIds = interaction.value.queue.filter { it.key in mediaKeys }.mapTo(linkedSetOf()) { it.entryId }
        removedOccurrences(entryIds, purgeBookmark = true, mediaKeys = mediaKeys)
    }

    /**
     * Removes [entryIds] and, when the last row of a media goes, also purges that media's bookmark.
     * [mediaKeys] records the media a real library/bot deletion removed, so every occurrence of it
     * disappears and cannot be restored later.
     */
    private fun removedOccurrences(entryIds: Set<String>, purgeBookmark: Boolean, mediaKeys: Set<String> = emptySet()) {
        if (entryIds.isEmpty() && mediaKeys.isEmpty()) return
        val current = interaction.value
        // Deleting an entry is final: a delayed list or snapshot emission cannot bring it back.
        deletedEntryIds.addAll(entryIds)
        if (mediaKeys.isNotEmpty()) deletedMediaKeys.addAll(mediaKeys)
        deletedVersion++
        // A removed occurrence loses its media only when no other row still shows it. A media that was
        // actually deleted is gone regardless, even when no queue row was involved.
        val purged = current.queue.filterNot { it.entryId in entryIds }.mapTo(mutableSetOf()) { it.key }
        val goneMedia = current.queue.filter { it.entryId in entryIds }.mapTo(linkedSetOf()) { it.key }
        goneMedia.addAll(mediaKeys)
        goneMedia.removeAll(purged)
        if (purgeBookmark && goneMedia.isNotEmpty() && bookmarks.purge(goneMedia)) persistBookmarks()
        pendingResumeTarget?.let { target -> if (target.entryId in entryIds) onDismissResumePrompt() }
        restoredQueueSpec = restoredQueueSpec?.let { spec ->
            spec.copy(
                rows = spec.rows.filterNot { it.entryId in entryIds || it.key in mediaKeys },
                currentKey = spec.currentKey?.takeIf { it !in goneMedia },
                resumeKey = spec.resumeKey?.takeIf { it !in goneMedia },
                pausedKey = spec.pausedKey?.takeIf { it !in goneMedia },
                currentEntryId = spec.currentEntryId?.takeIf { it !in entryIds },
                resumeEntryId = spec.resumeEntryId?.takeIf { it !in entryIds },
                pausedEntryId = spec.pausedEntryId?.takeIf { it !in entryIds },
            )
        }
        // Pending-autoplay cancellation is by occurrence: deleting another copy must not stop playback.
        if (pendingBotPlayback.current?.entryId in entryIds) cancelPendingBotPlayback()
        val pausedEntryId = current.queue.getOrNull(current.queueIndex)?.entryId
        if (pausedEntryId != null && pausedEntryId in entryIds) {
            pausedCurrentSourceKey?.let { pausedSourcePositions.remove(it) }
        }
        pausedCurrentSourceKey?.let { key -> if (key !in purged) pausedSourcePositions.remove(key) }
        if (pausedCurrentSourceKey in goneMedia) {
            pausedCurrentSourceKey = null
            if (isSeparatedPausedStream(StreamPlayback.state.value)) StreamPlayback.publish(StreamingState())
        }
        val mutation = removeQueueEntries(current.queue, current.queueIndex, current.queueResumeIndex, entryIds)
        interaction.update { input ->
            val updated = if (input.queue == current.queue && input.queueIndex == current.queueIndex && input.queueResumeIndex == current.queueResumeIndex) {
                mutation
            } else removeQueueEntries(input.queue, input.queueIndex, input.queueResumeIndex, entryIds)
            input.copy(queue = updated.queue, queueIndex = updated.index, queueResumeIndex = updated.resumeIndex,
                emptyPlayerVisible = input.emptyPlayerVisible || updated.removedCurrent,
                queueSelectedKeys = input.queueSelectedKeys - entryIds)
        }
        if (mutation.removedCurrent) {
            clearQueuePlaybackPreference()
            clearPausedSession()
            StreamPlayback.stop()
        }
    }

    /**
     * The user's pause. A restarted service or a process restored without one has no player, so
     * pause only records the resumed entry, paused flag and position without starting a service.
     */
    override fun onPause() {
        settleInterruptedResume()
        // A paused player must not start playing later on its own once Telegram reconnects.
        cancelPendingBotPlayback()
        StreamPlayback.pause()
    }

    /**
     * A live playback records its position. A session whose service ended keeps that record and
     * its resumable identity, so the player stays on the same queue entry. A stopped player's
     * entry is its paused position; nothing here starts a service.
     */
    private fun observePlayback(stream: StreamingState) {
        if (stream.source != PlaybackSource.NONE && !isSeparatedPausedStream(stream)) {
            // The row identity is the saved one; the playback handle only names the request.
            val key = pausedQueueKey(stream, interaction.value.queue)
            if (key.isNotEmpty()) {
                pendingResumedKey = null
                pausedCurrentSourceKey = key
                pausedSourcePositions[key] = PausedSource(key, playheadPositionMs(stream))
            }
            return
        }
        if (stream.source != PlaybackSource.NONE && stream.serviceDetached) {
            // The service ended without a close: the item stays paused where it stopped.
            val key = pausedCurrentSourceKey ?: pausedQueueKey(stream, interaction.value.queue)
            if (key.isNotEmpty()) {
                pendingResumedKey = null
                pausedCurrentSourceKey = key
                pausedSourcePositions[key] = pausedSourcePositions[key]
                    ?: PausedSource(key, playheadPositionMs(stream))
            }
            return
        }
        if (interaction.value.emptyPlayerVisible) {
            val queue = interaction.value
            val index = queue.queueResumeIndex.takeIf { it in queue.queue.indices }
                ?: queue.queue.indices.firstOrNull() ?: return
            pausedCurrentSourceKey = queue.queue[index].key
        }
    }

    override fun onResume() {
        val stream = StreamPlayback.state.value
        if (stream.resolving) return
        if (stream.source == PlaybackSource.BOT && (!botState.value.connected || stream.error != null)) {
            retryBotPlayback(stream)
            return
        }
        when {
            stream.source != PlaybackSource.NONE && !isSeparatedPausedStream(stream) -> StreamPlayback.resume()
            stream.source != PlaybackSource.NONE -> resumePausedSource(stream)
            interaction.value.emptyPlayerVisible -> {
                // The mini player continues its own item without asking; the prompt belongs to an
                // explicit play of a Bot/Library list card only.
                val queue = interaction.value
                val index = queue.queueResumeIndex.takeIf { it in queue.queue.indices } ?: 0
                playQueueItem(index, direction = 1, resumeBookmark = true)
            }
        }
    }

    /**
     * Restores the service for a paused session starting at its recorded position. The request
     * is sent now; if Android stops this Activity while it is still being delivered, the paused
     * entry stays marked so a later pause/pause-save keeps the resumed intent.
     */
    private fun resumePausedSource(stream: StreamingState) {
        val queue = interaction.value.queue
        val item = queue.getOrNull(pausedQueueIndex(queue, interaction.value.queueIndex, interaction.value.queueResumeIndex, pausedCurrentSourceKey))
        val identity = pausedQueueKey(stream, queue).ifEmpty { pendingResumedKey.orEmpty() }
        val paused = pausedSourcePositions[identity]
        val positionMs = paused?.positionMs ?: playheadPositionMs(stream)
        if (item == null) {
            // Nothing playable survived the restore; stay paused rather than start another item.
            notice("재생할 항목을 찾을 수 없어요.", true)
            return
        }
        // The mini player resumes its own saved spot silently; asking is reserved for an explicit
        // play of a Bot/Library list card.
        val index = queue.indexOfFirst { it.entryId == item.entryId }.takeIf { it >= 0 } ?: interaction.value.queueIndex
        if (item.sourceKind == PlaybackSource.BOT) {
            // Same resolve path as a fresh bot play, so the current file id is restored first.
            if (!resumeBotAt(item, positionMs)) {
                notice("봇 목록에서 영상을 다시 선택해 주세요.", true)
                pausedCurrentSourceKey = identity.ifEmpty { item.key }
                return
            }
            pausedCurrentSourceKey = null
            pausedSourcePositions.remove(identity)
            pausedSourcePositions.remove(item.key)
            interaction.update { input -> input.copy(
                emptyPlayerVisible = false,
                queueIndex = index,
                queueResumeIndex = index,
            ) }
            return
        }
        val localResult = if (item.sourceKind == PlaybackSource.LOCAL_AUDIO) libraryResultFor(item.localAudioPath) else null
        val localFile = localResult?.let { LocalPlaybackPolicy.ownedAudioFile(appContext.filesDir, it.path) }
        val request = restoredPlaybackRequest(
            item = item,
            queue = queue,
            positionMs = positionMs,
            localAudioAvailable = localFile != null,
            botVideoAvailable = false,
        )
        if (request == null) {
            // The owner has not published this row yet: keep the stable identity for the next press.
            notice(LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true)
            pausedCurrentSourceKey = identity.ifEmpty { item.key }
            return
        }
        pausedCurrentSourceKey = null
        pausedSourcePositions.remove(identity)
        pausedSourcePositions.remove(item.key)
        pendingResumedKey = item.key
        pendingResumedPositionMs = request.positionMs
        interaction.update { input -> input.copy(
            emptyPlayerVisible = false,
            queueIndex = input.queue.indexOfFirst { it.entryId == item.entryId }.takeIf { it >= 0 } ?: input.queueIndex,
            queueResumeIndex = input.queue.indexOfFirst { it.entryId == item.entryId }.takeIf { it >= 0 } ?: input.queueResumeIndex,
        ) }
        val startable = request.item
        val mime = localResult?.let { LocalPlaybackPolicy.audioMime(it.mime) }
        StreamPlayback.resumeRestored(
            context = appContext,
            spec = StreamingAudioService.ResumeSpec(
                positionMs = request.positionMs,
                localAudioPath = startable.localAudioPath?.takeIf { localFile != null },
                mime = mime,
                title = startable.title,
                artworkPath = startable.artworkPath,
                artworkBase64 = startable.artworkBase64,
            ),
            identity = stream,
        )
    }

    /** The live inbox row for a bot queue entry; the queue row alone may predate its owner list. */
    private fun botVideoFor(item: QueueItemUi): BotVideo? = botState.value.videos.firstOrNull {
        if (item.botMessageId != null) it.messageId == item.botMessageId && it.chatId == item.botChatId
        else it.fileId == item.botFileId
    } ?: item.botFileId?.let { fileId -> botState.value.videos.firstOrNull { it.fileId == fileId } }

    private fun libraryResultFor(path: String?): AudioResult? = path?.let { wanted ->
        (libraryHistory.value + listOfNotNull(libraryState.value.result))
            .firstOrNull { canonicalPath(it.path) == canonicalPath(wanted) }
    }

    /** The live entry for a saved row, used when a paused restore needs its current media. */
    /**
     * Refreshest metadata for an existing row. The occurrence id is carried over: a re-resolve
     * describes the same queue entry, not a new one.
     */
    private fun resolvedEntry(item: QueueItemUi): QueueItemUi = when (item.sourceKind) {
        PlaybackSource.BOT -> botVideoFor(item)?.let { queueItem(it).copy(entryId = item.entryId) } ?: item
        PlaybackSource.LOCAL_AUDIO -> libraryResultFor(item.localAudioPath)?.let { queueItem(it).copy(entryId = item.entryId) } ?: item
        PlaybackSource.NONE -> item
    }

    /** A pause while a resume is still delivered means the resume never started playback. */
    private fun settleInterruptedResume() {
        val key = pendingResumedKey ?: return
        pendingResumedKey = null
        val stream = StreamPlayback.state.value
        if (isSeparatedPausedStream(stream) && sourceKey(stream) == key) {
            // Keep the paused session displayed; starting it must stay a user action.
            pausedSourcePositions[key] = PausedSource(key, pendingResumedPositionMs, "restored:$key")
            pausedCurrentSourceKey = key
        }
    }

    /**
     * The paused entry to display, when the current stream is a separated paused session on an
     * entry that still exists.
     */
    private fun pausedDisplayOf(stream: StreamingState): PausedDisplay {
        val key = pausedCurrentSourceKey ?: return PausedDisplay(null, -1)
        if (!isSeparatedPausedStream(stream)) return PausedDisplay(null, -1)
        val input = interaction.value
        val index = pausedQueueIndex(input.queue, input.queueIndex, input.queueResumeIndex, key)
        return PausedDisplay(stream.takeIf { index >= 0 }, index)
    }

    /** The saved snapshot keeps the last pause even after a resume so the next process restores it. */
    private fun recordedPauseMs(stream: StreamingState): Long =
        pausedCurrentSourceKey?.let { key ->
            pausedSourcePositions[key]?.positionMs
                ?: if (key == pendingResumedKey) pendingResumedPositionMs else null
        } ?: playheadPositionMs(stream)

    /** Explicit UI close clears the queue and its resume state; pause is a separate operation. */
    override fun onStop() {
        // An explicit close deletes the playback session itself: queue, current row, resume point and
        // paused state all go. Only a pause or a returning process keeps the session.
        cancelPendingBotPlayback()
        botPlaybackTimeoutJob?.cancel()
        botPlaybackTimeoutJob = null
        val closing = interaction.value.queue
        /*
         * The closed rows lose their continue-listening position, as an explicit queue delete would, so
         * a late flush cannot recreate a resume point for media the user just cleared. Only queued
         * media is touched; other bookmarks, library files and bot data stay as they are.
         */
        val closingMedia = closing.mapTo(linkedSetOf(), QueueItemUi::key)
        if (bookmarks.purge(closingMedia)) persistBookmarks()
        // Guards only the close's own late samples; a later explicit add clears it again.
        closedQueueMediaKeys.addAll(closingMedia)
        // The checkpoint is gone from memory and disk before the UI clears, so a process death right
        // after the close cannot restore the dismissed session.
        playbackQueueStore.clearBlocking()
        lastPersistedPlaybackSnapshot = null
        restoredQueueSpec = null
        restoredQueue = emptyList()
        pausedSourcePositions.clear()
        pausedCurrentSourceKey = null
        pendingResumedKey = null
        pendingResumedPositionMs = 0L
        pendingResumeTarget = null
        lastBookmarkCandidate = null
        lastPlayerSourceKey = sourceKey(StreamingState())
        clearQueuePlaybackPreference()
        interaction.update { it.copy(
            queue = emptyList(),
            queueIndex = -1,
            queueResumeIndex = 0,
            emptyPlayerVisible = false,
            queueVisible = false,
            queueEditing = false,
            queueSelectedKeys = emptySet(),
            resumePrompt = null,
            playerSaveSourceKey = null,
        ) }
        StreamPlayback.stop()
    }
    override fun onSeek(positionMs: Long) {
        val stream = StreamPlayback.state.value
        val pausedKey = pausedCurrentSourceKey
        if (pausedKey != null && isSeparatedPausedStream(stream)) {
            // A restored, paused session stays displayed at the new position without a service.
            StreamPlayback.publish(stream.copy(positionMs = positionMs.coerceAtLeast(0L)))
            pausedSourcePositions[pausedKey] = PausedSource(pausedKey, positionMs.coerceAtLeast(0L))
            return
        }
        StreamPlayback.seekTo(positionMs)
    }

    override fun onRetryStream() {
        clearQueuePlaybackPreference()
        val stream = StreamPlayback.state.value
        when {
            stream.source == PlaybackSource.LOCAL_AUDIO -> currentAudioResult(stream)?.let { StreamPlayback.playLocal(appContext, it) }
            // A Bot retry survives a dropped connection: it reconnects once and replays the same
            // message at the saved playhead, instead of giving up because the client is offline.
            stream.source == PlaybackSource.BOT -> retryBotPlayback(stream)
        }
    }

    /**
     * Starts a Bot play the user asked for, repairing the Telegram connection first when it is down.
     *
     * The target is found by the stable chat+message identity because a reconnect can reissue the
     * file id. The position comes from the live playhead or the saved bookmark, so the retry resumes
     * rather than restarting. While waiting the player shows a resolving state with a short notice
     * instead of leaving the stale error on screen.
     */
    /**
     * Starts a Bot play the user asked for, repairing the Telegram connection first when it is down.
     *
     * The target is found by the stable chat+message identity because a reconnect can reissue the
     * file id. The retry position comes from the live playhead or the saved bookmark, so it continues
     * rather than restarts. Playback happens once, when the connection is usable, or the wait ends
     * with a clear message so the player never stays on a spinner.
     */
    /**
     * Starts the Bot play the user asked for, repairing the Telegram connection first when it is down.
     *
     * The target is found by the stable chat+message identity because a reconnect can reissue the
     * file id. Playback happens once, when the connection is usable, or the wait ends with an inline
     * player error. Nothing here announces progress: the internal reconnect is invisible to the user.
     */
    private fun retryBotPlayback(stream: StreamingState) {
        val queue = interaction.value.queue
        val row = queue.firstOrNull { it.key == botQueueKeyForStream(stream) }
            ?: queue.getOrNull(interaction.value.queueIndex)
        val video = resolvedBotRetryTarget(botState.value.videos, row, stream.botFileId)
            ?: row?.takeIf { it.sourceKind == PlaybackSource.BOT && it.botChatId != null && it.botMessageId != null }
                ?.let { BotVideo(it.botFileId ?: 0, requireNotNull(it.botChatId), requireNotNull(it.botMessageId),
                    it.title, stream.sourceBytes, (stream.durationMs / 1000L).toInt(), 0L) }
        if (video == null) {
            endPendingBotPlayback(BotPendingPlaybackEngine.MISSING_ITEM)
            return
        }
        // A retry keeps the queue the player already shows; only the connection step is shared.
        playBotOrReconnect(video, retryPlayheadMs(stream, row), prepareQueue = false)
    }

    /**
     * Plays [video] now, or records one pending play and reconnects when Telegram is down.
     *
     * [prepareQueue] mirrors the card contract: a fresh card selection starts a fresh queue, while a
     * retry keeps the queue the player already shows.
     *
     * Repeating the same item joins the waiting play instead of starting a second reconnect; a
     * different item replaces it with its own identity and position, so the wrong item never plays.
     */
    private fun playBotOrReconnect(video: BotVideo, positionMs: Long, prepareQueue: Boolean) {
        val item = queueItem(video)
        val bot = botState.value
        val reconnecting = botPlaybackRecovery.isReconnecting() || pendingBotPlayback.isPending
        val block = BotReconnectPolicy.block(
            ready = botPlaybackRecovery.isAuthenticated(),
            connected = bot.connected,
            hasClient = botPlaybackRecovery.hasClient(),
            reconnecting = reconnecting,
            busy = bot.busy,
            setupComplete = bot.setupComplete,
            paired = bot.paired,
        )
        if (video.needsForward || block == BotPlaybackBlock.SETUP_REQUIRED) {
            showBotPlaybackWaiting(video, positionMs)
            endPendingBotPlayback(BotPendingPlaybackEngine.SETUP_GONE)
            return
        }
        if (block == BotPlaybackBlock.BUSY && !reconnecting) {
            showBotPlaybackWaiting(video, positionMs)
            endPendingBotPlayback(BotPendingPlaybackEngine.TIMED_OUT)
            return
        }
        if (block == BotPlaybackBlock.NONE) {
            if (prepareQueue && !pendingBotPlayback.isPending) {
                clearQueuePlaybackPreference()
                startFreshQueue(item)
                preservePlayerSizeBotQueueKey = item.key
            }
            // The connection is usable: play the requested item, superseding any older wait.
            playBotVideo(video, positionMs)
            return
        }

        // Repeated taps join the same bounded wait without extending its deadline.
        if (pendingBotPlayback.isPendingItem(video.chatId, video.messageId)) return
        pendingBotPlayback.request(item.key, video.chatId, video.messageId, positionMs, item.entryId)
        if (prepareQueue) {
            clearQueuePlaybackPreference()
            startFreshQueue(item)
            preservePlayerSizeBotQueueKey = item.key
        }
        showBotPlaybackWaiting(video, positionMs)
        if (block == BotPlaybackBlock.ALREADY_RECONNECTING) {
            // Join the running attempt; the botState observer sees its outcome.
            armBotPlaybackTimeout()
            return
        }
        when (botPlaybackRecovery.reconnect(appContext)) {
            ReconnectStart.STARTED, ReconnectStart.JOINED -> armBotPlaybackTimeout()
            // Nothing is reconnecting, so waiting could never succeed.
            ReconnectStart.UNAVAILABLE -> endPendingBotPlayback(BotPendingPlaybackEngine.SETUP_GONE)
        }
    }

    /**
     * Arms the single bounded wait for the current request.
     *
     * The controller already observes botState, so success arrives through it. This one job only
     * guarantees the wait ends: it captures the request generation so a newer request or a cancel
     * makes it do nothing, and it removes the spinner itself if the connection arrives late.
     */
    private fun armBotPlaybackTimeout() {
        botPlaybackTimeoutJob?.cancel()
        val generation = pendingBotPlayback.currentGeneration
        botPlaybackTimeoutJob = controllerScope.launch {
            delay(botPlaybackWaitMs)
            if (!pendingBotPlayback.isPending || pendingBotPlayback.currentGeneration != generation) return@launch
            when (val outcome = pendingBotPlayback.expire(generation)) {
                is BotPendingOutcome.Fail -> endPendingBotPlayback(outcome.message)
                else -> Unit
            }
        }
    }

    private fun cancelBotPlaybackTimeout() {
        botPlaybackTimeoutJob?.cancel()
        botPlaybackTimeoutJob = null
    }

    /**
     * Shows progress for a waiting Bot play using only the metadata it owns.
     *
     * The previous state is deliberately not copied: a reused LOCAL path, ended flag or detached
     * service would describe a different stream. Toast suppression covers this internal wait only.
     */
    private fun showBotPlaybackWaiting(video: BotVideo, positionMs: Long) {
        StreamPlayback.publish(
            StreamingState(
                source = PlaybackSource.BOT,
                title = safeDisplayName(video.name),
                resolving = true,
                ready = false,
                playing = false,
                buffering = true,
                positionMs = positionMs,
                durationMs = video.durationSeconds.coerceAtLeast(0) * 1000L,
                botFileId = video.fileId,
                error = null,
                suppressErrorToast = true,
            ),
        )
    }

    /** Feeds one observation to the pending engine and applies its outcome. */
    private fun observePendingBotPlayback() {
        if (!pendingBotPlayback.isPending) return
        val bot = botState.value
        if (bot.error != null && !bot.connected && !bot.busy && !botPlaybackRecovery.isReconnecting()) {
            endPendingBotPlayback(BotPendingPlaybackEngine.TIMED_OUT)
            return
        }
        when (val outcome = pendingBotPlayback.observe(
            ready = botPlaybackRecovery.isAuthenticated(),
            connected = bot.connected,
            hasClient = botPlaybackRecovery.hasClient(),
            busy = bot.busy,
            setupComplete = bot.setupComplete,
            paired = bot.paired,
            initialStateLoaded = bot.initialStateLoaded,
            videos = pendingVideoRows(bot.videos),
        )) {
            BotPendingOutcome.Wait -> Unit
            is BotPendingOutcome.Play -> {
                val video = botVideoForRow(bot.videos, outcome.target.chatId, outcome.target.messageId)
                if (video == null) endPendingBotPlayback(BotPendingPlaybackEngine.MISSING_ITEM)
                else playBotVideo(video, outcome.target.positionMs)
            }
            is BotPendingOutcome.Fail -> endPendingBotPlayback(outcome.message)
        }
    }

    /**
     * Ends a waiting play: clears the spinner and leaves an inline player error, which is the existing
     * playback failure surface. It never raises a toast of its own.
     */
    private fun endPendingBotPlayback(message: String) {
        pendingBotPlayback.cancel()
        cancelBotPlaybackTimeout()
        val stream = StreamPlayback.state.value
        if (stream.source == PlaybackSource.BOT && stream.resolving) {
            StreamPlayback.publish(
                stream.copy(resolving = false, buffering = false, error = message, suppressErrorToast = true),
            )
        }
    }

    /** Cancels a waiting Bot play: the user stopped, switched item, deleted, or signed out. */
    private fun cancelPendingBotPlayback() {
        cancelBotPlaybackTimeout()
        if (!pendingBotPlayback.isPending) return
        pendingBotPlayback.cancel()
        val stream = StreamPlayback.state.value
        if (stream.source == PlaybackSource.BOT && stream.resolving) {
            StreamPlayback.publish(stream.copy(resolving = false, buffering = false, ready = true,
                playing = false, serviceDetached = true, suppressErrorToast = true))
        }
    }

    /** Whether the player is currently showing the internal Bot wait, so its error stays inline. */
    private fun isSuppressingInternalBotError(stream: StreamingState): Boolean =
        stream.suppressErrorToast && stream.source == PlaybackSource.BOT

    /** The queue key of the Bot row the player is showing, when one can be derived. */
    private fun botQueueKeyForStream(stream: StreamingState): String? =
        stream.botFileId?.let { fileId ->
            botState.value.videos.firstOrNull { it.fileId == fileId }?.let { queueItem(it).key }
        }

    /** The playhead a retry continues from: the live position, else the saved bookmark, else 0. */
    private fun retryPlayheadMs(stream: StreamingState, row: QueueItemUi?): Long {
        playheadPositionMs(stream).takeIf { it > 0L }?.let { return it }
        val key = row?.key ?: return 0L
        return bookmarks.of(key)?.takeIf { it.resumable }?.positionMs ?: 0L
    }

    override fun onExternalPlayStream() {
        // Handing playback to an external app supersedes any Bot play still waiting for a reconnect.
        cancelPendingBotPlayback()
        val stream = StreamPlayback.state.value
        when {
            stream.source == PlaybackSource.LOCAL_AUDIO -> {
                val result = currentAudioResult(stream)
                if (result == null) notice("보관함에서 오디오를 다시 선택해 주세요.", true)
                else {
                    StreamPlayback.stop()
                    ExternalPlayback.openAudio(appContext, File(result.path), stream.localAudioMime ?: result.mime) { notice(it, true, UserFacingErrors.Context.LOCAL_MEDIA) }
                }
            }
            stream.source == PlaybackSource.BOT -> {
                val video = stream.botFileId?.let { fileId -> botState.value.videos.firstOrNull { it.fileId == fileId } }
                if (video == null) notice("봇 목록에서 영상을 다시 선택해 주세요.", true)
                else onExternalPlayBotVideo(video)
            }
        }
    }

    override fun onSaveStream() {
        // Freeze source metadata now; later UI changes cannot retarget this save.
        val stream = StreamPlayback.state.value
        val botFileId = stream.botFileId ?: return
        if (stream.source != PlaybackSource.BOT || !botState.value.connected) return
        val sourceKey = sourceKey(stream)
        if (interaction.value.preparingPlayerSaveSourceKey == sourceKey ||
            AppStore.downloads.value.any { it.sourceBotFileId == botFileId }) return
        val input = interaction.value
        val request = conversionIntent(input.saveFormat)
            .putExtra("name", stream.title)
            .putExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH, stream.artworkPath)
            .putExtra(AudioArtworkStore.EXTRA_ARTWORK_BASE64, stream.artworkBase64)
        request.putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, botFileId)
            .putExtra(ConversionService.EXTRA_SOURCE_BOT_GENERATION, BotRuntime.audioSaveGeneration())
            .putExtra(BotRuntime.EXTRA_TDLIB_NAME, stream.title)
            .putExtra(BotRuntime.EXTRA_TDLIB_SIZE, stream.sourceBytes)
        interaction.update { it.copy(preparingPlayerSaveSourceKey = sourceKey) }
        interaction.update { it.copy(playerSaveSourceKey = sourceKey) }
        if (!startConversion(request, "오디오 저장 준비 중", "오디오 저장을 시작하지 못했어요. 앱을 다시 열고 시도해 주세요.")) {

            interaction.update { it.copy(playerSaveSourceKey = null) }
            interaction.update { it.copy(preparingPlayerSaveSourceKey = null) }
        }
    }

    override fun onExternalPlayAudio(result: AudioResult) {
        StreamPlayback.stop()
        ExternalPlayback.openAudio(appContext, File(result.path), result.mime) { notice(it, true, UserFacingErrors.Context.LOCAL_MEDIA) }
    }

    override fun onShareAudio(result: AudioResult) {
        try {
            val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".files", File(result.path))
            val send = Intent(Intent.ACTION_SEND).setType(result.mime)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(result.name, uri)
            hostEffects?.startShare(Intent.createChooser(send, "오디오 공유"))
        } catch (_: Exception) { notice("공유할 수 없어요. 파일을 저장한 뒤 다시 시도해 주세요.", true, UserFacingErrors.Context.LOCAL_MEDIA) }
    }

    override fun onRenameAudio(result: AudioResult, titleStem: String) {
        if (interaction.value.saving) { notice("오디오를 저장하는 동안에는 이름을 바꿀 수 없어요.", true); return }
        if (interaction.value.renamingAudioPath != null) { notice("다른 오디오 이름을 변경하고 있어요. 잠시 기다려 주세요.", true); return }
        val oldPath = canonicalPath(result.path)
        if (StreamPlayback.state.value.localAudioPath?.let(::canonicalPath) == oldPath) StreamPlayback.stop()
        interaction.update { it.copy(renamingAudioPath = oldPath) }
        controllerScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { AppStore.rename(appContext, result, titleStem) } }
            interaction.update { it.copy(renamingAudioPath = null) }
            outcome.fold(
                onSuccess = { renamed ->
                    interaction.update { input -> input.copy(
                        selectedAudioPaths = input.selectedAudioPaths.mapTo(mutableSetOf()) { if (canonicalPath(it) == oldPath) renamed.path else it },
                        pendingSaveResult = if (input.pendingSaveResult?.path?.let(::canonicalPath) == oldPath) renamed else input.pendingSaveResult,
                        // Metadata refresh only: every queued occurrence keeps its own id.
                        queue = input.queue.map { item -> if (item.localAudioPath?.let(::canonicalPath) == oldPath) queueItem(renamed).copy(entryId = item.entryId) else item },
                    ) }
                    notice("오디오 이름을 변경했어요.")
                },
                onFailure = { notice(UserFacingErrors.fromThrowable(it, UserFacingErrors.Context.LOCAL_MEDIA), true, UserFacingErrors.Context.LOCAL_MEDIA) },
            )
        }
    }

    override fun onRequestLibraryDelete(results: List<AudioResult>) {
        interaction.update { it.copy(pendingLibraryDelete = results.distinctBy(AudioResult::path).takeIf(List<AudioResult>::isNotEmpty)) }
    }

    override fun onConfirmLibraryDelete(results: List<AudioResult>) {
        if (results.isEmpty()) return
        if (interaction.value.renamingAudioPath != null) { notice("오디오 이름을 변경하는 중이에요. 완료된 뒤 삭제해 주세요.", true); return }
        val paths = results.mapTo(mutableSetOf()) { canonicalPath(it.path) }
        val playingPath = StreamPlayback.state.value.localAudioPath?.let(::canonicalPath)
        if (playingPath != null && playingPath in paths) {
            interaction.update { it.copy(emptyPlayerVisible = true) }
            StreamPlayback.stop()
        }
        try {
            val outcome = AppStore.removeMany(appContext, results)
            updateLibraryQuery(interaction.value.libraryQuery.withAvailableTags(AppStore.history.value.map(AudioResult::tags)), AppStore.history.value)
            removeMediaFromQueue(results.mapTo(mutableSetOf()) { queueItem(it).key })
            if (outcome.failedFileDeletes == 0) notice("보관함에서 ${outcome.removedEntries}개 항목을 삭제했어요.")
            else notice("보관함에서 ${results.size}개 항목을 제거했지만 일부 파일을 삭제하지 못했어요.", true)
            if (interaction.value.selectionMode) onClearSelection()
        } catch (_: Exception) { notice("보관함 항목을 삭제하지 못했어요. 다시 시도해 주세요.", true) }
        finally { interaction.update { it.copy(pendingLibraryDelete = null) } }
    }

    override fun onBeginSelection() { interaction.update { it.copy(selectionMode = true, selectedAudioPaths = emptySet(), selectedBotMessageIds = emptySet()) } }
    override fun onToggleAudioSelection(path: String) { interaction.update { it.copy(selectedAudioPaths = if (path in it.selectedAudioPaths) it.selectedAudioPaths - path else it.selectedAudioPaths + path) } }
    override fun onToggleBotSelection(messageId: Long) { interaction.update { it.copy(selectedBotMessageIds = if (messageId in it.selectedBotMessageIds) it.selectedBotMessageIds - messageId else it.selectedBotMessageIds + messageId) } }
    override fun onSelectAllCurrentItems() {
        when (interaction.value.destination) {
            Destination.Library -> {
                val paths = filterLibrary(AppStore.history.value, interaction.value.libraryQuery).mapTo(mutableSetOf(), AudioResult::path)
                interaction.update { it.copy(selectedAudioPaths = if (paths.isNotEmpty() && it.selectedAudioPaths.containsAll(paths)) emptySet() else paths) }
            }
            Destination.Bot -> {
                val input = interaction.value
                val ids = filterBot(applyBotTagOverrides(botState.value.videos, input.botVideoTagsOverrides), input.botQuery).mapTo(mutableSetOf(), BotVideo::messageId)
                interaction.update { it.copy(selectedBotMessageIds = if (ids.isNotEmpty() && it.selectedBotMessageIds.containsAll(ids)) emptySet() else ids) }
            }
            else -> Unit
        }
    }
    override fun onClearSelection() { interaction.update { it.copy(selectionMode = false, selectedAudioPaths = emptySet(), selectedBotMessageIds = emptySet()) } }

    override fun onConnectBot(credentials: BotCredentials) {
        cancelPendingBotPlayback()
        val stream = StreamPlayback.state.value
        if (stream.suppressErrorToast) StreamPlayback.publish(stream.copy(suppressErrorToast = false))
        val submitted = credentials.copy(apiHash = credentials.apiHash.trim(), token = credentials.token.trim())
        BotRuntime.connect(appContext, submitted)
    }
    override fun onDisconnectBot() {
        // Signing out must not leave a Bot play that resumes when a later connection returns.
        cancelPendingBotPlayback()
        onCloseBotSetup()
        BotRuntime.disconnect(appContext)
        onTopLevelSelected(Destination.Bot)
    }
    override fun onPairBot() {
        BotRuntime.pair(appContext)?.let(::onOpenExternalUrl)
    }
    override fun onRefreshBot() {
        if (interaction.value.destination == Destination.Bot) BotRuntime.refreshInbox(appContext)
    }
    override fun onSaveBotVideo(video: BotVideo, format: AudioFormat) {
        if (!canUseBotVideo(video)) return
        if (video.messageId in interaction.value.preparingBotSaveSources ||
            video.fileId in interaction.value.preparingBotSaveSources.values ||
            AppStore.downloads.value.any { it.sourceBotFileId == video.fileId }) return
        interaction.update {
            it.copy(
                preparingBotSaveSources = it.preparingBotSaveSources + (video.messageId to video.fileId),
            )
        }
        // Accept the click before permission prompts or asynchronous metadata can reorder it.
        BotRuntime.save(appContext, video, format.name)
        onRequestNotificationPermission()
    }
    override fun onRenameBotVideo(video: BotVideo, title: String) {
        BotRuntime.renameVideo(appContext, video, title) { error ->
            if (error == null) notice("영상 이름을 저장했어요.") else notice(error, true, UserFacingErrors.Context.TELEGRAM)
        }
    }
    override fun onExternalPlayBotVideo(video: BotVideo) {
        if (!canUseBotVideo(video)) return
        BotRuntime.resolveForExternalPlayback(appContext, video) { resolved ->
            StreamPlayback.stop()
            ExternalPlayback.openBotVideo(appContext, resolved) { notice(it, true, UserFacingErrors.Context.TELEGRAM) }
        }
    }
    override fun onRequestBotDelete(messageIds: Set<Long>) { interaction.update { it.copy(pendingBotDelete = messageIds.toSet().takeIf(Set<Long>::isNotEmpty)) } }
    override fun onConfirmBotDelete(messageIds: Set<Long>) {
        val targets = botState.value.videos.filter { it.messageId in messageIds }
        if (targets.isEmpty()) {
            interaction.update { it.copy(pendingBotDelete = null) }
            notice("봇 목록이 바뀌어 선택한 항목을 찾지 못했어요. 목록을 확인해 주세요.", true)
            if (interaction.value.selectionMode) onClearSelection()
            return
        }
        val targetIds = targets.mapTo(mutableSetOf(), BotVideo::messageId)
        val fileIds = targets.mapNotNull { it.fileId.takeIf { id -> id > 0 } }.toSet()
        val playingFileId = StreamPlayback.state.value.botFileId
        if (playingFileId != null && playingFileId in fileIds) {
            interaction.update { it.copy(emptyPlayerVisible = true) }
            StreamPlayback.stop()
        }
        interaction.update {
            it.copy(
                pendingBotDelete = null,
                preparingBotSaveSources = it.preparingBotSaveSources - targetIds,
            )
        }
        fileIds.forEach { fileId -> runCatching {
            appContext.startService(Intent(appContext, ConversionService::class.java).setAction("cancel_bot").putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, fileId))
        } }
        BotRuntime.removeVideos(appContext, targetIds) { error ->
            controllerScope.launch {
                if (error == null) {
                    interaction.update { it.copy(botVideoTagsOverrides = it.botVideoTagsOverrides - targetIds) }
                    val rows = applyBotTagOverrides(botState.value.videos.filterNot { it.messageId in targetIds }, interaction.value.botVideoTagsOverrides)
                    updateBotQuery(interaction.value.botQuery.withAvailableTags(rows.map(BotVideo::tags)), rows)
                    removeMediaFromQueue(targets.mapTo(mutableSetOf()) { queueItem(it).key })
                    notice("봇 목록에서 ${targets.size}개 항목을 삭제했어요. Telegram 메시지는 유지돼요.")
                    if (interaction.value.selectionMode) onClearSelection()
                } else notice(error, true, UserFacingErrors.Context.TELEGRAM)
            }
        }
    }
    override fun onExportDiagnostics() { hostEffects?.takeIf(HostEffects::diagnosticsAvailable)?.exportDiagnostics() }
    override fun onOpenExternalUrl(url: String) { runCatching { hostEffects?.openExternalUrl(url) } }

    private fun canUseBotVideo(video: BotVideo): Boolean = botState.value.let { it.connected && !it.busy && !video.needsForward }

    private fun readVideoMetadata(uri: Uri): SelectedVideoMetadata {
        var name = "선택한 영상"
        var sizeBytes: Long? = null
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0) cursor.getString(nameColumn)?.takeIf(String::isNotBlank)?.let { name = it }
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        sizeBytes = cursor.getLong(sizeColumn).takeIf { it >= 0L }
                    }
                }
            }
        }
        val durationMs = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0L }
            } finally {
                retriever.release()
            }
        }.getOrNull()
        return SelectedVideoMetadata(name, sizeBytes, durationMs)
    }

    private fun currentAudioResult(stream: StreamingState): AudioResult? {
        val path = stream.localAudioPath?.let(::canonicalPath) ?: return null
        return AppStore.history.value.firstOrNull { canonicalPath(it.path) == path }
            ?: AppStore.state.value.result?.takeIf { canonicalPath(it.path) == path }
    }
    private fun conversionIntent(format: AudioFormat) = Intent(appContext, ConversionService::class.java).putExtra("format", format.name)
    private fun saveQuery(bundle: Bundle, key: String, query: ListQuery) {
        bundle.putString("${key}Text", query.text)
        bundle.putString("${key}Sort", query.sortKey.name)
        bundle.putBoolean("${key}Ascending", query.ascending)
        bundle.putString("${key}Range", query.range.name)
        bundle.putStringArrayList("${key}Tags", ArrayList(query.tags))
    }
    private fun startConversion(request: Intent, stage: String, failure: String): Boolean {
        val origin = if (request.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0) > 0) {
            io.github.polarfox0317.sikboonge.ConversionOrigin.BOT_SAVE
        } else {
            io.github.polarfox0317.sikboonge.ConversionOrigin.LOCAL_CONVERT
        }
        AppStore.state.value = ConversionState(origin = origin, running = true, stage = stage)
        return try {
            appContext.startForegroundService(request)
            true
        } catch (_: Exception) {
            AppStore.state.value = ConversionState(origin = origin, error = failure)
            false
        }
    }
    private fun notice(text: String, error: Boolean = false, context: UserFacingErrors.Context = UserFacingErrors.Context.GENERAL) {
        val sanitized = if (error) UserFacingErrors.fromErrorString(text, context) ?: return else text
        controllerScope.launch { mutableMessages.emit(UiMessage.Text(sanitized, error)) }
    }
    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun queueItem(result: AudioResult) = QueueItemUi(
        key = "local:${canonicalPath(result.path)}", title = result.name, artworkPath = result.artworkPath,
        sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = result.path,
    )
    private fun queueItem(video: BotVideo) = QueueItemUi(
        key = "bot:${video.chatId}:${video.messageId}", title = video.displayName,
        artworkPath = video.thumbnailPath, artworkBase64 = video.thumbnailBase64,
        sourceKind = PlaybackSource.BOT, botMessageId = video.messageId, botChatId = video.chatId, botFileId = video.fileId,
    )
    private fun queueItem(stream: StreamingState) = QueueItemUi(
        key = sourceKey(stream), title = stream.title, artworkPath = stream.artworkPath, artworkBase64 = stream.artworkBase64,
        sourceKind = stream.source, localAudioPath = stream.localAudioPath, botFileId = stream.botFileId,
    )
    private fun queueItemMatches(item: QueueItemUi, stream: StreamingState): Boolean = when (stream.source) {
        PlaybackSource.LOCAL_AUDIO -> item.sourceKind == stream.source && item.localAudioPath?.let(::canonicalPath) == stream.localAudioPath?.let(::canonicalPath)
        PlaybackSource.BOT -> item.sourceKind == stream.source && item.botFileId == stream.botFileId
        PlaybackSource.NONE -> false
    }
    private fun sourceKey(stream: StreamingState): String = when {
        stream.source == PlaybackSource.LOCAL_AUDIO -> stream.localAudioPath?.let(::canonicalPath)?.let { "local:$it" } ?: "local:invalid"
        stream.source == PlaybackSource.BOT -> stream.botFileId?.let { "bot:$it" } ?: "bot:invalid"
        else -> ""
    }
    private fun canonicalPath(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath)
    override fun onCleared() { cancelPendingBotPlayback(); PlaybackQueueTransport.unbind(this); hostEffects = null; controllerScope.cancel(); super.onCleared() }
}

/** Use ViewModelProvider(activity, AppControllerFactory(activity.applicationContext))[AppController::class.java]. */
class AppControllerFactory(
    context: Context,
    private val scope: CoroutineScope? = null,
    private val botStateOverride: StateFlow<BotUiState>? = null,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppController::class.java)) { "Unknown ViewModel class: ${modelClass.name}" }
        @Suppress("UNCHECKED_CAST")
        return AppController(appContext, scope, botStateOverride ?: BotRuntime.state) as T
    }
}
private data class InteractionState(
    val destination: Destination = Destination.Bot,
    val videoUri: String? = null,
    val videoName: String = "",
    val videoSizeBytes: Long? = null,
    val videoDurationMs: Long? = null,
    val videoArtworkPath: String? = null,
    val saveFormat: AudioFormat = AudioFormat.ORIGINAL,
    val downloadExecutionMode: DownloadExecutionMode = DownloadExecutionMode.Parallel,
    val libraryQuery: ListQuery = ListQuery(),
    val botQuery: ListQuery = ListQuery(),
    val botVideoTagsOverrides: Map<Long, List<String>> = emptyMap(),
    val libraryLayout: ListLayout = ListLayout.List,
    val botLayout: ListLayout = ListLayout.List,
    val settingsOrigin: Destination = Destination.Bot,
    val settingsPage: SettingsPage = SettingsPage.Root,
    val botSetupRequested: Boolean = false,
    val botApiIdText: String = "",
    val botApiHash: String = "",
    val botToken: String = "",
    val selectionMode: Boolean = false,
    val selectedAudioPaths: Set<String> = emptySet(),
    val selectedBotMessageIds: Set<Long> = emptySet(),
    val pendingLibraryDelete: List<AudioResult>? = null,
    val pendingBotDelete: Set<Long>? = null,
    val pendingSaveResult: AudioResult? = null,
    val saving: Boolean = false,
    val renamingAudioPath: String? = null,
    val preparingBotSaveSources: Map<Long, Int> = emptyMap(),
    val preparingPlayerSaveSourceKey: String? = null,
    val notificationsAllowed: Boolean = false,
    val diagnosticsAvailable: Boolean = false,
    val playerExpanded: Boolean = true,
    val emptyPlayerVisible: Boolean = false,
    val playerSaveSourceKey: String? = null,
    val queue: List<QueueItemUi> = emptyList(),
    val queueIndex: Int = -1,
    val queueVisible: Boolean = false,
    val queueEditing: Boolean = false,
    val queueSelectedKeys: Set<String> = emptySet(),
    val queueResumeIndex: Int = 0,
    /** An explicit play waiting on the continue/restart question; transient interaction state. */
    val resumePrompt: ResumePrompt? = null,
) {
    fun snapshot(
        conversion: ConversionState,
        history: List<AudioResult>,
        downloads: List<LibraryDownload>,
        stream: StreamingState,
        bot: BotUiState,
        paused: PausedDisplay = PausedDisplay(null, -1),
    ) = AppUiState(
        pausedDisplay = paused.stream, pausedEntryIndex = paused.entryIndex,
        resumePrompt = resumePrompt,        bot = bot.copy(videos = applyBotTagOverrides(bot.videos, botVideoTagsOverrides)),
        libraryTagVocabulary = tagVocabulary(history.map(AudioResult::tags)),
        botTagVocabulary = tagVocabulary(bot.videos.map { video -> botVideoTagsOverrides[video.messageId] ?: video.tags }),
        allTagVocabulary = tagVocabulary(history.map(AudioResult::tags), bot.videos.map { video -> botVideoTagsOverrides[video.messageId] ?: video.tags }),
        destination = destination, videoUri = videoUri, videoName = videoName,
        videoSizeBytes = videoSizeBytes, videoDurationMs = videoDurationMs, videoArtworkPath = videoArtworkPath,
        saveFormat = saveFormat, downloadExecutionMode = downloadExecutionMode,
        libraryQuery = libraryQuery, botQuery = botQuery,
        libraryLayout = libraryLayout, botLayout = botLayout, settingsOrigin = settingsOrigin, settingsPage = settingsPage,
        conversion = conversion, history = history, downloads = downloads,
        stream = stream, botSetupRequested = botSetupRequested, botApiIdText = botApiIdText, botApiHash = botApiHash, botToken = botToken,
        selectionMode = selectionMode, selectedAudioPaths = selectedAudioPaths, selectedBotMessageIds = selectedBotMessageIds,
        pendingLibraryDelete = pendingLibraryDelete, pendingBotDelete = pendingBotDelete, pendingSaveResult = pendingSaveResult,
        saving = saving, renamingAudioPath = renamingAudioPath,
        preparingBotSaveSources = preparingBotSaveSources,
        preparingPlayerSaveSourceKey = preparingPlayerSaveSourceKey,
        notificationsAllowed = notificationsAllowed, diagnosticsAvailable = diagnosticsAvailable,
        playerExpanded = playerExpanded, emptyPlayerVisible = emptyPlayerVisible, playerSaveSourceKey = playerSaveSourceKey,
        queue = queue, queueIndex = queueIndex, queueVisible = queueVisible,
        queueEditing = queueEditing, queueSelectedKeys = queueSelectedKeys, queueResumeIndex = queueResumeIndex,
    )
}

private data class SelectedVideoMetadata(
    val name: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
)

private data class SavePreparationInputs(
    val conversion: ConversionState,
    val downloads: List<LibraryDownload>,
    val bot: BotUiState,
    val stream: StreamingState,
)

/** Throttle for position-only bookmark writes; pause, end and stop always write immediately. */
private const val BOOKMARK_WRITE_INTERVAL_MS = 5_000L
