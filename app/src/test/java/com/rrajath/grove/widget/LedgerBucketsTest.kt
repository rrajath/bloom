package com.rrajath.grove.widget

import com.rrajath.grove.testing.support.english

import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaMetaTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LedgerBucketsTest {

    private val today = LocalDate.of(2025, 6, 11)
    private val settings = GroveSettings()

    private fun note(
        title: String,
        fileName: String = "notes.org",
        keyword: String? = "TODO",
        done: Boolean = false,
        priority: String? = null,
        scheduled: String? = null,
        deadline: String? = null,
        active: String? = null,
    ) = NoteMeta(
        fileName, 0, title, keyword, done, priority, emptyList(), emptyList(),
        scheduled, deadline, null, active, null, 0L, title,
    )

    @Test
    fun `days with no tasks are omitted entirely`() {
        val notes = listOf(
            note("Today task", scheduled = "<2025-06-11 Wed>"),
            note("Far task", scheduled = "<2025-06-20 Fri>"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        // No section for every day in between that has nothing scheduled.
        assertEquals(listOf("Today · Jun 11", "Jun 20"), sections.map { it.label.english() })
    }

    @Test
    fun `section keys are stable and untranslated`() {
        val notes = listOf(
            note("Ancient", scheduled = "<2025-01-01 Wed>"),
            note("Today task", scheduled = "<2025-06-11 Wed>"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(listOf("overdue", "2025-06-11"), sections.map { it.key })
        assertEquals(listOf(true, false), sections.map { it.isOverdue })
    }

    @Test
    fun `overdue section always comes first and is unbounded by the window`() {
        val notes = listOf(
            note("Ancient", scheduled = "<2025-01-01 Wed>"),
            note("Today task", scheduled = "<2025-06-11 Wed>"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals("Overdue", sections.first().label.english())
        assertEquals(1, sections.first().count)
        assertEquals(listOf("Ancient"), sections.first().rows.map { it.title })
    }

    @Test
    fun `overdue header does not repeat the count in the label`() {
        val notes = listOf(note("Ancient", scheduled = "<2025-01-01 Wed>"))
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals("Overdue", sections.first().label.english())
    }

    @Test
    fun `within a day, priority A beats B beats C beats no priority`() {
        val notes = listOf(
            note("None", scheduled = "<2025-06-11 Wed>", priority = null),
            note("C", scheduled = "<2025-06-11 Wed>", priority = "C"),
            note("A", scheduled = "<2025-06-11 Wed>", priority = "A"),
            note("B", scheduled = "<2025-06-11 Wed>", priority = "B"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(listOf("A", "B", "C", "None"), sections.first().rows.map { it.title })
    }

    @Test
    fun `within the same priority, timed tasks sort by time`() {
        val notes = listOf(
            note("Late", scheduled = "<2025-06-11 Wed 14:00>", priority = "A"),
            note("Early", scheduled = "<2025-06-11 Wed 09:00>", priority = "A"),
            note("Untimed", scheduled = "<2025-06-11 Wed>", priority = "A"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(listOf("Early", "Late", "Untimed"), sections.first().rows.map { it.title })
    }

    @Test
    fun `today and tomorrow get a date suffix, other days are bare dates`() {
        val notes = listOf(
            note("T0", scheduled = "<2025-06-11 Wed>"),
            note("T1", scheduled = "<2025-06-12 Thu>"),
            note("T2", scheduled = "<2025-06-13 Fri>"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(listOf("Today · Jun 11", "Tomorrow · Jun 12", "Jun 13"), sections.map { it.label.english() })
    }

    @Test
    fun `a day beyond the window is excluded`() {
        val notes = listOf(note("Far out", scheduled = "<2025-07-01 Tue>"))
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertTrue(sections.isEmpty())
    }

    @Test
    fun `a heading with no keyword still lands in its day bucket`() {
        val notes = listOf(note("Just scheduled", keyword = null, scheduled = "<2025-06-11 Wed>"))
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(1, sections.first().rows.size)
        assertEquals(null, sections.first().rows.first().keyword)
    }

    @Test
    fun `a bare-timestamp event is woven into its day section`() {
        val notes = listOf(
            note("Ship it", scheduled = "<2025-06-13 Fri>"),
            note("Team offsite", keyword = null, active = "<2025-06-13 Fri>"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        val friday = sections.single { it.label.english() == "Jun 13" }
        assertEquals(2, friday.count)
        val event = friday.rows.single { it.title == "Team offsite" }
        assertEquals(null, event.keyword)
        // The "Jun 13" section header already names the day: no repeated `●` chip.
        assertTrue(event.meta.none { it.tone == AgendaMetaTone.EVENT })
    }

    @Test
    fun `a day with only an event still renders`() {
        val notes = listOf(note("Team offsite", keyword = null, active = "<2025-06-13 Fri>"))
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(listOf("Jun 13"), sections.map { it.label.english() })
        assertEquals(1, sections.single().count)
    }

    @Test
    fun `a multi-day event shows once per covered day`() {
        val notes = listOf(note("Conference", keyword = null, active = "<2025-06-12 Thu>--<2025-06-14 Sat>"))
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals(
            listOf("Tomorrow · Jun 12", "Jun 13", "Jun 14"),
            sections.filter { it.rows.any { r -> r.title == "Conference" } }.map { it.label.english() },
        )
    }

    @Test
    fun `an event never appears in the Overdue section`() {
        val notes = listOf(
            note("Ancient task", scheduled = "<2025-01-01 Wed>"),
            note("Past party", keyword = null, active = "<2025-01-01 Wed>"),
        )
        val sections = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
        assertEquals("Overdue", sections.first().label.english())
        assertEquals(listOf("Ancient task"), sections.first().rows.map { it.title })
    }

    @Test
    fun `within a day a timed event sorts by its time among the no-priority rows`() {
        val notes = listOf(
            note("Priority task", scheduled = "<2025-06-13 Fri 15:00>", priority = "A"),
            note("Early event", keyword = null, active = "<2025-06-13 Fri 09:00>"),
            note("Late event", keyword = null, active = "<2025-06-13 Fri 18:00>"),
        )
        val rows = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings)
            .single { it.label.english() == "Jun 13" }.rows
        assertEquals(listOf("Priority task", "Early event", "Late event"), rows.map { it.title })
    }

    @Test
    fun `truncate keeps everything and reports zero hidden when under the cap`() {
        val notes = listOf(note("Ancient", scheduled = "<2025-01-01 Wed>"))
        val rows = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings).first().rows
        val (visible, hidden) = LedgerBuckets.truncate(rows, max = 20)
        assertEquals(rows, visible)
        assertEquals(0, hidden)
    }

    @Test
    fun `truncate caps a section that would blow the widget's RemoteViews payload`() {
        // Regression test: a stale/misclassified index (e.g. the KILL-keyword
        // cold-start race) can pile hundreds of notes into one section; an
        // uncapped LazyColumn ships every row inline over a single binder call
        // and Android's ~1MB transaction limit turns that into a silent
        // "Can't show content" placeholder with nothing logged.
        val notes = (1..443).map { note("Overdue #$it", scheduled = "<2025-01-01 Wed>") }
        val rows = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings).first().rows
        assertEquals(443, rows.size)

        val (visible, hidden) = LedgerBuckets.truncate(rows, max = 20)
        assertEquals(20, visible.size)
        assertEquals(423, hidden)
        // Truncation keeps the front of the (already-sorted) list, not an
        // arbitrary subset, so the "+N more" row genuinely refers to what's cut.
        assertEquals(rows.take(20), visible)
    }

    @Test
    fun `truncate at exactly the cap reports zero hidden`() {
        val notes = (1..20).map { note("Overdue #$it", scheduled = "<2025-01-01 Wed>") }
        val rows = LedgerBuckets.build(notes, today, windowDays = 14, settings = settings).first().rows
        val (visible, hidden) = LedgerBuckets.truncate(rows, max = 20)
        assertEquals(20, visible.size)
        assertEquals(0, hidden)
    }

    @Test
    fun `widget-only tags, filename, and priority toggles are independent of the in-app Agenda screen's`() {
        val note = note("Task", scheduled = "<2025-06-11 Wed>", priority = "A", fileName = "Work/Projects/notes.org")
        val allOn = settings.copy(
            agendaWidgetShowTags = true, agendaWidgetShowFileName = true, agendaWidgetShowPriority = true,
            agendaShowTags = false, agendaShowFile = false,
        )
        val row = LedgerBuckets.build(listOf(note), today, windowDays = 14, settings = allOn).first().rows.first()
        assertEquals("A", row.priority)
        assertTrue(row.meta.any { it.text.english() == "W/P/notes.org" })

        val allOff = settings.copy(agendaWidgetShowTags = false, agendaWidgetShowFileName = false, agendaWidgetShowPriority = false)
        val rowOff = LedgerBuckets.build(listOf(note), today, windowDays = 14, settings = allOff).first().rows.first()
        assertEquals(null, rowOff.priority)
        assertTrue(rowOff.meta.none { it.text.english().contains("notes.org") })
    }

    @Test
    fun `contractFileName collapses nested folders to their first letter, case preserved`() {
        assertEquals("W/P/meeting-notes.org", LedgerBuckets.contractFileName("Work/Projects/meeting-notes.org"))
        assertEquals("notes.org", LedgerBuckets.contractFileName("notes.org"))
    }
}
