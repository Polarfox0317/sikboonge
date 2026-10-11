package io.github.polarfox0317.sikboonge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledChangelogTest {
    @Test
    fun upgradedBuild125HasOfflineNotesUntilAcknowledged() {
        val pending = pendingBundledChangelog(125L, 111L, isUpdatedInstall = true)
        assertNotNull(pending)
        assertEquals(bundledChangelog(125L), pending)
        assertNotNull(pendingBundledChangelog(125L, 124L, isUpdatedInstall = true))
        // Acknowledgement persists 125 through the existing preference path.
        assertNull(pendingBundledChangelog(125L, 125L, isUpdatedInstall = true))
    }

    @Test
    fun freshInstallDoesNotAddAnUpdatePopupToTheWelcomeGuide() {
        assertNull(pendingBundledChangelog(125L, 0L, isUpdatedInstall = false))
    }

    @Test
    fun upgradedInstallWithNoPreviouslySeenNotesStillShows125() {
        assertNotNull(pendingBundledChangelog(125L, 0L, isUpdatedInstall = true))
    }

    @Test
    fun acknowledgedOrNewerSeenCodeDoesNotShowAgain() {
        assertNull(pendingBundledChangelog(125L, 125L, isUpdatedInstall = false))
        assertNull(pendingBundledChangelog(125L, 126L, isUpdatedInstall = true))
    }

    @Test
    fun koreanNotesKeepApprovedCategoriesAndSixDownloadItems() {
        val notes = requireNotNull(bundledChangelog(125L, "ko-KR"))
        assertEquals(
            listOf("다운로드 기능 개선", "태그와 검색", "테마", "첫 사용 가이드", "오픈소스 라이선스", "버그 수정"),
            notes.filterIsInstance<ReleaseNoteBlock.Heading>().map { it.text },
        )
        assertEquals(16, notes.filterIsInstance<ReleaseNoteBlock.Item>().size)
        assertEquals(6, notes.drop(1).takeWhile { it !is ReleaseNoteBlock.Heading }.size)
        assertTrue(notes.filterIsInstance<ReleaseNoteBlock.Item>().any { "#태그" in it.text })
    }

    @Test
    fun englishLocaleSelectsOnlyTheApprovedEnglishSection() {
        val notes = requireNotNull(bundledChangelog(125L, "en-US"))
        assertEquals(
            listOf("Download improvements", "Tags and search", "Themes", "First-use guide", "Open-source licenses", "Bug fixes"),
            notes.filterIsInstance<ReleaseNoteBlock.Heading>().map { it.text },
        )
        assertEquals(16, notes.filterIsInstance<ReleaseNoteBlock.Item>().size)
        assertTrue(notes.filterIsInstance<ReleaseNoteBlock.Item>().any { "#tag" in it.text })
        assertTrue(notes.filterIsInstance<ReleaseNoteBlock.Item>().none { "다운로드" in it.text })
    }

    @Test
    fun otherLocalesUseTheExistingKoreanFallback() {
        assertEquals(bundledChangelog(125L, "ko"), bundledChangelog(125L, "fr"))
        assertEquals(bundledChangelog(125L, "ko"), bundledChangelog(125L))
    }

    @Test
    fun existingBundledVersionsRemainAvailableAndUnknownBuildsDoNotBorrow125() {
        assertEquals(3, requireNotNull(bundledChangelog(109L)).size)
        assertEquals(bundledChangelog(109L), bundledChangelog(110L))
        assertEquals("다운로드 알림 개선", (requireNotNull(bundledChangelog(111L)).first() as ReleaseNoteBlock.Heading).text)
        assertNull(bundledChangelog(124L))
        assertNull(pendingBundledChangelog(126L, 125L, isUpdatedInstall = true))
    }

}
