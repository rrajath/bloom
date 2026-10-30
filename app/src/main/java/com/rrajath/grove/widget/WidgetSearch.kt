package com.rrajath.grove.widget

import android.util.Log
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.data.toNoteMeta
import com.rrajath.grove.search.FilenameMatcher
import com.rrajath.grove.search.MatchOptions
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QueryMatcher
import com.rrajath.grove.search.QueryParser
import com.rrajath.grove.search.loadSearchCandidates
import com.rrajath.grove.ui.search.SearchResultOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

private const val TAG = "GroveWidget"

/**
 * The Agenda widget's saved-search view: the same query pipeline the Search
 * screen runs for a saved search (candidate SQL, [QueryMatcher], the screen's
 * result order), minus everything that only exists to draw that screen
 * (snippets, file rows, facet filters).
 */
internal object WidgetSearch {

    /**
     * [rawQuery]'s matching headings in the Search screen's order. A blank
     * query matches nothing here: on the Search screen it's the Quick Start
     * blank state, not a result list.
     */
    suspend fun run(app: GroveApplication, rawQuery: String, defaultPriority: Char?): List<NoteMeta> {
        if (rawQuery.isBlank()) return emptyList()
        val query = QueryParser.parse(rawQuery)
        val dao = app.database.indexDao()
        val rows = loadSearchCandidates(app.database, query)
        val recency = if (query.agendaDays == null) dao.fileRecency() else emptyList()
        return withContext(Dispatchers.Default) {
            val notes = rows.map { it.toNoteMeta() }
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val options = MatchOptions(
                stateOrder = app.keywords.value.all,
                defaultPriority = defaultPriority?.toString(),
            )
            val matched = QueryMatcher.filter(notes, query, today, options, now)
            val nameFiles = SearchResultOrder.nameMatchedFiles(
                FilenameMatcher.match(recency.map { it.fileName }, query),
                recency.associate { it.fileName to it.lastModified },
            )
            SearchResultOrder.flatHeadings(matched, query, today, nameFiles)
        }
    }

    /**
     * [run], degrading to no results instead of failing: same reasoning as the
     * agenda view's bucketing guard, an exception here would otherwise leave
     * the whole widget on the host's "Can't show content" placeholder.
     */
    suspend fun runSafely(app: GroveApplication, rawQuery: String, defaultPriority: Char?): List<NoteMeta> =
        try {
            run(app, rawQuery, defaultPriority)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "saved search failed; showing no results: $rawQuery", e)
            emptyList()
        }

    /**
     * [runSafely], re-run on every index write (so a mark-done, a sync, or an in-app
     * edit reaches a live widget session on its own, like the agenda view's
     * `plannedNotes()` collection) and whenever the keywords or the default
     * priority (which change what the query means) change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun results(app: GroveApplication, rawQuery: String): Flow<List<NoteMeta>> =
        combine(
            indexChanges(app),
            app.keywords,
            app.settingsRepository.settings.map { it.defaultPriority }.distinctUntilChanged(),
        ) { _, _, defaultPriority -> defaultPriority }
            .mapLatest { defaultPriority -> runSafely(app, rawQuery, defaultPriority) }
            .flowOn(Dispatchers.Default)

    /**
     * Room's `notesChanged()`, with later writes debounced by
     * [INDEX_CHANGE_DEBOUNCE_MS]. A full reindex commits one transaction per
     * file, and each would otherwise re-run the whole search; [mapLatest]
     * alone can't stop that, since the search's CPU work never suspends. The
     * first emission passes straight through, so a session's first results
     * aren't delayed.
     */
    @OptIn(FlowPreview::class)
    private fun indexChanges(app: GroveApplication): Flow<Int> = flow {
        var first = true
        emitAll(
            app.database.indexDao().notesChanged().debounce {
                if (first) 0L.also { first = false } else INDEX_CHANGE_DEBOUNCE_MS
            },
        )
    }

    private const val INDEX_CHANGE_DEBOUNCE_MS = 500L
}
