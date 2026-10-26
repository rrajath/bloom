package com.rrajath.grove.ui.search

import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickStartsTest {

    private val states = listOf("TODO", "NEXT")

    @Test
    fun `cards keep the screen's order and reserved ids`() {
        assertEquals(
            listOf(
                QuickStartOverrides.OVERDUE_ID, QuickStartOverrides.TODAY_ID,
                QuickStartOverrides.OPEN_TASKS_ID, QuickStartOverrides.UNSCHEDULED_ID,
            ),
            QuickStarts.Card.entries.map { it.id },
        )
        assertEquals(QuickStartOverrides.ids, QuickStarts.Card.entries.map { it.id }.toSet())
    }

    @Test
    fun `overdue built-in ORs every active keyword across scheduled and deadline`() {
        assertEquals(
            "i.TODO s.overdue OR i.TODO d.overdue OR i.NEXT s.overdue OR i.NEXT d.overdue",
            QuickStarts.builtInQuery(QuickStarts.Card.OVERDUE, states),
        )
        assertEquals("s.overdue OR d.overdue", QuickStarts.builtInQuery(QuickStarts.Card.OVERDUE, emptyList()))
        assertNull(QuickStarts.builtInFilters(QuickStarts.Card.OVERDUE, states))
    }

    @Test
    fun `filter-preset built-ins mirror to the same query text as the Filters panel`() {
        assertEquals("s.eq.today", QuickStarts.builtInQuery(QuickStarts.Card.TODAY, states))
        assertEquals("i.next OR i.todo", QuickStarts.builtInQuery(QuickStarts.Card.OPEN_TASKS, states))
        assertEquals(
            "s.none d.none i.next OR s.none d.none i.todo",
            QuickStarts.builtInQuery(QuickStarts.Card.UNSCHEDULED, states),
        )
        QuickStarts.Card.entries.forEach { card ->
            QuickStarts.builtInFilters(card, states)?.let {
                assertEquals(FilterExpression.of(it), QuickStarts.builtInQuery(card, states))
            }
        }
    }

    @Test
    fun `query prefers the override row`() {
        val saved = listOf(SavedSearch(QuickStartOverrides.OPEN_TASKS_ID, "Open tasks", "i.todo t.work"))
        assertEquals("i.todo t.work", QuickStarts.query(QuickStarts.Card.OPEN_TASKS, saved, states))
        assertEquals("s.eq.today", QuickStarts.query(QuickStarts.Card.TODAY, saved, states))
    }
}
