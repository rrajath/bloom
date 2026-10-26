package com.rrajath.grove.widget

import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaMetaTone
import com.rrajath.grove.ui.search.QuickStarts
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

    private val names = WidgetViews.QuickStartName { card ->
        when (card) {
            QuickStarts.Card.OVERDUE -> "Overdue"
            QuickStarts.Card.TODAY -> "Today"
            QuickStarts.Card.OPEN_TASKS -> "Open tasks"
            QuickStarts.Card.UNSCHEDULED -> "Unscheduled"
        }
    }
    private val states = listOf("TODO", "NEXT")

    private fun items(selectedId: String?, searches: List<SavedSearch> = saved) =
        WidgetViews.pickerItems("Agenda", "Overdue + next 14 days", searches, states, names, selectedId)

    private fun resolve(selectedId: String?, searches: List<SavedSearch> = saved) =
        WidgetViews.resolve(selectedId, searches, states, names)

    @Test
    fun `picker lists Agenda, then the quick starts, then saved searches in saved order`() {
        assertEquals(
            listOf("Agenda", "Overdue", "Today", "Open tasks", "Unscheduled", "Work", "This Week"),
            items(null).map { it.label },
        )
        assertEquals(
            listOf(
                null, QuickStartOverrides.OVERDUE_ID, QuickStartOverrides.TODAY_ID,
                QuickStartOverrides.OPEN_TASKS_ID, QuickStartOverrides.UNSCHEDULED_ID, "s-work", "s-week",
            ),
            items(null).map { it.searchId },
        )
    }

    @Test
    fun `override rows show once, as their quick start, not as a saved search`() {
        val ids = items(null).mapNotNull { it.searchId }
        assertEquals(ids.distinct(), ids)
        assertEquals(1, ids.count { it == QuickStartOverrides.TODAY_ID })
    }

    @Test
    fun `subtitles are the agenda window and each view's query`() {
        val subtitles = items(null).associate { it.label to it.subtitle }
        assertEquals("Overdue + next 14 days", subtitles["Agenda"])
        assertEquals("t.work", subtitles["Work"])
        // Today is overridden by the s.today row; Open tasks runs its built-in.
        assertEquals("s.today", subtitles["Today"])
        assertEquals("i.next OR i.todo", subtitles["Open tasks"])
    }

    @Test
    fun `picker marks exactly the current selection`() {
        assertEquals(listOf(false, false, false, false, false, false, true), items("s-week").map { it.selected })
    }

    @Test
    fun `a quick start can be the current selection`() {
        val selected = items(QuickStartOverrides.UNSCHEDULED_ID).single { it.selected }
        assertEquals("Unscheduled", selected.label)
    }

    @Test
    fun `no stored selection marks Agenda`() {
        assertEquals(listOf(true), items(null).filter { it.selected }.map { it.searchId == null })
    }

    @Test
    fun `deleted saved search falls back to Agenda`() {
        assertNull(resolve("s-gone"))
        assertEquals(null, items("s-gone").single { it.selected }.searchId)
    }

    @Test
    fun `quick start resolves to its override query when one exists`() {
        val view = resolve(QuickStartOverrides.TODAY_ID)
        assertEquals("Today", view?.name)
        assertEquals("s.today", view?.query)
    }

    @Test
    fun `quick start resolves to its built-in query without an override`() {
        val view = resolve(QuickStartOverrides.TODAY_ID, searches = listOf(work))
        assertEquals("s.eq.today", view?.query)
    }

    @Test
    fun `resolve finds the search by id so a rename shows the new name`() {
        val renamed = listOf(work.copy(name = "Office"), week)
        assertEquals("Office", resolve("s-work", renamed)?.name)
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
