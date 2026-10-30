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
    fun windows(line: String, terms: List<String>, radius: Int = CONTEXT_WORDS): List<String> {
        val words = WORD.findAll(line).map { it.range }.toList()
        if (words.isEmpty()) return emptyList()
        val matchWords = wordIndices(words, highlightRanges(line, terms).map { it.first }.sorted())
        val out = mutableListOf<String>()
        var windowEnd = -1
        for (w in matchWords) {
            if (w <= windowEnd) continue
            val from = (w - radius).coerceAtLeast(0)
            windowEnd = (w + radius).coerceAtMost(words.lastIndex)
            val prefix = if (from > 0) "…" else ""
            val suffix = if (windowEnd < words.lastIndex) "…" else ""
            out += prefix + line.substring(words[from].first, words[windowEnd].last + 1) + suffix
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
