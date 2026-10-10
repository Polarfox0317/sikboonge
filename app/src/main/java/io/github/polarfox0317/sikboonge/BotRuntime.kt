package io.github.polarfox0317.sikboonge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Staged native Bot API session. This is a bot-token MTProto client, not a user-phone login and not
 * the HTTP Bot API getFile path. Copy into app source only together with the integration notes.
 */
object BotRuntime : TdlibFileAccess, BotSavedFileAccess {
    private const val ROOT_DIR = "telegram-bot"
    private const val MAX_SAVE_VIDEO_BYTES = 1024L * 1024L * 1024L
    private const val READ_CHUNK_BYTES = 1024L * 1024L
    private const val READ_TIMEOUT_MS = 45_000L
    private const val RECEIVE_LOG_TAG = "VideoAudioBot"
    private const val THUMBNAIL_LOG_TAG = "VideoAudioThumbQA"
    private val lock = Object()
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "telegram-bot-runtime") }
    private val authScheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "telegram-bot-auth-timeout").apply { isDaemon = true } }
    private val mutableState = MutableStateFlow(BotUiState())
    private val setupLoadInFlight = AtomicBoolean(false)
    val state: StateFlow<BotUiState> = mutableState.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var client: Client? = null
    @Volatile private var clientGeneration = 0L
    @Volatile private var credentials: BotCredentials? = null
    @Volatile private var ready = false
    @Volatile private var authStarted = false
    private var connectionHasBeenReady = false
    private var connectionIsReady = false
    @Volatile private var pendingNonce: String? = null
    private var waitingParametersContext: Context? = null
    @Volatile private var pairedUserId = 0L
    @Volatile private var pairedChatId = 0L
    @Volatile private var botUsername: String? = null
    @Volatile private var botDisplayName: String? = null
    @Volatile private var botUserId = 0L
    private val chatTypes = mutableMapOf<Long, TdApi.ChatType>()
    private val files = mutableMapOf<Int, TdApi.File>()
    private val videos = linkedMapOf<Long, BotVideo>()
    private val restoredVideoMessages = mutableSetOf<Long>()
    private val tombstonedMessages = mutableSetOf<BotMessageKey>()
    private val pendingDeletions = mutableSetOf<BotMessageKey>()
    private val protectedSharedFiles = mutableSetOf<Int>()
    private val deleteAfterLease = mutableSetOf<Int>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private data class RangeRequest(val offset: Long, val limit: Long, val sentAtNanos: Long)
    private data class InboxRefreshRequest(
        val owner: Client,
        val chatId: Long,
        val userId: Long,
        val generation: Long,
        val requestId: Long,
    )
    private data class CachedPrivateState(
        val pairedUserId: Long,
        val pairedChatId: Long,
        val tombstones: Set<BotMessageKey>,
        val videos: List<BotVideo>,
    )
    private val activeRange = mutableMapOf<Int, RangeRequest>()
    private val rangeLocks = ConcurrentHashMap<Int, Any>()
    private val playbackLeases = mutableMapOf<Int, Int>()
    private val saveLeases = mutableSetOf<Int>()
    private val thumbnailDownloads = mutableSetOf<Int>()
    private val transferErrors = mutableMapOf<Int, String>()
    private var inboxRefreshInFlight = false
    private var inboxRefreshRequestId = 0L
    private var lastInboxRefreshStartedMs = 0L
    private var inboxRefreshTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    private var authWatchdog: java.util.concurrent.ScheduledFuture<*>? = null
    private val closeLatches = ConcurrentHashMap<Long, java.util.concurrent.CountDownLatch>()
    private var closingClient: Client? = null
    private var closingGeneration = 0L
    private var closingClearAccount = false

    /** Call when the app returns to the foreground; this does not start an always-on service. */
    fun setup(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (client != null) {
            publish { BotInboxRecoveryPolicy.initialStateLoaded(this) }
            return
        }
        if (!setupLoadInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                if (client != null) {
                    publish { BotInboxRecoveryPolicy.initialStateLoaded(this) }
                    return@execute
                }
                publishPersistedInboxSnapshot(app)
                when (val result = runCatching { BotCredentialsStore(app).loadResult() }
                    .getOrElse { BotCredentialsReadResult.Unreadable }) {
                    BotCredentialsReadResult.Missing -> publishCredentialRecoveryState(app, unreadable = false)
                    BotCredentialsReadResult.Unreadable -> publishCredentialRecoveryState(app, unreadable = true)
                    is BotCredentialsReadResult.Available -> connectInternal(app, result.credentials, persistCredentials = false)
                }
            } finally {
                setupLoadInFlight.set(false)
            }
        }
    }

    /** Publish the encrypted inbox before credential migration or TDLib startup can take time. */
    private fun publishPersistedInboxSnapshot(context: Context) {
        val restored = runCatching {
            val store = BotPrivateStore(context)
            val tombstones = store.deletedMessageKeys
            CachedPrivateState(
                store.pairedUserId,
                store.pairedChatId,
                tombstones,
                BotInboxRecoveryPolicy.restoreRows(store.videos, tombstones, MAX_LISTED_VIDEOS),
            )
        }.getOrNull() ?: return
        val hasPairing = restored.pairedUserId > 0L && restored.pairedChatId > 0L
        if (!hasPairing && restored.videos.isEmpty()) return
        synchronized(lock) {
            pairedUserId = restored.pairedUserId
            pairedChatId = restored.pairedChatId
            tombstonedMessages.clear()
            tombstonedMessages.addAll(restored.tombstones)
            videos.clear()
            restored.videos.forEach { videos[it.messageId] = it }
        }
        publish {
            copy(
                paired = hasPairing,
                setupComplete = setupComplete || hasPairing,
                videos = restored.videos,
            ).let(BotInboxRecoveryPolicy::initialStateLoaded)
        }
    }

    private fun publishCredentialRecoveryState(context: Context, unreadable: Boolean) {
        val restored = runCatching {
            val store = BotPrivateStore(context)
            val tombstones = store.deletedMessageKeys
            CachedPrivateState(
                store.pairedUserId,
                store.pairedChatId,
                tombstones,
                BotInboxRecoveryPolicy.restoreRows(store.videos, tombstones, MAX_LISTED_VIDEOS),
            )
        }.getOrNull()
        val snapshot = synchronized(lock) {
            if (restored != null) {
                credentials = null
                pairedUserId = restored.pairedUserId
                pairedChatId = restored.pairedChatId
                tombstonedMessages.clear()
                tombstonedMessages.addAll(restored.tombstones)
                videos.clear()
                restored.videos.forEach { videos[it.messageId] = it }
            }
            val current = mutableState.value
            val paired = (pairedUserId > 0L && pairedChatId > 0L) || current.paired
            Triple(paired, current.setupComplete || paired, videos.values.toList().ifEmpty { current.videos })
        }
        val status = when {
            unreadable -> "연결 정보를 읽지 못했어요"
            snapshot.first -> "연결 정보를 찾지 못했어요"
            else -> "연결 전"
        }
        val error = when {
            unreadable -> "저장된 봇 연결 정보를 읽을 수 없어 자동 연결을 멈췄어요. 인증 정보와 받은 영상은 삭제하지 않았어요. Android 잠금을 해제한 뒤 앱을 다시 열어 주세요."
            snapshot.first -> "저장된 봇 연결 정보가 없어요. 받은 영상과 Telegram 연결 정보는 보존했어요. 설정에서 연결 정보를 다시 입력해 주세요."
            else -> null
        }
        publish {
            copy(
                status = status,
                busy = false,
                connected = false,
                paired = snapshot.first,
                setupComplete = snapshot.second,
                videos = snapshot.third,
                error = error,
                needsReconnect = !unreadable && snapshot.first,
            ).let(BotInboxRecoveryPolicy::initialStateLoaded)
        }
    }

    fun connect(context: Context, value: BotCredentials) {
        val app = context.applicationContext
        appContext = app
        io.execute {
            if (value.apiId <= 0 || value.apiHash.isBlank() || value.token.isBlank()) {
                publish { copy(busy = false, error = "api_id, api_hash, 봇 token을 확인해 주세요.") }
                return@execute
            }
            if (synchronized(lock) { playbackLeases.isNotEmpty() || saveLeases.isNotEmpty() }) {
                publish { copy(error = "봇 재생과 저장을 중지한 뒤 다시 연결해 주세요.") }
                return@execute
            }
            if (!closeClient(app, clearAccount = credentials != null && credentials != value)) return@execute
            connectInternal(app, value, persistCredentials = true)
        }
    }

    private fun connectInternal(context: Context, value: BotCredentials, persistCredentials: Boolean) {
        var setupComplete = false
        try {
            if (persistCredentials) BotCredentialsStore(context).save(value)
            val stateStore = BotPrivateStore(context)
            val restoredTombstones = stateStore.deletedMessageKeys
            val persistedVideos = BotInboxRecoveryPolicy.restoreRows(stateStore.videos, restoredTombstones, MAX_LISTED_VIDEOS)
            val sameAccountCachedVideos = synchronized(lock) {
                if (credentials == value && pairedChatId > 0L && pairedChatId == stateStore.pairedChatId) {
                    videos.values.toList()
                } else {
                    emptyList()
                }
            }
            val restoredVideos = persistedVideos.ifEmpty {
                BotInboxRecoveryPolicy.restoreRows(sameAccountCachedVideos, restoredTombstones, MAX_LISTED_VIDEOS)
            }
            setupComplete = stateStore.pairedUserId > 0L && stateStore.pairedChatId > 0L
            val dataRoot = ownRoot(context)
            File(dataRoot, "tdlib").mkdirs()
            File(dataRoot, "files").mkdirs()
            synchronized(lock) {
                credentials = value
                pendingNonce = stateStore.pendingNonce.takeIf {
                    it != null && System.currentTimeMillis() - stateStore.pendingCreatedMs in 0..PAIRING_NONCE_TTL_MS
                }
                pairedUserId = stateStore.pairedUserId
                pairedChatId = stateStore.pairedChatId
                tombstonedMessages.clear()
                tombstonedMessages.addAll(restoredTombstones)
                pendingDeletions.clear()
                inboxRefreshInFlight = false
                inboxRefreshRequestId++
                inboxRefreshTimeout?.cancel(false)
                inboxRefreshTimeout = null
                videos.clear()
                restoredVideos.forEach {
                    videos[it.messageId] = it.copy(
                        needsForward = it.remoteFileId.isBlank(),
                        thumbnailFileId = 0,
                    )
                }
                restoredVideoMessages.clear()
                files.clear()
                thumbnailDownloads.clear()
                protectedSharedFiles.clear()
                deleteAfterLease.clear()
                chatTypes.clear()
                authStarted = false
                ready = false
                connectionHasBeenReady = false
                connectionIsReady = false
            }
            publish { BotInboxRecoveryPolicy.connectingState(this, setupComplete, restoredVideos).copy(needsReconnect = false) }
            val key = stateStore.databaseKey()
            databaseKey = key
            Client.setLogMessageHandler(0) { _, _ ->
                // Native diagnostics can contain request data. Do not mirror TDLib logs or tokens.
            }
            runCatching {
                Client.execute(TdApi.SetLogStream(TdApi.LogStreamEmpty()))
                Client.execute(TdApi.SetLogVerbosityLevel(0))
            }
            val generation = synchronized(lock) { ++clientGeneration }
            authWatchdog?.cancel(false)
            authWatchdog = authScheduler.schedule({
                if (generation == clientGeneration && !ready) {
                    publish { copy(busy = false, connected = false, status = "연결 시간이 초과됐어요", error = "네트워크를 확인하고 다시 연결해 주세요.") }
                }
            }, 60, TimeUnit.SECONDS)
            val tdClient = Client.create(
                Client.ResultHandler { obj ->
                    if (obj is TdApi.UpdateAuthorizationState && obj.authorizationState is TdApi.AuthorizationStateClosed) {
                        closeLatches[generation]?.countDown()
                        io.execute { if (closingGeneration == generation) finishClosing(context) }
                    }
                    io.execute { if (generation == clientGeneration) onObject(context, obj) }
                },
                Client.ExceptionHandler { if (generation == clientGeneration) publish { copy(error = "Telegram 연결을 시작하지 못했어요.") } },
                Client.ExceptionHandler { if (generation == clientGeneration) publish { copy(error = "Telegram 내부 통신에 문제가 있어요.") } },
            )
            val waitingContext = synchronized(lock) {
                client = tdClient
                waitingParametersContext.also { waitingParametersContext = null }
            }
            if (waitingContext != null) initializeTdlib(waitingContext)
            // The initial state update will trigger setTdlibParameters in onObject below.
        } catch (_: UnsatisfiedLinkError) {
            publishConnectionFailure("연결하지 못했어요", "Telegram 구성 요소를 불러오지 못했어요. 앱을 다시 설치해 주세요.", setupComplete)
            closeClient(context, clearAccount = false)
        } catch (error: IllegalStateException) {
            if (error.message?.startsWith("The Telegram database key is damaged") == true) {
                publishConnectionFailure("연결 정보를 읽지 못했어요", "이전 Telegram 연결 정보를 읽지 못했어요. 저장된 인증 정보와 받은 영상은 보존했어요.", setupComplete)
            } else {
                publishConnectionFailure("연결하지 못했어요", "봇 연결 정보를 안전하게 읽지 못했어요. 저장된 인증 정보와 받은 영상은 보존했어요. Android 잠금을 해제한 뒤 다시 시도해 주세요.", setupComplete)
            }
            closeClient(context, clearAccount = false)
        } catch (_: Exception) {
            publishConnectionFailure("연결하지 못했어요", "봇 설정을 확인하고 다시 시도해 주세요.", setupComplete)
            closeClient(context, clearAccount = false)
        } finally {
            publish { BotInboxRecoveryPolicy.initialStateLoaded(this) }
        }
    }

    private fun publishConnectionFailure(status: String, error: String, setupCompleteFallback: Boolean) {
        publish {
            val paired = paired || (pairedUserId > 0L && pairedChatId > 0L) || setupCompleteFallback
            copy(
                status = status,
                busy = false,
                connected = false,
                paired = paired,
                setupComplete = setupComplete || paired,
                videos = synchronized(lock) { BotRuntime.videos.values.toList().ifEmpty { this.videos } },
                error = error,
            )
        }
    }

    @Volatile private var databaseKey: ByteArray? = null

    private fun onObject(context: Context, value: TdApi.Object) {
        when (value) {
            is TdApi.UpdateAuthorizationState -> when (val auth = value.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> initializeTdlib(context)
                is TdApi.AuthorizationStateWaitPhoneNumber -> authenticateBot()
                is TdApi.AuthorizationStateReady -> onReady(context)
                is TdApi.AuthorizationStateClosed -> {
                    cancelInboxRefresh()
                    authWatchdog?.cancel(false); authWatchdog = null
                    ready = false
                    client = null
                    publish { copy(connected = false, busy = false, refreshing = false, status = "연결 해제됨") }
                }
            }
            is TdApi.UpdateConnectionState -> {
                val isConnectionReady = value.state is TdApi.ConnectionStateReady
                val recovered = synchronized(lock) {
                    val shouldRefresh = BotInboxRecoveryPolicy.shouldRefreshAfterReconnect(
                        hasBeenReady = connectionHasBeenReady,
                        wasReady = connectionIsReady,
                        isReady = isConnectionReady,
                    )
                    connectionIsReady = isConnectionReady
                    if (isConnectionReady) connectionHasBeenReady = true
                    shouldRefresh
                }
                if (isConnectionReady) {
                    if (ready) publish { copy(connected = true, busy = false, needsReconnect = false, status = "봇 연결됨") }
                    if (recovered && ready) appContext?.let(::requestPairedChatSnapshot)
                } else if (ready) {
                    cancelInboxRefresh()
                    publish { copy(connected = false, status = "Telegram 연결을 다시 시도하는 중") }
                }
            }
            is TdApi.UpdateUser -> {
                val ownUserId = botUserId.takeIf { it > 0L }
                    ?: credentials?.token?.substringBefore(':')?.toLongOrNull()
                if (value.user.id == ownUserId) updateBotIdentity(value.user)
            }
            is TdApi.UpdateNewChat -> {
                synchronized(lock) { chatTypes[value.chat.id] = value.chat.type }
                if (value.chat.id == pairedChatId) {
                    value.chat.lastMessage?.let { onNewMessage(context, it, "대화 스냅샷") }
                }
            }
            is TdApi.UpdateNewMessage -> {
                if (pairedChatId > 0L && value.message.chatId != pairedChatId) {
                    recordReceiveDiagnostic("${BotMediaClassifier.contentKind(value.message.content)} · 연결한 비공개 대화 외에서 수신돼 제외했어요.")
                }
                onNewMessage(context, value.message, "새 메시지 업데이트")
            }
            is TdApi.UpdateChatLastMessage -> {
                if (value.chatId == pairedChatId) {
                    val lastMessage = value.lastMessage
                    if (lastMessage == null) {
                        recordReceiveDiagnostic("최근 메시지 알림은 받았지만 메시지 내용은 비어 있어요.")
                    } else {
                        onNewMessage(context, lastMessage, "최근 메시지 업데이트")
                    }
                }
            }
            is TdApi.UpdateMessageContent -> onMessageContentChanged(context, value)
            is TdApi.UpdateFile -> {
                val thumbnailVideos = synchronized(lock) {
                    files[value.file.id] = value.file
                    transferErrors.remove(value.file.id)
                    activeRange[value.file.id]?.let { request ->
                        val local = value.file.local
                        val readyEnd = local.downloadOffset + local.downloadedPrefixSize
                        if (local.isDownloadingCompleted || (local.downloadOffset <= request.offset && readyEnd >= request.offset + request.limit)) {
                            activeRange.remove(value.file.id)
                        }
                    }
                    lock.notifyAll()
                    if (value.file.local.isDownloadingCompleted) thumbnailDownloads.remove(value.file.id)
                    if (value.file.local.isDownloadingCompleted && value.file.local.path.isNotBlank()) {
                        BotThumbnailPolicy.rowsForCompletedFile(videos.values.toList(), value.file.id)
                    } else emptyList()
                }
                thumbnailVideos.forEach { cacheCompletedThumbnail(appContext, it, value.file) }
            }
            is TdApi.Error -> publish {
                copy(busy = false, error = when (value.code) {
                    401 -> "봇 token이 올바르지 않아요. @BotFather에서 token을 확인해 주세요."
                    else -> "Telegram 요청이 실패했어요. 연결 상태를 확인해 주세요."
                })
            }
        }
    }

    private fun initializeTdlib(context: Context) {
        val values = synchronized(lock) {
            if (authStarted) return
            val currentClient = client
            if (currentClient == null) {
                waitingParametersContext = context
                return
            }
            val value = credentials ?: return
            val key = databaseKey ?: return
            authStarted = true
            Triple(currentClient, value, key)
        }
        val root = ownRoot(context)
        sendOwned(values.first,
            TdApi.SetTdlibParameters(
                false,
                File(root, "tdlib").absolutePath,
                File(root, "files").absolutePath,
                values.third,
                true,
                true,
                true,
                false,
                values.second.apiId,
                values.second.apiHash,
                "ko",
                Build.MODEL.ifBlank { "Android" },
                "Android ${Build.VERSION.RELEASE}",
                "VideoAudio/1.15",
            ),
        ) { result -> if (result is TdApi.Error) authFailure(result.code) }
    }

    private fun authenticateBot() {
        val token = credentials?.token ?: return
        val owner = client ?: return
        sendOwned(owner, TdApi.CheckAuthenticationBotToken(token)) { result ->
            if (result is TdApi.Error) authFailure(result.code)
        }
    }

    private fun authFailure(code: Int) {
        cancelInboxRefresh()
        publish {
            copy(
                busy = false,
                connected = false,
                status = "연결하지 못했어요",
                error = if (code == 401) "봇 token 또는 Telegram API credentials가 올바르지 않아요." else "Telegram 인증을 완료하지 못했어요. 설정을 확인해 주세요.",
                needsReconnect = true,
            )
        }
    }

    private fun onReady(context: Context) {
        ready = true
        authWatchdog?.cancel(false)
        authWatchdog = null
        val saved = BotPrivateStore(context)
        val owner = client ?: return
        sendOwned(owner, TdApi.GetMe()) { result ->
            // A failed GetMe request must not erase identity already received through UpdateUser.
            if (result is TdApi.User) updateBotIdentity(result)
            publish {
                copy(
                    busy = false,
                    connected = true,
                    needsReconnect = false,
                    status = "봇 연결됨",
                    username = botUsername,
                    botName = botDisplayName,
                    pairingUrl = pendingNonce?.let { nonce -> botUsername?.let { "https://t.me/$it?start=$nonce" } },
                    paired = pairedUserId != 0L && pairedChatId != 0L,
                    setupComplete = pairedUserId > 0L && pairedChatId > 0L,
                    videos = synchronized(lock) { BotRuntime.videos.values.toList() },
                    error = null,
                )
            }
            if (saved.pairedUserId != 0L && saved.pairedChatId != 0L) {
                requestCachedFileRefresh(context)
                synchronized(lock) { videos.values.toList() }.forEach { restoreThumbnailRemote(context, it, owner) }
                requestPairedChatSnapshot(context)
            }
        }
    }

    /** GetMe and own-user updates share identity extraction without relying on an active alias. */
    private fun updateBotIdentity(user: TdApi.User) {
        synchronized(lock) {
            botUserId = user.id
            botDisplayName = user.firstName?.trim()?.takeIf(String::isNotBlank) ?: botDisplayName
            botUsername = user.usernames?.activeUsernames?.firstOrNull { it.isNotBlank() }
                ?: user.usernames?.editableUsername?.takeIf(String::isNotBlank)
                ?: botUsername
            publish {
                copy(
                    botName = botDisplayName,
                    username = botUsername,
                    pairingUrl = pendingNonce?.let { nonce -> botUsername?.let { "https://t.me/$it?start=$nonce" } },
                )
            }
        }
    }

    fun pair(context: Context) {
        appContext = context.applicationContext
        if (!ready) {
            publish { copy(error = "먼저 봇에 연결해 주세요.") }
            return
        }
        val random = ByteArray(18).also(SecureRandom()::nextBytes)
        val nonce = Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        random.fill(0)
        pendingNonce = nonce
        BotPrivateStore(context).apply { pendingNonce = nonce; pendingCreatedMs = System.currentTimeMillis() }
        val username = botUsername
        publish {
            copy(
                pairingUrl = username?.let { "https://t.me/$it?start=$nonce" },
                paired = false,
                status = "Telegram에서 내 계정으로 시작을 눌러 연결하세요.",
                error = null,
            )
        }
    }

    /** Rename one cached inbox card locally; no Telegram message is edited. */
    fun renameVideo(
        context: Context,
        video: BotVideo,
        title: String,
        onComplete: (String?) -> Unit = {},
    ) {
        val app = context.applicationContext
        appContext = app
        val customTitle = try {
            BotVideoTitlePolicy.normalize(title)
        } catch (_: IllegalArgumentException) {
            val error = "제목은 ${BotVideoTitlePolicy.MAX_TITLE_CHARS}자 이하로 입력해 주세요."
            publish { copy(error = error) }
            mainHandler.post { onComplete(error) }
            return
        }

        io.execute {
            val targetExists = synchronized(lock) {
                video.chatId > 0L && video.chatId == pairedChatId && video.messageId > 0L &&
                    !isMessageBlockedLocked(BotMessageKey(video.chatId, video.messageId)) &&
                    videos[video.messageId]?.chatId == video.chatId
            }
            if (!targetExists) {
                val error = "받은 영상 목록에서 이 항목을 찾지 못했어요. 새로고침한 뒤 다시 시도해 주세요."
                publish { copy(error = error) }
                mainHandler.post { onComplete(error) }
                return@execute
            }

            val persisted = try {
                BotPrivateStore(app).renameVideo(video.chatId, video.messageId, customTitle)
            } catch (_: Exception) {
                null
            }
            if (persisted == null) {
                val error = "영상 제목을 저장하지 못했어요. 목록을 새로고침한 뒤 다시 시도해 주세요."
                publish { copy(error = error) }
                mainHandler.post { onComplete(error) }
                return@execute
            }

            val rows = synchronized(lock) {
                val live = videos[video.messageId]?.takeIf {
                    it.chatId == video.chatId && video.chatId == pairedChatId &&
                        !isMessageBlockedLocked(BotMessageKey(it.chatId, it.messageId))
                } ?: return@synchronized null
                videos[video.messageId] = live.copy(customTitle = customTitle)
                videos.values.toList()
            }
            if (rows == null) {
                val error = "영상이 목록에서 삭제되어 제목을 적용하지 않았어요."
                publish { copy(error = error) }
                mainHandler.post { onComplete(error) }
                return@execute
            }
            publish { copy(videos = rows, error = null) }
            mainHandler.post { onComplete(null) }
        }
    }

    /** Read only the paired chat's last-message snapshot; this is not a history request. */
    private fun requestPairedChatSnapshot(context: Context) {
        val chatId = pairedChatId
        val owner = client
        if (chatId <= 0L || owner == null) return
        recordReceiveDiagnostic("연결된 대화의 최근 메시지를 확인하고 있어요.")
        sendOwned(owner, TdApi.GetChat(chatId)) { result ->
            io.execute {
                if (client !== owner || pairedChatId != chatId) return@execute
                val chat = result as? TdApi.Chat
                if (chat == null || chat.id != chatId) {
                    logReceiveDiagnostic("snapshot result=${result.javaClass.simpleName} errorCode=${(result as? TdApi.Error)?.code ?: 0}")
                    recordReceiveDiagnostic("최근 메시지 조회를 확인할 수 없어 새 수신 업데이트를 기다리고 있어요.")
                    return@execute
                }
                val chatType = chat.type as? TdApi.ChatTypePrivate
                if (chatType == null || chatType.userId != pairedUserId) {
                    recordReceiveDiagnostic("연결된 대화 정보를 비공개 대화로 확인하지 못했어요.")
                    return@execute
                }
                synchronized(lock) { chatTypes[chat.id] = chat.type }
                val lastMessage = chat.lastMessage
                requestIndexedMessageMetadata(context, owner, chatId)
                if (lastMessage == null) {
                    recordReceiveDiagnostic("최근 메시지 내용을 조회하지 못해 새 수신 업데이트를 기다리고 있어요.")
                } else {
                    onNewMessage(context, lastMessage, "연결 후 최근 메시지 확인")
                }
            }
        }
    }

    /** Refresh only the paired chat snapshot and known inbox rows; this never scans chat history. */
    fun refreshInbox(context: Context) {
        val app = context.applicationContext
        appContext = app
        var request: InboxRefreshRequest? = null
        var diagnostic: String? = null
        var error: String? = null
        var clearRefreshing = false
        synchronized(lock) {
            val ui = mutableState.value
            when {
                inboxRefreshInFlight -> diagnostic = "받은 영상 새로고침을 진행하고 있어요."
                !ready || client == null || !ui.connected -> {
                    diagnostic = "봇 연결이 완료된 뒤 새로고침할 수 있어요."
                    error = "Telegram 봇에 연결한 뒤 다시 시도해 주세요."
                    clearRefreshing = true
                }
                pairedUserId <= 0L || pairedChatId <= 0L || !ui.paired -> {
                    diagnostic = "연결한 Telegram 계정을 확인해 주세요."
                    error = "먼저 내 Telegram 계정을 봇에 연결해 주세요."
                    clearRefreshing = true
                }
                ui.busy -> {
                    diagnostic = "봇 연결이 완료된 뒤 새로고침할 수 있어요."
                    error = "봇 연결이 끝난 뒤 다시 시도해 주세요."
                    clearRefreshing = true
                }
                System.currentTimeMillis() - lastInboxRefreshStartedMs < REFRESH_COOLDOWN_MS -> {
                    diagnostic = "잠시 후 다시 새로고침해 주세요."
                }
                else -> {
                    inboxRefreshInFlight = true
                    inboxRefreshRequestId += 1L
                    lastInboxRefreshStartedMs = System.currentTimeMillis()
                    val currentOwner = requireNotNull(client)
                    val requestId = inboxRefreshRequestId
                    request = InboxRefreshRequest(currentOwner, pairedChatId, pairedUserId, clientGeneration, requestId)
                    inboxRefreshTimeout?.cancel(false)
                    inboxRefreshTimeout = authScheduler.schedule({
                        finishInboxRefresh(
                            requestId,
                            "새로고침 응답을 받지 못했어요. 연결 상태를 확인해 주세요.",
                            "새로고침 시간이 초과됐어요. 연결 상태를 확인하고 다시 시도해 주세요.",
                        )
                    }, REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        val ticket = request
        if (ticket == null) {
            publish {
                copy(
                    refreshing = if (clearRefreshing) false else refreshing,
                    receiveDiagnostic = diagnostic ?: receiveDiagnostic,
                    error = error ?: this.error,
                )
            }
            return
        }

        publish { copy(refreshing = true, error = null, receiveDiagnostic = "최근 받은 영상 정보를 새로고침하고 있어요.") }
        try {
            sendOwned(ticket.owner, TdApi.GetChat(ticket.chatId)) { result ->
                io.execute {
                    if (!isCurrentInboxRefresh(ticket)) {
                        finishInboxRefresh(
                            ticket.requestId,
                            "연결이 바뀌어 새로고침을 중단했어요.",
                            "Telegram 연결이 바뀌어 새로고침을 완료하지 못했어요.",
                        )
                        return@execute
                    }
                    val chat = result as? TdApi.Chat
                    if (chat == null || chat.id != ticket.chatId) {
                        logReceiveDiagnostic("refresh chat result=${result.javaClass.simpleName} errorCode=${(result as? TdApi.Error)?.code ?: 0}")
                        finishInboxRefresh(
                            ticket.requestId,
                            "Telegram에서 최근 메시지를 확인하지 못했어요.",
                            "최근 메시지를 새로고침하지 못했어요. 연결 상태를 확인해 주세요.",
                        )
                        return@execute
                    }
                    val privateChat = chat.type as? TdApi.ChatTypePrivate
                    if (privateChat == null || privateChat.userId != ticket.userId) {
                        finishInboxRefresh(
                            ticket.requestId,
                            "연결한 계정의 비공개 대화를 확인하지 못했어요.",
                            "연결한 Telegram 계정의 비공개 대화를 확인할 수 없어요.",
                        )
                        return@execute
                    }
                    synchronized(lock) { chatTypes[chat.id] = chat.type }
                    chat.lastMessage?.let { onNewMessage(app, it, "목록 새로고침", metadataRefresh = true) }

                    // Refresh stable remote identifiers and only request bounded thumbnail bytes.
                    requestCachedFileRefresh(app, forceRefresh = true)
                    val rows = synchronized(lock) {
                        videos.values.filter {
                            it.chatId == ticket.chatId && !isMessageBlockedLocked(BotMessageKey(it.chatId, it.messageId))
                        }.toList()
                    }
                    rows.forEach { restoreThumbnailRemote(app, it, ticket.owner) }
                    requestIndexedMessageMetadata(app, ticket.owner, ticket.chatId, forceRefresh = true) { successful, total ->
                        if (isCurrentInboxRefresh(ticket)) {
                            val (completed, refreshError) = when {
                                total == 0 && chat.lastMessage == null ->
                                    "최근 메시지가 없어요. 저장된 영상도 없어요." to null
                                total == 0 ->
                                    "최근 메시지를 확인했어요. 다시 가져올 저장된 영상 정보가 없어요." to null
                                successful == 0 ->
                                    "최근 메시지는 확인했지만 저장된 영상 정보 응답을 받지 못했어요." to
                                        "일부 영상 정보를 새로고침하지 못했어요. 연결 상태를 확인해 주세요."
                                successful < total ->
                                    "최근 메시지와 일부 저장된 영상 정보를 확인했어요." to
                                        "몇 개 영상 정보를 새로고침하지 못했어요. 다시 시도해 주세요."
                                chat.lastMessage == null ->
                                    "최근 메시지가 없어요. 저장된 영상 정보를 새로 확인했어요." to null
                                else ->
                                    "최근 메시지와 저장된 영상 정보를 새로 확인했어요." to null
                            }
                            finishInboxRefresh(ticket.requestId, completed, refreshError)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            finishInboxRefresh(
                ticket.requestId,
                "새로고침을 시작하지 못했어요. 연결 상태를 확인해 주세요.",
                "새로고침을 시작하지 못했어요. 잠시 후 다시 시도해 주세요.",
            )
        }
    }

    private fun isCurrentInboxRefresh(request: InboxRefreshRequest): Boolean = synchronized(lock) {
        inboxRefreshInFlight && BotInboxRecoveryPolicy.isCurrentRefresh(
            requestOwnerIsCurrent = client === request.owner,
            requestGeneration = request.generation,
            currentGeneration = clientGeneration,
            requestId = request.requestId,
            currentRequestId = inboxRefreshRequestId,
            requestChatId = request.chatId,
            currentChatId = pairedChatId,
            requestUserId = request.userId,
            currentUserId = pairedUserId,
            ready = ready,
        )
    }

    private fun finishInboxRefresh(requestId: Long, diagnostic: String, error: String?) {
        val finished = synchronized(lock) {
            if (!inboxRefreshInFlight || inboxRefreshRequestId != requestId) false
            else {
                inboxRefreshInFlight = false
                inboxRefreshTimeout?.cancel(false)
                inboxRefreshTimeout = null
                lock.notifyAll()
                true
            }
        }
        if (finished) publish { copy(refreshing = false, receiveDiagnostic = diagnostic, error = error) }
    }

    private fun cancelInboxRefresh() {
        val requestId = synchronized(lock) { inboxRefreshRequestId.takeIf { inboxRefreshInFlight } } ?: return
        finishInboxRefresh(
            requestId,
            "Telegram 연결이 바뀌어 새로고침을 중단했어요.",
            "Telegram 연결이 바뀌어 새로고침을 완료하지 못했어요.",
        )
    }

    private fun onMessageContentChanged(context: Context, update: TdApi.UpdateMessageContent) {
        if (update.chatId != pairedChatId || pairedChatId <= 0L || isMessageBlocked(update.chatId, update.messageId)) return
        recordReceiveDiagnostic("메시지 내용 업데이트 수신 · ${BotMediaClassifier.contentKind(update.newContent)} · 메시지 확인 중이에요.")
        val owner = client ?: run {
            recordReceiveDiagnostic("메시지 내용은 갱신됐지만 봇 연결을 확인할 수 없어요.")
            return
        }
        sendOwned(owner, TdApi.GetMessage(update.chatId, update.messageId)) { result ->
            io.execute {
                if (client !== owner) return@execute
                val message = result as? TdApi.Message
                if (message == null) {
                    logReceiveDiagnostic("content-refresh result=${result.javaClass.simpleName} errorCode=${(result as? TdApi.Error)?.code ?: 0}")
                    recordReceiveDiagnostic("메시지 내용 업데이트 후 원본 메시지를 조회하지 못했어요.")
                } else {
                    onNewMessage(context, message, "메시지 내용 업데이트 조회")
                }
            }
        }
    }

    /** Refresh metadata only for already indexed rows; never scans chat history or downloads media. */
    private fun requestIndexedMessageMetadata(
        context: Context,
        owner: Client,
        chatId: Long,
        forceRefresh: Boolean = false,
        onComplete: ((successful: Int, total: Int) -> Unit)? = null,
    ) {
        if (!ready || client !== owner || pairedChatId != chatId) return
        val indexed = synchronized(lock) {
            val rows = BotInboxDeletionPolicy.refreshRows(
                videos.values.toList(), chatId, tombstonedMessages + pendingDeletions, MAX_LISTED_VIDEOS,
            )
            (if (forceRefresh) rows else BotThumbnailPolicy.rowsNeedingMetadata(rows) {
                BotVideoThumbnailData.hasRenderableCache(context, it.thumbnailPath)
            }).take(MAX_LISTED_VIDEOS)
        }
        if (indexed.isEmpty()) {
            onComplete?.invoke(0, 0)
            return
        }
        val remaining = AtomicInteger(indexed.size)
        val successful = AtomicInteger(0)
        indexed.forEach { video ->
            sendOwned(owner, TdApi.GetMessage(chatId, video.messageId)) { result ->
                io.execute {
                    try {
                        if (!ready || client !== owner || pairedChatId != chatId) return@execute
                        val message = result as? TdApi.Message ?: return@execute
                        if (message.chatId != chatId || message.id != video.messageId) return@execute
                        successful.incrementAndGet()
                        if (synchronized(lock) {
                                val key = BotMessageKey(chatId, video.messageId)
                                videos[video.messageId] == null || isMessageBlockedLocked(key)
                            }) return@execute
                        onNewMessage(context, message, "저장된 미리보기 정보 확인", metadataRefresh = true)
                    } finally {
                        if (remaining.decrementAndGet() == 0) onComplete?.invoke(successful.get(), indexed.size)
                    }
                }
            }
        }
    }

    private fun recordReceiveDiagnostic(value: String) {
        logReceiveDiagnostic(value)
        publish { copy(receiveDiagnostic = value) }
    }

    private fun logReceiveDiagnostic(value: String) {
        val flags = appContext?.applicationInfo?.flags ?: return
        if (flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.i(RECEIVE_LOG_TAG, value)
    }

    private fun onNewMessage(
        context: Context,
        message: TdApi.Message,
        source: String = "새 메시지 업데이트",
        metadataRefresh: Boolean = false,
    ) {
        val contentKind = BotMediaClassifier.contentKind(message.content)
        val fromPairedChat = pairedChatId > 0L && message.chatId == pairedChatId
        val knownChatType = synchronized(lock) { chatTypes[message.chatId] }
        logReceiveDiagnostic(
            "event=$source content=${message.content?.javaClass?.simpleName ?: "null"} " +
                "senderKind=${message.senderId?.javaClass?.simpleName ?: "null"} " +
                "chatKind=${knownChatType?.javaClass?.simpleName ?: "unknown"} " +
                "pairedChat=$fromPairedChat outgoing=${message.isOutgoing} saveAllowed=${message.canBeSaved}",
        )
        if (message.isOutgoing) {
            if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · $contentKind · 보낸 메시지라 제외했어요.")
            return
        }
        if (message.chatId <= 0L) {
            if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · 개인 대화 메시지가 아니어서 제외했어요.")
            return
        }
        val sender = (message.senderId as? TdApi.MessageSenderUser)?.userId ?: run {
            if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · 사용자 발신이 아닌 메시지예요.")
            return
        }
        val chatType = synchronized(lock) { chatTypes[message.chatId] }
        if (chatType == null) {
            if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · 대화 유형 정보를 확인하지 못했어요.")
            return
        }
        if (chatType !is TdApi.ChatTypePrivate) {
            if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · 비공개 대화가 아니라서 제외했어요.")
            return
        }
        if (chatType.userId != sender) {
            if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · 대화 발신자가 일치하지 않아요.")
            return
        }
        if (isMessageBlocked(message.chatId, message.id)) {
            if (!metadataRefresh) recordReceiveDiagnostic("$source · 삭제된 영상의 이전 업데이트를 무시했어요.")
            return
        }
        val text = (message.content as? TdApi.MessageText)?.text?.text.orEmpty().trim()
        val store = BotPrivateStore(context)
        val nonce = pendingNonce
        if (sender == message.chatId && BotInboxPolicy.pairingMatches(text, nonce, store.pendingCreatedMs, System.currentTimeMillis())) {
            pairedUserId = sender
            pairedChatId = message.chatId
            pendingNonce = null
            BotPrivateStore(context).apply {
                this.pendingNonce = null
                this.pendingCreatedMs = 0L
                this.pairedUserId = sender
                this.pairedChatId = message.chatId
            }
            publish {
                copy(
                paired = true,
                setupComplete = credentials != null && pairedUserId > 0L && pairedChatId > 0L,
                pairingUrl = null,
                    status = "내 Telegram 계정이 연결됐어요.",
                    receiveDiagnostic = "계정 연결 메시지를 확인했어요.",
                    error = null,
                )
            }
            return
        }
        if (!BotInboxPolicy.acceptsPairedPrivateIncoming(
                sender, message.chatId, pairedUserId, pairedChatId, privateChat = true, outgoing = message.isOutgoing,
            )) {
            if (fromPairedChat) recordReceiveDiagnostic("$source · $contentKind · 연결한 계정의 메시지가 아니어서 제외했어요.")
            return
        }
        if (fromPairedChat && !metadataRefresh) recordReceiveDiagnostic("$source · $contentKind · 영상 형식 확인 중이에요.")
        val media = BotMediaClassifier.classify(message.content)
        if (media == null) {
            if (metadataRefresh) return
            val textContent = message.content as? TdApi.MessageText
            if (textContent != null && BotInboxPolicy.containsPrivatePostLink(textContent.text.text)) {
                recordReceiveDiagnostic("$source · 텍스트 메시지 · 링크만 있어 제외했어요.")
                publish { copy(error = "비공개 게시물 링크만으로는 영상을 받을 수 없어요. 원본 게시물에서 영상 자체를 봇에게 전달해 주세요.") }
            } else {
                val explanation = BotMediaClassifier.rejectionReason(message.content)
                if (explanation != null) {
                    recordReceiveDiagnostic("$source · $contentKind · 지원되는 영상 파일을 찾지 못했어요.")
                    publish { copy(error = explanation) }
                } else {
                    recordReceiveDiagnostic("$source · $contentKind · 영상 메시지가 아니어서 목록에 추가하지 않았어요.")
                }
            }
            return
        }
        if (!BotInboxPolicy.acceptsVideo(
                sender, message.chatId, pairedUserId, pairedChatId, privateChat = true,
                outgoing = message.isOutgoing, canBeSaved = message.canBeSaved,
        )) {
            if (metadataRefresh) return
            recordReceiveDiagnostic("$source · $contentKind · Telegram 보호 설정으로 제외했어요.")
            publish { copy(error = "이 영상은 Telegram에서 보호되어 재생할 수 없어요.") }
            return
        }
        val video = BotVideo(
            fileId = media.file.id,
            chatId = message.chatId,
            messageId = message.id,
            name = media.name,
            bytes = BotMediaClassifier.listedFileSize(media.file),
            durationSeconds = media.durationSeconds,
            date = message.date.toLong(),
            remoteFileId = media.file.remote?.id.orEmpty(),
            mimeType = media.mimeType,
            thumbnailRemoteFileId = media.thumbnailFile?.remote?.id.orEmpty(),
            thumbnailFileId = media.thumbnailFile?.id ?: 0,
            thumbnailBase64 = BotVideoThumbnailData.encodeMini(media.minithumbnail),
        )
        val indexed = synchronized(lock) { videos.values.map { it.messageId to it.fileId }.toSet() }
        val duplicate = BotInboxPolicy.isDuplicateMedia(message.id, media.file.id, indexed)
        val previous = synchronized(lock) { videos[video.messageId] }
        val mergedVideo = video.mergeThumbnailMetadata(previous)
        val admission = synchronized(lock) {
            val key = BotMessageKey(video.chatId, video.messageId)
            if (isMessageBlockedLocked(key)) return@synchronized null
            files[video.fileId] = media.file
            videos[video.messageId] = mergedVideo
            restoredVideoMessages += video.messageId
            deleteAfterLease.remove(video.fileId)
            if (video.thumbnailFileId > 0) deleteAfterLease.remove(video.thumbnailFileId)
            val removed = buildList<BotVideo> {
                while (videos.size > MAX_LISTED_VIDEOS) videos.remove(videos.keys.first())?.let { add(it) }
            }
            lock.notifyAll()
            removed to videos.values.toList()
        }
        if (admission == null) return
        val evicted = admission.first
        evicted.forEach { it.thumbnailPath?.let(::deleteThumbnailCache) }
        previous?.thumbnailPath?.takeIf { it != mergedVideo.thumbnailPath }?.let(::deleteThumbnailCache)
        if (previous != mergedVideo) BotPrivateStore(context).videos = admission.second
        val diagnostic = if (metadataRefresh) "저장된 영상의 미리보기 정보를 새로 확인했어요."
            else if (duplicate) "$source · $contentKind · 이미 목록에 등록된 메시지예요."
            else "$source · $contentKind · 영상 목록에 추가했어요."
        if (!metadataRefresh) logReceiveDiagnostic(diagnostic)
        publish {
            copy(
                videos = synchronized(lock) { BotRuntime.videos.values.toList() },
                status = if (duplicate || metadataRefresh) status else "새 영상이 도착했어요.",
                receiveDiagnostic = if (metadataRefresh) receiveDiagnostic else diagnostic,
                error = if (metadataRefresh) error else null,
            )
        }
        if (!BotVideoThumbnailData.hasRenderableCache(context, mergedVideo.thumbnailPath)) {
            media.thumbnailFile?.let { startThumbnailDownload(context, mergedVideo, it) }
        }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(a, b)
    }

    private fun isMessageBlocked(chatId: Long, messageId: Long): Boolean = synchronized(lock) {
        isMessageBlockedLocked(BotMessageKey(chatId, messageId))
    }

    private fun isMessageBlockedLocked(key: BotMessageKey): Boolean =
        key in tombstonedMessages || key in pendingDeletions

    /** Remove selected inbox rows locally. This never asks Telegram to delete remote messages. */
    fun removeVideos(context: Context, messageIds: Set<Long>, onComplete: (String?) -> Unit = {}) {
        val app = context.applicationContext
        appContext = app
        val ids = messageIds.filterTo(mutableSetOf()) { it > 0L }
        if (ids.isEmpty()) {
            mainHandler.post { onComplete("삭제할 영상을 선택해 주세요.") }
            return
        }

        val chatHint = synchronized(lock) {
            pairedChatId.takeIf { it > 0L }
                ?: videos.values.firstOrNull { it.messageId in ids }?.chatId?.takeIf { it > 0L }
        }
        val initiallyPending = BotInboxDeletionPolicy.keys(chatHint ?: 0L, ids)
        synchronized(lock) { pendingDeletions.addAll(initiallyPending) }
        io.execute {
            var pendingKeys = initiallyPending
            try {
                val store = BotPrivateStore(app)
                val chatId = chatHint
                    ?: store.pairedChatId.takeIf { it > 0L }
                    ?: store.videos.firstOrNull { it.messageId in ids }?.chatId?.takeIf { it > 0L }
                    ?: throw IllegalStateException("paired chat unavailable")
                val keys = BotInboxDeletionPolicy.keys(chatId, ids)
                pendingKeys = keys
                synchronized(lock) { pendingDeletions.addAll(keys) }

                // The encrypted row list and tombstones are committed before visible state changes.
                val persistedRemoved = store.removeVideos(chatId, ids)
                val cleanup = synchronized(lock) {
                    val removedFromRuntime = videos.values.filter { BotMessageKey(it.chatId, it.messageId) in keys }
                    val removedByKey = (persistedRemoved + removedFromRuntime)
                        .associateBy { BotMessageKey(it.chatId, it.messageId) }.values.toList()
                    keys.forEach { key ->
                        if (videos[key.messageId]?.chatId == key.chatId) videos.remove(key.messageId)
                        restoredVideoMessages.remove(key.messageId)
                    }
                    tombstonedMessages.addAll(keys)

                    val remainingRows = videos.values.toList()
                    val stillReferenced = remainingRows.asSequence()
                        .flatMap { sequenceOf(it.fileId, it.thumbnailFileId) }
                        .filter { it > 0 }
                        .toSet()
                    val candidateFiles = removedByKey.asSequence()
                        .flatMap { sequenceOf(it.fileId, it.thumbnailFileId) }
                        .filter { it > 0 }
                        .toSet()
                    val deleteNow = mutableSetOf<Int>()
                    candidateFiles.forEach { fileId ->
                        if (fileId in stillReferenced) {
                            protectedSharedFiles += fileId
                            deleteAfterLease -= fileId
                        } else {
                            protectedSharedFiles -= fileId
                            if (hasActiveFileLeaseLocked(fileId)) deleteAfterLease += fileId
                            else {
                                deleteAfterLease -= fileId
                                deleteNow += fileId
                            }
                        }
                    }
                    val remainingThumbnailPaths = remainingRows.mapNotNullTo(mutableSetOf()) { it.thumbnailPath }
                    val thumbnailPaths = removedByKey.mapNotNull { it.thumbnailPath }
                        .filterNot { it in remainingThumbnailPaths }.distinct()
                    pendingDeletions.removeAll(keys)
                    lock.notifyAll()
                    Triple(remainingRows, deleteNow.toList(), thumbnailPaths)
                }

                cleanup.third.forEach(::deleteThumbnailCache)
                cleanup.second.forEach { fileId -> runCatching { deleteLocalFile(fileId) } }
                publish { copy(videos = cleanup.first, error = null) }
                mainHandler.post { onComplete(null) }
            } catch (_: Exception) {
                synchronized(lock) {
                    pendingDeletions.removeAll(pendingKeys)
                    lock.notifyAll()
                }
                val error = "영상을 삭제하지 못했어요. 잠시 후 다시 시도해 주세요."
                publish { copy(error = error) }
                mainHandler.post { onComplete(error) }
            }
        }
    }

    private fun hasActiveFileLeaseLocked(fileId: Int): Boolean =
        fileId in saveLeases || (playbackLeases[fileId] ?: 0) > 0

    private fun shouldDeleteReleasedFileLocked(fileId: Int): Boolean {
        if (hasActiveFileLeaseLocked(fileId) || fileId in protectedSharedFiles) return false
        deleteAfterLease.remove(fileId)
        return true
    }

    fun play(context: Context, video: BotVideo) {
        io.execute {
            resolveVideoFile(context.applicationContext, video) { resolved ->
                mainHandler.post {
                    if (ready && !isMessageBlocked(resolved.chatId, resolved.messageId)) {
                        StreamPlayback.playBot(context, resolved)
                    }
                }
            }
        }
    }

    /** Resolve an inbox item before opening it in a compatible external video app. */
    fun resolveForExternalPlayback(context: Context, video: BotVideo, onResolved: (BotVideo) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val owner = client
            if (owner == null || !ready) {
                publish { copy(error = "봇 연결이 완료된 뒤 다시 들어 주세요.") }
                return@execute
            }
            if (pairedUserId <= 0L || video.chatId != pairedChatId || isMessageBlocked(video.chatId, video.messageId)) {
                publish { copy(error = "연결한 Telegram 계정의 비공개 대화 영상만 열 수 있어요.") }
                return@execute
            }
            val current = synchronized(lock) { videos[video.messageId] }
            if (current == null || current.chatId != pairedChatId) {
                publish { copy(error = "이 영상은 현재 봇 받은 편지함에 없어요. 목록을 새로고침해 주세요.") }
                return@execute
            }
            resolveVideoFile(app, video) { resolved ->
                val generation = clientGeneration
                mainHandler.post {
                    if (client === owner && generation == clientGeneration && ready && pairedUserId > 0L &&
                        resolved.chatId == pairedChatId && resolved.messageId == video.messageId &&
                        !isMessageBlocked(resolved.chatId, resolved.messageId)) {
                        onResolved(resolved)
                    } else {
                        publish { copy(error = "봇 연결이 변경되어 영상을 열지 못했어요. 다시 시도해 주세요.") }
                    }
                }
            }
        }
    }

    /** Explicit Save starts an audio-only conversion from the paired bot video. */
    fun save(context: Context, video: BotVideo, format: String = "ORIGINAL") {
        if (format !in setOf("ORIGINAL", "WAV", "MP3")) {
            publish { copy(error = "저장 형식을 다시 선택해 주세요.") }
            return
        }
        val app = context.applicationContext
        val currentState = synchronized(lock) { videos[video.messageId] }
        val sender = (synchronized(lock) { chatTypes[video.chatId] } as? TdApi.ChatTypePrivate)?.userId
        if (!ready || pairedUserId <= 0L || currentState == null || currentState.chatId != pairedChatId ||
            isMessageBlocked(video.chatId, video.messageId) ||
            currentState.needsForward || sender != pairedUserId || video.chatId != pairedChatId) {
            publish { copy(error = "연결한 비공개 대화의 저장 가능한 영상을 선택해 주세요.") }
            return
        }
        publish { copy(error = null) }
        val requestedGeneration = clientGeneration
        io.execute {
            resolveVideoFile(app, video) { resolved ->
                mainHandler.post {
                    val live = synchronized(lock) { videos[resolved.messageId] }
                    val liveSender = (synchronized(lock) { chatTypes[resolved.chatId] } as? TdApi.ChatTypePrivate)?.userId
                    val blocked = isMessageBlocked(resolved.chatId, resolved.messageId)
                    if (blocked) return@post
                    if (ready && requestedGeneration == clientGeneration && pairedUserId > 0L &&
                        resolved.chatId == pairedChatId && live?.fileId == resolved.fileId && !live.needsForward &&
                        liveSender == pairedUserId && !blocked) {
                        val intent = Intent(app, ConversionService::class.java)
                            .putExtra(EXTRA_TDLIB_FILE_ID, resolved.fileId)
                            .putExtra(EXTRA_TDLIB_NAME, resolved.displayName)
                            .putExtra("format", format)
                            .putExtra(AudioArtworkStore.EXTRA_ARTWORK_PATH, resolved.thumbnailPath)
                            .putExtra(AudioArtworkStore.EXTRA_ARTWORK_BASE64, resolved.thumbnailBase64)
                            .putExtra("tdlib_file_size", resolved.bytes)
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
                            else app.startService(intent)
                        } catch (error: Exception) {
                            AppDiagnostics.failure("bot-save-start", error)
                            publish { copy(error = "오디오 저장을 시작하지 못했어요. 앱을 열고 다시 시도해 주세요.") }
                        }
                    } else {
                        publish { copy(error = "봇 연결이 바뀌어 오디오 저장을 시작하지 못했어요. 다시 시도해 주세요.") }
                    }
                }
            }
        }
    }

    /** ConversionService calls this on its worker thread after an explicit Save request. */
    override fun awaitCompletedFile(fileId: Int, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val tdClient = client ?: throw java.io.IOException("Telegram bot is disconnected")
        if (BotMediaClassifier.listedFileSize(getCurrentFile(fileId, tdClient)) > MAX_SAVE_VIDEO_BYTES) {
            throw java.io.IOException("Telegram full-video save exceeds the 1GB limit")
        }
        synchronized(lock) { saveLeases += fileId }
        var completedPathReturned = false
        try {
            sendOwned(tdClient, TdApi.DownloadFile(fileId, 32, 0L, 0L, false)) { result ->
                synchronized(lock) {
                    if (result is TdApi.Error) transferErrors[fileId] = "Telegram could not download this file"
                    if (result is TdApi.File) files[fileId] = result
                    lock.notifyAll()
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            while (true) {
                if (cancelled()) throw java.io.InterruptedIOException("Telegram save cancelled")
                val file = getCurrentFile(fileId, tdClient)
                val total = BotMediaClassifier.listedFileSize(file)
                if (total > MAX_SAVE_VIDEO_BYTES) throw java.io.IOException("Telegram full-video save exceeds the 1GB limit")
                progress(file.local.downloadedSize, total)
                if (file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
                    val local = File(file.local.path)
                    if (local.isFile && local.length() > 0L) {
                        requireOwnedTdlibFile(local)
                        completedPathReturned = true
                        return local
                    }
                }
                synchronized(lock) { transferErrors.remove(fileId)?.let { throw java.io.IOException(it) } }
                if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("Telegram file download timed out")
                synchronized(lock) { if (!cancelled()) lock.wait(500L) }
            }
        } finally {
            if (!completedPathReturned) {
                val delete = synchronized(lock) {
                    saveLeases.remove(fileId)
                    lock.notifyAll()
                    shouldDeleteReleasedFileLocked(fileId)
                }
                if (delete) deleteLocalFile(fileId)
            }
        }
    }

    override fun endSave(fileId: Int) {
        val delete = synchronized(lock) {
            saveLeases.remove(fileId)
            lock.notifyAll()
            shouldDeleteReleasedFileLocked(fileId)
        }
        if (delete) deleteLocalFile(fileId)
    }

    internal fun ensureFileInfo(fileId: Int): TdApi.File = getCurrentFile(
        fileId,
        client ?: throw java.io.IOException("Telegram bot is disconnected"),
    )

    override fun acquireFile(fileId: Int): TdApi.File {
        val file = ensureFileInfo(fileId)
        if (file.local.path.isNotBlank()) requireOwnedTdlibFile(File(file.local.path))
        return file
    }

    override fun beginPlayback(fileId: Int) {
        synchronized(lock) { playbackLeases[fileId] = (playbackLeases[fileId] ?: 0) + 1 }
    }

    override fun endPlayback(fileId: Int) {
        val delete = synchronized(lock) {
            val remaining = ((playbackLeases[fileId] ?: 1) - 1).coerceAtLeast(0)
            if (remaining == 0) playbackLeases.remove(fileId) else playbackLeases[fileId] = remaining
            shouldDeleteReleasedFileLocked(fileId)
        }
        if (delete) deleteLocalFile(fileId)
    }

    private fun deleteLocalFile(fileId: Int) {
        val tdClient = client ?: return
        sendOwned(tdClient, TdApi.CancelDownloadFile(fileId, false)) { }
        sendOwned(tdClient, TdApi.DeleteFile(fileId)) { result ->
            if (result is TdApi.Ok) synchronized(lock) { files.remove(fileId); activeRange.remove(fileId); lock.notifyAll() }
        }
    }

    internal fun releaseSavedFile(fileId: Int) {
        if (synchronized(lock) { shouldDeleteReleasedFileLocked(fileId) }) deleteLocalFile(fileId)
    }

    private fun requireOwnedTdlibFile(file: File) {
        val root = File(ownRoot(requireNotNull(appContext)), "files").canonicalFile
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(root.path + File.separator)) { "Telegram file path is outside the private cache" }
    }

    /** Called by the staged Media3 DataSource; it blocks only its loader thread and never buffers the whole file. */
    override fun readAt(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
        val rangeLock = rangeLocks.computeIfAbsent(fileId) { Any() }
        return synchronized(rangeLock) { readAtExclusive(fileId, offset, buffer, length, cancelled) }
    }

    private fun readAtExclusive(fileId: Int, offset: Long, buffer: ByteArray, length: Int, cancelled: () -> Boolean): Int {
        val tdClient = client ?: throw java.io.IOException("Telegram 봇 연결이 끊어졌어요.")
        require(offset >= 0L && length > 0) { "Invalid file range" }
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS)
        while (true) {
            if (cancelled()) throw java.io.InterruptedIOException("Telegram audio read cancelled")
            if (client !== tdClient) throw java.io.IOException("Telegram bot is disconnected")
            val file = getCurrentFile(fileId, tdClient)
            TdlibPrefixReader.read(file, File(ownRoot(requireNotNull(appContext)), "files"), offset, buffer, length)?.let { return it }
            if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("Telegram media timed out while buffering")
            val chunkStart = (offset / READ_CHUNK_BYTES) * READ_CHUNK_BYTES
            val range = chunkStart to READ_CHUNK_BYTES
            val shouldRequest = synchronized(lock) {
                val now = System.nanoTime()
                val previous = activeRange[fileId]
                if (previous != null && previous.offset == range.first && previous.limit == range.second && now - previous.sentAtNanos < TimeUnit.SECONDS.toNanos(2)) false
                else { activeRange[fileId] = RangeRequest(range.first, range.second, now); true }
            }
            val fullSaveOwnsFile = synchronized(lock) { fileId in saveLeases }
            if (shouldRequest && !fullSaveOwnsFile) {
                sendOwned(tdClient, TdApi.DownloadFile(fileId, 32, range.first, range.second, false)) { result ->
                    if (result is TdApi.File) synchronized(lock) { files[fileId] = result; lock.notifyAll() }
                    else if (result is TdApi.Error) synchronized(lock) { activeRange.remove(fileId); lock.notifyAll() }
                }
            }
            synchronized(lock) {
                if (!cancelled()) lock.wait(250L)
            }
        }
    }

    internal fun fileInfo(fileId: Int): TdApi.File? = synchronized(lock) { files[fileId] }

    private fun getCurrentFile(fileId: Int, tdClient: Client): TdApi.File {
        synchronized(lock) { files[fileId] }?.let { return it }
        val monitor = Object()
        var result: TdApi.Object? = null
        sendOwned(tdClient, TdApi.GetFile(fileId)) { value -> synchronized(monitor) { result = value; monitor.notifyAll() } }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        synchronized(monitor) {
            while (result == null && System.nanoTime() < until) monitor.wait(250L)
        }
        return (result as? TdApi.File)?.also { synchronized(lock) { files[fileId] = it } }
            ?: throw java.io.IOException("Telegram file is unavailable")
    }

    private fun requestCachedFileRefresh(context: Context, forceRefresh: Boolean = false) {
        synchronized(lock) { videos.values.toList() }.filter { it.remoteFileId.isNotBlank() }.forEach { video ->
            resolveVideoFile(context, video, reportFailure = false, forceRefresh = forceRefresh) { }
        }
    }

    /** Session file numbers are refreshed from the encrypted, persistent remote identifier. */
    private fun resolveVideoFile(
        context: Context,
        requested: BotVideo,
        reportFailure: Boolean = true,
        forceRefresh: Boolean = false,
        onResolved: (BotVideo) -> Unit,
    ) {
        val owner = client
        if (owner == null || !ready) {
            if (reportFailure) publish { copy(error = "봇 연결이 완료된 뒤 다시 들어 주세요.") }
            return
        }
        val current = synchronized(lock) { videos[requested.messageId] }
        if (current == null) {
            if (reportFailure) publish { copy(error = "이 영상은 현재 봇 받은 편지함에 없어요. 목록을 새로고침해 주세요.") }
            return
        }
        if (isMessageBlocked(current.chatId, current.messageId)) {
            if (reportFailure) publish { copy(error = "삭제 중인 영상은 열 수 없어요.") }
            return
        }
        if (current.chatId != pairedChatId || pairedUserId <= 0L) {
            if (reportFailure) publish { copy(error = "연결한 Telegram 계정의 비공개 대화 영상만 열 수 있어요.") }
            return
        }
        if (!forceRefresh && synchronized(lock) { current.messageId in restoredVideoMessages }) {
            onResolved(current)
            return
        }
        if (current.remoteFileId.isBlank()) {
            if (reportFailure) publish { copy(error = "이전 연결의 파일 번호는 다시 사용할 수 없어요. 영상 자체를 봇에 다시 전달해 주세요.") }
            return
        }
        sendOwned(owner, TdApi.GetRemoteFile(current.remoteFileId, null)) { result ->
            io.execute {
                if (client !== owner || !ready || current.chatId != pairedChatId || pairedUserId <= 0L) {
                    if (reportFailure) publish { copy(error = "봇 연결이 변경되어 영상을 열지 못했어요. 다시 시도해 주세요.") }
                    return@execute
                }
                val latest = synchronized(lock) {
                    videos[current.messageId]?.takeIf {
                        !isMessageBlockedLocked(BotMessageKey(it.chatId, it.messageId))
                    }
                }
                if (latest == null || latest.chatId != pairedChatId || latest.remoteFileId != current.remoteFileId) {
                    if (reportFailure) publish { copy(error = "영상 정보가 바뀌어 열지 못했어요. 목록을 새로고침해 주세요.") }
                    return@execute
                }
                val file = result as? TdApi.File
                if (file == null) {
                    logReceiveDiagnostic("file-restore result=${result.javaClass.simpleName} errorCode=${(result as? TdApi.Error)?.code ?: 0}")
                    val marked = synchronized(lock) {
                        videos[current.messageId]?.takeIf {
                            it.chatId == current.chatId && !isMessageBlockedLocked(BotMessageKey(it.chatId, it.messageId))
                        }?.copy(needsForward = true)?.also { videos[current.messageId] = it }
                    }
                    if (marked == null) return@execute
                    publish {
                        copy(videos = synchronized(lock) { BotRuntime.videos.values.toList() },
                            error = if (reportFailure) "영상 파일을 다시 찾지 못했어요. 영상 자체를 봇에 다시 전달해 주세요." else error)
                    }
                    return@execute
                }
                val restored = synchronized(lock) {
                    val live = videos[current.messageId]?.takeIf {
                        it.chatId == current.chatId && it.remoteFileId == current.remoteFileId &&
                            !isMessageBlockedLocked(BotMessageKey(it.chatId, it.messageId))
                    } ?: return@synchronized null
                    val value = live.copy(
                        fileId = file.id,
                        bytes = BotMediaClassifier.listedFileSize(file).takeIf { it > 0L } ?: live.bytes,
                        remoteFileId = file.remote?.id?.takeIf { it.isNotBlank() } ?: live.remoteFileId,
                        needsForward = false,
                    )
                    files[file.id] = file
                    videos[current.messageId] = value
                    restoredVideoMessages += current.messageId
                    lock.notifyAll()
                    value
                } ?: return@execute
                BotPrivateStore(context).videos = synchronized(lock) { videos.values.toList() }
                publish { copy(videos = synchronized(lock) { BotRuntime.videos.values.toList() }) }
                logReceiveDiagnostic("file-restore success")
                restoreThumbnailRemote(context, restored, owner)
                onResolved(restored)
            }
        }
    }

    private fun startThumbnailDownload(context: Context, video: BotVideo, thumbnail: TdApi.File) {
        if (thumbnail.id <= 0 || !ready || isMessageBlocked(video.chatId, video.messageId) ||
            BotMediaClassifier.listedFileSize(thumbnail) > BotVideoThumbnailData.MAX_BYTES) return
        val owner = client ?: return
        synchronized(lock) {
            files[thumbnail.id] = thumbnail
            if (!thumbnailDownloads.add(thumbnail.id)) return
        }
        if (thumbnail.local.isDownloadingCompleted && thumbnail.local.path.isNotBlank()) {
            synchronized(lock) { thumbnailDownloads.remove(thumbnail.id) }
            cacheCompletedThumbnail(context, video, thumbnail)
            return
        }
        sendOwned(owner, TdApi.DownloadFile(thumbnail.id, 1, 0L, BotVideoThumbnailData.MAX_BYTES.toLong(), false)) { result ->
            if (result is TdApi.Error) synchronized(lock) { thumbnailDownloads.remove(thumbnail.id) }
            if (result is TdApi.File && result.local.isDownloadingCompleted) {
                io.execute {
                    if (client === owner && !isMessageBlocked(video.chatId, video.messageId)) {
                        synchronized(lock) { thumbnailDownloads.remove(thumbnail.id); files[result.id] = result }
                        videosReferencingThumbnail(result.id).forEach { cacheCompletedThumbnail(context, it, result) }
                    }
                }
            }
        }
    }

    private fun videosReferencingThumbnail(fileId: Int): List<BotVideo> = synchronized(lock) {
        BotThumbnailPolicy.rowsForCompletedFile(videos.values.toList(), fileId)
    }

    private fun restoreThumbnailRemote(context: Context, video: BotVideo, owner: Client) {
        val current = synchronized(lock) { videos[video.messageId] } ?: return
        if (isMessageBlocked(current.chatId, current.messageId)) return
        if (BotVideoThumbnailData.hasRenderableCache(context, current.thumbnailPath)) return
        val remote = current.thumbnailRemoteFileId.takeIf(String::isNotBlank) ?: return
        sendOwned(owner, TdApi.GetRemoteFile(remote, null)) { result ->
            val thumb = result as? TdApi.File ?: return@sendOwned
            io.execute {
                if (client !== owner || !ready || current.messageId != video.messageId || current.chatId != pairedChatId) return@execute
                val updated = synchronized(lock) {
                    videos[video.messageId]?.takeIf { latest ->
                        val sameMedia = if (latest.remoteFileId.isNotBlank() && current.remoteFileId.isNotBlank()) {
                            latest.remoteFileId == current.remoteFileId
                        } else {
                            latest.fileId == current.fileId
                        }
                        !isMessageBlockedLocked(BotMessageKey(latest.chatId, latest.messageId)) &&
                            latest.chatId == current.chatId && latest.chatId == pairedChatId &&
                            latest.thumbnailRemoteFileId == remote && sameMedia
                    }?.let { latest ->
                        latest.copy(thumbnailFileId = thumb.id).also { videos[video.messageId] = it }
                    }
                }
                if (updated != null) {
                    BotPrivateStore(context).videos = synchronized(lock) { videos.values.toList() }
                    publish { copy(videos = synchronized(lock) { BotRuntime.videos.values.toList() }) }
                    startThumbnailDownload(context, updated, thumb)
                }
            }
        }
    }

    private fun cacheCompletedThumbnail(context: Context?, video: BotVideo, file: TdApi.File) {
        val app = context ?: return
        val owner = client ?: return
        if (!ready || file.id != video.thumbnailFileId || video.chatId != pairedChatId) return
        val current = synchronized(lock) { videos[video.messageId] } ?: return
        if (isMessageBlocked(current.chatId, current.messageId) ||
            current.thumbnailFileId != file.id || current.chatId != pairedChatId) return
        if (BotVideoThumbnailData.hasRenderableCache(app, current.thumbnailPath)) return
        val source = File(file.local.path)
        if (!BotThumbnailPathPolicy.isOwnedSource(File(app.filesDir, ROOT_DIR), source)) {
            logThumbnailCacheFailure(app, "source-path", "PATH_NOT_OWNED")
            return
        }
        if (!source.isFile || source.length() !in 1..BotVideoThumbnailData.MAX_BYTES.toLong()) {
            logThumbnailCacheFailure(app, "source-file", "MISSING_OR_OUT_OF_RANGE")
            return
        }
        val path = BotVideoThumbnailData.cache(app, video.messageId, source)
        if (path == null) {
            logThumbnailCacheFailure(app, "cache", "INVALID_IMAGE_OR_CACHE_IO")
            return
        }
        if (client !== owner || !ready) {
            deleteThumbnailCache(path)
            logThumbnailCacheFailure(app, "cache-publish", "CONNECTION_CHANGED")
            return
        }
        val update = synchronized(lock) {
            val latest = videos[video.messageId]
            if (latest == null || isMessageBlockedLocked(BotMessageKey(latest.chatId, latest.messageId)) ||
                latest.thumbnailFileId != file.id || latest.chatId != pairedChatId) null
            else latest.copy(thumbnailPath = path).also { videos[video.messageId] = it } to latest.thumbnailPath
        }
        if (update == null) {
            deleteThumbnailCache(path)
            logThumbnailCacheFailure(app, "cache-publish", "STALE_ROW")
            return
        }
        val previousPath = update.second
        BotPrivateStore(app).videos = synchronized(lock) { videos.values.toList() }
        publish { copy(videos = synchronized(lock) { BotRuntime.videos.values.toList() }) }
        previousPath?.takeIf { it != path }?.let(::deleteThumbnailCache)
    }

    private fun logThumbnailCacheFailure(context: Context, stage: String, errorKind: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.i(THUMBNAIL_LOG_TAG, "stage=$stage errorKind=$errorKind")
        }
    }

    private fun deleteThumbnailCache(path: String) {
        runCatching {
            val base = File(requireNotNull(appContext).filesDir, "$ROOT_DIR/thumbnails").canonicalFile
            val target = File(path).canonicalFile
            require(target.path.startsWith(base.path + File.separator))
            target.delete()
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext
        appContext = app
        cancelInboxRefresh()
        runCatching { ExternalBotStreams.cancelAll(app) }
        runCatching { app.startService(Intent(app, StreamingAudioService::class.java).setAction(ACTION_STOP_TDLIB)) }
        runCatching { app.startService(Intent(app, ConversionService::class.java).setAction("cancel_bot")) }
        io.execute {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            synchronized(lock) {
                while ((saveLeases.isNotEmpty() || playbackLeases.isNotEmpty()) && System.nanoTime() < deadline) lock.wait(250)
                if (saveLeases.isNotEmpty() || playbackLeases.isNotEmpty()) {
                    publish { copy(error = "재생과 저장을 중지한 뒤 연결 초기화를 다시 눌러 주세요.") }
                    return@execute
                }
            }
            closeClient(app, clearAccount = true)
        }
    }

    private fun closeClient(context: Context, clearAccount: Boolean): Boolean {
        cancelInboxRefresh()
        ready = false
        authWatchdog?.cancel(false)
        authWatchdog = null
        closingClearAccount = closingClearAccount || clearAccount
        if (closingClient == null) {
            val old = client
            if (old == null) {
                if (closingClearAccount) clearAccountData(context)
                closingClearAccount = false
                return true
            }
            closingClient = old
            closingGeneration = clientGeneration
            closeLatches[closingGeneration] = java.util.concurrent.CountDownLatch(1)
            ++clientGeneration // Ignore all late updates except the old client's Closed acknowledgement.
            old.send(TdApi.Close()) { }
        }
        val closed = requireNotNull(closeLatches[closingGeneration])
        if (!closed.await(20, TimeUnit.SECONDS)) {
            // Keep the client, generation and latch: neither reconnect nor reset may delete a live database.
            publish { copy(busy = false, connected = false, status = "연결 종료 대기 중", error = "Telegram 연결 종료를 기다리고 있어요. 잠시 후 연결 초기화를 다시 눌러 주세요.") }
            return false
        }
        finishClosing(context)
        return true
    }

    private fun finishClosing(context: Context) {
        val latch = closeLatches[closingGeneration] ?: return
        if (latch.count != 0L) return
        client = null
        closingClient = null
        closeLatches.remove(closingGeneration)
        closingGeneration = 0L
        if (closingClearAccount) clearAccountData(context)
        closingClearAccount = false
        databaseKey?.fill(0); databaseKey = null
    }

    private fun clearAccountData(context: Context) {
        BotCredentialsStore(context).clear()
        BotPrivateStore(context).clear()
        deleteOwnedRoot(context)
        synchronized(lock) {
            inboxRefreshInFlight = false
            inboxRefreshRequestId++
            lastInboxRefreshStartedMs = 0L
            inboxRefreshTimeout?.cancel(false)
            inboxRefreshTimeout = null
            videos.clear(); restoredVideoMessages.clear(); tombstonedMessages.clear(); pendingDeletions.clear()
            protectedSharedFiles.clear(); deleteAfterLease.clear(); files.clear(); chatTypes.clear(); activeRange.clear()
            playbackLeases.clear(); saveLeases.clear(); thumbnailDownloads.clear(); transferErrors.clear()
            pendingNonce = null; pairedUserId = 0L; pairedChatId = 0L; credentials = null
            botUserId = 0L; botUsername = null; botDisplayName = null
        }
        publish { BotUiState(initialStateLoaded = true, status = "연결 해제됨") }
    }

    private fun publish(transform: BotUiState.() -> BotUiState) {
        synchronized(lock) { mutableState.value = mutableState.value.transform() }
    }

    private fun sendOwned(owner: Client, request: TdApi.Function<*>, complete: (TdApi.Object) -> Unit) {
        val generation = clientGeneration
        owner.send(request) { value ->
            if (generation == clientGeneration && client === owner) complete(value)
        }
    }

    private fun ownRoot(context: Context) = File(context.filesDir, ROOT_DIR)

    private fun deleteOwnedRoot(context: Context) {
        val root = ownRoot(context).canonicalFile
        require(root.parentFile == context.filesDir.canonicalFile) { "Invalid Telegram bot cache path" }
        root.deleteRecursively()
    }

    internal const val ACTION_PLAY_TDLIB = "io.github.polarfox0317.sikboonge.action.PLAY_TDLIB"
    internal const val ACTION_STOP_TDLIB = "io.github.polarfox0317.sikboonge.action.STOP_TDLIB"
    internal const val EXTRA_TDLIB_FILE_ID = "tdlib_file_id"
    internal const val EXTRA_TDLIB_NAME = "tdlib_file_name"
    internal const val EXTRA_TDLIB_SIZE = "tdlib_file_size"
    private const val MAX_LISTED_VIDEOS = 50
    private const val PAIRING_NONCE_TTL_MS = 5L * 60L * 1000L
    private const val REFRESH_COOLDOWN_MS = 1_200L
    private const val REFRESH_TIMEOUT_MS = 45_000L
}
