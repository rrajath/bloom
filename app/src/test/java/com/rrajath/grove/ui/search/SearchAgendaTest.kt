package com.rrajath.grove.ui.search

import com.rrajath.grove.search.NoteMeta
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SearchAgendaTest {

    // Wednesday.
    private val today = LocalDate.of(2025, 6, 11)

    private fun note(
        title: String,
        scheduled: String? = null,
        deadline: String? = null,
        active: String? = null,
    ) = NoteMeta(
        "notes.org", 0, title, "TODO", false, null, emptyList(), emptyList(),
        scheduled, deadline, null, active, null, 0L, title,
    )

    private fun layout(vararg notes: NoteMeta, days: Int = 7): List<Pair<String, List<String>>> =
        SearchAgenda.build(notes.toList(), today, days).map { d -> d.label to d.entries.map { it.note.title } }

    @Test
    fun `overdue comes first, then each day in order, empty days left out`() {
        assertEquals(
            listOf(
                "Overdue" to listOf("Old"),
                "Today, Jun 11" to listOf("Now"),
                "Friday, Jun 13" to listOf("Later"),
            ),
            layout(
                note("Later", scheduled = "<2025-06-13 Fri>"),
                note("Now", deadline = "<2025-06-11 Wed>"),
                note("Old", scheduled = "<2025-06-02 Mon>"),
            ),
        )
    }

    @Test
    fun `timed entries come first in time order, untimed after by title`() {
        assertEquals(
            listOf("Today, Jun 11" to listOf("Early", "Late", "Alpha", "Zulu")),
            layout(
                note("Zulu", scheduled = "<2025-06-11 Wed>"),
                note("Late", scheduled = "<2025-06-11 Wed 18:00>"),
                note("Alpha", scheduled = "<2025-06-11 Wed>"),
                note("Early", active = "<2025-06-11 Wed 08:30>"),
            ),
        )
    }

    @Test
    fun `events land on every window day they cover and never go overdue`() {
        assertEquals(
            listOf(
                "Today, Jun 11" to listOf("Trip"),
                "Tomorrow, Jun 12" to listOf("Trip"),
            ),
            layout(note("Trip", active = "<2025-06-09 Mon>--<2025-06-12 Thu>"), note("Past", active = "<2025-06-01 Sun>")),
        )
    }

    @Test
    fun `a repeating event lands on each occurrence in the window`() {
        assertEquals(
            listOf("Today, Jun 11" to listOf("Weekly"), "Wednesday, Jun 18" to listOf("Weekly")),
            layout(note("Weekly", active = "<2025-05-28 Wed 12:00 +1w>"), days = 8),
        )
        // The time belongs to each occurrence's own day.
        val entries = SearchAgenda.build(listOf(note("Weekly", active = "<2025-05-28 Wed 12:00 +1w>")), today, 8)
        assertEquals(listOf(java.time.LocalTime.of(12, 0), java.time.LocalTime.of(12, 0)), entries.map { it.entries.single().time })
    }

    @Test
    fun `a heading lands on its scheduled day, or its deadline when scheduled is past the window`() {
        assertEquals(
            listOf(
                "Tomorrow, Jun 12" to listOf("Both"),
                "Saturday, Jun 14" to listOf("Far"),
            ),
            layout(
                note("Both", scheduled = "<2025-06-12 Thu>", deadline = "<2025-06-14 Sat>"),
                note("Far", scheduled = "<2025-07-01 Tue>", deadline = "<2025-06-14 Sat>"),
            ),
        )
    }

    @Test
    fun `the window is N days starting today`() {
        val edge = note("Edge", scheduled = "<2025-06-13 Fri>")
        assertEquals(listOf("Friday, Jun 13" to listOf("Edge")), layout(edge, days = 3))
        assertEquals(emptyList<Pair<String, List<String>>>(), layout(edge, days = 2))
        assertEquals(emptyList<Pair<String, List<String>>>(), layout(note("Event", active = "<2025-06-12 Thu>"), days = 1))
    }
}
