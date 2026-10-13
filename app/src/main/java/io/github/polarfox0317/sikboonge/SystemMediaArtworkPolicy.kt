package io.github.polarfox0317.sikboonge

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Metadata visibility and async-completion rules for system-only artwork. */
internal object SystemMediaArtworkPolicy {
    fun metadata(
        source: MediaMetadata,
        enabled: Boolean,
        importedArtwork: ByteArray?,
    ): MediaMetadata {
        val builder = source.buildUpon()
        if (!enabled || importedArtwork == null) {
            return builder
                .setArtworkData(null, null)
                .setArtworkUri(null)
                .build()
        }
        return builder
            .setArtworkData(importedArtwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setArtworkUri(null)
            .build()
    }

    fun mediaItem(item: MediaItem, enabled: Boolean, importedArtwork: ByteArray?): MediaItem {
        val metadata = metadata(item.mediaMetadata, enabled, importedArtwork)
        return item.buildUpon()
            .setMediaMetadata(metadata)
            .build()
    }

    fun isCurrentRequest(
        enabled: Boolean,
        requestGeneration: Long,
        activeGeneration: Long,
        requestSource: String,
        activeSource: String,
    ): Boolean = enabled && requestGeneration == activeGeneration && requestSource == activeSource
}
