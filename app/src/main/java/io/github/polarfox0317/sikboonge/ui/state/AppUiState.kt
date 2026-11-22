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

/**
 * A queue entry retains display metadata and stable playback identity.
 *
 * [key] is the **media key**: the same file or message always maps to the same key, and it owns the
 * bookmark, the bot resolve and the real media deletion. A media may be queued more than once, so
 * [entryId] is the **occurrence identity**: one distinct value per queue entry, preserved across
 * moves, copies and a process restore. Selection, removal, current/resume pointers and list keys use
 * [entryId]; only media lookups use [key].
 */
data class QueueItemUi(
    val key: String,
    val title: String,
    val artworkPath: String? = null,
    val artworkBase64: String = "",
    val sourceKind: PlaybackSource,
    val localAudioPath: String? = null,
    val botMessageId: Long? = null,
    val botChatId: Long? = null,
    /** New occurrences get their own id; callers that re-resolve an existing row pass it explicitly. */
    val entryId: String = java.util.UUID.randomUUID().toString(),
    val botFileId: Int? = null,
)

/**
 * The pending "continue or restart?" choice for one explicit play. It carries only stable identity
 * and the saved offset; the actual play is re-derived when the user chooses, so a row deleted
 * meanwhile cancels safely instead of starting stale media.
 */
data class ResumePrompt(
    val key: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    /** True when the item was played from the queue, so its queue and order must be preserved. */
    val fromQueue: Boolean = false,
)

/** Product snapshots and interaction targets; list positions, menus and gestures belong to the UI. */data class AppUiState(
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
    /** Keeps the basic empty player visible after its current queue entry is removed. */
    val emptyPlayerVisible: Boolean = false,
    val playerSaveSourceKey: String? = null,
    val queue: List<QueueItemUi> = emptyList(),
    val queueIndex: Int = -1,
    val queueVisible: Boolean = false,
    val queueEditing: Boolean = false,
    val queueSelectedKeys: Set<String> = emptySet(),
    val queueResumeIndex: Int = 0,
    /**
     * A separated paused session is displayed through this stopped shim so the player shows the
     * paused entry (title, artwork, position) instead of live service state. Only a user press
     * resumes it. [pausedEntryIndex] names the queue row the paused player resumes.
     */
    val pausedDisplay: StreamingState? = null,
    val pausedEntryIndex: Int = -1,
    /**
     * An explicit play is waiting for the user to choose between continuing a saved position or
     * starting over. Nothing is started until the choice is made.
     */
    val resumePrompt: ResumePrompt? = null,
) {
    /** A paused session is shown only when its shim names a queue row that still exists. */
    private val separatedPause: Boolean
        get() = pausedDisplay != null && isSeparatedPausedStream(stream) && pausedEntryIndex in queue.indices

    /**
     * A paused session is displayable only through a paused entry. Without one it must not fall
     * back to the raw stream: a bare source would mislead or hide the player.
     */
    private val displayable: Boolean get() = !isSeparatedPausedStream(stream) || separatedPause

    /** A presentation-only stopped selection; it never becomes a StreamPlayback service request. */
    val playerPreview: QueueItemUi? get() = when {
        separatedPause -> queue.getOrNull(pausedEntryIndex)
        stream.source == PlaybackSource.NONE && emptyPlayerVisible ->
            queue.getOrNull(queueResumeIndex) ?: queue.firstOrNull()
        else -> null
    }

    val playerDisplayStream: StreamingState get() = when {
        separatedPause -> pausedDisplay!!
        !displayable -> StreamingState()
        else -> playerPreview?.let { item ->
            StreamingState(
                source = item.sourceKind,
                title = item.title,
                ready = true,
                botFileId = item.botFileId,
                localAudioPath = item.localAudioPath,
                artworkPath = item.artworkPath,
                artworkBase64 = item.artworkBase64,
            )
        } ?: stream
    }

    /**
     * Shared visibility rule for the shell and the player. Live or stopped sessions keep the
     * previous condition; a paused session is visible only while a paused entry is displayed.
     */
    val playerVisible: Boolean
        get() = when {
            stream.source == PlaybackSource.NONE -> emptyPlayerVisible
            isSeparatedPausedStream(stream) -> separatedPause
            else -> stream.isPlayerVisible()
        }

    /** The queue entry the player resumes at; null when nothing is resumable. */
    val resumableQueueIndex: Int
        get() = when {
            separatedPause -> pausedEntryIndex.takeIf { it in queue.indices } ?: -1
            stream.source == PlaybackSource.NONE && emptyPlayerVisible ->
                (queueResumeIndex.takeIf { it in queue.indices } ?: 0).takeIf { it in queue.indices } ?: -1
            else -> -1
        }

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
