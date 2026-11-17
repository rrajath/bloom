package com.rrajath.grove.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QueryParserTest {

    @Test
    fun `plain words are ANDed text terms`() {
        val q = QueryParser.parse("ramen kyoto")
        assertEquals(1, q.groups.size)
        assertEquals(
            listOf(Condition.Text("ramen"), Condition.Text("kyoto")),
            q.groups[0].map { it.condition },
        )
        assertTrue(q.groups[0].none { it.negated })
    }

    @Test
    fun `OR splits groups`() {
        val q = QueryParser.parse("t.work OR t.home")
        assertEquals(2, q.groups.size)
        assertEquals(Condition.Tag("work", false), q.groups[0][0].condition)
        assertEquals(Condition.Tag("home", false), q.groups[1][0].condition)
    }

    @Test
    fun `dot prefix negates`() {
        val q = QueryParser.parse(".i.done t.trip")
        assertTrue(q.groups[0][0].negated)
        assertEquals(Condition.State("done"), q.groups[0][0].condition)
        assertFalse(q.groups[0][1].negated)
    }

    @Test
    fun `all operator types parse`() {
        val q = QueryParser.parse("s.today d.2d a.7d c.yesterday cr.1w i.todo b.inbox t.x tn.y p.a")
        val conditions = q.groups[0].map { it.condition }
        assertEquals(Condition.Scheduled(Period("today")), conditions[0])
        assertEquals(Condition.Deadline(Period("2d")), conditions[1])
        assertEquals(Condition.Active(Period("7d")), conditions[2])
        assertEquals(Condition.Closed(Period("yesterday")), conditions[3])
        assertEquals(Condition.Created(Period("1w")), conditions[4])
        assertEquals(Condition.State("todo"), conditions[5])
        assertEquals(Condition.Notebook("inbox"), conditions[6])
        assertEquals(Condition.Tag("x", ownOnly = false), conditions[7])
        assertEquals(Condition.Tag("y", ownOnly = true), conditions[8])
        assertEquals(Condition.Priority("a"), conditions[9])
    }

    @Test
    fun `sort and agenda tokens`() {
        val q = QueryParser.parse("i.todo o.scheduled o.priority ad.7")
        assertEquals(listOf(SortKey(SortField.SCHEDULED), SortKey(SortField.PRIORITY)), q.sortBy)
        assertEquals(7, q.agendaDays)
        assertEquals(1, q.groups.size)
    }

    // --- nesting ---

    private fun SearchQuery.groupStrings(): List<String> = groups.map { g ->
        g.joinToString(" ") { t -> (if (t.negated) "." else "") + t.condition.short() }
    }

    private fun Condition.short(): String = when (this) {
        is Condition.Text -> term
        is Condition.State -> "i.$state"
        is Condition.StateType -> "it.${type.name.lowercase()}"
        is Condition.Notebook -> "b.$name"
        is Condition.Tag -> "t.$tag"
        is Condition.Priority -> "p.$priority"
        is Condition.Scheduled -> "s.${period.raw}"
        is Condition.Closed -> "c.${period.op?.name?.lowercase()?.plus(".") ?: ""}${period.raw}"
        else -> toString()
    }

    @Test
    fun `brackets nest and distribute AND over OR`() {
        val q = QueryParser.parse("i.todo (s.today OR p.A)")
        assertEquals(listOf("i.todo s.today", "i.todo p.A"), q.groupStrings())
    }

    @Test
    fun `two levels of nesting with explicit AND, it and comparison`() {
        val q = QueryParser.parse("b.shopping t.tigros AND (it.todo OR (it.done AND c.eq.today)) o.p o.st o.t")
        assertEquals(
            listOf("b.shopping t.tigros it.todo", "b.shopping t.tigros it.done c.eq.today"),
            q.groupStrings(),
        )
        assertEquals(
            listOf(SortKey(SortField.PRIORITY), SortKey(SortField.STATE), SortKey(SortField.TITLE)),
            q.sortBy,
        )
    }

    @Test
    fun `brackets attached to words are split off`() {
        assertEquals(listOf("a b", "c"), QueryParser.parse("(a b)OR(c)").groupStrings())
    }

    @Test
    fun `negated group applies De Morgan`() {
        assertEquals(listOf(".t.work .t.home"), QueryParser.parse(".(t.work OR t.home)").groupStrings())
        assertEquals(listOf(".t.work", ".t.home"), QueryParser.parse(".(t.work t.home)").groupStrings())
        assertEquals(listOf("t.work"), QueryParser.parse(".(.t.work)").groupStrings())
    }

    @Test
    fun `unbalanced and empty brackets are lenient`() {
        assertEquals(listOf("a b", "a c"), QueryParser.parse("a (b OR c").groupStrings())
        assertEquals(listOf("a b"), QueryParser.parse("a) b)").groupStrings())
        assertEquals(listOf("a"), QueryParser.parse("a OR ()").groupStrings())
        assertTrue(QueryParser.parse("()").isEmpty)
        assertTrue(QueryParser.parse("( OR )").isEmpty)
    }

    @Test
    fun `and and or are case-insensitive like Orgzly`() {
        assertEquals(listOf("b.Home phone", "b.Work phone"), QueryParser.parse("(b.Home or b.Work) phone").groupStrings())
        assertEquals(listOf("a b", "c"), QueryParser.parse("a And b Or c").groupStrings())
    }

    // --- quoting ---

    @Test
    fun `quoted notebook names keep their spaces`() {
        assertEquals(Condition.Notebook("My Notebook"), QueryParser.parse("b.\"My Notebook\"").groups[0][0].condition)
        val neg = QueryParser.parse(".b.\"My Notebook\" i.todo").groups[0]
        assertEquals(Term(Condition.Notebook("My Notebook"), negated = true), neg[0])
    }

    @Test
    fun `quoted words are literal text`() {
        val q = QueryParser.parse("\"phone call\" \"or\" \"f(x)\" \"i.todo\" .\"draft copy\"")
        assertEquals(
            listOf(
                Term(Condition.Text("phone call"), false),
                Term(Condition.Text("or"), false),
                Term(Condition.Text("f(x)"), false),
                Term(Condition.Text("i.todo"), false),
                Term(Condition.Text("draft copy"), true),
            ),
            q.groups.single(),
        )
    }

    @Test
    fun `quotes are lenient`() {
        // Unclosed runs to the end; empty quotes contribute nothing.
        assertEquals(listOf("a b c"), QueryParser.parse("\"a b c").groupStrings().map { it })
        assertEquals(listOf("x"), QueryParser.parse("x \"\"").groupStrings())
        assertTrue(QueryParser.parse("\"\"").isEmpty)
    }

    @Test
    fun `e is an alias for a and ps is set-only priority`() {
        val q = QueryParser.parse("e.ge.now ps.b p.b")
        assertEquals(
            listOf(
                Condition.Active(Period("now", CompareOp.GE)),
                Condition.Priority("b", setOnly = true),
                Condition.Priority("b"),
            ),
            q.groups[0].map { it.condition },
        )
    }

    @Test
    fun `oversized flattening keeps the tree but skips groups`() {
        val input = (1..7).joinToString(" ") { "(x$it OR y$it)" }
        val q = QueryParser.parse(input)
        assertTrue(q.isFlatteningSkipped)
        assertTrue(q.groups.isEmpty())
        assertFalse(q.isEmpty)
        assertEquals(14, q.textTerms.size)
    }

    // --- it., comparisons, sort keys ---

    @Test
    fun `it tokens parse, unknown values stay text`() {
        val q = QueryParser.parse("it.todo it.DONE it.none it.later")
        assertEquals(
            listOf(
                Condition.StateType(Condition.StateType.Type.TODO),
                Condition.StateType(Condition.StateType.Type.DONE),
                Condition.StateType(Condition.StateType.Type.NONE),
                Condition.Text("it.later"),
            ),
            q.groups[0].map { it.condition },
        )
    }

    @Test
    fun `comparison operators parse on every date prefix`() {
        val q = QueryParser.parse("s.le.today d.gt.3d a.eq.tomorrow c.ge.-1w cr.ne.yesterday s.today")
        assertEquals(
            listOf(
                Condition.Scheduled(Period("today", CompareOp.LE)),
                Condition.Deadline(Period("3d", CompareOp.GT)),
                Condition.Active(Period("tomorrow", CompareOp.EQ)),
                Condition.Closed(Period("-1w", CompareOp.GE)),
                Condition.Created(Period("yesterday", CompareOp.NE)),
                Condition.Scheduled(Period("today")),
            ),
            q.groups[0].map { it.condition },
        )
    }

    @Test
    fun `targets count from today, sign respected for every prefix`() {
        val today = LocalDate.of(2025, 6, 11)
        assertEquals(today.plusDays(3), Period("3d").target(today))
        assertEquals(today.plusDays(3), Period("+3d").target(today))
        assertEquals(today.minusDays(3), Period("-3d").target(today))
        assertEquals(today.plusWeeks(2), Period("2w").target(today))
        assertEquals(today.minusMonths(1), Period("-1m").target(today))
        assertEquals(LocalDate.of(2025, 1, 31), Period("2025-01-31").target(today))
        assertNull(Period("overdue").target(today))
        assertNull(Period("none").target(today))
        assertNull(Period("3h").target(today))
        assertNull(Period("nonsense").target(today))
    }

    @Test
    fun `Orgzly day aliases`() {
        val today = LocalDate.of(2025, 6, 11)
        for (alias in listOf("today", "tod", "now", "TODAY")) assertEquals(alias, today, Period(alias).target(today))
        for (alias in listOf("tomorrow", "tom", "tmrw")) assertEquals(alias, today.plusDays(1), Period(alias).target(today))
        assertEquals(today.minusDays(1), Period("yesterday").target(today))
    }

    @Test
    fun `Orgzly sort aliases and reversed sort`() {
        val q = QueryParser.parse("o.book o.sched .o.dead o.e o.close o.cr o.pri .o.state o.title o.bogus")
        assertEquals(
            listOf(
                SortKey(SortField.NOTEBOOK),
                SortKey(SortField.SCHEDULED),
                SortKey(SortField.DEADLINE, descending = true),
                SortKey(SortField.EVENT),
                SortKey(SortField.CLOSED),
                SortKey(SortField.CREATED),
                SortKey(SortField.PRIORITY),
                SortKey(SortField.STATE, descending = true),
                SortKey(SortField.TITLE),
            ),
            q.sortBy,
        )
        assertTrue(q.groups.isEmpty())
    }

    @Test
    fun `sort and agenda modifiers inside brackets stay global`() {
        val q = QueryParser.parse("(o.t t.x) OR (t.y .o.p ad.3)")
        assertEquals(listOf("t.x", "t.y"), q.groupStrings())
        assertEquals(listOf(SortKey(SortField.TITLE), SortKey(SortField.PRIORITY, descending = true)), q.sortBy)
        assertEquals(3, q.agendaDays)
    }

    @Test
    fun `a group holding only modifiers contributes no branch`() {
        // Otherwise "t.x OR (o.p)" would gain an empty, match-everything group.
        assertEquals(listOf("t.x"), QueryParser.parse("t.x OR (o.p)").groupStrings())
    }

    @Test
    fun `deep nesting flattens correctly`() {
        val q = QueryParser.parse("a (b (c (d OR e)))")
        assertEquals(listOf("a b c d", "a b c e"), q.groupStrings())
    }

    @Test
    fun `odd inputs parse without crashing`() {
        assertTrue(QueryParser.parse("AND").isEmpty)
        assertTrue(QueryParser.parse("OR OR").isEmpty)
        assertTrue(QueryParser.parse(".(").isEmpty)
        assertTrue(QueryParser.parse(")))(((").isEmpty)
        assertEquals(listOf("a", "b"), QueryParser.parse("a AND OR b").groupStrings())
        assertEquals(listOf("a b"), QueryParser.parse("AND a AND b AND").groupStrings())
        // A lone "." is text, and ". (a)" (with a space) is not a group negation.
        assertEquals(listOf(". a"), QueryParser.parse(". (a)").groupStrings())
        // ".." before a bracket isn't the negation marker either.
        assertEquals(listOf(".. a"), QueryParser.parse("..(a)").groupStrings())
    }

    @Test
    fun `malformed comparisons fall back to plain periods`() {
        assertEquals(Condition.Scheduled(Period("eq.")), QueryParser.parse("s.eq.").groups[0][0].condition)
        assertEquals(Condition.Scheduled(Period("foo.today")), QueryParser.parse("s.foo.today").groups[0][0].condition)
        assertEquals(Condition.Scheduled(Period("today", CompareOp.LE)), QueryParser.parse("s.LE.today").groups[0][0].condition)
        // With an operator, none/nodate/overdue aren't the special window tokens.
        val p = Period.parse("eq.none")
        assertFalse(p.isNoDate)
        assertFalse(Period.parse("le.overdue").isOverdue)
    }

    @Test
    fun `text terms come from the tree without duplicates from distribution`() {
        val q = QueryParser.parse("milk (eggs OR bread) .cheese")
        assertEquals(listOf("milk", "eggs", "bread"), q.textTerms)
    }

    @Test
    fun `words containing dots stay text`() {
        val q = QueryParser.parse("file.org v1.2")
        assertEquals(
            listOf(Condition.Text("file.org"), Condition.Text("v1.2")),
            q.groups[0].map { it.condition },
        )
    }

    @Test
    fun `empty and blank queries`() {
        assertTrue(QueryParser.parse("").isEmpty)
        assertTrue(QueryParser.parse("   ").isEmpty)
    }

    @Test
    fun `none, no, nodate and overdue are op-less specials`() {
        for (t in listOf("none", "no", "nodate", "NoDate")) assertTrue(t, Period(t).isNoDate)
        assertFalse(Period("today").isNoDate)
        assertFalse(Period("none", CompareOp.EQ).isNoDate)
        assertTrue(Period("overdue").isOverdue)
        assertFalse(Period("overdue", CompareOp.LE).isOverdue)
        assertFalse(Period("today").isOverdue)
    }

    @Test
    fun `id prefix parses to an Id condition`() {
        assertEquals(Condition.Id("a1b2"), QueryParser.parse("id.a1b2").groups[0][0].condition)
        assertTrue(Condition.Id("NONE").isNone)
        assertFalse(Condition.Id("abc").isNone)
        val negated = QueryParser.parse(".id.none").groups[0][0]
        assertEquals(Condition.Id("none"), negated.condition)
        assertTrue(negated.negated)
    }
}
