package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagPolicyTest {
    @Test fun normalizeRemovesControlsNormalizesUnicodeAndCollapsesWhitespace() {
        assertEquals("가나", TagPolicy.normalizeTag("##가\t\u0000나  "))
        assertNull(TagPolicy.normalizeTag(" ## \n "))
        assertNull(TagPolicy.normalizeTag("a".repeat(TagPolicy.MAX_TAG_CHARS + 1)))
    }

    @Test fun normalizeTagsDeduplicatesCaseInsensitivelyAndKeepsFirstTen() {
        val tags = TagPolicy.normalizeTags(listOf("Night", "night", " Day ") + (1..12).map { "tag$it" })
        assertEquals("Night", tags.first())
        assertEquals("Day", tags[1])
        assertEquals(TagPolicy.MAX_TAGS, tags.size)
        assertEquals("night", TagPolicy.key("NIGHT"))
    }
}
