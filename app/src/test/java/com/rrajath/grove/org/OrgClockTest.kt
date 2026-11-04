package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class OrgClockTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private fun at(s: String) = LocalDateTime.parse(s)

    // --- reading a CLOCK line, as org-element does ---

    @Test
    fun `parses running, closed and naked clocks`() {
        val running = OrgClock.parse("CLOCK: [2026-10-30 Fri 09:15]", 3)!!
        assertEquals(at("2026-10-30T09:15"), running.start)
        assertNull(running.end)
        assertTrue(running.isRunning)
        assertEquals(3, running.lineIndex)

        val closed = OrgClock.parse("  CLOCK: [2026-10-30 Fri 09:15]--[2026-10-30 Fri 10:30] =>  1:15", 0)!!
        assertEquals(at("2026-10-30T10:30"), closed.end)
        assertFalse(closed.isRunning)

        val naked = OrgClock.parse("CLOCK: => 2:05", 0)!!
        assertEquals(125, naked.nakedMinutes)
        assertFalse(naked.isRunning)
    }

    @Test
    fun `lines org does not read as clocks are not clocks`() {
        // A range without its duration cookie isn't a clock element in Org.
        assertNull(OrgClock.parse("CLOCK: [2026-10-30 Fri 09:15]--[2026-10-30 Fri 10:30]", 0))
        assertNull(OrgClock.parse("CLOCK: <2026-10-30 Fri 09:15>", 0))
        assertNull(OrgClock.parse("CLOCK: [2026-10-30 Fri 09:15]--[2026-10-30 Fri 10:30] =>  1:15 extra", 0))
        assertNull(OrgClock.parse("CLOCK:[2026-10-30 Fri 09:15]", 0))
        assertNull(OrgClock.parse("- CLOCK: [2026-10-30 Fri 09:15]", 0))
    }

    @Test
    fun `a date-only start counts from midnight`() {
        assertEquals(at("2026-10-30T00:00"), OrgClock.parse("CLOCK: [2026-10-30 Fri]", 0)!!.start)
    }

    @Test
    fun `clocks are read from the whole section but not from blocks or descendants`() {
        val doc = OrgParser.parse(
            """
            * Task
            :LOGBOOK:
            CLOCK: [2026-10-30 Fri 09:00]--[2026-10-30 Fri 10:00] =>  1:00
            :END:
            CLOCK: [2026-10-29 Thu 09:00]--[2026-10-29 Thu 09:30] =>  0:30
            #+begin_example
            CLOCK: [2026-10-28 Wed 09:00]--[2026-10-28 Wed 17:00] =>  8:00
            #+end_example
            #+BEGIN_QUOTE
            unterminated, so this is a paragraph and the next line is a clock
            CLOCK: [2026-10-27 Tue 09:00]--[2026-10-27 Tue 09:10] =>  0:10
            ** Child
            CLOCK: [2026-10-26 Mon 09:00]--[2026-10-26 Mon 09:05] =>  0:05
            """.trimIndent(),
        )
        val task = doc.headlines[0]
        assertEquals(listOf(2, 4, 10), doc.clocks(task).map { it.lineIndex })
        assertEquals(100, doc.clockedMinutes(task, zone = utc, includeSubtree = false))
        assertEquals(105, doc.clockedMinutes(task, zone = utc))
    }

    // --- org-clock-sum ---

    @Test
    fun `minutes recompute from the stamps, clipped to a range, floored, positive only`() {
        val c = OrgClock(0, at("2026-10-30T09:00"), at("2026-10-30T11:30"))
        assertEquals(150, c.minutes(zone = utc))
        assertEquals(90, c.minutes(from = at("2026-10-30T10:00"), zone = utc))
        assertEquals(60, c.minutes(from = at("2026-10-30T10:00"), to = at("2026-10-30T11:00"), zone = utc))
        assertEquals(0, c.minutes(from = at("2026-10-30T12:00"), zone = utc))
        assertEquals(0, OrgClock(0, at("2026-10-30T11:00"), at("2026-10-30T10:00")).minutes(zone = utc))
    }

    @Test
    fun `a naked duration counts in full and a running clock only when asked`() {
        assertEquals(90, OrgClock(0, null, null, nakedMinutes = 90).minutes(from = at("2030-01-01T00:00"), zone = utc))
        val running = OrgClock(0, at("2026-10-30T09:00"), null)
        assertEquals(0, running.minutes(zone = utc))
        assertEquals(45, running.minutes(zone = utc, runningUntil = at("2026-10-30T09:45")))
    }

    @Test
    fun `minutes follow the wall clock across a DST change`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:00 EST springs forward to 03:00 EDT.
        assertEquals(60, OrgClock(0, at("2026-03-08T01:30"), at("2026-03-08T03:30")).minutes(zone = ny))
    }

    @Test
    fun `the displayed total adds the running clock's whole span`() {
        val doc = OrgParser.parse(
            """
            * Task
            :LOGBOOK:
            CLOCK: [2026-10-30 Fri 11:00]
            CLOCK: [2026-10-30 Fri 09:00]--[2026-10-30 Fri 10:00] =>  1:00
            :END:
            """.trimIndent(),
        )
        assertEquals(90, doc.clockDisplayMinutes(doc.headlines[0], now = at("2026-10-30T11:30"), zone = utc))
    }

    // --- org-clock-get-sum-start ---

    @Test
    fun `the mode-line total counts from LAST_REPEAT unless CLOCK_MODELINE_TOTAL says otherwise`() {
        fun h(props: String) = OrgParser.parse("* Task\n:PROPERTIES:\n$props\n:END:\n").headlines[0]
        val now = at("2026-10-30T15:00")
        assertNull(clockTotalStart(OrgParser.parse("* Task\n").headlines[0], now))
        assertEquals(at("2026-10-28T08:30"), clockTotalStart(h(":LAST_REPEAT: [2026-10-28 Wed 08:30]"), now))
        assertEquals(at("2026-10-30T00:00"), clockTotalStart(h(":CLOCK_MODELINE_TOTAL: today"), now))
        assertEquals(now, clockTotalStart(h(":CLOCK_MODELINE_TOTAL: current"), now))
        assertNull(clockTotalStart(h(":CLOCK_MODELINE_TOTAL: all\n:LAST_REPEAT: [2026-10-28 Wed 08:30]"), now))
        assertNull(clockTotalStart(h(":CLOCK_MODELINE_TOTAL: repeat"), now))
    }

    // --- writing ---

    @Test
    fun `durations are formatted as org-clock-out writes them`() {
        assertEquals(" 0:00", OrgClock.cookie(0))
        assertEquals(" 1:15", OrgClock.cookie(75))
        assertEquals("12:05", OrgClock.cookie(725))
        assertEquals("100:05", OrgClock.cookie(6005))
        assertEquals("0:05", OrgClock.hmm(5))
        assertEquals("27:00", OrgClock.hmm(1620))
    }

    @Test
    fun `closing keeps the start stamp as written`() {
        assertEquals(
            "  CLOCK: [2026-10-30 Fr 09:15]--[2026-10-30 Fri 10:30] =>  1:15",
            OrgClock.closedLine("  CLOCK: [2026-10-30 Fr 09:15]  ", at("2026-10-30T10:30:42"), utc),
        )
        assertNull(OrgClock.closedLine("CLOCK: [2026-10-30 Fri 09:15]--[2026-10-30 Fri 10:30] =>  1:15", at("2026-10-30T11:00"), utc))
    }

    @Test
    fun `an end before the start closes the clock at zero instead of negative`() {
        assertEquals(
            "CLOCK: [2026-10-30 Fri 09:15]--[2026-10-30 Fri 09:15] =>  0:00",
            OrgClock.closedLine("CLOCK: [2026-10-30 Fri 09:15]", at("2026-10-30T09:10"), utc),
        )
    }

    // --- OrgMutations ---

    private val keywords = OrgKeywords.parse("TODO NEXT | DONE")

    @Test
    fun `clocking in on a heading whose clock runs resumes it`() {
        val doc = OrgParser.parse("* TODO Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n", keywords)
        assertNull(OrgMutations.clockIn(doc, doc.headlines[0], at("2026-10-30T10:00")))
    }

    @Test
    fun `clocking in can switch the state first`() {
        val doc = OrgParser.parse("* TODO Task\n", keywords)
        assertEquals(
            "* NEXT Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n",
            OrgMutations.clockIn(doc, doc.headlines[0], at("2026-10-30T09:00"), switchTo = "NEXT"),
        )
        // Already in that state: only the clock line.
        val next = OrgParser.parse("* NEXT Task\n", keywords)
        assertEquals(
            "* NEXT Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n",
            OrgMutations.clockIn(next, next.headlines[0], at("2026-10-30T09:00"), switchTo = "NEXT"),
        )
    }

    @Test
    fun `clock out and cancel pick the open clock by its start`() {
        val doc = OrgParser.parse(
            "* Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 11:00]\nCLOCK: [2026-10-29 Thu 09:00]\n:END:\n",
        )
        val h = doc.headlines[0]
        assertEquals(
            "* Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 11:00]\n" +
                "CLOCK: [2026-10-29 Thu 09:00]--[2026-10-29 Thu 17:00] =>  8:00\n:END:\n",
            OrgMutations.clockOut(doc, h, at("2026-10-29T17:00"), removeZero = true, start = at("2026-10-29T09:00"), zone = utc),
        )
        assertEquals(
            "* Task\n:LOGBOOK:\nCLOCK: [2026-10-29 Thu 09:00]\n:END:\n",
            OrgMutations.cancelClock(doc, h, start = at("2026-10-30T11:00")),
        )
        assertNull(OrgMutations.clockOut(doc, h, at("2026-10-30T12:00"), removeZero = true, start = at("2026-10-28T09:00")))
        val none = OrgParser.parse("* Task\n")
        assertNull(OrgMutations.cancelClock(none, none.headlines[0]))
    }

    @Test
    fun `marking done clocks out first`() {
        val doc = OrgParser.parse("* TODO Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n", keywords)
        val done = OrgMutations.markDone(doc, doc.headlines[0], "DONE", at("2026-10-30T10:30"))
        assertEquals(
            "* DONE Task\nCLOSED: [2026-10-30 Fri 10:30]\n:LOGBOOK:\n" +
                "- State \"DONE\"       from \"TODO\"       [2026-10-30 Fri 10:30]\n" +
                "CLOCK: [2026-10-30 Fri 09:00]--[2026-10-30 Fri 10:30] =>  1:30\n:END:\n",
            done,
        )
    }

    @Test
    fun `marking a repeating task done clocks out too`() {
        val doc = OrgParser.parse(
            "* TODO Task\nSCHEDULED: <2026-10-30 Fri +1d>\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n",
            keywords,
        )
        val after = OrgParser.parse(OrgMutations.markDone(doc, doc.headlines[0], "DONE", at("2026-10-30T09:45")), keywords)
        val h = after.headlines[0]
        assertEquals("TODO", h.keyword)
        assertNull(after.runningClock(h))
        assertEquals(45, after.clockedMinutes(h))
    }

    @Test
    fun `marking done leaves the clock running when told to`() {
        val doc = OrgParser.parse("* TODO Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n", keywords)
        val text = OrgMutations.markDone(doc, doc.headlines[0], "DONE", at("2026-10-30T10:30"), ClockOutPolicy(whenDone = false))
        assertNotNull(OrgParser.parse(text, keywords).let { it.runningClock(it.headlines[0]) })
    }

    @Test
    fun `a zero-minute clock closed by marking done is discarded by default`() {
        val doc = OrgParser.parse("* TODO Task\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 09:00]\n:END:\n", keywords)
        val text = OrgMutations.markDone(doc, doc.headlines[0], "DONE", at("2026-10-30T09:00"))
        assertFalse(text.contains("CLOCK:"))
    }
}
