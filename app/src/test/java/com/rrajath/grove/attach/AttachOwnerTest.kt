package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import kotlinx.coroutines.runBlocking
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

    @Test fun `ancestor id is never inherited`() {
        val o = resolveOwner(doc, offsetOf("Food")) as AttachOwner.Heading
        assertEquals(listOf("Japan", "Osaka", "Dotonbori"), o.titlePath)
        assertNull(o.id)
        assertNull(o.dirProperty)
        assertNull(o.dirFrom)
    }

    @Test fun `file id is never inherited by a heading`() {
        val d = OrgParser.parse(":PROPERTIES:\n:ID: F\n:END:\n* A\n** B\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals(AttachOwner.Heading(4, listOf("A", "B"), null, null), o)
    }

    @Test fun `own dir is not marked inherited`() {
        val o = resolveOwner(doc, offsetOf("Kyoto body")) as AttachOwner.Heading
        assertNull(o.dirFrom)
    }

    @Test fun `parent dir is inherited and names the parent`() {
        val d = OrgParser.parse("* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\n** Day 1\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals(listOf("Trip", "Day 1"), o.titlePath)
        assertEquals(4, o.lineIndex)
        assertEquals("trip", o.dirProperty)
        assertEquals(AttachOwner.Heading(0, listOf("Trip"), null, "trip"), o.dirFrom)
    }

    @Test fun `grandparent dir is inherited`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n** B\n*** C\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("a", o.dirProperty)
        assertEquals(listOf("A"), (o.dirFrom as AttachOwner.Heading).titlePath)
    }

    @Test fun `nearest ancestor dir wins`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n** B\n:PROPERTIES:\n:DIR: b\n:END:\n*** C\nbody\n")
        assertEquals("b", resolveOwner(d, d.text.indexOf("body")).dirProperty)
    }

    @Test fun `own dir beats an ancestor dir`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n** B\n:PROPERTIES:\n:DIR: b\n:END:\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("b", o.dirProperty)
        assertNull(o.dirFrom)
    }

    @Test fun `file drawer dir is inherited by every heading`() {
        val d = OrgParser.parse(":PROPERTIES:\n:ID: F\n:DIR: files\n:END:\n* A\n** B\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("files", o.dirProperty)
        assertEquals(AttachOwner.File("F", "files"), o.dirFrom)
    }

    @Test fun `ancestor dir beats the heading's own id`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n** B\n:PROPERTIES:\n:ID: B-ID\n:END:\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("B-ID", o.id)
        assertEquals("a", o.dirProperty)
        assertEquals(runBlocking { AttachDirResolver(DirExists { false }, AttachConfig.Default).writeDir("n/x.org", o) }, "n/a")
    }

    @Test fun `ancestor dir beats the heading's own attach_dir`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n** B\n:PROPERTIES:\n:ATTACH_DIR: legacy\n:END:\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("a", o.dirProperty)
        assertEquals(listOf("A"), (o.dirFrom as AttachOwner.Heading).titlePath)
    }

    @Test fun `own attach_dir beats an ancestor attach_dir`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:ATTACH_DIR: a\n:END:\n** B\n:PROPERTIES:\n:ATTACH_DIR: b\n:END:\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("b", o.dirProperty)
        assertNull(o.dirFrom)
    }

    @Test fun `ancestor attach_dir is inherited when no dir exists`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:ATTACH_DIR: a\n:END:\n** B\n:PROPERTIES:\n:ID: B-ID\n:END:\nbody\n")
        val o = resolveOwner(d, d.text.indexOf("body")) as AttachOwner.Heading
        assertEquals("a", o.dirProperty)
        assertEquals("B-ID", o.id)
    }

    @Test fun `a sibling's dir is not inherited`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n* B\nbody\n")
        assertNull(resolveOwner(d, d.text.indexOf("body")).dirProperty)
    }

    @Test fun `blank dir is skipped`() {
        val d = OrgParser.parse("* A\n:PROPERTIES:\n:DIR: a\n:END:\n** B\n:PROPERTIES:\n:DIR:\n:END:\nbody\n")
        assertEquals("a", resolveOwner(d, d.text.indexOf("body")).dirProperty)
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
