package com.rrajath.grove.ui.search

import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.search.FilenameMatcher
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QueryMatcher
import com.rrajath.grove.search.QueryParser
import com.rrajath.grove.ui.nav.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SearchResultOrderTest {

    private val today = LocalDate.of(2025, 6, 11)

    private fun note(
        title: String,
        fileName: String,
        lineIndex: Int,
        scheduled: String? = null,
        active: String? = null,
        lastModified: Long = 0L,
    ) = NoteMeta(
        fileName, lineIndex, title, "TODO", false, null, emptyList(), emptyList(),
        scheduled, null, null, active, null, lastModified, title,
    )

    private fun titles(notes: List<NoteMeta>) = notes.map { it.title }

    @Test
    fun `groups by file in first-seen order keeping the matcher's order within a file`() {
        val matched = listOf(
            note("b1", "b.org", 3),
            note("a1", "a.org", 1),
            note("b2", "b.org", 1),
            note("a2", "a.org", 2),
        )
        val out = SearchResultOrder.flatHeadings(matched, QueryParser.parse("i.todo"), today)
        assertEquals(listOf("b1", "b2", "a1", "a2"), titles(out))
    }

    @Test
    fun `name-matched files come first`() {
        val matched = listOf(note("x", "other.org", 1), note("y", "groceries.org", 1))
        val out = SearchResultOrder.flatHeadings(matched, QueryParser.parse("milk"), today, nameFiles = listOf("groceries.org"))
        assertEquals(listOf("y", "x"), titles(out))
    }

    @Test
    fun `name-matched files without matching headings add nothing`() {
        val matched = listOf(note("x", "other.org", 1))
        val out = SearchResultOrder.flatHeadings(matched, QueryParser.parse("milk"), today, nameFiles = listOf("milk.org"))
        assertEquals(listOf("x"), titles(out))
    }

    @Test
    fun `file intros are dropped`() {
        val matched = listOf(note("intro", "a.org", INTRO_LINE_INDEX), note("h", "a.org", 1))
        val out = SearchResultOrder.flatHeadings(matched, QueryParser.parse("h"), today)
        assertEquals(listOf("h"), titles(out))
    }

    @Test
    fun `agenda-day queries flatten day by day with each note listed once`() {
        val matched = listOf(
            note("tomorrow", "a.org", 1, scheduled = "<2025-06-12 Thu>"),
            note("overdue", "a.org", 2, scheduled = "<2025-06-01 Sun>"),
            note("ranged", "b.org", 1, active = "<2025-06-11 Wed>--<2025-06-13 Fri>"),
        )
        val out = SearchResultOrder.flatHeadings(matched, QueryParser.parse("ad.3"), today)
        assertEquals(listOf("overdue", "ranged", "tomorrow"), titles(out))
    }

    @Test
    fun `matches the matcher's sort when one file holds everything`() {
        val notes = listOf(
            note("Call mom", "a.org", 1, lastModified = 1),
            note("call", "a.org", 2, lastModified = 0),
        )
        val query = QueryParser.parse("call")
        val matched = QueryMatcher.filter(notes, query, today)
        // Exact title match ranks above a contains match, and the flat list keeps that.
        assertEquals(listOf("call", "Call mom"), titles(SearchResultOrder.flatHeadings(matched, query, today)))
    }

    @Test
    fun `name-matched files order by quality then recency then name`() {
        val hits = listOf(
            FilenameMatcher.Hit("b.org", FilenameMatcher.Quality.CONTAINS, emptyList()),
            FilenameMatcher.Hit("c.org", FilenameMatcher.Quality.EXACT, emptyList()),
            FilenameMatcher.Hit("a.org", FilenameMatcher.Quality.CONTAINS, emptyList()),
            FilenameMatcher.Hit("d.org", FilenameMatcher.Quality.CONTAINS, emptyList()),
        )
        val recency = mapOf("a.org" to 5L, "b.org" to 5L, "d.org" to 9L)
        assertEquals(
            listOf("c.org", "d.org", "a.org", "b.org"),
            SearchResultOrder.nameMatchedFiles(hits, recency),
        )
    }

    @Test
    fun `search deep link carries the encoded query`() {
        assertEquals("bloom://search?q=t.work%20s.7d", DeepLinks.search("t.work s.7d"))
    }
}
