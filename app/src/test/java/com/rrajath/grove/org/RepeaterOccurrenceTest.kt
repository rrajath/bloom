package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Repeater projection and arithmetic, with expectations taken from real Emacs
 * 30.2 / Org 9.8 runs (`internal/emacs-date-parity/`): which days `org-agenda`
 * lists a repeating timestamp on, and where `org-todo` moves it.
 */
class RepeaterOccurrenceTest {

    private fun ts(raw: String) = OrgTimestamp.parseAll(raw).single()

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    /** Every day in [from]..[to] the stamp has an occurrence on. */
    private fun days(raw: String, from: LocalDate, to: LocalDate): List<LocalDate> {
        val stamp = ts(raw)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .filter { stamp.occurrenceCovering(it) != null }.toList()
    }

    // --- Emacs-style month/year arithmetic ---

    @Test
    fun `months and years overflow an invalid day like Emacs`() {
        assertEquals(d(2026, 3, 3), d(2026, 1, 31).plusOrgUnits(1, 'm'))
        assertEquals(d(2026, 10, 1), d(2026, 8, 31).plusOrgUnits(1, 'm'))
        assertEquals(d(2025, 3, 1), d(2024, 2, 29).plusOrgUnits(1, 'y'))
        assertEquals(d(2028, 2, 29), d(2024, 2, 29).plusOrgUnits(4, 'y'))
        assertEquals(d(2026, 2, 15), d(2026, 1, 15).plusOrgUnits(1, 'm'))
    }

    @Test
    fun `mark-done month and year shifts overflow like Emacs`() {
        val now = LocalDateTime.of(2026, 11, 4, 10, 0)
        assertEquals("<2026-03-03 Tue +1m>", ts("<2026-01-31 Sat +1m>").advanceRepeater(now).format())
        // ++ chains from each shifted date: Aug 31, Oct 1, Nov 1, Dec 1.
        assertEquals("<2026-12-01 Tue ++1m>", ts("<2026-08-31 Mon ++1m>").advanceRepeater(now).format())
        assertEquals("<2025-03-01 Sat +1y>", ts("<2024-02-29 Thu +1y>").advanceRepeater(now).format())
    }

    @Test
    fun `zero-interval and time-less hour repeaters never advance`() {
        val now = LocalDateTime.of(2026, 11, 4, 10, 0)
        assertEquals("<2026-11-04 Wed ++0d>", ts("<2026-11-04 Wed ++0d>").advanceRepeater(now).format())
        assertEquals("<2026-11-04 Wed ++2h>", ts("<2026-11-04 Wed ++2h>").advanceRepeater(now).format())
    }

    // --- agenda projection ---

    @Test
    fun `issue 97 a weekly bare timestamp recurs from its base date`() {
        assertEquals(
            listOf(d(2026, 10, 28), d(2026, 11, 4), d(2026, 11, 11)),
            days("<2026-10-28 Wed 12:00-14:00 +1w>", d(2026, 10, 20), d(2026, 11, 15)),
        )
    }

    @Test
    fun `all three repeater types display the same`() {
        val expected = days("<2026-10-28 Wed +1w>", d(2026, 10, 20), d(2026, 12, 1))
        assertEquals(expected, days("<2026-10-28 Wed ++1w>", d(2026, 10, 20), d(2026, 12, 1)))
        assertEquals(expected, days("<2026-10-28 Wed .+1w>", d(2026, 10, 20), d(2026, 12, 1)))
    }

    @Test
    fun `a monthly stamp on the 31st follows Emacs overflow`() {
        assertEquals(
            listOf(
                d(2026, 8, 31), d(2026, 10, 1), d(2026, 10, 31), d(2026, 12, 1), d(2026, 12, 31),
                d(2027, 1, 31), d(2027, 3, 3), d(2027, 3, 31), d(2027, 5, 1),
            ),
            days("<2026-08-31 Mon +1m>", d(2026, 8, 25), d(2027, 5, 1)),
        )
    }

    @Test
    fun `a yearly Feb 29 stamp lands on Mar 1 in common years`() {
        assertEquals(
            listOf(d(2024, 2, 29), d(2025, 3, 1), d(2026, 3, 1), d(2027, 3, 1), d(2028, 2, 29)),
            days("<2024-02-29 Thu +1y>", d(2024, 1, 1), d(2028, 12, 31)),
        )
    }

    @Test
    fun `a daily stamp covers every day from its base`() {
        assertEquals(
            listOf(d(2026, 11, 1), d(2026, 11, 2), d(2026, 11, 3)),
            days("<2026-11-01 Sun +1d>", d(2026, 10, 30), d(2026, 11, 3)),
        )
    }

    @Test
    fun `hour repeaters cover each day an hourly start lands on`() {
        assertEquals(
            listOf(d(2026, 11, 4), d(2026, 11, 5), d(2026, 11, 6)),
            days("<2026-11-04 Wed 09:00 +2h>", d(2026, 11, 3), d(2026, 11, 6)),
        )
        assertEquals(
            listOf(d(2026, 11, 4), d(2026, 11, 6), d(2026, 11, 8)),
            days("<2026-11-04 Wed 09:00 +48h>", d(2026, 11, 3), d(2026, 11, 8)),
        )
    }

    @Test
    fun `a repeating range repeats as a block`() {
        val range = ts("<2026-10-29 Thu +1w>--<2026-10-30 Fri>")
        assertEquals(d(2026, 11, 5), range.occurrenceCovering(d(2026, 11, 5)))
        assertEquals(d(2026, 11, 5), range.occurrenceCovering(d(2026, 11, 6)))
        assertNull(range.occurrenceCovering(d(2026, 11, 7)))
    }

    @Test
    fun `a repeater followed by a warning cookie still repeats`() {
        assertEquals(d(2026, 11, 4), ts("<2026-10-28 Wed +1w -1d>").occurrenceCovering(d(2026, 11, 4)))
    }

    @Test
    fun `plain and zero-interval stamps cover their own span only`() {
        assertNull(ts("<2026-10-28 Wed>").occurrenceCovering(d(2026, 11, 4)))
        assertNull(ts("<2026-10-28 Wed +0w>").occurrenceCovering(d(2026, 11, 4)))
        assertEquals(d(2026, 10, 28), ts("<2026-10-28 Wed +0w>").occurrenceCovering(d(2026, 10, 28)))
    }

    @Test
    fun `nothing is projected before the base date`() {
        assertNull(ts("<2026-11-11 Wed +1w>").occurrenceCovering(d(2026, 11, 4)))
    }

    @Test
    fun `occursBetween finds a repeat inside a window`() {
        val weekly = ts("<2026-10-28 Wed +1w>")
        assertTrue(weekly.occursBetween(d(2026, 11, 2), d(2026, 11, 8)))
        assertFalse(weekly.occursBetween(d(2026, 11, 5), d(2026, 11, 10)))
        assertFalse(ts("<2026-10-28 Wed>").occursBetween(d(2026, 11, 2), d(2026, 11, 8)))
    }

    // --- text shifting ---

    @Test
    fun `shiftRepeaters moves only repeating active stamps and keeps the rest`() {
        val now = LocalDateTime.of(2026, 11, 4, 10, 0)
        assertEquals(
            "Bins <2026-11-05 Thu +1w>, [2026-10-29 Thu +1w] and <2026-10-29 Thu>.",
            OrgTimestamp.shiftRepeaters("Bins <2026-10-29 Thu +1w>, [2026-10-29 Thu +1w] and <2026-10-29 Thu>.", now),
        )
    }
}
