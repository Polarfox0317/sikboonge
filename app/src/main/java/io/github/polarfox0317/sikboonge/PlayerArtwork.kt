package io.github.polarfox0317.sikboonge

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.polarfox0317.sikboonge.ui.state.SelectedVideoThumbnail
import java.io.File

/** Displays cached artwork, with an audio icon as the fallback. */
@Composable
internal fun PlayerArtwork(
    artworkPath: String?,
    artworkBase64: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, artworkPath, artworkBase64) {
        value = null
        value = withContext(Dispatchers.IO) {
            loadArtwork(context, artworkPath, artworkBase64)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = bitmap
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = "미디어 미리보기",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_media_audio),
                contentDescription = "오디오",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun loadArtwork(
    context: Context,
    path: String?,
    base64: String,
): Bitmap? {
    return runCatching {
        val library = path?.takeIf { AudioArtworkStore.isOwnedArtworkPath(context.filesDir, it) }
            ?.let { BotVideoThumbnailData.decodeSampled(File(it).readBytes()) }
        library ?: BotVideoThumbnailData.decodeFile(context, path)?.let(BotVideoThumbnailData::decodeSampled)
            ?: SelectedVideoThumbnail.decodeFile(context, path)?.let(BotVideoThumbnailData::decodeSampled)
            ?: BotVideoThumbnailData.decodeMini(base64)?.let(BotVideoThumbnailData::decodeSampled)
    }.getOrNull()
}
