package com.rrajath.grove.capture

import com.rrajath.grove.org.NotePlacement
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeTest {

    @Test
    fun `url uses the fetched page title as the link description`() {
        val note = ShareIntake.composeNote(
            SharedPayload(text = "", url = "https://example.com/post"),
            resolvedTitle = "A Great Post",
        )
        assertEquals("[[https://example.com/post][A Great Post]]", note.heading)
        assertNull(note.body)
    }

    @Test
    fun `url falls back to shared text then the url when no title resolved`() {
        val withSubject = ShareIntake.composeNote(
            SharedPayload(text = "Subject line", url = "https://example.com"),
            resolvedTitle = null,
        )
        assertEquals("[[https://example.com][Subject line]]", withSubject.heading)

        val bare = ShareIntake.composeNote(
            SharedPayload(text = "", url = "https://example.com"),
            resolvedTitle = null,
        )
        assertEquals("[[https://example.com][https://example.com]]", bare.heading)
    }

    @Test
    fun `square brackets in the title are neutralised so the link stays valid`() {
        val note = ShareIntake.composeNote(
            SharedPayload(text = "", url = "https://example.com"),
            resolvedTitle = "Guide [2026] to [things]",
        )
        assertEquals("[[https://example.com][Guide (2026) to (things)]]", note.heading)
    }

    @Test
    fun `long text becomes an empty heading with the text as body`() {
        val long = "x".repeat(ShareIntake.TEXT_HEADING_LIMIT + 1)
        val note = ShareIntake.composeNote(SharedPayload(text = long, url = ""), resolvedTitle = null)
        assertEquals("", note.heading)
        assertEquals(long, note.body)
    }

    @Test
    fun `short text becomes the heading`() {
        val note = ShareIntake.composeNote(
            SharedPayload(text = "Quick thought", url = ""),
            resolvedTitle = null,
        )
        assertEquals("Quick thought", note.heading)
        assertNull(note.body)
    }

    @Test
    fun `text at exactly the limit stays a heading`() {
        val exact = "y".repeat(ShareIntake.TEXT_HEADING_LIMIT)
        val note = ShareIntake.composeNote(SharedPayload(text = exact, url = ""), resolvedTitle = null)
        assertEquals(exact, note.heading)
        assertNull(note.body)
    }

    private val placeDoc = OrgParser.parse(
        "#+TITLE: Inbox\n* Projects\nnotes\n** Reading\n*** Old\n* Later\n"
    )
    private val shared = ShareIntake.Note(heading = "New link", body = null)
    private val noOptions = OrgMutations.NewNoteOptions()

    @Test
    fun `place at the top of the file lands below the preface`() {
        val placed = ShareIntake.place(placeDoc, emptyList(), NotePlacement.TOP, shared, noOptions)
        assertTrue(placed.headingFound)
        assertEquals("#+TITLE: Inbox\n* New link\n* Projects\n", placed.text.substringBefore("notes"))
    }

    @Test
    fun `place at the bottom of the file appends a top-level heading`() {
        val placed = ShareIntake.place(placeDoc, emptyList(), NotePlacement.BOTTOM, shared, noOptions)
        assertTrue(placed.text.endsWith("* Later\n* New link\n"))
    }

    @Test
    fun `place under a heading at the top makes the first subheading`() {
        val placed = ShareIntake.place(placeDoc, listOf("Projects", "Reading"), NotePlacement.TOP, shared, noOptions)
        assertTrue(placed.headingFound)
        assertTrue(placed.text.contains("** Reading\n*** New link\n*** Old\n"))
    }

    @Test
    fun `place under a heading at the bottom makes the last subheading`() {
        val placed = ShareIntake.place(placeDoc, listOf("Projects"), NotePlacement.BOTTOM, shared, noOptions)
        assertTrue(placed.text.contains("*** Old\n** New link\n* Later\n"))
    }

    @Test
    fun `a missing heading falls back to the file at the chosen placement`() {
        val placed = ShareIntake.place(placeDoc, listOf("Gone"), NotePlacement.TOP, shared, noOptions)
        assertFalse(placed.headingFound)
        assertTrue(placed.text.startsWith("#+TITLE: Inbox\n* New link\n* Projects\n"))
    }
}
