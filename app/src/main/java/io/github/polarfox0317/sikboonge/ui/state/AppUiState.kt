package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.AudioFormat
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotUiState
import io.github.polarfox0317.sikboonge.ConversionState
import io.github.polarfox0317.sikboonge.LibraryDownload
import io.github.polarfox0317.sikboonge.StreamingState
import io.github.polarfox0317.sikboonge.PlaybackSource

sealed interface Destination {
    data object Bot : Destination
    data object Convert : Destination
    data object Library : Destination
    data object Settings : Destination
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
    val libraryQuery: ListQuery = ListQuery(),
    val botQuery: ListQuery = ListQuery(),
    val libraryLayout: ListLayout = ListLayout.List,
    val botLayout: ListLayout = ListLayout.List,
    val settingsOrigin: Destination = Destination.Bot,
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
    val videoSelected: Boolean get() = videoUri != null
    val visibleHistory: List<AudioResult> get() = filterLibrary(history, libraryQuery)
    val visibleBotVideos get() = filterBot(bot.videos, botQuery)
    val topLevelDestination: Destination
        get() = when (destination) {
            Destination.Settings -> settingsOrigin
            else -> destination
        }
}
