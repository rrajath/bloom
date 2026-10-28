package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

class OrgTimestampTest {

    @Test
    fun `parses active date`() {
        val ts = OrgTimestamp.parse("<2025-04-30 Wed>")!!
        assertEquals(LocalDate.of(2025, 4, 30), ts.date)
        assertTrue(ts.active)
        assertNull(ts.time)
        assertNull(ts.repeater)
    }

    @Test
    fun `parses inactive datetime`() {
        val ts = OrgTimestamp.parse("[2025-06-11 Wed 14:32]")!!
        assertFalse(ts.active)
        assertEquals(LocalTime.of(14, 32), ts.time)
    }

    @Test
    fun `parses time range`() {
        val ts = OrgTimestamp.parse("<2025-05-01 Thu 10:00-11:30>")!!
        assertEquals(LocalTime.of(10, 0), ts.time)
        assertEquals(LocalTime.of(11, 30), ts.endTime)
    }

    @Test
    fun `parses repeaters of all types`() {
        assertEquals(
            Repeater(RepeaterType.CUMULATIVE, 1, 'w'),
            OrgTimestamp.parse("<2025-04-30 Wed +1w>")!!.repeater,
        )
        assertEquals(
            Repeater(RepeaterType.CATCH_UP, 2, 'd'),
            OrgTimestamp.parse("<2025-04-30 Wed ++2d>")!!.repeater,
        )
        assertEquals(
            Repeater(RepeaterType.FUTURE, 3, 'm'),
            OrgTimestamp.parse("<2025-04-30 Wed .+3m>")!!.repeater,
        )
    }

    @Test
    fun `parses repeater with warning`() {
        val ts = OrgTimestamp.parse("<2025-05-01 Thu 10:00-11:30 ++2d -1d>")!!
        assertEquals(Repeater(RepeaterType.CATCH_UP, 2, 'd'), ts.repeater)
        assertEquals("-1d", ts.warning)
    }

    @Test
    fun `parses without day name`() {
        val ts = OrgTimestamp.parse("<2025-04-30>")!!
        assertEquals(LocalDate.of(2025, 4, 30), ts.date)
    }

    @Test
    fun `rejects garbage and mismatched brackets`() {
        assertNull(OrgTimestamp.parse("no timestamp here"))
        assertNull(OrgTimestamp.parse("<2025-13-45 Xxx>"))
        assertNull(OrgTimestamp.parse("<2025-04-30 Wed]"))
    }

    @Test
    fun `rejects out-of-range hour or minute instead of throwing`() {
        // Regression test: LocalTime.of(25, 0) / LocalTime.of(10, 99) throw
        // DateTimeException. The regex's \d{1,2}:\d{2} happily matches these
        // (a hand-typo'd time, or a >24h CLOCK duration cookie that looks like
        // a clock time), so parsing must reject the whole timestamp the same
        // way it already does for an out-of-range date, rather than letting
        // the exception escape into callers like the agenda ledger widget's
        // provideGlance (which had no try/catch and surfaced as the system's
        // generic "Can't show content" widget fallback).
        assertNull(OrgTimestamp.parse("<2025-04-30 Wed 25:00>"))
        assertNull(OrgTimestamp.parse("<2025-04-30 Wed 10:99>"))
        assertNull(OrgTimestamp.parse("<2025-04-30 Wed 10:00-25:30>"))
    }

    @Test
    fun `format emits canonical org form`() {
        assertEquals("<2025-04-30 Wed>", OrgTimestamp.parse("<2025-04-30 Wed>")!!.format())
        assertEquals("[2025-06-11 Wed 14:32]", OrgTimestamp.parse("[2025-06-11 Wed 14:32]")!!.format())
        assertEquals(
            "<2025-05-01 Thu 10:00-11:30 ++2d -1d>",
            OrgTimestamp.parse("<2025-05-01 Thu 10:00-11:30 ++2d -1d>")!!.format(),
        )
        // Day name corrected to the actual weekday
        assertEquals("<2025-04-30 Wed>", OrgTimestamp.parse("<2025-04-30 Mon>")!!.format())
    }

    @Test
    fun `formatHuman drops brackets and day name`() {
        val today = LocalDate.of(2025, 4, 1)
        fun human(text: String) = OrgTimestamp.parse(text)!!.formatHuman(today)

        assertEquals("Apr 30", human("<2025-04-30 Wed>"))
        assertEquals("Jun 11 14:32", human("[2025-06-11 Wed 14:32]"))
        assertEquals("May 1 10:00-11:30", human("<2025-05-01 Thu 10:00-11:30>"))
    }

    @Test
    fun `formatHuman keeps the year only outside the current one`() {
        val ts = OrgTimestamp.parse("<2025-04-30 Wed>")!!
        assertEquals("Apr 30", ts.formatHuman(today = LocalDate.of(2025, 12, 31)))
        assertEquals("Apr 30, 2025", ts.formatHuman(today = LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `formatHuman keeps repeater and warning cookies`() {
        assertEquals(
            "May 1 10:00-11:30 ++2d -1d",
            OrgTimestamp.parse("<2025-05-01 Thu 10:00-11:30 ++2d -1d>")!!
                .formatHuman(today = LocalDate.of(2025, 4, 1)),
        )
    }

    @Test
    fun `cumulative repeater advances by one interval`() {
        val ts = OrgTimestamp.parse("<2025-04-30 Wed +1w>")!!
        val advanced = ts.advanceRepeater(now = LocalDate.of(2025, 6, 1).atStartOfDay())
        assertEquals(LocalDate.of(2025, 5, 7), advanced.date)
    }

    @Test
    fun `catch-up repeater jumps past today`() {
        val ts = OrgTimestamp.parse("<2025-04-30 Wed ++1w>")!!
        val advanced = ts.advanceRepeater(now = LocalDate.of(2025, 6, 1).atStartOfDay())
        assertTrue(advanced.date.isAfter(LocalDate.of(2025, 6, 1)))
        // Stays on the same weekday
        assertEquals(ts.date.dayOfWeek, advanced.date.dayOfWeek)
        assertEquals(LocalDate.of(2025, 6, 4), advanced.date)
    }

    @Test
    fun `future repeater shifts from today`() {
        val ts = OrgTimestamp.parse("<2025-04-30 Wed .+2d>")!!
        val advanced = ts.advanceRepeater(now = LocalDate.of(2025, 6, 1).atStartOfDay())
        assertEquals(LocalDate.of(2025, 6, 3), advanced.date)
    }

    @Test
    fun `future hour repeater restarts from the completion time`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 10:00 .+2h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 26, 12, 0))
        assertEquals("<2026-09-26 Sat 14:00 .+2h>", advanced.format())
    }

    @Test
    fun `future hour repeater keeps the completion minutes and rolls past midnight`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 10:00 .+2h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 26, 23, 17, 42))
        assertEquals("<2026-09-27 Sun 01:17 .+2h>", advanced.format())
    }

    @Test
    fun `cumulative hour repeater adds one interval to the old time`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 10:00 +2h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 26, 18, 0))
        assertEquals("<2026-09-26 Sat 12:00 +2h>", advanced.format())
    }

    @Test
    fun `cumulative hour repeater crossing midnight advances the date`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 23:00 +2h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 26, 23, 30))
        assertEquals("<2026-09-27 Sun 01:00 +2h>", advanced.format())
    }

    @Test
    fun `catch-up hour repeater jumps past now in whole intervals`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 10:00 ++2h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 26, 16, 30))
        assertEquals("<2026-09-26 Sat 18:00 ++2h>", advanced.format())
    }

    @Test
    fun `catch-up hour repeater landing exactly on now moves one more interval`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 22:00 ++2h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 27, 2, 0))
        assertEquals("<2026-09-27 Sun 04:00 ++2h>", advanced.format())
    }

    @Test
    fun `hour repeater shifts the end time with the start`() {
        val ts = OrgTimestamp.parse("<2026-09-26 Sat 10:00-11:30 .+3h>")!!
        val advanced = ts.advanceRepeater(now = LocalDateTime.of(2026, 9, 26, 12, 0))
        assertEquals("<2026-09-26 Sat 15:00-16:30 .+3h>", advanced.format())
    }

    @Test
    fun `non-repeating timestamp is unchanged`() {
        val ts = OrgTimestamp.parse("<2025-04-30 Wed>")!!
        assertEquals(ts, ts.advanceRepeater(LocalDate.of(2025, 6, 1).atStartOfDay()))
    }

    @Test
    fun `parseWithRange reports the matched span`() {
        val text = "SCHEDULED: <2025-04-09 Wed> trailing"
        val (ts, range) = OrgTimestamp.parseWithRange(text)!!
        assertNotNull(ts)
        assertEquals("<2025-04-09 Wed>", text.substring(range))
    }

    @Test
    fun `parseAll returns a single bare timestamp`() {
        val all = OrgTimestamp.parseAll("meeting <2026-09-08 Mon> in the body")
        assertEquals(1, all.size)
        assertEquals(LocalDate.of(2026, 9, 8), all[0].date)
        assertNull(all[0].rangeEnd)
    }

    @Test
    fun `parseAll returns multiple timestamps across prose`() {
        val all = OrgTimestamp.parseAll("first <2026-09-08 Mon> then later <2026-09-10 Wed>")
        assertEquals(2, all.size)
        assertEquals(LocalDate.of(2026, 9, 8), all[0].date)
        assertEquals(LocalDate.of(2026, 9, 10), all[1].date)
    }

    @Test
    fun `parseAll pairs a range into one timestamp`() {
        val all = OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed>")
        assertEquals(1, all.size)
        assertEquals(LocalDate.of(2026, 9, 8), all[0].date)
        assertEquals(LocalDate.of(2026, 9, 10), all[0].rangeEnd)
    }

    @Test
    fun `parseAll parses a range then a following standalone stamp`() {
        val all = OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed> and <2026-09-20 Sat>")
        assertEquals(2, all.size)
        assertEquals(LocalDate.of(2026, 9, 10), all[0].rangeEnd)
        assertEquals(LocalDate.of(2026, 9, 20), all[1].date)
        assertNull(all[1].rangeEnd)
    }

    @Test
    fun `parseAll ignores inactive stamps only when filtered by caller`() {
        val all = OrgTimestamp.parseAll("[2026-09-08 Mon] <2026-09-09 Tue>")
        assertEquals(2, all.size)
        assertEquals(listOf(false, true), all.map { it.active })
    }

    @Test
    fun `format round-trips a range`() {
        val ts = OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed>").single()
        assertEquals("<2026-09-08 Tue>--<2026-09-10 Thu>", ts.format())
    }

    @Test
    fun `formatHuman shows a range with an en dash`() {
        val ts = OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed>").single()
        assertEquals("Sep 8 – Sep 10", ts.formatHuman(today = LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `format splits a timed cross-day range across the two brackets`() {
        val ts = OrgTimestamp(
            date = LocalDate.of(2026, 9, 11),
            time = LocalTime.of(9, 0),
            endTime = LocalTime.of(12, 0),
            rangeEnd = LocalDate.of(2026, 9, 12),
        )
        assertEquals("<2026-09-11 Fri 09:00>--<2026-09-12 Sat 12:00>", ts.format())
    }

    @Test
    fun `parseAll reads the end time off the closing bracket of a range`() {
        val ts = OrgTimestamp.parseAll("<2026-09-11 Fri 09:00>--<2026-09-12 Sat 12:00>").single()
        assertEquals(LocalTime.of(9, 0), ts.time)
        assertEquals(LocalTime.of(12, 0), ts.endTime)
        assertEquals(LocalDate.of(2026, 9, 12), ts.rangeEnd)
    }

    @Test
    fun `parseAll still reads a legacy joined end time on the opening bracket`() {
        val ts = OrgTimestamp.parseAll("<2026-09-11 Fri 09:00-12:00>--<2026-09-12 Sat>").single()
        assertEquals(LocalTime.of(12, 0), ts.endTime)
        assertEquals(LocalDate.of(2026, 9, 12), ts.rangeEnd)
        // …and re-emits it in the canonical split form.
        assertEquals("<2026-09-11 Fri 09:00>--<2026-09-12 Sat 12:00>", ts.format())
    }

    @Test
    fun `formatHuman puts the end time on the last date of a cross-day range`() {
        val ts = OrgTimestamp.parseAll("<2026-09-11 Fri 09:00>--<2026-09-12 Sat 12:00>").single()
        assertEquals("Sep 11 09:00 – Sep 12 12:00", ts.formatHuman(today = LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `advanceRepeater shifts the range end by the same delta`() {
        val ts = OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed>").single()
            .copy(repeater = Repeater(RepeaterType.CUMULATIVE, 1, 'w'))
        val advanced = ts.advanceRepeater(now = LocalDate.of(2026, 9, 1).atStartOfDay())
        assertEquals(LocalDate.of(2026, 9, 15), advanced.date)
        assertEquals(LocalDate.of(2026, 9, 17), advanced.rangeEnd)
    }

    @Test
    fun `written day name follows the device locale like Emacs`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // Stand-alone form: glibc's %a writes "Mi", not CLDR's in-sentence "Mi.".
            assertEquals("<2025-06-11 Mi>", OrgTimestamp(LocalDate.of(2025, 6, 11)).format())
            Locale.setDefault(Locale.FRANCE)
            assertEquals("<2025-06-11 mer.>", OrgTimestamp(LocalDate.of(2025, 6, 11)).format())
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `parses timestamps whose day name is in another language`() {
        assertEquals(LocalDate.of(2025, 6, 11), OrgTimestamp.parse("<2025-06-11 Mi 14:00>")!!.date)
        assertEquals(LocalTime.of(14, 0), OrgTimestamp.parse("<2025-06-11 mer. 14:00>")!!.time)
    }
}
