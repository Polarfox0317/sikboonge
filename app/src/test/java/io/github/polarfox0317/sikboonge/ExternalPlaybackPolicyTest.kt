package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ExternalPlaybackPolicyTest {
    @Test fun supportedAudioCodecsUseAudioOnlyStreamCopyContainers() {
        assertEquals("audio/mp4", ExternalAudioPolicy.plan("aac").mime)
        assertEquals("audio/mp4", ExternalAudioPolicy.plan("alac").mime)
        assertEquals("audio/mpeg", ExternalAudioPolicy.plan("mp3").mime)
        assertEquals("audio/flac", ExternalAudioPolicy.plan("flac").mime)
        assertEquals("audio/ogg", ExternalAudioPolicy.plan("opus").mime)
        assertEquals("audio/ogg", ExternalAudioPolicy.plan("vorbis").mime)
        val args = ExternalAudioPolicy.outputArgs("tcp://127.0.0.1:10000", ExternalAudioPolicy.plan("aac"))
        assertTrue(args.containsAll(listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-c:a", "copy", "-f", "mp4")))
        assertFalse(args.any { it == "-c:v" || it == "-map" && args.getOrNull(args.indexOf(it) + 1)?.startsWith("0:v") == true })
    }

    @Test fun unsupportedCodecsNeverSelectVideoFallback() {
        try {
            ExternalAudioPolicy.plan("ac3")
            throw AssertionError("Unsupported codecs must not fall back to video")
        } catch (expected: UnsupportedAudioCodecException) {
            assertTrue(expected.message.orEmpty().contains("오디오 코덱"))
        }
    }

    @Test fun audioMimeFilenameAndLongRangeAreCanonicalAndSafe() {
        assertEquals("audio/mpeg", normalizeAudioMime("AUDIO/MPEG; codecs=mp3"))
        assertEquals("audio/*", normalizeAudioMime("video/mp4"))
        assertEquals("audio/*", normalizeAudioMime("audio/../mp3"))
        assertEquals("clip.m4a", ExternalAudioPolicy.displayName("C:\\private\\clip.mp4", "m4a"))
        assertEquals("telegam.ogg", ExternalAudioPolicy.displayName("/private/tele\u0000gam.mp4", "ogg"))
        assertEquals("Telegram media", safeDisplayName("  "))
        assertEquals(Long.MAX_VALUE, ExternalAudioPolicy.rangeEnd(Long.MAX_VALUE - 3, 12))
        assertEquals(3L * 1024 * 1024 * 1024 + 17, ExternalAudioPolicy.rangeEnd(3L * 1024 * 1024 * 1024, 17))
    }

    @Test fun audioDurationPrefersExactSelectedStreamTicksAndRejectsUnknownValues() {
        assertEquals(73.0, ExternalAudioPolicy.audioDurationSeconds("72.9", "3504000", "1/48000")!!, 0.0001)
        assertEquals(12.5, ExternalAudioPolicy.audioDurationSeconds("12.5", "N/A", "N/A")!!, 0.0001)
        assertEquals(null, ExternalAudioPolicy.audioDurationSeconds("N/A", "N/A", "N/A"))
        assertEquals(null, ExternalAudioPolicy.audioDurationSeconds("-1", "-10", "1/48000"))
    }

    @Test fun fragmentedMp4InitialHeaderGetsKnownDurationWithoutChangingItsSize() {
        val original = fixtureHeader()
        val patcher = ExternalAudioMp4DurationPatcher(73.0)
        assertEquals(null, patcher.append(original, original.size - 1))
        val patched = requireNotNull(patcher.append(original.copyOfRange(original.size - 1, original.size), 1))

        assertEquals("No bytes are added or removed from the live stream", original.size, patched.size)
        assertEquals(73_000L, findDuration(patched, "mvhd"))
        assertEquals(73_000L, findDuration(patched, "tkhd"))
        assertEquals(3_504_000L, findDuration(patched, "mdhd"))
        assertEquals("The metadata patch must not alter codec/header bytes", original[0], patched[0])
        assertEquals("The following fragment bytes pass through unchanged", byteArrayOf(7, 8, 9).toList(), patcher.append(byteArrayOf(7, 8, 9), 3)?.toList())
    }

    @Test fun unknownOrUnrepresentableDurationLeavesFragmentedHeaderUnchanged() {
        val original = fixtureHeader()
        assertEquals(original.toList(), ExternalAudioMp4DurationPatcher(Double.NaN).append(original, original.size)?.toList())
        assertEquals(original.toList(), ExternalAudioMp4DurationPatcher(1.0e20).append(original, original.size)?.toList())
    }

    @Test fun versionOneDurationFieldsUseTheir64BitLayout() {
        val header = fixtureHeader(versionOne = true)
        val patched = requireNotNull(ExternalAudioMp4DurationPatcher(1_500_000_000.0).append(header, header.size))
        assertEquals(1_500_000_000_000L, findDuration(patched, "mvhd"))
        assertEquals(1_500_000_000_000L, findDuration(patched, "tkhd"))
        assertEquals(72_000_000_000_000L, findDuration(patched, "mdhd"))
    }

    private fun fixtureHeader(versionOne: Boolean = false): ByteArray {
        fun box(type: String, contents: ByteArray): ByteArray = ByteBuffer.allocate(8 + contents.size)
            .putInt(8 + contents.size).put(type.toByteArray(Charsets.US_ASCII)).put(contents).array()
        fun durationBox(type: String, timeScale: Int, durationOffset: Int): ByteArray {
            val data = ByteArray(durationOffset + if (versionOne) 8 else 4)
            data[0] = if (versionOne) 1 else 0
            val timeScaleOffset = if (versionOne) 20 else 12
            ByteBuffer.wrap(data).putInt(timeScaleOffset, timeScale)
            return box(type, data)
        }
        val mvhd = durationBox("mvhd", 1_000, if (versionOne) 24 else 16)
        val tkhd = durationBox("tkhd", 0, if (versionOne) 28 else 20)
        val mdhd = durationBox("mdhd", 48_000, if (versionOne) 24 else 16)
        val trak = box("trak", tkhd + box("mdia", mdhd))
        return box("ftyp", byteArrayOf(0, 0, 0, 0)) + box("moov", mvhd + trak)
    }

    private fun findDuration(data: ByteArray, type: String): Long {
        val bytes = type.toByteArray(Charsets.US_ASCII)
        val typeOffset = data.indices.first { offset ->
            offset + 4 <= data.size && data.copyOfRange(offset, offset + 4).contentEquals(bytes)
        }
        val version = data[typeOffset + 4].toInt() and 0xff
        val durationOffset = when (type) {
            "mvhd", "mdhd" -> typeOffset + if (version == 0) 20 else 28
            "tkhd" -> typeOffset + if (version == 0) 24 else 32
            else -> error("Unexpected box type")
        }
        val buffer = ByteBuffer.wrap(data)
        return if (version == 0) buffer.getInt(durationOffset).toLong() and 0xffff_ffffL else buffer.getLong(durationOffset)
    }
}
