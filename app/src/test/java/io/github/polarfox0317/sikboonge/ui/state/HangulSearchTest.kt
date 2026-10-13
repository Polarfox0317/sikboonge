package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulSearchTest {
    @Test fun choseongAndMixedTermsMatchWithoutBroadeningComposedQueries() {
        assertTrue(matchesTextTerms("한국 OST2026", emptyList(), "ㅎㄱ"))
        assertTrue(matchesTextTerms("한국 OST2026", emptyList(), "한ㄱ"))
        assertTrue(matchesTextTerms("한국 OST2026", emptyList(), "ㅎㄱ ost2026"))
        assertTrue(matchesTextTerms("한국 OST2026", emptyList(), "ㅎㄱ oST2026"))
        assertFalse(matchesTextTerms("헌금 OST2026", emptyList(), "한ㄱ ost2026"))
        assertFalse(matchesTextTerms("한강", emptyList(), "한국"))
        assertTrue(matchesTextTerms("한강", emptyList(), "ㅎㄱ"))
    }

    @Test fun tenseInitialsRemainDistinctAndFinalConsonantsAreNotInitials() {
        assertTrue(matchesTextTerms("까치", emptyList(), "ㄲㅊ"))
        assertFalse(matchesTextTerms("가치", emptyList(), "ㄲㅊ"))
        assertTrue(matchesTextTerms("따봄", emptyList(), "ㄸㅂ"))
        assertTrue(matchesTextTerms("빨봄", emptyList(), "ㅃㅂ"))
        assertTrue(matchesTextTerms("쌀집", emptyList(), "ㅆㅈ"))
        assertTrue(matchesTextTerms("짜장면", emptyList(), "ㅉㅈㅁ"))
        assertFalse(matchesTextTerms("각", emptyList(), "ㄱㄱ"))
        assertFalse(matchesTextTerms("한", emptyList(), "ㄴ"))
    }

    @Test fun decomposedHangulAndModernLeadingJamoWork() {
        assertTrue(matchesTextTerms("한국", emptyList(), "ㅎㄱ"))
        assertTrue(matchesTextTerms("한국", emptyList(), "ᄒᄀ"))
        assertTrue(matchesTextTerms("한글", emptyList(), "한ㄱ"))
        assertEquals("한국", HangulSearch.normalize("한국"))
    }

    @Test fun punctuationAndNonHangulRemainLiteralBoundaries() {
        assertTrue(matchesTextTerms("한국 OST2026", emptyList(), "ㅎㄱ ost2026"))
        assertFalse(matchesTextTerms("한-국", emptyList(), "ㅎㄱ"))
        assertFalse(matchesTextTerms("한 국", emptyList(), "ㅎㄱ"))
        assertTrue(matchesTextTerms("한-국", emptyList(), "한-국"))
        assertTrue(matchesTextTerms("한 국", emptyList(), "한 국"))
    }

    @Test fun titleAndTagMatchingFlowsThroughBotAndLibraryFilters() {
        val library = listOf(
            AudioResult("a", "한국 OST2026", "audio/test", 1, 10, tags = listOf("봄 음악")),
            AudioResult("b", "한강 OST2026", "audio/test", 1, 10, tags = listOf("여름")),
        )
        val bot = listOf(
            BotVideo(1, 2, 3, "한국 OST2026", 1, 1, 10, tags = listOf("봄 음악")),
            BotVideo(2, 2, 4, "한강 OST2026", 1, 1, 10, tags = listOf("여름")),
        )
        val query = ListQuery(text = "ㅎㄱ ost2026")
        assertEquals(listOf("a", "b"), filterLibrary(library, query, 10_000).map { it.path })
        assertEquals(listOf(3L, 4L), filterBot(bot, query, 10_000).map { it.messageId })
        assertTrue(matchesTextTerms("다른 제목", listOf("한국 음악"), "#ㅎㄱ"))
        assertFalse(matchesTextTerms("한국 음악", emptyList(), "#ㅎㄱ"))
    }
}
