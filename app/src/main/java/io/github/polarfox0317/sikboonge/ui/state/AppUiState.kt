package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.AudioFormat
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotUiState
import io.github.polarfox0317.sikboonge.ConversionState
import io.github.polarfox0317.sikboonge.LibraryDownload
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.PlaybackSource
import io.github.polarfox0317.sikboonge.DownloadExecutionMode

sealed interface Destination {
    data object Bot : Destination
    data object Convert : Destination
    data object Library : Destination
    data object Settings : Destination
}

/** Settings pages remain under the originating top-level tab. */
sealed interface SettingsPage {
    data object Root : SettingsPage
    data object Licenses : SettingsPage
    data class LicenseDetail(val id: String) : SettingsPage
}

/** A queue entry retains display metadata and stable playback identity. */
data class QueueItemUi(
    val key: String,
    val title: String,
    val artworkPath: String? = null,
    val artworkBase64: String = "",
    val sourceKind: PlaybackSource,
    val localAudioPath: String? = null,
    val botMessageId: Long? = null,
    val botChatId: Long? = null,
    val botFileId: Int? = null,
)

/** Product snapshots and interaction targets; list positions, menus and gestures belong to the UI. */
data class AppUiState(
    val destination: Destination = Destination.Bot,
    val videoUri: String? = null,
    val videoName: String = "",
    val videoSizeBytes: Long? = null,
    val videoDurationMs: Long? = null,
    val videoArtworkPath: String? = null,
    val reviewSampleVideoAvailable: Boolean = false,
    val saveFormat: AudioFormat = AudioFormat.ORIGINAL,
    val downloadExecutionMode: DownloadExecutionMode = DownloadExecutionMode.Parallel,
    val libraryQuery: ListQuery = ListQuery(),
    val botQuery: ListQuery = ListQuery(),
    val libraryTagVocabulary: List<String> = emptyList(),
    val botTagVocabulary: List<String> = emptyList(),
    val allTagVocabulary: List<String> = emptyList(),
    val libraryLayout: ListLayout = ListLayout.List,
    val botLayout: ListLayout = ListLayout.List,
    val settingsOrigin: Destination = Destination.Bot,
    val settingsPage: SettingsPage = SettingsPage.Root,
    val conversion: ConversionState = ConversionState(),
    val history: List<AudioResult> = emptyList(),
    val downloads: List<LibraryDownload> = emptyList(),
    val stream: StreamingState = StreamingState(),
    val bot: BotUiState = BotUiState(),
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
    val playerSaveSourceKey: String? = null,
    val queue: List<QueueItemUi> = emptyList(),
    val queueIndex: Int = -1,
    val queueVisible: Boolean = false,
    val queueEditing: Boolean = false,
    val queueSelectedKeys: Set<String> = emptySet(),
    val queueResumeIndex: Int = 0,
) {
    val preparingBotSaveMessageId: Long? get() = preparingBotSaveSources.keys.firstOrNull()
    val preparingBotSaveMessageIds: Set<Long> get() = preparingBotSaveSources.keys
    val preparingBotSaveFileIds: Set<Int> get() = preparingBotSaveSources.values.toSet()
    val videoSelected: Boolean get() = videoUri != null
    val visibleHistory: List<AudioResult> get() = filterLibrary(history, libraryQuery)
    val visibleBotVideos get() = filterBot(bot.videos, botQuery)
    val topLevelDestination: Destination
        get() = when (destination) {
            Destination.Settings -> settingsOrigin
            else -> destination
        }
}
