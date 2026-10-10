package io.github.polarfox0317.sikboonge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object BotStreamTestHooks {
    @Volatile var accessOverride: TdlibFileAccess? = null
}

/** Registry for short-lived, one-purpose grants to audio-only spools. */
internal object BotStreamSessions {
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(context: Context, video: BotVideo, access: TdlibFileAccess, onError: (String) -> Unit = {}): Uri {
        require(video.fileId > 0) { "영상 파일 정보를 확인할 수 없어요." }
        val bridge = ExternalAudioInputBridge.forBot(video.fileId, video.bytes, access)
        return createSession(
            context,
            video.name,
            bridge,
            onError,
        )
    }

    private fun createSession(
        context: Context,
        title: String,
        bridge: ExternalAudioInputBridge,
        onError: (String) -> Unit,
    ): Uri {
        val appContext = context.applicationContext
        val session = Session(appContext, title, bridge, bridge.inputUrl, onError)
        check(sessions.putIfAbsent(session.token, session) == null)
        session.scheduleInitialExpiry()
        session.start()
        return session.uri
    }

    fun find(uri: Uri): Session {
        val token = tokenFrom(uri) ?: throw SecurityException("Invalid external audio URI")
        val session = sessions[token] ?: throw SecurityException("External audio URI expired")
        if (uri.authority != "${session.context.packageName}${BotStreamProvider.AUTHORITY_SUFFIX}") {
            throw SecurityException("Invalid external audio URI")
        }
        session.assertUsable()
        return session
    }

    fun cancel(context: Context, token: String) {
        sessions[token]?.let(::expire)
        stopRelayIfIdle(context.applicationContext)
    }

    fun cancelUri(context: Context, uri: Uri) {
        if (uri.authority != "${context.packageName}${BotStreamProvider.AUTHORITY_SUFFIX}") return
        tokenFrom(uri)?.let { cancel(context, it) }
    }

    fun cancelAll(context: Context) {
        sessions.values.toList().forEach { session -> runCatching { expire(session) } }
        context.stopService(Intent(context, BotStreamRelayService::class.java))
    }

    fun isIdle(): Boolean = sessions.isEmpty()
    fun isActiveToken(token: String): Boolean = token.matches(TOKEN_REGEX) && sessions.containsKey(token)

    private fun tokenFrom(uri: Uri): String? {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return null
        if (uri.query != null || uri.fragment != null || uri.userInfo != null || uri.port != -1) return null
        val path = uri.pathSegments
        if (path.size != 1 || uri.encodedPath != "/${path.single()}") return null
        return path.single().takeIf { it.matches(TOKEN_REGEX) }
    }

    private fun expire(session: Session) {
        if (!sessions.remove(session.token, session)) return
        session.close()
        runCatching { session.context.revokeUriPermission(session.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        stopRelayIfIdle(session.context)
    }

    private fun stopRelayIfIdle(context: Context) {
        if (sessions.isEmpty()) context.stopService(Intent(context, BotStreamRelayService::class.java))
    }

    private val TOKEN_REGEX = Regex("[A-Za-z0-9_-]{43}")
    private const val INITIAL_OPEN_TIMEOUT_MS = 90_000L
    private const val REOPEN_GRACE_MS = 30_000L
    private const val MAX_SESSION_LIFETIME_MS = 5L * 60L * 60L * 1000L

    internal class Session(
        val context: Context,
        title: String,
        private val sourceBridge: ExternalAudioInputBridge,
        input: String,
        private val onError: (String) -> Unit,
    ) {
        val token: String = newToken()
        val uri: Uri = Uri.Builder().scheme("content").authority("${context.packageName}.botstream").appendPath(token).build()
        private val lock = Any()
        private val deadline = SystemClock.elapsedRealtime() + MAX_SESSION_LIFETIME_MS
        private val readers = linkedSetOf<ReadLease>()
        private val relays = linkedSetOf<ExternalAudioPipeRelay>()
        private var closed = false
        private val expiry = Runnable { expire(this) }
        private val job = ExternalAudioJob(context, input, sourceBridge, token, title, ::jobStateChanged)

        fun start() = job.start()

        fun assertUsable() = synchronized(lock) {
            if (closed || SystemClock.elapsedRealtime() >= deadline) throw SecurityException("External audio URI expired")
        }

        /** The pipe has no seekable size until FFmpeg finalizes the audio container. */
        fun size(): Long? = job.finalSize
        fun mime(): String = job.currentMime
        fun displayName(): String = job.currentDisplayName

        fun openPipe(): ParcelFileDescriptor {
            val reader = openReader()
            var descriptors: Array<ParcelFileDescriptor>? = null
            var relay: ExternalAudioPipeRelay? = null
            try {
                descriptors = ParcelFileDescriptor.createReliablePipe()
                relay = ExternalAudioPipeRelay(reader, descriptors[0], descriptors[1])
                synchronized(lock) {
                    if (closed || SystemClock.elapsedRealtime() >= deadline) throw IOException("External audio URI expired")
                    relays += relay
                }
                relay.start()
                return descriptors[0]
            } catch (error: Exception) {
                if (relay != null) runCatching { relay.close() }
                else {
                    descriptors?.forEach { runCatching { it.close() } }
                    reader.close()
                }
                throw error
            }
        }

        fun openReader(): ReadLease {
            synchronized(lock) {
                if (closed || SystemClock.elapsedRealtime() >= deadline) throw IOException("External audio URI expired")
                val reader = ReadLease(this, job)
                readers += reader
                scheduleLocked(deadline - SystemClock.elapsedRealtime())
                return reader
            }
        }

        internal fun isClosed(): Boolean = synchronized(lock) { closed || SystemClock.elapsedRealtime() >= deadline }

        internal fun readerClosed(reader: ReadLease) {
            synchronized(lock) {
                readers.remove(reader)
                scheduleWhenIdleLocked()
            }
        }

        private fun jobStateChanged(state: ExternalAudioJob.State, message: String?) {
            if (state != ExternalAudioJob.State.FAILED || message == null || isClosed()) return
            runCatching { onError(message) }
            handler.post { expire(this) }
        }

        fun close() {
            val openReaders: List<ReadLease>
            val openRelays: List<ExternalAudioPipeRelay>
            synchronized(lock) {
                if (closed) return
                closed = true
                handler.removeCallbacks(expiry)
                openReaders = readers.toList()
                openRelays = relays.toList()
                readers.clear()
                relays.clear()
            }
            openRelays.forEach { relay -> runCatching { relay.close() } }
            openReaders.forEach { reader -> runCatching { reader.close() } }
            runCatching { job.close() }
            runCatching { sourceBridge.close() }
        }

        private fun scheduleWhenIdleLocked() {
            if (!closed && readers.isEmpty()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) handler.post(expiry)
                else scheduleLocked(minOf(REOPEN_GRACE_MS, remaining))
            }
        }

        internal fun scheduleInitialExpiry() = schedule(INITIAL_OPEN_TIMEOUT_MS)
        private fun schedule(delayMs: Long) = synchronized(lock) { scheduleLocked(delayMs) }
        private fun scheduleLocked(delayMs: Long) {
            handler.removeCallbacks(expiry)
            handler.postDelayed(expiry, delayMs.coerceAtLeast(0L))
        }

        private fun newToken(): String {
            val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
    }

    internal class ReadLease(private val session: Session, private val job: ExternalAudioJob) {
        private val closed = AtomicBoolean(false)
        fun isCancelled(): Boolean = closed.get() || session.isClosed() || job.isCancelled
        fun waitForInitialBytes(): Long {
            if (isCancelled()) throw IOException("External audio read cancelled")
            return job.waitForInitialBytes()
        }
        fun size(): Long? = job.finalSize
        fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (isCancelled()) throw IOException("External audio read cancelled")
            return job.readAt(offset, buffer, length, ::isCancelled)
        }
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            session.readerClosed(this)
        }
    }
}

