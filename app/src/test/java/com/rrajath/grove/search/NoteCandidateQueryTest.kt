package com.rrajath.grove.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCandidateQueryTest {

    private fun build(input: String? = null, facets: FacetNarrowing = FacetNarrowing.NONE): CandidateSql {
        val query = input?.let { QueryParser.parse(it) }
        return NoteCandidateQuery.build(query?.let { FtsQuery.matchExpression(it) }, query, facets)
    }

    @Test
    fun `nothing to narrow selects the whole table in a stable order`() {
        val sql = build()
        assertEquals("SELECT * FROM notes ORDER BY fileName, lineIndex", sql.sql)
        assertTrue(sql.args.isEmpty())
        assertTrue(sql.isFullScan)
    }

    @Test
    fun `a text query joins through the FTS table`() {
        val sql = build("meeting")
        assertTrue(sql.sql.contains("(fileName, lineIndex) IN (SELECT fileName, lineIndex FROM notes_fts WHERE notes_fts MATCH ?)"))
        assertEquals(listOf("(\"meeting\")", "%meeting%"), sql.args)
        assertFalse(sql.isFullScan)
    }

    @Test
    fun `text terms also keep rows whose own tags contain them`() {
        val sql = build("meeting notes")
        assertTrue(sql.sql.contains("MATCH ?) OR tags LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')"))
        assertEquals(listOf("(\"meeting\" AND \"notes\")", "%meeting%", "%notes%"), sql.args)
    }

    @Test
    fun `a non-ASCII text term drops the FTS clause rather than miss a tag match`() {
        val sql = build("café")
        assertFalse(sql.sql.contains("notes_fts"))
        assertTrue(sql.isFullScan)
    }

    @Test
    fun `a negated text term adds no tag clause`() {
        val sql = build("meeting .notes")
        assertEquals(listOf("(\"meeting\")", "%meeting%"), sql.args)
    }

    @Test
    fun `a short text term narrows on nothing but still runs`() {
        val sql = build("hi")
        assertTrue(sql.isFullScan)
    }

    // --- structured query tokens ---

    @Test
    fun `state tokens become a keyword predicate`() {
        val sql = build("i.TODO")
        assertTrue(sql.sql.contains("(keyword COLLATE NOCASE = ?)"))
        assertEquals(listOf("TODO"), sql.args)
    }

    @Test
    fun `i none becomes a null check`() {
        assertTrue(build("i.none").sql.contains("keyword IS NULL"))
    }

    @Test
    fun `priority and tag tokens are ANDed within a group`() {
        val sql = build("p.A t.work")
        assertTrue(sql.sql.contains("priority COLLATE NOCASE = ?"))
        assertTrue(sql.sql.contains("inheritedTags LIKE ? ESCAPE '\\'"))
        assertEquals(listOf("A", "%work%"), sql.args)
    }

    @Test
    fun `tn uses the own-tags column`() {
        assertTrue(build("tn.work").sql.contains("tags LIKE ?"))
        assertFalse(build("tn.work").sql.contains("inheritedTags LIKE ?"))
    }

    @Test
    fun `notebook tokens accept the name with or without the org suffix`() {
        val sql = build("b.work")
        assertTrue(sql.sql.contains("(fileName COLLATE NOCASE = ? OR fileName COLLATE NOCASE = ?)"))
        assertEquals(listOf("work", "work.org"), sql.args)
    }

    @Test
    fun `date windows only require the timestamp to exist`() {
        assertTrue(build("s.3d").sql.contains("scheduled IS NOT NULL"))
        assertTrue(build("d.today").sql.contains("deadline IS NOT NULL"))
        assertTrue(build("a.7d").sql.contains("activeTimestamps IS NOT NULL"))
        assertTrue(build("c.1w").sql.contains("closed IS NOT NULL"))
        assertTrue(build("cr.1m").sql.contains("createdAt IS NOT NULL"))
    }

    @Test
    fun `s none and d none require the timestamp to be absent instead`() {
        assertTrue(build("s.none").sql.contains("scheduled IS NULL"))
        assertFalse(build("s.none").sql.contains("scheduled IS NOT NULL"))
        assertTrue(build("d.none").sql.contains("deadline IS NULL"))
        assertFalse(build("d.none").sql.contains("deadline IS NOT NULL"))
        assertTrue(build("a.none").sql.contains("activeTimestamps IS NULL"))
    }

    @Test
    fun `nodate is accepted as an alias for none`() {
        assertTrue(build("s.nodate").sql.contains("scheduled IS NULL"))
        assertTrue(build("d.nodate").sql.contains("deadline IS NULL"))
    }

    @Test
    fun `overdue only requires the timestamp to exist`() {
        assertTrue(build("s.overdue").sql.contains("scheduled IS NOT NULL"))
        assertTrue(build("d.overdue").sql.contains("deadline IS NOT NULL"))
    }

    @Test
    fun `OR groups become an ORed predicate`() {
        val sql = build("i.TODO OR p.A")
        assertTrue(sql.sql.contains("((keyword COLLATE NOCASE = ?) OR ((priority COLLATE NOCASE = ? OR priority IS NULL)))"))
        assertEquals(listOf("TODO", "A"), sql.args)
    }

    @Test
    fun `a group with nothing pushable drops the whole query predicate`() {
        // The second group would match anything, so ORing it in narrows nothing,
        // and emitting only the first group's predicate would drop valid rows.
        val sql = build("i.TODO OR hi")
        assertFalse(sql.sql.contains("keyword"))
        assertTrue(sql.isFullScan)
    }

    // --- it., comparisons, nesting ---

    @Test
    fun `it none becomes a null check`() {
        assertTrue(build("it.none").sql.contains("(keyword IS NULL)"))
    }

    @Test
    fun `it todo and it done only require a keyword`() {
        // Done-ness isn't a column predicate; "has a keyword" is a superset of both.
        for (q in listOf("it.todo", "it.done")) {
            val sql = build(q)
            assertTrue(q, sql.sql.contains("(keyword IS NOT NULL)"))
            assertTrue(q, sql.args.isEmpty())
        }
    }

    @Test
    fun `comparisons only require the timestamp to exist`() {
        assertTrue(build("s.le.today").sql.contains("scheduled IS NOT NULL"))
        assertTrue(build("d.gt.1w").sql.contains("deadline IS NOT NULL"))
        assertTrue(build("a.eq.tomorrow").sql.contains("activeTimestamps IS NOT NULL"))
        assertTrue(build("c.eq.today").sql.contains("closed IS NOT NULL"))
        assertTrue(build("cr.ge.-1m").sql.contains("createdAt IS NOT NULL"))
    }

    @Test
    fun `comparing against none or nodate still requires presence`() {
        // With an operator "none" is not the absence marker; it names no day,
        // so the matcher rejects every row and requiring presence stays a superset.
        assertTrue(build("s.eq.none").sql.contains("scheduled IS NOT NULL"))
        assertFalse(build("s.eq.none").sql.contains("scheduled IS NULL"))
    }

    @Test
    fun `nested query pushes down its flattened groups`() {
        val sql = build("p.A (i.TODO OR i.NEXT)")
        assertTrue(
            sql.sql,
            sql.sql.contains(
                "(((priority COLLATE NOCASE = ? OR priority IS NULL) AND keyword COLLATE NOCASE = ?) OR " +
                    "((priority COLLATE NOCASE = ? OR priority IS NULL) AND keyword COLLATE NOCASE = ?))",
            ),
        )
        assertEquals(listOf("A", "TODO", "A", "NEXT"), sql.args)
    }

    @Test
    fun `negated group pushes nothing down`() {
        // .(i.TODO OR p.A) flattens to .i.TODO .p.A: all negated, so nothing narrows.
        assertTrue(build(".(i.TODO OR p.A)").isFullScan)
    }

    @Test
    fun `negated group beside a positive term keeps the positive one`() {
        val sql = build("t.work .(i.DONE OR p.C)")
        assertTrue(sql.sql.contains("inheritedTags LIKE ?"))
        assertFalse(sql.sql.contains("keyword"))
        assertFalse(sql.sql.contains("priority"))
    }

    @Test
    fun `a nested branch with nothing pushable drops the whole query predicate`() {
        val sql = build("i.TODO OR (hi lo)")
        assertFalse(sql.sql.contains("keyword"))
        assertTrue(sql.isFullScan)
    }

    @Test
    fun `a term ANDed onto a nested OR narrows every branch`() {
        // Distribution copies p.A into the "hi" branch, so that branch still
        // narrows even though "hi" itself can't.
        val sql = build("p.A (i.TODO OR hi)")
        assertTrue(
            sql.sql,
            sql.sql.contains(
                "(((priority COLLATE NOCASE = ? OR priority IS NULL) AND keyword COLLATE NOCASE = ?) OR ((priority COLLATE NOCASE = ? OR priority IS NULL)))",
            ),
        )
        assertEquals(listOf("A", "TODO", "A"), sql.args)
    }

    @Test
    fun `a query too large to flatten adds no query predicate`() {
        val sql = build((1..7).joinToString(" ") { "(i.TODO$it OR p.$it)" })
        assertTrue(sql.isFullScan)
    }

    @Test
    fun `p keeps unprioritized rows as candidates, ps does not`() {
        // p.X also matches a note at the default priority, which SQL can't see.
        assertTrue(build("p.B").sql.contains("(priority COLLATE NOCASE = ? OR priority IS NULL)"))
        val ps = build("ps.B").sql
        assertTrue(ps.contains("(priority COLLATE NOCASE = ?)"))
        assertFalse(ps.contains("priority IS NULL"))
    }

    @Test
    fun `e pushes down like a`() {
        assertTrue(build("e.ge.today").sql.contains("activeTimestamps IS NOT NULL"))
        assertTrue(build("e.no").sql.contains("activeTimestamps IS NULL"))
    }

    @Test
    fun `negated terms are never pushed down`() {
        // .i.TODO excludes rows; leaving it out keeps the candidate set a superset.
        val sql = build(".i.TODO")
        assertTrue(sql.isFullScan)
    }

    @Test
    fun `negation alongside a positive term keeps only the positive one`() {
        val sql = build("i.TODO .p.A")
        assertTrue(sql.sql.contains("keyword COLLATE NOCASE = ?"))
        assertFalse(sql.sql.contains("priority"))
    }

    @Test
    fun `non-ASCII operands are left to the Kotlin matcher`() {
        // SQLite's NOCASE and LIKE only fold ASCII, so pushing these down could
        // exclude a row Kotlin's Unicode-aware ignoreCase would have matched.
        assertTrue(build("t.café").isFullScan)
        assertTrue(build("i.ЗАДАЧА").isFullScan)
    }

    @Test
    fun `LIKE wildcards inside a tag are escaped`() {
        assertEquals(listOf("%50\\%\\_done%"), build("t.50%_done").args)
    }

    // --- filter chips ---

    @Test
    fun `notebook chip is an exact file name match`() {
        val sql = build(facets = FacetNarrowing(notebook = "work.org"))
        assertTrue(sql.sql.contains("fileName = ?"))
        assertEquals(listOf("work.org"), sql.args)
    }

    @Test
    fun `state chips become an IN list`() {
        val sql = build(facets = FacetNarrowing(states = linkedSetOf("TODO", "NEXT")))
        assertTrue(sql.sql.contains("(keyword IN (?, ?))"))
        assertEquals(listOf("TODO", "NEXT"), sql.args)
    }

    @Test
    fun `the no-state chip ORs in a null check`() {
        val sql = build(facets = FacetNarrowing(states = setOf("TODO"), includeNoState = true))
        assertTrue(sql.sql.contains("(keyword IN (?) OR keyword IS NULL)"))
    }

    @Test
    fun `no-state on its own`() {
        val sql = build(facets = FacetNarrowing(includeNoState = true))
        assertTrue(sql.sql.contains("(keyword IS NULL)"))
        assertTrue(sql.args.isEmpty())
    }

    @Test
    fun `tag chips match a whole tag, not a substring`() {
        // The chips compare whole tags, so "work" must not also match "network".
        val sql = build(facets = FacetNarrowing(tags = setOf("work")))
        assertTrue(sql.sql.contains("instr(':' || inheritedTags || ':', ?) > 0"))
        assertEquals(listOf(":work:"), sql.args)
    }

    @Test
    fun `tag chips are ORed with each other`() {
        val sql = build(facets = FacetNarrowing(tags = linkedSetOf("work", "home")))
        assertTrue(sql.sql.contains("(instr(':' || inheritedTags || ':', ?) > 0 OR instr(':' || inheritedTags || ':', ?) > 0)"))
        assertEquals(listOf(":work:", ":home:"), sql.args)
    }

    @Test
    fun `date presence maps to null checks`() {
        assertTrue(build(facets = FacetNarrowing(scheduled = DatePresence.PRESENT)).sql.contains("scheduled IS NOT NULL"))
        assertTrue(build(facets = FacetNarrowing(scheduled = DatePresence.ABSENT)).sql.contains("scheduled IS NULL"))
        assertTrue(build(facets = FacetNarrowing(deadline = DatePresence.PRESENT)).sql.contains("deadline IS NOT NULL"))
        assertTrue(build(facets = FacetNarrowing(deadline = DatePresence.ANY)).isFullScan)
        assertTrue(build(facets = FacetNarrowing(active = DatePresence.PRESENT)).sql.contains("activeTimestamps IS NOT NULL"))
        assertTrue(build(facets = FacetNarrowing(active = DatePresence.ABSENT)).sql.contains("activeTimestamps IS NULL"))
        assertTrue(build(facets = FacetNarrowing(closed = DatePresence.PRESENT)).sql.contains("closed IS NOT NULL"))
        assertTrue(build(facets = FacetNarrowing(created = DatePresence.ABSENT)).sql.contains("createdAt IS NULL"))
    }

    @Test
    fun `text, query tokens and chips all combine with AND`() {
        val sql = build("meeting i.TODO", FacetNarrowing(priorities = setOf("A")))
        assertTrue(sql.sql.contains("notes_fts MATCH ?"))
        assertTrue(sql.sql.contains("keyword COLLATE NOCASE = ?"))
        assertTrue(sql.sql.contains("priority IN (?)"))
        assertEquals(listOf("(\"meeting\")", "%meeting%", "TODO", "A"), sql.args)
    }

    @Test
    fun `argument order follows placeholder order`() {
        val sql = build(
            "meeting t.work",
            FacetNarrowing(notebook = "n.org", states = setOf("TODO"), priorities = setOf("B"), tags = setOf("home")),
        )
        val placeholders = sql.sql.count { it == '?' }
        assertEquals(placeholders, sql.args.size)
    }
}
