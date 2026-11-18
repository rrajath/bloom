package com.rrajath.grove.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocIdMapTest {

    @Test fun `case-sensitive maps only find the exact key`() {
        val m = DocIdMap(caseInsensitive = false, mapOf("Inbox.org" to "1"))
        assertEquals("Inbox.org", m.keyFor("Inbox.org"))
        assertNull(m.keyFor("inbox.org"))
    }

    @Test fun `case-insensitive maps find the cached spelling`() {
        val m = DocIdMap(caseInsensitive = true, mapOf("Notes/Inbox.org" to "1"))
        assertEquals("Notes/Inbox.org", m.keyFor("notes/INBOX.org"))
        m["Projects/Acme.org"] = "2"
        assertEquals("Projects/Acme.org", m.keyFor("projects/acme.ORG"))
        assertNull(m.keyFor("projects/other.org"))
    }

    @Test fun `removal drops the folded entry, keeping other spellings`() {
        val m = DocIdMap(caseInsensitive = true)
        m["A.org"] = "1"
        m["a.org"] = "2"
        m.remove("A.org")
        assertEquals("a.org", m.keyFor("A.ORG"))
        m.removeKeysIf { it.endsWith(".org") }
        assertNull(m.keyFor("a.org"))
        assertFalse("a.org" in m)
        assertTrue(m.keys.isEmpty())
    }

    @Test fun `fold agrees with equals ignoreCase`() {
        val samples = listOf("ß", "SS", "İ", "i", "I", "ı", "Σ", "σ", "ς", "ǅ", "ǆ", "Ǆ", "K", "k", "K", "Å", "å", "Å")
        for (a in samples) for (b in samples) {
            assertEquals("$a vs $b", a.equals(b, ignoreCase = true), DocIdMap.fold(a) == DocIdMap.fold(b))
        }
    }
}
