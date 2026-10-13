package io.github.polarfox0317.sikboonge.ui.state

import java.text.Normalizer
import java.util.Locale

/** Korean choseong search helpers, ported from es-hangul's getChoseong (MIT). */
internal object HangulSearch {
    private const val CHOSEONG_START = 0x1100
    private const val CHOSEONG_END = 0x1112

    private val choseong = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )
    private val compatibilityChoseong = choseong.toSet()

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * Match query code points in order. Explicit choseong characters match only the initial
     * of one source syllable; every other character, including composed Hangul, stays literal.
     */
    fun matches(normalizedText: String, normalizedTerm: String): Boolean {
        if (normalizedTerm.isEmpty()) return true
        if (normalizedText.contains(normalizedTerm)) return true
        if (!containsExplicitChoseong(normalizedTerm)) return false
        val source = normalizedText.codePoints().toArray()
        val query = normalizedTerm.codePoints().toArray()
        if (query.size > source.size) return false
        for (start in 0..source.size - query.size) {
            var matched = true
            for (index in query.indices) {
                val queryPoint = query[index]
                val sourcePoint = source[start + index]
                if (isChoseong(queryPoint)) {
                    if (choseongFor(sourcePoint) != compatibilityChoseongFor(queryPoint)) {
                        matched = false
                        break
                    }
                } else if (queryPoint != sourcePoint) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /**
     * Ported from toss/es-hangul getChoseong with keepNonHangul=true at
     * https://github.com/toss/es-hangul/blob/920119b/src/core/getChoseong/getChoseong.ts.
     * NFD exposes a composed syllable's choseong; the upstream 19-entry mapping is retained.
     */
    private fun choseongFor(codePoint: Int): Char? {
        if (codePoint in CHOSEONG_START..CHOSEONG_END) return choseong[codePoint - CHOSEONG_START]
        if (codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in compatibilityChoseong) {
            return codePoint.toChar()
        }
        if (codePoint !in 0xAC00..0xD7A3) return null
        val decomposed = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
        val initial = decomposed.codePointAt(0)
        return if (initial in CHOSEONG_START..CHOSEONG_END) choseong[initial - CHOSEONG_START] else null
    }

    private fun isChoseong(codePoint: Int): Boolean =
        codePoint in CHOSEONG_START..CHOSEONG_END ||
            (codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in compatibilityChoseong)

    private fun containsExplicitChoseong(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = Character.codePointAt(value, offset)
            offset += Character.charCount(codePoint)
            if (isChoseong(codePoint)) return true
        }
        return false
    }

    private fun compatibilityChoseongFor(codePoint: Int): Char? = when (codePoint) {
        in CHOSEONG_START..CHOSEONG_END -> choseong[codePoint - CHOSEONG_START]
        else -> codePoint.toChar().takeIf { it in compatibilityChoseong }
    }
}
