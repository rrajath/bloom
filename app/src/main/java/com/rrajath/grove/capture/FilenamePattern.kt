package com.rrajath.grove.capture

import com.rrajath.grove.R
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.ui.util.UiText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Computes a Roam node's target filename from its pattern, and reads a draft's live title. */
object FilenamePattern {

    private val STRFTIME_BLOCK = Regex("""%<([^>]*)>""")
    private val STRFTIME_TOKEN = Regex("%[YmdHMS]")
    private val SLUG_TOKEN = "%(slug)"
    private val HEADLINE_START = Regex("""^\*+\s""")

    // A filename pattern is a single stem, not a path — forward slash is illegal
    // here (unlike FilenameValidation, which treats it as a path separator).
    private val ILLEGAL_LITERAL_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /** Expands `%<...>` strftime blocks (Y/m/d/H/M/S only) and `%(slug)` in [pattern]. */
    fun expand(pattern: String, now: LocalDateTime, slug: String): String =
        STRFTIME_BLOCK.replace(pattern) { expandStrftime(it.groupValues[1], now) }
            .replace(SLUG_TOKEN, slug)

    /** [toDateRegex]'s built regex plus which capturing group holds each date part —
     *  computed together so [parse] doesn't need `Matcher`'s by-name group lookup,
     *  which requires API 26 while this app's minSdk is 23. Build once via
     *  [compileDatePattern] and reuse it across many file names. */
    class DateRegex internal constructor(
        val regex: Regex,
        private val yearGroup: Int,
        private val monthGroup: Int,
        private val dayGroup: Int,
    ) {
        /** [fileName] parsed against this pattern, or `null` if it doesn't match
         *  or the matched digits aren't a real calendar date. */
        fun parse(fileName: String): LocalDate? {
            val match = regex.find(fileName) ?: return null
            val y = match.groupValues.getOrNull(yearGroup)?.toIntOrNull() ?: return null
            val m = match.groupValues.getOrNull(monthGroup)?.toIntOrNull() ?: return null
            val d = match.groupValues.getOrNull(dayGroup)?.toIntOrNull() ?: return null
            return runCatching { LocalDate.of(y, m, d) }.getOrNull()
        }
    }

    /** [pattern] compiled into a reusable [DateRegex], or `null` when it can't be
     *  reverse-parsed (see [toDateRegex]). */
    fun compileDatePattern(pattern: String): DateRegex? = buildDateRegex(pattern)

    private fun buildDateRegex(pattern: String): DateRegex? {
        if (pattern.contains(SLUG_TOKEN)) return null
        var groupIndex = 0
        var yearGroup = -1
        var monthGroup = -1
        var dayGroup = -1
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val block = STRFTIME_BLOCK.find(pattern, i)
            if (block == null || block.range.first != i) {
                sb.append(Regex.escape(pattern[i].toString()))
                i++
                continue
            }
            val spec = block.groupValues[1]
            var j = 0
            while (j < spec.length) {
                val token = STRFTIME_TOKEN.find(spec, j)
                if (token == null || token.range.first != j) {
                    sb.append(Regex.escape(spec[j].toString()))
                    j++
                    continue
                }
                when (token.value) {
                    "%Y" -> { sb.append("(\\d{4})"); yearGroup = ++groupIndex }
                    "%m" -> { sb.append("(\\d{2})"); monthGroup = ++groupIndex }
                    "%d" -> { sb.append("(\\d{2})"); dayGroup = ++groupIndex }
                    "%H" -> sb.append("\\d{2}")
                    "%M" -> sb.append("\\d{2}")
                    "%S" -> sb.append("\\d{2}")
                }
                j += token.value.length
            }
            i = block.range.last + 1
        }
        if (yearGroup < 0 || monthGroup < 0 || dayGroup < 0) return null
        return DateRegex(Regex("^$sb$"), yearGroup, monthGroup, dayGroup)
    }

    /**
     * A regex matching file names [expand] would produce for *some* date — or `null`
     * when [pattern] doesn't carry a full year+month+day date (only those patterns
     * can be reverse-parsed into a [LocalDate]; a date-only-by-month pattern,
     * for instance, can't distinguish which day a file belongs to).
     */
    fun toDateRegex(pattern: String): Regex? = buildDateRegex(pattern)?.regex

    /** [fileName] parsed against [pattern] via [toDateRegex], or `null` if it doesn't match
     *  or the matched digits aren't a real calendar date. */
    fun parseDate(fileName: String, pattern: String): LocalDate? =
        buildDateRegex(pattern)?.parse(fileName)

    /** Formatter per strftime spec: Dailies resolves the same pattern on every
     *  date switch and prefetch, so build each one once. */
    private val FORMATTERS = java.util.concurrent.ConcurrentHashMap<String, DateTimeFormatter>()

    private fun expandStrftime(spec: String, now: LocalDateTime): String =
        now.format(FORMATTERS.getOrPut(spec) { DateTimeFormatter.ofPattern(toJavaPattern(spec)) })

    private fun toJavaPattern(spec: String): String =
        STRFTIME_TOKEN.replace(spec) { m ->
            when (m.value) {
                "%Y" -> "yyyy"
                "%m" -> "MM"
                "%d" -> "dd"
                "%H" -> "HH"
                "%M" -> "mm"
                "%S" -> "ss"
                else -> m.value
            }
        }

    /** Trims, lowercases, and collapses whitespace runs to `_`; no char-stripping. Blank title → blank slug. */
    fun slugFromTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.lowercase().replace(Regex("""\s+"""), "_")
    }

    /**
     * The live `#+title:` value from preamble lines (before the first headline) in a
     * draft. Case-insensitive keyword, same scoping as [OrgParser]'s preamble scan.
     * Blank/missing title → null.
     */
    fun titleFromDraft(text: String): String? {
        for (line in text.split("\n")) {
            if (HEADLINE_START.find(line) != null) break
            val match = OrgParser.PREAMBLE_KEYWORD.matchEntire(line.trim()) ?: continue
            if (match.groupValues[1].equals("title", ignoreCase = true)) {
                return match.groupValues[2].trim().ifEmpty { null }
            }
        }
        return null
    }

    /**
     * `null` when [pattern] is a valid file name pattern: strips recognized
     * `%<...>` strftime blocks and `%(slug)`, then validates the remaining
     * literal text contains no filesystem-illegal characters; otherwise a
     * user-facing reason. A blank (or all-whitespace) pattern is rejected.
     */
    fun errorFor(pattern: String): UiText? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return UiText.res(R.string.filename_error_enter_pattern)

        val literal = STRFTIME_BLOCK.replace(trimmed, "").replace(SLUG_TOKEN, "")
        if (ILLEGAL_LITERAL_CHARS.containsMatchIn(literal)) return UiText.res(R.string.filename_error_pattern_illegal_chars)
        return null
    }
}
