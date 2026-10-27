package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Test

class OrgLinkParserTest {

    private fun parse(target: String, currentFile: String = "notes.org") =
        OrgLinkParser.parse(target, currentFile)

    @Test
    fun `id link`() {
        assertEquals(OrgLinkTarget.Id("abc-123"), parse("id:abc-123"))
    }

    @Test
    fun `custom-id link is current-file`() {
        assertEquals(OrgLinkTarget.CustomId("kyoto-day-2"), parse("#kyoto-day-2"))
    }

    @Test
    fun `star link is a current-file heading`() {
        assertEquals(OrgLinkTarget.Heading("Weekly Review"), parse("*Weekly Review"))
    }

    @Test
    fun `star link strips extra leading stars and space`() {
        assertEquals(OrgLinkTarget.Heading("Deep heading"), parse("**  Deep heading"))
    }

    @Test
    fun `external schemes are handed off verbatim`() {
        assertEquals(OrgLinkTarget.External("https://example.com/x?y=1"), parse("https://example.com/x?y=1"))
        assertEquals(OrgLinkTarget.External("mailto:a@b.com"), parse("mailto:a@b.com"))
        assertEquals(OrgLinkTarget.External("tel:+15551234"), parse("tel:+15551234"))
    }

    @Test
    fun `file link with explicit prefix, whole file`() {
        assertEquals(OrgLinkTarget.File("projects.org"), parse("file:projects.org"))
    }

    @Test
    fun `bare org filename is a file link`() {
        assertEquals(OrgLinkTarget.File("projects.org"), parse("projects.org"))
    }

    @Test
    fun `file link resolves relative to the linking file's directory`() {
        assertEquals(
            OrgLinkTarget.File("work/projects.org"),
            parse("file:projects.org", currentFile = "work/journal.org"),
        )
    }

    @Test
    fun `dot-dot segments are normalized against the current directory`() {
        assertEquals(
            OrgLinkTarget.File("work/shared.org"),
            parse("file:../shared.org", currentFile = "work/clients/acme.org"),
        )
    }

    @Test
    fun `leading slash is treated as vault-root relative`() {
        assertEquals(
            OrgLinkTarget.File("inbox.org"),
            parse("file:/inbox.org", currentFile = "work/clients/acme.org"),
        )
    }

    @Test
    fun `missing org suffix is added`() {
        assertEquals(OrgLinkTarget.File("work/projects.org"), parse("file:projects", currentFile = "work/x.org"))
    }

    @Test
    fun `file link with a heading search option`() {
        assertEquals(
            OrgLinkTarget.FileHeading("projects.org", "Design"),
            parse("file:projects.org::*Design"),
        )
    }

    @Test
    fun `file link with a custom-id search option`() {
        assertEquals(
            OrgLinkTarget.FileCustomId("projects.org", "design"),
            parse("file:projects.org::#design"),
        )
    }

    @Test
    fun `file link with a plain fuzzy search option`() {
        assertEquals(
            OrgLinkTarget.FileHeading("projects.org", "Some target"),
            parse("file:projects.org::Some target"),
        )
    }

    @Test
    fun `double-colon works without the file prefix`() {
        assertEquals(
            OrgLinkTarget.FileHeading("work/projects.org", "Design"),
            parse("projects.org::*Design", currentFile = "work/x.org"),
        )
    }

    @Test
    fun `denote link is a vault-wide note identifier`() {
        assertEquals(
            OrgLinkTarget.Denote("20240101T120000"),
            parse("denote:20240101T120000", currentFile = "work/x.org"),
        )
    }

    @Test
    fun `denote link with a heading search option`() {
        assertEquals(
            OrgLinkTarget.DenoteHeading("20240101T120000", "Design"),
            parse("denote:20240101T120000::*Design"),
        )
        assertEquals(
            OrgLinkTarget.DenoteHeading("20240101T120000", "Some target"),
            parse("denote:20240101T120000::Some target"),
        )
    }

    @Test
    fun `denote link with a custom-id search option`() {
        assertEquals(
            OrgLinkTarget.DenoteCustomId("20240101T120000", "design"),
            parse("denote:20240101T120000::#design"),
        )
    }

    @Test
    fun `denote link with an empty search option is the whole note`() {
        assertEquals(OrgLinkTarget.Denote("20240101T120000"), parse("denote:20240101T120000::"))
    }

    @Test
    fun `plain text with no scheme is fuzzy`() {
        assertEquals(OrgLinkTarget.Fuzzy("Meeting notes"), parse("Meeting notes"))
    }
}
