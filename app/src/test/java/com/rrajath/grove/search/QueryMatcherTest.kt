package com.rrajath.grove.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class QueryMatcherTest {

    private val today = LocalDate.of(2025, 6, 11)

    private fun note(
        title: String,
        fileName: String = "notes.org",
        keyword: String? = null,
        done: Boolean = false,
        priority: String? = null,
        tags: List<String> = emptyList(),
        inherited: List<String> = tags,
        scheduled: String? = null,
        deadline: String? = null,
        closed: String? = null,
        active: String? = null,
        created: String? = null,
        body: String = "",
        modified: Long = 0L,
        line: Int = 0,
    ) = NoteMeta(
        fileName, line, title, keyword, done, priority, tags, inherited,
        scheduled, deadline, closed, active, created, modified, "$title\n$body",
    )

    private fun run(query: String, vararg notes: NoteMeta): List<String> =
        QueryMatcher.filter(notes.toList(), QueryParser.parse(query), today).map { it.title }

    @Test
    fun `text terms match title and body case-insensitively`() {
        val a = note("Ramen places", body = "the best shoyu")
        val b = note("Sushi", body = "try the RAMEN here too")
        val c = note("Pasta")
        assertEquals(listOf("Ramen places", "Sushi"), run("ramen", a, b, c))
        assertEquals(listOf("Sushi"), run("ramen sushi", a, b, c))
    }

    @Test
    fun `state notebook tag priority conditions`() {
        val a = note("A", keyword = "TODO", fileName = "work.org", tags = listOf("project"), priority = "A")
        val b = note("B", keyword = "DONE", done = true, fileName = "home.org", tags = listOf("beeblebrox"))
        assertEquals(listOf("A"), run("i.todo", a, b))
        assertEquals(listOf("B"), run("i.done", a, b))
        assertEquals(listOf("A"), run("b.work", a, b))
        // tag substring match (PRD §5.6)
        assertEquals(listOf("B"), run("t.bee", a, b))
        assertEquals(listOf("A"), run("p.a", a, b))
    }

    @Test
    fun `inherited vs own tags`() {
        val child = note("Child", tags = emptyList(), inherited = listOf("trip"))
        assertEquals(listOf("Child"), run("t.trip", child))
        assertEquals(emptyList<String>(), run("tn.trip", child))
    }

    @Test
    fun `i none matches keywordless notes`() {
        val a = note("Plain")
        val b = note("Task", keyword = "TODO")
        assertEquals(listOf("Plain"), run("i.none", a, b))
    }

    @Test
    fun `plain s and d default to le, so today includes overdue like Orgzly`() {
        val past = note("Past", scheduled = "<2025-06-01 Sun>", deadline = "<2025-06-01 Sun>")
        val todayNote = note("Today", scheduled = "<2025-06-11 Wed>", deadline = "<2025-06-11 Wed>")
        val future = note("Future", scheduled = "<2025-06-20 Fri>", deadline = "<2025-06-20 Fri>")
        val none = note("None")
        for (p in listOf("s", "d")) {
            assertEquals(p, listOf("Past", "Today"), run("$p.today", past, todayNote, future, none))
            assertEquals(p, listOf("Past"), run("$p.yesterday", past, todayNote, future, none))
            assertEquals(p, listOf("Past", "Today", "Future"), run("$p.2w", past, todayNote, future, none))
            assertEquals(p, listOf("Past"), run("$p.-1d", past, todayNote, future, none))
            // The old exact-day meaning is now spelled with eq.
            assertEquals(p, listOf("Today"), run("$p.eq.today", past, todayNote, future, none))
        }
    }

    @Test
    fun `plain cr defaults to le and plain c to eq`() {
        val created10 = note("C10", created = "[2025-06-10 Tue]", closed = "[2025-06-10 Tue]")
        val created04 = note("C04", created = "[2025-06-04 Wed]", closed = "[2025-06-04 Wed]")
        assertEquals(listOf("C10", "C04"), run("cr.yesterday", created10, created04))
        assertEquals(listOf("C04"), run("cr.-1w", created10, created04))
        assertEquals(listOf("C10"), run("c.yesterday", created10, created04))
        assertEquals(listOf("C04"), run("c.-1w", created10, created04))
        // Unsigned offsets count forward: closed exactly a week from now matches nothing.
        assertEquals(emptyList<String>(), run("c.1w", created10, created04))
        assertEquals(listOf("C10", "C04"), run("cr.1w", created10, created04))
    }

    @Test
    fun `s overdue and d overdue match only past-dated notes`() {
        val past = note("Past", scheduled = "<2025-06-01 Sun>", deadline = "<2025-06-01 Sun>")
        val todayNote = note("Today", scheduled = "<2025-06-11 Wed>")
        val future = note("Future", scheduled = "<2025-06-20 Fri>")
        val none = note("None")
        assertEquals(listOf("Past"), run("s.overdue", past, todayNote, future, none))
        assertEquals(listOf("Past"), run("d.overdue", past, todayNote, future, none))
    }

    @Test
    fun `s nodate and d nodate match notes with no such timestamp`() {
        val scheduledOnly = note("Scheduled", scheduled = "<2025-06-11 Wed>")
        val deadlineOnly = note("Deadline", deadline = "<2025-06-11 Wed>")
        val neither = note("Neither")
        assertEquals(listOf("Deadline", "Neither"), run("s.nodate", scheduledOnly, deadlineOnly, neither))
        assertEquals(listOf("Scheduled", "Neither"), run("d.nodate", scheduledOnly, deadlineOnly, neither))
        // "none" is still accepted as an alias.
        assertEquals(listOf("Deadline", "Neither"), run("s.none", scheduledOnly, deadlineOnly, neither))
        // Negated: the opposite set, same as any other s./d. condition.
        assertEquals(listOf("Scheduled"), run(".s.nodate", scheduledOnly, deadlineOnly, neither))
    }


    @Test
    fun `ad N narrows to scheduled or deadline within the window, dropping undated notes`() {
        val withinScheduled = note("Within scheduled", scheduled = "<2025-06-15 Sun>")
        val withinDeadline = note("Within deadline", deadline = "<2025-06-16 Mon>")
        val overdue = note("Overdue", scheduled = "<2025-06-01 Sun>")
        val tooFar = note("Too far", scheduled = "<2025-06-30 Mon>")
        val undated = note("Undated", keyword = "TODO")
        assertEquals(
            listOf("Within scheduled", "Within deadline", "Overdue"),
            run("ad.7", withinScheduled, withinDeadline, overdue, tooFar, undated),
        )
    }

    @Test
    fun `ad N combines with other criteria as AND`() {
        val dueTodo = note("Due todo", keyword = "TODO", scheduled = "<2025-06-12 Thu>")
        val dueDone = note("Due done", keyword = "DONE", done = true, scheduled = "<2025-06-12 Thu>")
        val undatedTodo = note("Undated todo", keyword = "TODO")
        // Regression: "ad.7 i.todo" must not show tasks with no date set at all.
        assertEquals(listOf("Due todo"), run("ad.7 i.todo", dueTodo, dueDone, undatedTodo))
    }

    @Test
    fun `closed within past window`() {
        val recent = note("Recent", closed = "[2025-06-10 Tue]")
        val old = note("Old", closed = "[2025-05-01 Thu]")
        assertEquals(listOf("Recent"), run("c.ge.-2d", recent, old))
        assertEquals(emptyList<String>(), run("c.today", recent, old))
    }

    @Test
    fun `a period matches bare active timestamps over the whole list`() {
        val soon = note("Soon", active = "<2025-06-13 Fri>")
        val far = note("Far", active = "<2025-07-20 Sun>")
        val multi = note("Multi", active = "<2025-09-01 Mon> <2025-06-12 Thu>")
        val none = note("None")
        assertEquals(listOf("Soon", "Multi"), run("a.le.7d", soon, far, multi, none))
        // a./e. default to eq: a.2d is the one day two days out.
        assertEquals(listOf("Soon"), run("a.2d", soon, far, multi, none))
        assertEquals(listOf("Soon"), run("e.2d", soon, far, multi, none))
        // "none" alias matches only notes with no active timestamp at all.
        assertEquals(listOf("None"), run("a.none", soon, far, none))
        assertEquals(listOf("Soon", "Far"), run(".a.none", soon, far, none))
    }

    @Test
    fun `a today matches a ranged event that spans today`() {
        val spanning = note("Spanning", active = "<2025-06-09 Mon>--<2025-06-15 Sun>")
        val past = note("Past", active = "<2025-06-01 Sun>--<2025-06-05 Thu>")
        assertEquals(listOf("Spanning"), run("a.today", spanning, past))
        // A past event is still reachable by an explicit a.overdue (only the agenda hides it).
        assertEquals(listOf("Past"), run("a.overdue", spanning, past))
    }

    @Test
    fun `o active sorts by the earliest active date`() {
        val a = note("A", active = "<2025-06-20 Fri>")
        val b = note("B", active = "<2025-08-01 Fri> <2025-06-12 Thu>")
        assertEquals(listOf("B", "A"), run("a.le.3m o.active", a, b))
    }

    @Test
    fun `negation and OR`() {
        val a = note("A", keyword = "TODO", tags = listOf("work"))
        val b = note("B", keyword = "DONE", done = true, tags = listOf("work"))
        val c = note("C", tags = listOf("home"))
        assertEquals(listOf("A"), run("t.work .i.done", a, b, c))
        assertEquals(listOf("A", "C"), run("i.todo OR t.home", a, b, c).sorted())
    }

    @Test
    fun `ranking exact title then contains then body`() {
        val exact = note("ramen", modified = 1)
        val inTitle = note("ramen places", modified = 2)
        val inBody = note("Sushi", body = "ramen mention", modified = 3)
        assertEquals(
            listOf("ramen", "ramen places", "Sushi"),
            run("ramen", inBody, inTitle, exact),
        )
    }

    @Test
    fun `explicit sort overrides ranking`() {
        val a = note("A", scheduled = "<2025-06-20 Fri>")
        val b = note("B", scheduled = "<2025-06-12 Thu>")
        assertEquals(listOf("B", "A"), run("s.2w o.scheduled", a, b))
    }

    @Test
    fun `snippet keeps ten words either side of the match`() {
        val line = (1..30).joinToString(" ") { "w$it" }.replace("w15", "magic")
        val snippets = Snippets.windows(line, listOf("magic"))
        assertEquals(listOf("…w5 w6 w7 w8 w9 w10 w11 w12 w13 w14 magic w16 w17 w18 w19 w20 w21 w22 w23 w24 w25…"), snippets)
    }

    @Test
    fun `a short line is shown whole with no ellipsis`() {
        assertEquals(listOf("the magic word"), Snippets.windows("the magic word", listOf("magic")))
    }

    @Test
    fun `a second match inside the window does not open another snippet`() {
        val snippets = Snippets.windows("magic one two magic three", listOf("magic"))
        assertEquals(listOf("magic one two magic three"), snippets)
    }

    @Test
    fun `matches further apart than the window get their own snippets`() {
        val line = (1..40).joinToString(" ") { "w$it" }.replace("w1 ", "magic ").replace("w30", "magic")
        val snippets = Snippets.windows(line, listOf("magic"))
        assertEquals(2, snippets.size)
        assertTrue(snippets[0].startsWith("magic w2"))
        assertTrue(snippets[1].startsWith("…w20") && snippets[1].contains("magic"))
    }

    @Test
    fun `a line without the term yields no snippet`() {
        assertTrue(Snippets.windows("no match here", listOf("absent")).isEmpty())
    }

    @Test
    fun `highlightRanges covers every occurrence of every term`() {
        val text = "magic word, another magic moment"
        val words = Snippets.highlightRanges(text, listOf("magic", "moment")).map { text.substring(it) }
        assertEquals(listOf("magic", "magic", "moment"), words)
    }

    @Test
    fun `title highlight ranges cover every match`() {
        val ranges = Snippets.highlightRanges("Call TransUnion about the transfer", listOf("trans"))
        val words = ranges.map { "Call TransUnion about the transfer".substring(it) }
        assertEquals(listOf("Trans", "trans"), words)
    }

    // --- nesting, it., comparisons, Orgzly sort keys ---

    @Test
    fun `nested query matches the tree`() {
        val open = note("Milk", keyword = "TODO", fileName = "shopping.org", tags = listOf("tigros"))
        val doneToday = note("Eggs", keyword = "DONE", done = true, fileName = "shopping.org",
            tags = listOf("tigros"), closed = "[2025-06-11 Wed 10:00]")
        val doneEarlier = note("Bread", keyword = "DONE", done = true, fileName = "shopping.org",
            tags = listOf("tigros"), closed = "[2025-06-01 Sun]")
        val elsewhere = note("Soap", keyword = "TODO", fileName = "shopping.org", tags = listOf("coop"))
        assertEquals(
            listOf("Eggs", "Milk"),
            run("b.shopping t.tigros AND (it.todo OR (it.done AND c.eq.today)) o.t", open, doneToday, doneEarlier, elsewhere),
        )
    }

    @Test
    fun `negated group excludes either tag`() {
        val a = note("A", tags = listOf("work"))
        val b = note("B", tags = listOf("home"))
        val c = note("C")
        assertEquals(listOf("C"), run(".(t.work OR t.home)", a, b, c))
    }

    @Test
    fun `oversized query still matches through the tree`() {
        val hit = note("x1 x2 x3 x4 x5 x6 y7")
        val miss = note("x1 x2 x3 x4 x5 x6")
        val query = (1..7).joinToString(" ") { "(x$it OR y$it)" }
        assertEquals(listOf("x1 x2 x3 x4 x5 x6 y7"), run(query, hit, miss))
        val parsed = QueryParser.parse(query)
        assertEquals(listOf(emptyList<Term>()), QueryMatcher.satisfiedGroups(hit, parsed, today))
        assertTrue(QueryMatcher.satisfiedGroups(miss, parsed, today).isEmpty())
    }

    @Test
    fun `it matches keyword types`() {
        val todo = note("T", keyword = "NEXT")
        val done = note("D", keyword = "CANCELLED", done = true)
        val none = note("N")
        assertEquals(listOf("T"), run("it.todo", todo, done, none))
        assertEquals(listOf("D"), run("it.done", todo, done, none))
        assertEquals(listOf("N"), run("it.none", todo, done, none))
    }

    @Test
    fun `comparison operators on dates`() {
        val past = note("Past", scheduled = "<2025-06-09 Mon>")
        val now = note("Now", scheduled = "<2025-06-11 Wed>")
        val later = note("Later", scheduled = "<2025-06-14 Sat>")
        val none = note("None")
        assertEquals(listOf("Past", "Now"), run("s.le.today", past, now, later, none))
        assertEquals(listOf("Later"), run("s.gt.today", past, now, later, none))
        assertEquals(listOf("Past", "Later"), run("s.ne.today", past, now, later, none))
        assertEquals(listOf("Later"), run("s.ge.3d", past, now, later, none))
        assertEquals(listOf("Past"), run("s.lt.-1d", past, now, later, none))
        assertEquals(emptyList<String>(), run("s.le.overdue", past, now, later, none))

        val closedRecent = note("Recent", closed = "[2025-06-10 Tue]")
        val closedOld = note("Old", closed = "[2025-05-01 Thu]")
        assertEquals(listOf("Recent"), run("c.ge.-1w", closedRecent, closedOld))
        // Unsigned offsets count forward for every prefix (Orgzly), c. included.
        assertEquals(emptyList<String>(), run("c.ge.1w", closedRecent, closedOld))
        assertEquals(listOf("Recent"), run("c.eq.yesterday", closedRecent, closedOld))
        assertEquals(listOf("Recent"), run("c.ge.2025-06-01", closedRecent, closedOld))
    }

    @Test
    fun `sort by state follows configured order, missing last either way`() {
        val todo = note("a", keyword = "TODO")
        val next = note("b", keyword = "NEXT")
        val done = note("c", keyword = "DONE", done = true)
        val none = note("d")
        val order = listOf("TODO", "NEXT", "DONE")
        fun sorted(q: String) = QueryMatcher.filter(listOf(none, done, next, todo), QueryParser.parse(q), today, MatchOptions(stateOrder = order))
            .map { it.title }
        assertEquals(listOf("a", "b", "c", "d"), sorted("o.st"))
        assertEquals(listOf("c", "b", "a", "d"), sorted(".o.state"))
    }

    @Test
    fun `sort by event uses oldest ascending and newest descending`() {
        // spread's oldest event is earliest and its newest is latest, so it
        // leads in both directions only if each direction picks its own end.
        val spread = note("spread", active = "<2025-06-01 Sun> <2025-06-30 Mon>")
        val middle = note("middle", active = "<2025-06-15 Sun>")
        val none = note("none")
        assertEquals(listOf("spread", "middle", "none"), run("o.e", none, middle, spread))
        assertEquals(listOf("spread", "middle", "none"), run(".o.event", none, middle, spread))
        assertEquals(listOf("spread", "middle", "none"), run("o.a", none, middle, spread))
    }

    @Test
    fun `sort by priority, title and notebook`() {
        val a = note("beta", priority = "A", fileName = "z.org")
        val c = note("Alpha", priority = "C", fileName = "a.org")
        val none = note("gamma", fileName = "m.org")
        assertEquals(listOf("beta", "Alpha", "gamma"), run("o.prio", none, c, a))
        assertEquals(listOf("Alpha", "beta", "gamma"), run("o.t", none, a, c))
        assertEquals(listOf("gamma", "beta", "Alpha"), run(".o.t", c, a, none))
        assertEquals(listOf("beta", "gamma", "Alpha"), run(".o.book", c, none, a))
    }

    // --- matcher edge cases ---

    @Test
    fun `comparisons on deadline and created`() {
        val soon = note("Soon", deadline = "<2025-06-12 Thu>", created = "[2025-06-10 Tue]")
        val far = note("Far", deadline = "<2025-07-01 Tue>", created = "[2025-05-01 Thu]")
        val none = note("None")
        assertEquals(listOf("Far"), run("d.gt.1w", soon, far, none))
        assertEquals(listOf("Soon"), run("d.eq.tomorrow", soon, far, none))
        assertEquals(listOf("Soon"), run("cr.ge.-1w", soon, far, none))
        assertEquals(listOf("Far"), run("cr.lt.-1m", soon, far, none))
        assertEquals(listOf("Soon", "Far"), run("cr.le.+0d", soon, far, none))
    }

    @Test
    fun `event comparisons match when any event day passes`() {
        val two = note("Two", active = "<2025-06-01 Sun> <2025-06-20 Fri>")
        val ranged = note("Ranged", active = "<2025-06-10 Tue>--<2025-06-12 Thu>")
        val none = note("None")
        assertEquals(listOf("Two"), run("a.lt.-5d", two, ranged, none))
        assertEquals(listOf("Two", "Ranged"), run("a.ge.today", two, ranged, none))
        // A ranged event contributes every day it spans, so today matches eq.
        assertEquals(listOf("Ranged"), run("a.eq.today", two, ranged, none))
        // ne passes when at least one day differs, which a two-event note always has.
        assertEquals(listOf("Two", "Ranged"), run("a.ne.today", two, ranged, none))
        assertEquals(emptyList<String>(), run("a.eq.overdue", two, ranged, none))
    }

    @Test
    fun `negated it`() {
        val todo = note("T", keyword = "TODO")
        val done = note("D", keyword = "DONE", done = true)
        val none = note("N")
        assertEquals(listOf("T", "N"), run(".it.done", todo, done, none))
        assertEquals(listOf("T", "D"), run(".it.none", todo, done, none))
    }

    @Test
    fun `negating a nested group applies De Morgan at every level`() {
        // .(t.aa (t.bb OR t.cc)) excludes only notes with aa AND (bb OR cc).
        val ab = note("ab", tags = listOf("aa", "bb"))
        val ac = note("ac", tags = listOf("aa", "cc"))
        val a = note("a", tags = listOf("aa"))
        val bc = note("bc", tags = listOf("bb", "cc"))
        val none = note("none")
        assertEquals(listOf("a", "bc", "none"), run(".(t.aa (t.bb OR t.cc))", ab, ac, a, bc, none))
    }

    @Test
    fun `agenda window still applies to a nested query`() {
        val inWindow = note("In", tags = listOf("xx"), scheduled = "<2025-06-13 Fri>")
        val unscheduled = note("Unscheduled", tags = listOf("xx"))
        val otherTag = note("Other", tags = listOf("zz"), scheduled = "<2025-06-12 Thu>")
        assertEquals(listOf("In"), run("ad.7 (t.xx OR t.yy)", inWindow, unscheduled, otherTag))
    }

    @Test
    fun `satisfiedGroups reports only the branches a note matched`() {
        val milkEggs = note("milk eggs")
        val q = QueryParser.parse("milk (eggs OR bread)")
        val groups = QueryMatcher.satisfiedGroups(milkEggs, q, today)
        assertEquals(1, groups.size)
        assertEquals(listOf("milk", "eggs"), groups.single().textTerms())
    }

    // --- sort edge cases ---

    @Test
    fun `date sorts order by time of day within a date`() {
        val untimed = note("untimed", scheduled = "<2025-06-12 Thu>", deadline = "<2025-06-12 Thu>",
            closed = "[2025-06-10 Tue]", created = "[2025-06-10 Tue]")
        val morning = note("morning", scheduled = "<2025-06-12 Thu 09:00>", deadline = "<2025-06-12 Thu 09:00>",
            closed = "[2025-06-10 Tue 09:00]", created = "[2025-06-10 Tue 09:00]")
        val evening = note("evening", scheduled = "<2025-06-12 Thu 18:30>", deadline = "<2025-06-12 Thu 18:30>",
            closed = "[2025-06-10 Tue 18:30]", created = "[2025-06-10 Tue 18:30]")
        for (key in listOf("s", "d", "c", "cr")) {
            assertEquals(key, listOf("untimed", "morning", "evening"), run("o.$key", evening, untimed, morning))
            assertEquals(key, listOf("evening", "morning", "untimed"), run(".o.$key", morning, evening, untimed))
        }
    }

    @Test
    fun `reversed date sorts still put missing dates last`() {
        val early = note("early", scheduled = "<2025-06-01 Sun>", deadline = "<2025-06-01 Sun>",
            closed = "[2025-06-01 Sun]", created = "[2025-06-01 Sun]", active = "<2025-06-01 Sun>")
        val late = note("late", scheduled = "<2025-06-20 Fri>", deadline = "<2025-06-20 Fri>",
            closed = "[2025-06-20 Fri]", created = "[2025-06-20 Fri]", active = "<2025-06-20 Fri>")
        val none = note("none")
        for (key in listOf("s", "d", "c", "cr", "e")) {
            assertEquals(key, listOf("early", "late", "none"), run("o.$key", none, late, early))
            assertEquals(key, listOf("late", "early", "none"), run(".o.$key", none, early, late))
        }
    }

    @Test
    fun `later sort keys break ties left by earlier ones`() {
        val aTodo = note("aTodo", priority = "A", keyword = "TODO")
        val aDone = note("aDone", priority = "A", keyword = "DONE", done = true)
        val bTodo = note("bTodo", priority = "B", keyword = "TODO")
        val order = listOf("TODO", "DONE")
        fun sorted(q: String) =
            QueryMatcher.filter(listOf(bTodo, aDone, aTodo), QueryParser.parse(q), today, MatchOptions(stateOrder = order)).map { it.title }
        assertEquals(listOf("aTodo", "aDone", "bTodo"), sorted("o.p o.st"))
        assertEquals(listOf("aDone", "aTodo", "bTodo"), sorted("o.p .o.st"))
        assertEquals(listOf("aTodo", "bTodo", "aDone"), sorted("o.st o.p"))
    }

    @Test
    fun `state sort is case-insensitive and puts unconfigured keywords after configured ones`() {
        val todo = note("todo", keyword = "todo")
        val waiting = note("waiting", keyword = "WAITING")
        val done = note("done", keyword = "DONE", done = true)
        val none = note("none")
        val order = listOf("TODO", "DONE")
        fun sorted(q: String) =
            QueryMatcher.filter(listOf(none, waiting, done, todo), QueryParser.parse(q), today, MatchOptions(stateOrder = order)).map { it.title }
        assertEquals(listOf("todo", "done", "waiting", "none"), sorted("o.st"))
        assertEquals(listOf("waiting", "done", "todo", "none"), sorted(".o.st"))
    }

    @Test
    fun `state sort with no configured order keeps keyworded notes ahead of none`() {
        val todo = note("todo", keyword = "TODO")
        val none = note("none")
        assertEquals(listOf("todo", "none"), run("o.st", none, todo))
    }

    // --- odd inputs ---

    @Test
    fun `malformed comparisons and unknown operators match nothing`() {
        val n = note("n", scheduled = "<2025-06-11 Wed>")
        for (q in listOf("s.eq.", "s.foo.today", "s.le.nodate", "s.eq.3x")) {
            assertEquals(q, emptyList<String>(), run(q, n))
        }
    }

    // --- default priority, ps., aliases ---

    @Test
    fun `p counts unprioritized notes as the default priority, ps does not`() {
        val b = note("B", priority = "B")
        val a = note("A", priority = "A")
        val none = note("None")
        fun run(q: String, default: String?) = QueryMatcher.filter(
            listOf(b, a, none), QueryParser.parse(q), today, MatchOptions(defaultPriority = default),
        ).map { it.title }
        assertEquals(listOf("B", "None"), run("p.b", "B"))
        assertEquals(listOf("B"), run("p.b", null))
        assertEquals(listOf("B"), run("ps.b", "B"))
        assertEquals(listOf("A"), run("p.a", "B"))
        assertEquals(listOf("A"), run(".p.b", "B"))
        assertEquals(listOf("A", "None"), run(".ps.b", "B"))
    }

    @Test
    fun `priority sort places unprioritized notes at the default priority`() {
        val a = note("A", priority = "A")
        val c = note("C", priority = "C")
        val none = note("None")
        val sorted = QueryMatcher.filter(
            listOf(c, none, a), QueryParser.parse("o.p"), today, MatchOptions(defaultPriority = "B"),
        ).map { it.title }
        assertEquals(listOf("A", "None", "C"), sorted)
    }

    @Test
    fun `day aliases and no work in queries`() {
        val tomorrowNote = note("Tom", scheduled = "<2025-06-12 Thu>")
        val todayNote = note("Tod", scheduled = "<2025-06-11 Wed>")
        val none = note("None")
        assertEquals(listOf("Tom"), run("s.eq.tom", tomorrowNote, todayNote, none))
        assertEquals(listOf("Tom"), run("s.eq.tmrw", tomorrowNote, todayNote, none))
        assertEquals(listOf("Tod"), run("s.eq.tod", tomorrowNote, todayNote, none))
        assertEquals(listOf("None"), run("s.no", tomorrowNote, todayNote, none))
    }

    @Test
    fun `quoted notebook name with spaces matches`() {
        val n = note("N", fileName = "My Notebook.org")
        val other = note("O", fileName = "My.org")
        assertEquals(listOf("N"), run("b.\"My Notebook\"", n, other))
    }

    // --- P2: moments, h/y units, text in tags, default order ---

    private val tenThirty = LocalDateTime.of(2025, 6, 11, 10, 30)

    private fun runAt(now: LocalDateTime, query: String, vararg notes: NoteMeta): List<String> =
        QueryMatcher.filter(notes.toList(), QueryParser.parse(query), now.toLocalDate(), now = now).map { it.title }

    @Test
    fun `now compares the time of day of timed entries`() {
        val early = note("Early", scheduled = "<2025-06-11 Wed 09:00>")
        val late = note("Late", scheduled = "<2025-06-11 Wed 14:00>")
        assertEquals(listOf("Late"), runAt(tenThirty, "s.ge.now", early, late))
        assertEquals(listOf("Early"), runAt(tenThirty, "s.now", early, late))
        assertEquals(listOf("Early"), runAt(tenThirty, "s.lt.now", early, late))
    }

    @Test
    fun `an untimed entry covers its whole day against now`() {
        val allDay = note("All day", active = "<2025-06-11 Wed>")
        val yesterday = note("Yesterday", active = "<2025-06-10 Tue>")
        assertEquals(listOf("All day"), runAt(tenThirty, "e.ge.now", allDay, yesterday))
        assertEquals(listOf("All day"), runAt(tenThirty, "e.now", allDay, yesterday))
        assertEquals(listOf("All day", "Yesterday"), runAt(tenThirty, "e.le.now", allDay, yesterday))
        val untimedTask = note("Task", scheduled = "<2025-06-11 Wed>")
        assertEquals(listOf("Task"), runAt(tenThirty, "s.le.now", untimedTask))
        assertEquals(listOf("Task"), runAt(tenThirty, "s.ge.now", untimedTask))
    }

    @Test
    fun `an event with an end time is still on while it runs`() {
        val meeting = note("Meeting", active = "<2025-06-11 Wed 10:00-11:00>")
        val over = note("Standup", active = "<2025-06-11 Wed 09:00-09:15>")
        assertEquals(listOf("Meeting"), runAt(tenThirty, "e.ge.now", meeting, over))
    }

    @Test
    fun `hour offsets count from now in both directions`() {
        val soon = note("Soon", deadline = "<2025-06-11 Wed 12:00>")
        val later = note("Later", deadline = "<2025-06-11 Wed 15:00>")
        val past = note("Past", closed = "[2025-06-11 Wed 09:00]")
        // d. defaults to le: due within the next two hours (or earlier).
        assertEquals(listOf("Soon"), runAt(tenThirty, "d.2h", soon, later))
        assertEquals(listOf("Soon", "Later"), runAt(tenThirty, "d.le.5h", soon, later))
        assertEquals(listOf("Past"), runAt(tenThirty, "c.ge.-2h", past))
        assertEquals(emptyList<String>(), runAt(tenThirty, "c.ge.-1h", past))
    }

    @Test
    fun `year offsets`() {
        val nextYear = note("Next year", scheduled = "<2026-03-01 Sun>")
        val far = note("Far", scheduled = "<2027-06-12 Sat>")
        assertEquals(listOf("Next year"), run("s.1y", nextYear, far))
        assertEquals(listOf("Far"), run("s.gt.1y", nextYear, far))
        val old = note("Old", created = "[2023-01-01 Sun]")
        assertEquals(listOf("Old"), run("cr.le.-2y", old))
    }

    @Test
    fun `plain text also matches own tags but not inherited ones`() {
        val tagged = note("Call Bob", tags = listOf("phone"))
        val child = note("Child", tags = emptyList(), inherited = listOf("phone"))
        val plain = note("Nothing")
        assertEquals(listOf("Call Bob"), run("phone", tagged, child, plain))
        assertEquals(listOf("Call Bob"), run("pho", tagged, child, plain))
        assertEquals(listOf("Child", "Nothing"), run(".phone", tagged, child, plain))
    }

    @Test
    fun `filter-only queries order by notebook, priority, then position`() {
        val a = note("A", fileName = "b.org", line = 1)
        val b = note("B", fileName = "a.org", line = 5, priority = "C")
        val c = note("C", fileName = "a.org", line = 9, priority = "A")
        val d = note("D", fileName = "a.org", line = 2)
        val e = note("E", fileName = "a.org", line = 1)
        assertEquals(listOf("C", "B", "E", "D", "A"), run("i.none", a, b, c, d, e))
    }

    @Test
    fun `default order counts unprioritized notes at the default priority`() {
        val none = note("None", line = 1)
        val a = note("A", line = 2, priority = "A")
        val c = note("C", line = 3, priority = "C")
        val sorted = QueryMatcher.filter(
            listOf(c, none, a), QueryParser.parse("i.none"), today, MatchOptions(defaultPriority = "B"),
        ).map { it.title }
        assertEquals(listOf("A", "None", "C"), sorted)
    }

    @Test
    fun `default order sorts by scheduled time when the query filters on s`() {
        val late = note("Late", line = 1, scheduled = "<2025-06-11 Wed 15:00>")
        val early = note("Early", line = 2, scheduled = "<2025-06-10 Tue>")
        val mid = note("Mid", line = 3, scheduled = "<2025-06-11 Wed 09:00>")
        assertEquals(listOf("Early", "Mid", "Late"), run("s.today", late, early, mid))
        // Without s./d. in the query, position decides.
        assertEquals(listOf("Late", "Early", "Mid"), run("i.none", mid, late, early))
    }

    @Test
    fun `default order sorts by deadline time when the query filters on d`() {
        val x = note("X", line = 1, deadline = "<2025-06-13 Fri>")
        val y = note("Y", line = 2, deadline = "<2025-06-12 Thu>")
        assertEquals(listOf("Y", "X"), run("d.1w", x, y))
    }

    @Test
    fun `text queries keep relevance ranking`() {
        val body = note("Other", body = "ramen", line = 1, priority = "A")
        val title = note("Ramen", line = 2)
        assertEquals(listOf("Ramen", "Other"), run("ramen", body, title))
    }

    // --- P3: ad.N window ---

    @Test
    fun `ad N covers N days starting today, overdue included`() {
        val lastDay = note("Last day", scheduled = "<2025-06-17 Tue>")
        val dayAfter = note("Day after", scheduled = "<2025-06-18 Wed>")
        val overdue = note("Overdue", deadline = "<2025-06-01 Sun>")
        assertEquals(listOf("Last day", "Overdue"), run("ad.7", lastDay, dayAfter, overdue))
        assertEquals(listOf("Overdue"), run("ad.1", lastDay, overdue))
    }

    @Test
    fun `ad N includes events in the window but not past ones`() {
        val soon = note("Soon", active = "<2025-06-13 Fri>")
        val running = note("Running", active = "<2025-06-09 Mon>--<2025-06-12 Thu>")
        val past = note("Past", active = "<2025-06-10 Tue>")
        val far = note("Far", active = "<2025-06-30 Mon>")
        assertEquals(listOf("Soon", "Running"), run("ad.3", soon, running, past, far))
    }

    @Test
    fun `ad N includes a repeating event whose next occurrence falls in the window`() {
        // Based on Wed May 28, weekly: recurs on Wed Jun 11 (today) and Jun 18.
        val weekly = note("Weekly", active = "<2025-05-28 Wed +1w>")
        val monthly = note("Monthly", active = "<2025-05-20 Tue +1m>")
        assertEquals(listOf("Weekly"), run("ad.3", weekly, monthly))
    }
}
