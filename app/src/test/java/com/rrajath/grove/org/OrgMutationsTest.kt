package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OrgMutationsTest {

    private val doc = OrgParser.parse(
        """
        #+TITLE: Test

        * First :tag:
        body one

        ** TODO [#A] Child task  :work:
        SCHEDULED: <2025-06-09 Mon +1w>
        child body
        ** Second child
        * TODO Last
        DEADLINE: <2025-06-27 Fri>
        last body
        """.trimIndent() + "\n"
    )

    private fun h(title: String) = doc.headlines.first { it.title == title }

    @Test
    fun `setKeyword rewrites only the headline line`() {
        val result = OrgMutations.setKeyword(doc, h("First"), "TODO")
        assertTrue(result.contains("* TODO First  :tag:"))
        // everything else byte-identical
        assertEquals(
            doc.text.lines().filterIndexed { i, _ -> i != h("First").lineIndex },
            result.lines().filterIndexed { i, _ -> i != h("First").lineIndex },
        )
    }

    @Test
    fun `clearing keyword and priority`() {
        val child = h("Child task")
        val noKw = OrgParser.parse(OrgMutations.setKeyword(doc, child, null))
        assertNull(noKw.findByTitle("Child task")!!.keyword)
        assertEquals('A', noKw.findByTitle("Child task")!!.priority)

        val noPrio = OrgParser.parse(OrgMutations.setPriority(doc, child, null))
        assertNull(noPrio.findByTitle("Child task")!!.priority)
        assertEquals("TODO", noPrio.findByTitle("Child task")!!.keyword)
    }

    @Test
    fun `setTags adds and removes`() {
        val tagged = OrgParser.parse(OrgMutations.setTags(doc, h("Second child"), listOf("a", "b")))
        assertEquals(listOf("a", "b"), tagged.findByTitle("Second child")!!.tags)

        val untagged = OrgParser.parse(OrgMutations.setTags(doc, h("First"), emptyList()))
        assertEquals(emptyList<String>(), untagged.findByTitle("First")!!.tags)
    }

    /**
     * Regression for a metadata-sheet bug: the sheet edits and displays a
     * heading's *own* tags only (org semantics — you can't un-inherit a
     * parent's tag from a child), so removing a redundant own tag that's
     * also carried by a parent heading must clear it from [OrgHeadline.tags]
     * even though [OrgDocument.inheritedTags] still (correctly) reports it
     * via the ancestor. A read-mode view that renders inheritedTags for a
     * heading's own tag chips would make this successful removal look like
     * a no-op.
     */
    @Test
    fun `removing a tag also carried by a parent clears only the child's own tags`() {
        val nested = OrgParser.parse(
            """
            * Projects :work:
            ** Redesign the app :work:urgent:
            body
            """.trimIndent() + "\n"
        )
        val child = nested.findByTitle("Redesign the app")!!
        assertEquals(listOf("work", "urgent"), child.tags)
        assertEquals(listOf("work", "urgent"), nested.inheritedTags(child))

        val result = OrgParser.parse(OrgMutations.setTags(nested, child, child.tags - "work"))
        val newChild = result.findByTitle("Redesign the app")!!
        assertEquals(listOf("urgent"), newChild.tags)
        // Still inherited from "Projects" — org semantics, not a bug.
        assertEquals(listOf("urgent", "work"), result.inheritedTags(newChild))
    }

    @Test
    fun `setScheduled inserts planning line when missing`() {
        val ts = OrgTimestamp.parse("<2025-07-01 Tue>")!!
        val result = OrgParser.parse(OrgMutations.setScheduled(doc, h("First"), ts))
        assertEquals(ts, result.findByTitle("First")!!.planning.scheduled)
        // body unchanged
        assertTrue(result.text.contains("body one"))
    }

    @Test
    fun `setScheduled null removes the whole planning line when empty`() {
        val result = OrgMutations.setDeadline(doc, h("Last"), null)
        assertTrue(!result.contains("DEADLINE: <2025-06-27 Fri>"))
        assertTrue(result.contains("last body"))
    }

    @Test
    fun `setDeadline preserves existing scheduled`() {
        val child = h("Child task")
        val ts = OrgTimestamp.parse("<2025-08-01 Fri>")!!
        val result = OrgParser.parse(OrgMutations.setDeadline(doc, child, ts))
        val planning = result.findByTitle("Child task")!!.planning
        assertEquals(ts, planning.deadline)
        assertEquals("<2025-06-09 Mon +1w>", planning.scheduled!!.format())
    }

    @Test
    fun `markDone advances repeater and keeps keyword`() {
        val child = h("Child task")
        val result = OrgParser.parse(
            OrgMutations.markDone(doc, child, "DONE", LocalDateTime.of(2025, 6, 11, 14, 0))
        )
        val after = result.findByTitle("Child task")!!
        assertEquals("TODO", after.keyword)
        assertEquals("<2025-06-16 Mon +1w>", after.planning.scheduled!!.format())
        assertNull(after.planning.closed)
    }

    @Test
    fun `advanceActiveTimestamp advances only the matching dedicated stamp`() {
        val doc = OrgParser.parse(
            "* Standup\n<2025-06-09 Mon +1w> <2025-06-10 Tue>\n"
        )
        val h = doc.findByTitle("Standup")!!
        val result = OrgParser.parse(
            OrgMutations.advanceActiveTimestamp(
                doc, h, java.time.LocalDate.of(2025, 6, 9), LocalDateTime.of(2025, 6, 11, 9, 0),
            )!!
        )
        val stamps = result.findByTitle("Standup")!!.dedicatedActiveTimestamps
        assertEquals("<2025-06-16 Mon +1w>", stamps[0].format())
        // The non-repeating sibling stamp on the same line is untouched.
        assertEquals("<2025-06-10 Tue>", stamps[1].format())
    }

    @Test
    fun `advanceActiveTimestamp returns null when the date has no repeater`() {
        val doc = OrgParser.parse("* Holiday\n<2025-06-09 Mon>\n")
        val h = doc.findByTitle("Holiday")!!
        assertNull(
            OrgMutations.advanceActiveTimestamp(
                doc, h, java.time.LocalDate.of(2025, 6, 9), LocalDateTime.of(2025, 6, 11, 9, 0),
            )
        )
    }

    @Test
    fun `advanceActiveTimestamp ignores a repeater typed inline in prose`() {
        // Not on the dedicated first line, so there's no managed line to rewrite.
        val doc = OrgParser.parse("* Standup\nSee you at <2025-06-09 Mon +1w>.\n")
        val h = doc.findByTitle("Standup")!!
        assertNull(
            OrgMutations.advanceActiveTimestamp(
                doc, h, java.time.LocalDate.of(2025, 6, 9), LocalDateTime.of(2025, 6, 11, 9, 0),
            )
        )
    }

    @Test
    fun `advanceRepeatingPlanning advances a keyword-less SCHEDULED repeater with no keyword or logbook side effects`() {
        val doc = OrgParser.parse("* Take out compost\nSCHEDULED: <2025-06-09 Mon +1w>\n")
        val h = doc.findByTitle("Take out compost")!!
        val result = OrgParser.parse(
            OrgMutations.advanceRepeatingPlanning(
                doc, h, PlanningKind.SCHEDULED, LocalDateTime.of(2025, 6, 11, 9, 0),
            )!!
        )
        val after = result.findByTitle("Take out compost")!!
        assertEquals("<2025-06-16 Mon +1w>", after.planning.scheduled!!.format())
        assertNull(after.keyword)
        assertTrue(after.logbook.isEmpty())
        assertNull(after.properties["LAST_REPEAT"])
    }

    @Test
    fun `advanceRepeatingPlanning advances only DEADLINE, leaving a co-existing SCHEDULED untouched`() {
        val doc = OrgParser.parse(
            "* Renew passport\nSCHEDULED: <2025-06-09 Mon +1w> DEADLINE: <2025-06-20 Fri +1y>\n"
        )
        val h = doc.findByTitle("Renew passport")!!
        val result = OrgParser.parse(
            OrgMutations.advanceRepeatingPlanning(
                doc, h, PlanningKind.DEADLINE, LocalDateTime.of(2025, 6, 11, 9, 0),
            )!!
        )
        val after = result.findByTitle("Renew passport")!!
        assertEquals("<2026-06-20 Sat +1y>", after.planning.deadline!!.format())
        // The row's own scoping (per-row advance, not "advance everything repeating
        // on this heading") leaves the sibling SCHEDULED stamp exactly as it was.
        assertEquals("<2025-06-09 Mon +1w>", after.planning.scheduled!!.format())
    }

    @Test
    fun `advanceRepeatingPlanning returns null when that planning field has no repeater`() {
        val doc = OrgParser.parse("* One-off errand\nSCHEDULED: <2025-06-09 Mon>\n")
        val h = doc.findByTitle("One-off errand")!!
        assertNull(
            OrgMutations.advanceRepeatingPlanning(
                doc, h, PlanningKind.SCHEDULED, LocalDateTime.of(2025, 6, 11, 9, 0),
            )
        )
    }

    @Test
    fun `advanceRepeatingPlanning returns null when that planning field is absent`() {
        val doc = OrgParser.parse("* No deadline here\nSCHEDULED: <2025-06-09 Mon +1w>\n")
        val h = doc.findByTitle("No deadline here")!!
        assertNull(
            OrgMutations.advanceRepeatingPlanning(
                doc, h, PlanningKind.DEADLINE, LocalDateTime.of(2025, 6, 11, 9, 0),
            )
        )
    }

    @Test
    fun `markDone without repeater sets keyword and CLOSED`() {
        val result = OrgParser.parse(
            OrgMutations.markDone(doc, h("Last"), "DONE", LocalDateTime.of(2025, 6, 11, 14, 30))
        )
        val after = result.findByTitle("Last")!!
        assertEquals("DONE", after.keyword)
        assertEquals("[2025-06-11 Wed 14:30]", after.planning.closed!!.format())
    }

    @Test
    fun `markDone with repeater logs the state change and stamps LAST_REPEAT`() {
        val child = h("Child task")
        val result = OrgMutations.markDone(doc, child, "DONE", LocalDateTime.of(2025, 6, 11, 14, 0))
        assertTrue(result.contains("- State \"DONE\"       from \"TODO\"       [2025-06-11 Wed 14:00]"))
        val after = OrgParser.parse(result).findByTitle("Child task")!!
        assertEquals("[2025-06-11 Wed 14:00]", after.properties["LAST_REPEAT"])
        assertEquals(
            listOf("- State \"DONE\"       from \"TODO\"       [2025-06-11 Wed 14:00]"),
            after.logbook,
        )
        // Org convention: PROPERTIES precedes LOGBOOK.
        assertTrue(result.indexOf(":PROPERTIES:") < result.indexOf(":LOGBOOK:"))
    }

    private val doneDoc = OrgParser.parse(
        """
        * DONE Wrap up
        CLOSED: [2025-06-11 Wed 14:30]
        """.trimIndent() + "\n"
    )

    @Test
    fun `changeKeyword away from a done state to a todo keyword drops CLOSED`() {
        val result = OrgParser.parse(
            OrgMutations.changeKeyword(doneDoc, doneDoc.headlines.first(), "TODO", doneDoc.keywords, LocalDateTime.now())
        )
        val after = result.headlines.first()
        assertEquals("TODO", after.keyword)
        assertNull(after.planning.closed)
    }

    @Test
    fun `changeKeyword to no keyword at all also drops CLOSED`() {
        val result = OrgParser.parse(
            OrgMutations.changeKeyword(doneDoc, doneDoc.headlines.first(), null, doneDoc.keywords, LocalDateTime.now())
        )
        val after = result.headlines.first()
        assertNull(after.keyword)
        assertNull(after.planning.closed)
    }

    @Test
    fun `changeKeyword to a done keyword applies markDone semantics`() {
        val result = OrgParser.parse(
            OrgMutations.changeKeyword(doc, h("Last"), "DONE", doc.keywords, LocalDateTime.of(2025, 6, 11, 14, 30))
        )
        val after = result.findByTitle("Last")!!
        assertEquals("DONE", after.keyword)
        assertEquals("[2025-06-11 Wed 14:30]", after.planning.closed!!.format())
    }

    @Test
    fun `markDone from a todo keyword logs the state change at the top of LOGBOOK`() {
        val withLog = OrgParser.parse(
            """
            * TODO Task
            :PROPERTIES:
            :ID: x
            :END:
            :LOGBOOK:
            - State "DONE"       from "TODO"       [2026-10-01 Thu 08:00]
            :END:
            body
            """.trimIndent() + "\n"
        )
        val result = OrgMutations.changeKeyword(
            withLog, withLog.headlines.first(), "DONE", withLog.keywords, LocalDateTime.of(2026, 10, 15, 9, 5),
        )
        assertEquals(
            """
            * DONE Task
            CLOSED: [2026-10-15 Thu 09:05]
            :PROPERTIES:
            :ID: x
            :END:
            :LOGBOOK:
            - State "DONE"       from "TODO"       [2026-10-15 Thu 09:05]
            - State "DONE"       from "TODO"       [2026-10-01 Thu 08:00]
            :END:
            body
            """.trimIndent() + "\n",
            result,
        )
    }

    @Test
    fun `markDone from no keyword creates the LOGBOOK with an empty from state`() {
        val plain = OrgParser.parse("* Task\nbody\n")
        val result = OrgMutations.changeKeyword(
            plain, plain.headlines.first(), "DONE", plain.keywords, LocalDateTime.of(2026, 10, 15, 9, 5),
        )
        assertEquals(
            "* DONE Task\nCLOSED: [2026-10-15 Thu 09:05]\n:LOGBOOK:\n" +
                "- State \"DONE\"       from \"\"           [2026-10-15 Thu 09:05]\n:END:\nbody\n",
            result,
        )
    }

    @Test
    fun `done to done refreshes CLOSED without a LOGBOOK entry`() {
        val result = OrgParser.parse(
            OrgMutations.changeKeyword(
                doneDoc, doneDoc.headlines.first(), "CANCELLED", doneDoc.keywords, LocalDateTime.of(2026, 10, 15, 9, 5),
            )
        )
        val after = result.headlines.first()
        assertEquals("CANCELLED", after.keyword)
        assertEquals("[2026-10-15 Thu 09:05]", after.planning.closed!!.format())
        assertTrue(after.logbook.isEmpty())
    }

    @Test
    fun `changeKeyword between two open keywords is a plain rewrite`() {
        val result = OrgParser.parse(
            OrgMutations.changeKeyword(doc, h("Last"), "IN-PROGRESS", doc.keywords, LocalDateTime.now())
        )
        val after = result.findByTitle("Last")!!
        assertEquals("IN-PROGRESS", after.keyword)
        assertNull(after.planning.closed)
    }

    @Test
    fun `appendLogbookEntry prepends newest entry first`() {
        val once = OrgMutations.appendLogbookEntry(doc, h("Last"), "- entry one")
        val redoc = OrgParser.parse(once)
        assertEquals(listOf("- entry one"), redoc.findByTitle("Last")!!.logbook)

        val twice = OrgMutations.appendLogbookEntry(redoc, redoc.findByTitle("Last")!!, "- entry two")
        val redoc2 = OrgParser.parse(twice)
        assertEquals(listOf("- entry two", "- entry one"), redoc2.findByTitle("Last")!!.logbook)
    }

    @Test
    fun `appendLogbookEntry creates drawer after an existing PROPERTIES drawer`() {
        val withProps = OrgParser.parse(
            """
            * Task
            :PROPERTIES:
            :ID: abc
            :END:
            body
            """.trimIndent() + "\n"
        )
        val result = OrgMutations.appendLogbookEntry(withProps, withProps.findByTitle("Task")!!, "- entry")
        val redoc = OrgParser.parse(result)
        assertEquals(listOf("- entry"), redoc.findByTitle("Task")!!.logbook)
        assertEquals("abc", redoc.findByTitle("Task")!!.properties["ID"])
        assertTrue(result.indexOf(":PROPERTIES:") < result.indexOf(":LOGBOOK:"))
    }

    @Test
    fun `appendLogbookNote creates the drawer when missing`() {
        val ts = OrgTimestamp(java.time.LocalDate.of(2026, 7, 29), java.time.LocalTime.of(12, 25), active = false)
        val result = OrgMutations.appendLogbookNote(doc, h("Last"), "This is a test note", ts)
        val redoc = OrgParser.parse(result)
        assertEquals(
            listOf("- Note taken on [2026-07-29 Wed 12:25] \\\\", "  This is a test note"),
            redoc.findByTitle("Last")!!.logbook,
        )
    }

    @Test
    fun `appendLogbookNote goes after existing history but above the most recent note`() {
        val ts1 = OrgTimestamp(java.time.LocalDate.of(2026, 7, 29), java.time.LocalTime.of(12, 25), active = false)
        val ts2 = OrgTimestamp(java.time.LocalDate.of(2026, 7, 30), java.time.LocalTime.of(9, 0), active = false)

        // History entry already present (e.g. a DONE state change), same as markDone writes.
        val withHistory = OrgParser.parse(
            OrgMutations.appendLogbookEntry(doc, h("Last"), "- State \"DONE\"       from \"TODO\"       [2026-07-28 Tue 08:00]")
        )
        val withFirstNote = OrgParser.parse(
            OrgMutations.appendLogbookNote(withHistory, withHistory.findByTitle("Last")!!, "first note", ts1)
        )
        assertEquals(
            listOf(
                "- State \"DONE\"       from \"TODO\"       [2026-07-28 Tue 08:00]",
                "- Note taken on [2026-07-29 Wed 12:25] \\\\",
                "  first note",
            ),
            withFirstNote.findByTitle("Last")!!.logbook,
        )

        val withSecondNote = OrgParser.parse(
            OrgMutations.appendLogbookNote(withFirstNote, withFirstNote.findByTitle("Last")!!, "second note", ts2)
        )
        assertEquals(
            listOf(
                "- State \"DONE\"       from \"TODO\"       [2026-07-28 Tue 08:00]",
                "- Note taken on [2026-07-30 Thu 09:00] \\\\",
                "  second note",
                "- Note taken on [2026-07-29 Wed 12:25] \\\\",
                "  first note",
            ),
            withSecondNote.findByTitle("Last")!!.logbook,
        )
    }

    @Test
    fun `upsertProperty creates a drawer then updates the existing key in place`() {
        val created = OrgMutations.upsertProperty(doc, h("Last"), "LAST_REPEAT", "[2025-06-11 Wed 14:00]")
        val redoc = OrgParser.parse(created)
        assertEquals("[2025-06-11 Wed 14:00]", redoc.findByTitle("Last")!!.properties["LAST_REPEAT"])

        val updated = OrgMutations.upsertProperty(redoc, redoc.findByTitle("Last")!!, "LAST_REPEAT", "[2025-06-18 Wed 09:00]")
        val redoc2 = OrgParser.parse(updated)
        assertEquals("[2025-06-18 Wed 09:00]", redoc2.findByTitle("Last")!!.properties["LAST_REPEAT"])
        assertEquals(1, updated.lines().count { it.trim().startsWith(":LAST_REPEAT:") })
    }

    @Test
    fun `upsertProperty inserts into an existing PROPERTIES drawer before END`() {
        val withProps = OrgParser.parse(
            """
            * Task
            :PROPERTIES:
            :ID: abc
            :END:
            body
            """.trimIndent() + "\n"
        )
        val result = OrgMutations.upsertProperty(
            withProps, withProps.findByTitle("Task")!!, "LAST_REPEAT", "[2025-06-11 Wed 14:00]",
        )
        val redoc = OrgParser.parse(result)
        val props = redoc.findByTitle("Task")!!.properties
        assertEquals("abc", props["ID"])
        assertEquals("[2025-06-11 Wed 14:00]", props["LAST_REPEAT"])
        assertTrue(result.contains("body"))
    }

    @Test
    fun `moveSubtree swaps siblings with bodies intact`() {
        val (result, newLine) = OrgMutations.moveSubtree(doc, h("Child task"), +1)!!
        val redoc = OrgParser.parse(result)
        val first = redoc.findByTitle("First")!!
        assertEquals(
            listOf("Second child", "Child task"),
            redoc.directChildren(first).map { it.title },
        )
        assertTrue(result.contains("child body"))
        assertEquals(redoc.findByTitle("Child task")!!.lineIndex, newLine)
        // No-op at the edge
        assertNull(OrgMutations.moveSubtree(doc, h("Child task"), -1))
    }

    @Test
    fun `move top-level subtree`() {
        val (result, newLine) = OrgMutations.moveSubtree(doc, h("Last"), -1)!!
        val redoc = OrgParser.parse(result)
        assertTrue(
            redoc.headlines.first { it.level == 1 }.title == "Last" ||
                    redoc.headlines.indexOfFirst { it.title == "Last" } <
                    redoc.headlines.indexOfFirst { it.title == "First" }
        )
        assertEquals(redoc.findByTitle("Last")!!.lineIndex, newLine)
    }

    @Test
    fun `moveSubtree down past a deep sibling subtree tracks the new line`() {
        val big = OrgParser.parse(
            """
            * A
            ** B
            body b
            *** B1
            deep body
            ** C
            body c
            """.trimIndent() + "\n"
        )
        val b = big.headlines.first { it.title == "B" }
        val (text, newLine) = OrgMutations.moveSubtree(big, b, +1)!!
        val redoc = OrgParser.parse(text)
        assertEquals(
            listOf("C", "B"),
            redoc.directChildren(redoc.findByTitle("A")!!).map { it.title },
        )
        assertEquals(redoc.findByTitle("B")!!.lineIndex, newLine)
        // B1 travelled with B, still its child
        assertEquals("B", redoc.parent(redoc.findByTitle("B1")!!)!!.title)
        // No-op moving down at the last sibling
        assertNull(OrgMutations.moveSubtree(redoc, redoc.findByTitle("B")!!, +1))
    }

    @Test
    fun `promoteSubtree shifts heading and descendants one level up`() {
        val text = OrgMutations.promoteSubtree(doc, h("Child task"))!!
        val redoc = OrgParser.parse(text)
        assertEquals(1, redoc.findByTitle("Child task")!!.level)
        // planning and body untouched
        assertTrue(text.contains("SCHEDULED: <2025-06-09 Mon +1w>"))
        assertTrue(text.contains("child body"))
        // siblings untouched
        assertEquals(2, redoc.findByTitle("Second child")!!.level)
    }

    @Test
    fun `promoteSubtree carries the whole subtree`() {
        val big = OrgParser.parse("* A\n** B\n*** B1\nbody\n** C\n")
        val text = OrgMutations.promoteSubtree(big, big.findByTitle("B")!!)!!
        val redoc = OrgParser.parse(text)
        assertEquals(1, redoc.findByTitle("B")!!.level)
        assertEquals(2, redoc.findByTitle("B1")!!.level)
        assertEquals(2, redoc.findByTitle("C")!!.level) // sibling stays put
    }

    @Test
    fun `promoteSubtree at level 1 returns null`() {
        assertNull(OrgMutations.promoteSubtree(doc, h("First")))
    }

    @Test
    fun `demoteSubtree shifts subtree deeper`() {
        val text = OrgMutations.demoteSubtree(doc, h("Second child"))!!
        val redoc = OrgParser.parse(text)
        val demoted = redoc.findByTitle("Second child")!!
        assertEquals(3, demoted.level)
        assertEquals("Child task", redoc.parent(demoted)!!.title)
    }

    @Test
    fun `demoteSubtree without previous sibling returns null`() {
        assertNull(OrgMutations.demoteSubtree(doc, h("Child task"))) // first child
        assertNull(OrgMutations.demoteSubtree(doc, h("First")))      // first top-level
    }

    @Test
    fun `demoteSubtree of last top-level nests under previous`() {
        val text = OrgMutations.demoteSubtree(doc, h("Last"))!!
        val redoc = OrgParser.parse(text)
        val demoted = redoc.findByTitle("Last")!!
        assertEquals(2, demoted.level)
        assertEquals("First", redoc.parent(demoted)!!.title)
        assertTrue(text.contains("DEADLINE: <2025-06-27 Fri>"))
    }

    @Test
    fun `refileInsert to top level appends releveled at end of file`() {
        val subtree = OrgMutations.subtreeText(doc, h("Child task"))
        val (text, line) = OrgMutations.refileInsert(doc, null, subtree)
        val redoc = OrgParser.parse(text)
        val moved = redoc.headlines.last()
        assertEquals("Child task", moved.title)
        assertEquals(1, moved.level)
        assertEquals(line, moved.lineIndex)
        assertTrue(text.contains("child body"))
    }

    @Test
    fun `refileInsert to top level of file without trailing newline`() {
        val dest = OrgParser.parse("* Only\nbody")
        val (text, line) = OrgMutations.refileInsert(dest, null, "** Moved\nstuff\n")
        val redoc = OrgParser.parse(text)
        val moved = redoc.findByTitle("Moved")!!
        assertEquals(1, moved.level)
        assertEquals(line, moved.lineIndex)
        assertEquals(listOf("Only", "Moved"), redoc.headlines.map { it.title })
    }

    @Test
    fun `refileInsert under nested heading relevels below target`() {
        val subtree = "* Moved\nbody m\n** Moved child\n"
        val (text, line) = OrgMutations.refileInsert(doc, h("Second child"), subtree)
        val redoc = OrgParser.parse(text)
        val moved = redoc.findByTitle("Moved")!!
        assertEquals(3, moved.level)
        assertEquals(4, redoc.findByTitle("Moved child")!!.level)
        assertEquals("Second child", redoc.parent(moved)!!.title)
        assertEquals(line, moved.lineIndex)
    }

    @Test
    fun `refileInsert into empty document`() {
        val empty = OrgParser.parse("")
        val (text, line) = OrgMutations.refileInsert(empty, null, "** Moved\nbody\n")
        assertEquals("* Moved\nbody\n", text)
        assertEquals(0, line)
    }

    @Test
    fun `refileWithinFile moves subtree under a later heading`() {
        val (text, line) = OrgMutations.refileWithinFile(doc, h("Child task"), h("Last").lineIndex)!!
        val redoc = OrgParser.parse(text)
        val moved = redoc.findByTitle("Child task")!!
        assertEquals("Last", redoc.parent(moved)!!.title)
        assertEquals(2, moved.level)
        assertEquals(line, moved.lineIndex)
        assertTrue(text.contains("child body"))
    }

    @Test
    fun `refileWithinFile moves subtree under an earlier heading`() {
        val (text, line) = OrgMutations.refileWithinFile(doc, h("Last"), h("Second child").lineIndex)!!
        val redoc = OrgParser.parse(text)
        val moved = redoc.findByTitle("Last")!!
        assertEquals("Second child", redoc.parent(moved)!!.title)
        assertEquals(3, moved.level)
        assertEquals(line, moved.lineIndex)
    }

    @Test
    fun `refileWithinFile to top level moves subtree to end`() {
        val (text, _) = OrgMutations.refileWithinFile(doc, h("Child task"), null)!!
        val redoc = OrgParser.parse(text)
        val moved = redoc.findByTitle("Child task")!!
        assertEquals(1, moved.level)
        assertNull(redoc.parent(moved))
        assertEquals("Child task", redoc.headlines.last().title)
    }

    @Test
    fun `refileWithinFile into own subtree returns null`() {
        assertNull(OrgMutations.refileWithinFile(doc, h("First"), h("Child task").lineIndex))
        assertNull(OrgMutations.refileWithinFile(doc, h("First"), h("First").lineIndex))
    }

    @Test
    fun `delete and replace subtree`() {
        val deleted = OrgParser.parse(OrgMutations.deleteSubtree(doc, h("Child task")))
        assertNull(deleted.findByTitle("Child task"))
        assertTrue(deleted.text.contains("Second child"))

        val replaced = OrgMutations.replaceSubtree(doc, h("Second child"), "** Renamed child\nnew body")
        assertTrue(replaced.contains("** Renamed child\nnew body"))
        assertTrue(!replaced.contains("** Second child"))
    }

    // --- scoped preface / intro editors (prefaceRange / introRange / replaceLines) ---

    private val introDoc = OrgParser.parse(
        ":PROPERTIES:\n:ID: x\n:END:\n#+TITLE: Test\n#+FILETAGS: :db:\n\nIntro prose.\nMore of it.\n\n* First\nbody\n"
    )

    @Test
    fun `prefaceRange covers only the keyword run, below the drawer and above the intro`() {
        val range = OrgMutations.prefaceRange(introDoc)!!
        assertEquals(
            "#+TITLE: Test\n#+FILETAGS: :db:",
            OrgMutations.regionText(introDoc, range),
        )
    }

    @Test
    fun `prefaceRange starts at line 0 when there is no file property drawer`() {
        assertEquals("#+TITLE: Test", OrgMutations.regionText(doc, OrgMutations.prefaceRange(doc)!!))
    }

    @Test
    fun `prefaceRange is null when the file opens with content`() {
        assertNull(OrgMutations.prefaceRange(OrgParser.parse("Just prose.\n\n* First\n")))
    }

    @Test
    fun `introRange covers only the heading-less content`() {
        val range = OrgMutations.introRange(introDoc)!!
        assertEquals("Intro prose.\nMore of it.", OrgMutations.regionText(introDoc, range))
    }

    @Test
    fun `introRange is null when the preamble is only a drawer and keywords`() {
        assertNull(OrgMutations.introRange(doc))
    }

    @Test
    fun `introRange covers a whole heading-less file`() {
        val roam = OrgParser.parse("#+title: T\n\nAll of it.\nEvery line.\n")
        assertEquals("All of it.\nEvery line.", OrgMutations.regionText(roam, OrgMutations.introRange(roam)!!))
    }

    @Test
    fun `saving the preface leaves the drawer, the intro and the headings alone`() {
        val saved = OrgMutations.replaceLines(
            introDoc,
            OrgMutations.prefaceRange(introDoc)!!,
            "#+TITLE: Renamed",
        )
        assertEquals(
            ":PROPERTIES:\n:ID: x\n:END:\n#+TITLE: Renamed\n\nIntro prose.\nMore of it.\n\n* First\nbody\n",
            saved,
        )
    }

    @Test
    fun `saving the intro leaves the preface, the drawer and the headings alone`() {
        val saved = OrgMutations.replaceLines(
            introDoc,
            OrgMutations.introRange(introDoc)!!,
            "Rewritten intro.",
        )
        assertEquals(
            ":PROPERTIES:\n:ID: x\n:END:\n#+TITLE: Test\n#+FILETAGS: :db:\n\nRewritten intro.\n\n* First\nbody\n",
            saved,
        )
    }

    @Test
    fun `preface round-trips non-keyword lines it does not render`() {
        // The PREFACE UI (CollapsibleKvSection) only lists #+KEY lines, but the raw
        // region can also hold blank lines; the editor must not silently drop them.
        val spaced = OrgParser.parse("#+TITLE: Test\n\n#+FILETAGS: :db:\n\n* First\nbody\n")
        assertEquals(
            "#+TITLE: Test\n\n#+FILETAGS: :db:",
            OrgMutations.regionText(spaced, OrgMutations.prefaceRange(spaced)!!),
        )
    }

    // --- scoped drawer editors (fileDrawerRange / headingDrawerRange / replaceLines) ---

    private val drawerDoc = OrgParser.parse(
        """
        :PROPERTIES:
        :ID: file-1
        :END:
        #+TITLE: D

        * Task
        SCHEDULED: <2025-06-09 Mon>
        :PROPERTIES:
        :CUSTOM_ID: task
        :END:
        :LOGBOOK:
        - State "DONE" from "TODO" [2025-06-09 Mon 10:00]
        :END:
        body line
        """.trimIndent() + "\n"
    )

    @Test
    fun `fileDrawerRange spans the leading property drawer with its markers`() {
        val range = OrgMutations.fileDrawerRange(drawerDoc)!!
        assertEquals(0..2, range)
        assertEquals(":PROPERTIES:\n:ID: file-1\n:END:", OrgMutations.regionText(drawerDoc, range))
        assertNull(OrgMutations.fileDrawerRange(OrgParser.parse("* Only a heading\n")))
        assertNull(OrgMutations.fileDrawerRange(OrgParser.parse(":PROPERTIES:\n:ID: x\n* unclosed\n")))
    }

    @Test
    fun `headingDrawerRange finds the properties and logbook drawers past the planning line`() {
        val task = drawerDoc.headlines.single()
        val props = OrgMutations.headingDrawerRange(drawerDoc, task, ":PROPERTIES:")!!
        assertEquals(":PROPERTIES:\n:CUSTOM_ID: task\n:END:", OrgMutations.regionText(drawerDoc, props))
        val log = OrgMutations.headingDrawerRange(drawerDoc, task, ":LOGBOOK:")!!
        assertTrue(OrgMutations.regionText(drawerDoc, log).startsWith(":LOGBOOK:\n- State \"DONE\""))
        val bare = OrgParser.parse("* No drawers\nbody\n")
        assertNull(OrgMutations.headingDrawerRange(bare, bare.headlines.single(), ":PROPERTIES:"))
    }

    @Test
    fun `replaceLines swaps a drawer and leaves the rest of the file byte-identical`() {
        val task = drawerDoc.headlines.single()
        val log = OrgMutations.headingDrawerRange(drawerDoc, task, ":LOGBOOK:")!!
        val edited = OrgMutations.replaceLines(
            drawerDoc, log,
            ":LOGBOOK:\n- State \"DONE\" from \"TODO\" [2025-06-09 Mon 10:00]\nCLOCK: [2025-06-09 Mon 11:00]--[2025-06-09 Mon 11:30] =>  0:30\n:END:",
        )
        val reparsed = OrgParser.parse(edited)
        assertTrue(reparsed.headlines.single().logbook.any { it.startsWith("CLOCK:") })
        assertTrue(edited.contains(":ID: file-1"))
        assertTrue(edited.trimEnd().endsWith("body line"))
    }

    // --- scoped block editor (blockRange / replaceLines) ---

    private val blockDoc = OrgParser.parse(
        """
        * Notes
        Intro line.

        #+CAPTION: A snippet
        #+ATTR_LATEX: :width 0.8
        #+BEGIN_SRC python
        print("hi")
        #+END_SRC

        Between the blocks.

        #+begin_quote
        To be, or not to be.
        #+end_quote
        """.trimIndent() + "\n"
    )

    @Test
    fun `blockRange spans the src block, its markers, and the affiliated lines above it`() {
        // Read mode passes the block's first line (the first affiliated line, index 3).
        val range = OrgMutations.blockRange(blockDoc, 3)!!
        assertEquals(
            "#+CAPTION: A snippet\n#+ATTR_LATEX: :width 0.8\n#+BEGIN_SRC python\nprint(\"hi\")\n#+END_SRC",
            OrgMutations.regionText(blockDoc, range),
        )
    }

    @Test
    fun `blockRange resolves the nearest block when the tapped line drifts past its BEGIN`() {
        // An external edit shifted the file: the tapped line now lands inside the quote block.
        val range = OrgMutations.blockRange(blockDoc, 12)!!
        assertEquals("#+begin_quote\nTo be, or not to be.\n#+end_quote", OrgMutations.regionText(blockDoc, range))
    }

    @Test
    fun `blockRange is null for a file with no blocks`() {
        assertNull(OrgMutations.blockRange(OrgParser.parse("* Just a heading\nbody\n"), 1))
    }

    @Test
    fun `blockRange spans a standalone keyword run with no BEGIN below it`() {
        val doc = OrgParser.parse(
            "* Notes\nprose\n\n#+CAPTION: foo\n#+NAME: bar\n\nmore prose\n"
        )
        val range = OrgMutations.blockRange(doc, 4)!!
        assertEquals("#+CAPTION: foo\n#+NAME: bar", OrgMutations.regionText(doc, range))
    }

    @Test
    fun `replaceLines swaps a block and leaves the rest byte-identical`() {
        val range = OrgMutations.blockRange(blockDoc, 11)!!
        val edited = OrgMutations.replaceLines(
            blockDoc, range,
            "#+begin_quote\nAll the world's a stage.\n#+end_quote",
        )
        assertTrue(edited.contains("All the world's a stage."))
        assertTrue(edited.contains("print(\"hi\")"))
        assertTrue(edited.trimEnd().endsWith("#+end_quote"))
    }

    @Test
    fun `wrapIntroInHeading puts a blank heading above heading-less content`() {
        val roam = OrgParser.parse(
            ":PROPERTIES:\n:ID: abc\n:END:\n#+title: Note\n\nFirst para.\nSecond line.\n",
        )
        val (text, line) = OrgMutations.wrapIntroInHeading(roam)
        val parsed = OrgParser.parse(text)
        val hd = parsed.headlines.single()
        assertEquals(line, hd.lineIndex)
        assertEquals("", hd.title)
        // #+title / property drawer stay above the new heading; the loose text
        // becomes the heading's body.
        assertTrue(text.substringBefore("* ").contains("#+title: Note"))
        assertEquals(listOf("First para.", "Second line."), parsed.bodyOf(hd).filter { it.isNotBlank() })
        // The leading file-level property drawer stays a file-level drawer (not
        // absorbed into the new heading, not duplicated).
        assertEquals(listOf(":ID:" to "abc"), parsed.filePropertyDrawer)
        assertTrue(hd.properties.isEmpty())
    }

    @Test
    fun `wrapIntroInHeading keeps an existing first heading and its content`() {
        val mixed = OrgParser.parse("#+title: T\n\nPreamble prose.\n\n* Timeline\nunder heading\n")
        val (text, line) = OrgMutations.wrapIntroInHeading(mixed)
        val parsed = OrgParser.parse(text)
        val wrapped = parsed.headlines.first { it.lineIndex == line }
        assertEquals("", wrapped.title)
        assertEquals("Timeline", parsed.headlines.last().title)
        assertTrue(parsed.bodyOf(wrapped).any { it.contains("Preamble prose") })
        assertTrue(parsed.bodyOf(parsed.headlines.last()).any { it.contains("under heading") })
    }

    @Test
    fun `cut and paste releveles the subtree`() {
        val cut = OrgMutations.subtreeText(doc, h("Child task"))
        val without = OrgParser.parse(OrgMutations.deleteSubtree(doc, h("Child task")))
        val pasted = OrgParser.parse(
            OrgMutations.pasteUnder(without, without.findByTitle("Last")!!, cut)
        )
        val moved = pasted.findByTitle("Child task")!!
        assertEquals(2, moved.level)
        assertEquals("Last", pasted.parent(moved)!!.title)
        assertEquals("<2025-06-09 Mon +1w>", moved.planning.scheduled!!.format())
    }

    @Test
    fun `newChild appends with ID and CREATED drawer`() {
        val (text, line) = OrgMutations.newChild(
            doc, h("First"), "Fresh note",
            OrgMutations.NewNoteOptions(
                id = "uuid-1234",
                createdAt = LocalDateTime.of(2025, 6, 11, 9, 5),
            ),
        )
        val redoc = OrgParser.parse(text)
        val fresh = redoc.findByTitle("Fresh note")!!
        assertEquals(2, fresh.level)
        assertEquals(line, fresh.lineIndex)
        assertEquals("uuid-1234", fresh.id)
        assertEquals("[2025-06-11 Wed 09:05]", fresh.properties["CREATED"])
        assertEquals("First", redoc.parent(fresh)!!.title)
    }

    @Test
    fun `newTopLevel appends at end of file`() {
        val (text, line) = OrgMutations.newTopLevel(doc, "Brand new")
        val redoc = OrgParser.parse(text)
        val fresh = redoc.findByTitle("Brand new")!!
        assertEquals(1, fresh.level)
        assertEquals(line, fresh.lineIndex)
        assertEquals(redoc.headlines.last().title, "Brand new")
        assertTrue(text.startsWith(doc.text.trimEnd('\n')))
    }

    @Test
    fun `newTopLevel into empty document has no leading blank`() {
        val empty = OrgParser.parse("")
        val (text, line) = OrgMutations.newTopLevel(empty, "First note")
        assertEquals("* First note\n", text)
        assertEquals(0, line)
    }

    @Test
    fun `insertSiblingAbove lands right before the note at the same level`() {
        val (text, line) = OrgMutations.insertSiblingAbove(doc, h("Second child"), "New above")
        val redoc = OrgParser.parse(text)
        val fresh = redoc.findByTitle("New above")!!
        assertEquals(line, fresh.lineIndex)
        assertEquals(2, fresh.level)
        assertEquals("First", redoc.parent(fresh)!!.title)
        assertEquals(
            listOf("Child task", "New above", "Second child"),
            redoc.directChildren(redoc.findByTitle("First")!!).map { it.title },
        )
    }

    @Test
    fun `insertSiblingBelow lands right after the note's whole subtree`() {
        val (text, line) = OrgMutations.insertSiblingBelow(doc, h("Child task"), "New below")
        val redoc = OrgParser.parse(text)
        val fresh = redoc.findByTitle("New below")!!
        assertEquals(line, fresh.lineIndex)
        assertEquals(2, fresh.level)
        assertEquals(
            listOf("Child task", "New below", "Second child"),
            redoc.directChildren(redoc.findByTitle("First")!!).map { it.title },
        )
        // Child task's own body must stay intact, ahead of the new sibling.
        assertTrue(text.contains("child body"))
    }

    @Test
    fun `insertSiblingBelow at top level appends after the whole subtree`() {
        val (text, line) = OrgMutations.insertSiblingBelow(doc, h("First"), "New top-level")
        val redoc = OrgParser.parse(text)
        val fresh = redoc.findByTitle("New top-level")!!
        assertEquals(line, fresh.lineIndex)
        assertEquals(1, fresh.level)
        assertEquals(
            listOf("First", "New top-level", "Last"),
            redoc.headlines.filter { it.level == 1 }.map { it.title },
        )
    }

    @Test
    fun `headlineLine formatting`() {
        assertEquals("** TODO [#A] Title  :a:b:", OrgMutations.headlineLine(2, "TODO", 'A', "Title", listOf("a", "b")))
        assertEquals("* Title", OrgMutations.headlineLine(1, null, null, "Title", emptyList()))
        assertEquals("* TODO", OrgMutations.headlineLine(1, "TODO", null, "", emptyList()))
    }

    // --- toggleCheckboxDone / toggleCheckboxProgress ---

    private val checklistDoc = OrgParser.parse(
        """
        * Groceries
        - [ ] milk
        - [X] eggs
        - [-] bread
          - [ ] nested
        - plain item
        not a list line
        """.trimIndent() + "\n"
    )

    @Test
    fun `tap toggles a box between open and done`() {
        val line = checklistDoc.lines.indexOf("- [ ] milk")
        val once = OrgMutations.toggleCheckboxDone(checklistDoc, line)!!
        assertEquals("- [X] milk", once.lines()[line])
        val twice = OrgMutations.toggleCheckboxDone(OrgParser.parse(once), line)!!
        assertEquals("- [ ] milk", twice.lines()[line])
    }

    @Test
    fun `tap on an in-progress box marks it done`() {
        val line = checklistDoc.lines.indexOf("- [-] bread")
        val result = OrgMutations.toggleCheckboxDone(checklistDoc, line)!!
        assertEquals("- [X] bread", result.lines()[line])
    }

    @Test
    fun `tap on a done box opens it`() {
        val line = checklistDoc.lines.indexOf("- [X] eggs")
        val result = OrgMutations.toggleCheckboxDone(checklistDoc, line)!!
        assertEquals("- [ ] eggs", result.lines()[line])
    }

    @Test
    fun `long-press toggles a box between open and in-progress`() {
        val line = checklistDoc.lines.indexOf("- [ ] milk")
        val once = OrgMutations.toggleCheckboxProgress(checklistDoc, line)!!
        assertEquals("- [-] milk", once.lines()[line])
        val twice = OrgMutations.toggleCheckboxProgress(OrgParser.parse(once), line)!!
        assertEquals("- [ ] milk", twice.lines()[line])
    }

    @Test
    fun `long-press on a done box marks it in-progress`() {
        val line = checklistDoc.lines.indexOf("- [X] eggs")
        val result = OrgMutations.toggleCheckboxProgress(checklistDoc, line)!!
        assertEquals("- [-] eggs", result.lines()[line])
    }

    @Test
    fun `lowercase x is treated as done`() {
        val doc = OrgParser.parse("- [x] task\n")
        assertEquals("- [ ] task", OrgMutations.toggleCheckboxDone(doc, 0)!!.lines()[0])
    }

    @Test
    fun `toggle preserves indent and trailing text`() {
        val line = checklistDoc.lines.indexOf("  - [ ] nested")
        val result = OrgMutations.toggleCheckboxDone(checklistDoc, line)!!
        assertEquals("  - [X] nested", result.lines()[line])
    }

    @Test
    fun `toggle only rewrites the target line, everything else is byte-identical`() {
        val line = checklistDoc.lines.indexOf("- [ ] milk")
        val result = OrgMutations.toggleCheckboxDone(checklistDoc, line)!!
        assertEquals(
            checklistDoc.lines.filterIndexed { i, _ -> i != line },
            result.lines().filterIndexed { i, _ -> i != line },
        )
    }

    @Test
    fun `non-checkbox list item returns null`() {
        val line = checklistDoc.lines.indexOf("- plain item")
        assertNull(OrgMutations.toggleCheckboxDone(checklistDoc, line))
        assertNull(OrgMutations.toggleCheckboxProgress(checklistDoc, line))
    }

    @Test
    fun `non-list line returns null`() {
        val line = checklistDoc.lines.indexOf("not a list line")
        assertNull(OrgMutations.toggleCheckboxDone(checklistDoc, line))
    }

    @Test
    fun `out of range line index returns null`() {
        assertNull(OrgMutations.toggleCheckboxDone(checklistDoc, -1))
        assertNull(OrgMutations.toggleCheckboxDone(checklistDoc, checklistDoc.lines.size))
    }

    // --- toggleCheckboxDone parent statistics cookie ---

    private val cookieDoc = OrgParser.parse(
        """
        * Shopping
        - Groceries [/]
          - [ ] milk
          - [X] eggs
          - [X] bread
          - [ ] butter
          - [X] cheese
        - Errands [0/3]
          - [ ] bank
          - [ ] post office
          - [ ] pharmacy
        - Chores [%]
          - [ ] dishes
          - [ ] laundry
        - Reading
          - [ ] chapter one
        - Outer [/]
          - [ ] item a
          - [ ] item b
            - [X] item b sub
        """.trimIndent() + "\n"
    )

    @Test
    fun `checking a box refreshes the parent's fraction cookie from direct children`() {
        // milk starts unchecked; checking it brings done from 3 to 4 of 5.
        val line = cookieDoc.lines.indexOf("  - [ ] milk")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        assertEquals("- Groceries [4/5]", result.lines()[cookieDoc.lines.indexOf("- Groceries [/]")])
    }

    @Test
    fun `parent cookie is recalculated from scratch, not incremented off stale values`() {
        val line = cookieDoc.lines.indexOf("  - [ ] bank")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        assertEquals("- Errands [1/3]", result.lines()[cookieDoc.lines.indexOf("- Errands [0/3]")])
    }

    @Test
    fun `parent percent cookie recalculates as a rounded percentage`() {
        val line = cookieDoc.lines.indexOf("  - [ ] dishes")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        assertEquals("- Chores [50%]", result.lines()[cookieDoc.lines.indexOf("- Chores [%]")])
    }

    @Test
    fun `parent with no cookie in its text is left untouched`() {
        val line = cookieDoc.lines.indexOf("  - [ ] chapter one")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        assertEquals("- Reading", result.lines()[cookieDoc.lines.indexOf("- Reading")])
    }

    @Test
    fun `only the shallowest direct children count, a nested sub-list is not folded in`() {
        val line = cookieDoc.lines.indexOf("  - [ ] item a")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        // "item b sub" is a grandchild (nested under "item b"), so it's excluded
        // from Outer's count even though it's also a checkbox.
        assertEquals("- Outer [1/2]", result.lines()[cookieDoc.lines.indexOf("- Outer [/]")])
    }

    @Test
    fun `cookie updates only the immediate parent, not an ancestor two levels up`() {
        val line = cookieDoc.lines.indexOf("    - [X] item b sub")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        // "item b sub"'s immediate parent is "item b", which has no cookie of
        // its own, so "Outer [/]" two levels up is left untouched.
        assertEquals("- Outer [/]", result.lines()[cookieDoc.lines.indexOf("- Outer [/]")])
    }

    // --- toggleCheckboxDone parent completion (a checkbox parent's own mark) ---

    private val hierarchicalDoc = OrgParser.parse(
        """
        * List
        - [ ] Group A
          - [ ] a1
          - [ ] a2
        - [ ] Group B
          - [X] b1
        """.trimIndent() + "\n"
    )

    @Test
    fun `completing all direct children checks the parent's own box`() {
        val a1 = hierarchicalDoc.lines.indexOf("  - [ ] a1")
        val once = OrgMutations.toggleCheckboxDone(hierarchicalDoc, a1)!!
        // a2 still open, so Group A stays unchecked.
        assertEquals("- [ ] Group A", once.lines()[hierarchicalDoc.lines.indexOf("- [ ] Group A")])

        val a2 = hierarchicalDoc.lines.indexOf("  - [ ] a2")
        val twice = OrgMutations.toggleCheckboxDone(OrgParser.parse(once), a2)!!
        // both children now done: Group A checks itself off too.
        assertEquals("- [X] Group A", twice.lines()[hierarchicalDoc.lines.indexOf("- [ ] Group A")])
    }

    @Test
    fun `unchecking a child after full completion unchecks the parent's own box`() {
        val b1 = hierarchicalDoc.lines.indexOf("  - [X] b1")
        val result = OrgMutations.toggleCheckboxDone(hierarchicalDoc, b1)!!
        // Group B's only child just became unchecked, so Group B unchecks too.
        assertEquals("- [ ] Group B", result.lines()[hierarchicalDoc.lines.indexOf("- [ ] Group B")])
    }

    @Test
    fun `parent checkbox completion does not cascade past the immediate parent`() {
        val line = cookieDoc.lines.indexOf("    - [X] item b sub")
        val result = OrgMutations.toggleCheckboxDone(cookieDoc, line)!!
        // "item b" (immediate parent) has its own box unchecked to match its
        // now-unchecked sole child, but "Outer" two levels up is untouched.
        assertEquals("  - [ ] item b", result.lines()[cookieDoc.lines.indexOf("  - [ ] item b")])
        assertEquals("- Outer [/]", result.lines()[cookieDoc.lines.indexOf("- Outer [/]")])
    }

    // --- setActiveTimestamps ---

    private val activeDoc = OrgParser.parse(
        """
        * Event
        :PROPERTIES:
        :ID: e1
        :END:
        the body

        * Other
        """.trimIndent() + "\n"
    )

    @Test
    fun `setActiveTimestamps inserts a dedicated line above the drawer`() {
        val ts = OrgTimestamp.parse("<2026-09-12 Fri>")!!
        val result = OrgMutations.setActiveTimestamps(activeDoc, h2("Event"), listOf(ts))
        val redoc = OrgParser.parse(result)
        val ev = redoc.findByTitle("Event")!!
        assertEquals(listOf(java.time.LocalDate.of(2026, 9, 12)), ev.activeTimestamps.map { it.date })
        assertEquals("<2026-09-12 Sat>", redoc.lines[ev.lineIndex + 1])
        assertEquals(":PROPERTIES:", redoc.lines[ev.lineIndex + 2])
        assertTrue(result.contains("the body"))
    }

    @Test
    fun `setActiveTimestamps replaces an existing pure line and is idempotent`() {
        val once = OrgMutations.setActiveTimestamps(
            activeDoc, h2("Event"), listOf(OrgTimestamp.parse("<2026-09-12 Fri>")!!)
        )
        val doc1 = OrgParser.parse(once)
        val edited = OrgMutations.setActiveTimestamps(
            doc1, doc1.findByTitle("Event")!!,
            listOf(OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed>").single()),
        )
        val doc2 = OrgParser.parse(edited)
        val ev2 = doc2.findByTitle("Event")!!
        assertEquals(
            "<2026-09-08 Tue>--<2026-09-10 Thu>",
            doc2.lines[ev2.lineIndex + 1],
        )
        // Re-applying the same list changes nothing.
        val again = OrgMutations.setActiveTimestamps(
            doc2, ev2,
            listOf(OrgTimestamp.parseAll("<2026-09-08 Mon>--<2026-09-10 Wed>").single()),
        )
        assertEquals(edited, again)
    }

    @Test
    fun `setActiveTimestamps migrates a pre-flip line below the drawer to above it`() {
        val preFlip = OrgParser.parse(
            """
            * Event
            :PROPERTIES:
            :ID: e1
            :END:
            <2026-09-08 Tue>
            the body

            * Other
            """.trimIndent() + "\n"
        )
        val h = preFlip.findByTitle("Event")!!
        // The old-position stamp still parses as dedicated.
        assertEquals(listOf(java.time.LocalDate.of(2026, 9, 8)), h.dedicatedActiveTimestamps.map { it.date })

        val result = OrgMutations.setActiveTimestamps(
            preFlip, h, listOf(OrgTimestamp.parse("<2026-09-12 Fri>")!!)
        )
        val redoc = OrgParser.parse(result)
        val ev = redoc.findByTitle("Event")!!
        assertEquals("<2026-09-12 Sat>", redoc.lines[ev.lineIndex + 1])
        assertEquals(":PROPERTIES:", redoc.lines[ev.lineIndex + 2])
        // The stale below-the-drawer line is gone, not left behind.
        assertTrue(!result.contains("2026-09-08"))
        assertTrue(result.contains("the body"))
    }

    @Test
    fun `setActiveTimestamps with an empty list removes the dedicated line`() {
        val once = OrgMutations.setActiveTimestamps(
            activeDoc, h2("Event"), listOf(OrgTimestamp.parse("<2026-09-12 Fri>")!!)
        )
        val doc1 = OrgParser.parse(once)
        val cleared = OrgMutations.setActiveTimestamps(doc1, doc1.findByTitle("Event")!!, emptyList())
        assertTrue(!cleared.contains("<2026-09-12 Fri>"))
        assertTrue(cleared.contains("the body"))
    }

    // --- OrgHeadline.dedicatedActiveTimestamps / setPlanningAndActiveTimestamps ---

    @Test
    fun `dedicatedActiveTimestamps reads the bodyStart line only, not inline prose`() {
        val doc = OrgParser.parse(
            """
            * Event
            <2026-09-12 Fri>
            met the team <2026-09-20 Sun> for lunch
            """.trimIndent() + "\n"
        )
        val h = doc.findByTitle("Event")!!
        // The parser sees both stamps; only the dedicated bodyStart line counts here.
        assertEquals(2, h.activeTimestamps.size)
        assertEquals(
            listOf(java.time.LocalDate.of(2026, 9, 12)),
            h.dedicatedActiveTimestamps.map { it.date },
        )

        val inlineOnly = OrgParser.parse("* Event\nmet the team <2026-09-20 Sun> for lunch\n")
        assertEquals(
            emptyList<java.time.LocalDate>(),
            inlineOnly.findByTitle("Event")!!.dedicatedActiveTimestamps.map { it.date },
        )
    }

    @Test
    fun `setPlanningAndActiveTimestamps writes the planning line and the active line together`() {
        val doc = OrgParser.parse("* Pay rent\nthe body\n")
        val h = doc.findByTitle("Pay rent")!!
        val result = OrgMutations.setPlanningAndActiveTimestamps(
            doc, h,
            scheduled = OrgTimestamp.parse("<2026-09-10 Thu>"),
            deadline = null,
            active = listOf(OrgTimestamp.parse("<2026-09-12 Fri>")!!),
        )
        val redoc = OrgParser.parse(result)
        val again = redoc.findByTitle("Pay rent")!!
        assertEquals(java.time.LocalDate.of(2026, 9, 10), again.planning.scheduled?.date)
        assertEquals(
            listOf(java.time.LocalDate.of(2026, 9, 12)),
            again.dedicatedActiveTimestamps.map { it.date },
        )
        assertTrue(result.contains("the body"))

        // Clearing both removes both managed lines.
        val cleared = OrgMutations.setPlanningAndActiveTimestamps(
            redoc, again, scheduled = null, deadline = null, active = emptyList(),
        )
        assertTrue(!cleared.contains("SCHEDULED:"))
        assertTrue(!cleared.contains("<2026-09-12"))
        assertTrue(cleared.contains("the body"))
    }

    private fun h2(title: String) = activeDoc.headlines.first { it.title == title }

    // --- byte preservation (F-Droid review, MR 45754) ---

    private val indented = OrgParser.parse(
        "* TODO Indented\n" +
            "  SCHEDULED: <2025-06-09 Mon>\n" +
            "  body\n" +
            "\n" +
            "* TODO Bare\n" +
            "no planning here\n" +
            "\n" +
            "** TODO Deep\n" +
            "  DEADLINE: <2025-06-20 Fri>\n"
    )

    private fun hi(title: String) = indented.headlines.first { it.title == title }

    @Test
    fun `replaceSubtree keeps the blank line before the next heading after a body edit`() {
        val edited = OrgMutations.subtreeText(indented, hi("Indented")).replace("body", "bodyx").trimEnd('\n')
        val result = OrgMutations.replaceSubtree(indented, hi("Indented"), edited)
        assertEquals(indented.text.replace("body\n", "bodyx\n"), result)
    }

    @Test
    fun `replaceSubtree with unchanged text is byte-identical`() {
        for (h in indented.headlines) {
            val result = OrgMutations.replaceSubtree(indented, h, OrgMutations.subtreeText(indented, h))
            assertEquals(indented.text, result)
        }
    }

    @Test
    fun `replaceSubtree keeps extra trailing blank lines the user added`() {
        val edited = OrgMutations.subtreeText(indented, hi("Indented")) + "\n\n"
        val result = OrgMutations.replaceSubtree(indented, hi("Indented"), edited)
        assertTrue(result.contains("  body\n\n\n\n* TODO Bare"))
    }

    @Test
    fun `setScheduled keeps an indented planning line indented`() {
        val ts = OrgTimestamp.parse("<2025-07-01 Tue>")!!
        val result = OrgMutations.setScheduled(indented, hi("Indented"), ts)
        assertEquals(indented.text.replace("<2025-06-09 Mon>", "<2025-07-01 Tue>"), result)
    }

    @Test
    fun `a new planning line follows the file's level-plus-one indentation`() {
        val ts = OrgTimestamp.parse("<2025-07-01 Tue>")!!
        val result = OrgMutations.setScheduled(indented, hi("Bare"), ts)
        assertTrue(result.contains("* TODO Bare\n  SCHEDULED: <2025-07-01 Tue>\nno planning here"))
    }

    @Test
    fun `markDone keeps an indented DEADLINE indented`() {
        val result = OrgMutations.markDone(indented, hi("Deep"), "DONE", LocalDateTime.of(2025, 6, 11, 14, 0))
        val planning = result.lines().first { "DEADLINE:" in it }
        assertTrue(planning, planning.startsWith("  DEADLINE: <2025-06-20 Fri>"))
        assertTrue(planning.contains("CLOSED:"))
    }

    @Test
    fun `a column-0 file still gets column-0 planning lines`() {
        val ts = OrgTimestamp.parse("<2025-07-01 Tue>")!!
        val result = OrgMutations.setScheduled(doc, h("First"), ts)
        assertTrue(result.contains("* First :tag:\nSCHEDULED: <2025-07-01 Tue>\nbody one"))
    }

    @Test
    fun `upsertProperty matches an indented drawer`() {
        val d = OrgParser.parse("* Note\n  :PROPERTIES:\n  :ID: abc\n  :END:\nbody\n")
        val result = OrgMutations.upsertProperty(d, d.headlines.first(), "CUSTOM_ID", "x")
        assertEquals("* Note\n  :PROPERTIES:\n  :ID: abc\n  :CUSTOM_ID: x\n  :END:\nbody\n", result)
    }

    // --- applyToEach (bulk Move & indent) ---

    private val bulk = OrgParser.parse(
        """
        * A
        a body
        ** A1
        a1 body
        ** A2
        ** A3
        * B
        b body
        * C
        """.trimIndent() + "\n"
    )

    private fun line(title: String) = bulk.headlines.first { it.title == title }.lineIndex

    private fun outline(d: OrgDocument) = d.headlines.map { "*".repeat(it.level) + " " + it.title }

    @Test
    fun `applyToEach moves adjacent selected siblings down as a block`() {
        val r = OrgMutations.applyToEach(bulk, setOf(line("A1"), line("A2")), OrgMutations.StructureOp.MOVE_DOWN)!!
        assertEquals(listOf("* A", "** A3", "** A1", "** A2", "* B", "* C"), outline(r.doc))
        assertEquals(2, r.applied)
        // The selection follows the headings to their new lines.
        assertEquals(r.doc.findByTitle("A1")!!.lineIndex, r.remap(line("A1")))
        assertEquals(r.doc.findByTitle("A2")!!.lineIndex, r.remap(line("A2")))
        assertEquals(r.doc.findByTitle("A3")!!.lineIndex, r.remap(line("A3")))
        assertTrue("bodies travel with their heading", r.text.contains("** A1\na1 body"))
    }

    @Test
    fun `applyToEach never swaps two selected siblings with each other`() {
        // A1 is already first, so A2 must not leapfrog it either.
        assertNull(OrgMutations.applyToEach(bulk, setOf(line("A1"), line("A2")), OrgMutations.StructureOp.MOVE_UP))
        val r = OrgMutations.applyToEach(bulk, setOf(line("A2"), line("A3")), OrgMutations.StructureOp.MOVE_UP)!!
        assertEquals(listOf("* A", "** A2", "** A3", "** A1", "* B", "* C"), outline(r.doc))
    }

    @Test
    fun `applyToEach promotes and demotes each heading, keeping their order`() {
        val promoted = OrgMutations.applyToEach(bulk, setOf(line("A2"), line("A3")), OrgMutations.StructureOp.PROMOTE)!!
        assertEquals(listOf("* A", "** A1", "* A2", "* A3", "* B", "* C"), outline(promoted.doc))

        val demoted = OrgMutations.applyToEach(bulk, setOf(line("A2"), line("A3")), OrgMutations.StructureOp.DEMOTE)!!
        assertEquals(listOf("* A", "** A1", "*** A2", "*** A3", "* B", "* C"), outline(demoted.doc))
    }

    @Test
    fun `applyToEach never demotes a selected heading under another selected heading`() {
        // A1 has no previous sibling, so the A1..A3 block stays put as a whole.
        assertNull(OrgMutations.applyToEach(bulk, setOf(line("A1"), line("A2")), OrgMutations.StructureOp.DEMOTE))
        assertNull(OrgMutations.applyToEach(bulk, setOf(line("A1"), line("A2"), line("A3")), OrgMutations.StructureOp.DEMOTE))
        // Only the stuck block is skipped; B still demotes under the unselected A.
        val r = OrgMutations.applyToEach(bulk, setOf(line("A1"), line("A2"), line("B")), OrgMutations.StructureOp.DEMOTE)!!
        assertEquals(listOf("* A", "** A1", "** A2", "** A3", "** B", "* C"), outline(r.doc))
        assertEquals(1, r.applied)
        assertEquals(3, r.attempted)
    }

    @Test
    fun `applyToEach lets a selected descendant ride along with its selected ancestor`() {
        val r = OrgMutations.applyToEach(bulk, setOf(line("A"), line("A2")), OrgMutations.StructureOp.MOVE_DOWN)!!
        assertEquals(listOf("* B", "* A", "** A1", "** A2", "** A3", "* C"), outline(r.doc))
        assertEquals(1, r.attempted)
        assertEquals(r.doc.findByTitle("A2")!!.lineIndex, r.remap(line("A2")))
    }

    @Test
    fun `applyToEach reports a partial batch and skips headings it can't apply to`() {
        val r = OrgMutations.applyToEach(bulk, setOf(line("A1"), line("B")), OrgMutations.StructureOp.PROMOTE)!!
        assertEquals(listOf("* A", "* A1", "** A2", "** A3", "* B", "* C"), outline(r.doc))
        assertEquals(1, r.applied)
        assertEquals(2, r.attempted)
    }

    @Test
    fun `applyToEach deletes every selected subtree and remaps the survivors`() {
        val r = OrgMutations.applyToEach(bulk, setOf(line("A1"), line("B")), OrgMutations.StructureOp.DELETE)!!
        assertEquals(listOf("* A", "** A2", "** A3", "* C"), outline(r.doc))
        assertNull(r.remap(line("A1")))
        assertNull(r.remap(line("B")))
        assertEquals(r.doc.findByTitle("C")!!.lineIndex, r.remap(line("C")))
        assertEquals(r.doc.findByTitle("A3")!!.lineIndex, r.remap(line("A3")))
    }

    @Test
    fun `editEach edits each heading bottom-up and remaps the ones whose lines shifted`() {
        val stamp = OrgTimestamp.parse("<2026-10-05 Mon>")!!
        val r = OrgMutations.editEach(bulk, setOf(line("A"), line("A1"), line("B"))) { d, h ->
            OrgMutations.setScheduled(d, h, stamp)
        }!!
        assertEquals(3, r.applied)
        listOf("A", "A1", "B").forEach { title ->
            val h = r.doc.findByTitle(title)!!
            assertEquals(stamp, h.planning.scheduled)
            assertEquals(h.lineIndex, r.remap(line(title)))
        }
        assertNull(r.doc.findByTitle("A2")!!.planning.scheduled)
        assertEquals("bodies stay put", "a1 body", r.doc.lines[r.doc.findByTitle("A1")!!.lineIndex + 2])
    }

    @Test
    fun `editEach returns null when nothing changed`() {
        assertNull(OrgMutations.editEach(bulk, setOf(line("A"))) { _, _ -> null })
        assertNull(OrgMutations.editEach(bulk, setOf(line("A"))) { d, _ -> d.text })
    }

    @Test
    fun `liftSubtrees takes out each top-level selected subtree and remaps the rest`() {
        val lifted = OrgMutations.liftSubtrees(bulk, setOf(line("A"), line("A2"), line("C")))
        assertEquals(listOf("* B"), outline(lifted.doc))
        assertEquals(2, lifted.subtrees.size)
        assertTrue(lifted.subtrees[0].startsWith("* A\na body"))
        assertTrue("A2 travels inside A", lifted.subtrees[0].contains("** A2"))
        assertNull(lifted.remap(line("A2")))
        assertEquals(lifted.doc.findByTitle("B")!!.lineIndex, lifted.remap(line("B")))
    }

    @Test
    fun `refileEachWithinFile moves subtrees under the target in their original order`() {
        val r = OrgMutations.refileEachWithinFile(bulk, setOf(line("A1"), line("C")), line("B"))!!
        assertEquals(listOf("* A", "** A2", "** A3", "* B", "** A1", "** C"), outline(r))
        assertTrue("bodies travel with their heading", r.text.contains("** A1\na1 body"))
        // To the top level: appended at the end.
        val top = OrgMutations.refileEachWithinFile(bulk, setOf(line("A1"), line("A3")), null)!!
        assertEquals(listOf("* A", "** A2", "* B", "* C", "* A1", "* A3"), outline(top))
    }

    @Test
    fun `refileEachWithinFile refuses a target inside a moved subtree`() {
        assertNull(OrgMutations.refileEachWithinFile(bulk, setOf(line("A"), line("C")), line("A2")))
    }
}
