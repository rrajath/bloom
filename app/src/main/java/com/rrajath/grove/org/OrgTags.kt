package com.rrajath.grove.org

/**
 * What a heading tag may be: org's `[[:alnum:]_@#%]` in any script, plus `-`.
 * Same charset as [OrgParser]'s TAGS_AT_END, so a tag that passes [error] is
 * read back as a tag once it's written to a headline.
 */
object OrgTags {
    private val TAG = Regex("""[\p{L}\p{M}\p{N}_@#%-]+""")

    /** Why [raw] (trimmed) can't be a tag, or null when it can. */
    fun error(raw: String): String? {
        val v = raw.trim()
        return when {
            v.isEmpty() -> "Enter a tag."
            v.any { it.isWhitespace() } -> "Tags can't contain spaces."
            ':' in v -> "Tags can't contain colons."
            !TAG.matches(v) -> "Use letters, numbers, _ @ # % or -."
            else -> null
        }
    }
}
