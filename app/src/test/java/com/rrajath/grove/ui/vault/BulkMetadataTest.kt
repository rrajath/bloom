package com.rrajath.grove.ui.vault

import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkMetadataTest {

    private val doc = OrgParser.parse(
        """
        * TODO [#A] One  :work:home:
        SCHEDULED: <2026-10-05 Mon> DEADLINE: <2026-10-09 Fri>
        :PROPERTIES:
        :ID: one
        :END:
        * TODO [#B] Two  :home:
        SCHEDULED: <2026-10-05 Mon>
        """.trimIndent() + "\n"
    )

    private val summary = bulkMetadataSummary(doc.headlines)!!

    @Test
    fun `summary keeps shared values and blanks differing ones`() {
        val h = summary.headline
        assertEquals("TODO", h.keyword)
        assertFalse(summary.stateMixed)
        assertNull(h.priority)
        assertTrue(summary.priorityMixed)
        assertEquals(listOf("home"), h.tags)
        assertEquals(doc.headlines[0].planning.scheduled, h.planning.scheduled)
        assertNull(h.planning.deadline)
        assertNull("not every heading has an :ID:", h.id)
        assertEquals("2 notes", h.title)
    }

    @Test
    fun `a bulk Dates edit only sets the fields that changed`() {
        val shown = summary.headline
        val newScheduled = doc.headlines[0].planning.deadline
        val edit = BulkPlanningEdit.between(shown, newScheduled, shown.planning.deadline, shown.dedicatedActiveTimestamps)

        assertTrue(edit.setScheduled)
        assertFalse(edit.setDeadline)
        assertFalse(edit.setActive)
        // One keeps its own DEADLINE even though the sheet showed none.
        val (scheduled, deadline, _) = edit.applyTo(doc.headlines[0])
        assertEquals(newScheduled, scheduled)
        assertEquals(doc.headlines[0].planning.deadline, deadline)
    }

    @Test
    fun `confirming the Dates screen unchanged is no edit at all`() {
        val shown = summary.headline
        assertTrue(
            BulkPlanningEdit.between(shown, shown.planning.scheduled, shown.planning.deadline, shown.dedicatedActiveTimestamps).isEmpty
        )
    }
}
