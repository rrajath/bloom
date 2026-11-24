package com.rrajath.grove.org

/**
 * What a heading tag may be: org's `[[:alnum:]_@#%]` in any script, plus `-`.
 * Same charset as [OrgParser]'s TAGS_AT_END, so a tag that passes [error] is
 * read back as a tag once it's written to a headline.
 */
object OrgTags {
    private val TAG = Regex("""[\p{L}\p{M}\p{N}_@#%-]+""")

    /** Why a tag was rejected. The UI turns it into text (`ui/util/OrgTagText.kt`). */
    enum class Problem { EMPTY, WHITESPACE, COLON, CHARSET }

    /** Why [raw] (trimmed) can't be a tag, or null when it can. */
    fun error(raw: String): Problem? {
        val v = raw.trim()
        return when {
            v.isEmpty() -> Problem.EMPTY
            v.any { it.isWhitespace() } -> Problem.WHITESPACE
            ':' in v -> Problem.COLON
            !TAG.matches(v) -> Problem.CHARSET
            else -> null
        }
    }
}
