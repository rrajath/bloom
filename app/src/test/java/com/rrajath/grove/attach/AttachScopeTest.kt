package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachScopeTest {

    @Test fun `heading owner gets three scopes, file owner two`() {
        assertEquals(
            listOf(AttachScope.HEADING, AttachScope.FILE, AttachScope.VAULT),
            scopesFor(AttachOwner.Heading(3, listOf("A"), "X1", null)),
        )
        assertEquals(listOf(AttachScope.FILE, AttachScope.VAULT), scopesFor(AttachOwner.File("F1", null)))
    }

    @Test fun `relative link paths`() {
        assertEquals("data/9B/2E0E/a.jpg", relativeLinkPath("image-test.org", "data/9B/2E0E/a.jpg"))
        assertEquals("../journal/data/AB/CD/p.jpg", relativeLinkPath("notes/x.org", "journal/data/AB/CD/p.jpg"))
        assertEquals("../data/AB/CD/p.jpg", relativeLinkPath("notes/x.org", "data/AB/CD/p.jpg"))
        assertEquals("../../data/p.jpg", relativeLinkPath("a/b/x.org", "data/p.jpg"))
        assertEquals("data/p.jpg", relativeLinkPath("a/b/x.org", "a/b/data/p.jpg"))
    }

    @Test fun `link text and linkable names`() {
        assertEquals("[[file:data/a b.jpg]]", fileLinkText("data/a b.jpg"))
        assertTrue(linkableName("data/9B/x/photo.jpg"))
        assertFalse(linkableName("data/9B/x/pic[1].jpg"))
        assertFalse(linkableName("data/9B/x/pic]1.jpg"))
        assertFalse(linkableName("data/9B/x/a::b.pdf"))
        assertTrue(linkableName("data/9B/x/a:b.pdf"))
    }

    @Test fun `vault source label`() {
        assertEquals("journal.org › Trip notes", vaultSource("notes/journal.org", "Trip notes"))
        assertEquals("journal.org", vaultSource("notes/journal.org", null))
    }
}
