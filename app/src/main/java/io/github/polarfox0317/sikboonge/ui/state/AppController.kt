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
import io.github.polarfox0317.sikboonge.BotUiState
import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.ConversionService
import io.github.polarfox0317.sikboonge.ConversionState
import io.github.polarfox0317.sikboonge.ExternalPlayback
import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.LocalPlaybackPolicy
import io.github.polarfox0317.sikboonge.StreamPlayback
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.UserFacingErrors
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class AppController(context: Context, scope: CoroutineScope? = null) : ViewModel(), AppActions {
    private val appContext = context.applicationContext
    private val savedUiPreferences = appContext.getSharedPreferences("sikboonge_ui_preferences", Context.MODE_PRIVATE)
    private val controllerJob = SupervisorJob(scope?.coroutineContext?.get(Job))
    private val controllerScope = CoroutineScope(Dispatchers.Main.immediate + controllerJob)
    private val interaction = MutableStateFlow(InteractionState(
        saveFormat = AudioFormat.entries.firstOrNull { it.name == savedUiPreferences.getString("save_format", null) } ?: AudioFormat.ORIGINAL,
        libraryLayout = ListLayout.entries.firstOrNull { it.name == savedUiPreferences.getString("library_layout", null) } ?: ListLayout.List,
        botLayout = ListLayout.entries.firstOrNull { it.name == savedUiPreferences.getString("bot_layout", null) } ?: ListLayout.List,
        notificationsAllowed = notificationsAllowed(),
    ))
    private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 64)
    private var hostEffects: HostEffects? = null
    private var handledIncomingEventToken = 0
    private var lastPlayerSourceKey = sourceKey(StreamPlayback.state.value)
    private var hostWasBound = false
    private var selectedVideoGeneration = 0L
    private var preservePlayerSizeSourceKey: String? = null
    private var preservePlayerSizeBotQueueKey: String? = null

    val actions: AppActions = this
    val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()
    val uiState: StateFlow<AppUiState> = combine(
        interaction, AppStore.state, AppStore.history, StreamPlayback.state, BotRuntime.state,
    ) { input, conversion, history, stream, bot ->
        input.snapshot(conversion, history, stream, bot)
    }.stateIn(
        controllerScope, SharingStarted.Eagerly,
        interaction.value.snapshot(AppStore.state.value, AppStore.history.value, StreamPlayback.state.value, BotRuntime.state.value),
    )

    init {
        controllerScope.launch {
            combine(AppStore.state, BotRuntime.state) { conversion, bot -> conversion to bot }
                .collect { (conversion, bot) ->
                    interaction.update { input ->
                        input.copy(
                            preparingBotSaveMessageId = input.preparingBotSaveMessageId.takeUnless {
                                conversion.running || bot.error != null || !bot.connected
                            },
                            playerSaveSourceKey = input.playerSaveSourceKey.takeIf { conversion.running },
                            botSetupRequested = input.botSetupRequested && !(bot.connected && bot.paired),
                        )
                    }
                }
        }
        controllerScope.launch {
            StreamPlayback.state.collect { stream ->
                val key = sourceKey(stream)
                val botQueueKey = preservePlayerSizeBotQueueKey
                // TDLib can assign a new file ID while resolving the same queued message.
                val sameQueuedBotMessage = botQueueKey != null && stream.source == PlaybackSource.BOT &&
                    BotRuntime.state.value.videos.any { it.fileId == stream.botFileId && queueItem(it).key == botQueueKey }
                val keepPlayerSize = key == preservePlayerSizeSourceKey || sameQueuedBotMessage
                if (keepPlayerSize || key != lastPlayerSourceKey) clearQueuePlaybackPreference()
                if (key != lastPlayerSourceKey) {
                    lastPlayerSourceKey = key
                    interaction.update { input ->
                        if (key.isEmpty()) input.copy(playerExpanded = true, queueIndex = -1, queueResumeIndex = 0, queueVisible = false, queueEditing = false, queueSelectedKeys = emptySet())
                        else {
                            val resolvedBotKeys = if (stream.source == PlaybackSource.BOT) BotRuntime.state.value.videos
                                .filter { it.fileId == stream.botFileId }.mapTo(mutableSetOf()) { queueItem(it).key } else emptySet()
                            val queue = input.queue.map { item ->
                                if (item.key in resolvedBotKeys) item.copy(botFileId = stream.botFileId) else item
                            }
                            val index = input.queueIndex.takeIf { i -> queue.getOrNull(i)?.let { queueItemMatches(it, stream) } == true }
                                ?: queue.indexOfFirst { queueItemMatches(it, stream) }
                            val expanded = if (keepPlayerSize) input.playerExpanded else true
                            if (index >= 0) input.copy(playerExpanded = expanded, queue = queue, queueIndex = index, queueResumeIndex = index)
                            else {
                                val item = queueItem(stream)
                                val at = (if (input.queueIndex >= 0) input.queueIndex + 1 else input.queueResumeIndex).coerceIn(0, queue.size)
                                input.copy(playerExpanded = expanded, queue = queue.toMutableList().apply { add(at, item) }, queueIndex = at, queueResumeIndex = at)
                            }
                        }
                    }
                }
            }
        }
        controllerScope.launch {
            BotRuntime.state.map { it.videos }.distinctUntilChanged().collect { videos ->
                if (videos.isNotEmpty()) withContext(Dispatchers.IO) {
                    // Same migration as the former LaunchedEffect(bot.videos); no fixture branches.
                    runCatching { AppStore.attachMissingArtwork(appContext, videos) }
                }
            }
        }
        controllerScope.launch {
            var initialEmission = true
            val announcedDiagnostics = mutableSetOf<String>()
            BotRuntime.state.map { it.receiveDiagnostic.trim() }.distinctUntilChanged().collect { diagnostic ->
                if (initialEmission) {
                    initialEmission = false
                    if (diagnostic.isNotBlank()) announcedDiagnostics.add(diagnostic)
                } else if (diagnostic.isNotBlank() && announcedDiagnostics.add(diagnostic)) {
                    notice("수신 확인 · $diagnostic")
                }
            }
        }
        controllerScope.launch {
            BotRuntime.state.map { it.error }.distinctUntilChanged().collect { error ->
                error?.let { notice(it, true, UserFacingErrors.Context.TELEGRAM) }
            }
        }
        controllerScope.launch {
            StreamPlayback.state.map { it.error to (it.localAudioPath != null) }.distinctUntilChanged()
                .collect { (error, local) ->
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
            AppStore.state.map { Triple(it.error, it.result, it.stage.takeIf { stage -> stage == "취소했어요" }) }
                .distinctUntilChanged().collect { (error, result, cancelled) ->
                    when {
                        error != null -> notice(error, true)
                        result != null -> notice("추출 완료 · 보관함에서 재생, 저장, 공유할 수 있어요.")
                        cancelled != null -> notice(cancelled)
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
        putString("settingsOrigin", when (input.settingsOrigin) {
            Destination.Library -> "library"
            Destination.Bot -> "bot"
            else -> "convert"
        })
        putBoolean("botSetupRequested", input.botSetupRequested)
        putBoolean("playerExpanded", input.playerExpanded)
        putString("playerSourceKey", lastPlayerSourceKey)
        putString("playerSaveSourceKey", input.playerSaveSourceKey)
        putStringArrayList("queueKeys", ArrayList(input.queue.map(QueueItemUi::key)))
        putInt("queueIndex", input.queueIndex)
        putInt("queueResumeIndex", input.queueResumeIndex)
        putString("queueCurrentKey", input.queue.getOrNull(input.queueIndex)?.key)
        putInt("handledIncomingEventToken", handledIncomingEventToken)
    }

    /** A retained ViewModel is already newer than its Bundle; restore only a new process's instance. */
    internal fun restoreRestorableState(saved: Bundle) {
        if (hostWasBound) return
        val savedFormat = sequenceOf("saveFormat", "botFormat", "listenFormat")
            .mapNotNull { key -> AudioFormat.entries.firstOrNull { it.name == saved.getString(key) } }
            .firstOrNull() ?: AudioFormat.ORIGINAL
        fun layout(key: String) = ListLayout.entries.firstOrNull { it.name == saved.getString(key) } ?: ListLayout.List
        handledIncomingEventToken = saved.getInt("handledIncomingEventToken")
        val currentSourceKey = sourceKey(StreamPlayback.state.value)
        val sameSource = saved.getString("playerSourceKey") == currentSourceKey
        lastPlayerSourceKey = currentSourceKey
        val restoredQueue = run {
            val candidates = (AppStore.history.value + listOfNotNull(AppStore.state.value.result)).map(::queueItem) +
                BotRuntime.state.value.videos.map(::queueItem)
            saved.getStringArrayList("queueKeys").orEmpty().mapNotNull { key -> candidates.firstOrNull { it.key == key } }
        }
        val savedQueueIndex = restoredQueue.indexOfFirst { it.key == saved.getString("queueCurrentKey") }
        val restoredIndex = savedQueueIndex.takeIf { i -> restoredQueue.getOrNull(i)?.let { queueItemMatches(it, StreamPlayback.state.value) } == true }
            ?: restoredQueue.indexOfFirst { queueItemMatches(it, StreamPlayback.state.value) }
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
            settingsOrigin = when (saved.getString("settingsOrigin")) {
                "library" -> Destination.Library
                "bot" -> Destination.Bot
                "convert", "listen" -> Destination.Convert
                else -> Destination.Bot
            },
            botSetupRequested = saved.getBoolean("botSetupRequested") && !BotRuntime.state.value.let { bot -> bot.connected && bot.paired },
            playerExpanded = if (sameSource) saved.getBoolean("playerExpanded", true) else true,
            playerSaveSourceKey = saved.getString("playerSaveSourceKey").takeIf { AppStore.state.value.running },
            queue = restoredQueue,
            queueIndex = if (currentSourceKey.isNotEmpty()) restoredIndex else -1,
            queueResumeIndex = saved.getInt("queueResumeIndex").coerceIn(0, restoredQueue.size),
        ) }
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
        val oldTopLevel = interaction.value.let { input -> when (input.destination) {
            Destination.Settings -> input.settingsOrigin
            else -> input.destination
        } }
        interaction.update { it.copy(destination = destination, pendingLibraryDelete = null, pendingBotDelete = null) }
        onClearSelection()
        return oldTopLevel == destination
    }

    override fun onOpenSettings() {
        val origin = interaction.value.destination
        if (origin != Destination.Convert && origin != Destination.Library && origin != Destination.Bot) return
        onClearSelection()
        interaction.update { it.copy(destination = Destination.Settings, settingsOrigin = origin, pendingLibraryDelete = null, pendingBotDelete = null) }
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
            interaction.update { it.copy(destination = it.settingsOrigin) }; true
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
        val request = conversionIntent(input.saveFormat).putExtra("uri", uri)
        request.clipData = ClipData.newRawUri("영상", Uri.parse(uri))
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startConversion(request, "추출 준비 중", "추출을 시작하지 못했어요. 앱을 다시 열고 시도해 주세요.")
    }

    override fun onSaveFormatChanged(value: AudioFormat) {
        if (AppStore.state.value.running || interaction.value.preparingBotSaveMessageId != null) return
        savedUiPreferences.edit().putString("save_format", value.name).apply()
        interaction.update { it.copy(saveFormat = value) }
    }

    override fun onLibraryQueryChanged(query: ListQuery) = updateLibraryQuery(query, AppStore.history.value)

    /** Debug fixtures supply their own data while retaining the controller-owned query and selection. */
    internal fun updateLibraryQuery(query: ListQuery, items: List<AudioResult>) {
        val visiblePaths = filterLibrary(items, query).mapTo(mutableSetOf(), AudioResult::path)
        interaction.update { it.copy(libraryQuery = query,
            selectedAudioPaths = if (it.selectionMode) it.selectedAudioPaths.intersect(visiblePaths) else it.selectedAudioPaths) }
    }

    override fun onBotQueryChanged(query: ListQuery) = updateBotQuery(query, BotRuntime.state.value.videos)

    internal fun updateBotQuery(query: ListQuery, items: List<BotVideo>) {
        val visibleIds = filterBot(items, query).mapTo(mutableSetOf(), BotVideo::messageId)
        interaction.update { it.copy(botQuery = query,
            selectedBotMessageIds = if (it.selectionMode) it.selectedBotMessageIds.intersect(visibleIds) else it.selectedBotMessageIds) }
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
    override fun onRequestNotificationPermission() { hostEffects?.requestNotificationPermission() }
    override fun onNotificationPermissionResult(allowed: Boolean) { interaction.update { it.copy(notificationsAllowed = allowed) } }

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
        clearQueuePlaybackPreference()
        insertAndSelectQueueItem(queueItem(result))
        playAudio(result, toggleCurrent = true)
    }

    private fun playAudio(result: AudioResult, toggleCurrent: Boolean = false) {
        val stream = StreamPlayback.state.value
        if (!toggleCurrent || stream.localAudioPath?.let(::canonicalPath) != canonicalPath(result.path) || stream.ended) StreamPlayback.playLocal(appContext, result)
        else if (stream.playing) StreamPlayback.pause() else StreamPlayback.resume()
    }

    override fun onPlayBotVideo(video: BotVideo) {
        if (!canUseBotVideo(video)) return
        clearQueuePlaybackPreference()
        insertAndSelectQueueItem(queueItem(video))
        playBotVideo(video)
    }

    private fun playBotVideo(video: BotVideo): Boolean {
        if (!canUseBotVideo(video)) return false
        BotRuntime.play(appContext, video)
        return true
    }

    private fun insertAndSelectQueueItem(item: QueueItemUi) {
        interaction.update { input ->
            val existing = input.queue.indexOfFirst { it.key == item.key }
            val at = if (existing >= 0) existing else (if (input.queueIndex >= 0) input.queueIndex + 1 else input.queueResumeIndex).coerceIn(0, input.queue.size)
            val queue = input.queue.toMutableList().apply { if (existing >= 0) set(at, item) else add(at, item) }
            input.copy(playerExpanded = true, queue = queue, queueIndex = at, queueResumeIndex = at)
        }
    }

    override fun onAddToQueue(result: AudioResult) = appendQueueItem(queueItem(result))
    override fun onAddBotVideoToQueue(video: BotVideo) {
        if (!video.needsForward) appendQueueItem(queueItem(video))
    }
    private fun appendQueueItem(item: QueueItemUi) {
        val present = interaction.value.queue.any { it.key == item.key }
        if (!present) interaction.update { it.copy(queue = it.queue + item) }
        notice(if (present) "이미 재생목록에 있어요" else "재생목록에 추가했어요")
    }

    override fun onPlayPrevious() {
        if (StreamPlayback.state.value.positionMs > 3_000L) onSeek(0L)
        else interaction.value.let { playQueueItem((if (it.queueIndex >= 0) it.queueIndex else it.queueResumeIndex) - 1, direction = -1) }
    }
    override fun onPlayNext() = interaction.value.let { playQueueItem(if (it.queueIndex >= 0) it.queueIndex + 1 else it.queueResumeIndex, direction = 1) }
    override fun onPlayQueueItem(index: Int) = playQueueItem(index, direction = 1)
    override fun onShowQueue() { interaction.update { it.copy(queueVisible = true) } }
    override fun onHideQueue() { interaction.update { it.copy(queueVisible = false, queueEditing = false, queueSelectedKeys = emptySet()) } }
    override fun onToggleQueueEditing() { interaction.update { it.copy(queueEditing = !it.queueEditing, queueSelectedKeys = emptySet()) } }
    override fun onToggleQueueSelection(key: String) {
        interaction.update { input -> if (!input.queueEditing || input.queue.none { it.key == key }) input else
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

    private fun playQueueItem(index: Int, direction: Int) {
        var target = index
        while (target in interaction.value.queue.indices) {
            val item = interaction.value.queue[target]
            when (item.sourceKind) {
                PlaybackSource.LOCAL_AUDIO -> {
                    val result = (AppStore.history.value + listOfNotNull(AppStore.state.value.result))
                        .firstOrNull { canonicalPath(it.path) == item.localAudioPath?.let(::canonicalPath) }
                    if (result != null && LocalPlaybackPolicy.ownedAudioFile(appContext.filesDir, result.path) != null) {
                        interaction.update { it.copy(queueIndex = target, queueResumeIndex = target) }
                        preservePlayerSizeForQueuePlayback(result)
                        playAudio(result)
                        return
                    }
                    notice(LocalPlaybackPolicy.INVALID_AUDIO_ERROR, true, UserFacingErrors.Context.LOCAL_MEDIA)
                }
                PlaybackSource.BOT -> {
                    val video = BotRuntime.state.value.videos.firstOrNull {
                        if (item.botMessageId != null) it.messageId == item.botMessageId && it.chatId == item.botChatId
                        else it.fileId == item.botFileId
                    }
                    if (video != null && !video.needsForward) {
                        // The same connected/busy guard as a Bot-card play action.
                        if (!canUseBotVideo(video)) return
                        interaction.update { input -> input.copy(queueIndex = target, queueResumeIndex = target,
                            queue = input.queue.mapIndexed { i, queued -> if (i == target) queued.copy(botFileId = video.fileId) else queued }) }
                        preservePlayerSizeSourceKey = "bot:${video.fileId}"
                        preservePlayerSizeBotQueueKey = queueItem(video).key
                        playBotVideo(video)
                        return
                    }
                    notice("봇 목록에서 영상을 다시 선택해 주세요.", true, UserFacingErrors.Context.TELEGRAM)
                }
                PlaybackSource.NONE -> return
            }
            removeQueueItems(setOf(item.key))
            if (direction < 0) target--
        }
    }

    /** The debug queue uses the same local source transition preference as the production queue. */
    internal fun preservePlayerSizeForQueuePlayback(result: AudioResult) {
        preservePlayerSizeSourceKey = "local:${canonicalPath(result.path)}"
        preservePlayerSizeBotQueueKey = null
    }

    private fun clearQueuePlaybackPreference() {
        preservePlayerSizeSourceKey = null
        preservePlayerSizeBotQueueKey = null
    }

    private fun removeQueueItems(keys: Set<String>) {
        interaction.update { input ->
            val removed = removeQueueEntries(input.queue, input.queueIndex, input.queueResumeIndex, keys)
            input.copy(queue = removed.queue, queueIndex = removed.index, queueResumeIndex = removed.resumeIndex,
                queueSelectedKeys = input.queueSelectedKeys - keys)
        }
    }

    override fun onPause() = StreamPlayback.pause()
    override fun onResume() = StreamPlayback.resume()
    override fun onStop() {
        clearQueuePlaybackPreference()
        interaction.update { it.copy(queueIndex = -1, queueResumeIndex = 0, queueVisible = false, queueEditing = false, queueSelectedKeys = emptySet()) }
        StreamPlayback.stop()
    }
    override fun onSeek(positionMs: Long) = StreamPlayback.seekTo(positionMs)

    override fun onRetryStream() {
        clearQueuePlaybackPreference()
        val stream = StreamPlayback.state.value
        when {
            stream.source == PlaybackSource.LOCAL_AUDIO -> currentAudioResult(stream)?.let { StreamPlayback.playLocal(appContext, it) }
            stream.source == PlaybackSource.BOT -> stream.botFileId?.let { fileId ->
                BotRuntime.state.value.videos.firstOrNull { it.fileId == fileId }?.let(::playBotVideo)
            }
        }
    }

    override fun onExternalPlayStream() {
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
                val video = stream.botFileId?.let { fileId -> BotRuntime.state.value.videos.firstOrNull { it.fileId == fileId } }
                if (video == null) notice("봇 목록에서 영상을 다시 선택해 주세요.", true)
                else onExternalPlayBotVideo(video)
            }
        }
    }

    override fun onSaveStream() {
        // Freeze source metadata now; later UI changes cannot retarget this save.
        val stream = StreamPlayback.state.value
        val botFileId = stream.botFileId ?: return
        if (stream.source != PlaybackSource.BOT || AppStore.state.value.running || !BotRuntime.state.value.connected) return
        val input = interaction.value
        val request = conversionIntent(input.saveFormat)
            .putExtra("name", stream.title)
            .putExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH, stream.artworkPath)
            .putExtra(AudioArtworkStore.EXTRA_ARTWORK_BASE64, stream.artworkBase64)
        request.putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, botFileId)
            .putExtra(BotRuntime.EXTRA_TDLIB_NAME, stream.title)
            .putExtra(BotRuntime.EXTRA_TDLIB_SIZE, stream.sourceBytes)
        interaction.update { it.copy(playerSaveSourceKey = sourceKey(stream)) }
        startConversion(request, "오디오 저장 준비 중", "오디오 저장을 시작하지 못했어요. 앱을 다시 열고 시도해 주세요.")
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
                        queue = input.queue.map { item -> if (item.localAudioPath?.let(::canonicalPath) == oldPath) queueItem(renamed) else item },
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
        if (playingPath != null && playingPath in paths) StreamPlayback.stop()
        try {
            val outcome = AppStore.removeMany(appContext, results)
            removeQueueItems(results.mapTo(mutableSetOf()) { queueItem(it).key })
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
                val ids = filterBot(BotRuntime.state.value.videos, interaction.value.botQuery).mapTo(mutableSetOf(), BotVideo::messageId)
                interaction.update { it.copy(selectedBotMessageIds = if (ids.isNotEmpty() && it.selectedBotMessageIds.containsAll(ids)) emptySet() else ids) }
            }
            else -> Unit
        }
    }
    override fun onClearSelection() { interaction.update { it.copy(selectionMode = false, selectedAudioPaths = emptySet(), selectedBotMessageIds = emptySet()) } }

    override fun onConnectBot(credentials: BotCredentials) {
        val submitted = credentials.copy(apiHash = credentials.apiHash.trim(), token = credentials.token.trim())
        interaction.update { it.copy(botApiIdText = "", botApiHash = "", botToken = "") }
        BotRuntime.connect(appContext, submitted)
    }
    override fun onDisconnectBot() {
        onCloseBotSetup()
        BotRuntime.disconnect(appContext)
        onTopLevelSelected(Destination.Bot)
    }
    override fun onPairBot() = BotRuntime.pair(appContext)
    override fun onRefreshBot() {
        val bot = BotRuntime.state.value
        if (interaction.value.destination == Destination.Bot && bot.setupComplete && bot.connected && bot.paired && !bot.busy && !bot.refreshing) BotRuntime.refreshInbox(appContext)
    }
    override fun onSaveBotVideo(video: BotVideo, format: AudioFormat) {
        if (!canUseBotVideo(video) || AppStore.state.value.running || interaction.value.preparingBotSaveMessageId != null) return
        interaction.update { it.copy(preparingBotSaveMessageId = video.messageId) }
        notice("보관함에 저장할 오디오를 준비하고 있어요.")
        BotRuntime.save(appContext, video, interaction.value.saveFormat.name)
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
        val targets = BotRuntime.state.value.videos.filter { it.messageId in messageIds }
        if (targets.isEmpty()) {
            interaction.update { it.copy(pendingBotDelete = null) }
            notice("봇 목록이 바뀌어 선택한 항목을 찾지 못했어요. 목록을 확인해 주세요.", true)
            if (interaction.value.selectionMode) onClearSelection()
            return
        }
        val targetIds = targets.mapTo(mutableSetOf(), BotVideo::messageId)
        val fileIds = targets.mapNotNull { it.fileId.takeIf { id -> id > 0 } }.toSet()
        val playingFileId = StreamPlayback.state.value.botFileId
        if (playingFileId != null && playingFileId in fileIds) StreamPlayback.stop()
        interaction.update { it.copy(pendingBotDelete = null, preparingBotSaveMessageId = it.preparingBotSaveMessageId.takeUnless { id -> id != null && id in targetIds }) }
        fileIds.forEach { fileId -> runCatching {
            appContext.startService(Intent(appContext, ConversionService::class.java).setAction("cancel_bot").putExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, fileId))
        } }
        BotRuntime.removeVideos(appContext, targetIds) { error ->
            controllerScope.launch {
                if (error == null) {
                    removeQueueItems(targets.mapTo(mutableSetOf()) { queueItem(it).key })
                    notice("봇 목록에서 ${targets.size}개 항목을 삭제했어요. Telegram 메시지는 유지돼요.")
                    if (interaction.value.selectionMode) onClearSelection()
                } else notice(error, true, UserFacingErrors.Context.TELEGRAM)
            }
        }
    }
    override fun onExportDiagnostics() { hostEffects?.takeIf(HostEffects::diagnosticsAvailable)?.exportDiagnostics() }
    override fun onOpenExternalUrl(url: String) { runCatching { hostEffects?.openExternalUrl(url) } }

    private fun canUseBotVideo(video: BotVideo): Boolean = BotRuntime.state.value.let { it.connected && !it.busy && !video.needsForward }

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
    private fun startConversion(request: Intent, stage: String, failure: String) {
        AppStore.state.value = ConversionState(running = true, stage = stage)
        try { appContext.startForegroundService(request) }
        catch (_: Exception) { AppStore.state.value = ConversionState(error = failure) }
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
    override fun onCleared() { hostEffects = null; controllerScope.cancel(); super.onCleared() }
}

/** Use ViewModelProvider(activity, AppControllerFactory(activity.applicationContext))[AppController::class.java]. */
class AppControllerFactory(context: Context, private val scope: CoroutineScope? = null) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppController::class.java)) { "Unknown ViewModel class: ${modelClass.name}" }
        @Suppress("UNCHECKED_CAST")
        return AppController(appContext, scope) as T
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
    val libraryQuery: ListQuery = ListQuery(),
    val botQuery: ListQuery = ListQuery(),
    val libraryLayout: ListLayout = ListLayout.List,
    val botLayout: ListLayout = ListLayout.List,
    val settingsOrigin: Destination = Destination.Bot,
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
    val preparingBotSaveMessageId: Long? = null,
    val notificationsAllowed: Boolean = false,
    val diagnosticsAvailable: Boolean = false,
    val playerExpanded: Boolean = true,
    val playerSaveSourceKey: String? = null,
    val queue: List<QueueItemUi> = emptyList(),
    val queueIndex: Int = -1,
    val queueVisible: Boolean = false,
    val queueEditing: Boolean = false,
    val queueSelectedKeys: Set<String> = emptySet(),
    val queueResumeIndex: Int = 0,
) {
    fun snapshot(conversion: ConversionState, history: List<AudioResult>, stream: StreamingState, bot: BotUiState) = AppUiState(
        destination = destination, videoUri = videoUri, videoName = videoName,
        videoSizeBytes = videoSizeBytes, videoDurationMs = videoDurationMs, videoArtworkPath = videoArtworkPath,
        saveFormat = saveFormat, libraryQuery = libraryQuery, botQuery = botQuery,
        libraryLayout = libraryLayout, botLayout = botLayout, settingsOrigin = settingsOrigin,
        conversion = conversion, history = history,
        stream = stream, bot = bot, botSetupRequested = botSetupRequested, botApiIdText = botApiIdText, botApiHash = botApiHash, botToken = botToken,
        selectionMode = selectionMode, selectedAudioPaths = selectedAudioPaths, selectedBotMessageIds = selectedBotMessageIds,
        pendingLibraryDelete = pendingLibraryDelete, pendingBotDelete = pendingBotDelete, pendingSaveResult = pendingSaveResult,
        saving = saving, renamingAudioPath = renamingAudioPath, preparingBotSaveMessageId = preparingBotSaveMessageId,
        notificationsAllowed = notificationsAllowed, diagnosticsAvailable = diagnosticsAvailable,
        playerExpanded = playerExpanded, playerSaveSourceKey = playerSaveSourceKey,
        queue = queue, queueIndex = queueIndex, queueVisible = queueVisible,
        queueEditing = queueEditing, queueSelectedKeys = queueSelectedKeys, queueResumeIndex = queueResumeIndex,
    )
}

private data class SelectedVideoMetadata(
    val name: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
)
