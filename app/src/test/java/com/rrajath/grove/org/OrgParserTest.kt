package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgParserTest {

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/golden/$name")!!.readBytes().toString(Charsets.UTF_8)

    // --- Round trip: parse → serialize must be byte-identical ---

    @Test
    fun `round trip is byte-identical for all golden files`() {
        for (name in listOf("travel.org", "edge-cases.org")) {
            val text = golden(name)
            assertEquals("round-trip failed for $name", text, OrgParser.parse(text).serialize())
        }
    }

    @Test
    fun `round trip preserves empty and quirky content`() {
        for (text in listOf("", "\n", "no headings at all", "* h", "* h\n", "*  spaced  \n\n\n")) {
            assertEquals(text, OrgParser.parse(text).serialize())
        }
    }

    // --- Structure ---

    @Test
    fun `parses headline hierarchy from golden file`() {
        val doc = OrgParser.parse(golden("travel.org"))
        assertEquals(listOf("trip"), doc.fileTags)

        val japan = doc.headlines.first()
        assertEquals(1, japan.level)
        assertEquals("Japan: Spring 2025", japan.title)
        assertEquals(listOf("japan"), japan.tags)
        assertEquals("11111111-aaaa-bbbb-cccc-000000000001", japan.id)

        val children = doc.directChildren(japan).map { it.title }
        assertEquals(listOf("Book Kyoto ryokan", "Kyoto: Day 2", "Order JR Rail Pass"), children)

        val kyotoDay2 = doc.findByCustomId("kyoto-day-2")!!
        assertEquals(3, doc.directChildren(kyotoDay2).size)
        assertEquals(3, doc.subtree(kyotoDay2).size)
        assertEquals("Japan: Spring 2025", doc.parent(kyotoDay2)!!.title)
    }

    @Test
    fun `preambleKeywords extracts file-level keyword lines in file order`() {
        val doc = OrgParser.parse(golden("travel.org"))
        assertEquals(
            listOf("#+TITLE:" to "Travel plans", "#+FILETAGS:" to ":trip:"),
            doc.preambleKeywords,
        )
    }

    @Test
    fun `preambleKeywords is empty when the file has no keyword lines`() {
        val doc = OrgParser.parse("* just a heading\nbody text\n")
        assertTrue(doc.preambleKeywords.isEmpty())
    }

    @Test
    fun `preambleKeywords stops at the intro so attr_org lines stay with their content`() {
        val doc = OrgParser.parse(
            "#+title: Push ups\n\nIntro text\n#+attr_org: :width 300\n[[attachment:a.png]]\n",
        )
        assertEquals(listOf("#+title:" to "Push ups"), doc.preambleKeywords)
        assertTrue(doc.introBody.contains("#+attr_org: :width 300"))
    }

    @Test
    fun `preambleKeywords ignores keyword-looking lines after the first headline`() {
        val doc = OrgParser.parse("#+TITLE: Foo\n* Heading\n#+NOT_PREAMBLE: bar\n")
        assertEquals(listOf("#+TITLE:" to "Foo"), doc.preambleKeywords)
    }

    @Test
    fun `intro helpers see real content before the first heading`() {
        val doc = OrgParser.parse(
            ":PROPERTIES:\n:ID: x\n:END:\n#+title: Roam note\n\nOriginally proposed by Hegel.\nMore prose.\n",
        )
        assertTrue(doc.hasIntro)
        assertEquals("Originally proposed by Hegel.", doc.introTitle)
        assertEquals(
            listOf("Originally proposed by Hegel.", "More prose."),
            doc.introBody.filter { it.isNotBlank() },
        )
    }

    @Test
    fun `intro helpers see content that precedes a later heading`() {
        val doc = OrgParser.parse("#+title: T\n\nPreamble prose.\n\n* Timeline\nunder heading\n")
        assertTrue(doc.hasIntro)
        assertEquals("Preamble prose.", doc.introTitle)
        assertTrue(doc.introBody.none { it.contains("Timeline") })
    }

    @Test
    fun `no intro when the preamble is only keywords and a drawer`() {
        val doc = OrgParser.parse(":PROPERTIES:\n:ID: x\n:END:\n#+title: T\n\n* Heading\nbody\n")
        assertTrue(!doc.hasIntro)
        assertEquals("", doc.introTitle)
        assertEquals(doc.preambleEnd, doc.introStart)
    }

    @Test
    fun `no intro for a plain heading-only file`() {
        val doc = OrgParser.parse("* just a heading\nbody text\n")
        assertTrue(!doc.hasIntro)
    }

    @Test
    fun `filePropertyDrawer parses a leading file-level property drawer`() {
        val doc = OrgParser.parse(
            ":PROPERTIES:\n:ID: 1a2b3c\n:CREATED: [2026-08-01]\n:END:\n#+TITLE: Kyoto trip\n\nSome intro prose.\n\n* First heading\n",
        )
        assertEquals(
            listOf(":ID:" to "1a2b3c", ":CREATED:" to "[2026-08-01]"),
            doc.filePropertyDrawer,
        )
        // The drawer is not also dumped into the prose block.
        assertEquals(listOf("Some intro prose."), doc.introBody.filter { it.isNotBlank() })
        assertEquals("Some intro prose.", doc.introTitle)
    }

    @Test
    fun `fileId is the drawer's ID, or null without one`() {
        assertEquals(
            "1a2b3c",
            OrgParser.parse(":PROPERTIES:\n:ID: 1a2b3c\n:END:\n#+TITLE: T\n\n* H\n").fileId,
        )
        assertEquals(null, OrgParser.parse("#+TITLE: T\n\n* H\n").fileId)
        // A drawer :ID: on the first heading is not a file-level id.
        assertEquals(null, OrgParser.parse("* H\n:PROPERTIES:\n:ID: abc\n:END:\n").fileId)
    }

    @Test
    fun `filePropertyDrawer is empty when there is no drawer`() {
        val doc = OrgParser.parse("#+TITLE: T\n\nProse.\n\n* Heading\nbody\n")
        assertTrue(doc.filePropertyDrawer.isEmpty())
    }

    @Test
    fun `filePropertyDrawer is empty for a plain heading-only file`() {
        val doc = OrgParser.parse("* just a heading\nbody\n")
        assertTrue(doc.filePropertyDrawer.isEmpty())
    }

    @Test
    fun `filePropertyDrawer degrades to empty for an unclosed drawer`() {
        val doc = OrgParser.parse(":PROPERTIES:\n:ID: x\n#+TITLE: T\n\n* Heading\nbody\n")
        assertTrue(doc.filePropertyDrawer.isEmpty())
    }

    @Test
    fun `filePropertyDrawer only matches a drawer on the very first line`() {
        // A drawer after keywords is not a file-level property drawer.
        val doc = OrgParser.parse("#+TITLE: T\n:PROPERTIES:\n:ID: x\n:END:\n\n* Heading\n")
        assertTrue(doc.filePropertyDrawer.isEmpty())
    }

    @Test
    fun `filePropertyDrawer ignores a same-named drawer on the first heading`() {
        val doc = OrgParser.parse("* Heading\n:PROPERTIES:\n:ID: abc\n:END:\nbody\n")
        assertTrue(doc.filePropertyDrawer.isEmpty())
        assertEquals("abc", doc.headlines[0].properties["ID"])
    }

    @Test
    fun `parses keyword priority and planning`() {
        val doc = OrgParser.parse(golden("travel.org"))

        val ryokan = doc.findByTitle("Book Kyoto ryokan")!!
        assertEquals("TODO", ryokan.keyword)
        assertEquals("2025-04-09", ryokan.planning.scheduled!!.date.toString())

        val passport = doc.findByTitle("Renew passport")!!
        assertEquals("TODO", passport.keyword)
        assertEquals('A', passport.priority)
        assertEquals(listOf("admin"), passport.tags)
        assertEquals("2025-06-27", passport.planning.deadline!!.date.toString())

        val railPass = doc.findByTitle("Order JR Rail Pass")!!
        assertEquals("DONE", railPass.keyword)
        assertNotNull(railPass.planning.closed)

        val review = doc.findByTitle("Weekly review")!!
        assertEquals(RepeaterType.CUMULATIVE, review.planning.scheduled!!.repeater!!.type)
    }

    @Test
    fun `inherited tags include ancestors and file tags`() {
        val doc = OrgParser.parse(golden("travel.org"))
        val lunch = doc.findByTitle("Lunch")!!
        assertEquals(listOf("kyoto", "japan", "trip"), doc.inheritedTags(lunch))
    }

    @Test
    fun `inheritedTagsAll matches per-headline inheritedTags`() {
        val doc = OrgParser.parse(golden("travel.org"))
        val all = doc.inheritedTagsAll()
        doc.headlines.forEachIndexed { i, h ->
            assertEquals(doc.inheritedTags(h), all[i])
        }
    }

    @Test
    fun `body excludes planning and properties`() {
        val doc = OrgParser.parse(golden("travel.org"))
        val ryokan = doc.findByTitle("Book Kyoto ryokan")!!
        assertEquals(
            listOf("Check the one with the garden view first.", ""),
            doc.bodyOf(ryokan),
        )
        val japan = doc.headlines.first()
        assertTrue(doc.bodyOf(japan).first().startsWith("Planning notes"))
    }

    // --- Edge cases ---

    @Test
    fun `keyword must be exact configured word`() {
        val doc = OrgParser.parse(golden("edge-cases.org"))
        assertNull(doc.findByTitle("TODOX is a title not a keyword")!!.keyword)
        val bare = doc.headlines.first { it.title.isEmpty() }
        assertEquals("TODO", bare.keyword)
    }

    @Test
    fun `priority parses without keyword`() {
        val doc = OrgParser.parse(golden("edge-cases.org"))
        val h = doc.findByTitle("Priority without keyword")!!
        assertEquals('B', h.priority)
        assertNull(h.keyword)
    }

    @Test
    fun `tags with spaces are not tags`() {
        val doc = OrgParser.parse(golden("edge-cases.org"))
        assertNotNull(doc.findByTitle("Tags that are not tags :not a tag:"))
    }

    @Test
    fun `tags with dashes are recognized as tags`() {
        val doc = OrgParser.parse("* A heading :floating-note:\n")
        val h = doc.headlines.first()
        assertEquals("A heading", h.title)
        assertEquals(listOf("floating-note"), h.tags)
    }

    @Test
    fun `indented or starless lines are not headlines`() {
        val doc = OrgParser.parse(golden("edge-cases.org"))
        assertNull(doc.headlines.firstOrNull { it.title.contains("Also not a heading") })
        assertNull(doc.headlines.firstOrNull { it.title.contains("no star prefix") })
    }

    @Test
    fun `unclosed properties drawer does not swallow the file`() {
        val doc = OrgParser.parse(golden("edge-cases.org"))
        val h = doc.findByTitle("Drawer not closed")!!
        // Drawer never closed → treated as body, no properties extracted
        assertTrue(h.properties.isEmpty())
        // Following headline still parsed
        assertNotNull(doc.headlines.firstOrNull { it.title.startsWith("Unicode") })
    }

    @Test
    fun `logbook drawer is parsed as raw lines, excluded from body`() {
        val doc = OrgParser.parse(
            "* DONE Ship the release\n" +
                "CLOSED: [2025-01-15 Wed 10:30]\n" +
                ":LOGBOOK:\n" +
                "- State \"DONE\"       from \"TODO\"       [2025-01-15 Wed 10:30]\n" +
                "CLOCK: [2025-01-15 Wed 09:00]--[2025-01-15 Wed 10:30] =>  1:30\n" +
                ":END:\n" +
                "Actual note body.\n"
        )
        val h = doc.headlines.first()
        assertEquals(
            listOf(
                "- State \"DONE\"       from \"TODO\"       [2025-01-15 Wed 10:30]",
                "CLOCK: [2025-01-15 Wed 09:00]--[2025-01-15 Wed 10:30] =>  1:30",
            ),
            h.logbook,
        )
        assertEquals(listOf("Actual note body.", ""), doc.bodyOf(h))
    }

    @Test
    fun `properties and logbook drawers parse together in either order`() {
        val propertiesFirst = OrgParser.parse(
            "* Heading\n:PROPERTIES:\n:ID: abc\n:END:\n:LOGBOOK:\nCLOCK: x\n:END:\nbody\n"
        ).headlines.first()
        assertEquals("abc", propertiesFirst.properties["ID"])
        assertEquals(listOf("CLOCK: x"), propertiesFirst.logbook)

        val logbookFirst = OrgParser.parse(
            "* Heading\n:LOGBOOK:\nCLOCK: x\n:END:\n:PROPERTIES:\n:ID: abc\n:END:\nbody\n"
        ).headlines.first()
        assertEquals("abc", logbookFirst.properties["ID"])
        assertEquals(listOf("CLOCK: x"), logbookFirst.logbook)
    }

    @Test
    fun `unclosed logbook drawer does not swallow the file`() {
        val doc = OrgParser.parse(
            "* Heading\n:LOGBOOK:\nCLOCK: x\n* Next heading\nbody\n"
        )
        val h = doc.findByTitle("Heading")!!
        assertTrue(h.logbook.isEmpty())
        assertNotNull(doc.findByTitle("Next heading"))
    }

    @Test
    fun `multiple keywords config is respected`() {
        val kw = OrgKeywords.parse("NEXT WAITING | DONE")
        val doc = OrgParser.parse("* NEXT Call bank\n* TODO not a keyword here", kw)
        assertEquals("NEXT", doc.headlines[0].keyword)
        assertNull(doc.headlines[1].keyword)
        assertEquals("TODO not a keyword here", doc.headlines[1].title)
    }

    // --- Bare active timestamps ---

    @Test
    fun `parses a bare active timestamp from the body`() {
        val doc = OrgParser.parse("* Concert\nsome context <2026-09-12 Fri> more text\n")
        val h = doc.headlines.first()
        assertEquals(1, h.activeTimestamps.size)
        assertEquals(java.time.LocalDate.of(2026, 9, 12), h.activeTimestamps[0].date)
    }

    @Test
    fun `parses a dedicated active-timestamp line and a ranged one`() {
        val doc = OrgParser.parse(
            "* Trip\n<2026-09-08 Mon>--<2026-09-10 Wed>\nbody\n* Party\n<2026-09-20 Sat>\n"
        )
        val trip = doc.findByTitle("Trip")!!
        assertEquals(1, trip.activeTimestamps.size)
        assertEquals(java.time.LocalDate.of(2026, 9, 10), trip.activeTimestamps[0].rangeEnd)
        val party = doc.findByTitle("Party")!!
        assertEquals(java.time.LocalDate.of(2026, 9, 20), party.activeTimestamps.single().date)
    }

    @Test
    fun `bodyWithoutDedicatedTimestamp is a no-op when the dedicated line is in its canonical slot`() {
        // Canonical slot (right after the heading, before any drawer): the
        // parser already excludes it from bodyOf, so there's nothing to drop.
        val doc = OrgParser.parse(
            "* Trip\n<2026-09-08 Mon>--<2026-09-10 Wed>\nPack the bags.\nBook the train.\n"
        )
        val trip = doc.findByTitle("Trip")!!
        assertEquals(listOf("Pack the bags.", "Book the train.", ""), doc.bodyOf(trip))
        assertEquals(doc.bodyOf(trip), doc.bodyWithoutDedicatedTimestamp(trip))
        assertEquals(trip.bodyStart, doc.bodyStartWithoutDedicatedTimestamp(trip))
    }

    @Test
    fun `bodyWithoutDedicatedTimestamp drops the leading bare stamp line for a pre-flip file`() {
        // Pre-flip position (below the drawer, as the body's own first line):
        // still recognized as dedicated, so it needs stripping out of the body.
        val doc = OrgParser.parse(
            "* Trip\n:PROPERTIES:\n:ID: t1\n:END:\n<2026-09-08 Mon>--<2026-09-10 Wed>\n" +
                "Pack the bags.\nBook the train.\n"
        )
        val trip = doc.findByTitle("Trip")!!
        assertEquals(
            listOf(java.time.LocalDate.of(2026, 9, 8)),
            trip.dedicatedActiveTimestamps.map { it.date },
        )
        assertEquals(listOf("Pack the bags.", "Book the train.", ""), doc.bodyWithoutDedicatedTimestamp(trip))
        assertEquals(trip.bodyStart + 1, doc.bodyStartWithoutDedicatedTimestamp(trip))
    }

    @Test
    fun `bodyWithoutDedicatedTimestamp is a no-op when the stamp is only inline`() {
        val doc = OrgParser.parse("* Note\nMeeting on <2026-09-08 Mon> as planned.\n")
        val note = doc.findByTitle("Note")!!
        assertEquals(doc.bodyOf(note), doc.bodyWithoutDedicatedTimestamp(note))
        assertEquals(note.bodyStart, doc.bodyStartWithoutDedicatedTimestamp(note))
    }

    @Test
    fun `active timestamps exclude descendants, planning and inactive stamps`() {
        val doc = OrgParser.parse(
            "* Parent\nSCHEDULED: <2026-09-01 Tue>\n:PROPERTIES:\n:CREATED: [2026-08-01 Sat]\n:END:\n" +
                "note [2026-08-15 Sat] and <2026-09-05 Sat>\n** Child\n<2026-09-30 Wed>\n"
        )
        val parent = doc.findByTitle("Parent")!!
        assertEquals(
            listOf(java.time.LocalDate.of(2026, 9, 5)),
            parent.activeTimestamps.map { it.date },
        )
    }
}
