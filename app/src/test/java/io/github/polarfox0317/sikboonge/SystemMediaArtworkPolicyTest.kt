package io.github.polarfox0317.sikboonge

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMediaArtworkPolicyTest {
    @Test
    fun disabledMetadataClearsArtworkAndPreservesOtherMetadata() {
        val source = MediaMetadata.Builder()
            .setTitle("Imported title")
            .setArtist("Imported artist")
            .setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        val exposed = SystemMediaArtworkPolicy.metadata(source, enabled = false, importedArtwork = null)

        assertEquals("Imported title", exposed.title)
        assertEquals("Imported artist", exposed.artist)
        assertNull(exposed.artworkData)
        assertNull(exposed.artworkUri)

        val item = MediaItem.Builder()
            .setMediaId("bot:22")
            .setMediaMetadata(source)
            .build()
        val exposedItem = SystemMediaArtworkPolicy.mediaItem(item, enabled = false, importedArtwork = null)
        assertEquals(item.mediaId, exposedItem.mediaId)
        assertEquals("Imported title", exposedItem.mediaMetadata.title)
        assertNull(exposedItem.mediaMetadata.artworkData)
        assertNull(exposedItem.mediaMetadata.artworkUri)
    }

    @Test
    fun enabledImportedArtworkUsesCompressedBytesAndPreservesMetadata() {
        val artwork = byteArrayOf(7, 8, 9)
        val exposed = SystemMediaArtworkPolicy.metadata(
            source = MediaMetadata.Builder().setTitle("Audio title").build(),
            enabled = true,
            importedArtwork = artwork,
        )
        val item = MediaItem.Builder()
            .setMediaId("local:/owned/audio.m4a")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Audio title").build())
            .build()
        val exposedItem = SystemMediaArtworkPolicy.mediaItem(item, enabled = true, importedArtwork = artwork)

        assertEquals("Audio title", exposed.title)
        assertArrayEquals(artwork, exposed.artworkData)
        assertNull(exposed.artworkUri)
        assertEquals(MediaMetadata.PICTURE_TYPE_FRONT_COVER, exposed.artworkDataType)
        assertEquals(item.mediaId, exposedItem.mediaId)
        assertEquals("Audio title", exposedItem.mediaMetadata.title)
        assertArrayEquals(artwork, exposedItem.mediaMetadata.artworkData)
        assertNull(exposedItem.mediaMetadata.artworkUri)
    }

    @Test
    fun enabledWithoutImportedArtworkSuppressesEmbeddedArtwork() {
        val source = MediaMetadata.Builder()
            .setTitle("Audio title")
            .setArtworkData(byteArrayOf(4, 5, 6), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        val exposed = SystemMediaArtworkPolicy.metadata(source, enabled = true, importedArtwork = null)

        assertEquals("Audio title", exposed.title)
        assertNull(exposed.artworkData)
        assertNull(exposed.artworkUri)
    }

    @Test
    fun lateArtworkCompletionIsRejectedAfterDisableOrSourceSwitch() {
        assertFalse(SystemMediaArtworkPolicy.isCurrentRequest(
            enabled = false,
            requestGeneration = 4,
            activeGeneration = 4,
            requestSource = "local:a",
            activeSource = "local:a",
        ))
        assertFalse(SystemMediaArtworkPolicy.isCurrentRequest(
            enabled = true,
            requestGeneration = 4,
            activeGeneration = 5,
            requestSource = "local:a",
            activeSource = "local:a",
        ))
        assertFalse(SystemMediaArtworkPolicy.isCurrentRequest(
            enabled = true,
            requestGeneration = 4,
            activeGeneration = 4,
            requestSource = "local:a",
            activeSource = "bot:22",
        ))
        assertTrue(SystemMediaArtworkPolicy.isCurrentRequest(
            enabled = true,
            requestGeneration = 5,
            activeGeneration = 5,
            requestSource = "bot:22",
            activeSource = "bot:22",
        ))
    }
}
