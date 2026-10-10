package io.github.polarfox0317.sikboonge.update

import java.math.BigInteger

internal data class GithubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

internal data class GithubReleaseRecord(
    val tagName: String,
    val title: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GithubReleaseAsset>,
)

internal data class AppRelease(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val apk: GithubReleaseAsset?,
)

/** Numeric, suffix-insensitive comparison for tags such as v0.3.0 and installed 0.3.0-debug. */
internal fun compareVersions(first: String, second: String): Int? {
    val firstParts = versionParts(first) ?: return null
    val secondParts = versionParts(second) ?: return null
    val count = maxOf(firstParts.size, secondParts.size)
    for (index in 0 until count) {
        val left = firstParts.getOrElse(index) { BigInteger.ZERO }
        val right = secondParts.getOrElse(index) { BigInteger.ZERO }
        val compared = left.compareTo(right)
        if (compared != 0) return compared
    }
    return 0
}

internal fun normalizedVersion(version: String): String? {
    val parts = versionParts(version) ?: return null
    return parts.joinToString(".")
}

private fun versionParts(value: String): List<BigInteger>? {
    val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
    return match.groupValues[1].split('.').map { segment ->
        segment.toBigIntegerOrNull() ?: return null
    }
}

private val VERSION_PATTERN = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:[-+].*)?$")

/** Converts the GitHub API's decoded release record into the small app-facing model. */
internal fun parseReleaseRecord(record: GithubReleaseRecord): AppRelease? {
    if (record.draft || record.prerelease) return null
    val version = normalizedVersion(record.tagName) ?: return null
    return AppRelease(
        tagName = record.tagName,
        version = version,
        title = record.title.trim().ifBlank { record.tagName },
        notes = shortenReleaseNotes(record.body),
        apk = selectApkAsset(record.assets),
    )
}

internal fun selectLatestRelease(records: List<GithubReleaseRecord>): AppRelease? =
    records.mapNotNull(::parseReleaseRecord).maxWithOrNull(
        Comparator { left, right -> compareVersions(left.version, right.version) ?: 0 },
    )

internal fun selectApkAsset(assets: List<GithubReleaseAsset>): GithubReleaseAsset? =
    assets.firstOrNull { asset ->
        asset.name.endsWith(".apk", ignoreCase = true) &&
            asset.downloadUrl.startsWith("https://", ignoreCase = true)
    }

internal fun shortenReleaseNotes(body: String, maxLength: Int = 420): String {
    val plainText = body.lineSequence()
        .map { line -> line.trim().replace(Regex("^#{1,6}\\s*|^[-*+]\\s*|^>\\s*"), "") }
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replace(Regex("[`*_~]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (plainText.length <= maxLength) return plainText
    return plainText.take((maxLength - 1).coerceAtLeast(0)).trimEnd() + "…"
}
