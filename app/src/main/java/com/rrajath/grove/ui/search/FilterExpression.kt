package com.rrajath.grove.ui.search

/**
 * [SearchFilters] mirrored into query text, pulled out of [SearchViewModel] so
 * the Quick Start searches (see [QuickStarts]) can name the exact query a
 * filter-driven card runs. Pure, so it stays testable on the JVM.
 */
internal object FilterExpression {

    /**
     * The filter-panel selection expressed as query tokens, in the same
     * t./i./b./p. syntax the field itself parses (see `SearchViewModel.lastFilterExpr`).
     *
     * Included values within one facet OR together (per [SearchFilters]'s own
     * doc comment), but the query grammar has no parens, so a facet with 2+
     * selected values is cross-multiplied against every other such facet into
     * separate OR-groups (e.g. tags={work,home} states={TODO,NEXT} becomes
     * "t.work i.TODO OR t.work i.NEXT OR t.home i.TODO OR t.home i.NEXT")
     * rather than space-joining everything, which would wrongly AND them and
     * make a multi-value facet unsatisfiable once mirrored back through
     * QueryMatcher.
     */
    fun of(f: SearchFilters): String {
        // AND-only tokens: negations are independent NOT conditions, and each
        // date facet holds at most one preset, so neither needs an OR group.
        val fixed = mutableListOf<String>()
        f.excludedTags.sorted().forEach { fixed += ".t.$it" }
        f.excludedStates.sorted().forEach { fixed += ".i.${stateToken(it)}" }
        f.excludedPriorities.sorted().forEach { fixed += ".p.$it" }
        f.excludedNotebooks.sorted().forEach { fixed += ".b.${it.removeSuffix(".org")}" }
        datePresetToken("s", f.scheduled, f.scheduledRange)?.let { fixed += it }
        datePresetToken("d", f.deadline, f.deadlineRange)?.let { fixed += it }
        datePresetToken("a", f.active, f.activeRange)?.let { fixed += it }
        datePresetToken("c", f.closed, f.closedRange)?.let { fixed += it }
        datePresetToken("cr", f.created, f.createdRange)?.let { fixed += it }

        val orGroups = listOfNotNull(
            f.tags.sorted().map { "t.$it" }.takeIf { it.isNotEmpty() },
            f.states.sorted().map { "i.${stateToken(it)}" }.takeIf { it.isNotEmpty() },
            f.priorities.sorted().map { "p.$it" }.takeIf { it.isNotEmpty() },
            f.notebooks.sorted().map { "b.${it.removeSuffix(".org")}" }.takeIf { it.isNotEmpty() },
        )
        if (orGroups.isEmpty()) return fixed.joinToString(" ")

        val combinations = orGroups.fold(listOf(emptyList<String>())) { acc, group ->
            acc.flatMap { combo -> group.map { combo + it } }
        }
        return combinations.joinToString(" OR ") { combo -> (fixed + combo).joinToString(" ") }
    }

    /**
     * The mirrored token(s) for one date facet. They are parsed and ANDed with
     * `SearchViewModel.matchesFilters` like anything typed, so each must match at least what
     * the preset does: explicit ops pin that down regardless of the prefix's
     * default op (`s.today` alone would also take overdue items).
     */
    private fun datePresetToken(prefix: String, preset: DatePreset, range: DateRange?): String? = when (preset) {
        DatePreset.ANY -> null
        DatePreset.TODAY -> "$prefix.eq.today"
        DatePreset.NEXT_7_DAYS -> "$prefix.ge.today $prefix.le.7d"
        DatePreset.OVERDUE -> "$prefix.overdue"
        DatePreset.NO_DATE -> "$prefix.none"
        DatePreset.CUSTOM -> range?.let { "$prefix.ge.${it.start} $prefix.le.${it.end}" }
    }

    private fun stateToken(state: String) = if (state == NO_STATE) "none" else state.lowercase()
}
