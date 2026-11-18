package com.rrajath.grove.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropertyKeysTest {

    @Test
    fun `join drops append markers, blanks and duplicates`() {
        assertEquals("ID FOO", joinPropertyKeys(listOf("ID", "FOO+", "FOO", "+")))
        assertNull(joinPropertyKeys(emptyList()))
    }

    @Test
    fun `counts drawers per key case-insensitively under the most used spelling`() {
        val counts = countPropertyKeys(listOf("ID CUSTOM_ID", "ID custom_id", "ID CUSTOM_ID", "Effort"))
        assertEquals(mapOf("ID" to 3, "CUSTOM_ID" to 3, "Effort" to 1), counts)
    }

    @Test
    fun `spelling ties go to the alphabetically first`() {
        assertEquals(mapOf("EFFORT" to 2), countPropertyKeys(listOf("effort", "EFFORT")))
    }
}
