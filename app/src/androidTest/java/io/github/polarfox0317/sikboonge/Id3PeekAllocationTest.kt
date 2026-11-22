package io.github.polarfox0317.sikboonge

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.EOFException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounds this upstream regression probe to a 1 MiB declared tag, never the large OOM payload. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class Id3PeekAllocationTest {
    @Test fun oversizedTagInTinyInputDoesNotAllocateItsDeclaredLength() = probe(version = 4, revision = 0, flags = 0)

    @Test fun invalidVersionOversizedTagDoesNotAllocateItsDeclaredLength() =
        probe(version = 0x20, revision = 0x74, flags = 0x68)

    private fun probe(version: Byte, revision: Byte, flags: Byte) {
        val bytes = ByteArray(176)
        // Valid ID3v2.4 header, but the tag declares 1 MiB in a known 176-byte input.
        byteArrayOf(0x49, 0x44, 0x33, version, revision, flags, 0, 0x40, 0, 0).copyInto(bytes)
        var offset = 0
        val reader = DataReader { target, targetOffset, length ->
            if (offset >= bytes.size) -1 else {
                val count = minOf(length, bytes.size - offset)
                bytes.copyInto(target, targetOffset, offset, offset + count)
                offset += count
                count
            }
        }
        val input = DefaultExtractorInput(reader, 0L, bytes.size.toLong())
        val buffer = DefaultExtractorInput::class.java.getDeclaredField("peekBuffer").apply { isAccessible = true }
        val initialCapacity = (buffer.get(input) as ByteArray).size
        try {
            Mp3Extractor().sniff(input)
        } catch (_: EOFException) {
            // Rejection of the truncated input is expected; the allocation before rejection isn't.
        }
        val finalCapacity = (buffer.get(input) as ByteArray).size
        println("ID3_PROBE version=$version inputBytes=${bytes.size} declaredTagBytes=1048576 initialPeek=$initialCapacity finalPeek=$finalCapacity")
        assertTrue("Tiny known-length input expanded peek buffer from $initialCapacity to $finalCapacity",
            finalCapacity <= initialCapacity)
    }
}
