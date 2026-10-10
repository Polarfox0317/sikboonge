package io.github.polarfox0317.sikboonge.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal sealed interface ReleaseLookupResult {
    data class Found(val release: AppRelease) : ReleaseLookupResult
    data object NoPublishedRelease : ReleaseLookupResult
    data object NoApkAsset : ReleaseLookupResult
}

internal class GithubReleaseClient {
    fun fetchLatestRelease(): ReleaseLookupResult {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", ACCEPT_HEADER)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            return when (val status = connection.responseCode) {
                404 -> ReleaseLookupResult.NoPublishedRelease
                in 200..299 -> {
                    val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val record = parseGithubReleaseJson(json)
                    val release = parseReleaseRecord(record)
                        ?: return ReleaseLookupResult.NoPublishedRelease
                    if (release.apk == null) ReleaseLookupResult.NoApkAsset
                    else ReleaseLookupResult.Found(release)
                }
                else -> throw IOException("GitHub release lookup returned HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGithubReleaseJson(json: String): GithubReleaseRecord {
        val root = JSONObject(json)
        val assetsJson = root.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                val asset = assetsJson.optJSONObject(index) ?: continue
                val name = asset.optString("name").takeIf(String::isNotBlank) ?: continue
                val url = asset.optString("browser_download_url").takeIf(String::isNotBlank) ?: continue
                add(
                    GithubReleaseAsset(
                        name = name,
                        downloadUrl = url,
                        sizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }
        return GithubReleaseRecord(
            tagName = root.optString("tag_name"),
            title = root.optString("name"),
            body = root.optString("body"),
            draft = root.optBoolean("draft", false),
            prerelease = root.optBoolean("prerelease", false),
            assets = assets,
        )
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Polarfox0317/sikboonge/releases/latest"
        const val ACCEPT_HEADER = "application/vnd.github+json"
        const val USER_AGENT = "sikboonge-android"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
