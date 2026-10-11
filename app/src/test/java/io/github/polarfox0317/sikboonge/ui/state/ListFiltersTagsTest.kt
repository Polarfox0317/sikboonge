package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListFiltersTagsTest {
    @Test fun hashtagTermsOnlyMatchTagsAndOtherTermsMatchTitleOrTags() {
        val tags = listOf("Rain Sound", "Night")
        assertTrue(matchesTextTerms("Morning ambience", tags, "#rain sou"))
        assertFalse(matchesTextTerms("Rain is falling", listOf("Night"), "#rain"))
        assertTrue(matchesTextTerms("Morning ambience", tags, "morning #night"))
        assertTrue(matchesTextTerms("Morning ambience", tags, "sound"))
        assertFalse(matchesTextTerms("Morning ambience", tags, "morning ocean"))
    }

    @Test fun selectedTagsAreAnAndFilterForLibraryAndBot() {
        val library = listOf(
            AudioResult("a", "Rain", "audio/test", 1, 10, tags = listOf("rain", "night")),
            AudioResult("b", "Rain", "audio/test", 1, 10, tags = listOf("rain")),
        )
        val bot = listOf(
            BotVideo(1, 2, 3, "Rain", 1, 1, 10, tags = listOf("rain", "night")),
            BotVideo(2, 2, 4, "Rain", 1, 1, 10, tags = listOf("rain")),
        )
        val query = ListQuery(tags = setOf("RAIN", "night"))
        assertEquals(listOf("a"), filterLibrary(library, query, 10_000).map { it.path })
        assertEquals(listOf(3L), filterBot(bot, query, 10_000).map { it.messageId })
        assertTrue(query.active)
        assertTrue(matchesTagFilter(emptyList(), emptySet()))
        assertFalse(matchesTagFilter(listOf("rain"), setOf("rain", "night")))
    }

    @Test fun editedBotTagsAreUsedForVisibleSelectionAndSurviveAnotherEdit() {
        val original = listOf(
            BotVideo(1, 2, 3, "Rain", 1, 1, 10),
            BotVideo(2, 2, 4, "Rain", 1, 1, 10),
        )
        val edited = applyBotTagOverrides(original, mapOf(3L to listOf("Night"), 4L to listOf("Rain")))
        val query = ListQuery(tags = setOf("NIGHT"))
        assertEquals(listOf(3L), filterBot(edited, query, 10_000).map { it.messageId })
        assertEquals(setOf("night"), query.withAvailableTags(edited.map(BotVideo::tags)).tags)
    }

    @Test fun deletingLastTaggedItemClearsOnlyMissingFilters() {
        val query = ListQuery(text = "rain", ascending = true, tags = setOf("NIGHT", "Rain"))
        val remaining = query.withAvailableTags(listOf(listOf("RAIN")))
        assertEquals(query.copy(tags = setOf("rain")), remaining)
        assertEquals(query.copy(tags = emptySet()), query.withAvailableTags(emptyList()))
    }

    @Test fun untaggedPendingNamesStillMatchTextButNeverTagFilters() {
        assertTrue(matchesTextTerms("Morning Rain", emptyList(), "morning rain"))
        assertFalse(matchesTextTerms("Morning Rain", emptyList(), "morning #rain"))
        assertFalse(matchesTagFilter(emptyList(), setOf("rain")))
    }
}
