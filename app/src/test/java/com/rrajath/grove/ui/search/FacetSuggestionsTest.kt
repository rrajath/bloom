package com.rrajath.grove.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetSuggestionsTest {
    private val pool = listOf("archive.org", "inbox.org", "journal.org", "Work.org", "networking.org", "homework.org")

    @Test fun `prefix matches come before substring matches`() {
        assertEquals(listOf("Work.org", "networking.org", "homework.org"), facetSuggestions(pool, "wo", emptySet()))
    }

    @Test fun `matching ignores case`() {
        assertEquals(listOf("inbox.org"), facetSuggestions(pool, "INB", emptySet()))
    }

    @Test fun `blank query suggests nothing`() {
        assertTrue(facetSuggestions(pool, "  ", emptySet()).isEmpty())
    }

    @Test fun `already shown chips are left out`() {
        assertEquals(listOf("networking.org", "homework.org"), facetSuggestions(pool, "wo", setOf("Work.org")))
    }

    @Test fun `query is trimmed`() {
        assertEquals(listOf("journal.org"), facetSuggestions(pool, " jour ", emptySet()))
    }

    @Test fun `limit caps the list`() {
        assertEquals(1, facetSuggestions(pool, "o", emptySet(), limit = 1).size)
    }

    @Test fun `typeahead kicks in at the threshold`() {
        assertFalse(useFacetTypeahead(TYPEAHEAD_THRESHOLD - 1))
        assertTrue(useFacetTypeahead(TYPEAHEAD_THRESHOLD))
    }

    @Test fun `shown chips keep pick order and add active ones not yet picked`() {
        assertEquals(listOf("b", "a", "c"), shownFacetChips(listOf("b", "a"), setOf("a", "c")))
    }
}
