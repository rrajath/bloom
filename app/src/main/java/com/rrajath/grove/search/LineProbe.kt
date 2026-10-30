package com.rrajath.grove.search

/**
 * A cheap test on a raw body line for whether its rendered text could contain
 * a search [term], so Search can skip rendering the lines that can't.
 *
 * Rendering a line without a `[[link]]` only drops emphasis markers (which sit
 * between a non-letter and a letter, never inside a word) and reformats phone
 * numbers (which keep their digit groups). Every run of letters and digits the
 * rendered line shows is therefore in the raw line too, so a term whose runs
 * aren't all in the raw line can't be in the rendered one. Bracket links can
 * join words across their brackets; callers render those lines unconditionally.
 */
class LineProbe(val term: String, val inTags: Boolean) {

    private val runs: List<String> = term.split(NON_WORD).filter { it.isNotEmpty() }

    /** False only when the rendered [raw] line certainly lacks [term]. */
    fun mayBeIn(raw: String): Boolean = runs.all { raw.contains(it, ignoreCase = true) }

    companion object {
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        /**
         * False only when [raw] certainly can't satisfy the AND-group [probes]:
         * every term must be in the line or the note's tags, and at least one
         * in the line itself (a line showing none of its terms gets no snippet).
         */
        fun mayMatch(raw: String, probes: List<LineProbe>): Boolean =
            probes.all { it.inTags || it.mayBeIn(raw) } && probes.any { it.mayBeIn(raw) }
    }
}
