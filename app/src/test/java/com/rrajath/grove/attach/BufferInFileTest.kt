package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BufferInFileTest {

    private val file = """
        :PROPERTIES:
        :ID: FILE-ID
        :END:
        #+TITLE: Travel

        * Trip
        :PROPERTIES:
        :DIR: trip
        :END:
        ** Kyoto
        *** Lunch
        :PROPERTIES:
        :ID: LUNCH-ID
        :END:
        Soba.
        * Other
        Other body.
    """.trimIndent()

    /** What the single-heading editor holds for [title]: its subtree text and owned span. */
    private fun editorFor(title: String, text: String = file): Pair<String, AttachFileContext> {
        val doc = OrgParser.parse(text)
        val h = doc.headlines.first { it.title == title }
        return OrgMutations.subtreeText(doc, h) to AttachFileContext(text, h.lineIndex until doc.subtreeEndLine(h))
    }

    @Test fun `a subtree buffer inherits its grandparent's dir from the file`() {
        val (buffer, ctx) = editorFor("Lunch")
        val o = BufferInFile.of(buffer, ctx).ownerAtOffset(buffer.indexOf("Soba")) as AttachOwner.Heading
        // Buffer coordinates, so links still go into the buffer's heading.
        assertEquals(0, o.lineIndex)
        assertEquals(listOf("Lunch"), o.titlePath)
        assertEquals("LUNCH-ID", o.id)
        assertEquals("trip", o.dirProperty)
        assertEquals(listOf("Trip"), (o.dirFrom as AttachOwner.Heading).titlePath)
    }

    @Test fun `a subtree buffer inherits its parent's dir`() {
        val (buffer, ctx) = editorFor("Kyoto")
        val o = BufferInFile.of(buffer, ctx).ownerAtLine(0) as AttachOwner.Heading
        assertEquals(listOf("Kyoto"), o.titlePath)
        assertEquals("trip", o.dirProperty)
    }

    @Test fun `a subtree buffer inherits the file drawer's dir`() {
        val text = file.replace(":ID: FILE-ID", ":ID: FILE-ID\n:DIR: files")
        val (buffer, ctx) = editorFor("Other", text)
        val o = BufferInFile.of(buffer, ctx).ownerAtLine(1) as AttachOwner.Heading
        assertEquals("files", o.dirProperty)
        assertEquals(AttachOwner.File("FILE-ID", "files"), o.dirFrom)
    }

    @Test fun `without the file the buffer alone has no inherited dir`() {
        val (buffer, _) = editorFor("Lunch")
        val o = BufferInFile.of(buffer, null).ownerAtLine(0) as AttachOwner.Heading
        assertNull(o.dirProperty)
        assertEquals("LUNCH-ID", o.id)
    }

    @Test fun `unsaved buffer edits count`() {
        val (buffer, ctx) = editorFor("Kyoto")
        val edited = buffer.replace("** Kyoto\n", "** Kyoto\n:PROPERTIES:\n:DIR: kyoto\n:END:\n")
        val placed = BufferInFile.of(edited, ctx)
        assertEquals("kyoto", placed.ownerAtOffset(edited.indexOf("Soba")).dirProperty)
        val kyoto = placed.ownerAtLine(0) as AttachOwner.Heading
        assertEquals("kyoto", kyoto.dirProperty)
        assertNull(kyoto.dirFrom)
    }

    @Test fun `a buffer that grew past its span still maps line for line`() {
        val (buffer, ctx) = editorFor("Kyoto")
        val edited = "$buffer\n**** Dessert\nMochi.\n"
        val o = BufferInFile.of(edited, ctx).ownerAtOffset(edited.indexOf("Mochi")) as AttachOwner.Heading
        assertEquals(listOf("Kyoto", "Lunch", "Dessert"), o.titlePath)
        assertEquals("trip", o.dirProperty)
    }

    @Test fun `a span that no longer fits the file falls back to the buffer`() {
        val (buffer, _) = editorFor("Lunch")
        val stale = AttachFileContext(file, 40 until 60)
        assertNull(BufferInFile.of(buffer, stale).ownerAtLine(0).dirProperty)
    }
}
