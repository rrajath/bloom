package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachOwnerTest {

    private val text = """
        :PROPERTIES:
        :ID: FILE-ID
        :END:
        #+TITLE: Travel

        Intro line
        * Japan
        :PROPERTIES:
        :ID: JAPAN-ID
        :END:
        Japan body
        ** Kyoto
        :PROPERTIES:
        :dir: photos/kyoto
        :END:
        Kyoto body
        ** Osaka
        Osaka body
        *** Dotonbori
        Food
    """.trimIndent()
    private val doc = OrgParser.parse(text)

    private fun offsetOf(s: String) = text.indexOf(s).also { check(it >= 0) }

    @Test fun `cursor above the first heading is the file owner`() {
        assertEquals(AttachOwner.File("FILE-ID", null), resolveOwner(doc, offsetOf("Intro line")))
        assertEquals(AttachOwner.File("FILE-ID", null), resolveOwner(doc, 0))
    }

    @Test fun `cursor in a body is the nearest heading at or above it`() {
        val o = resolveOwner(doc, offsetOf("Japan body")) as AttachOwner.Heading
        assertEquals(listOf("Japan"), o.titlePath)
        assertEquals("JAPAN-ID", o.id)
        assertEquals(6, o.lineIndex)
    }

    @Test fun `cursor on the headline line belongs to that heading`() {
        val o = resolveOwner(doc, offsetOf("** Osaka") + 3) as AttachOwner.Heading
        assertEquals(listOf("Japan", "Osaka"), o.titlePath)
        assertNull(o.id)
    }

    @Test fun `dir property is read case-insensitively`() {
        val o = resolveOwner(doc, offsetOf("Kyoto body")) as AttachOwner.Heading
        assertEquals("photos/kyoto", o.dirProperty)
    }

    @Test fun `attach_dir is the legacy fallback for dir`() {
        val d = OrgParser.parse("* H\n:PROPERTIES:\n:ATTACH_DIR: old/place\n:END:\n")
        assertEquals("old/place", (resolveOwner(d, 0) as AttachOwner.Heading).dirProperty)
    }

    @Test fun `file without drawer has no id`() {
        val d = OrgParser.parse("#+TITLE: x\n\nhello\n")
        assertEquals(AttachOwner.File(null, null), resolveOwner(d, 12))
    }

    @Test fun `without inheritance a heading without id stays itself`() {
        val o = resolveOwner(doc, offsetOf("Food")) as AttachOwner.Heading
        assertEquals(listOf("Japan", "Osaka", "Dotonbori"), o.titlePath)
        assertNull(o.id)
    }

    @Test fun `with inheritance the nearest ancestor id wins`() {
        val o = resolveOwner(doc, offsetOf("Food"), AttachConfig(useInheritance = true)) as AttachOwner.Heading
        assertEquals(listOf("Japan"), o.titlePath)
        assertEquals("JAPAN-ID", o.id)
    }

    @Test fun `with inheritance falls back to the file id`() {
        val d = OrgParser.parse(":PROPERTIES:\n:ID: F\n:END:\n* A\n** B\nbody\n")
        assertEquals(AttachOwner.File("F", null), resolveOwner(d, d.text.indexOf("body"), AttachConfig(useInheritance = true)))
    }

    @Test fun `relocate finds a heading by id after it moved`() {
        val owner = resolveOwner(doc, offsetOf("Japan body"))
        val edited = OrgParser.parse("* New first\n$text")
        val found = relocateOwner(edited, owner) as AttachOwner.Heading
        assertEquals(7, found.lineIndex)
        assertEquals("JAPAN-ID", found.id)
    }

    @Test fun `relocate falls back to title path`() {
        val owner = resolveOwner(doc, offsetOf("Osaka body"))
        val edited = OrgParser.parse("* Extra\n$text")
        assertEquals(17, (relocateOwner(edited, owner) as AttachOwner.Heading).lineIndex)
    }

    @Test fun `relocate returns null when the heading is gone`() {
        val owner = resolveOwner(doc, offsetOf("Osaka body"))
        assertNull(relocateOwner(OrgParser.parse("* Japan\n** Kyoto\n"), owner))
    }
}
