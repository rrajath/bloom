package com.rrajath.grove.ui.editor

import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPickTest {

    private fun parse(text: String) = OrgParser.parse(text, OrgKeywords.DEFAULT)

    @Test
    fun `file without an id links by relative path, named after its title`() {
        val doc = parse("#+TITLE: Reading list\n* Books\n")
        val pick = resolveLinkPick("dailies/2026-09-24.org", doc, "reading.org", null, null)
        assertEquals(LinkPick.Direct("[[file:../reading.org][Reading list]]"), pick)
    }

    @Test
    fun `file with an id asks whether to use it`() {
        val doc = parse(":PROPERTIES:\n:ID: file-1\n:END:\n#+TITLE: Kyoto\n")
        val pick = resolveLinkPick("dailies/2026-09-24.org", doc, "trips/kyoto.org", null, null)
        assertEquals(
            LinkPick.AskId(LinkIdChoice("[[id:file-1][Kyoto]]", "[[file:../trips/kyoto.org][Kyoto]]", LinkSubject.FILE)),
            pick,
        )
    }

    @Test
    fun `heading without an id links by name, with the selection as description`() {
        val doc = parse("* Weekly Review\n")
        val heading = doc.headlines.single()
        val pick = resolveLinkPick("dailies/2026-09-24.org", doc, "work.org", heading, "review")
        assertEquals(LinkPick.Direct("[[file:../work.org::*Weekly Review][review]]"), pick)
    }

    @Test
    fun `heading in the same file drops the file qualifier`() {
        val doc = parse("* Weekly Review\n")
        val heading = doc.headlines.single()
        val pick = resolveLinkPick("work.org", doc, "work.org", heading, null)
        assertEquals(LinkPick.Direct("[[*Weekly Review][Weekly Review]]"), pick)
    }

    @Test
    fun `heading with an id asks whether to use it`() {
        val doc = parse("* Weekly Review\n:PROPERTIES:\n:ID: h-1\n:END:\n")
        val heading = doc.headlines.single()
        val pick = resolveLinkPick("work.org", doc, "work.org", heading, null)
        assertEquals(
            LinkPick.AskId(LinkIdChoice("[[id:h-1][Weekly Review]]", "[[*Weekly Review][Weekly Review]]", LinkSubject.HEADING)),
            pick,
        )
    }
}
