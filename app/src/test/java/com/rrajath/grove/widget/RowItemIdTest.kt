package com.rrajath.grove.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowItemIdTest {

    // Glance's internal `ReservedItemIdRangeEnd` (glance-appwidget 1.2.0).
    private val reservedEnd = -0x4000_0000_0000_0000L

    @Test
    fun `ids are stable and outside Glance's reserved range`() {
        assertEquals(rowItemId("inbox.org", 12), rowItemId("inbox.org", 12))
        for (line in 0 until 2_000) {
            assertTrue(rowItemId("journal/2026.org", line) > reservedEnd)
        }
    }

    @Test
    fun `ids that the old 32-bit scheme collided on now differ`() {
        // "Aa" and "BB" share a String.hashCode, so the old fileName.hashCode() * 31 + line collided.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(rowItemId("Aa", 0), rowItemId("BB", 0))
    }

    @Test
    fun `no collisions across a large vault`() {
        val ids = HashSet<Long>()
        for (file in 0 until 500) for (line in 0 until 200) ids += rowItemId("notes/file-$file.org", line)
        assertEquals(500 * 200, ids.size)
    }
}
