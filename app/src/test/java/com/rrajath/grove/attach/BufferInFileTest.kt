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

    @Test fun `a placed capture entry inherits its target heading's dir, matched by title across re-leveling`() {
        // Capture inserted "* Lunch" under ** Kyoto as "*** Lunch" (line 10 onward).
        val placedText = file.lines().let { lines ->
            (lines.take(10) + listOf("*** Dinner", "Ramen.") + lines.drop(10)).joinToString("\n")
        }
        val ctx = AttachFileContext(placedText, 10 until 12, placed = true)
        val o = BufferInFile.of("* Dinner\nRamen.", ctx).ownerAtLine(1) as AttachOwner.Heading
        assertEquals(0, o.lineIndex)
        assertEquals(listOf("Dinner"), o.titlePath)
        assertEquals("trip", o.dirProperty)
    }

    @Test fun `heading-less capture text belongs to the heading it lands in`() {
        // Body-only text inserted into Kyoto's body (line 10).
        val lines = file.lines()
        val placedText = (lines.take(10) + listOf("Note one.") + lines.drop(10)).joinToString("\n")
        val placed = BufferInFile.of("Note one.", AttachFileContext(placedText, 10..10, placed = true))
        assertEquals(AttachOwner.File(null, null), placed.ownerAtLine(0))
        val around = placed.ownerAround(0) as AttachOwner.Heading
        assertEquals(listOf("Trip", "Kyoto"), around.titlePath)
        assertEquals(9, around.lineIndex)
        assertEquals("trip", around.dirProperty)
        assertEquals(1, placed.dirFromLevel(around))
    }

    @Test fun `ownerAround is null when the buffer has its own heading or no file`() {
        val (buffer, ctx) = editorFor("Kyoto")
        assertNull(BufferInFile.of(buffer, ctx).ownerAround(0))
        assertNull(BufferInFile.of("Note.", null).ownerAround(0))
    }
}
