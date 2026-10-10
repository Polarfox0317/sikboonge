package io.github.polarfox0317.sikboonge

import android.app.*
import android.content.*
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque

data class AudioResult(
    val path: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val date: Long,
    val artworkPath: String? = null,
)
data class LibraryDownload(
    val id: Long,
    val name: String,
    val mime: String,
    val date: Long,
    val artworkPath: String?,
    val stage: String,
    val progress: Float? = null,
    val receivedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val remainingSeconds: Long? = null,
)
enum class ConversionOrigin { LOCAL_CONVERT, BOT_SAVE }
data class ConversionState(
    val origin: ConversionOrigin = ConversionOrigin.LOCAL_CONVERT,
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float? = null,
    val error: String? = null,
    val result: AudioResult? = null,
    val receivedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val botSaveCompletionId: Long? = null,
)
internal data class AppStoreRemovalResult(val removedEntries: Int, val failedFileDeletes: Int)
object AppStore {
    val state = MutableStateFlow(ConversionState())
    val history = MutableStateFlow<List<AudioResult>>(emptyList())
    val downloads = MutableStateFlow<List<LibraryDownload>>(emptyList())
    val cancelledDownloadId = MutableStateFlow<Long?>(null)
    @Synchronized fun load(context: Context) {
        val file = File(context.filesDir, "history.json")
        history.value = try {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i -> json.getJSONObject(i).let { row ->
                val artworkPath = row.optString("artworkPath", "").takeIf(String::isNotBlank)
                    ?.takeIf { AudioArtworkStore.isOwnedArtworkPath(context.filesDir, it) }
                AudioResult(row.getString("path"), row.getString("name"), row.getString("mime"), row.getLong("bytes"), row.getLong("date"), artworkPath)
            } }.filter { File(it.path).isFile }
        } catch (_: Exception) { emptyList() }
    }
    @Synchronized fun add(context: Context, result: AudioResult) {
        persist(context, listOf(result) + history.value)
    }
    @Synchronized internal fun putDownload(download: LibraryDownload) {
        downloads.value = listOf(download) + downloads.value.filterNot { it.id == download.id }
    }
    @Synchronized internal fun updateDownload(id: Long, stage: String, metrics: DownloadMetrics) {
        downloads.value = downloads.value.map { item ->
            if (item.id != id) item else item.copy(
                stage = stage,
                progress = metrics.progress,
                receivedBytes = metrics.receivedBytes,
                totalBytes = metrics.totalBytes,
                bytesPerSecond = metrics.bytesPerSecond,
                remainingSeconds = metrics.remainingSeconds,
            )
        }
    }
    @Synchronized internal fun removeDownload(id: Long) {
        downloads.value = downloads.value.filterNot { it.id == id }
    }
    @Synchronized internal fun completeDownload(context: Context, id: Long, result: AudioResult) {
        val pending = downloads.value.firstOrNull { it.id == id }
        removeDownload(id) // Avoid presenting the same save as both pending and completed.
        try {
            add(context, result)
        } catch (failure: Exception) {
            if (pending != null) putDownload(pending)
            throw failure
        }
    }
    /** Call on a worker thread; source artwork is captured and persisted without consulting BotRuntime. */
    @Synchronized internal fun attachMissingArtwork(context: Context, videos: List<BotVideo>): Int {
        val candidates = videos.distinctBy { it.chatId to it.messageId }
        val capturedByVideo = mutableMapOf<Pair<Long, Long>, String?>()
        var attached = 0
        val updated = history.value.map { entry ->
            if (!entry.artworkPath.isNullOrBlank()) return@map entry
            val matches = candidates.filter { video ->
                AudioArtworkStore.matchesGeneratedAudioName(entry.name, video.displayName) ||
                    AudioArtworkStore.matchesGeneratedAudioName(entry.name, video.name)
            }
            val identities = matches.distinctBy { video ->
                if (video.fileId > 0) "file:${video.fileId}" else "message:${video.chatId}:${video.messageId}"
            }
            if (identities.size != 1) return@map entry
            val video = matches.firstOrNull { !it.thumbnailPath.isNullOrBlank() || it.thumbnailBase64.isNotBlank() }
                ?: identities.single()
            val key = video.chatId to video.messageId
            val artwork = capturedByVideo.getOrPut(key) {
                AudioArtworkStore.capture(context, video.thumbnailPath, video.thumbnailBase64)
            }
            if (artwork == null) entry else {
                attached++
                entry.copy(artworkPath = artwork)
            }
        }
        if (attached > 0) {
            try {
                persist(context, updated)
            } catch (error: Exception) {
                AudioArtworkStore.deleteUnreferenced(context, capturedByVideo.values, history.value.map { it.artworkPath })
                throw error
            }
        }
        return attached
    }
    @Synchronized fun remove(context: Context, result: AudioResult) {
        removeMany(context, listOf(result))
    }
    @Synchronized internal fun rename(context: Context, result: AudioResult, requestedTitle: String): AudioResult {
        val oldHistory = history.value
        val audioRoot = File(context.filesDir, "audio").canonicalFile
        val rootPrefix = audioRoot.path + File.separator
        val source = File(result.path).canonicalFile
        require(source.path.startsWith(rootPrefix) && source.isFile) { "이 오디오 파일을 변경할 수 없어요." }
        val indexedResult = oldHistory.firstOrNull { entry ->
            runCatching { File(entry.path).canonicalPath == source.path }.getOrDefault(false)
        }
        require(indexedResult != null) { "보관함에 있는 오디오만 이름을 변경할 수 있어요." }

        val sanitizedTitle = requestedTitle
            .map { character ->
                if (character.isISOControl() || character in "\\/:*?\"<>|") '_' else character
            }
            .joinToString("")
            .trim()
            .trim('.')
        val boundedTitle = buildString {
            var index = 0
            var bytes = 0
            while (index < sanitizedTitle.length) {
                val codePoint = sanitizedTitle.codePointAt(index)
                val character = String(Character.toChars(codePoint))
                val characterBytes = character.toByteArray(Charsets.UTF_8).size
                if (bytes + characterBytes > 200) break
                append(character)
                bytes += characterBytes
                index += Character.charCount(codePoint)
            }
        }.trim().trim('.')
        require(boundedTitle.isNotBlank()) { "파일 이름을 입력해 주세요." }

        val extension = source.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val parent = source.parentFile ?: throw IllegalArgumentException("이 오디오 파일을 변경할 수 없어요.")
        var candidate: File
        var suffix = 1
        while (true) {
            val candidateStem = if (suffix == 1) boundedTitle else "$boundedTitle ($suffix)"
            candidate = File(parent, if (extension == null) candidateStem else "$candidateStem.$extension")
            if (candidate.canonicalPath == source.path) break
            if (!candidate.exists()) break
            suffix++
        }

        val renamed = indexedResult.copy(path = candidate.canonicalPath, name = candidate.name)
        if (renamed.path == source.path && renamed.name == result.name) return result
        try {
            Files.move(source.toPath(), candidate.toPath())
        } catch (error: Exception) {
            throw IllegalStateException("오디오 파일 이름을 변경할 수 없어요.", error)
        }
        try {
            val updated = oldHistory.map { entry ->
                if (runCatching { File(entry.path).canonicalPath == source.path }.getOrDefault(false)) renamed else entry
            }
            persist(context, updated)
        } catch (error: Exception) {
            val restored = runCatching { Files.move(candidate.toPath(), source.toPath()) }.isSuccess
            if (!restored) {
                throw IllegalStateException("저장 목록을 기록하지 못했고 파일 이름도 복구하지 못했어요.", error)
            }
            throw IllegalStateException("저장 목록을 기록할 수 없어요.", error)
        }
        val stateResult = state.value.result
        if (stateResult != null && runCatching { File(stateResult.path).canonicalPath == source.path }.getOrDefault(false)) {
            state.value = state.value.copy(result = renamed)
        }
        return renamed
    }
    @Synchronized internal fun removeMany(context: Context, results: Collection<AudioResult>): AppStoreRemovalResult {
        if (results.isEmpty()) return AppStoreRemovalResult(0, 0)
        val audioRoot = File(context.filesDir, "audio").canonicalFile
        val rootPrefix = audioRoot.path + File.separator
        // Validate the complete request before changing either history or files.
        val targetFiles = results.map { result ->
            File(result.path).canonicalFile.also { file ->
                require(file.path.startsWith(rootPrefix) && !file.isDirectory) { "이 파일을 삭제할 수 없어요." }
            }
        }.distinctBy { it.path }
        val targetPaths = targetFiles.mapTo(mutableSetOf()) { it.path }
        fun canonicalPath(path: String): String? = runCatching { File(path).canonicalPath }.getOrNull()
        val oldHistory = history.value
        val updated = oldHistory.filterNot { canonicalPath(it.path)?.let(targetPaths::contains) == true }
        val removedRows = oldHistory.filter { canonicalPath(it.path)?.let(targetPaths::contains) == true }
        val removedEntries = oldHistory.size - updated.size
        persist(context, updated)
        var failures = 0
        targetFiles.forEach { file ->
            if (file.exists() && !file.delete() && file.exists()) failures++
        }
        AudioArtworkStore.deleteUnreferenced(context, removedRows.map { it.artworkPath }, updated.map { it.artworkPath })
        val stateResultPath = state.value.result?.path?.let(::canonicalPath)
        if (stateResultPath != null && stateResultPath in targetPaths) state.value = ConversionState()
        return AppStoreRemovalResult(removedEntries, failures)
    }
    private fun persist(context: Context, updated: List<AudioResult>) {
        val json = JSONArray().apply { updated.forEach { put(JSONObject().put("path", it.path).put("name", it.name).put("mime", it.mime).put("bytes", it.bytes).put("date", it.date).put("artworkPath", it.artworkPath)) } }
        val temp = File(context.filesDir, "history.tmp").apply { writeText(json.toString()) }
        check(temp.renameTo(File(context.filesDir, "history.json"))) { "저장 목록을 기록할 수 없어요." }
        history.value = updated
    }
}

class ConversionService : Service() {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var probeSessionId: Long? = null
    @Volatile private var conversionSessionId: Long? = null
    @Volatile private var botInputBridge: ExternalAudioInputBridge? = null
    private var worker: Thread? = null
    @Volatile private var workerJob = 0L
    private val pendingIntents = ArrayDeque<Intent>()
    @Volatile private var activeBotFileId: Int? = null
    @Volatile private var activeLibraryDownloadId: Long? = null
    @Volatile private var activeDownloadTotalBytes: Long? = null
    @Volatile private var activeStage: String = ""
    @Volatile private var lastNotificationAtNanos = 0L
    @Volatile private var lastNotifiedStage = ""
    @Volatile private var lastLibraryUpdateAtNanos = 0L
    private val stateLock = Any()
    @Volatile private var transferProgress: DownloadProgressTracker? = null
    private var activeJob = 0L
    @Volatile private var activeOrigin = ConversionOrigin.LOCAL_CONVERT
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("conversion", "오디오 추출", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancel(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CANCEL_DOWNLOAD) {
            intent.getLongExtra(EXTRA_DOWNLOAD_ID, 0L).takeIf { it > 0L }?.let(::cancelDownload)
            if (worker?.isAlive != true && synchronized(stateLock) { pendingIntents.isEmpty() }) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "cancel_bot") {
            val requestedFileId = intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 }
            if (requestedFileId == null || activeBotFileId == requestedFileId) {
                if (activeBotFileId != null) cancel()
            }
            synchronized(stateLock) {
                val queued = pendingIntents.filter { queuedIntent ->
                    val queuedFileId = queuedIntent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 }
                    queuedFileId != null && (requestedFileId == null || requestedFileId == queuedFileId)
                }
                pendingIntents.removeAll(queued.toSet())
                queued.forEach { queuedIntent ->
                    queuedIntent.getLongExtra(EXTRA_DOWNLOAD_ID, 0L).takeIf { it > 0L }?.let(AppStore::removeDownload)
                }
            }
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) {
            val isBotSave = intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0) > 0
            if (cancelled.get() || activeJob == 0L || isBotSave) synchronized(stateLock) {
                if (isBotSave) {
                    val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, 0L).takeIf { it > 0L } ?: nextDownloadId()
                    intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    intent.putExtra(EXTRA_DOWNLOAD_DATE, System.currentTimeMillis())
                    AppStore.putDownload(pendingDownload(intent, downloadId, "대기 중"))
                }
                pendingIntents.addLast(intent)
            }
            return START_NOT_STICKY
        }
        return startJob(intent)
    }

    private fun startJob(intent: Intent): Int {
        cancelled.set(false)
        val job = System.nanoTime()
        val startedAt = System.nanoTime()
        synchronized(stateLock) {
            activeJob = job
            transferProgress = null
        }
        val botFileId = intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 }
        activeOrigin = if (botFileId != null) ConversionOrigin.BOT_SAVE else ConversionOrigin.LOCAL_CONVERT
        val legacyBotSaveAccess = BotSaveTestHooks.accessOverride
        activeBotFileId = botFileId
        val libraryDownloadId = if (botFileId != null) {
            intent.getLongExtra(EXTRA_DOWNLOAD_ID, 0L).takeIf { it > 0L }
                ?: nextDownloadId().also { intent.putExtra(EXTRA_DOWNLOAD_ID, it) }
        } else null
        if (libraryDownloadId != null && intent.getLongExtra(EXTRA_DOWNLOAD_DATE, 0L) <= 0L) {
            intent.putExtra(EXTRA_DOWNLOAD_DATE, System.currentTimeMillis())
        }
        activeLibraryDownloadId = libraryDownloadId
        activeDownloadTotalBytes = intent.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0L).takeIf { it > 0L }
        activeStage = if (botFileId != null) "Telegram에서 오디오 확인 중" else "영상 가져오는 중"
        if (libraryDownloadId != null) AppStore.putDownload(pendingDownload(intent, libraryDownloadId, activeStage))
        val foregroundNotification = notification(activeStage, null)
        if (android.os.Build.VERSION.SDK_INT >= 35) startForeground(1, foregroundNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, foregroundNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, foregroundNotification)
        AppStore.state.value = ConversionState(origin = activeOrigin, running = true, stage = activeStage)
        workerJob = job
        worker = Thread {
            val input = if (botFileId != null) null else File(cacheDir, "input-${System.nanoTime()}.video")
            var botBridge: ExternalAudioInputBridge? = null
            var legacyBotInput: File? = null
            var output: File? = null
            var localVideoUri: Uri? = null
            try {
                val onNetworkRange: (Long, Long, Long?) -> Unit = { offset, bytes, total -> recordNetworkRange(job, offset, bytes, total) }
                val source: String
                val name: String
                if (botFileId != null) {
                    name = intent.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME).orEmpty().ifBlank { "Telegram 영상" }
                    if (legacyBotSaveAccess != null) {
                        update("저장할 영상을 임시로 받는 중", null, job)
                        beginTransferStage(job)
                        var previousReceived = 0L
                        legacyBotInput = legacyBotSaveAccess.awaitCompletedFile(botFileId, cancelled::get) { received, total ->
                            val delta = (received - previousReceived).coerceAtLeast(0L)
                            previousReceived = received
                            onNetworkRange(previousReceived - delta, delta, total.takeIf { it > 0L })
                            update("저장할 영상을 임시로 받는 중", if (total > 0) (received.toFloat() / total).coerceIn(0f, .99f) else null, job)
                        }
                        source = legacyBotInput.absolutePath
                    } else {
                        update("Telegram에서 오디오 확인 중", null, job)
                        val bridge = ExternalAudioInputBridge.forBot(
                            botFileId,
                            intent.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0L),
                            BotAudioSaveTestHooks.accessOverride ?: BotRuntime,
                            onBytesTransferred = onNetworkRange,
                        )
                        botBridge = bridge
                        botInputBridge = bridge
                        source = bridge.inputUrl
                    }
                } else {
                    val uri = Uri.parse(intent.getStringExtra("uri") ?: throw IllegalArgumentException("영상을 먼저 선택해 주세요."))
                    require(uri.scheme == "content") { "영상 파일을 다시 선택하거나 Telegram에서 파일로 공유해 주세요." }
                    localVideoUri = uri
                    source = requireNotNull(input).absolutePath
                    name = displayName(uri).substringBeforeLast('.')
                    contentResolver.openInputStream(uri)?.use { sourceStream -> requireNotNull(input).outputStream().use { target ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { checkCancelled(); val count = sourceStream.read(buffer); if (count < 0) break; total += count; require(total <= MAX_BYTES) { "영상은 1GB 이하로 선택해 주세요." }; target.write(buffer, 0, count) }
                    } } ?: throw IllegalArgumentException("영상 파일을 열 수 없어요. 다시 공유해 주세요.")
                }
                checkCancelled()
                update("오디오 확인 중", null, job)
                val activeBotBridge = botBridge
                val probe = if (activeBotBridge != null) {
                    beginTransferStage(job)
                    inspectBot(activeBotBridge, job, startedAt)
                } else {
                    val localInput = requireNotNull(legacyBotInput ?: input)
                    val (codec, duration) = AudioEngine.inspect(localInput)
                    AudioProbe(codec, duration, localInput.length())
                }
                val format = AudioFormat.valueOf(intent.getStringExtra("format") ?: "ORIGINAL")
                val plan = AudioEngine.plan(format, probe.codec)
                val cleanName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "오디오" }
                val folder = File(filesDir, "audio").apply { mkdirs() }
                output = File(folder, "$cleanName-${System.currentTimeMillis()}.${plan.extension}")
                val botOutputLimit = if (activeBotBridge != null) BotAudioSavePolicy.outputLimit(folder.usableSpace) else null
                val stage = if (botFileId != null) "Telegram 오디오 저장 중" else "오디오 추출 중"
                beginTransferStage(job)
                update(stage, 0f, job)
                BotAudioSaveTestHooks.beforeConvert?.invoke(activeBotBridge)
                AudioEngine.convert(source, output, plan, probe.duration, { update(stage, it, job) }, {
                    conversionSessionId = it
                    if (cancelled.get()) AudioEngine.cancel(it)
                }, if (activeBotBridge != null) BotAudioSavePolicy.remainingSeconds(startedAt) else DEFAULT_CONVERSION_TIMEOUT_SECONDS,
                    externalInputBridge = activeBotBridge, maxOutputBytes = botOutputLimit)
                checkCancelled()
                val artworkPath = localVideoUri?.let { uri ->
                    AudioArtworkStore.captureVideoThumbnail(this, uri, requireNotNull(input))
                } ?: AudioArtworkStore.capture(
                    this,
                    intent.getStringExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH),
                    intent.getStringExtra(AudioArtworkStore.EXTRA_ARTWORK_BASE64),
                )
                val result = AudioResult(output.absolutePath, output.name, plan.mime, output.length(), System.currentTimeMillis(), artworkPath)
                var addedToHistory = false
                try {
                    synchronized(stateLock) {
                        checkCancelled()
                        if (activeJob == job) {
                            if (activeOrigin == ConversionOrigin.BOT_SAVE && libraryDownloadId != null) {
                                AppStore.completeDownload(this, libraryDownloadId, result)
                                runCatching { postBotSaveCompletedNotification(libraryDownloadId, result) }
                            } else {
                                AppStore.add(this, result)
                            }
                            addedToHistory = true
                            activeJob = 0L
                            activeLibraryDownloadId = null
                            AppStore.state.value = if (activeOrigin == ConversionOrigin.BOT_SAVE) {
                                ConversionState(origin = activeOrigin, botSaveCompletionId = job)
                            } else {
                                ConversionState(origin = activeOrigin, stage = "추출 완료", progress = 1f, result = result)
                            }
                        }
                    }
                } finally {
                    if (!addedToHistory) AudioArtworkStore.deleteUnreferenced(this, listOf(artworkPath), AppStore.history.value.map { it.artworkPath })
                }
            } catch (_: InterruptedException) {
                output?.delete(); finish(job, cancellationState())
            } catch (e: Exception) {
                if (!cancelled.get()) AppDiagnostics.failure("conversion", e)
                val errorContext = when {
                    botFileId != null -> UserFacingErrors.Context.TELEGRAM
                    else -> UserFacingErrors.Context.LOCAL_MEDIA
                }
                output?.delete(); finish(job, if (cancelled.get()) cancellationState() else ConversionState(error = UserFacingErrors.fromThrowable(e, errorContext)))
            } finally {
                botBridge?.close()
                if (botInputBridge === botBridge) botInputBridge = null
                if (legacyBotInput != null) runCatching { legacyBotSaveAccess?.endSave(requireNotNull(botFileId)) }
                activeLibraryDownloadId = null
                activeDownloadTotalBytes = null
                activeStage = ""
                activeBotFileId = null
                input?.delete()
                probeSessionId = null
                conversionSessionId = null
                android.os.Handler(mainLooper).post {
                    if (workerJob != job) return@post
                    val next = synchronized(stateLock) { pendingIntents.pollFirst() }
                    worker = null
                    workerJob = 0L
                    if (next != null) {
                        startJob(next)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }.apply { name = "audio-conversion"; start() }
        return START_NOT_STICKY
    }

    private fun inspectBot(bridge: ExternalAudioInputBridge, job: Long, startedAt: Long): AudioProbe {
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<AudioProbe>?>(null)
        val id = AudioEngine.inspectBotAsync(bridge) { result -> outcome.set(result); completed.countDown() }
        probeSessionId = id
        if (cancelled.get()) AudioEngine.cancel(id)
        try {
            if (!completed.await(BotAudioSavePolicy.remainingSeconds(startedAt), TimeUnit.SECONDS)) {
                AudioEngine.cancel(id)
                completed.await(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS)
                throw IllegalArgumentException("Telegram 오디오 정보를 확인하는 시간이 초과됐어요. 다시 시도해 주세요.")
            }
        } catch (error: InterruptedException) {
            AudioEngine.cancel(id)
            completed.await(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS)
            throw error
        } finally {
            probeSessionId = null
        }
        checkCancelled()
        update("Telegram에서 오디오 확인 완료", null, job)
        return outcome.get()?.getOrThrow() ?: throw IllegalArgumentException("영상에서 오디오 정보를 읽을 수 없어요.")
    }

    private fun checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun cancel(expectedDownloadId: Long? = null): Boolean {
        synchronized(stateLock) {
            if (activeJob == 0L || (expectedDownloadId != null && activeLibraryDownloadId != expectedDownloadId)) return false
            if (cancelled.get()) return false
            cancelled.set(true)
        }
        botInputBridge?.close()
        probeSessionId?.let { AudioEngine.cancel(it) }
        conversionSessionId?.let { AudioEngine.cancel(it) }
        worker?.interrupt()
        return true
    }

    private fun cancelDownload(downloadId: Long): Boolean {
        val queuedDownloadRemoved = synchronized(stateLock) {
            val queuedIntent = pendingIntents.firstOrNull {
                it.getLongExtra(EXTRA_DOWNLOAD_ID, 0L) == downloadId
            }
            if (queuedIntent == null) false else {
                pendingIntents.remove(queuedIntent)
                AppStore.removeDownload(downloadId)
                true
            }
        }
        val activeDownloadCancelled = !queuedDownloadRemoved && cancel(downloadId)
        if (queuedDownloadRemoved || activeDownloadCancelled) {
            AppStore.cancelledDownloadId.value = downloadId
        }
        return queuedDownloadRemoved || activeDownloadCancelled
    }

    override fun onDestroy() {
        cancel()
        synchronized(stateLock) {
            pendingIntents.forEach { queuedIntent ->
                queuedIntent.getLongExtra(EXTRA_DOWNLOAD_ID, 0L).takeIf { it > 0L }?.let(AppStore::removeDownload)
            }
            pendingIntents.clear()
        }
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopSelf() }
    private fun update(stage: String, progress: Float?, job: Long) {
        synchronized(stateLock) {
            if (activeJob != job) return
            activeStage = stage
            val sample = transferProgress?.observeProgress(progress) ?: DownloadMetrics(progress = progress)
            AppStore.state.value = ConversionState(
                origin = activeOrigin,
                running = true,
                stage = stage,
                progress = sample.progress,
                receivedBytes = sample?.receivedBytes,
                totalBytes = sample?.totalBytes,
                bytesPerSecond = sample?.bytesPerSecond,
            )
            activeLibraryDownloadId?.let { AppStore.updateDownload(it, stage, sample) }
            postProgressNotification(stage, sample.progress, sample)
        }
    }
    private fun beginTransferStage(job: Long) {
        synchronized(stateLock) {
            if (activeJob != job || cancelled.get()) return
            val initialDownloadedSize = activeBotFileId?.let(BotRuntime::fileInfo)?.local?.downloadedSize?.toLong()
            transferProgress = DownloadProgressTracker(activeDownloadTotalBytes, initialDownloadedSize)
            AppStore.state.value = AppStore.state.value.copy(
                progress = null,
                receivedBytes = 0L,
                totalBytes = activeDownloadTotalBytes,
                bytesPerSecond = null,
            )
            activeLibraryDownloadId?.let { id ->
                AppStore.updateDownload(id, activeStage, DownloadMetrics(totalBytes = activeDownloadTotalBytes))
            }
        }
    }
    private fun recordNetworkRange(job: Long, offset: Long, bytes: Long, totalBytes: Long?) {
        synchronized(stateLock) {
            if (activeJob != job || cancelled.get()) return
            val sampler = transferProgress ?: return
            var updatedSample = sampler.addRange(offset, bytes, totalBytes)
            val tdlibFile = activeBotFileId?.let(BotRuntime::fileInfo)
            if (tdlibFile != null) {
                updatedSample = sampler.observePlatformDownloadedSize(tdlibFile.local.downloadedSize.toLong(), totalBytes)
            }
            AppStore.state.value = AppStore.state.value.copy(
                receivedBytes = updatedSample.receivedBytes,
                totalBytes = updatedSample.totalBytes,
                bytesPerSecond = updatedSample.bytesPerSecond,
            )
            val now = System.nanoTime()
            if (now - lastLibraryUpdateAtNanos >= LIBRARY_UPDATE_INTERVAL_NANOS) {
                activeLibraryDownloadId?.let { AppStore.updateDownload(it, activeStage, updatedSample) }
                lastLibraryUpdateAtNanos = now
            }
            postProgressNotification(activeStage, updatedSample.progress, updatedSample)
        }
    }
    private fun finish(job: Long, state: ConversionState) {
        synchronized(stateLock) {
            if (activeJob != job) return
            activeJob = 0L
            activeLibraryDownloadId?.let(AppStore::removeDownload)
            activeLibraryDownloadId = null
            AppStore.state.value = state.copy(origin = activeOrigin)
        }
    }
    private fun cancellationState(): ConversionState = if (activeOrigin == ConversionOrigin.BOT_SAVE) {
        ConversionState(origin = ConversionOrigin.BOT_SAVE)
    } else {
        ConversionState(stage = "취소했어요")
    }
    private fun postProgressNotification(stage: String, progress: Float?, metrics: DownloadMetrics?, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && stage == lastNotifiedStage && now - lastNotificationAtNanos < NOTIFICATION_INTERVAL_NANOS) return
        lastNotificationAtNanos = now
        lastNotifiedStage = stage
        getSystemService(NotificationManager::class.java).notify(1, notification(stage, progress, metrics))
    }
    private fun notification(stage: String, progress: Float?, metrics: DownloadMetrics? = null): Notification {
        val isBotSave = activeOrigin == ConversionOrigin.BOT_SAVE && activeLibraryDownloadId != null
        val downloadId = activeLibraryDownloadId
        val download = downloadId?.let { id -> AppStore.downloads.value.firstOrNull { it.id == id } }
        val cancelIntent = Intent(this, ConversionService::class.java)
            .setAction(if (isBotSave) ACTION_CANCEL_DOWNLOAD else "cancel")
        if (isBotSave && downloadId != null) cancelIntent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        val cancel = PendingIntent.getService(
            this,
            if (isBotSave) requireNotNull(downloadId).toInt() else 1,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val content = if (isBotSave) DownloadProgress.format(metrics ?: DownloadMetrics()).notification else stage
        val boundedProgress = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 0.99f)
        val percent = ((boundedProgress ?: 0f) * 100f).toInt()
        return NotificationCompat.Builder(this, "conversion")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (isBotSave) download?.name ?: getString(R.string.app_name) else getString(R.string.app_name))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSubText(if (isBotSave) stage else null)
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(100, percent, boundedProgress == null)
            .addAction(0, if (isBotSave) "다운로드 중지" else "취소", cancel)
            .build()
    }
    private fun postBotSaveCompletedNotification(downloadId: Long, result: AudioResult) {
        val notificationId = BOT_SAVE_COMPLETED_NOTIFICATION_BASE or (downloadId.hashCode() and BOT_SAVE_COMPLETED_NOTIFICATION_MASK)
        val openLibrary = Intent(this, MainActivity::class.java)
            .putExtra("open_library", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openLibrary,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val completed = NotificationCompat.Builder(this, "conversion")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(result.name)
            .setContentText("보관함에 저장했어요")
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, completed)
    }
    private fun pendingDownload(intent: Intent, id: Long, stage: String): LibraryDownload {
        val sourceName = intent.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME).orEmpty().ifBlank { "Telegram 영상" }
        val baseName = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(70).ifBlank { "오디오" }
        val mime = when (intent.getStringExtra("format")) {
            AudioFormat.MP3.name -> "audio/mpeg"
            AudioFormat.WAV.name -> "audio/wav"
            else -> "audio/*"
        }
        return LibraryDownload(
            id = id,
            name = baseName,
            mime = mime,
            date = intent.getLongExtra(EXTRA_DOWNLOAD_DATE, System.currentTimeMillis()),
            artworkPath = intent.getStringExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH),
            stage = stage,
        )
    }
    private fun displayName(uri: Uri): String = try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "영상" } catch (_: Exception) { "영상" }
    companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024
        private val downloadSequence = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L) * 1000L)
        private const val BOT_SAVE_COMPLETED_NOTIFICATION_BASE = 0x50000000
        private const val BOT_SAVE_COMPLETED_NOTIFICATION_MASK = 0x0FFFFFFF
        const val EXTRA_DOWNLOAD_ID = "library_download_id"
        private const val EXTRA_DOWNLOAD_DATE = "library_download_date"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        private const val CANCEL_GRACE_SECONDS = 15L
        private const val DEFAULT_CONVERSION_TIMEOUT_SECONDS = 600L
        // Coalesce state and notification updates without leaving pending rows stale.
        private const val LIBRARY_UPDATE_INTERVAL_NANOS = 250_000_000L
        private const val NOTIFICATION_INTERVAL_NANOS = 500_000_000L
    }

    private fun nextDownloadId(): Long = downloadSequence.incrementAndGet().coerceAtLeast(1L)
}
