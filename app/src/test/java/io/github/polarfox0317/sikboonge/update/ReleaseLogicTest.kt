package io.github.polarfox0317.sikboonge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLogicTest {
    @Test
    fun compareVersionsUsesNumericSegmentsAndIgnoresBuildSuffixes() {
        assertEquals(0, compareVersions("v0.3.0", "0.3.0-debug"))
        assertTrue(compareVersions("0.3.10", "0.3.9")!! > 0)
        assertTrue(compareVersions("0.4", "0.3.99")!! > 0)
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertNull(compareVersions("latest", "0.3.0"))
    }

    @Test
    fun parseReleaseRecordIgnoresDraftsAndPrereleases() {
        assertNull(release(draft = true).let(::parseReleaseRecord))
        assertNull(release(prerelease = true).let(::parseReleaseRecord))
        assertNull(release(tag = "not-a-version").let(::parseReleaseRecord))
    }

    @Test
    fun parseReleaseRecordNormalizesTagAndSelectsApk() {
        val record = release(
            tag = "v0.3.1",
            assets = listOf(
                GithubReleaseAsset("notes.txt", "https://github.com/release/notes.txt", 10),
                GithubReleaseAsset("sikboonge-0.3.1.APK", "https://github.com/release/app.apk", 120),
            ),
        )

        val parsed = requireNotNull(parseReleaseRecord(record))
        assertEquals("0.3.1", parsed.version)
        assertEquals("v0.3.1", parsed.tagName)
        assertEquals("sikboonge 0.3.1", parsed.title)
        assertEquals("sikboonge-0.3.1.APK", parsed.apk?.name)
    }

    @Test
    fun selectLatestReleaseChoosesHighestStableNumericVersion() {
        val latest = selectLatestRelease(
            listOf(
                release(tag = "v0.3.9"),
                release(tag = "v0.4.0-rc1", prerelease = true),
                release(tag = "v0.3.10"),
                release(tag = "v0.5.0", draft = true),
            ),
        )

        assertEquals("0.3.10", latest?.version)
    }

    @Test
    fun selectApkAssetRequiresApkExtensionAndHttps() {
        val selected = selectApkAsset(
            listOf(
                GithubReleaseAsset("app.apk.zip", "https://github.com/release/app.apk.zip", 10),
                GithubReleaseAsset("unsafe.apk", "http://github.com/release/unsafe.apk", 10),
                GithubReleaseAsset("release.apk", "https://github.com/release/release.apk", 10),
            ),
        )

        assertEquals("release.apk", selected?.name)
        assertNull(selectApkAsset(emptyList()))
    }

    @Test
    fun shortenReleaseNotesRemovesCommonMarkdownAndLimitsLength() {
        assertEquals("Fix audio playback Add a retry button", shortenReleaseNotes("## Fix audio playback\n- Add a retry button"))
        assertEquals("abcd…", shortenReleaseNotes("abcdefgh", maxLength = 5))
    }

    private fun release(
        tag: String = "v0.3.1",
        title: String = "sikboonge 0.3.1",
        body: String = "## Fix audio playback\n- Add a retry button",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GithubReleaseAsset> = listOf(
            GithubReleaseAsset("sikboonge-0.3.1.apk", "https://github.com/release/app.apk", 120),
        ),
    ) = GithubReleaseRecord(tag, title, body, draft, prerelease, assets)
}
