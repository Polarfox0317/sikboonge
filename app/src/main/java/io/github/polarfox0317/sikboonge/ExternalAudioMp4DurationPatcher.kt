package io.github.polarfox0317.sikboonge

import java.io.ByteArrayOutputStream

/** Buffers only the bounded initial ISO-BMFF header so its known movie duration can be exposed. */
internal class ExternalAudioMp4DurationPatcher(private val durationSeconds: Double) {
    private val pending = ByteArrayOutputStream()
    private var completed = false

    /** Returns the buffered prefix once the first moov is complete, or null while it is incomplete. */
    fun append(bytes: ByteArray, length: Int): ByteArray? {
        require(length in 0..bytes.size)
        if (completed) return bytes.copyOf(length)
        if (pending.size() > MAX_HEADER_BYTES - length) {
            return flushUnmodified(bytes, length)
        }
        pending.write(bytes, 0, length)
        val prefix = pending.toByteArray()
        val moov = findInitialMoov(prefix) ?: return null
        completed = true
        patchMovieDuration(prefix, moov, durationSeconds)
        return prefix
    }

    /** Preserves a short or malformed producer prefix if it ends before a complete moov arrives. */
    fun finish(): ByteArray? {
        if (completed || pending.size() == 0) return null
        completed = true
        return pending.toByteArray()
    }

    private fun flushUnmodified(bytes: ByteArray, length: Int): ByteArray {
        pending.write(bytes, 0, length)
        completed = true
        return pending.toByteArray()
    }

    private data class Box(val start: Int, val headerSize: Int, val size: Int, val type: String) {
        val end: Int get() = start + size
    }

    private fun findInitialMoov(data: ByteArray): Box? {
        var cursor = 0
        while (cursor < data.size) {
            val box = readBox(data, cursor, data.size) ?: return null
            if (box.type == "moov") return box
            cursor = box.end
        }
        return null
    }

    private fun patchMovieDuration(data: ByteArray, moov: Box, seconds: Double) {
        if (!seconds.isFinite() || seconds <= 0.0) return
        var cursor = moov.start + moov.headerSize
        var movieTimeScale = 0L
        while (cursor < moov.end) {
            val child = readBox(data, cursor, moov.end) ?: return
            when (child.type) {
                "mvhd" -> {
                    movieTimeScale = patchDurationBox(data, child, seconds, DurationBoxKind.MOVIE) ?: 0L
                }
                "trak" -> patchTrackDuration(data, child, seconds, movieTimeScale)
            }
            cursor = child.end
        }
    }

    private fun patchTrackDuration(data: ByteArray, trak: Box, seconds: Double, movieTimeScale: Long) {
        var cursor = trak.start + trak.headerSize
        while (cursor < trak.end) {
            val child = readBox(data, cursor, trak.end) ?: return
            when (child.type) {
                "tkhd" -> if (movieTimeScale > 0L) patchDurationBox(data, child, seconds, DurationBoxKind.TRACK, movieTimeScale)
                "mdia" -> {
                    var mediaCursor = child.start + child.headerSize
                    while (mediaCursor < child.end) {
                        val mediaChild = readBox(data, mediaCursor, child.end) ?: return
                        if (mediaChild.type == "mdhd") patchDurationBox(data, mediaChild, seconds, DurationBoxKind.MEDIA)
                        mediaCursor = mediaChild.end
                    }
                }
            }
            cursor = child.end
        }
    }

    private enum class DurationBoxKind { MOVIE, TRACK, MEDIA }

    /** Patches only the existing fixed-width duration field; it never changes box sizes/layout. */
    private fun patchDurationBox(data: ByteArray, box: Box, seconds: Double, kind: DurationBoxKind, knownTimeScale: Long = 0L): Long? {
        val fullBox = box.start + box.headerSize
        if (fullBox + 4 > box.end) return null
        val version = data[fullBox].toInt() and 0xff
        val timeScaleOffset: Int
        val durationOffset: Int
        val durationBytes: Int
        when (version) {
            0 -> {
                timeScaleOffset = when (kind) {
                    DurationBoxKind.TRACK -> -1
                    else -> fullBox + 12
                }
                durationOffset = when (kind) {
                    DurationBoxKind.TRACK -> fullBox + 20
                    else -> fullBox + 16
                }
                durationBytes = 4
            }
            1 -> {
                timeScaleOffset = when (kind) {
                    DurationBoxKind.TRACK -> -1
                    else -> fullBox + 20
                }
                durationOffset = when (kind) {
                    DurationBoxKind.TRACK -> fullBox + 28
                    else -> fullBox + 24
                }
                durationBytes = 8
            }
            else -> return null
        }
        if (durationOffset + durationBytes > box.end) return null
        val timeScale = if (kind == DurationBoxKind.TRACK) knownTimeScale
        else readUInt32(data, timeScaleOffset)
        if (timeScale <= 0L) return null
        val ticks = seconds * timeScale.toDouble()
        if (!ticks.isFinite() || ticks < 1.0 || ticks >= Long.MAX_VALUE.toDouble()) return null
        val rounded = Math.round(ticks)
        if (durationBytes == 4) {
            if (rounded <= 0L || rounded > UINT32_MAX) return null
            writeUInt32(data, durationOffset, rounded)
        } else {
            if (rounded <= 0L) return null
            writeUInt64(data, durationOffset, rounded)
        }
        return timeScale
    }

    private fun readBox(data: ByteArray, start: Int, limit: Int): Box? {
        if (start < 0 || limit > data.size || start > limit - 8) return null
        val shortSize = readUInt32(data, start)
        val type = String(data, start + 4, 4, Charsets.US_ASCII)
        val headerSize: Int
        val size: Long
        when (shortSize) {
            0L -> return null // A size-to-EOF box cannot be bounded while the producer is live.
            1L -> {
                if (start > limit - 16) return null
                val extended = readUInt64(data, start + 8) ?: return null
                headerSize = 16
                size = extended
            }
            else -> {
                headerSize = 8
                size = shortSize
            }
        }
        if (size < headerSize || size > MAX_HEADER_BYTES || size > (limit - start).toLong()) return null
        return Box(start, headerSize, size.toInt(), type)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun readUInt64(data: ByteArray, offset: Int): Long? {
        var value = 0L
        for (index in 0 until 8) {
            if (value < 0L) return null
            value = (value shl 8) or (data[offset + index].toLong() and 0xff)
        }
        return value.takeIf { it >= 0L }
    }

    private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) data[offset + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun writeUInt64(data: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) data[offset + index] = (value ushr (56 - index * 8)).toByte()
    }

    companion object {
        private const val MAX_HEADER_BYTES = 4 * 1024 * 1024
        private const val UINT32_MAX = 0xffff_ffffL
    }
}
