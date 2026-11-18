package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The single-pass owner table ([headingOwners], [attachOwners], [OwnerLocator])
 * must match the per-heading parent walk it replaced, which is kept here
 * verbatim as the reference.
 */
class AttachOwnerLinearPassTest {

    private val text = """
        :PROPERTIES:
        :ID: FILE-ID
        :ATTACH_DIR: file-legacy
        :END:
        #+TITLE: Mixed
        Intro
        * Top A
        :PROPERTIES:
        :ID: A-ID
        :END:
        ** Child with own dir
        :PROPERTIES:
        :DIR: a/child
        :END:
        *** Grandchild inherits dir
        **** Great-grandchild with id
        :PROPERTIES:
        :ID: GG-ID
        :END:
        ** Child with legacy attach dir only
        :PROPERTIES:
        :ATTACH_DIR: legacy/child
        :END:
        *** Under legacy
        * Top B
        :PROPERTIES:
        :dir: b
        :attach_dir: b-legacy
        :END:
        *** Skipped level under B
        ** Back to level two under B
        * Top C plain
        ** Shared id one
        :PROPERTIES:
        :ID: SHARED
        :END:
        ** Shared id two
        :PROPERTIES:
        :ID: SHARED
        :END:
        ** Top C plain
        * Top C plain
        * Blank props
        :PROPERTIES:
        :DIR:
        :ID:
        :END:
    """.trimIndent()

    private val doc = OrgParser.parse(text)

    // ---- reference: the pre-2026-11-17 implementation ----

    private fun prop(h: OrgHeadline, key: String): String? =
        h.properties.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun fileProp(d: OrgDocument, key: String): String? =
        d.filePropertyDrawer.firstOrNull { it.first.equals(":$key:", ignoreCase = true) }?.second?.takeIf { it.isNotBlank() }

    private fun referenceResolve(d: OrgDocument, h: OrgHeadline): AttachOwner {
        class Supplier(val owner: AttachOwner, val dir: String?, val attachDir: String?)
        val own = d.ownerOf(h)
        val chain = buildList {
            var cur: OrgHeadline? = h
            while (cur != null) {
                add(Supplier(d.ownerOf(cur), prop(cur, "DIR"), prop(cur, "ATTACH_DIR")))
                cur = d.parent(cur)
            }
            add(Supplier(d.fileOwner(), fileProp(d, "DIR"), fileProp(d, "ATTACH_DIR")))
        }
        val (supplier, value) = chain.firstNotNullOfOrNull { s -> s.dir?.let { s to it } }
            ?: chain.firstNotNullOfOrNull { s -> s.attachDir?.let { s to it } }
            ?: return own
        return own.copy(dirProperty = value, dirFrom = supplier.owner.takeIf { supplier !== chain.first() })
    }

    private fun referenceRelocate(d: OrgDocument, owner: AttachOwner.Heading): AttachOwner? {
        val byId = owner.id?.let { id -> d.headlines.firstOrNull { prop(it, "ID") == id } }
        val match = byId ?: d.headlines.firstOrNull { d.titlePath(it) == owner.titlePath }
        return match?.let { d.ownerOf(it) }
    }

    // ---- tests ----

    @Test fun `resolved owners match the per-heading walk`() {
        val owners = doc.headingOwners()
        assertEquals(doc.headlines.map { referenceResolve(doc, it) }, owners.resolved)
        assertEquals(doc.headlines.map { doc.ownerOf(it) }, owners.own)
    }

    @Test fun `resolveOwnerAtLine matches the per-heading walk on every line`() {
        for (line in doc.lines.indices) {
            val h = doc.headlines.lastOrNull { it.lineIndex <= line }
            val expected = h?.let { referenceResolve(doc, it) } ?: doc.fileOwner()
            assertEquals("line $line", expected, resolveOwnerAtLine(doc, line))
        }
    }

    @Test fun `headingAtLine binary search matches a linear scan`() {
        for (line in -1..doc.lines.size + 1) {
            assertEquals("line $line", doc.headlines.lastOrNull { it.lineIndex <= line }, doc.headingAtLine(line))
        }
        assertNull(OrgParser.parse("no headings\n").headingAtLine(3))
    }

    @Test fun `inherited DIR beats a heading's own ATTACH_DIR, and the file ATTACH_DIR is the last resort`() {
        val owners = doc.headingOwners().resolved.associateBy { it.title }
        assertEquals("legacy/child", owners.getValue("Child with legacy attach dir only").dirProperty)
        assertEquals("a/child", owners.getValue("Great-grandchild with id").dirProperty)
        assertEquals("Child with own dir", (owners.getValue("Great-grandchild with id").dirFrom as AttachOwner.Heading).title)
        assertEquals("b", owners.getValue("Skipped level under B").dirProperty)
        val plain = doc.headingOwners().resolved.first { it.title == "Top C plain" }
        assertEquals("file-legacy", plain.dirProperty)
        assertEquals(doc.fileOwner(), plain.dirFrom)
    }

    @Test fun `attachOwners keeps the file owner and only headings with an id or a dir`() {
        val noDirs = OrgParser.parse(
            """
            * Plain
            ** Plain child
            * Has id
            :PROPERTIES:
            :ID: X
            :END:
            * Has dir
            :PROPERTIES:
            :DIR: d
            :END:
            ** Inherits dir
            """.trimIndent(),
        )
        val owners = noDirs.attachOwners()
        assertEquals(AttachOwner.File(null, null), owners.first())
        assertEquals(listOf("Has id", "Has dir", "Inherits dir"), owners.drop(1).map { (it as AttachOwner.Heading).title })
    }

    @Test fun `attachOwners in a file-level dir keeps every heading`() {
        // Every heading inherits the file ATTACH_DIR, so none are dropped.
        assertEquals(doc.headlines.size + 1, doc.attachOwners().size)
    }

    @Test fun `locator matches relocation by id then title path, first match wins`() {
        val edited = OrgParser.parse(text.replace("Intro", "Intro\nmore intro\n* Inserted\n"))
        val locator = OwnerLocator(edited)
        val owners = doc.headingOwners().own + listOf(
            AttachOwner.Heading(0, listOf("Gone"), null, null),
            AttachOwner.Heading(0, listOf("Gone"), "MISSING-ID", null),
            AttachOwner.Heading(0, listOf("Top C plain"), "MISSING-ID", null),
        )
        for (o in owners) {
            assertEquals(o.titlePath.toString(), referenceRelocate(edited, o), locator.relocate(o))
            assertEquals(o.titlePath.toString(), referenceRelocate(edited, o), relocateOwner(edited, o))
        }
    }
}
