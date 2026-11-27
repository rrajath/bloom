package com.rrajath.grove.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavUrlTest {

    private val root = WebDavUrl.parse("https://cloud.example.com/remote.php/dav/files/alice/Notes")!!

    @Test
    fun `https only and trailing slash`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Notes/",
            WebDavUrl.normalize(root.toString().trimEnd('/')),
        )
        assertNull(WebDavUrl.parse("http://cloud.example.com/dav/"))
        assertNull(WebDavUrl.parse("https://cloud.example.com/dav/?a=1"))
        assertNull(WebDavUrl.parse("https://alice:secret@cloud.example.com/dav/"))
        assertNull(WebDavUrl.parse("not a url"))
    }

    @Test
    fun `path segments are percent encoded`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Notes/projects/week%202.org",
            WebDavUrl.build(root, "projects/week 2.org").toString(),
        )
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Notes/",
            WebDavUrl.build(root, "").toString(),
        )
    }

    @Test
    fun `hrefs become vault relative paths`() {
        assertEquals(
            "projects/week 2.org",
            WebDavUrl.relativeFromHref(
                root,
                "/remote.php/dav/files/alice/Notes/projects/week%202.org",
                "",
            ),
        )
        assertEquals(
            "inbox.org",
            WebDavUrl.relativeFromHref(
                root,
                "https://cloud.example.com/remote.php/dav/files/alice/Notes/inbox.org",
                "",
            ),
        )
        assertEquals(
            "projects/inbox.org",
            WebDavUrl.relativeFromHref(root, "inbox.org", "projects"),
        )
        assertEquals("", WebDavUrl.relativeFromHref(root, "/remote.php/dav/files/alice/Notes/", ""))
        assertNull(WebDavUrl.relativeFromHref(root, "/somewhere/else/inbox.org", ""))
    }

    @Test
    fun `collection urls end with a slash`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Notes/",
            WebDavUrl.buildCollection(root, "").toString(),
        )
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Notes/projects/",
            WebDavUrl.buildCollection(root, "projects").toString(),
        )
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Notes/projects/week%202/",
            WebDavUrl.buildCollection(root, "projects/week 2").toString(),
        )
    }

    @Test
    fun `percent encoded vault roots match their own hrefs`() {
        val spaced = WebDavUrl.parse("https://cloud.example.com/dav/My%20Notes")!!
        assertEquals(
            "projects/week 2.org",
            WebDavUrl.relativeFromHref(spaced, "/dav/My%20Notes/projects/week%202.org", ""),
        )
        assertEquals(
            "projects/week 2.org",
            WebDavUrl.relativeFromHref(
                spaced,
                "https://cloud.example.com/dav/My%20Notes/projects/week%202.org",
                "",
            ),
        )
    }

    @Test
    fun `root relative hrefs are not prefixed twice`() {
        assertEquals(
            "projects/a.org",
            WebDavUrl.relativeFromHref(root, "projects/a.org", "projects"),
        )
    }

    @Test
    fun `unsafe and hidden paths are rejected`() {
        assertTrue(WebDavUrl.isSafeRelative("projects/acme.org"))
        assertFalse(WebDavUrl.isSafeRelative("../secret.org"))
        assertFalse(WebDavUrl.isSafeRelative("/absolute.org"))
        assertFalse(WebDavUrl.isSafeRelative("projects/.stversions/x.org"))
        assertFalse(WebDavUrl.isSafeRelative("projects\\x.org"))
    }

    @Test
    fun `parent collections come shallowest first`() {
        assertEquals(listOf("data", "data/ab"), WebDavUrl.parentDirs("data/ab/photo.jpg"))
        assertEquals(emptyList<String>(), WebDavUrl.parentDirs("inbox.org"))
    }
}
