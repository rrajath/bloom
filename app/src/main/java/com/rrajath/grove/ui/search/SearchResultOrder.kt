package com.rrajath.grove.ui.search

import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.search.FilenameMatcher
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.SearchQuery
import java.time.LocalDate

/**
 * The order the Search screen lays its results out in, pulled out of
 * [SearchViewModel] so a surface that shows the same results without the
 * screen's file groups (the Agenda widget's saved-search view) lists them in
 * exactly that order. Pure, so it stays testable on the JVM.
 */
object SearchResultOrder {

    /**
     * Files whose *name* matched the query, in the order the Search screen
     * floats them above every content-only group: best match quality first,
     * then most recently modified, then name.
     */
    fun nameMatchedFiles(hits: List<FilenameMatcher.Hit>, lastModifiedByFile: Map<String, Long>): List<String> =
        hits
            .sortedWith(
                compareByDescending<FilenameMatcher.Hit> { it.quality }
                    .thenByDescending { lastModifiedByFile[it.fileName] ?: Long.MIN_VALUE }
                    .thenBy { it.fileName.lowercase() },
            )
            .map { it.fileName }

    /**
     * [matched] (already [com.rrajath.grove.search.QueryMatcher.filter]ed and
     * sorted) as one flat list of headings, in the order the Search screen
     * shows them:
     *
     * - an `ad.N` query is the day-grouped view, flattened day by day. A note
     *   that shows on several days (a ranged event) is listed once, at its
     *   first day.
     * - anything else is grouped by file: [nameFiles] first, then every other
     *   file in first-seen order, each file's headings in [matched]'s order.
     *
     * File intros (heading-less content) are dropped: they're file rows on the
     * Search screen, not headings.
     */
    fun flatHeadings(
        matched: List<NoteMeta>,
        query: SearchQuery,
        today: LocalDate,
        nameFiles: List<String> = emptyList(),
    ): List<NoteMeta> {
        val headings = matched.filter { it.lineIndex != INTRO_LINE_INDEX }
        val agendaDays = query.agendaDays
        if (agendaDays != null) {
            return SearchAgenda.build(headings, today, agendaDays)
                .flatMap { day -> day.entries.map { it.note } }
                .distinctBy { it.fileName to it.lineIndex }
        }
        val byFile = LinkedHashMap<String, MutableList<NoteMeta>>()
        headings.forEach { byFile.getOrPut(it.fileName) { mutableListOf() } += it }
        val orderedFiles = (nameFiles + byFile.keys).distinct()
        return orderedFiles.flatMap { byFile[it].orEmpty() }
    }
}
