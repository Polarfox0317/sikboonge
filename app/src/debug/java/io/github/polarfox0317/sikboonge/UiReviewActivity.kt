package io.github.polarfox0317.sikboonge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.polarfox0317.sikboonge.ui.shell.AppRoot
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppController
import io.github.polarfox0317.sikboonge.ui.state.AppControllerFactory
import io.github.polarfox0317.sikboonge.ui.state.Destination
import io.github.polarfox0317.sikboonge.ui.state.ListQuery
import io.github.polarfox0317.sikboonge.ui.state.QueueItemUi
import io.github.polarfox0317.sikboonge.ui.state.SelectedVideoThumbnail
import io.github.polarfox0317.sikboonge.ui.state.removeQueueEntries
import io.github.polarfox0317.sikboonge.ui.state.moveQueueEntry
import io.github.polarfox0317.sikboonge.ui.state.filterLibrary
import io.github.polarfox0317.sikboonge.ui.state.filterBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Debug review activity using local sample media only; user history and Telegram credentials stay untouched. */
class UiReviewActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private var sample by mutableStateOf<ReviewSample?>(null)
    private var sampleError by mutableStateOf<String?>(null)
    private var bot by mutableStateOf(BotUiState())
    private var extraLibrary by mutableStateOf<List<AudioResult>>(emptyList())
    private var conversion by mutableStateOf(ConversionState())
    private var sampleVideoSelected by mutableStateOf(true)
    private var sampleVideoArtworkPath by mutableStateOf<String?>(null)
    private var sampleArtworkGeneration = 0L
    private var pendingLibraryDelete by mutableStateOf<List<AudioResult>?>(null)
    private var pendingBotDelete by mutableStateOf<Set<Long>?>(null)
    private var queue by mutableStateOf<List<QueueItemUi>>(emptyList())
    private var queueIndex by mutableStateOf(-1)
    private var queueVisible by mutableStateOf(false)
    private var queueEditing by mutableStateOf(false)
    private var queueSelectedKeys by mutableStateOf<Set<String>>(emptySet())
    private var queueResumeIndex by mutableStateOf(0)
    private var extracting = false
    private var activeSessionId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        controller = ViewModelProvider(this, AppControllerFactory(applicationContext))[AppController::class.java]
        val fixtureActions = ReviewActions(controller.actions)
        setContent {
            val live by controller.uiState.collectAsStateWithLifecycle()
            val currentSample = sample
            val state = live.copy(
                videoUri = if (sampleVideoSelected && currentSample != null) "review-sample://video" else live.videoUri,
                videoArtworkPath = if (sampleVideoSelected) sampleVideoArtworkPath else live.videoArtworkPath,
                videoName = if (sampleVideoSelected && currentSample != null) "로컬 테스트 영상 · 12초 · Telegram 연결 안 함" else live.videoName,
                reviewSampleVideoAvailable = currentSample != null,
                conversion = conversion,
                history = currentSample?.libraryAudio.orEmpty() + extraLibrary,
                bot = bot,
                pendingLibraryDelete = pendingLibraryDelete,
                pendingBotDelete = pendingBotDelete,
                queue = queue, queueIndex = queueIndex, queueVisible = queueVisible,
                queueEditing = queueEditing, queueSelectedKeys = queueSelectedKeys, queueResumeIndex = queueResumeIndex,
            )
            AppRoot(controller, stateOverride = state, actionsOverride = fixtureActions)
        }
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) { ReviewSampleMedia.prepare(this@UiReviewActivity) }
            if (prepared.isSuccess) {
                sample = prepared.getOrThrow()
                bot = reviewBotState(sample!!.video.length())
                if (sampleVideoSelected) prepareSampleArtwork()
            } else {
                sampleError = prepared.exceptionOrNull()?.message ?: "로컬 테스트 샘플을 만들지 못했어요."
                bot = BotUiState(setupComplete = true, paired = true, connected = true, status = "로컬 테스트 샘플 준비 실패", error = sampleError)
            }
        }
    }

    private inner class ReviewActions(private val delegate: AppActions) : AppActions by delegate {
        override fun onVideoPicked(uri: Uri?) {
            if (uri == null) {
                delegate.onVideoPicked(null)
            } else if (uri.scheme == "review-sample") {
                sampleVideoSelected = true
                prepareSampleArtwork()
                delegate.onTopLevelSelected(Destination.Convert)
            } else {
                sampleVideoSelected = false
                delegate.onVideoPicked(uri)
            }
        }
        override fun onClearVideo() {
            sampleVideoSelected = false
            sampleArtworkGeneration++
            val oldArtwork = sampleVideoArtworkPath
            sampleVideoArtworkPath = null
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    SelectedVideoThumbnail.delete(this@UiReviewActivity, oldArtwork) { !sampleVideoSelected }
                }
            }
            delegate.onClearVideo()
        }
        override fun onSelectReviewSampleVideo() {
            sampleVideoSelected = true
            prepareSampleArtwork()
            delegate.onTopLevelSelected(Destination.Convert)
        }
        override fun onExtractVideo() {
            if (sampleVideoSelected && sample != null) extractSample(controller.uiState.value.saveFormat)
            else if (sampleError != null) conversion = ConversionState(error = sampleError)
            else delegate.onExtractVideo()
        }
        override fun onPlayAudio(result: AudioResult) {
            selectReviewQueueItem(reviewQueueItem(result))
            delegate.onPlayAudio(result)
        }
        override fun onPlayBotVideo(video: BotVideo) {
            if (video.needsForward) return
            delegate.setPlayerExpanded(true)
            selectReviewQueueItem(reviewQueueItem(video))
            playReviewQueueItem(queueIndex)
        }
        override fun onPlayPrevious() {
            if (StreamPlayback.state.value.positionMs > 3_000L) delegate.onSeek(0L)
            else playReviewQueueItem((if (queueIndex >= 0) queueIndex else queueResumeIndex) - 1)
        }
        override fun onPlayNext() = playReviewQueueItem(if (queueIndex >= 0) queueIndex + 1 else queueResumeIndex)
        override fun onPlayQueueItem(index: Int) = playReviewQueueItem(index)
        override fun onShowQueue() { queueVisible = true }
        override fun onHideQueue() { queueVisible = false; queueEditing = false; queueSelectedKeys = emptySet() }
        override fun onToggleQueueEditing() { queueEditing = !queueEditing; queueSelectedKeys = emptySet() }
        override fun onToggleQueueSelection(key: String) {
            if (queueEditing && queue.any { it.key == key }) queueSelectedKeys =
                if (key in queueSelectedKeys) queueSelectedKeys - key else queueSelectedKeys + key
        }
        override fun onDeleteQueueSelection() { removeReviewQueueItems(queueSelectedKeys); queueSelectedKeys = emptySet() }
        override fun onMoveQueueItem(from: Int, to: Int) {
            val moved = moveQueueEntry(queue, queueIndex, queueResumeIndex, from, to)
            queue = moved.queue; queueIndex = moved.index; queueResumeIndex = moved.resumeIndex
        }
        override fun onAddToQueue(result: AudioResult) = appendReviewQueueItem(reviewQueueItem(result))
        override fun onAddBotVideoToQueue(video: BotVideo) {
            if (!video.needsForward) appendReviewQueueItem(reviewQueueItem(video))
        }
        private fun appendReviewQueueItem(item: QueueItemUi) {
            val present = queue.any { it.key == item.key }
            if (!present) queue = queue + item
            controller.onHostFeedback(if (present) "이미 재생목록에 있어요" else "재생목록에 추가했어요", false)
        }
        private fun selectReviewQueueItem(item: QueueItemUi) {
            val existing = queue.indexOfFirst { it.key == item.key }
            val at = if (existing >= 0) existing else (if (queueIndex >= 0) queueIndex + 1 else queueResumeIndex).coerceIn(0, queue.size)
            queue = queue.toMutableList().apply { if (existing >= 0) set(at, item) else add(at, item) }
            queueIndex = at; queueResumeIndex = at
        }
        override fun onStop() {
            queueIndex = -1; queueResumeIndex = 0; onHideQueue()
            delegate.onStop()
        }
        override fun onRetryStream() {
            if (queueIndex in queue.indices) playReviewQueueItem(queueIndex) else delegate.onRetryStream()
        }
        private fun playReviewQueueItem(index: Int) {
            val item = queue.getOrNull(index) ?: return
            val audio = when (item.sourceKind) {
                PlaybackSource.LOCAL_AUDIO -> (sample?.libraryAudio.orEmpty() + extraLibrary)
                    .firstOrNull { reviewCanonicalPath(it.path) == item.localAudioPath?.let(::reviewCanonicalPath) }
                PlaybackSource.BOT -> {
                    val video = bot.videos.firstOrNull { it.messageId == item.botMessageId } ?: return
                    sample?.libraryAudio?.firstOrNull()?.copy(
                        name = video.displayName,
                        artworkPath = AudioArtworkStore.capture(this@UiReviewActivity, video.thumbnailPath, video.thumbnailBase64),
                    )
                }
                PlaybackSource.NONE -> null
            } ?: return
            queueIndex = index
            queueResumeIndex = index
            controller.preservePlayerSizeForQueuePlayback(audio)
            StreamPlayback.playLocal(this@UiReviewActivity, audio)
        }
        private fun removeReviewQueueItems(keys: Set<String>) {
            val removed = removeQueueEntries(queue, queueIndex, queueResumeIndex, keys)
            queue = removed.queue; queueIndex = removed.index; queueResumeIndex = removed.resumeIndex
            queueSelectedKeys = queueSelectedKeys - keys
        }
        override fun onExternalPlayBotVideo(video: BotVideo) {
            onStop()
            sample?.libraryAudio?.firstOrNull()?.let(delegate::onExternalPlayAudio)
        }
        override fun onExternalPlayAudio(result: AudioResult) {
            onStop()
            delegate.onExternalPlayAudio(result)
        }
        override fun onExternalPlayStream() {
            val path = StreamPlayback.state.value.localAudioPath?.let(::reviewCanonicalPath)
            val fixtureAudio = (sample?.libraryAudio.orEmpty() + extraLibrary)
                .firstOrNull { reviewCanonicalPath(it.path) == path }
            if (fixtureAudio != null) onExternalPlayAudio(fixtureAudio)
            else { delegate.onExternalPlayStream(); queueIndex = -1; queueResumeIndex = 0; onHideQueue() }
        }
        override fun onSaveFormatChanged(value: AudioFormat) {
            if (!conversion.running) delegate.onSaveFormatChanged(value)
        }
        override fun onSaveBotVideo(video: BotVideo, format: AudioFormat) =
            extractSample(controller.uiState.value.saveFormat, video.thumbnailPath, video.thumbnailBase64)
        override fun onLibraryQueryChanged(query: ListQuery) {
            controller.updateLibraryQuery(query, sample?.libraryAudio.orEmpty() + extraLibrary)
        }
        override fun onBotQueryChanged(query: ListQuery) {
            controller.updateBotQuery(query, bot.videos)
        }
        override fun onRefreshBot() { /* Local fixture only. */ }
        override fun onConnectBot(credentials: BotCredentials) { /* Never persist credentials in review. */ }
        override fun onPairBot() { /* No Telegram session in review. */ }
        override fun onDisconnectBot() {
            // The fixture owns its bot data and never resets the real Telegram account.
            delegate.onCloseBotSetup()
            onStop()
            bot = BotUiState(status = "연결 해제됨")
            pendingBotDelete = null
            delegate.onTopLevelSelected(Destination.Bot)
        }
        override fun onOpenExternalUrl(url: String) { /* Keep review controls local. */ }
        override fun onSelectAllCurrentItems() {
            val liveState = controller.uiState.value
            when (liveState.topLevelDestination) {
                Destination.Library -> {
                    val visiblePaths = filterLibrary(sample?.libraryAudio.orEmpty() + extraLibrary, liveState.libraryQuery).mapTo(mutableSetOf(), AudioResult::path)
                    val selectedPaths = liveState.selectedAudioPaths
                    val targetPaths = if (visiblePaths.isNotEmpty() && selectedPaths.containsAll(visiblePaths)) emptySet() else visiblePaths
                    (selectedPaths + targetPaths).filter { (it in selectedPaths) != (it in targetPaths) }
                        .forEach(delegate::onToggleAudioSelection)
                }
                Destination.Bot -> {
                    val visibleIds = filterBot(bot.videos, liveState.botQuery).mapTo(mutableSetOf(), BotVideo::messageId)
                    val selectedIds = liveState.selectedBotMessageIds
                    val targetIds = if (visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)) emptySet() else visibleIds
                    (selectedIds + targetIds).filter { (it in selectedIds) != (it in targetIds) }
                        .forEach(delegate::onToggleBotSelection)
                }
                else -> Unit
            }
        }
        override fun onRequestLibraryDelete(results: List<AudioResult>) {
            pendingLibraryDelete = results.takeIf { it.isNotEmpty() }
        }
        override fun onConfirmLibraryDelete(results: List<AudioResult>) {
            val paths = results.map { File(it.path).absolutePath }.toSet()
            if (StreamPlayback.state.value.localAudioPath?.let { File(it).absolutePath } in paths) onStop()
            removeReviewQueueItems(results.mapTo(mutableSetOf()) { reviewQueueItem(it).key })
            extraLibrary = extraLibrary.filterNot { File(it.path).absolutePath in paths }
            sample = sample?.let { it.copy(libraryAudio = it.libraryAudio.filterNot { row -> File(row.path).absolutePath in paths }) }
            pendingLibraryDelete = null
            delegate.onClearSelection()
        }
        override fun onRenameAudio(result: AudioResult, titleStem: String) {
            val extension = result.name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            val name = if (extension == null) titleStem else "$titleStem.$extension"
            sample = sample?.let { it.copy(libraryAudio = it.libraryAudio.map { row -> if (row.path == result.path) row.copy(name = name) else row }) }
            extraLibrary = extraLibrary.map { if (it.path == result.path) it.copy(name = name) else it }
        }
        override fun onRequestBotDelete(messageIds: Set<Long>) {
            pendingBotDelete = messageIds.takeIf { it.isNotEmpty() }
        }
        override fun onConfirmBotDelete(messageIds: Set<Long>) {
            if (queue.getOrNull(queueIndex)?.botMessageId in messageIds) onStop()
            removeReviewQueueItems(bot.videos.filter { it.messageId in messageIds }.mapTo(mutableSetOf()) { reviewQueueItem(it).key })
            bot = bot.copy(videos = bot.videos.filterNot { it.messageId in messageIds })
            pendingBotDelete = null
            delegate.onClearSelection()
        }
        override fun onRenameBotVideo(video: BotVideo, title: String) {
            bot = bot.copy(videos = bot.videos.map { if (it.messageId == video.messageId) it.copy(customTitle = title) else it })
        }
        override fun onCancelConversion() {
            if (activeSessionId > 0L) AudioEngine.cancel(activeSessionId)
            activeSessionId = 0L
            extracting = false
            conversion = ConversionState(error = "로컬 테스트 추출을 취소했어요.")
        }
    }

    private fun prepareSampleArtwork() {
        val video = sample?.video ?: return
        val generation = ++sampleArtworkGeneration
        lifecycleScope.launch {
            val artwork = withContext(Dispatchers.IO) {
                SelectedVideoThumbnail.create(this@UiReviewActivity, Uri.fromFile(video))
            }
            if (sampleVideoSelected && generation == sampleArtworkGeneration) sampleVideoArtworkPath = artwork
            else if (!sampleVideoSelected) withContext(Dispatchers.IO) {
                SelectedVideoThumbnail.delete(this@UiReviewActivity, artwork) { !sampleVideoSelected }
            }
        }
    }

    private fun extractSample(format: AudioFormat, artworkPath: String? = null, artworkBase64: String? = null) {
        val source = sample ?: run {
            conversion = ConversionState(error = sampleError ?: "로컬 테스트 샘플을 준비 중이에요.")
            return
        }
        if (extracting) return
        extracting = true
        conversion = ConversionState(running = true, stage = "로컬 테스트 영상에서 오디오 추출 중")
        lifecycleScope.launch {
            val result = ReviewSampleMedia.extract(
                source,
                format,
                onProgress = { value -> runOnUiThread { if (extracting) conversion = conversion.copy(progress = value) } },
                onSessionReady = { activeSessionId = it },
            )
            activeSessionId = 0L
            extracting = false
            result.fold(
                onSuccess = { audio ->
                    val fixtureArtworkPath = withContext(Dispatchers.IO) {
                        AudioArtworkStore.captureVideoThumbnail(
                            this@UiReviewActivity, Uri.fromFile(source.video), source.video,
                        )
                    }
                    val savedAudio = audio.copy(
                        artworkPath = fixtureArtworkPath ?: AudioArtworkStore.capture(
                            this@UiReviewActivity, artworkPath, artworkBase64,
                        ),
                    )
                    extraLibrary = listOf(savedAudio) + extraLibrary
                    conversion = ConversionState(result = savedAudio)
                },
                onFailure = { error -> conversion = ConversionState(error = error.message ?: "로컬 테스트 오디오를 만들지 못했어요.") },
            )
        }
    }
}

private fun reviewQueueItem(audio: AudioResult) = QueueItemUi(
    key = "local:${reviewCanonicalPath(audio.path)}", title = audio.name, artworkPath = audio.artworkPath,
    sourceKind = PlaybackSource.LOCAL_AUDIO, localAudioPath = audio.path,
)

private fun reviewQueueItem(video: BotVideo) = QueueItemUi(
    key = "bot:${video.chatId}:${video.messageId}", title = video.displayName,
    artworkPath = video.thumbnailPath, artworkBase64 = video.thumbnailBase64,
    sourceKind = PlaybackSource.BOT, botChatId = video.chatId, botMessageId = video.messageId, botFileId = video.fileId,
)

private fun reviewCanonicalPath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath)

private fun reviewBotState(bytes: Long) = BotUiState(
    setupComplete = true,
    status = "로컬 테스트 모드 · Telegram 연결 안 함",
    botName = "로컬 샘플 봇",
    username = "local_sample",
    connected = true,
    paired = true,
    videos = listOf(
        reviewVideo(-71001L, "로컬 테스트 톤 · 한강 산책 · 저녁 노을.mp4", bytes, 0xFF547D86.toInt(), 0xFFE8B77B.toInt()),
        reviewVideo(-71002L, "로컬 테스트 톤 · 서울 골목길의 빗소리.mp4", bytes, 0xFF68745F.toInt(), 0xFFD1A77A.toInt()),
        reviewVideo(-71003L, "로컬 테스트 톤 · 카드 말줄임을 확인하는 긴 제목의 예시 영상.mp4", bytes, 0xFF60556F.toInt(), 0xFFBCA8CE.toInt()),
    ),
    receiveDiagnostic = "생성된 로컬 샘플만 사용해요. 계정·네트워크 연결은 하지 않아요.",
)

private fun reviewVideo(id: Long, name: String, bytes: Long, background: Int, accent: Int) = BotVideo(
    fileId = 0,
    chatId = 0L,
    messageId = id,
    name = name,
    bytes = bytes,
    durationSeconds = 12,
    date = System.currentTimeMillis() / 1_000L - (-id - 71001L) * 86_400L,
    mimeType = "video/mp4",
    thumbnailBase64 = reviewThumbnail(background, accent),
)

private fun reviewThumbnail(background: Int, accent: Int): String {
    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(background)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = accent
    canvas.drawOval(RectF(28f, 20f, 165f, 160f), paint)
    paint.color = 0x55302A3A
    canvas.drawRect(150f, 0f, 320f, 180f, paint)
    paint.color = 0xFFFAF4E8.toInt()
    canvas.drawCircle(248f, 90f, 28f, paint)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
    bitmap.recycle()
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}
