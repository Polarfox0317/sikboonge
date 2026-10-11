package io.github.polarfox0317.sikboonge

import java.text.Normalizer
import java.util.Locale

internal object TagPolicy {
    const val MAX_TAG_CHARS = 20
    const val MAX_TAGS = 10

    fun normalizeTag(raw: String): String? {
        val normalized = Normalizer.normalize(
            raw.filterNot { Character.isISOControl(it) },
            Normalizer.Form.NFC,
        ).trim().trimStart('#').replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length) > MAX_TAG_CHARS) return null
        return normalized
    }

    fun normalizeTags(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        return raw.mapNotNull(::normalizeTag)
            .filter { seen.add(key(it)) }
            .take(MAX_TAGS)
    }

    fun key(tag: String): String = Normalizer.normalize(tag, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}
