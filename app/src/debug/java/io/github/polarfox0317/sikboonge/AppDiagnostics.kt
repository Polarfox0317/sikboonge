package io.github.polarfox0317.sikboonge

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal object AppDiagnostics {
    private const val MAX_EVENTS = 160
    private const val MAX_EVENT_CHARS = 360
    private val lock = Any()
    private val events = ArrayDeque<String>()

    fun event(category: String, message: String) {
        val safeCategory = sanitizeCategory(category)
        val safeMessage = sanitize(message).take(MAX_EVENT_CHARS)
        addEvent("event", "$safeCategory: $safeMessage")
    }

    fun failure(category: String, error: Throwable) {
        val safeCategory = sanitizeCategory(category)
        val type = error.javaClass.simpleName.take(80).ifBlank { "Exception" }
        val detail = error.message?.let(::sanitize)?.take(MAX_EVENT_CHARS).orEmpty()
        addEvent("failure", "$safeCategory: $type${if (detail.isBlank()) "" else ": $detail"}")
    }

    internal fun createReport(context: Context): String {
        val playback = StreamPlayback.state.value
        val conversion = AppStore.state.value
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode else packageInfo?.versionCode?.toLong()
        val appVersion = "${packageInfo?.versionName.orEmpty().ifBlank { "unknown" }} ($versionCode)"
        val eventSnapshot = synchronized(lock) { events.toList() }
        val logcat = collectOwnProcessLogcat()
        val output = buildString {
            appendLine("Video to Sound diagnostic report")
            appendLine("Created UTC: ${utcNow()}")
            appendLine("App version: $appVersion")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${safeDeviceName(Build.MANUFACTURER)} ${safeDeviceName(Build.MODEL)}")
            appendLine()
            appendLine("Playback")
            appendLine("Source kind: ${playbackSourceKind(playback)}")
            appendLine("Resolving: ${playback.resolving}")
            appendLine("Ready: ${playback.ready}")
            appendLine("Playing: ${playback.playing}")
            appendLine("Ended: ${playback.ended}")
            appendLine("Position ms: ${playback.positionMs.coerceAtLeast(0L)}")
            appendLine("Duration ms: ${playback.durationMs.coerceAtLeast(0L)}")
            appendLine("Source bytes: ${playback.sourceBytes.coerceAtLeast(0L)}")
            appendLine("Has playback error: ${playback.error != null}")
            appendLine()
            appendLine("Conversion")
            appendLine("Running: ${conversion.running}")
            appendLine("Stage: ${sanitize(conversion.stage).take(180).ifBlank { "idle" }}")
            appendLine("Progress percent: ${conversion.progress?.takeIf { it.isFinite() }?.let { (it.coerceIn(0f, 1f) * 100f).toInt() } ?: "unknown"}")
            appendLine("Received bytes: ${conversion.receivedBytes?.coerceAtLeast(0L) ?: "unknown"}")
            appendLine("Total bytes: ${conversion.totalBytes?.coerceAtLeast(0L) ?: "unknown"}")
            appendLine("Bytes per second: ${conversion.bytesPerSecond?.coerceAtLeast(0L) ?: "unknown"}")
            appendLine("Has conversion error: ${conversion.error != null}")
            appendLine("Has saved result: ${conversion.result != null}")
            appendLine()
            appendLine("Structured events (newest last)")
            if (eventSnapshot.isEmpty()) appendLine("(none recorded)")
            else eventSnapshot.forEach { appendLine(it) }
            appendLine()
            appendLine("App-process warning/error logcat (allowlisted tags only)")
            if (logcat.isEmpty()) appendLine("(unavailable, empty, or no allowlisted entries)")
            else logcat.forEach { appendLine(it) }
        }
        return output.take(MAX_REPORT_CHARS)
    }

    private fun addEvent(kind: String, entry: String) {
        val timestamp = utcNow()
        synchronized(lock) {
            while (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast("$timestamp [$kind] $entry")
        }
    }

    private fun collectOwnProcessLogcat(): List<String> {
        val process = runCatching {
            ProcessBuilder(
                "logcat", "-d", "-t", MAX_LOGCAT_LINES.toString(),
                "--pid=${Process.myPid()}", "-v", "threadtime",
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return emptyList()

        val collected = mutableListOf<String>()
        val collector = Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (synchronized(collected) { collected.size < MAX_LOGCAT_LINES }) {
                        val raw = reader.readLine() ?: break
                        parseSafeLogLine(raw.take(MAX_RAW_LOG_LINE_CHARS))?.let { safe ->
                            synchronized(collected) {
                                if (collected.size < MAX_LOGCAT_LINES) collected += safe
                            }
                        }
                    }
                }
            }
        }, "diagnostic-logcat-reader").apply { isDaemon = true }
        collector.start()
        try {
            if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            collector.join(LOGCAT_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        } finally {
            process.destroy()
            runCatching { process.inputStream.close() }
        }
        return synchronized(collected) { collected.toList() }
    }

    private fun parseSafeLogLine(line: String): String? {
        val match = LOG_LINE.matchEntire(line.trim()) ?: return null
        val priority = match.groupValues[1]
        val tag = match.groupValues[2].trim().take(80)
        if (priority !in setOf("W", "E", "F") || tag !in SAFE_LOG_TAGS) return null
        return "[$priority/$tag] ${sanitize(match.groupValues[3]).take(MAX_SAFE_LOG_CHARS)}"
    }

    private fun playbackSourceKind(state: StreamingState): String = when {
        state.source == PlaybackSource.LOCAL_AUDIO -> "local audio"
        state.source == PlaybackSource.BOT -> "bot video"
        else -> "none"
    }

    private fun sanitizeCategory(value: String): String =
        value.take(64).takeIf { CATEGORY.matches(it) } ?: "event"

    private fun sanitize(value: String): String {
        var safe = value.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_RAW_MESSAGE_CHARS)
        safe = URL_PATTERN.replace(safe, "[url]")
        safe = TOKEN_PATTERN.replace(safe, "[token]")
        safe = SECRET_ASSIGNMENT.replace(safe) { "${it.groupValues[1]}=[redacted]" }
        safe = AUTHORIZATION_PATTERN.replace(safe, "Authorization=[redacted]")
        safe = SENSITIVE_FIELD.replace(safe) { "${it.groupValues[1]}=[redacted]" }
        safe = PATH_PATTERN.replace(safe, "[path]")
        safe = MEDIA_FILENAME_PATTERN.replace(safe, "[media-file]")
        return safe.trim().ifBlank { "[redacted]" }
    }

    private fun safeDeviceName(value: String): String = sanitize(value).take(80)

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private const val MAX_LOGCAT_LINES = 180
    private const val MAX_RAW_LOG_LINE_CHARS = 2_400
    private const val MAX_SAFE_LOG_CHARS = 320
    private const val MAX_RAW_MESSAGE_CHARS = 1_000
    private const val MAX_REPORT_CHARS = 80_000
    private const val LOGCAT_TIMEOUT_MS = 2_000L
    private const val LOGCAT_JOIN_MS = 300L
    private val CATEGORY = Regex("[A-Za-z0-9_.-]{1,64}")
    private val LOG_LINE = Regex("\\d{2}-\\d{2}\\s+\\S+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)")
    private val URL_PATTERN = Regex("(?i)\\b(?:https?|tg|tdlib)://[^\\s\\\"'<>]+")
    private val TOKEN_PATTERN = Regex("\\b\\d{5,}:[A-Za-z0-9_-]{20,}\\b")
    private val SECRET_ASSIGNMENT = Regex("(?i)\\b(bot[_ -]?token|api[_ -]?(?:hash|id)|token|secret|password|auth(?:orization)?)\\s*[:=]\\s*[^\\s,;]+")
    private val AUTHORIZATION_PATTERN = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/-]+=*")
    private val SENSITIVE_FIELD = Regex("(?i)\\b(title|caption|chat(?:name)?|filename|file[_ -]?name|path|link|url|message)\\s*[:=]\\s*[^,;]+")
    private val PATH_PATTERN = Regex("(?<![A-Za-z0-9])(?:[A-Za-z]:\\\\|/(?:[^\\s/:]+/)+)[^\\s,;\\\"'<>]*")
    private val MEDIA_FILENAME_PATTERN = Regex("(?i)(?<![A-Za-z0-9])[^\\s/\\\\,;\\\"']{1,100}\\.(?:mp4|m4v|mkv|mov|webm|avi|m4a|mp3|wav|flac|aac|ogg)(?![A-Za-z0-9])")
    private val SAFE_LOG_TAGS = setOf(
        "AppDiagnostics", "AudioEngine", "ConversionService", "ExternalAudioJob",
        "ExternalPlayback", "PlayerArtwork", "StreamPlayback",
        "StreamingAudioService",
    )
}

@Composable
internal fun rememberDiagnosticLogExporter(onFeedback: (String, Boolean) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { destination ->
        if (destination != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val report = AppDiagnostics.createReport(context.applicationContext)
                        val output = context.contentResolver.openOutputStream(destination, "w")
                            ?: throw IllegalStateException("Output stream unavailable")
                        output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(report) }
                    }.isSuccess
                }
                if (saved) onFeedback("진단 로그를 선택한 위치에 저장했어요.", false)
                else onFeedback("진단 로그를 저장하지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요.", true)
            }
        }
    }
    return {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        launcher.launch("video-audio-diagnostics-$stamp.txt")
    }
}
