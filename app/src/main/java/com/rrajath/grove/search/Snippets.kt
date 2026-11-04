package com.rrajath.grove.search

/** Builds the match-in-context snippets a full-text search result shows. */
object Snippets {

    /** Words of context kept on each side of a match. */
    const val CONTEXT_WORDS = 10

    private val WORD = Regex("""\S+""")

    /**
     * One snippet per cluster of matches in [line]: each match gets [radius]
     * words either side, and a later match that falls inside the current
     * window is shown there rather than opening a snippet of its own. Words are
     * whitespace-separated; the original spacing inside a window is kept, and
     * `…` marks a cut at either end.
     */
    fun windows(line: String, terms: List<String>, radius: Int = CONTEXT_WORDS): List<String> =
        windowRanges(line, terms, radius).map { it.text(line) }

    /**
     * One cluster window of a line: [range] of the source line it shows, and
     * whether it was cut at either end (shown as `…`).
     */
    data class Window(val range: IntRange, val cutStart: Boolean, val cutEnd: Boolean) {
        /** Chars the leading `…` adds before [range]'s text. */
        val offset: Int get() = if (cutStart) 1 else 0

        fun text(line: String): String =
            (if (cutStart) "…" else "") + line.substring(range.first, range.last + 1) + (if (cutEnd) "…" else "")
    }

    /**
     * [windows] as source ranges. A window never cuts through one of the
     * [atomic] ranges (attachment chips): it widens to take the whole range.
     */
    fun windowRanges(
        line: String,
        terms: List<String>,
        radius: Int = CONTEXT_WORDS,
        atomic: List<IntRange> = emptyList(),
    ): List<Window> {
        val words = WORD.findAll(line).map { it.range }.toList()
        if (words.isEmpty()) return emptyList()
        val matchWords = wordIndices(words, highlightRanges(line, terms).map { it.first }.sorted())
        val out = mutableListOf<Window>()
        var windowEnd = -1
        for (w in matchWords) {
            if (w <= windowEnd) continue
            var from = (w - radius).coerceAtLeast(0)
            windowEnd = (w + radius).coerceAtMost(words.lastIndex)
            for (a in atomic) {
                if (a.first < words[from].first && a.last >= words[from].first) {
                    from = words.indexOfFirst { it.last >= a.first }
                }
                if (a.last > words[windowEnd].last && a.first <= words[windowEnd].last) {
                    windowEnd = words.indexOfLast { it.first <= a.last }
                }
            }
            out += Window(words[from].first..words[windowEnd].last, from > 0, windowEnd < words.lastIndex)
        }
        return out
    }

    /**
     * The index of the word holding each of the ascending [starts], distinct
     * and ascending, in one forward pass. A start on whitespace (no word holds
     * it) maps to word 0.
     */
    private fun wordIndices(words: List<IntRange>, starts: List<Int>): List<Int> {
        val out = sortedSetOf<Int>()
        var w = 0
        for (start in starts) {
            while (w < words.size && words[w].last < start) w++
            out += if (w < words.size && words[w].first <= start) w else 0
        }
        return out.toList()
    }

    /** Every occurrence of every term within [text], case-insensitive. */
    fun highlightRanges(text: String, terms: List<String>): List<IntRange> = terms.flatMap { findRanges(text, it) }

    private fun findRanges(text: String, term: String): List<IntRange> {
        if (term.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var idx = 0
        while (true) {
            val found = text.indexOf(term, idx, ignoreCase = true)
            if (found < 0) break
            ranges += found until (found + term.length)
            idx = found + term.length
        }
        return ranges
    }
}
