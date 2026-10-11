package io.github.polarfox0317.sikboonge.ui.state

import android.net.Uri
import io.github.polarfox0317.sikboonge.AudioFormat
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotCredentials
import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.DownloadExecutionMode

/** All callbacks are invoked on the main thread, like the original Compose callbacks. */
interface AppActions {
    /** Replaces changeTab; returns true for a tab reselection so the UI can scroll to the top. */
    fun onTopLevelSelected(destination: Destination): Boolean
    /** Replaces handleBack after the host has handled IME and menus; returns whether it consumed back. */
    fun onBack(): Boolean
    /** Replaces BotCredentialDraftState.apiIdText's input callback. */
    fun onBotApiIdChanged(value: String)
    /** Replaces BotCredentialDraftState.apiHash's input callback. */
    fun onBotApiHashChanged(value: String)
    /** Replaces BotCredentialDraftState.token's input callback. */
    fun onBotTokenChanged(value: String)
    /** Replaces the video MIME wildcard picker. */
    fun onPickVideo()
    /** Replaces select and the OpenDocument result callback; null means cancellation. */
    fun onVideoPicked(uri: Uri?)
    fun onClearVideo()
    /** Replaces the incoming-event LaunchedEffect for shared video URIs. */
    fun onSharedIntent(uri: String?, eventToken: Int)
    /** Starts audio extraction from the selected local video. */
    fun onExtractVideo()
    /** Debug review fixture action; production controllers intentionally do nothing. */
    fun onSelectReviewSampleVideo() = Unit
    /** Shared output format for local conversion, player saves and bot saves. */
    fun onSaveFormatChanged(value: AudioFormat)
    /** Persists how queued Bot video saves are executed. */
    fun onDownloadExecutionModeChanged(value: DownloadExecutionMode)
    fun onLibraryQueryChanged(query: ListQuery)
    fun onBotQueryChanged(query: ListQuery)
    fun onLayoutChanged(destination: Destination, layout: ListLayout)
    fun onOpenSettings()
    fun onOpenLicenses() = Unit
    fun onOpenLicense(id: String) = Unit
    /** Opens the reconnect form within the Bot tab while preserving its cached inbox. */
    fun onOpenBotSetup()
    fun onCloseBotSetup()
    /** Replaces the ConversionService cancel callback. */
    fun onCancelConversion()
    /** Cancels a specific pending or active Telegram save. */
    fun onCancelDownload(id: Long)
    fun onPauseDownload(id: Long) = Unit
    fun onResumeDownload(id: Long) = Unit
    /** Replaces notifications.launch(POST_NOTIFICATIONS). */
    fun onRequestNotificationPermission()
    /** Replaces the RequestPermission result callback. */
    fun onNotificationPermissionResult(allowed: Boolean)
    /** Defers an operation until the notification permission prompt completes. */
    fun onRequestNotificationPermissionThen(onResult: (Boolean) -> Unit) {
        onRequestNotificationPermission()
        onResult(false)
    }
    /** Replaces saveResult assignment and save.launch(result). */
    fun onSaveAudioToDevice(result: AudioResult)
    /** Replaces the SaveAudioContract result callback. */
    fun onSaveDestination(uri: Uri?)
    /** Replaces ResultCardRuntimeContent.onPlay including pause/resume/restart. */
    fun onPlayAudio(result: AudioResult)
    /** Replaces BotScreen.onPlay. */
    fun onPlayBotVideo(video: BotVideo)
    /** Replaces StreamPlaybackCard.onPause. */
    fun onPause()
    /** Replaces StreamPlaybackCard.onResume. */
    fun onResume()
    /** Replaces StreamPlaybackCard.onStop. */
    fun onStop()
    fun onPlayPrevious() = Unit
    fun onPlayNext() = Unit
    fun onPlayQueueItem(index: Int) = Unit
    fun onShowQueue() = Unit
    fun onHideQueue() = Unit
    fun onAddToQueue(result: AudioResult) = Unit
    fun onAddBotVideoToQueue(video: BotVideo) = Unit
    fun onToggleQueueEditing() = Unit
    fun onToggleQueueSelection(key: String) = Unit
    fun onDeleteQueueSelection() = Unit
    fun onMoveQueueItem(from: Int, to: Int) = Unit
    /** Updates the player expansion preference without changing playback. */
    fun setPlayerExpanded(expanded: Boolean)
    /** Replaces StreamPlaybackCard.onSeek. */
    fun onSeek(positionMs: Long)
    /** Retries playback from the local library or Bot inbox. */
    fun onRetryStream()
    /** Opens the current local-library or Bot source in an external player. */
    fun onExternalPlayStream()
    /** Replaces StreamPlaybackCard.onSave, capturing the current resolved source. */
    fun onSaveStream()
    /** Replaces ResultCardRuntimeContent.onExternalPlay. */
    fun onExternalPlayAudio(result: AudioResult)
    /** Replaces ResultCardRuntimeContent.onShare. */
    fun onShareAudio(result: AudioResult)
    /** Replaces renameLibraryItem; the requested title excludes the existing extension. */
    fun onRenameAudio(result: AudioResult, titleStem: String)
    fun onSetAudioTags(result: AudioResult, tags: List<String>)
    /** Replaces requestLibraryDelete; an empty list dismisses the pending confirmation. */
    fun onRequestLibraryDelete(results: List<AudioResult>)
    /** Replaces removeLibraryItems after user confirmation. */
    fun onConfirmLibraryDelete(results: List<AudioResult>)
    /** Replaces beginSelection. */
    fun onBeginSelection()
    /** Replaces toggleAudioSelection. */
    fun onToggleAudioSelection(path: String)
    /** Replaces toggleBotSelection. */
    fun onToggleBotSelection(messageId: Long)
    /** Replaces toggleAllVisibleSelection, including deselect-all when all rows are selected. */
    fun onSelectAllCurrentItems()
    /** Replaces clearSelection. */
    fun onClearSelection()
    /** Replaces BotScreen's trimmed credential submission and draft clearing before BotRuntime.connect. */
    fun onConnectBot(credentials: BotCredentials)
    /** Replaces BotScreen.onDisconnect after the UI confirms reset. */
    fun onDisconnectBot()
    /** Replaces BotScreen.onPair. */
    fun onPairBot()
    /** Replaces BotScreen.onRefresh with its original connected/paired/busy guard. */
    fun onRefreshBot()
    /** Replaces BotScreen.onSaveToLibrary and tracks its asynchronous preparation. */
    fun onSaveBotVideo(video: BotVideo, format: AudioFormat)
    /** Replaces BotScreen.onRename and delegates the 120-character title policy. */
    fun onRenameBotVideo(video: BotVideo, title: String)
    fun onSetBotVideoTags(video: BotVideo, tags: List<String>)
    /** Replaces BotScreen.onExternalPlay and waits for successful Bot resolution. */
    fun onExternalPlayBotVideo(video: BotVideo)
    /** Replaces requestBotDelete; an empty set dismisses the pending confirmation. */
    fun onRequestBotDelete(messageIds: Set<Long>)
    /** Replaces removeBotItems after user confirmation. */
    fun onConfirmBotDelete(messageIds: Set<Long>)
    /** Replaces exportDiagnosticLogs?.invoke(). */
    fun onExportDiagnostics()
    /** Opens an external helper URL from Bot setup. */
    fun onOpenExternalUrl(url: String)
}
