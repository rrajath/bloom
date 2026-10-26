package com.rrajath.grove.org

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatOccurrenceTest {
    private val today = LocalDate.of(2026, 10, 1)

    @Test fun `ordinal suffixes`() {
        val d = { day: Int -> formatOccurrence(LocalDate.of(2026, 6, day), today) }
        assertEquals("Jun 1st", d(1))
        assertEquals("Jun 2nd", d(2))
        assertEquals("Jun 3rd", d(3))
        assertEquals("Jun 5th", d(5))
        assertEquals("Jun 11th", d(11))
        assertEquals("Jun 12th", d(12))
        assertEquals("Jun 13th", d(13))
        assertEquals("Jun 21st", d(21))
        assertEquals("Jun 22nd", d(22))
        assertEquals("Jun 23rd", d(23))
    }

    @Test fun `year shown only across a year boundary`() {
        assertEquals("Marked done. Next occurrence Dec 31st", markedDoneMessage(LocalDate.of(2026, 12, 31), today))
        assertEquals("Marked done. Next occurrence Jan 3rd, 2027", markedDoneMessage(LocalDate.of(2027, 1, 3), today))
    }

    @Test fun `non-recurring mark done says just Marked done`() {
        assertEquals("Marked done", markedDoneMessage(null, today))
    }

    @Test fun `next occurrence of a repeating scheduled task after markDone`() {
        val doc = OrgParser.parse("* TODO Water plants\nSCHEDULED: <2026-10-01 Thu +1w>\n")
        val h = doc.headlines.first()
        val text = OrgMutations.markDone(doc, h, "DONE", LocalDateTime.of(2026, 10, 1, 9, 0))
        val after = OrgParser.parse(text).headlines.first()
        assertEquals(LocalDate.of(2026, 10, 8), nextRepeatOccurrence(h, after))
    }

    @Test fun `no next occurrence for a plain task`() {
        val doc = OrgParser.parse("* TODO Once\nSCHEDULED: <2026-10-01 Thu>\n")
        val h = doc.headlines.first()
        val text = OrgMutations.markDone(doc, h, "DONE", LocalDateTime.of(2026, 10, 1, 9, 0))
        assertNull(nextRepeatOccurrence(h, OrgParser.parse(text).headlines.first()))
    }

    private val now = LocalDateTime.of(2026, 10, 1, 9, 0)

    /** Runs a real [OrgMutations.changeKeyword] on the first heading of [text] and diffs it. */
    private fun advancedBy(text: String, keyword: String?): AdvancedPlanning {
        val doc = OrgParser.parse(text)
        val h = doc.headlines.first()
        val after = OrgParser.parse(OrgMutations.changeKeyword(doc, h, keyword, doc.keywords, now)).headlines.first()
        return advancedRepeatingPlanning(h, after, keyword, doc.keywords)
    }

    @Test fun `done on a repeating scheduled task reports only scheduled`() {
        val a = advancedBy("* TODO Water plants\nSCHEDULED: <2026-10-01 Thu +1w>\n", "DONE")
        assertEquals(AdvancedPlanning(scheduled = true, deadline = false), a)
    }

    @Test fun `done on a repeating deadline task reports only deadline`() {
        val a = advancedBy("* TODO Pay rent\nDEADLINE: <2026-10-01 Thu +1m>\n", "DONE")
        assertEquals(AdvancedPlanning(scheduled = false, deadline = true), a)
    }

    @Test fun `done with both repeating stamps reports both`() {
        val a = advancedBy(
            "* TODO Review\nSCHEDULED: <2026-10-01 Thu +1w> DEADLINE: <2026-10-03 Sat +1w>\n", "DONE",
        )
        assertEquals(AdvancedPlanning(scheduled = true, deadline = true), a)
    }

    @Test fun `a non-repeating stamp beside a repeating one is not reported`() {
        val a = advancedBy(
            "* TODO Review\nSCHEDULED: <2026-10-01 Thu +1w> DEADLINE: <2026-10-20 Tue>\n", "DONE",
        )
        assertEquals(AdvancedPlanning(scheduled = true, deadline = false), a)
    }

    @Test fun `done on a non-repeating task reports nothing`() {
        val a = advancedBy("* TODO Once\nSCHEDULED: <2026-10-01 Thu> DEADLINE: <2026-10-02 Fri>\n", "DONE")
        assertFalse(a.any)
    }

    @Test fun `a non-done keyword reports nothing even with a repeater`() {
        assertFalse(advancedBy("* TODO Water plants\nSCHEDULED: <2026-10-01 Thu +1w>\n", "IN-PROGRESS").any)
        assertFalse(advancedBy("* TODO Water plants\nSCHEDULED: <2026-10-01 Thu +1w>\n", null).any)
    }

    @Test fun `an unchanged repeating stamp is not reported`() {
        val h = OrgParser.parse("* TODO Water plants\nSCHEDULED: <2026-10-01 Thu +1w>\n").headlines.first()
        assertFalse(advancedRepeatingPlanning(h, h, "DONE", OrgKeywords.DEFAULT).any)
    }
}
