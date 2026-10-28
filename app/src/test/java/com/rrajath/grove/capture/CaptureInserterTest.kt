package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class CaptureInserterTest {

    private val today = LocalDate.of(2025, 6, 11) // Wednesday

    private fun insert(doc: String, location: TargetLocation, entry: String) =
        CaptureInserter.insert(doc, location, entry, today)

    // --- top / bottom ---

    @Test
    fun `top of file inserts after preamble`() {
        val doc = "#+TITLE: X\n\n* First\nbody\n"
        val result = insert(doc, TargetLocation.TopOfFile, "* New entry")
        assertEquals("#+TITLE: X\n\n* New entry\n* First\nbody\n", result.newText)
        assertEquals(2, result.insertedAtLine)
    }

    @Test
    fun `bottom of file keeps trailing newline`() {
        val doc = "* First\nbody\n"
        val result = insert(doc, TargetLocation.BottomOfFile, "* New entry\ncontent")
        assertEquals("* First\nbody\n* New entry\ncontent\n", result.newText)
    }

    @Test
    fun `bottom of file without trailing newline`() {
        val result = insert("* First", TargetLocation.BottomOfFile, "* New")
        assertEquals("* First\n* New", result.newText)
    }

    @Test
    fun `empty file gets just the entry`() {
        val result = insert("", TargetLocation.BottomOfFile, "* New")
        assertEquals("* New\n", result.newText)
    }

    @Test
    fun `entry without stars becomes a heading`() {
        val result = insert("", TargetLocation.BottomOfFile, "plain text idea")
        assertEquals("* plain text idea\n", result.newText)
    }

    // --- under heading ---

    private val projectDoc = """
        * Projects :work:
        ** Existing child
        child body
        * Other
        :PROPERTIES:
        :CUSTOM_ID: other-id
        :END:
        other body
    """.trimIndent() + "\n"

    @Test
    fun `outlinePath walks ancestors outermost first, skipping siblings and cousins`() {
        val doc = com.rrajath.grove.org.OrgParser.parse(
            """
            * Areas
            ** Home
            *** Garden
            ** Work
            *** Meetings
            **** Standup
            *** Reviews
            * Inbox
            """.trimIndent(),
            com.rrajath.grove.org.OrgKeywords.DEFAULT,
        )
        fun pathTo(title: String) = CaptureInserter.outlinePath(doc, doc.findByTitle(title)!!)

        assertEquals(listOf("Areas", "Work", "Reviews"), pathTo("Reviews"))
        assertEquals(listOf("Areas", "Work", "Meetings", "Standup"), pathTo("Standup"))
        assertEquals(listOf("Inbox"), pathTo("Inbox"))
    }

    @Test
    fun `under heading by title appends as last child with releveling`() {
        val result = insert(
            projectDoc,
            TargetLocation.UnderHeading(title = "Projects"),
            "* New task\ndetails",
        )
        val expected = """
            * Projects :work:
            ** Existing child
            child body
            ** New task
            details
            * Other
            :PROPERTIES:
            :CUSTOM_ID: other-id
            :END:
            other body
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `under heading by custom id`() {
        val result = insert(
            projectDoc,
            TargetLocation.UnderHeading(customId = "other-id"),
            "note under other",
        )
        assertTrue(result.newText.contains("other body\n** note under other"))
    }

    private val idDoc = """
        * Inbox
        :PROPERTIES:
        :ID: AAAA-1111
        :END:
        inbox body
        * Journal
        :PROPERTIES:
        :CUSTOM_ID: journal
        :END:
        journal body
    """.trimIndent() + "\n"

    @Test
    fun `under heading by id matches the ID property`() {
        val result = insert(idDoc, TargetLocation.UnderHeading(id = "AAAA-1111"), "new")
        assertTrue(result.newText.contains("inbox body\n** new\n* Journal"))
    }

    @Test
    fun `under heading by id also matches a CUSTOM_ID`() {
        val result = insert(idDoc, TargetLocation.UnderHeading(id = "journal"), "new")
        assertTrue(result.newText.endsWith("journal body\n** new\n"))
    }

    @Test
    fun `legacy customId field still matches an ID property`() {
        val result = insert(idDoc, TargetLocation.UnderHeading(customId = "AAAA-1111"), "new")
        assertTrue(result.newText.contains("inbox body\n** new\n* Journal"))
    }

    @Test
    fun `id wins over legacy customId when both are set`() {
        val result = insert(
            idDoc,
            TargetLocation.UnderHeading(customId = "AAAA-1111", id = "journal"),
            "new",
        )
        assertTrue(result.newText.endsWith("journal body\n** new\n"))
    }

    // --- body-only (content into an existing heading's body) ---

    private val shoppingDoc = """
        * Shopping List
        :PROPERTIES:
        :ID: SHOP
        :END:
        - [ ] Milk
        - [ ] Eggs

        ** Hardware store
        - [ ] Nails
        * Other
    """.trimIndent() + "\n"

    @Test
    fun `body-only appends to the heading's own body, above children and trailing blanks`() {
        val result = CaptureInserter.insert(
            shoppingDoc, TargetLocation.UnderHeading(id = "SHOP"), "- [ ] Bread", today, bodyOnly = true,
        )
        assertTrue(result.newText.contains("- [ ] Eggs\n- [ ] Bread\n\n** Hardware store"))
        assertEquals(6, result.insertedAtLine)
    }

    @Test
    fun `body-only first position goes right after the drawers`() {
        val result = CaptureInserter.insert(
            shoppingDoc, TargetLocation.UnderHeading(id = "SHOP", appendLast = false), "- [ ] Bread", today,
            bodyOnly = true,
        )
        assertTrue(result.newText.contains(":END:\n- [ ] Bread\n- [ ] Milk"))
    }

    @Test
    fun `body-only keeps multi-line entries verbatim and never adds stars`() {
        val result = CaptureInserter.insert(
            "* Log\n", TargetLocation.UnderHeading(title = "Log"), "- a\n  - b\n", today, bodyOnly = true,
        )
        assertEquals("* Log\n- a\n  - b\n", result.newText)
    }

    @Test
    fun `body-only draft can be removed and re-inserted in place`() {
        val loc = TargetLocation.UnderHeading(id = "SHOP")
        val first = CaptureInserter.insert(shoppingDoc, loc, "- [ ] Br", today, bodyOnly = true)
        val base = CaptureInserter.removeInsertion(first.newText, first)
        assertEquals(shoppingDoc, base)
    }

    @Test
    fun `isBodyOnly only for under-heading templates without a leading heading`() {
        val under = TargetLocation.UnderHeading(title = "Shopping List")
        assertTrue(CaptureInserter.isBodyOnly("- [ ] ", under))
        assertTrue(CaptureInserter.isBodyOnly("\nplain text", under))
        assertFalse(CaptureInserter.isBodyOnly("* TODO item", under))
        assertFalse(CaptureInserter.isBodyOnly("\n** item", under))
        assertFalse(CaptureInserter.isBodyOnly("- [ ] ", TargetLocation.BottomOfFile))
    }

    @Test
    fun `under heading first-child position`() {
        val result = insert(
            projectDoc,
            TargetLocation.UnderHeading(title = "Projects", appendLast = false),
            "* New first",
        )
        assertTrue(result.newText.contains("* Projects :work:\n** New first\n** Existing child"))
    }

    @Test
    fun `missing heading throws`() {
        assertThrows(CaptureInserter.CaptureTargetNotFound::class.java) {
            insert(projectDoc, TargetLocation.UnderHeading(title = "Nope"), "x")
        }
    }

    @Test
    fun `untouched content is byte-identical around the insertion`() {
        val result = insert(
            projectDoc,
            TargetLocation.UnderHeading(title = "Projects"),
            "* New task",
        )
        val inserted = "** New task\n"
        assertEquals(projectDoc, result.newText.replace(inserted, ""))
    }

    // --- datetree ---

    @Test
    fun `datetree creates full hierarchy in empty file`() {
        val result = insert("", TargetLocation.Datetree(), "<2025-06-11 Wed 14:32>\nFerry back")
        val expected = """
            * 2025
            ** 2025-06 June
            *** 2025-06-11 Wednesday
            **** <2025-06-11 Wed 14:32>
            Ferry back
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `datetree names follow the device locale`() = withLocale(Locale.GERMANY) {
        val result = insert("", TargetLocation.Datetree(), "entry")
        val expected = """
            * 2025
            ** 2025-06 Juni
            *** 2025-06-11 Mittwoch
            **** entry
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `datetree reuses nodes named in another language`() {
        // Written by Emacs in German; inserting from an English device must not
        // add a second "2025-06 June" branch above them.
        val doc = """
            * 2025
            ** 2025-05 Mai
            ** 2025-06 Juni
            *** 2025-06-10 Dienstag
            **** old entry
            *** 2025-06-11 Mittwoch
            **** morning
            ** 2025-07 Juli
        """.trimIndent() + "\n"
        val result = insert(doc, TargetLocation.Datetree(), "afternoon")
        val expected = """
            * 2025
            ** 2025-05 Mai
            ** 2025-06 Juni
            *** 2025-06-10 Dienstag
            **** old entry
            *** 2025-06-11 Mittwoch
            **** morning
            **** afternoon
            ** 2025-07 Juli
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `datetree places a new day before a later day named in another language`() {
        val doc = "* 2025\n** 2025-06 Juni\n*** 2025-06-12 Donnerstag\n"
        val result = insert(doc, TargetLocation.Datetree(), "entry")
        assertEquals(
            "* 2025\n** 2025-06 Juni\n*** 2025-06-11 Wednesday\n**** entry\n*** 2025-06-12 Donnerstag\n",
            result.newText,
        )
    }

    @Test
    fun `datetree reuses existing hierarchy and appends to day`() {
        val doc = """
            * 2025
            ** 2025-06 June
            *** 2025-06-11 Wednesday
            **** 09:00 Morning entry
            text
        """.trimIndent() + "\n"
        val result = insert(doc, TargetLocation.Datetree(), "14:32 Afternoon")
        val expected = doc + "**** 14:32 Afternoon\n"
        assertEquals(expected.trimEnd('\n') + "\n", result.newText)
    }

    @Test
    fun `datetree creates missing day under existing month`() {
        val doc = """
            * 2025
            ** 2025-06 June
            *** 2025-06-10 Tuesday
            **** old entry
        """.trimIndent() + "\n"
        val result = insert(doc, TargetLocation.Datetree(), "new entry")
        val expected = """
            * 2025
            ** 2025-06 June
            *** 2025-06-10 Tuesday
            **** old entry
            *** 2025-06-11 Wednesday
            **** new entry
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `datetree inserts day in chronological position`() {
        val doc = """
            * 2025
            ** 2025-06 June
            *** 2025-06-10 Tuesday
            *** 2025-06-14 Saturday
        """.trimIndent() + "\n"
        val result = insert(doc, TargetLocation.Datetree(), "entry")
        val expected = """
            * 2025
            ** 2025-06 June
            *** 2025-06-10 Tuesday
            *** 2025-06-11 Wednesday
            **** entry
            *** 2025-06-14 Saturday
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `datetree leaves non-date headings alone`() {
        val doc = """
            * Inbox
            stuff
            * 2024
            ** 2024-12 December
        """.trimIndent() + "\n"
        val result = insert(doc, TargetLocation.Datetree(), "entry")
        assertTrue(result.newText.startsWith("* Inbox\nstuff\n"))
        assertTrue(result.newText.contains("* 2024\n** 2024-12 December\n* 2025\n** 2025-06 June\n*** 2025-06-11 Wednesday\n**** entry"))
    }

    @Test
    fun `datetree year inserted before later year`() {
        val doc = "* 2026\n** 2026-01 January\n"
        val result = insert(doc, TargetLocation.Datetree(), "entry")
        assertTrue(
            result.newText.indexOf("* 2025") < result.newText.indexOf("* 2026")
        )
    }

    // --- outline path / datetree inside a heading / tree types ---

    private val nestedDoc = """
        * Inbox
        ** Tasks
        * Work
        :PROPERTIES:
        :ID: WORK-1
        :END:
        ** Tasks
        *** existing
    """.trimIndent() + "\n"

    @Test
    fun `under heading by outline path picks the nested heading, not the first same-named one`() {
        val result = insert(nestedDoc, TargetLocation.UnderHeading(path = listOf("Work", "Tasks")), "* new")
        assertTrue(result.newText, result.newText.endsWith("** Tasks\n*** existing\n*** new\n"))
        assertTrue(result.newText.startsWith("* Inbox\n** Tasks\n* Work\n"))
    }

    @Test
    fun `under heading id wins over a stale outline path`() {
        val result = insert(nestedDoc, TargetLocation.UnderHeading(id = "WORK-1", path = listOf("Gone")), "* new")
        assertTrue(result.newText, result.newText.endsWith("*** existing\n** new\n"))
    }

    @Test
    fun `under heading with a missing outline path throws`() {
        assertThrows(CaptureInserter.CaptureTargetNotFound::class.java) {
            insert(nestedDoc, TargetLocation.UnderHeading(path = listOf("Work", "Nope")), "x")
        }
    }

    @Test
    fun `datetree inside a heading nests the tree one level below it`() {
        val doc = "* Journal\n* Other\n"
        val result = insert(doc, TargetLocation.Datetree(path = listOf("Journal")), "entry")
        val expected = """
            * Journal
            ** 2025
            *** 2025-06 June
            **** 2025-06-11 Wednesday
            ***** entry
            * Other
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `datetree inside a heading found by id reuses its existing tree`() {
        val doc = """
            * Work
            :PROPERTIES:
            :ID: WORK-1
            :END:
            ** 2025
            *** 2025-06 June
            **** 2025-06-11 Wednesday
            ***** first
        """.trimIndent() + "\n"
        val result = insert(doc, TargetLocation.Datetree(id = "WORK-1", path = listOf("Renamed")), "second")
        assertEquals(doc + "***** second\n", result.newText)
    }

    @Test
    fun `datetree inside a missing heading throws`() {
        assertThrows(CaptureInserter.CaptureTargetNotFound::class.java) {
            insert("* Journal\n", TargetLocation.Datetree(path = listOf("Nope")), "x")
        }
    }

    @Test
    fun `week tree groups days under the ISO week`() {
        val result = insert("", TargetLocation.Datetree(treeType = DatetreeType.WEEK), "entry")
        val expected = """
            * 2025
            ** 2025-W24
            *** 2025-06-11 Wednesday
            **** entry
        """.trimIndent() + "\n"
        assertEquals(expected, result.newText)
    }

    @Test
    fun `week tree uses the ISO week-based year at a year boundary`() {
        // 2027-01-01 is a Friday in ISO week 53 of 2026.
        val result = CaptureInserter.insert(
            "", TargetLocation.Datetree(treeType = DatetreeType.WEEK), "entry", LocalDate.of(2027, 1, 1),
        )
        assertEquals("* 2026\n** 2026-W53\n*** 2027-01-01 Friday\n**** entry\n", result.newText)
    }

    @Test
    fun `month tree puts entries under the month`() {
        val doc = "* 2025\n** 2025-05 May\n*** may entry\n"
        val result = insert(doc, TargetLocation.Datetree(treeType = DatetreeType.MONTH), "entry")
        assertEquals("* 2025\n** 2025-05 May\n*** may entry\n** 2025-06 June\n*** entry\n", result.newText)
    }

    @Test
    fun `month tree entry is prefilled with month-level stars`() {
        val result = CaptureInserter.withHeadingStars(
            ExpandedTemplate("", 0), TargetLocation.Datetree(treeType = DatetreeType.MONTH),
        )
        assertEquals("*** ", result.text)
    }

    @Test
    fun `datetree inside a heading has no static entry level`() {
        val result = CaptureInserter.withHeadingStars(
            ExpandedTemplate("", 0), TargetLocation.Datetree(path = listOf("Journal")),
        )
        assertEquals("", result.text)
    }

    // --- editor star prefill ---

    @Test
    fun `datetree entry is prefilled with day-level stars`() {
        val result = CaptureInserter.withHeadingStars(
            ExpandedTemplate("", 0), TargetLocation.Datetree(),
        )
        assertEquals("**** ", result.text)
        assertEquals(5, result.cursorOffset)
    }

    @Test
    fun `star prefill keeps the cursor in place relative to the template`() {
        val result = CaptureInserter.withHeadingStars(
            ExpandedTemplate("<2025-06-11 Wed> \nbody", 17), TargetLocation.Datetree(),
        )
        assertEquals("**** <2025-06-11 Wed> \nbody", result.text)
        assertEquals(22, result.cursorOffset)
    }

    @Test
    fun `templates that already start with a heading are untouched`() {
        val expanded = ExpandedTemplate("* My title\nbody", 15)
        assertEquals(expanded, CaptureInserter.withHeadingStars(expanded, TargetLocation.BottomOfFile))
    }

    @Test
    fun `top and bottom of file prefill a single star`() {
        assertEquals(
            "* note",
            CaptureInserter.withHeadingStars(ExpandedTemplate("note", 0), TargetLocation.TopOfFile).text,
        )
    }

    @Test
    fun `under-heading entries are not prefilled`() {
        val expanded = ExpandedTemplate("note", 0)
        assertEquals(
            expanded,
            CaptureInserter.withHeadingStars(expanded, TargetLocation.UnderHeading(title = "Inbox")),
        )
    }

    @Test
    fun `prefilled stars round-trip through datetree insertion unchanged`() {
        val typed = "**** My day\nIt went well"
        val result = insert("", TargetLocation.Datetree(), typed)
        assertTrue(result.newText.contains("*** 2025-06-11 Wednesday\n**** My day\nIt went well"))
    }

    // --- autosave draft replacement (entryLineCount / removeInsertion) ---

    @Test
    fun `entryLineCount reports the number of lines the entry occupies`() {
        val single = insert("* First\n", TargetLocation.BottomOfFile, "* New entry")
        assertEquals(1, single.entryLineCount)

        val multi = insert("* First\n", TargetLocation.BottomOfFile, "* New entry\nline two\nline three")
        assertEquals(3, multi.entryLineCount)
    }

    @Test
    fun `removeInsertion strips exactly the previously spliced lines`() {
        val doc = "* First\nbody\n"
        val first = insert(doc, TargetLocation.BottomOfFile, "* Draft\nmore text")
        assertTrue(first.newText.contains("* Draft\nmore text"))

        val stripped = CaptureInserter.removeInsertion(first.newText, first)
        assertEquals(doc, stripped)
    }

    @Test
    fun `autosave replace cycle never duplicates the draft`() {
        val doc = "* Inbox\n"
        var current = doc
        var previous: CaptureInserter.Insertion? = null
        for (draftText in listOf("* Buy milk", "* Buy milk and eggs", "* Buy milk, eggs and bread")) {
            val base = previous?.let { CaptureInserter.removeInsertion(current, it) } ?: current
            val result = insert(base, TargetLocation.BottomOfFile, draftText)
            current = result.newText
            previous = result
        }
        assertEquals(1, Regex("\\* Buy milk").findAll(current).count())
        assertTrue(current.contains("* Buy milk, eggs and bread"))
    }

    @Test
    fun `datetree autosave replace only strips the entry, not the day tree`() {
        var current = ""
        var previous: CaptureInserter.Insertion? = null
        for (draftText in listOf("14:00 Draft one", "14:00 Draft one updated")) {
            val base = previous?.let { CaptureInserter.removeInsertion(current, it) } ?: current
            val result = insert(base, TargetLocation.Datetree(), draftText)
            current = result.newText
            previous = result
        }
        assertEquals(1, Regex("\\*\\*\\* 2025-06-11 Wednesday").findAll(current).count())
        assertTrue(current.contains("14:00 Draft one updated"))
        assertTrue(!current.contains("14:00 Draft one\n"))
    }

    // --- under heading path (widget quick-add target) ---

    @Test
    fun `heading path appends as last child of the nested heading`() {
        val doc = "* Work\n** Inbox\n*** TODO old\n** Later\n* Inbox\n"
        val result = CaptureInserter.insertUnderPath(doc, listOf("Work", "Inbox"), "TODO new")!!
        assertEquals("* Work\n** Inbox\n*** TODO old\n*** TODO new\n** Later\n* Inbox\n", result.newText)
        assertEquals(3, result.insertedAtLine)
    }

    @Test
    fun `empty heading path appends a top-level heading at the bottom`() {
        val result = CaptureInserter.insertUnderPath("* First\nbody\n", emptyList(), "TODO new")!!
        assertEquals("* First\nbody\n* TODO new\n", result.newText)
    }

    @Test
    fun `missing heading path returns null`() {
        val doc = "* Work\n** Inbox\n"
        assertEquals(null, CaptureInserter.insertUnderPath(doc, listOf("Work", "Someday"), "TODO new"))
        assertEquals(null, CaptureInserter.insertUnderPath(doc, listOf("Inbox"), "TODO new"))
    }

    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(saved)
        }
    }
}
