package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirPinTest {

    private val trip = "* Trip\n:PROPERTIES:\n:DIR: photos\n:END:\n** Day\n[[attachment:a.jpg]]\n"

    private fun pinFor(text: String, title: String, file: String = "Notes/n.org"): DirPin? {
        val doc = OrgParser.parse(text)
        return DirPin.of(doc, file, doc.headlines.first { it.title == title })
    }

    private fun subtree(text: String, title: String): String {
        val doc = OrgParser.parse(text)
        return OrgMutations.subtreeText(doc, doc.headlines.first { it.title == title })
    }

    private fun dirOfRoot(subtree: String) = OrgParser.parse(subtree).headlines.first().attachDirProperty()

    @Test
    fun `relative paths climb and descend as needed`() {
        assertEquals("../Notes/photos", DirPin.relativePath("Archive", "Notes/photos"))
        assertEquals("photos", DirPin.relativePath("Notes", "Notes/photos"))
        assertEquals("Notes/photos", DirPin.relativePath("", "Notes/photos"))
        assertEquals("..", DirPin.relativePath("Notes/sub", "Notes"))
        assertEquals(".", DirPin.relativePath("Notes", "Notes"))
    }

    @Test
    fun `a heading inheriting a DIR its links use is pinned, rewritten for another folder`() {
        val pin = pinFor(trip, "Day")!!
        assertEquals(DirPin("photos", "Notes/n.org"), pin)
        val out = pin.apply(subtree(trip, "Day"), OrgParser.parse("* Done\n"), "Archive/a.org", null)
        assertEquals("../Notes/photos", dirOfRoot(out))
    }

    @Test
    fun `the file drawer's DIR is inherited too`() {
        val text = ":PROPERTIES:\n:DIR: files\n:END:\n* Day\n[[attachment:a.jpg]]\n"
        assertEquals(DirPin("files", "Notes/n.org"), pinFor(text, "Day"))
    }

    @Test
    fun `a heading with its own ID still gets the inherited DIR`() {
        val text = "* Trip\n:PROPERTIES:\n:DIR: photos\n:END:\n** Day\n:PROPERTIES:\n:ID: AA11\n:END:\n[[attachment:a.jpg]]\n"
        val out = pinFor(text, "Day")!!.apply(subtree(text, "Day"), OrgParser.parse(""), "Other/o.org", null)
        val root = OrgParser.parse(out).headlines.first()
        assertEquals("../Notes/photos", root.attachDirProperty())
        assertEquals("AA11", OrgParser.parse(out).ownerOf(root).id)
    }

    @Test
    fun `a descendant's links count when it inherits through the moved heading`() {
        val text = "* Trip\n:PROPERTIES:\n:DIR: photos\n:END:\n** Day\n*** Morning\n[[attachment:a.jpg]]\n"
        assertEquals("photos", pinFor(text, "Day")?.value)
    }

    @Test
    fun `nothing to pin without links, with an own DIR, or with nothing inherited`() {
        assertNull(pinFor("* Trip\n:PROPERTIES:\n:DIR: photos\n:END:\n** Day\nplain\n", "Day"))
        // The links resolve through a DIR inside the subtree, not the inherited one.
        assertNull(pinFor("* Trip\n:PROPERTIES:\n:DIR: photos\n:END:\n** Day\n*** M\n:PROPERTIES:\n:DIR: m\n:END:\n[[attachment:a.jpg]]\n", "Day"))
        assertNull(pinFor(trip, "Trip"))
        assertNull(pinFor("* Day\n:PROPERTIES:\n:ID: AA11\n:END:\n[[attachment:a.jpg]]\n", "Day"))
    }

    @Test
    fun `no pin when the target resolves to the same folder`() {
        val pin = pinFor(trip, "Day")!!
        val dest = OrgParser.parse("* Other\n:PROPERTIES:\n:DIR: photos\n:END:\n")
        val st = subtree(trip, "Day")
        // Same file, a parent with the same DIR.
        assertEquals(st, pin.apply(st, dest, "Notes/n.org", dest.headlines.first()))
        // Another file in another folder whose DIR climbs back to the same place.
        val other = OrgParser.parse(":PROPERTIES:\n:DIR: ../Notes/photos\n:END:\n")
        assertEquals(st, pin.apply(st, other, "Archive/a.org", null))
    }

    @Test
    fun `same file, under a parent with another DIR, pins the old value`() {
        val text = trip + "* Other\n:PROPERTIES:\n:DIR: elsewhere\n:END:\n"
        val doc = OrgParser.parse(text)
        val out = pinFor(text, "Day")!!.apply(subtree(text, "Day"), doc, "Notes/n.org", doc.headlines.first { it.title == "Other" })
        assertEquals("photos", dirOfRoot(out))
    }

    @Test
    fun `an absolute DIR is kept as written`() {
        val text = "* Trip\n:PROPERTIES:\n:DIR: /abs/photos\n:END:\n** Day\n[[attachment:a.jpg]]\n"
        val out = pinFor(text, "Day")!!.apply(subtree(text, "Day"), OrgParser.parse(""), "Archive/a.org", null)
        assertEquals("/abs/photos", dirOfRoot(out))
    }
}
