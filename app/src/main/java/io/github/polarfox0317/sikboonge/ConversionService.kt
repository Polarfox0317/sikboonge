package io.github.polarfox0317.sikboonge

import android.app.*
import android.content.*
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
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
    val tags: List<String> = emptyList(),
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
    val sourceBotFileId: Int? = null,
    val paused: Boolean = false,
    val canPause: Boolean = false,
    val pausing: Boolean = false,
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
    val botSaveCompletions = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val cancelledDownloadId = MutableStateFlow<Long?>(null)
    @Synchronized fun load(context: Context) {
        val file = File(context.filesDir, "history.json")
        history.value = try {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i -> json.getJSONObject(i).let { row ->
                val artworkPath = row.optString("artworkPath", "").takeIf(String::isNotBlank)
                    ?.takeIf { AudioArtworkStore.isOwnedArtworkPath(context.filesDir, it) }
                AudioResult(row.getString("path"), row.getString("name"), row.getString("mime"), row.getLong("bytes"), row.getLong("date"), artworkPath,
                    TagPolicy.normalizeTags(row.optJSONArray("tags")?.let { tags -> (0 until tags.length()).mapNotNull { tags.optString(it, null) } }.orEmpty()))
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
    @Synchronized internal fun setDownloadPaused(id: Long, paused: Boolean, pausing: Boolean = false) {
        downloads.value = downloads.value.map { item ->
            if (item.id != id) item else item.copy(
                paused = paused, pausing = pausing,
                stage = if (pausing) "일시 중지 중" else if (paused) "일시 중지됨" else "대기 중",
                bytesPerSecond = null, remainingSeconds = null,
            )
        }
    }
    @Synchronized internal fun setDownloadSource(id: Long, sourceBotFileId: Int) {
        downloads.value = downloads.value.map { item ->
            if (item.id == id) item.copy(sourceBotFileId = sourceBotFileId) else item
        }
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
        if (renamed.path == source.path && renamed.name == result.name) return indexedResult
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
    @Synchronized fun setTags(context: Context, path: String, tags: List<String>): AudioResult? {
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        val index = history.value.indexOfFirst { runCatching { File(it.path).canonicalPath == canonical }.getOrDefault(false) }
        if (index < 0) return null
        val updatedResult = history.value[index].copy(tags = TagPolicy.normalizeTags(tags))
        val updated = history.value.toMutableList().also { it[index] = updatedResult }
        persist(context, updated)
        state.value.result?.takeIf { runCatching { File(it.path).canonicalPath == canonical }.getOrDefault(false) }
            ?.let { state.value = state.value.copy(result = updatedResult) }
        return updatedResult
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
        val json = JSONArray().apply { updated.forEach { result -> put(JSONObject().put("path", result.path).put("name", result.name).put("mime", result.mime).put("bytes", result.bytes).put("date", result.date).put("artworkPath", result.artworkPath).put("tags", JSONArray(TagPolicy.normalizeTags(result.tags)))) } }
        val temp = File(context.filesDir, "history.tmp").apply { writeText(json.toString()) }
        check(temp.renameTo(File(context.filesDir, "history.json"))) { "저장 목록을 기록할 수 없어요." }
        history.value = updated
    }
}

class ConversionService : ConversionServiceBackend() {
    companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val EXTRA_DOWNLOAD_ID = "library_download_id"
        internal const val EXTRA_DOWNLOAD_DATE = "library_download_date"
        internal const val EXTRA_SOURCE_BOT_CHAT_ID = "source_bot_chat_id"
        internal const val EXTRA_SOURCE_BOT_MESSAGE_ID = "source_bot_message_id"
        internal const val EXTRA_SOURCE_BOT_GENERATION = "source_bot_generation"
        internal const val EXTRA_SOURCE_BOT_TAGS = "source_bot_tags"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        const val ACTION_PAUSE_DOWNLOAD = "pause_download"
        const val ACTION_RESUME_DOWNLOAD = "resume_download"
        const val EXTRA_DEBUG_SOURCE_URI = "debug_local_source_uri"
        const val EXTRA_DEBUG_SOURCE_KEY = "debug_source_key"
    }
}
