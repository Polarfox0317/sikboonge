package io.github.polarfox0317.sikboonge

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaybackPolicyTest {
    @Test fun onlyReadableDirectAudioFilesUnderAppFilesDirectoryAreOwned() {
        val root = Files.createTempDirectory("local-playback-policy").toFile()
        try {
            val audioDirectory = File(root, "audio").apply { mkdirs() }
            val ownedFile = File(audioDirectory, "saved.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val nestedFile = File(audioDirectory, "nested/saved.wav").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val outsideFile = File(root, "outside.wav").apply { writeBytes(byteArrayOf(1)) }

            assertEquals(ownedFile.canonicalFile, LocalPlaybackPolicy.ownedAudioFile(root, ownedFile.absolutePath))
            assertNull(LocalPlaybackPolicy.ownedAudioFile(root, nestedFile.absolutePath))
            assertNull(LocalPlaybackPolicy.ownedAudioFile(root, outsideFile.absolutePath))
            assertNull(LocalPlaybackPolicy.ownedAudioFile(root, File(audioDirectory, "missing.wav").absolutePath))
            assertNull(LocalPlaybackPolicy.ownedAudioFile(root, null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun audioMimeIsNormalizedAndMalformedValuesFallBack() {
        assertEquals("audio/mpeg", LocalPlaybackPolicy.audioMime(" AUDIO/MPEG; codecs=mp3 "))
        assertEquals("audio/*", LocalPlaybackPolicy.audioMime("video/mp4"))
        assertEquals("audio/*", LocalPlaybackPolicy.audioMime("content://private/file"))
    }
}
