package io.github.polarfox0317.sikboonge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Opens an audio-only payload in a compatible installed player. */
object ExternalPlayback {
    /** Separates the original Telegram media's audio track before exposing its bytes. */
    fun openBotVideo(context: Context, resolvedVideo: BotVideo, onError: (String) -> Unit) {
        val appContext = context.applicationContext
        val uri = try {
            require(resolvedVideo.fileId > 0) { "영상 파일 정보를 확인할 수 없어요." }
            BotStreamSessions.create(appContext, resolvedVideo, BotStreamTestHooks.accessOverride ?: BotRuntime, onError)
        } catch (_: Exception) {
            onError("영상 파일 정보를 확인할 수 없어요.")
            return
        }
        try {
            if (!ExternalPlaybackTestHooks.skipRelayServiceForTests) BotStreamRelayService.start(appContext, uri.lastPathSegment.orEmpty())
            launchChooser(appContext, uri, "audio/*", ExternalAudioPolicy.displayName(resolvedVideo.name, null))
        } catch (_: Exception) {
            BotStreamSessions.cancel(appContext, uri.lastPathSegment.orEmpty())
            onError("외부 플레이어를 열지 못했어요. 다시 시도해 주세요.")
        }
    }

    /** Opens an already saved audio file through the existing private FileProvider. */
    fun openAudio(context: Context, file: File, mime: String, onError: (String) -> Unit) {
        if (!file.isFile || !file.canRead()) {
            onError("저장된 오디오 파일을 찾을 수 없어요.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            launchChooser(context, uri, normalizeAudioMime(mime), safeDisplayName(file.name))
        } catch (_: Exception) {
            onError("외부 플레이어를 열지 못했어요. 다시 시도해 주세요.")
        }
    }

    private fun launchChooser(context: Context, uri: Uri, mime: String, title: String) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        view.clipData = ClipData.newRawUri(title, uri)
        ExternalPlaybackTestHooks.explicitPackageForTests?.let(view::setPackage)
        if (ExternalPlaybackTestHooks.interceptViewForTests?.invoke(view) == true) return
        if (ExternalPlaybackTestHooks.explicitPackageForTests != null) {
            context.startActivity(view)
            return
        }
        context.startActivity(Intent.createChooser(view, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal object ExternalPlaybackTestHooks {
    @Volatile var interceptViewForTests: ((Intent) -> Boolean)? = null
    @Volatile var explicitPackageForTests: String? = null
    @Volatile var skipRelayServiceForTests = false
}

/** Stable teardown API for Telegram disconnect/reset and optional per-session cancellation. */
object ExternalBotStreams {
    fun cancelAll(context: Context) = BotStreamSessions.cancelAll(context.applicationContext)
    fun cancel(context: Context, uri: Uri) = BotStreamSessions.cancelUri(context.applicationContext, uri)
}

internal fun normalizeAudioMime(value: String): String {
    val mime = value.substringBefore(';').trim().lowercase()
    return if (mime.matches(Regex("audio/[a-z0-9][a-z0-9.+-]*"))) mime else "audio/*"
}

internal fun safeDisplayName(value: String): String {
    val leaf = value.replace('\\', '/').substringAfterLast('/').filterNot { Character.isISOControl(it) }
        .trim().take(160)
    return leaf.ifBlank { "Telegram media" }
}
