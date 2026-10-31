package com.rrajath.grove.search

import com.rrajath.grove.data.NotesFts

/** Whether a facet requires a planning timestamp to be present, absent, or either. */
enum class DatePresence { ANY, PRESENT, ABSENT }

/**
 * The part of the Filters panel that SQL can narrow on, decoupled from the UI's
 * `SearchFilters` so this builder stays testable without an Android runtime.
 * Date *windows* are absent by design: they compare parsed org timestamps, which
 * is Kotlin's job; only the presence of a timestamp reaches SQL.
 */
data class FacetNarrowing(
    val notebook: String? = null,
    val states: Set<String> = emptySet(),
    /** True when the "no state" chip is selected; matches rows with a null keyword. */
    val includeNoState: Boolean = false,
    val priorities: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val scheduled: DatePresence = DatePresence.ANY,
    val deadline: DatePresence = DatePresence.ANY,
    val active: DatePresence = DatePresence.ANY,
    val closed: DatePresence = DatePresence.ANY,
    val created: DatePresence = DatePresence.ANY,
) {
    companion object {
        val NONE = FacetNarrowing()
    }
}

/** A parameterised statement for `IndexDao.notesMatching`. */
data class CandidateSql(val sql: String, val args: List<String>) {
    /** True when nothing could be narrowed and this selects the whole table. */
    val isFullScan: Boolean get() = args.isEmpty() && !sql.contains(" WHERE ")
}

/**
 * Builds the SQL that loads the candidate rows for one search.
 *
 * Like [FtsQuery], this only ever has to return a **superset**: `QueryMatcher`
 * and the ViewModel's own `matchesFilters` still decide every result, so a
 * predicate is pushed down only when it provably cannot exclude a row the
 * Kotlin pass would have kept. Anything ambiguous (date arithmetic over org
 * timestamps, terms whose case folding SQLite and Kotlin disagree on) is left
 * out and handled in Kotlin as before.
 */
object NoteCandidateQuery {

    fun build(
        match: String?,
        query: SearchQuery? = null,
        facets: FacetNarrowing = FacetNarrowing.NONE,
    ): CandidateSql {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (match != null) {
            // Row-value IN against the FTS table's stored keys: notes' primary
            // key is (fileName, lineIndex), so the candidate set resolves
            // through that index rather than a scan.
            val fts = "(fileName, lineIndex) IN " +
                "(SELECT fileName, lineIndex FROM ${NotesFts.TABLE} WHERE ${NotesFts.TABLE} MATCH ?)"
            tagFallback(query)?.let { (tagSql, tagArgs) ->
                conditions += if (tagSql == null) fts else "($fts OR $tagSql)"
                args += match
                args.addAll(tagArgs)
            }
        }

        query?.let { queryConditions(it) }?.let { (sql, queryArgs) ->
            conditions += sql
            args.addAll(queryArgs)
        }

        facetConditions(facets).forEach { (sql, facetArgs) ->
            conditions += sql
            args.addAll(facetArgs)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        return CandidateSql(
            // Deterministic order so results group by file identically run to run.
            sql = "SELECT * FROM notes$where ORDER BY fileName, lineIndex",
            args = args,
        )
    }

    /**
     * Plain text also matches a note's own tags, which the FTS table doesn't
     * index. A matching note has every text term of some group in its title,
     * body or tags, so it is either an FTS hit or has at least one text term
     * in its tags: OR-ing the FTS clause with "tags contain any text term"
     * keeps a superset. Returns the extra clause (null = no text terms), or
     * null overall when a non-ASCII term rules out LIKE and the FTS clause
     * must be dropped entirely.
     */
    private fun tagFallback(query: SearchQuery?): Pair<String?, List<String>>? {
        val terms = query?.groups.orEmpty().flatten()
            .filter { !it.negated }
            .mapNotNull { (it.condition as? Condition.Text)?.term }
            .distinct()
        if (terms.isEmpty()) return null to emptyList()
        val likes = terms.map { term -> term.ifAscii { likeContains(it) } ?: return null }
        return likes.joinToString(" OR ") { "tags LIKE ? ESCAPE '\\'" } to likes
    }

    // --- structured query terms ---

    /**
     * `SearchQuery.groups` is an OR of AND-groups, so this mirrors that shape.
     * A group with nothing pushable matches anything, which would make the whole
     * OR trivially true, so the entire query-level predicate is dropped in that
     * case rather than emitting a condition that narrows nothing.
     */
    private fun queryConditions(query: SearchQuery): Pair<String, List<String>>? {
        if (query.groups.isEmpty()) return null
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (group in query.groups) {
            val (sql, groupArgs) = groupCondition(group) ?: return null
            parts += sql
            args.addAll(groupArgs)
        }
        return parts.joinToString(" OR ", prefix = "(", postfix = ")") to args
    }

    private fun groupCondition(group: List<Term>): Pair<String, List<String>>? {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (term in group) {
            // Negated terms only ever remove rows; skipping them keeps a superset.
            if (term.negated) continue
            val (sql, termArgs) = termCondition(term.condition) ?: continue
            parts += sql
            args.addAll(termArgs)
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(" AND ", prefix = "(", postfix = ")") to args
    }

    // The COLLATE goes on the column, never the bound value: with the value
    // form (`keyword = ? COLLATE NOCASE`), SQLite answered from the column's
    // BINARY index and silently matched case-sensitively (e.g. `i.todo OR
    // i.next` found nothing), though the two forms mean the same thing.
    private fun termCondition(condition: Condition): Pair<String, List<String>>? = when (condition) {
        // Handled by the FTS MATCH expression instead.
        is Condition.Text -> null

        is Condition.State ->
            if (condition.state.equals("none", ignoreCase = true)) "keyword IS NULL" to emptyList()
            else condition.state.ifAscii { "keyword COLLATE NOCASE = ?" to listOf(it) }

        // Done-ness isn't pushed down: "has a keyword" is a superset of both.
        is Condition.StateType ->
            if (condition.type == Condition.StateType.Type.NONE) "keyword IS NULL" to emptyList()
            else "keyword IS NOT NULL" to emptyList()

        // p. also matches an unprioritized note when the default priority
        // equals X, which only the matcher knows, so NULL rows stay candidates.
        is Condition.Priority -> condition.priority.ifAscii {
            if (condition.setOnly) "priority COLLATE NOCASE = ?" to listOf(it)
            else "(priority COLLATE NOCASE = ? OR priority IS NULL)" to listOf(it)
        }

        is Condition.Tag -> {
            val column = if (condition.ownOnly) "tags" else "inheritedTags"
            // QueryMatcher matches a tag as a case-insensitive substring, so the
            // joined column is probed the same way: matching across the ":"
            // separator only ever widens the candidate set.
            condition.tag.ifAscii { "$column LIKE ? ESCAPE '\\'" to listOf(likeContains(it)) }
        }

        is Condition.Notebook -> condition.name.ifAscii { name ->
            // QueryMatcher compares with ".org" stripped from both sides, so a
            // stored file name matches as either form.
            val base = name.removeSuffix(".org")
            "(fileName COLLATE NOCASE = ? OR fileName COLLATE NOCASE = ?)" to listOf(base, "$base.org")
        }

        // A note with no such timestamp can never satisfy a date window, except
        // a "none" period which is exactly the opposite: it requires absence.
        is Condition.Scheduled -> dateCondition("scheduled", condition.period)
        is Condition.Deadline -> dateCondition("deadline", condition.period)
        is Condition.Active -> dateCondition("activeTimestamps", condition.period)
        is Condition.Closed -> dateCondition("closed", condition.period)
        is Condition.Created -> dateCondition("createdAt", condition.period)
    }

    private fun dateCondition(column: String, period: Period): Pair<String, List<String>> =
        if (period.isNoDate) "$column IS NULL" to emptyList()
        else "$column IS NOT NULL" to emptyList()

    // --- filter chips ---

    private fun facetConditions(facets: FacetNarrowing): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, List<String>>>()

        facets.notebook?.let { out += "fileName = ?" to listOf(it) }

        if (facets.states.isNotEmpty() || facets.includeNoState) {
            val parts = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (facets.states.isNotEmpty()) {
                parts += "keyword IN (${placeholders(facets.states.size)})"
                args.addAll(facets.states)
            }
            if (facets.includeNoState) parts += "keyword IS NULL"
            out += parts.joinToString(" OR ", prefix = "(", postfix = ")") to args
        }

        if (facets.priorities.isNotEmpty()) {
            out += "priority IN (${placeholders(facets.priorities.size)})" to facets.priorities.toList()
        }

        if (facets.tags.isNotEmpty()) {
            // The chips match a whole tag, not a substring, so the ":"-joined
            // column is bracketed and probed with instr(): byte-exact, which is
            // exactly the Kotlin comparison.
            val parts = facets.tags.map { "instr(':' || inheritedTags || ':', ?) > 0" }
            out += parts.joinToString(" OR ", prefix = "(", postfix = ")") to facets.tags.map { ":$it:" }
        }

        // A stored timestamp is always one this app formatted, so it always
        // reparses: "column IS NULL" and "has no date" are the same set.
        presenceCondition("scheduled", facets.scheduled)?.let { out += it to emptyList() }
        presenceCondition("deadline", facets.deadline)?.let { out += it to emptyList() }
        presenceCondition("activeTimestamps", facets.active)?.let { out += it to emptyList() }
        presenceCondition("closed", facets.closed)?.let { out += it to emptyList() }
        presenceCondition("createdAt", facets.created)?.let { out += it to emptyList() }

        return out
    }

    private fun presenceCondition(column: String, presence: DatePresence): String? = when (presence) {
        DatePresence.ANY -> null
        DatePresence.PRESENT -> "$column IS NOT NULL"
        DatePresence.ABSENT -> "$column IS NULL"
    }

    // --- helpers ---

    /**
     * SQLite's `NOCASE` collation and `LIKE` only fold ASCII, while Kotlin's
     * `ignoreCase` folds the full Unicode range, so for a non-ASCII operand SQL
     * could exclude a row Kotlin would have matched. Those terms are simply not
     * pushed down.
     */
    private inline fun <T> String.ifAscii(build: (String) -> T): T? =
        if (all { it.code < 128 }) build(this) else null

    private fun likeContains(value: String): String =
        "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(", ")
}
