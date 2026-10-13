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
import io.github.polarfox0317.sikboonge.PlaybackQueueTransport
import io.github.polarfox0317.sikboonge.QueueTransportAvailability
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
class AppController(
    context: Context,
    scope: CoroutineScope? = null,
    private val botState: StateFlow<BotUiState> = BotRuntime.state,
) : ViewModel(), AppActions {
    private val appContext = context.applicationContext
    private val savedUiPreferences = appContext.getSharedPreferences("sikboonge_ui_preferences", Context.MODE_PRIVATE)
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

    val actions: AppActions = this
    val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()
    private val libraryRows = combine(AppStore.history, AppStore.downloads) { history, downloads ->
        history to downloads
    }
    val uiState: StateFlow<AppUiState> = combine(
        interaction, AppStore.state, libraryRows, StreamPlayback.state, botState,
    ) { input, conversion, library, stream, bot ->
        input.snapshot(conversion, library.first, library.second, stream, bot)
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
            combine(AppStore.state, AppStore.downloads, botState, StreamPlayback.state) { conversion, downloads, bot, stream ->
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
                val naturalEnd = naturalEndEdge.observe(stream.ended)
                if (PlaybackQueueTransport.owns(this@AppController) && naturalEnd) {
                    val input = interaction.value
                    val key = input.queue.getOrNull(input.queueIndex)?.key
                    when (val action = queueNaturalEndAction(
                        input.queue,
                        key,
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
                // Keep the queued identity through TDLib resolution; it may replace fileId after
                // this first source publication, and the resolved state still owns the size handoff.
                if ((keepPlayerSize || sourceChanged) && !stream.resolving) clearQueuePlaybackPreference()
                if (sourceChanged) {
                    lastPlayerSourceKey = key
                    interaction.update { input ->
                        if (key.isEmpty()) input.copy(playerExpanded = true, queueIndex = -1, queueResumeIndex = 0, queueVisible = false, queueEditing = false, queueSelectedKeys = emptySet())
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
                            val expanded = if (keepPlayerSize) input.playerExpanded else true
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
                    notice("수신 확인 · $diagnostic")
                }
            }
        }
        controllerScope.launch {
            botState.map { it.error }.distinctUntilChanged().collect { error ->
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
        lastPlayerSourceKey = currentSourceKey
        val restoredQueue = run {
            val candidates = (AppStore.history.value + listOfNotNull(AppStore.state.value.result)).map(::queueItem) +
                botState.value.videos.map(::queueItem)
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
            playerExpanded = if (sameSource) saved.getBoolean("playerExpanded", true) else true,
            emptyPlayerVisible = saved.getBoolean("emptyPlayerVisible") && currentSourceKey.isEmpty(),
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
        interaction.update { it.copy(destination = destination, settingsPage = SettingsPage.Root, pendingLibraryDelete = null, pendingBotDelete = null) }
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
        val stream = StreamPlayback.state.value
        val sameCurrentFile = stream.localAudioPath?.let(::canonicalPath) == canonicalPath(result.path)
        if (sameCurrentFile && stream.playing) {
            StreamPlayback.pause()
            return
        }
        clearQueuePlaybackPreference()
        startFreshQueue(queueItem(result))
        if (sameCurrentFile && !stream.ended) StreamPlayback.resume() else playAudio(result)
    }

    private fun playAudio(result: AudioResult) = StreamPlayback.playLocal(appContext, result)

    override fun onPlayBotVideo(video: BotVideo) {
        if (!canUseBotVideo(video)) return
        clearQueuePlaybackPreference()
        val item = queueItem(video)
        startFreshQueue(item)
        preservePlayerSizeBotQueueKey = item.key
        playBotVideo(video)
    }

    private fun playBotVideo(video: BotVideo): Boolean {
        if (!canUseBotVideo(video)) return false
        BotRuntime.play(appContext, video)
        return true
    }

    private fun startFreshQueue(item: QueueItemUi) {
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
        val present = interaction.value.queue.any { it.key == item.key }
        if (!present) interaction.update { input ->
            val wasIdleAndEmpty = StreamPlayback.state.value.source == PlaybackSource.NONE &&
                input.queue.isEmpty() && input.queueIndex < 0
            input.copy(
                queue = appendQueueEntry(input.queue, item),
                emptyPlayerVisible = input.emptyPlayerVisible || wasIdleAndEmpty,
                playerExpanded = if (wasIdleAndEmpty) false else input.playerExpanded,
            )
        }
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
                        interaction.update { input ->
                            val selected = selectQueueEntry(input.queue, target)
                            input.copy(
                                emptyPlayerVisible = false,
                                queue = selected.queue,
                                queueIndex = selected.index,
                                queueResumeIndex = selected.resumeIndex,
                            )
                        }
                        preservePlayerSizeForQueuePlayback(result)
                        playAudio(result)
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
                        // The same connected/busy guard as a Bot-card play action.
                        if (!canUseBotVideo(video)) return
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
        if (keys.isEmpty()) return
        val current = interaction.value
        val mutation = removeQueueEntries(current.queue, current.queueIndex, current.queueResumeIndex, keys)
        interaction.update { input ->
            val updated = if (input.queue == current.queue && input.queueIndex == current.queueIndex && input.queueResumeIndex == current.queueResumeIndex) {
                mutation
            } else removeQueueEntries(input.queue, input.queueIndex, input.queueResumeIndex, keys)
            input.copy(queue = updated.queue, queueIndex = updated.index, queueResumeIndex = updated.resumeIndex,
                emptyPlayerVisible = input.emptyPlayerVisible || updated.removedCurrent,
                queueSelectedKeys = input.queueSelectedKeys - keys)
        }
        if (mutation.removedCurrent) {
            clearQueuePlaybackPreference()
            StreamPlayback.stop()
        }
    }

    override fun onPause() = StreamPlayback.pause()
    override fun onResume() {
        val stream = StreamPlayback.state.value
        if (stream.resolving) return
        if (stream.source == PlaybackSource.NONE && interaction.value.emptyPlayerVisible) {
            val queue = interaction.value
            val previewIndex = queue.queueResumeIndex.takeIf { it in queue.queue.indices } ?: 0
            playQueueItem(previewIndex, direction = 1)
        } else {
            StreamPlayback.resume()
        }
    }
    override fun onStop() {
        clearQueuePlaybackPreference()
        interaction.update { it.copy(queue = emptyList(), emptyPlayerVisible = false, queueIndex = -1, queueResumeIndex = 0, queueVisible = false, queueEditing = false, queueSelectedKeys = emptySet()) }
        StreamPlayback.stop()
    }
    override fun onSeek(positionMs: Long) = StreamPlayback.seekTo(positionMs)

    override fun onRetryStream() {
        clearQueuePlaybackPreference()
        val stream = StreamPlayback.state.value
        when {
            stream.source == PlaybackSource.LOCAL_AUDIO -> currentAudioResult(stream)?.let { StreamPlayback.playLocal(appContext, it) }
            stream.source == PlaybackSource.BOT -> stream.botFileId?.let { fileId ->
                botState.value.videos.firstOrNull { it.fileId == fileId }?.let(::playBotVideo)
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
        if (playingPath != null && playingPath in paths) {
            interaction.update { it.copy(emptyPlayerVisible = true) }
            StreamPlayback.stop()
        }
        try {
            val outcome = AppStore.removeMany(appContext, results)
            updateLibraryQuery(interaction.value.libraryQuery.withAvailableTags(AppStore.history.value.map(AudioResult::tags)), AppStore.history.value)
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
                val input = interaction.value
                val ids = filterBot(applyBotTagOverrides(botState.value.videos, input.botVideoTagsOverrides), input.botQuery).mapTo(mutableSetOf(), BotVideo::messageId)
                interaction.update { it.copy(selectedBotMessageIds = if (ids.isNotEmpty() && it.selectedBotMessageIds.containsAll(ids)) emptySet() else ids) }
            }
            else -> Unit
        }
    }
    override fun onClearSelection() { interaction.update { it.copy(selectionMode = false, selectedAudioPaths = emptySet(), selectedBotMessageIds = emptySet()) } }

    override fun onConnectBot(credentials: BotCredentials) {
        val submitted = credentials.copy(apiHash = credentials.apiHash.trim(), token = credentials.token.trim())
        BotRuntime.connect(appContext, submitted)
    }
    override fun onDisconnectBot() {
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
                    removeQueueItems(targets.mapTo(mutableSetOf()) { queueItem(it).key })
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
    override fun onCleared() { PlaybackQueueTransport.unbind(this); hostEffects = null; controllerScope.cancel(); super.onCleared() }
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
) {
    fun snapshot(
        conversion: ConversionState,
        history: List<AudioResult>,
        downloads: List<LibraryDownload>,
        stream: StreamingState,
        bot: BotUiState,
    ) = AppUiState(
        bot = bot.copy(videos = applyBotTagOverrides(bot.videos, botVideoTagsOverrides)),
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
