package com.rrajath.grove.ui.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PropertyKeySuggestTest {

    /** [text] with `|` marking the caret: returns the trigger there, if any. */
    private fun triggerAt(text: String): PropertyKeyTrigger? {
        val caret = text.indexOf('|')
        return propertyKeyTriggerAt(text.removeRange(caret, caret + 1), TextRange(caret))
    }

    private fun rank(text: String, vault: Map<String, Int>, extra: List<String> = emptyList()): List<String> =
        rankPropertyKeys(vault, extra, triggerAt(text)!!)

    @Test
    fun `triggers on a colon and partial key inside a drawer`() {
        val t = triggerAt("* H\n:PROPERTIES:\n:C|\n:END:")
        assertNotNull(t)
        assertEquals("C", t!!.prefix)
        assertEquals(TextRange(17, 19), t.range)
    }

    @Test
    fun `a bare colon triggers with an empty prefix`() {
        assertEquals("", triggerAt(":PROPERTIES:\n:|\n:END:")?.prefix)
    }

    @Test
    fun `indented keys trigger`() {
        assertEquals("cu", triggerAt("** H\n  :PROPERTIES:\n  :cu|\n  :END:")?.prefix)
    }

    @Test
    fun `no trigger outside a drawer`() {
        assertNull(triggerAt("* H\n:C|"))
        assertNull(triggerAt(":PROPERTIES:\n:ID: x\n:END:\n:C|"))
        assertNull(triggerAt(":LOGBOOK:\n:C|\n:END:"))
        assertNull(triggerAt(":PROPERTIES:\n* H\n:C|"))
    }

    @Test
    fun `no trigger once the key is closed, mid-line, or with text after the caret`() {
        assertNull(triggerAt(":PROPERTIES:\n:ID:|\n:END:"))
        assertNull(triggerAt(":PROPERTIES:\n:ID: x :C|\n:END:"))
        assertNull(triggerAt(":PROPERTIES:\n:C|USTOM_ID:\n:END:"))
        assertNull(triggerAt(":PROPERTIES:\nx:C|\n:END:"))
    }

    @Test
    fun `no trigger for a selection`() {
        assertNull(propertyKeyTriggerAt(":PROPERTIES:\n:C\n:END:", TextRange(13, 15)))
    }

    @Test
    fun `collects keys already set above and below, uppercased`() {
        val t = triggerAt(":PROPERTIES:\n:id: 1\n:|\n:Effort+: 2\n:END:\n:CATEGORY: x")
        assertEquals(setOf("ID", "EFFORT"), t?.drawerKeys)
    }

    @Test
    fun `filters by prefix ignoring case, most used first`() {
        val vault = mapOf("CUSTOM_ID" to 40, "CREATED" to 90, "CLOSED_BY" to 1, "ID" to 300)
        assertEquals(listOf("CREATED", "CUSTOM_ID", "CLOSED_BY"), rank(":PROPERTIES:\n:c|\n:END:", vault))
    }

    @Test
    fun `keys already in the drawer drop out`() {
        val vault = mapOf("CUSTOM_ID" to 40, "CREATED" to 90)
        assertEquals(listOf("CUSTOM_ID"), rank(":PROPERTIES:\n:created: x\n:C|\n:END:", vault))
    }

    @Test
    fun `extra keys rank after used ones, alphabetically, and keep the vault spelling`() {
        val vault = mapOf("Effort" to 3)
        assertEquals(
            listOf("Effort", "CATEGORY", "EXTRA"),
            rank(":PROPERTIES:\n:|\n:END:", vault, extra = listOf("EXTRA", "EFFORT", "CATEGORY")),
        )
    }

    @Test
    fun `drawer markers are never offered`() {
        assertEquals(emptyList<String>(), rank(":PROPERTIES:\n:|\n:END:", mapOf("END" to 5, "PROPERTIES" to 2)))
    }

    @Test
    fun `insertion closes the key and adds a space`() {
        assertEquals(":CUSTOM_ID: ", formatPropertyKeyInsertion("CUSTOM_ID"))
    }

    @Test
    fun `buffer keys come only from property drawers`() {
        val text = ":PROPERTIES:\n:ID: 1\n:END:\n* H\n:FOO: not a drawer\n:PROPERTIES:\n:Bar+: x\n:END:\n:LOGBOOK:\n:BAZ: y\n:END:"
        assertEquals(setOf("ID", "Bar"), propertyKeysIn(text))
    }
}
