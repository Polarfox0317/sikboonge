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
    fun upgradedBuild132HasOfflineNotesUntilAcknowledgedAndKeeps125Available() {
        val pending = pendingBundledChangelog(132L, 125L, isUpdatedInstall = true)
        assertNotNull(pending)
        assertEquals(bundledChangelog(132L), pending)
        assertNotNull(pendingBundledChangelog(132L, 131L, isUpdatedInstall = true))
        assertNull(pendingBundledChangelog(132L, 132L, isUpdatedInstall = true))
        assertNotNull(bundledChangelog(125L, "en-US"))
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
    fun build132UsesApprovedKoreanAndEnglishPlaybackAndEditingCategories() {
        val korean = requireNotNull(bundledChangelog(132L, "ko-KR"))
        assertEquals(
            listOf("재생 기능 개선", "제목 편집 개선", "버그 수정"),
            korean.filterIsInstance<ReleaseNoteBlock.Heading>().map { it.text },
        )
        assertEquals(8, korean.filterIsInstance<ReleaseNoteBlock.Item>().size)
        assertTrue(korean.any { it is ReleaseNoteBlock.Item && "Android 미디어 컨트롤" in it.text })

        val english = requireNotNull(bundledChangelog(132L, "en-US"))
        assertEquals(
            listOf("Playback improvements", "Title editing improvements", "Bug fixes"),
            english.filterIsInstance<ReleaseNoteBlock.Heading>().map { it.text },
        )
        assertEquals(8, english.filterIsInstance<ReleaseNoteBlock.Item>().size)
        assertTrue(english.any { it is ReleaseNoteBlock.Item && "Android media controls" in it.text })
        assertTrue(english.filterIsInstance<ReleaseNoteBlock.Item>().none { "재생" in it.text })
        assertEquals(korean, bundledChangelog(132L, "fr"))
    }

    @Test
    fun existingBundledVersionsRemainAvailableAndUnknownBuildsDoNotBorrow125() {
        assertEquals(3, requireNotNull(bundledChangelog(109L)).size)
        assertEquals(bundledChangelog(109L), bundledChangelog(110L))
        assertEquals("다운로드 알림 개선", (requireNotNull(bundledChangelog(111L)).first() as ReleaseNoteBlock.Heading).text)
        assertNull(bundledChangelog(124L))
        assertNull(pendingBundledChangelog(126L, 125L, isUpdatedInstall = true))
    }

    // The product ships as 149; an install updating from 148 must land directly on the new notes.
    @Test
    fun upgradedBuild150ShowsThe149NotesUntilAcknowledged() {
        val pending = pendingBundledChangelog(150L, 149L, isUpdatedInstall = true)
        assertNotNull(pending)
        assertEquals(bundledChangelog(150L), pending)
        // 149 and 150 share the same 1.1.4 body.
        assertEquals(bundledChangelog(149L), bundledChangelog(150L))
        assertNotNull(pendingBundledChangelog(150L, 137L, isUpdatedInstall = true))
        assertNull(pendingBundledChangelog(150L, 150L, isUpdatedInstall = true))
        assertNull(pendingBundledChangelog(150L, 150L, isUpdatedInstall = true))
        // An install updating straight from 149 still sees the notes once.
        assertNotNull(pendingBundledChangelog(149L, 148L, isUpdatedInstall = true))
        // Build 151 shares the same 1.1.4 body.
        assertEquals(bundledChangelog(149L), bundledChangelog(151L))
        assertNotNull(pendingBundledChangelog(151L, 150L, isUpdatedInstall = true))
        assertNull(pendingBundledChangelog(151L, 151L, isUpdatedInstall = true))
    }

    @Test
    fun freshInstallDoesNotShowThe149UpdatePopup() {
        assertNull(pendingBundledChangelog(149L, 0L, isUpdatedInstall = false))
        assertNull(pendingBundledChangelog(150L, 0L, isUpdatedInstall = false))
        assertNull(pendingBundledChangelog(151L, 0L, isUpdatedInstall = false))
        // An upgrade with no previously seen notes still shows them, matching earlier builds.
        assertNotNull(pendingBundledChangelog(149L, 0L, isUpdatedInstall = true))
        assertNotNull(pendingBundledChangelog(150L, 0L, isUpdatedInstall = true))
    }

    @Test
    fun build149KoreanAndEnglishSectionsUseApprovedHeadings() {
        val korean = requireNotNull(bundledChangelog(149L, "ko-KR"))
        val english = requireNotNull(bundledChangelog(149L, "en-US"))
        assertEquals(
            listOf("버그 수정", "재생목록과 이어듣기", "플레이어와 안내", "라이브러리와 라이선스"),
            korean.filterIsInstance<ReleaseNoteBlock.Heading>().map { it.text },
        )
        assertEquals(
            listOf("Bug fixes", "Queue and resume playback", "Player and feedback", "Libraries and licensing"),
            english.filterIsInstance<ReleaseNoteBlock.Heading>().map { it.text },
        )
        // Category order matches across languages, and neither side is a partial translation.
        assertEquals(
            korean.filterIsInstance<ReleaseNoteBlock.Heading>().size,
            english.filterIsInstance<ReleaseNoteBlock.Heading>().size,
        )
        assertEquals(
            korean.filterIsInstance<ReleaseNoteBlock.Item>().size,
            english.filterIsInstance<ReleaseNoteBlock.Item>().size,
        )
        assertTrue(korean.filterIsInstance<ReleaseNoteBlock.Item>().all { it.text.isNotBlank() })
        assertTrue(english.filterIsInstance<ReleaseNoteBlock.Item>().all { it.text.isNotBlank() })
        // The English section never borrows Korean text.
        assertTrue(english.filterIsInstance<ReleaseNoteBlock.Item>().none { "재생" in it.text || "이어서" in it.text })
        assertEquals(korean, bundledChangelog(149L, "fr"))
    }

    // The release-notes header (source notice) must not leak into the in-app product list.
    @Test
    fun build149NotesContainNoSourceNoticeHeader() {
        val all = requireNotNull(bundledChangelog(149L, "ko-KR")) +
            requireNotNull(bundledChangelog(149L, "en-US"))
        val texts = all.map { block ->
            when (block) {
                is ReleaseNoteBlock.Heading -> block.text
                is ReleaseNoteBlock.Item -> block.text
                is ReleaseNoteBlock.Paragraph -> block.text
            }
        }
        // Only the section body is parsed: the title line and the source/checksum header are gone.
        assertTrue(texts.none { it.contains("SHA-256") })
        assertTrue(texts.none { it.contains("f563039dba6cf952c0b4de9bae0d19550f78404c6d060b76fc796e8706cbdde0") })
        assertTrue(texts.none { it.trim() == "싴붕이 1.1.4" })
        assertTrue(texts.none { it.contains("corresponding source") })
        assertTrue(texts.none { it.contains(".zip") })
        // The product list itself starts at the first approved category heading.
        assertTrue(all.first() is ReleaseNoteBlock.Heading)
    }

    // Older bundled notes keep their exact approved wording and stay reachable.
    @Test
    fun olderBundledNotesKeepTheirApprovedWording() {
        val notes137 = requireNotNull(bundledChangelog(137L, "ko"))
        assertEquals(1, notes137.filterIsInstance<ReleaseNoteBlock.Heading>().size)
        assertEquals("버그 수정", (notes137.first() as ReleaseNoteBlock.Heading).text)
        assertEquals(2, notes137.filterIsInstance<ReleaseNoteBlock.Item>().size)
        assertTrue(notes137.filterIsInstance<ReleaseNoteBlock.Item>()
            .any { "이미 재생목록에 있어요" in it.text })
        assertEquals(3, requireNotNull(bundledChangelog(109L)).size)
        assertEquals(bundledChangelog(109L), bundledChangelog(110L))
    }
}
