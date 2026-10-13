package io.github.polarfox0317.sikboonge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Executes each save in an isolated worker/session while serializing service lifecycle on main. */
open class ConversionServiceBackend : Service() {
    private class Job(
        val id: Long,
        val request: Intent,
        @Volatile var fileId: Int?,
        val fixtureUri: Uri?,
        val downloadId: Long?,
        val origin: ConversionOrigin,
        val sourceTags: List<String> = emptyList(),
        val notificationId: Int = 1,
    ) {
        val gate = BotSaveCommitGate()
        val cancelled get() = gate.cancelled
        val stopRequested get() = gate.stopRequested
        val clock = BotSaveActiveClock()
        @Volatile var sessions = BotAudioSaveCancellationScope()
        val progress = DownloadProgressTracker(
            request.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0L).takeIf { it > 0L },
            fileId?.let(BotRuntime::fileInfo)?.local?.downloadedSize?.toLong(),
        )
        val lastUiAtNanos = AtomicLong(0L)
        @Volatile var stage: String = if (origin == ConversionOrigin.BOT_SAVE) "Telegram에서 오디오 확인 중" else "영상 가져오는 중"
        @Volatile var worker: Thread? = null
        @Volatile var bridge: ExternalAudioInputBridge? = null
        @Volatile var completedResult: AudioResult? = null
        @Volatile var pinnedFileId: Int? = null
        @Volatile var runGeneration = 0L
        val committed get() = gate.committed

        val isBot: Boolean get() = origin == ConversionOrigin.BOT_SAVE
        fun checkCancelled() {
            if (cancelled.get() || stopRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
    }

    private val mainHandler by lazy { Handler(mainLooper) }
    private val stateLock = Any()
    private val scheduler = DownloadExecutionScheduler<Job>()
    private val localQueue = ArrayDeque<Job>()
    @Volatile private var localJob: Job? = null
    @Volatile private var destroyed = false
    private var foregroundStarted = false
    private var foregroundNotificationId: Int? = null
    private var foregroundJob: Job? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DownloadExecutionPreferences.MODE_KEY) mainHandler.post { pumpWork() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "오디오 추출", NotificationManager.IMPORTANCE_LOW),
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(COMPLETION_CHANNEL_ID, "오디오 저장 완료", NotificationManager.IMPORTANCE_DEFAULT),
        )
        DownloadExecutionPreferences.preferences(this).registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroyed) return START_NOT_STICKY
        when (intent?.action) {
            ConversionService.ACTION_PAUSE_DOWNLOAD -> {
                pauseDownload(intent.getLongExtra(ConversionService.EXTRA_DOWNLOAD_ID, 0L))
                pumpWork()
                return START_NOT_STICKY
            }
            ConversionService.ACTION_RESUME_DOWNLOAD -> {
                resumeDownload(intent.getLongExtra(ConversionService.EXTRA_DOWNLOAD_ID, 0L))
                pumpWork()
                return START_NOT_STICKY
            }
            ConversionService.ACTION_CANCEL_DOWNLOAD -> {
                intent.getLongExtra(ConversionService.EXTRA_DOWNLOAD_ID, 0L)
                    .takeIf { it > 0L }?.let(::cancelDownload)
                pumpWork()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_BOT -> {
                cancelBot(intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 })
                pumpWork()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                cancelAll()
                pumpWork()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_LOCAL -> {
                val activeLocal = localJob
                if (activeLocal != null) {
                    cancelJob(activeLocal)
                } else {
                    val queuedLocal = synchronized(stateLock) { localQueue.pollFirst() }
                    if (queuedLocal != null) {
                        queuedLocal.cancelled.set(true)
                        AppStore.state.value = ConversionState(stage = "취소했어요")
                    }
                }
                pumpWork()
                return START_NOT_STICKY
            }
        }
        if (intent == null) {
            pumpWork()
            return START_NOT_STICKY
        }

        val request = Intent(intent)
        try {
            val rawFixture = request.getStringExtra(ConversionService.EXTRA_DEBUG_SOURCE_URI)
            val fixtureUri = rawFixture?.let(::checkedFixtureUri)
            val fileId = request.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0).takeIf { it > 0 }
            if (fileId != null || fixtureUri != null) enqueueBotSave(request, fileId, fixtureUri)
            else enqueueLocal(request)
        } catch (error: Exception) {
            AppDiagnostics.failure("conversion-request", error)
            AppStore.state.value = ConversionState(error = UserFacingErrors.fromThrowable(error, UserFacingErrors.Context.LOCAL_MEDIA))
            pumpWork()
        }
        return START_NOT_STICKY
    }

    private fun enqueueBotSave(request: Intent, fileId: Int?, fixtureUri: Uri?) {
        val downloadId = request.getLongExtra(ConversionService.EXTRA_DOWNLOAD_ID, 0L)
            .takeIf { it > 0L } ?: nextDownloadId()
        request.putExtra(ConversionService.EXTRA_DOWNLOAD_ID, downloadId)
        if (request.getLongExtra(ConversionService.EXTRA_DOWNLOAD_DATE, 0L) <= 0L) {
            request.putExtra(ConversionService.EXTRA_DOWNLOAD_DATE, System.currentTimeMillis())
        }
        val sourceTags = request.getStringArrayListExtra(ConversionService.EXTRA_SOURCE_BOT_TAGS) ?: fileId?.let { sourceFileId ->
            val chatId = request.getLongExtra(ConversionService.EXTRA_SOURCE_BOT_CHAT_ID, 0L)
            val messageId = request.getLongExtra(ConversionService.EXTRA_SOURCE_BOT_MESSAGE_ID, 0L)
            runCatching { BotPrivateStore(this).videos.firstOrNull {
                if (chatId > 0L && messageId > 0L) it.chatId == chatId && it.messageId == messageId
                else it.fileId == sourceFileId
            }?.tags }.getOrNull()
        }.orEmpty()
        val job = Job(
            id = jobSequence.incrementAndGet(),
            request = request,
            fileId = fileId,
            fixtureUri = fixtureUri,
            downloadId = downloadId,
            origin = ConversionOrigin.BOT_SAVE,
            sourceTags = TagPolicy.normalizeTags(sourceTags),
            notificationId = nextNotificationId(),
        )
        val sourceKey = fileId?.let { "tdlib-file:$it" }
            ?: request.getStringExtra(ConversionService.EXTRA_DEBUG_SOURCE_KEY)
                ?.trim()?.takeIf(String::isNotEmpty)?.let { "debug-source:$it" }
            ?: "debug-download:$downloadId"
        when (scheduler.enqueue(downloadId, sourceKey, job)) {
            DownloadExecutionScheduler.EnqueueResult.Added -> {
                AppStore.putDownload(pendingDownload(job, "대기 중"))
                ensureForeground()
                pumpWork()
            }
            DownloadExecutionScheduler.EnqueueResult.DuplicateId,
            DownloadExecutionScheduler.EnqueueResult.DuplicateSource -> Unit
        }
    }

    private fun enqueueLocal(request: Intent) {
        val job = Job(
            id = jobSequence.incrementAndGet(),
            request = request,
            fileId = null,
            fixtureUri = null,
            downloadId = null,
            origin = ConversionOrigin.LOCAL_CONVERT,
        )
        synchronized(stateLock) { localQueue.addLast(job) }
        if (localJob == null) AppStore.state.value = ConversionState(running = true, stage = "대기 중")
        ensureForeground()
        pumpWork()
    }

    private fun checkedFixtureUri(raw: String): Uri {
        require(BuildConfig.DEBUG) { "로컬 샘플 입력은 디버그 빌드에서만 사용할 수 있어요." }
        val uri = Uri.parse(raw)
        require(uri.scheme in setOf("content", "android.resource", "file")) { "지원하지 않는 로컬 샘플 주소예요." }
        return uri
    }

    /** Local conversion stays exclusive; bot saves run concurrently up to the selected mode. */
    private fun pumpWork() {
        if (destroyed) return
        if (localJob != null) {
            refreshForeground()
            return
        }
        val activeBots = scheduler.activeEntries()
        if (activeBots.isNotEmpty()) {
            if (synchronized(stateLock) { localQueue.isEmpty() }) startAvailableBotJobs()
            refreshForeground()
            return
        }
        val nextLocal = synchronized(stateLock) { if (localQueue.isEmpty()) null else localQueue.removeFirst() }
        if (nextLocal != null) {
            localJob = nextLocal
            nextLocal.stage = "영상 가져오는 중"
            AppStore.state.value = ConversionState(origin = ConversionOrigin.LOCAL_CONVERT, running = true, stage = nextLocal.stage)
            launch(nextLocal)
            refreshForeground()
            return
        }
        startAvailableBotJobs()
        refreshForeground()
    }

    private fun startAvailableBotJobs() {
        val mode = DownloadExecutionPreferences.load(this)
        scheduler.startAvailable(mode).forEach { entry ->
            val job = entry.value
            job.gate.beginRun()
            job.sessions = BotAudioSaveCancellationScope()
            job.runGeneration++
            job.lastUiAtNanos.set(0L)
            job.clock.start()
            val metrics = job.progress.restartProcessing(job.fileId?.let(BotRuntime::fileInfo)?.local?.downloadedSize?.toLong())
            AppStore.setDownloadPaused(entry.downloadId, false)
            job.stage = "Telegram에서 오디오 확인 중"
            AppStore.updateDownload(requireNotNull(job.downloadId), job.stage, metrics)
            notifyJob(job, metrics)
            launch(job)
        }
    }

    private fun launch(job: Job) {
        if (destroyed) {
            job.downloadId?.let(AppStore::removeDownload)
            return
        }
        val thread = Thread({ runJob(job) }, "audio-save-${job.downloadId ?: job.id}").apply { isDaemon = true }
        job.worker = thread
        thread.start()
    }

    private fun runJob(job: Job) {
        val generation = job.runGeneration
        var input: File? = null
        var legacyInput: File? = null
        var output: File? = null
        var inputUri: Uri? = null
        var name = job.request.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME).orEmpty()
            .ifBlank { if (job.isBot) "Telegram 영상" else "영상" }
        val legacyAccess = BotSaveTestHooks.accessOverride
        try {
            job.checkCancelled()
            if (job.fileId != null && legacyAccess == null && BotAudioSaveTestHooks.accessOverride == null) {
                job.fileId = BotRuntime.resolveAudioSaveFile(this, job.request) {
                    job.runGeneration != generation || job.cancelled.get() || job.stopRequested.get()
                }
                job.checkCancelled()
                if (job.pinnedFileId != job.fileId) {
                    releaseCachePin(job)
                    val fileId = requireNotNull(job.fileId)
                    BotRuntime.beginPlayback(fileId) // Keep cached input ranges across worker stops.
                    job.pinnedFileId = fileId
                }
                AppStore.setDownloadSource(requireNotNull(job.downloadId), requireNotNull(job.fileId))
            }
            var source: String
            val bridge: ExternalAudioInputBridge?
            if (job.fileId != null) {
                val onRange: (Long, Long, Long?) -> Unit = { offset, bytes, total ->
                    if (job.runGeneration == generation) recordRange(job, offset, bytes, total)
                }
                if (legacyAccess != null) {
                    updateJob(job, "저장할 영상을 임시로 받는 중", null, force = true)
                    legacyInput = legacyAccess.awaitCompletedFile(requireNotNull(job.fileId), { job.cancelled.get() || job.stopRequested.get() }) { received, total ->
                        job.checkCancelled()
                        val metrics = job.progress.observeFileDownload(received, total)
                        publishProgress(job, "저장할 영상을 임시로 받는 중", null, metrics)
                    }
                    source = legacyInput.absolutePath
                    bridge = null
                } else {
                    updateJob(job, "Telegram에서 오디오 확인 중", null, force = true)
                    bridge = ExternalAudioInputBridge.forBot(
                        requireNotNull(job.fileId),
                        job.request.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0L),
                        BotAudioSaveTestHooks.accessOverride ?: BotRuntime,
                        onBytesTransferred = onRange,
                    )
                    job.bridge = bridge
                    if (job.cancelled.get() || job.stopRequested.get()) bridge.close()
                    source = bridge.inputUrl
                }
            } else {
                val uri = job.fixtureUri ?: Uri.parse(
                    job.request.getStringExtra("uri") ?: throw IllegalArgumentException("영상을 먼저 선택해 주세요."),
                )
                if (job.fixtureUri == null) require(uri.scheme == "content") {
                    "영상 파일을 다시 선택하거나 Telegram에서 파일로 공유해 주세요."
                }
                inputUri = uri
                input = File.createTempFile("conversion-${job.id}-", ".video", cacheDir)
                name = if (job.fixtureUri != null) {
                    job.request.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME).orEmpty().ifBlank { displayName(uri).substringBeforeLast('.') }
                } else displayName(uri).substringBeforeLast('.')
                stageUri(uri, input, job)
                source = input.absolutePath
                bridge = null
            }

            job.checkCancelled()
            var probe = if (job.bridge != null) inspectBot(job, requireNotNull(job.bridge)) else {
                val localInput = legacyInput ?: input ?: throw IllegalArgumentException("영상 파일을 열 수 없어요.")
                val (codec, duration) = AudioEngine.inspect(localInput)
                AudioProbe(codec, duration, localInput.length())
            }
            val format = AudioFormat.valueOf(job.request.getStringExtra("format") ?: "ORIGINAL")
            var plan = AudioEngine.plan(format, probe.codec)
            val safeStem = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "오디오" }
            val folder = File(filesDir, "audio").apply { mkdirs() }
            val outputLimit = if (job.isBot) BotAudioSavePolicy.outputLimit(folder.usableSpace) else null
            var extractionAttempt = 0
            while (true) {
                job.checkCancelled()
                val stage = if (job.isBot) {
                    if (extractionAttempt == 0) "Telegram 오디오 저장 중" else "Telegram 오디오 다시 저장 중"
                } else "오디오 추출 중"
                output = reserveOutput(folder, safeStem, plan.extension, job.id)
                updateJob(job, stage, 0f, force = true)
                try {
                    BotAudioSaveTestHooks.beforeConvert?.invoke(job.bridge)
                    AudioEngine.convert(
                        source,
                        output,
                        plan,
                        probe.duration,
                        { progress -> if (job.runGeneration == generation) updateJob(job, stage, progress) },
                        { sessionId -> job.sessions.registerSession(sessionId, AudioEngine::cancel) },
                        if (job.isBot) job.clock.remainingSeconds() else DEFAULT_CONVERSION_TIMEOUT_SECONDS,
                        externalInputBridge = job.bridge,
                        maxOutputBytes = outputLimit,
                    )
                    break
                } catch (error: IncompleteMediaInputException) {
                    output?.delete()
                    output = null
                    val retryable = job.isBot && extractionAttempt == 0 && job.bridge != null
                    if (!retryable) throw error
                    job.checkCancelled()
                    extractionAttempt++
                    job.bridge?.close()
                    job.checkCancelled()
                    val retryBridge = ExternalAudioInputBridge.forBot(
                        requireNotNull(job.fileId),
                        job.request.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, 0L),
                        BotAudioSaveTestHooks.accessOverride ?: BotRuntime,
                        onBytesTransferred = { offset, bytes, total ->
                            if (job.runGeneration == generation) recordRange(job, offset, bytes, total)
                        },
                    )
                    job.bridge = retryBridge
                    source = retryBridge.inputUrl
                    job.progress.retryProcessing(job.fileId?.let(BotRuntime::fileInfo)?.local?.downloadedSize?.toLong())
                    updateJob(job, "입력 다시 연결 중", null, force = true)
                    probe = inspectBot(job, retryBridge)
                    plan = AudioEngine.plan(format, probe.codec)
                }
            }
            job.checkCancelled()
            val artworkPath = inputUri?.let { uri -> AudioArtworkStore.captureVideoThumbnail(this, uri, input) }
                ?: AudioArtworkStore.capture(
                    this,
                    job.request.getStringExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH),
                    job.request.getStringExtra(AudioArtworkStore.EXTRA_ARTWORK_BASE64),
                )
            val result = AudioResult(
                output.absolutePath,
                output.name,
                plan.mime,
                output.length(),
                System.currentTimeMillis(),
                artworkPath,
                job.sourceTags,
            )
            job.gate.commit {
                job.checkCancelled()
                if (job.isBot) AppStore.completeDownload(this, requireNotNull(job.downloadId), result)
                else AppStore.add(this, result)
            }
            if (job.isBot) {
                job.completedResult = result
            } else {
                mainHandler.post {
                    if (localJob === job) AppStore.state.value = ConversionState(
                        origin = ConversionOrigin.LOCAL_CONVERT,
                        stage = "추출 완료",
                        progress = 1f,
                        result = result,
                    )
                }
            }
        } catch (error: InterruptedException) {
            output?.delete()
        } catch (error: Exception) {
            if (!job.committed) output?.delete()
            if (!job.cancelled.get() && !job.stopRequested.get()) {
                AppDiagnostics.failure("conversion", error)
                val context = if (job.isBot) UserFacingErrors.Context.TELEGRAM else UserFacingErrors.Context.LOCAL_MEDIA
                val userError = UserFacingErrors.fromThrowable(error, context)
                mainHandler.post {
                    if (!job.cancelled.get() && !job.stopRequested.get() && job.origin == ConversionOrigin.LOCAL_CONVERT && localJob === job) {
                        AppStore.state.value = ConversionState(error = userError)
                    } else if (!job.cancelled.get() && !job.stopRequested.get() && job.isBot && localJob == null) {
                        AppStore.state.value = ConversionState(origin = ConversionOrigin.BOT_SAVE, error = userError)
                    }
                }
            }
        } finally {
            job.sessions.cancel()
            job.bridge?.close()
            job.bridge = null
            job.pinnedFileId?.let { BotRuntime.stopAudioSaveRequests(it) }
            if (legacyInput != null && job.fileId != null) runCatching { legacyAccess?.endSave(requireNotNull(job.fileId)) }
            if (job.isBot) job.clock.stop()
            input?.delete()
            mainHandler.post { finishJob(job) }
        }
    }

    private fun stageUri(uri: Uri, destination: File, job: Job) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("영상 파일을 열 수 없어요. 다시 공유해 주세요.")
        stream.use { source ->
            destination.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    job.checkCancelled()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= ConversionService.MAX_BYTES) { "영상은 1GB 이하로 선택해 주세요." }
                    target.write(buffer, 0, count)
                }
            }
        }
    }

    private fun inspectBot(job: Job, bridge: ExternalAudioInputBridge): AudioProbe {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<AudioProbe>?>(null)
        val id = AudioEngine.inspectBotAsync(bridge) { result ->
            outcome.set(result)
            latch.countDown()
        }
        job.sessions.registerSession(id, AudioEngine::cancel)
        try {
            if (!latch.await(job.clock.remainingSeconds(), TimeUnit.SECONDS)) {
                job.sessions.cancelSession(id)
                awaitBotWorkerStopped(latch)
                throw IllegalArgumentException("Telegram 오디오 정보를 확인하는 시간이 초과됐어요. 다시 시도해 주세요.")
            }
        } catch (error: InterruptedException) {
            job.sessions.cancelSession(id)
            awaitBotWorkerStopped(latch)
            throw error
        } finally {
            job.sessions.finishSession(id)
        }
        job.checkCancelled()
        updateJob(job, "Telegram에서 오디오 확인 완료", null, force = true)
        return outcome.get()?.getOrThrow() ?: throw IllegalArgumentException("영상에서 오디오 정보를 읽을 수 없어요.")
    }

    private fun recordRange(job: Job, offset: Long, bytes: Long, total: Long?) {
        if (job.cancelled.get() || job.stopRequested.get() || job.committed) return
        var metrics = job.progress.addRange(offset, bytes, total)
        val file = job.fileId?.let(BotRuntime::fileInfo)
        if (file != null) metrics = job.progress.observePlatformDownloadedSize(file.local.downloadedSize.toLong(), total)
        publishProgress(job, job.stage, null, metrics)
    }

    private fun updateJob(job: Job, stage: String, progress: Float?, force: Boolean = false) {
        if (job.cancelled.get() || job.stopRequested.get() || job.committed) return
        job.stage = stage
        val metrics = if (progress == null) job.progress.snapshot() else job.progress.observeProgress(progress)
        publishProgress(job, stage, progress, metrics, force)
    }

    private fun publishProgress(
        job: Job,
        stage: String,
        progress: Float?,
        metrics: DownloadMetrics,
        force: Boolean = false,
    ) {
        if (job.cancelled.get() || job.stopRequested.get() || job.committed) return
        val generation = job.runGeneration
        val now = System.nanoTime()
        val previous = job.lastUiAtNanos.get()
        if (!force && now - previous < UI_UPDATE_INTERVAL_NANOS) return
        job.lastUiAtNanos.set(now)
        mainHandler.post {
            if (destroyed || !isActive(job) || job.runGeneration != generation ||
                job.cancelled.get() || job.stopRequested.get() || job.committed) return@post
            job.stage = stage
            if (job.isBot) {
                AppStore.updateDownload(requireNotNull(job.downloadId), stage, metrics)
                notifyJob(job, metrics)
            } else {
                AppStore.state.value = ConversionState(
                    origin = ConversionOrigin.LOCAL_CONVERT,
                    running = true,
                    stage = stage,
                    progress = metrics.progress ?: progress,
                    receivedBytes = metrics.receivedBytes,
                    totalBytes = metrics.totalBytes,
                    bytesPerSecond = metrics.bytesPerSecond,
                )
            }
            refreshForeground()
        }
    }

    private fun finishJob(job: Job) {
        // Posted from finally, but only acknowledge after the Java worker itself has returned.
        val worker = job.worker
        if (worker?.isAlive == true) {
            mainHandler.postDelayed({ finishJob(job) }, 10L)
            return
        }
        if (destroyed) { releaseCachePin(job); return }
        if (job.isBot) {
            val id = requireNotNull(job.downloadId)
            if (scheduler.active(id)?.value !== job) return
            if (job.stopRequested.get() && !job.cancelled.get() && !job.committed) {
                scheduler.requeueAfterStop(id)
                val paused = scheduler.isPaused(id)
                AppStore.setDownloadPaused(id, paused)
                job.stage = if (paused) "일시 중지됨" else "대기 중"
                pumpWork()
                if (paused) notifyJob(job, job.progress.snapshot())
                return
            }
            scheduler.finish(id)
            AppStore.removeDownload(id)
            releaseCachePin(job)
        } else if (localJob === job) {
            localJob = null
            if (job.cancelled.get()) AppStore.state.value = ConversionState(stage = "취소했어요")
        }
        pumpWork()
        if (job.isBot) {
            val result = job.completedResult
            if (job.committed && result != null) {
                val id = requireNotNull(job.downloadId)
                AppStore.botSaveCompletions.tryEmit(id)
                if (localJob == null) AppStore.state.value = ConversionState(
                    origin = ConversionOrigin.BOT_SAVE, botSaveCompletionId = id,
                )
                // Remove the LOW-channel record before replacing it with a DEFAULT completion.
                getSystemService(NotificationManager::class.java).cancel(job.notificationId)
                runCatching { postBotSaveCompletedNotification(job.notificationId, result) }
            } else getSystemService(NotificationManager::class.java).cancel(job.notificationId)
        } else if (foregroundNotificationId != FOREGROUND_ID) {
            getSystemService(NotificationManager::class.java).cancel(FOREGROUND_ID)
        }
    }

    private fun pauseDownload(downloadId: Long): Boolean {
        val active = scheduler.active(downloadId)?.value
        if (active != null) {
            val newlyStopping = active.gate.requestPause()
            if (!newlyStopping && (!active.stopRequested.get() || active.cancelled.get() || active.committed)) return false
            scheduler.pause(downloadId)
            AppStore.setDownloadPaused(downloadId, true, pausing = true)
            active.stage = "일시 중지 중"
            notifyJob(active, active.progress.snapshot())
            if (newlyStopping) stopWorker(active)
            return true
        }
        if (!scheduler.pause(downloadId)) return false
        AppStore.setDownloadPaused(downloadId, true)
        scheduler.queuedEntries().firstOrNull { it.downloadId == downloadId }?.value?.let {
            it.stage = "일시 중지됨"
            notifyJob(it, it.progress.snapshot())
        }
        return true
    }

    private fun resumeDownload(downloadId: Long): Boolean {
        if (!scheduler.resume(downloadId)) return false
        val stopping = scheduler.active(downloadId)?.value?.stopRequested?.get() == true
        AppStore.setDownloadPaused(downloadId, false, pausing = stopping)
        // A stop remains irreversible for this run; pump cannot launch until finishJob acknowledges it.
        return true
    }

    private fun releaseCachePin(job: Job) {
        val id = job.pinnedFileId ?: return
        job.pinnedFileId = null
        BotRuntime.endPlayback(id)
    }

    private fun cancelDownload(downloadId: Long): Boolean {
        val queued = scheduler.cancelQueued(downloadId)
        if (queued != null) {
            queued.value.gate.requestCancel()
            releaseCachePin(queued.value)
            AppStore.removeDownload(downloadId)
            AppStore.cancelledDownloadId.value = downloadId
            removeCancelledNotifications(listOf(queued.value))
            return true
        }
        val active = scheduler.active(downloadId)?.value ?: return false
        val cancelled = cancelJob(active)
        if (cancelled) {
            AppStore.removeDownload(downloadId)
            active.stage = "취소 중"
            AppStore.cancelledDownloadId.value = downloadId
            removeCancelledNotifications(listOf(active))
        }
        return cancelled
    }

    private fun cancelBot(fileId: Int?) {
        val cancelledJobs = mutableListOf<Job>()
        val removed = scheduler.removeQueuedWhere { job -> fileId == null || job.fileId == fileId }
        removed.forEach { entry ->
            entry.value.gate.requestCancel()
            releaseCachePin(entry.value)
            val id = requireNotNull(entry.value.downloadId)
            AppStore.removeDownload(id)
            AppStore.cancelledDownloadId.value = id
            cancelledJobs.add(entry.value)
        }
        scheduler.activeEntries().map { it.value }
            .filter { fileId == null || it.fileId == fileId }
            .forEach { job ->
                if (cancelJob(job)) {
                    job.downloadId?.let(AppStore::removeDownload)
                    job.stage = "취소 중"
                    AppStore.cancelledDownloadId.value = job.downloadId
                    cancelledJobs.add(job)
                }
            }
        removeCancelledNotifications(cancelledJobs)
    }

    /** Rotate/demote first: NotificationManager.cancel cannot remove an attached FGS record. */
    private fun removeCancelledNotifications(jobs: List<Job>) {
        if (!destroyed) refreshForeground()
        jobs.forEach { job ->
            if (DownloadNotificationLifecyclePolicy.shouldRemoveCancelled(notificationEntry(job), foregroundNotificationId)) {
                getSystemService(NotificationManager::class.java).cancel(job.notificationId)
            }
        }
    }

    private fun cancelAll() {
        cancelBot(null)
        localJob?.let(::cancelJob)
        val queuedLocal = synchronized(stateLock) { localQueue.toList().also { localQueue.clear() } }
        queuedLocal.forEach { job -> job.cancelled.set(true) }
    }

    private fun cancelJob(job: Job): Boolean {
        if (!job.gate.requestCancel()) return false
        stopWorker(job)
        return true
    }

    private fun stopWorker(job: Job) {
        val worker = job.worker
        val sessions = job.sessions
        val bridge = job.bridge
        worker?.interrupt()
        Thread({ sessions.cancel(); bridge?.close() }, "audio-stop-${job.id}")
            .apply { isDaemon = true }.start()
    }

    private fun isActive(job: Job): Boolean = if (job.isBot) {
        scheduler.active(requireNotNull(job.downloadId))?.value === job
    } else localJob === job

    private fun ensureForeground() {
        refreshForeground()
    }

    private fun promoteForeground(job: Job, notification: Notification) {
        val notificationId = job.notificationId
        if (foregroundStarted && foregroundNotificationId == notificationId) {
            foregroundJob = job
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)
            return
        }
        if (Build.VERSION.SDK_INT >= 35) startForeground(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        ) else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(notificationId, notification)
        foregroundStarted = true
        foregroundNotificationId = notificationId
        foregroundJob = job
    }

    private fun refreshForeground() {
        if (destroyed) return
        val active = listOfNotNull(localJob) + scheduler.activeEntries().map { it.value }
        val queued = synchronized(stateLock) { localQueue.toList() } + scheduler.queuedEntries().map { it.value }
        val activeSnapshots = active.map { it to notificationEntry(it) }
        val queuedSnapshots = queued.map { it to notificationEntry(it) }
        val next = DownloadNotificationLifecyclePolicy.selectForeground(
            activeSnapshots.map { it.second }, queuedSnapshots.map { it.second },
        )?.let { selected -> (activeSnapshots + queuedSnapshots).first { it.second === selected }.first }
        val transition = DownloadNotificationLifecyclePolicy.transition(
            foregroundJob?.takeIf { foregroundStarted }?.let(::notificationEntry), next?.let(::notificationEntry),
        )
        transition.stop?.let { stop ->
            stopForeground(if (stop == DownloadNotificationLifecyclePolicy.StopBehavior.Remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            foregroundStarted = false
            foregroundNotificationId = null
            foregroundJob = null
        }
        if (next != null) {
            val notification = when {
                next.cancelled.get() || next.committed -> stoppingNotification(next)
                next.isBot -> botNotification(next, next.progress.snapshot())
                else -> localNotification(next)
            }
            promoteForeground(next, notification)
        }
        if (!hasWork()) {
            getSystemService(NotificationManager::class.java).cancel(FOREGROUND_ID)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun notificationEntry(job: Job): DownloadNotificationLifecyclePolicy.Entry =
        DownloadNotificationLifecyclePolicy.Entry(
            notificationId = job.notificationId,
            active = isActive(job),
            cancelled = job.cancelled.get(),
            committed = job.committed,
            paused = job.downloadId?.let(scheduler::isPaused) == true,
            pausing = job.stopRequested.get(),
            pending = if (job.isBot) AppStore.downloads.value.any { it.id == job.downloadId }
                else localJob === job || synchronized(stateLock) { localQueue.any { it === job } },
        )

    /** Cancellation has no stale percentage, rate, ETA or pause/resume/cancel actions. */
    private fun stoppingNotification(job: Job): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(if (job.isBot) pendingDownload(job, job.stage).name else job.request.getStringExtra("name") ?: "오디오 추출")
        .setContentText(if (job.cancelled.get()) "취소 중" else "저장 마무리 중")
        .setContentIntent(openAppPendingIntent())
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .build()

    private fun hasWork(): Boolean = localJob != null || synchronized(stateLock) { localQueue.isNotEmpty() } || scheduler.hasWork

    private fun localNotification(job: Job): Notification {
        val text = job.stage
        val cancelIntent = PendingIntent.getService(
            this,
            FOREGROUND_ID,
            Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL_LOCAL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.request.getStringExtra("name") ?: "오디오 추출")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .addAction(0, "추출 중지", cancelIntent)
            .build()
    }

    private fun notifyJob(job: Job, metrics: DownloadMetrics) {
        if (destroyed || !DownloadNotificationLifecyclePolicy.canPostProgress(notificationEntry(job))) return
        getSystemService(NotificationManager::class.java).notify(job.notificationId, botNotification(job, metrics))
    }

    private fun botNotification(job: Job, metrics: DownloadMetrics): Notification {
        val id = requireNotNull(job.downloadId)
        val notificationId = job.notificationId
        val cancelIntent = PendingIntent.getService(
            this,
            notificationId,
            Intent(this, ConversionService::class.java)
                .setAction(ConversionService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(ConversionService.EXTRA_DOWNLOAD_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val row = AppStore.downloads.value.firstOrNull { it.id == id }
        val paused = scheduler.isPaused(id)
        val stopping = row?.pausing == true
        val content = if (paused || stopping) row?.stage ?: "일시 중지됨" else DownloadProgress.format(metrics).notification
        val progress = metrics.progress?.takeIf { !paused && !stopping && it.isFinite() }?.coerceIn(0f, .99f)
        val toggleIntent = Intent(this, ConversionService::class.java)
            .setAction(if (paused) ConversionService.ACTION_RESUME_DOWNLOAD else ConversionService.ACTION_PAUSE_DOWNLOAD)
            .putExtra(ConversionService.EXTRA_DOWNLOAD_ID, id)
        val togglePendingIntent = if (paused && Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(
            this, notificationId, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ) else PendingIntent.getService(
            this, notificationId, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(row?.name ?: pendingDownload(job, job.stage).name)
                .setContentText(content)
                .setSubText(job.stage)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppPendingIntent())
                .setOngoing(!paused || stopping)
                .apply {
                    if (!paused && !stopping) setProgress(100, ((progress ?: 0f) * 100f).toInt(), progress == null)
                    if (!stopping || paused) addAction(0, if (paused) "재개" else "일시 중지", togglePendingIntent)
                }
                .addAction(0, "취소", cancelIntent)
                .build()
    }

    private fun postBotSaveCompletedNotification(notificationId: Int, result: AudioResult) {
        val openLibrary = Intent(this, MainActivity::class.java)
            .putExtra("open_library", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openLibrary,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(result.name)
                .setContentText("저장 완료 · 보관함에 저장했어요")
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).putExtra("open_library", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun pendingDownload(job: Job, stage: String): LibraryDownload {
        val sourceName = job.request.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME).orEmpty()
            .ifBlank { job.fixtureUri?.let(::displayName) ?: "Telegram 영상" }
        val baseName = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(70).ifBlank { "오디오" }
        val mime = when (job.request.getStringExtra("format")) {
            AudioFormat.MP3.name -> "audio/mpeg"
            AudioFormat.WAV.name -> "audio/wav"
            else -> "audio/*"
        }
        return LibraryDownload(
            id = requireNotNull(job.downloadId),
            name = baseName,
            mime = mime,
            date = job.request.getLongExtra(ConversionService.EXTRA_DOWNLOAD_DATE, System.currentTimeMillis()),
            artworkPath = job.request.getStringExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH),
            stage = stage,
            sourceBotFileId = job.fileId,
            canPause = true,
        )
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "영상"
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/') ?: "영상"
    }

    private fun reserveOutput(folder: File, stem: String, extension: String, jobId: Long): File {
        repeat(Int.MAX_VALUE) { attempt ->
            val candidate = File(folder, "$stem-${System.currentTimeMillis()}-$jobId-$attempt.$extension")
            if (candidate.createNewFile()) return candidate
        }
        throw IllegalStateException("저장할 오디오 파일 이름을 만들 수 없어요.")
    }

    override fun onDestroy() {
        destroyed = true
        DownloadExecutionPreferences.preferences(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        terminateJobs()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        destroyed = true
        terminateJobs()
        stopSelf()
    }

    /** Clear visible pending rows immediately; worker finally blocks may arrive after service teardown. */
    private fun terminateJobs() {
        cancelAll()
        scheduler.activeEntries().forEach { entry ->
            val job = entry.value
            val id = requireNotNull(job.downloadId)
            cancelJob(job)
            AppStore.removeDownload(id)
            getSystemService(NotificationManager::class.java).cancel(job.notificationId)
            scheduler.finish(id)
        }
        val activeLocal = localJob
        if (activeLocal != null && !activeLocal.committed) {
            AppStore.state.value = ConversionState(stage = "취소했어요")
        }
        localJob = null
        synchronized(stateLock) { localQueue.clear() }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            foregroundNotificationId = null
            foregroundJob = null
        }
    }

    private fun nextDownloadId(): Long = downloadSequence.incrementAndGet().coerceAtLeast(1L)

    private fun nextNotificationId(): Int {
        val prefs = getSharedPreferences("audio_notification_ids", MODE_PRIVATE)
        val next = BotSaveNotificationIds.next(prefs.getInt("last", BotSaveNotificationIds.FIRST - 1))
        check(prefs.edit().putInt("last", next).commit()) { "Audio notification identity could not be saved" }
        return next
    }

    companion object {
        private const val CHANNEL_ID = "conversion"
        private const val COMPLETION_CHANNEL_ID = "bot_audio_completed"
        private const val FOREGROUND_ID = 1
        private const val UI_UPDATE_INTERVAL_NANOS = 250_000_000L
        private const val DEFAULT_CONVERSION_TIMEOUT_SECONDS = 600L
        private const val ACTION_CANCEL_BOT = "cancel_bot"
        private const val ACTION_CANCEL_LOCAL = "cancel"
        private const val ACTION_CANCEL_ALL = "cancel_all_downloads"
        private val jobSequence = AtomicLong(System.nanoTime().coerceAtLeast(1L))
        private val downloadSequence = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L) * 1000L)
    }
}
