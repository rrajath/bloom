package com.rrajath.grove.ui.search

/** At or above this many notebooks (or tags), the filter sheet swaps the
 *  "Load 10 more" chip list for a type-to-pick field. */
internal const val TYPEAHEAD_THRESHOLD = 40

internal fun useFacetTypeahead(poolSize: Int): Boolean = poolSize >= TYPEAHEAD_THRESHOLD

/**
 * Typeahead suggestions for [query] over [pool]: names starting with the query
 * first, then names containing it elsewhere, each group in [pool] order (already
 * sorted A-Z). Case-insensitive; a blank query suggests nothing; anything in
 * [exclude] (chips already on screen) is left out.
 */
internal fun facetSuggestions(
    pool: List<String>,
    query: String,
    exclude: Set<String>,
    limit: Int = 50,
): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val prefix = ArrayList<String>()
    val inner = ArrayList<String>()
    for (name in pool) {
        if (name in exclude) continue
        when {
            name.startsWith(q, ignoreCase = true) -> prefix += name
            name.contains(q, ignoreCase = true) -> inner += name
        }
    }
    return (prefix + inner).take(limit)
}

/** Chips shown in typeahead mode: everything picked this sheet session, in pick
 *  order (neutral ones included), plus any [active] filter not picked here yet. */
internal fun shownFacetChips(picked: List<String>, active: Set<String>): List<String> =
    picked + active.filter { it !in picked }.sorted()
