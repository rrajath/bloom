package com.rrajath.grove.search

import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.occursBetween
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Index-row view the matcher operates on (kept android/Room-free). */
data class NoteMeta(
    val fileName: String,
    val lineIndex: Int,
    val title: String,
    val keyword: String?,
    val isDoneKeyword: Boolean,
    val priority: String?,
    val tags: List<String>,
    val inheritedTags: List<String>,
    val scheduled: String?,
    val deadline: String?,
    val closed: String?,
    /** Space-joined bare active timestamps in the note's own body (see NoteEntity). */
    val active: String? = null,
    val createdAt: String?,
    val lastModified: Long,
    /** Heading + body text for plain-term matching (own [tags] match too). */
    val searchText: String,
) {
    // Parsed once per instance; matching, sorting, and the agenda view would
    // otherwise re-run the timestamp regex per comparison / per agenda day.
    val scheduledTs by tsOf(scheduled)
    val deadlineTs by tsOf(deadline)
    val closedTs by tsOf(closed)
    val createdTs by tsOf(createdAt)

    val scheduledDate: LocalDate? get() = scheduledTs?.date
    val deadlineDate: LocalDate? get() = deadlineTs?.date
    val closedDate: LocalDate? get() = closedTs?.date
    val createdDate: LocalDate? get() = createdTs?.date
    val scheduledTime: LocalTime? get() = scheduledTs?.time
    val deadlineTime: LocalTime? get() = deadlineTs?.time
    val closedTime: LocalTime? get() = closedTs?.time
    val createdTime: LocalTime? get() = createdTs?.time

    /** Every parsed bare active timestamp in the note's body. */
    val activeTimestamps: List<OrgTimestamp> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        active?.let { OrgTimestamp.parseAll(it).filter { ts -> ts.active } } ?: emptyList()
    }

    /**
     * Every calendar day a bare active timestamp puts this note on: a single
     * stamp is one day, a ranged stamp is every day from start to end inclusive.
     */
    val activeDates: List<LocalDate> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        activeTimestamps.flatMap { ts ->
            val end = ts.rangeEnd ?: ts.date
            generateSequence(ts.date) { d -> d.plusDays(1).takeIf { !it.isAfter(end) } }.toList()
        }
    }

    private fun tsOf(raw: String?) = lazy(LazyThreadSafetyMode.PUBLICATION) {
        raw?.let { OrgTimestamp.parse(it) }
    }
}

/**
 * Settings that change what a query means.
 * [stateOrder]: the configured keyword sequence (active then done), for `o.state`.
 * [defaultPriority]: org's default priority; `p.X` and `o.p` treat a note
 * with no priority as having it (null = no default).
 */
data class MatchOptions(
    val stateOrder: List<String> = emptyList(),
    val defaultPriority: String? = null,
)

object QueryMatcher {

    /**
     * [now] is what `now` and `Nh` count from; every other date token only
     * looks at [today]. Production passes the real clock; the default (start
     * of [today]) keeps tests that never use a moment token deterministic.
     */
    fun matches(
        note: NoteMeta,
        query: SearchQuery,
        today: LocalDate,
        options: MatchOptions = MatchOptions(),
        now: LocalDateTime = today.atStartOfDay(),
    ): Boolean {
        if (!matchesAgendaWindow(note, query, today)) return false
        val expr = query.expr ?: return true
        return expr.matches { term -> matchesTerm(note, term, today, now, options) }
    }

    /**
     * The AND-groups [note] satisfies (empty when it doesn't match at all). A
     * query with no groups counts as one empty group, so a filter-only search
     * still reports why each note matched. Search uses this to decide which of
     * a note's lines to show as snippets. A query too large to flatten
     * ([SearchQuery.isFlatteningSkipped]) also reports one empty group on a
     * match, so its notes show as headings without text snippets.
     */
    fun satisfiedGroups(
        note: NoteMeta,
        query: SearchQuery,
        today: LocalDate,
        options: MatchOptions = MatchOptions(),
        now: LocalDateTime = today.atStartOfDay(),
    ): List<List<Term>> {
        if (!matchesAgendaWindow(note, query, today)) return emptyList()
        if (query.expr == null) return listOf(emptyList())
        if (query.isFlatteningSkipped) {
            return if (matches(note, query, today, options, now)) listOf(emptyList()) else emptyList()
        }
        return query.groups.filter { group -> group.all { term -> matchesTerm(note, term, today, now, options) } }
    }

    /**
     * `ad.N` (PRD §5.5, Orgzly's agenda): besides switching Search to its
     * day-grouped view, it narrows to notes with any time set in the N days
     * starting today ([agendaHorizon]): a SCHEDULED or DEADLINE on or before
     * the last day (overdue included, as the agenda's Overdue section), or an
     * event (bare active timestamp) covering one of those days. Applied
     * independently of [SearchQuery.groups] so it still filters when `ad.N`
     * is the only token in the query.
     */
    private fun matchesAgendaWindow(note: NoteMeta, query: SearchQuery, today: LocalDate): Boolean {
        val days = query.agendaDays ?: return true
        val horizon = agendaHorizon(today, days)
        if (note.scheduledDate?.isAfter(horizon) == false) return true
        if (note.deadlineDate?.isAfter(horizon) == false) return true
        return note.activeTimestamps.any { it.occursBetween(today, horizon) }
    }

    /** The last day `ad.[days]` covers: `ad.1` is today only (`ad.0` and below count as 1). */
    fun agendaHorizon(today: LocalDate, days: Int): LocalDate =
        today.plusDays((days.coerceAtLeast(1) - 1).toLong())

    fun filter(
        notes: List<NoteMeta>,
        query: SearchQuery,
        today: LocalDate,
        options: MatchOptions = MatchOptions(),
        now: LocalDateTime = today.atStartOfDay(),
    ): List<NoteMeta> = sort(notes.filter { matches(it, query, today, options, now) }, query, options)

    private fun matchesTerm(
        note: NoteMeta,
        term: Term,
        today: LocalDate,
        now: LocalDateTime,
        options: MatchOptions,
    ): Boolean {
        val result = when (val c = term.condition) {
            // Orgzly: title, content or any of the note's own tags (not inherited ones).
            is Condition.Text ->
                note.searchText.contains(c.term, ignoreCase = true) ||
                    note.tags.any { it.contains(c.term, ignoreCase = true) }

            is Condition.State ->
                if (c.state.equals("none", true)) note.keyword == null
                else note.keyword?.equals(c.state, ignoreCase = true) == true

            is Condition.StateType -> when (c.type) {
                Condition.StateType.Type.TODO -> note.keyword != null && !note.isDoneKeyword
                Condition.StateType.Type.DONE -> note.keyword != null && note.isDoneKeyword
                Condition.StateType.Type.NONE -> note.keyword == null
            }

            is Condition.Notebook ->
                note.fileName.removeSuffix(".org").equals(c.name.removeSuffix(".org"), true)

            is Condition.Tag -> {
                val pool = if (c.ownOnly) note.tags else note.inheritedTags
                pool.any { it.contains(c.tag, ignoreCase = true) }
            }

            is Condition.Priority -> {
                val effective = if (c.setOnly) note.priority else note.priority ?: options.defaultPriority
                effective?.equals(c.priority, ignoreCase = true) == true
            }

            // Orgzly's default operators when none is written: le for s./d./cr., eq for c./a.
            is Condition.Scheduled -> matchesDate(note.scheduledTs, c.period, today, now, CompareOp.LE)
            is Condition.Deadline -> matchesDate(note.deadlineTs, c.period, today, now, CompareOp.LE)
            is Condition.Active -> anyActiveMatches(note, c.period, today, now)
            is Condition.Closed -> matchesDate(note.closedTs, c.period, today, now, CompareOp.EQ)
            is Condition.Created -> matchesDate(note.createdTs, c.period, today, now, CompareOp.LE)
        }
        return result != term.negated
    }

    /**
     * [ts] compared against what [period] names, with its own op or
     * [defaultOp]: its date against a day token, its [span] against a moment
     * token (`now`, `Nh`). The op-less specials: `none` requires no
     * timestamp, `overdue` requires one dated strictly before today. A token
     * naming nothing matches nothing.
     */
    private fun matchesDate(
        ts: OrgTimestamp?,
        period: Period,
        today: LocalDate,
        now: LocalDateTime,
        defaultOp: CompareOp,
    ): Boolean {
        if (period.isNoDate) return ts == null
        if (ts == null) return false
        if (period.isOverdue) return ts.date.isBefore(today)
        val op = period.op ?: defaultOp
        period.targetMoment(now)?.let { return op.test(ts.span(), it) }
        val target = period.target(today) ?: return false
        return op.test(ts.date, target)
    }

    /** a./e.: any bare active timestamp day satisfying the comparison (default
     *  `eq`), a ranged event contributing every day it spans via
     *  [NoteMeta.activeDates]. `a.overdue` matches an event that has fully
     *  passed (its last day is before today) on purpose: search is explicit,
     *  only the agenda hides overdue events. */
    private fun anyActiveMatches(note: NoteMeta, period: Period, today: LocalDate, now: LocalDateTime): Boolean {
        val dates = note.activeDates
        if (period.isNoDate) return dates.isEmpty()
        if (period.isOverdue) {
            return note.activeTimestamps.any { (it.rangeEnd ?: it.date).isBefore(today) }
        }
        val op = period.op ?: CompareOp.EQ
        period.targetMoment(now)?.let { moment ->
            return note.activeTimestamps.any { op.test(it.span(), moment) }
        }
        val target = period.target(today) ?: return false
        return dates.any { op.test(it, target) }
    }

    /**
     * The time a timestamp covers, for comparing against a moment. A timed
     * stamp is one instant ([Span.end] null) unless it has an end time; an
     * untimed one covers its whole day, so `e.ge.now` still shows today's
     * all-day events and `s.le.now` today's untimed tasks. A date range runs
     * to its end time, or to the end of its last day.
     */
    private fun OrgTimestamp.span(): Span {
        val start = date.atTime(time ?: LocalTime.MIN)
        val lastDay = rangeEnd ?: date
        val end = when {
            endTime != null -> lastDay.atTime(endTime)
            time != null && rangeEnd == null -> null
            else -> lastDay.plusDays(1).atStartOfDay()
        }
        return Span(start, end)
    }

    // --- ranking ---

    /**
     * `o.PROP` sorts when present (Orgzly's property set, `.o.PROP` reversed);
     * otherwise a query with text uses PRD §11 ranking: exact title > title
     * contains > body (or tag) match, recency as tiebreaker. A filter-only
     * query uses Orgzly's default order (see [defaultOrder]). Notes missing
     * the sorted property go last in either direction. `o.state` follows
     * [MatchOptions.stateOrder]; an unconfigured keyword sorts after every
     * configured one. `o.p` counts a note with no priority as
     * [MatchOptions.defaultPriority].
     */
    fun sort(notes: List<NoteMeta>, query: SearchQuery, options: MatchOptions = MatchOptions()): List<NoteMeta> {
        if (query.sortBy.isNotEmpty()) {
            val comparator = query.sortBy
                .map { comparatorFor(it, options) }
                .reduce { acc, next -> acc.then(next) }
            return notes.sortedWith(comparator)
        }
        val terms = query.textTerms
        if (terms.isEmpty()) return notes.sortedWith(defaultOrder(query, options))
        return notes.sortedWith(
            compareBy<NoteMeta> { note ->
                when {
                    terms.any { note.title.equals(it, true) } -> 0
                    terms.any { note.title.contains(it, true) } -> 1
                    else -> 2
                }
            }.thenByDescending { it.lastModified }
        )
    }

    /**
     * Orgzly's order without `o.`: notebook, then priority, then scheduled
     * and deadline time when the query filters on `s.`/`d.`, then position in
     * the notebook.
     */
    private fun defaultOrder(query: SearchQuery, options: MatchOptions): Comparator<NoteMeta> {
        val conditions = query.expr?.leaves().orEmpty().filter { !it.negated }.map { it.condition }
        var order = compareBy<NoteMeta>({ it.fileName.lowercase() }, { it.fileName })
            .then(comparatorFor(SortKey(SortField.PRIORITY), options))
        if (conditions.any { it is Condition.Scheduled }) {
            order = order.then(comparatorFor(SortKey(SortField.SCHEDULED), options))
        }
        if (conditions.any { it is Condition.Deadline }) {
            order = order.then(comparatorFor(SortKey(SortField.DEADLINE), options))
        }
        return order.thenBy { it.lineIndex }
    }

    private fun comparatorFor(key: SortKey, options: MatchOptions): Comparator<NoteMeta> {
        val stateOrder = options.stateOrder
        val desc = key.descending
        return when (key.field) {
            SortField.NOTEBOOK -> nullsLast(desc) { it.fileName.lowercase() }
            SortField.TITLE -> nullsLast(desc) { it.title.lowercase() }
            SortField.SCHEDULED -> nullsLast(desc) { it.scheduledDate?.atTime(it.scheduledTime ?: LocalTime.MIN) }
            SortField.DEADLINE -> nullsLast(desc) { it.deadlineDate?.atTime(it.deadlineTime ?: LocalTime.MIN) }
            SortField.CLOSED -> nullsLast(desc) { it.closedDate?.atTime(it.closedTime ?: LocalTime.MIN) }
            SortField.CREATED -> nullsLast(desc) { it.createdDate?.atTime(it.createdTime ?: LocalTime.MIN) }
            // Orgzly: ascending uses a note's oldest event, descending its most recent.
            SortField.EVENT -> nullsLast(desc) { note ->
                val times = note.activeTimestamps.map { it.date.atTime(it.time ?: LocalTime.MIN) }
                if (desc) times.maxOrNull() else times.minOrNull()
            }
            SortField.PRIORITY -> nullsLast(desc) { (it.priority ?: options.defaultPriority)?.uppercase() }
            SortField.STATE -> nullsLast(desc) { note ->
                note.keyword?.let { keyword ->
                    stateOrder.indexOfFirst { it.equals(keyword, ignoreCase = true) }
                        .takeIf { it >= 0 } ?: stateOrder.size
                }
            }
        }
    }

    private fun <T : Comparable<T>> nullsLast(descending: Boolean, selector: (NoteMeta) -> T?): Comparator<NoteMeta> =
        Comparator { a, b ->
            val x = selector(a)
            val y = selector(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                descending -> y.compareTo(x)
                else -> x.compareTo(y)
            }
        }

}
