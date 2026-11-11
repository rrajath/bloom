package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentInsertionTest {

    private val id = "8B41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C"

    private fun ownerAt(text: String, cursor: Int) = resolveOwner(OrgParser.parse(text), cursor)

    /** Runs the insertion with the cursor at the `|` marker in [marked]; returns (text, text with `|` at the new cursor). */
    private fun insert(marked: String, newId: String?, vararg names: String): Pair<String, String> {
        val cursor = marked.indexOf('|')
        val text = marked.removeRange(cursor, cursor + 1)
        val r = insertAttachmentLinks(text, cursor, ownerAt(text, cursor), newId, names.toList())
        return r.text to (r.text.substring(0, r.cursor) + "|" + r.text.substring(r.cursor))
    }

    @Test fun `link at end of a line goes on its own line below`() {
        val (_, withCursor) = insert("* H\n:PROPERTIES:\n:ID: X\n:END:\nsee this|\nmore\n", null, "a.jpg")
        assertEquals("* H\n:PROPERTIES:\n:ID: X\n:END:\nsee this\n[[attachment:a.jpg]]|\nmore\n", withCursor)
    }

    @Test fun `mid-line insertion splits the line`() {
        val (_, withCursor) = insert("* H\n:PROPERTIES:\n:ID: X\n:END:\nbefore |after\n", null, "a.pdf")
        assertEquals("* H\n:PROPERTIES:\n:ID: X\n:END:\nbefore \n[[attachment:a.pdf]]|\nafter\n", withCursor)
    }

    @Test fun `cursor on an empty line uses that line`() {
        val (_, withCursor) = insert("* H\n:PROPERTIES:\n:ID: X\n:END:\none\n|\ntwo", null, "a.pdf")
        assertEquals("* H\n:PROPERTIES:\n:ID: X\n:END:\none\n[[attachment:a.pdf]]|\ntwo", withCursor)
    }

    @Test fun `end of file without trailing newline`() {
        val (_, withCursor) = insert("* H\n:PROPERTIES:\n:ID: X\n:END:\nbody|", null, "a.pdf")
        assertEquals("* H\n:PROPERTIES:\n:ID: X\n:END:\nbody\n[[attachment:a.pdf]]|", withCursor)
    }

    @Test fun `multiple names get one link per line`() {
        val (_, withCursor) = insert("* H\n:PROPERTIES:\n:ID: X\n:END:\n|\n", null, "a.jpg", "b.jpg", "c.jpg")
        assertEquals(
            "* H\n:PROPERTIES:\n:ID: X\n:END:\n[[attachment:a.jpg]]\n[[attachment:b.jpg]]\n[[attachment:c.jpg]]|\n",
            withCursor,
        )
    }

    @Test fun `heading without drawer gets one created and the links shift below it`() {
        val (_, withCursor) = insert("* Trip\nnotes|\n* Next\n", id, "a.jpg")
        assertEquals("* Trip\n:PROPERTIES:\n:ID: $id\n:END:\nnotes\n[[attachment:a.jpg]]|\n* Next\n", withCursor)
    }

    @Test fun `heading with existing drawer gets the id added before end`() {
        val (_, withCursor) = insert("* Trip\n:PROPERTIES:\n:CREATED: x\n:END:\nnotes|\n", id, "a.jpg")
        assertEquals("* Trip\n:PROPERTIES:\n:CREATED: x\n:ID: $id\n:END:\nnotes\n[[attachment:a.jpg]]|\n", withCursor)
    }

    @Test fun `id goes after the planning line`() {
        val (text, _) = insert("* TODO Trip\nSCHEDULED: <2026-11-03 Tue>\nbody|\n", id, "a.jpg")
        assertEquals(
            "* TODO Trip\nSCHEDULED: <2026-11-03 Tue>\n:PROPERTIES:\n:ID: $id\n:END:\nbody\n[[attachment:a.jpg]]\n",
            text,
        )
        val h = OrgParser.parse(text).headlines.single()
        assertEquals(id, h.id)
        assertEquals(false, h.planning.isEmpty)
    }

    @Test fun `cursor on the headline moves the link into the body`() {
        val (_, withCursor) = insert("* Tr|ip\nSCHEDULED: <2026-11-03 Tue>\n:PROPERTIES:\n:ID: X\n:END:\nbody\n", null, "a.jpg")
        assertEquals(
            "* Trip\nSCHEDULED: <2026-11-03 Tue>\n:PROPERTIES:\n:ID: X\n:END:\n[[attachment:a.jpg]]|\nbody\n",
            withCursor,
        )
    }

    @Test fun `heading that is the last line with no body`() {
        val (_, withCursor) = insert("* Tr|ip", id, "a.jpg")
        assertEquals("* Trip\n:PROPERTIES:\n:ID: $id\n:END:\n[[attachment:a.jpg]]|", withCursor)
    }

    @Test fun `file owner without id gets a drawer on line 0 above the title`() {
        val (text, withCursor) = insert("#+TITLE: Travel\n\nintro|\n* H\n", id, "a.jpg")
        assertEquals(":PROPERTIES:\n:ID: $id\n:END:\n#+TITLE: Travel\n\nintro\n[[attachment:a.jpg]]|\n* H\n", withCursor)
        val doc = OrgParser.parse(text)
        assertEquals(id, doc.fileId)
        assertEquals(listOf("#+TITLE:" to "Travel"), doc.preambleKeywords)
    }

    @Test fun `file owner with existing drawer adds the id inside it`() {
        val (text, _) = insert(":PROPERTIES:\n:CATEGORY: t\n:END:\n#+TITLE: T\nintro|\n", id, "a.jpg")
        assertEquals(":PROPERTIES:\n:CATEGORY: t\n:ID: $id\n:END:\n#+TITLE: T\nintro\n[[attachment:a.jpg]]\n", text)
    }

    @Test fun `cursor in the file preface moves the link past the keywords`() {
        val (_, withCursor) = insert("#+TI|TLE: Travel\n#+FILETAGS: :x:\n* H\n", id, "a.jpg")
        assertEquals(":PROPERTIES:\n:ID: $id\n:END:\n#+TITLE: Travel\n#+FILETAGS: :x:\n[[attachment:a.jpg]]|\n* H\n", withCursor)
    }

    @Test fun `empty buffer`() {
        val (_, withCursor) = insert("|", id, "a.jpg")
        assertEquals(":PROPERTIES:\n:ID: $id\n:END:\n[[attachment:a.jpg]]|", withCursor)
    }

    @Test fun `id drawer lines are reported for the flash`() {
        val text = "#+TITLE: T\nbody"
        val r = insertAttachmentLinks(text, text.length, ownerAt(text, text.length), id, listOf("a.jpg"))
        assertEquals(0..2, r.idDrawerLines)
        val noId = insertAttachmentLinks(r.text, r.text.length, ownerAt(r.text, r.text.length), null, listOf("b.jpg"))
        assertNull(noId.idDrawerLines)
        val h = "* H\nbody"
        val rh = insertAttachmentLinks(h, h.length, ownerAt(h, h.length), id, listOf("a.jpg"))
        assertEquals(1..3, rh.idDrawerLines)
    }

    @Test fun `owner in a later heading only touches that heading`() {
        val (text, _) = insert("* A\nbody a\n* B\nbody b|\n", id, "x.pdf")
        assertEquals("* A\nbody a\n* B\n:PROPERTIES:\n:ID: $id\n:END:\nbody b\n[[attachment:x.pdf]]\n", text)
    }
}
