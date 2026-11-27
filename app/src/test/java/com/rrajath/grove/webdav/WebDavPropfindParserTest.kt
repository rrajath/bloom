package com.rrajath.grove.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavPropfindParserTest {

    private val root = WebDavUrl.parse("https://cloud.example.com/remote.php/dav/files/alice/Notes")!!

    @Test
    fun `parses files collections etags and sizes`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/remote.php/dav/files/alice/Notes/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/alice/Notes/inbox.org</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getetag>"abc123"</d:getetag>
                    <d:getlastmodified>Tue, 24 Nov 2026 05:00:00 GMT</d:getlastmodified>
                    <d:getcontentlength>42</d:getcontentlength>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/alice/Notes/projects/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>"""

        val entries = WebDavPropfindParser.parse(xml, root, "")
        assertEquals(3, entries.size)
        val rootEntry = entries.single { it.path.isEmpty() }
        assertTrue(rootEntry.isDir)

        val file = entries.single { it.path == "inbox.org" }
        assertFalse(file.isDir)
        assertEquals("\"abc123\"", file.etag)
        assertEquals(42L, file.size)
        assertTrue(file.lastModified > 0L)
        assertEquals("\"abc123\"", file.revision)

        assertTrue(entries.single { it.path == "projects" }.isDir)
    }

    @Test
    fun `handles default namespaces relative hrefs and 404 propstats`() {
        val xml = """<?xml version="1.0"?>
            <multistatus xmlns="DAV:">
              <response>
                <href>/remote.php/dav/files/alice/Notes/week%202.org</href>
                <propstat><prop><getetag>"r7"</getetag><getcontentlength>7</getcontentlength></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                <propstat><prop><getdisplayname/></prop><status>HTTP/1.1 404 Not Found</status></propstat>
              </response>
            </multistatus>"""

        val entries = WebDavPropfindParser.parse(xml, root, "")
        assertEquals(1, entries.size)
        assertEquals("week 2.org", entries.single().path)
        assertEquals("\"r7\"", entries.single().etag)
        assertEquals(7L, entries.single().size)
        assertEquals("\"r7\"", entries.single().revision)
    }

    @Test
    fun `relative hrefs are resolved against the queried collection`() {
        val xml = """<?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>a.org</D:href>
                <D:propstat><D:prop><D:getcontentlength>1</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>"""
        assertEquals("projects/a.org", WebDavPropfindParser.parse(xml, root, "projects").single().path)
    }

    @Test(expected = WebDavException::class)
    fun `malformed xml fails clearly`() {
        WebDavPropfindParser.parse("<not-xml", root, "")
    }

    @Test
    fun `iso 8601 modification dates are accepted`() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/files/alice/Notes/iso.org</d:href>
                <d:propstat><d:prop>
                  <d:getlastmodified>2026-11-24T05:00:00Z</d:getlastmodified>
                  <d:getcontentlength>3</d:getcontentlength>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>"""
        val entry = WebDavPropfindParser.parse(xml, root, "").single()
        assertTrue(entry.lastModified > 0L)
        assertEquals("${entry.lastModified}:3", entry.revision)
    }

    @Test
    fun `servers without etags fall back to mtime and size`() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/files/alice/Notes/plain.org</d:href>
                <d:propstat><d:prop>
                  <d:getlastmodified>Tue, 24 Nov 2026 05:00:00 GMT</d:getlastmodified>
                  <d:getcontentlength>12</d:getcontentlength>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>"""
        val entry = WebDavPropfindParser.parse(xml, root, "").single()
        assertEquals(null, entry.etag)
        assertEquals("${entry.lastModified}:12", entry.revision)
    }
}
