package com.rrajath.grove.ui.agenda

import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.org.cookieDays
import com.rrajath.grove.org.cookieFirstOnly
import com.rrajath.grove.org.firstOccurrenceOnOrAfter
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.GroveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Report phases 4 and 5 (issue #97 follow-up): future repeats of SCHEDULED /
 * DEADLINE in Upcoming, deadline prewarnings ("Due soon") and the SCHEDULED
 * delay cookie. Case IDs (S8, D6, B3, ...) are the Emacs fixture cases in
 * `internal/2026-11-04-emacs-date-parity-report.md`, all viewed on Wed Nov 4 2026.
 */
class AgendaRepeatsAndWarningsTest {

    private val today = LocalDate.of(2026, 11, 4)

    private fun day(d: Int, month: Int = 11) = LocalDate.of(2026, month, d)

    private fun note(
        title: String,
        keyword: String? = "TODO",
        done: Boolean = false,
        scheduled: String? = null,
        deadline: String? = null,
        line: Int = 0,
    ) = NoteMeta(
        "a.org", line, title, keyword, done, null, emptyList(), emptyList(),
        scheduled, deadline, null, null, null, 0L, title,
    )

    private fun repeatDays(m: NoteMeta, through: LocalDate): List<Pair<LocalDate, PlanningKind>> =
        generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(through) }
            .flatMap { d -> AgendaBuckets.repeatsOn(listOf(m), d, today).map { d to it.kind } }
            .toList()

    // --- occurrence and cookie helpers ---

    @Test
    fun `firstOccurrenceOnOrAfter projects from the base with Emacs month overflow`() {
        val ts = OrgTimestamp.parse("<2026-08-31 Mon +1m>")!!
        assertEquals(LocalDate.of(2026, 8, 31), ts.firstOccurrenceOnOrAfter(LocalDate.of(2026, 1, 1)))
        assertEquals(LocalDate.of(2026, 10, 1), ts.firstOccurrenceOnOrAfter(LocalDate.of(2026, 9, 1)))
        assertEquals(LocalDate.of(2027, 3, 3), ts.firstOccurrenceOnOrAfter(LocalDate.of(2027, 2, 1)))
        assertEquals(LocalDate.of(2026, 11, 11), OrgTimestamp.parse("<2026-10-28 Wed +1w>")!!.firstOccurrenceOnOrAfter(day(5)))
        assertNull(OrgTimestamp.parse("<2026-10-28 Wed>")!!.firstOccurrenceOnOrAfter(day(5)))
    }

    @Test
    fun `cookieDays reads Emacs warning units`() {
        assertEquals(2, OrgTimestamp.parse("<2026-11-09 Mon -2d>")!!.cookieDays)
        assertEquals(7, OrgTimestamp.parse("<2026-11-09 Mon +1w -1w>")!!.cookieDays)
        assertEquals(30, OrgTimestamp.parse("<2026-11-09 Mon -1m>")!!.cookieDays)
        assertEquals(1, OrgTimestamp.parse("<2026-11-09 Mon -36h>")!!.cookieDays)
        assertNull(OrgTimestamp.parse("<2026-11-09 Mon>")!!.cookieDays)
        assertTrue(OrgTimestamp.parse("<2026-11-09 Mon --2d>")!!.cookieFirstOnly)
        assertFalse(OrgTimestamp.parse("<2026-11-09 Mon -2d>")!!.cookieFirstOnly)
    }

    // --- phase 4: future repeats ---

    @Test
    fun `S5 overdue repeating SCHEDULED previews every repeat after today, not the past ones`() {
        val m = note("S5", scheduled = "<2026-10-21 Wed +1w>")
        assertEquals(
            listOf(day(11) to PlanningKind.SCHEDULED, day(18) to PlanningKind.SCHEDULED),
            repeatDays(m, day(20)),
        )
    }

    @Test
    fun `S8 future-based repeat previews after its stored date only`() {
        val m = note("S8", scheduled = "<2026-11-06 Fri +1w>")
        assertEquals(listOf(day(13), day(20)), repeatDays(m, day(20)).map { it.first })
    }

    @Test
    fun `S9 catch-up daily repeat previews every day after today`() {
        val m = note("S9", scheduled = "<2026-11-01 Sun ++1d>")
        assertEquals(listOf(day(5), day(6), day(7)), repeatDays(m, day(7)).map { it.first })
    }

    @Test
    fun `D7 repeating DEADLINE previews its repeats`() {
        val m = note("D7", deadline = "<2026-10-28 Wed +1w>")
        assertEquals(listOf(day(11) to PlanningKind.DEADLINE), repeatDays(m, day(17)))
    }

    @Test
    fun `B3 both repeating stamps project, one row per heading per day`() {
        // SCHEDULED weekly from Oct 21 (Wed), DEADLINE weekly from Oct 30 (Fri).
        val m = note("B3", scheduled = "<2026-10-21 Wed +1w>", deadline = "<2026-10-30 Fri +1w>")
        assertEquals(
            listOf(
                day(6) to PlanningKind.DEADLINE,
                day(11) to PlanningKind.SCHEDULED,
                day(13) to PlanningKind.DEADLINE,
            ),
            repeatDays(m, day(13)),
        )
        // Same weekday: the SCHEDULED repeat takes the day's one row.
        val same = note("B3s", scheduled = "<2026-10-21 Wed +1w>", deadline = "<2026-10-28 Wed +1w>")
        assertEquals(listOf(day(11) to PlanningKind.SCHEDULED), repeatDays(same, day(11)))
    }

    @Test
    fun `a non-repeating DEADLINE next to a repeating SCHEDULED does not preview`() {
        val m = note("B7", scheduled = "<2026-10-21 Wed +1w>", deadline = "<2026-11-20 Fri>")
        assertTrue(repeatDays(m, day(30)).none { it.second == PlanningKind.DEADLINE })
    }

    @Test
    fun `S7 a done heading never previews`() {
        val m = note("S7", keyword = "DONE", done = true, scheduled = "<2026-10-21 Wed +1w>")
        assertTrue(repeatDays(m, day(30)).isEmpty())
    }

    @Test
    fun `a keyword-less repeating SCHEDULED previews like a task`() {
        val m = note("S6", keyword = null, scheduled = "<2026-10-21 Wed +1w>")
        assertEquals(listOf(day(11)), repeatDays(m, day(11)).map { it.first })
    }

    @Test
    fun `no preview on today or on the stored date`() {
        val m = note("S11", scheduled = "<2026-11-04 Wed +1d>")
        assertTrue(AgendaBuckets.repeatsOn(listOf(m), today, today).isEmpty())
        assertEquals(day(5), repeatDays(m, day(5)).single().first)
    }

    @Test
    fun `a single-dash delay holds back every repeat, a double dash only the first`() {
        val single = note("single", scheduled = "<2026-11-06 Fri +1w -2d>")
        assertTrue(repeatDays(single, day(30)).isEmpty())
        val double = note("double", scheduled = "<2026-11-06 Fri +1w --2d>")
        assertEquals(listOf(day(13), day(20), day(27)), repeatDays(double, day(30)).map { it.first })
    }

    @Test
    fun `a preview carries the projected stamp`() {
        val m = note("timed", scheduled = "<2026-10-21 Wed 09:00 +1w>")
        val r = AgendaBuckets.repeatsOn(listOf(m), day(11), today).single()
        assertEquals(day(11), r.ts.date)
        assertEquals("09:00", r.ts.time.toString())
    }

    // --- phase 5: prewarnings and delays ---

    @Test
    fun `D1 a deadline within the default window is due soon, one further out is not`() {
        val soon = note("D1", deadline = "<2026-11-09 Mon>")
        val far = note("D3", deadline = "<2026-11-24 Tue>")
        assertEquals(listOf("D1"), AgendaBuckets.dueSoon(listOf(soon, far), today, 14).map { it.title })
    }

    @Test
    fun `D6 the deadline's own cookie replaces the default window`() {
        val m = note("D6", deadline = "<2026-11-09 Mon -2d>")
        assertTrue(AgendaBuckets.dueSoon(listOf(m), today, 14).isEmpty())
        assertEquals(listOf("D6"), AgendaBuckets.dueSoon(listOf(m), day(7), 14).map { it.title })
    }

    @Test
    fun `due soon skips done headings, past and today's deadlines, and a zero window`() {
        val done = note("done", keyword = "DONE", done = true, deadline = "<2026-11-06 Fri>")
        val past = note("past", deadline = "<2026-11-01 Sun>")
        val todayDl = note("today", deadline = "<2026-11-04 Wed>")
        val soon = note("soon", deadline = "<2026-11-06 Fri>")
        assertEquals(listOf("soon"), AgendaBuckets.dueSoon(listOf(done, past, todayDl, soon), today, 14).map { it.title })
        assertTrue(AgendaBuckets.dueSoon(listOf(soon), today, 0).isEmpty())
    }

    @Test
    fun `due soon sorts soonest first`() {
        val later = note("later", deadline = "<2026-11-10 Tue>")
        val sooner = note("sooner", deadline = "<2026-11-05 Thu>")
        assertEquals(listOf("sooner", "later"), AgendaBuckets.dueSoon(listOf(later, sooner), today, 14).map { it.title })
    }

    @Test
    fun `S10 a SCHEDULED delay hides the plan until the delay has passed`() {
        val m = note("S10", scheduled = "<2026-11-03 Tue -2d>")
        assertTrue(AgendaBuckets.delayed(m, today))
        assertFalse(AgendaBuckets.delayed(m, day(5)))
        assertFalse(AgendaBuckets.delayed(note("plain", scheduled = "<2026-11-03 Tue>"), today))
    }

    // --- rows ---

    @Test
    fun `a due soon row says when the deadline falls and how far off`() {
        val m = note("D1", deadline = "<2026-11-09 Mon 17:00>")
        val row = AgendaViewModel.row(m, today, showDate = false, p = GroveSettings(), placement = RowPlacement.DueSoon)
        assertEquals(listOf("⚑ Nov 9 · in 5d", "17:00"), row.meta.map { it.text })
        assertEquals(AgendaMetaTone.DANGER, row.meta.first().tone)
    }

    @Test
    fun `a deadline preview row reads as due and acts on the deadline`() {
        val m = note("D7", keyword = null, deadline = "<2026-10-28 Wed +1w>")
        val projected = OrgTimestamp.parse("<2026-11-11 Wed +1w>")!!
        val row = AgendaViewModel.row(
            m, today, showDate = false, p = GroveSettings(),
            placement = RowPlacement.Repeat(PlanningKind.DEADLINE, projected),
        )
        assertEquals(listOf("⚑ due", "↻ +1w"), row.meta.map { it.text })
        assertEquals(PlanningKind.DEADLINE, row.repeaterKind)
        // The row still carries the stored stamp, so the Dates screen prefills the real date.
        assertEquals(LocalDate.of(2026, 10, 28), row.deadlineTs!!.date)
    }

    @Test
    fun `a scheduled preview row keeps its time and repeater`() {
        val m = note("S5", scheduled = "<2026-10-21 Wed 09:00 +1w>")
        val projected = OrgTimestamp.parse("<2026-11-11 Wed 09:00 +1w>")!!
        val row = AgendaViewModel.row(
            m, today, showDate = false, p = GroveSettings(),
            placement = RowPlacement.Repeat(PlanningKind.SCHEDULED, projected),
        )
        assertEquals(listOf("09:00", "↻ +1w"), row.meta.map { it.text })
        assertEquals(PlanningKind.SCHEDULED, row.previewKind)
    }
}
