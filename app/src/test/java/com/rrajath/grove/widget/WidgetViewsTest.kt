package com.rrajath.grove.widget

import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaMetaTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetViewsTest {

    private val today = LocalDate.of(2025, 6, 11)

    private val work = SavedSearch("s-work", "Work", "t.work")
    private val week = SavedSearch("s-week", "This Week", "s.7d")
    private val override = SavedSearch(QuickStartOverrides.TODAY_ID, "Today", "s.today")
    private val saved = listOf(work, override, week)

    private fun note(
        title: String,
        lineIndex: Int = 1,
        fileName: String = "notes.org",
        keyword: String? = "TODO",
        done: Boolean = false,
        scheduled: String? = null,
    ) = NoteMeta(
        fileName, lineIndex, title, keyword, done, null, emptyList(), emptyList(),
        scheduled, null, null, null, null, 0L, title,
    )

    @Test
    fun `picker lists Agenda first then saved searches in saved order`() {
        val items = WidgetViews.pickerItems("Agenda", saved, selectedId = null)
        assertEquals(listOf("Agenda", "Work", "This Week"), items.map { it.label })
        assertEquals(listOf(null, "s-work", "s-week"), items.map { it.searchId })
    }

    @Test
    fun `picker hides quick start override rows`() {
        val items = WidgetViews.pickerItems("Agenda", saved, selectedId = null)
        assertTrue(items.none { it.searchId in QuickStartOverrides.ids })
    }

    @Test
    fun `picker marks exactly the current selection`() {
        val items = WidgetViews.pickerItems("Agenda", saved, selectedId = "s-week")
        assertEquals(listOf(false, false, true), items.map { it.selected })
    }

    @Test
    fun `no stored selection marks Agenda`() {
        val items = WidgetViews.pickerItems("Agenda", saved, selectedId = null)
        assertEquals(listOf(true, false, false), items.map { it.selected })
    }

    @Test
    fun `deleted saved search falls back to Agenda`() {
        assertNull(WidgetViews.resolve("s-gone", saved))
        val items = WidgetViews.pickerItems("Agenda", saved, selectedId = "s-gone")
        assertEquals(listOf(true, false, false), items.map { it.selected })
    }

    @Test
    fun `selection naming a quick start override falls back to Agenda`() {
        assertNull(WidgetViews.resolve(QuickStartOverrides.TODAY_ID, saved))
    }

    @Test
    fun `resolve finds the search by id so a rename shows the new name`() {
        val renamed = listOf(work.copy(name = "Office"), week)
        assertEquals("Office", WidgetViews.resolve("s-work", renamed)?.name)
    }

    @Test
    fun `search rows keep the given order and carry a date chip`() {
        val headings = listOf(
            note("B task", lineIndex = 2, scheduled = "<2025-06-12 Thu>"),
            note("A task", lineIndex = 1, scheduled = "<2025-06-11 Wed>"),
        )
        val rows = WidgetViews.searchRows(headings, today, GroveSettings())
        assertEquals(listOf("B task", "A task"), rows.map { it.title })
        // Unlike a day-section row, every row names its own date.
        assertTrue(rows.all { row -> row.meta.any { it.tone == AgendaMetaTone.NORMAL } })
    }

    @Test
    fun `search results truncate at the row cap with a hidden count`() {
        val headings = (1..25).map { note("Task $it", lineIndex = it) }
        val rows = WidgetViews.searchRows(headings, today, GroveSettings())
        val (visible, hidden) = LedgerBuckets.truncate(rows, 20)
        assertEquals(20, visible.size)
        assertEquals(5, hidden)
        assertEquals("Task 1", visible.first().title)
        assertEquals("Task 20", visible.last().title)
    }

    @Test
    fun `done headings stay in search results`() {
        val rows = WidgetViews.searchRows(listOf(note("Shipped", keyword = "DONE", done = true)), today, GroveSettings())
        assertEquals(1, rows.size)
        assertTrue(rows.single().isDone)
    }
}
