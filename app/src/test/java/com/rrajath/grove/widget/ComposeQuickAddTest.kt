package com.rrajath.grove.widget

import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ComposeQuickAddTest {

    private val created = LocalDateTime.of(2026, 11, 8, 9, 30, 45)

    private fun compose(
        base: String = "",
        path: List<String> = emptyList(),
        priority: Char? = null,
        date: OrgTimestamp? = null,
        id: String? = null,
        createdAt: LocalDateTime? = null,
    ) = composeQuickAdd(base, path, "TODO Buy milk", OrgKeywords.DEFAULT, priority, date, id, createdAt)

    @Test
    fun `no properties when both settings are off`() {
        val (text, found) = compose()
        assertTrue(found)
        assertEquals("* TODO Buy milk", text.trimEnd())
    }

    @Test
    fun `adds ID when the setting is on`() {
        val (text, _) = compose(id = "abc-123")
        assertEquals(
            "* TODO Buy milk\n:PROPERTIES:\n:ID: abc-123\n:END:",
            text.trimEnd(),
        )
    }

    @Test
    fun `adds ID and CREATED in one drawer`() {
        val (text, _) = compose(id = "abc-123", createdAt = created)
        assertEquals(
            "* TODO Buy milk\n:PROPERTIES:\n:ID: abc-123\n:CREATED: [2026-11-08 Sun 09:30]\n:END:",
            text.trimEnd(),
        )
    }

    @Test
    fun `drawer follows priority and SCHEDULED planning line`() {
        val date = OrgTimestamp(LocalDate.of(2026, 11, 9))
        val (text, _) = compose(priority = 'A', date = date, id = "abc-123")
        val lines = text.trimEnd().lines()
        assertEquals("* TODO [#A] Buy milk", lines[0])
        assertTrue(lines[1].startsWith("SCHEDULED: <2026-11-09"))
        assertEquals(listOf(":PROPERTIES:", ":ID: abc-123", ":END:"), lines.subList(2, 5))
    }

    @Test
    fun `ID lands on the new child under a heading path`() {
        val base = "* Inbox\n* Other\n"
        val (text, found) = compose(base = base, path = listOf("Inbox"), id = "abc-123")
        assertTrue(found)
        assertEquals(
            "* Inbox\n** TODO Buy milk\n:PROPERTIES:\n:ID: abc-123\n:END:\n* Other",
            text.trimEnd(),
        )
    }
}
