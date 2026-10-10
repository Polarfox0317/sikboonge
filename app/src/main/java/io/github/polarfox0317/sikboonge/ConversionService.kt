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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class AudioResult(
    val path: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val date: Long,
    val artworkPath: String? = null,
)
data class ConversionState(
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float? = null,
    val error: String? = null,
    val result: AudioResult? = null,
    val receivedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
)
internal data class AppStoreRemovalResult(val removedEntries: Int, val failedFileDeletes: Int)
object AppStore {
    val state = MutableStateFlow(ConversionState())
    val history = MutableStateFlow<List<AudioResult>>(emptyList())
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
    @Volatile private var pendingIntent: Intent? = null
    @Volatile private var activeBotFileId: Int? = null
    private val stateLock = Any()
    @Volatile private var transferProgress: TransferProgressSampler? = null
    private var activeJob = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("conversion", "오디오 추출", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancel(); return START_NOT_STICKY }
        if (intent?.action == "cancel_bot") {
            val requestedFileId = intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 }
            if (requestedFileId == null || activeBotFileId == requestedFileId) {
                if (activeBotFileId != null) cancel()
            }
            synchronized(stateLock) {
                val queuedFileId = pendingIntent?.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0)?.takeIf { it > 0 }
                if (queuedFileId != null && (requestedFileId == null || requestedFileId == queuedFileId)) pendingIntent = null
            }
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) {
            if (cancelled.get() || activeJob == 0L) synchronized(stateLock) { pendingIntent = intent }
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
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
        if (android.os.Build.VERSION.SDK_INT >= 35) startForeground(1, notification("오디오 추출 준비 중", null), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, notification("오디오 추출 준비 중", null), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notification("오디오 추출 준비 중", null))
        val botFileId = intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 }
        val legacyBotSaveAccess = BotSaveTestHooks.accessOverride
        activeBotFileId = botFileId
        AppStore.state.value = ConversionState(running = true, stage = if (botFileId != null) "Telegram에서 오디오 확인 중" else "영상 가져오는 중")
        workerJob = job
        worker = Thread {
            val input = if (botFileId != null) null else File(cacheDir, "input-${System.nanoTime()}.video")
            var botBridge: ExternalAudioInputBridge? = null
            var legacyBotInput: File? = null
            var output: File? = null
            var localVideoUri: Uri? = null
            try {
                val onNetworkBytes: (Long, Long?) -> Unit = { bytes, total -> recordNetworkBytes(job, bytes, total) }
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
                            onNetworkBytes(delta, total.takeIf { it > 0L })
                            update("저장할 영상을 임시로 받는 중", if (total > 0) (received.toFloat() / total).coerceIn(0f, .99f) else null, job)
                        }
                        source = legacyBotInput.absolutePath
                    } else {
                        update("Telegram에서 오디오 확인 중", null, job)
                        val bridge = ExternalAudioInputBridge.forBot(
                            botFileId,
                            intent.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0L),
                            BotAudioSaveTestHooks.accessOverride ?: BotRuntime,
                            onBytesTransferred = onNetworkBytes,
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
                            AppStore.add(this, result)
                            addedToHistory = true
                            activeJob = 0L
                            AppStore.state.value = ConversionState(stage = "추출 완료", progress = 1f, result = result)
                        }
                    }
                } finally {
                    if (!addedToHistory) AudioArtworkStore.deleteUnreferenced(this, listOf(artworkPath), AppStore.history.value.map { it.artworkPath })
                }
            } catch (_: InterruptedException) {
                output?.delete(); finish(job, ConversionState(stage = "취소했어요"))
            } catch (e: Exception) {
                if (!cancelled.get()) AppDiagnostics.failure("conversion", e)
                val errorContext = when {
                    botFileId != null -> UserFacingErrors.Context.TELEGRAM
                    else -> UserFacingErrors.Context.LOCAL_MEDIA
                }
                output?.delete(); finish(job, if (cancelled.get()) ConversionState(stage = "취소했어요") else ConversionState(error = UserFacingErrors.fromThrowable(e, errorContext)))
            } finally {
                botBridge?.close()
                if (botInputBridge === botBridge) botInputBridge = null
                if (legacyBotInput != null) runCatching { legacyBotSaveAccess?.endSave(requireNotNull(botFileId)) }
                activeBotFileId = null
                input?.delete()
                probeSessionId = null
                conversionSessionId = null
                android.os.Handler(mainLooper).post {
                    if (workerJob != job) return@post
                    val next = synchronized(stateLock) { pendingIntent.also { pendingIntent = null } }
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
    private fun cancel() {
        synchronized(stateLock) {
            if (activeJob == 0L) return
            cancelled.set(true)
        }
        botInputBridge?.close()
        probeSessionId?.let { AudioEngine.cancel(it) }
        conversionSessionId?.let { AudioEngine.cancel(it) }
        worker?.interrupt()
    }
    override fun onDestroy() { cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopSelf() }
    private fun update(stage: String, progress: Float?, job: Long) {
        synchronized(stateLock) {
            if (activeJob != job) return
            val sample = transferProgress?.snapshot()
            AppStore.state.value = ConversionState(
                running = true,
                stage = stage,
                progress = progress,
                receivedBytes = sample?.receivedBytes,
                totalBytes = sample?.totalBytes,
                bytesPerSecond = sample?.bytesPerSecond,
            )
            getSystemService(NotificationManager::class.java).notify(1, notification(stage, progress))
        }
    }
    private fun beginTransferStage(job: Long) {
        synchronized(stateLock) {
            if (activeJob != job || cancelled.get()) return
            transferProgress = TransferProgressSampler()
            AppStore.state.value = AppStore.state.value.copy(
                receivedBytes = 0L,
                totalBytes = null,
                bytesPerSecond = null,
            )
        }
    }
    private fun recordNetworkBytes(job: Long, bytes: Long, totalBytes: Long?) {
        synchronized(stateLock) {
            if (activeJob != job || cancelled.get()) return
            val sampler = transferProgress ?: return
            val updatedSample = sampler.add(bytes, totalBytes) ?: return
            AppStore.state.value = AppStore.state.value.copy(
                receivedBytes = updatedSample.receivedBytes,
                totalBytes = updatedSample.totalBytes,
                bytesPerSecond = updatedSample.bytesPerSecond,
            )
        }
    }
    private fun finish(job: Long, state: ConversionState) {
        synchronized(stateLock) {
            if (activeJob != job) return
            activeJob = 0L
            AppStore.state.value = state
        }
    }
    private fun notification(stage: String, progress: Float?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ConversionService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "conversion").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(getString(R.string.app_name)).setContentText(stage).setContentIntent(open).setOngoing(true).setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null).addAction(0, "취소", cancel).build()
    }
    private fun displayName(uri: Uri): String = try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "영상" } catch (_: Exception) { "영상" }
    companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024
        private const val CANCEL_GRACE_SECONDS = 15L
        private const val DEFAULT_CONVERSION_TIMEOUT_SECONDS = 600L
    }
}
