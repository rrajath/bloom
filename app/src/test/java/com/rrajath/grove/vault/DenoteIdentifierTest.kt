package com.rrajath.grove.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** File-name parsing and vault-wide matching behind `[[denote:…]]` links. */
class DenoteIdentifierTest {

    @Test
    fun `identifier leads a standard denote file name`() {
        assertEquals("20240101T120000", denoteIdentifier("20240101T120000--trip-plan__travel_japan.org"))
    }

    @Test
    fun `identifier is found in a nested path`() {
        assertEquals("20240101T120000", denoteIdentifier("notes/journal/20240101T120000--trip-plan.org"))
    }

    @Test
    fun `identifier stops at a signature, title or keywords component`() {
        assertEquals("20240101T120000", denoteIdentifier("20240101T120000==1a--title.org"))
        assertEquals("20240101T120000", denoteIdentifier("20240101T120000__travel.org"))
        assertEquals("20240101T120000", denoteIdentifier("20240101T120000.org"))
    }

    @Test
    fun `identifier marked with @@ can appear after other components`() {
        assertEquals("20240101T120000", denoteIdentifier("--trip-plan@@20240101T120000__travel.org"))
        assertEquals("20240101T120000", denoteIdentifier("@@20240101T120000--trip-plan.org"))
    }

    @Test
    fun `a name that starts with another component has no identifier`() {
        assertNull(denoteIdentifier("--trip-plan__travel.org"))
        assertNull(denoteIdentifier("__travel.org"))
    }

    private fun nb(fileName: String) = Notebook(fileName, noteCount = 1, lastModified = 0L)

    private val notebooks = listOf(
        nb("inbox.org"),
        nb("20231231T090000--older-note.org"),
        nb("work/20240101T120000--trip-plan__travel.org"),
        nb("archive/2023/--moved@@20240202T080000__misc.org"),
    )

    @Test
    fun `matches a note anywhere in the vault`() {
        assertEquals(
            "work/20240101T120000--trip-plan__travel.org",
            matchDenoteNotebook("20240101T120000", notebooks)?.fileName,
        )
        assertEquals(
            "archive/2023/--moved@@20240202T080000__misc.org",
            matchDenoteNotebook("20240202T080000", notebooks, preferredDir = "work")?.fileName,
        )
    }

    @Test
    fun `an identifier that is only a prefix does not match`() {
        assertNull(matchDenoteNotebook("20240101T12", notebooks))
        assertNull(matchDenoteNotebook("20240101", notebooks))
    }

    @Test
    fun `an unknown or blank identifier matches nothing`() {
        assertNull(matchDenoteNotebook("19990101T000000", notebooks))
        assertNull(matchDenoteNotebook("  ", notebooks))
    }

    @Test
    fun `a duplicated identifier prefers the linking file's directory`() {
        val dupes = listOf(
            nb("a/20240101T120000--copy.org"),
            nb("b/20240101T120000--original.org"),
        )
        assertEquals("b/20240101T120000--original.org", matchDenoteNotebook("20240101T120000", dupes, "b")?.fileName)
        assertEquals("a/20240101T120000--copy.org", matchDenoteNotebook("20240101T120000", dupes, "c")?.fileName)
    }
}
