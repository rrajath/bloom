package com.rrajath.grove.search

/**
 * Builds the FTS5 `MATCH` expression that narrows a [SearchQuery] down to
 * candidate rows.
 *
 * The index never decides a match: [QueryMatcher] still runs over whatever
 * comes back, so narrowing only has to return a *superset* of the real results.
 * Everything here is therefore deliberately conservative: anything that cannot
 * be expressed as a guaranteed superset is dropped from the expression, and if
 * even one AND-group cannot be narrowed at all, [matchExpression] returns null
 * and the caller falls back to scanning every row.
 */
object FtsQuery {

    /**
     * The trigram tokenizer indexes three-character sequences, so a fragment
     * shorter than this has no trigram to look up and cannot be searched for.
     * Such terms are left entirely to [QueryMatcher].
     */
    const val MIN_TERM_CHARS = 3

    /**
     * The MATCH expression for [query], or null when the query does not qualify
     * for narrowing.
     *
     * `SearchQuery.groups` is an OR of AND-groups, and a group with no usable
     * text term can match rows that contain none of the query's text at all
     * (a facet-only group like `i.TODO`, or one whose only text is a two-letter
     * word). Narrowing on such a query would silently drop valid results, so
     * *every* group must contribute at least one term of [MIN_TERM_CHARS] or
     * the whole query falls back to the full scan.
     */
    fun matchExpression(query: SearchQuery): String? {
        if (query.groups.isEmpty()) return null
        val groups = query.groups.map { group ->
            group.asSequence()
                // Negated terms are excluded on purpose: they only ever remove
                // rows, so ignoring them keeps the candidate set a superset.
                .filter { !it.negated }
                .mapNotNull { (it.condition as? Condition.Text)?.term }
                .filter { it.isSearchable() }
                .distinct()
                .toList()
                .ifEmpty { return null }
        }
        return groups.joinToString(" OR ") { group ->
            group.joinToString(separator = " AND ", prefix = "(", postfix = ")") { quote(it) }
        }
    }

    /** Long enough for the trigram tokenizer, counted in code points (CJK and emoji included). */
    private fun String.isSearchable(): Boolean = codePointCount(0, length) >= MIN_TERM_CHARS

    /**
     * A MATCH expression for notes whose `body` contains [text] as a substring,
     * case-insensitively (the tokenizer folds Unicode case, not just ASCII).
     * Null when [text] is too short for the trigram index ([MIN_TERM_CHARS]).
     */
    fun bodyContains(text: String): String? = if (text.isSearchable()) "body : ${quote(text)}" else null

    /**
     * Wraps a term as an FTS5 string literal so that operators the user typed
     * (`AND`, `*`, `^`, `-`, quotes, parentheses) are matched as text instead of
     * being parsed as query syntax. Against the trigram tokenizer a quoted
     * string behaves as a plain substring probe, which is exactly the semantics
     * `QueryMatcher` applies.
     */
    private fun quote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""
}
