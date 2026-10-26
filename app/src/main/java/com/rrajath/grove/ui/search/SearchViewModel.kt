package com.rrajath.grove.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.search.loadSearchCandidates
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.data.NoteEntity
import com.rrajath.grove.data.NoteFacetRow
import com.rrajath.grove.data.toNoteMeta
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.markedDoneMessage
import com.rrajath.grove.org.nextRepeatOccurrence
import com.rrajath.grove.search.DatePresence
import com.rrajath.grove.search.FacetNarrowing
import com.rrajath.grove.search.FilenameMatcher
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.MatchOptions
import com.rrajath.grove.search.QueryMatcher
import com.rrajath.grove.search.QueryParser
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.search.SearchQuery
import com.rrajath.grove.search.Snippets
import com.rrajath.grove.search.Term
import com.rrajath.grove.search.textTerms
import com.rrajath.grove.ui.vault.OutlineSnack
import com.rrajath.grove.ui.vault.factory
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.vault.AutoArchive
import com.rrajath.grove.vault.StateChangeResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Sentinel bucket for notes with no TODO keyword, in both the states filter
 *  and the states catalog, distinct from any real keyword string. */
const val NO_STATE = "-"

enum class DatePreset(val label: String) {
    ANY("Any"),
    TODAY("Today"),
    NEXT_7_DAYS("Next 7 days"),
    OVERDUE("Overdue"),
    NO_DATE("No date"),
    CUSTOM("Custom range"),
}

/** Inclusive start/end for [DatePreset.CUSTOM]. */
@Immutable
data class DateRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/**
 * Each facet is tri-state: a value is included, excluded (NOT), or absent from
 * both sets: [FilterPanel]'s chips cycle a tapped value through exactly those
 * three states. Included values OR together (e.g. tag=work OR tag=urgent);
 * excluded values each independently rule a note out.
 */
@Immutable
data class SearchFilters(
    val tags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val states: Set<String> = emptySet(),
    val excludedStates: Set<String> = emptySet(),
    val priorities: Set<String> = emptySet(),
    val excludedPriorities: Set<String> = emptySet(),
    val scheduled: DatePreset = DatePreset.ANY,
    val scheduledRange: DateRange? = null,
    val deadline: DatePreset = DatePreset.ANY,
    val deadlineRange: DateRange? = null,
    val active: DatePreset = DatePreset.ANY,
    val activeRange: DateRange? = null,
    val closed: DatePreset = DatePreset.ANY,
    val closedRange: DateRange? = null,
    val created: DatePreset = DatePreset.ANY,
    val createdRange: DateRange? = null,
    val notebooks: Set<String> = emptySet(),
    val excludedNotebooks: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() = (if (tags.isNotEmpty() || excludedTags.isNotEmpty()) 1 else 0) +
            (if (states.isNotEmpty() || excludedStates.isNotEmpty()) 1 else 0) +
            (if (priorities.isNotEmpty() || excludedPriorities.isNotEmpty()) 1 else 0) +
            (if (scheduled != DatePreset.ANY) 1 else 0) +
            (if (deadline != DatePreset.ANY) 1 else 0) +
            (if (active != DatePreset.ANY) 1 else 0) +
            (if (closed != DatePreset.ANY) 1 else 0) +
            (if (created != DatePreset.ANY) 1 else 0) +
            (if (notebooks.isNotEmpty() || excludedNotebooks.isNotEmpty()) 1 else 0)
}

/** A facet chip's current tri-state, for [FilterPanel]'s chip styling. */
enum class FacetState { NONE, INCLUDED, EXCLUDED }

fun <T> facetState(value: T, included: Set<T>, excluded: Set<T>): FacetState = when {
    value in included -> FacetState.INCLUDED
    value in excluded -> FacetState.EXCLUDED
    else -> FacetState.NONE
}

/** Cycles [value] through include -> exclude -> none across the given pair of sets. */
private fun <T> cycleFacet(included: Set<T>, excluded: Set<T>, value: T): Pair<Set<T>, Set<T>> = when (value) {
    in included -> (included - value) to (excluded + value)
    in excluded -> included to (excluded - value)
    else -> (included + value) to excluded
}

@Immutable
data class SearchResult(
    val fileName: String,
    val lineIndex: Int,
    val title: String,
    val keyword: String?,
    val isDone: Boolean,
    val priority: String?,
    val scheduledLabel: String?,
    val scheduledOverdue: Boolean,
    val deadlineLabel: String?,
    val deadlineOverdue: Boolean,
    /** Every bare active timestamp as a row pill label, earliest first, each with its time when set. */
    val activeLabels: ImmutableList<String> = persistentListOf(),
    val tagLine: String,
    /** Raw timestamps (vs. the display-only labels above) for the swipe-to-schedule action's date picker. */
    val scheduledTs: OrgTimestamp?,
    val deadlineTs: OrgTimestamp?,
    /** Active timestamps on the heading, so the date picker's ACTIVE tab prefills. */
    val activeTs: ImmutableList<OrgTimestamp> = persistentListOf(),
)

/**
 * One row under a file group: a [Heading] the query/filters selected (the only
 * kind that gets the swipe-to-state/schedule actions), or a [Text] snippet of a
 * body line the query's words appear on.
 */
@Immutable
sealed interface SearchRow {
    val key: String

    @Immutable
    data class Heading(val result: SearchResult) : SearchRow {
        override val key: String get() = "h-${result.lineIndex}"
    }

    @Immutable
    data class Text(
        val fileName: String,
        /** The heading the line sits under, or [INTRO_LINE_INDEX] for text before the first heading. */
        val lineIndex: Int,
        /** Up to [Snippets.CONTEXT_WORDS] words either side of the match, as plain text. */
        val snippet: String,
        /** Position among the file's snippets, to keep [key] unique. */
        val ordinal: Int,
    ) : SearchRow {
        override val key: String get() = "t-$lineIndex-$ordinal"
    }
}

@Immutable
data class SearchFileGroup(
    val fileName: String,
    val rows: ImmutableList<SearchRow>,
    /** Set when the query's text also matched this notebook's file name: the
     *  group floats above content-only groups and shows a tappable file row. */
    val nameMatch: FilenameMatch? = null,
    /** The file itself (not one of its headings) satisfied a filter-only part of
     *  the query, e.g. a `#+filetags:` tag: shows the same tappable file row. */
    val fileLevelMatch: Boolean = false,
) {
    /** The heading rows, for callers that only care about headings. */
    val headings: List<SearchResult> get() = rows.filterIsInstance<SearchRow.Heading>().map { it.result }

    /** Whether the group shows a file row above its [rows]. */
    val hasFileRow: Boolean get() = nameMatch != null || fileLevelMatch
}

/** The file-name hit behind a [SearchFileGroup.nameMatch]. */
@Immutable
data class FilenameMatch(
    /** Offsets within the base file name to emphasise in the file row. */
    val ranges: ImmutableList<IntRange>,
    /** [FilenameMatcher.Quality] ordinal; higher sorts higher. */
    val quality: Int,
)

/** One day section of the `ad.N` view (see [SearchAgenda]). */
@Immutable
data class SearchDayGroup(
    /** [SearchAgenda.OVERDUE_KEY] or the ISO date. */
    val key: String,
    val label: String,
    val results: ImmutableList<SearchResult>,
)

@Immutable
data class SearchCatalog(
    val tags: ImmutableList<String> = persistentListOf(),
    val states: ImmutableList<String> = persistentListOf(),
    val notebooks: ImmutableList<String> = persistentListOf(),
)

data class QuickCounts(val overdue: Int = 0, val today: Int = 0, val openTasks: Int = 0, val unscheduled: Int = 0)

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val groups: ImmutableList<SearchFileGroup> = persistentListOf(),
    /** Non-null when the query has `ad.N`: results laid out by day instead of
     *  by notebook ([groups] is then empty). */
    val days: ImmutableList<SearchDayGroup>? = null,
    val resultCount: Int = 0,
    val notebookCount: Int = 0,
    val isBlank: Boolean = true,
    val catalog: SearchCatalog = SearchCatalog(),
    val quickCounts: QuickCounts = QuickCounts(),
    /** Configured todo-type (non-done) keywords: backs the "Open tasks" quick card. */
    val activeStates: ImmutableList<String> = persistentListOf(),
    /** Current query's plain-text terms: result rows highlight these after org-rendering. */
    val matchedTerms: ImmutableList<String> = persistentListOf(),
)

/** Full-text + faceted search, results grouped by file (design spec §9 "Search
 *  B: panel"), or by day for an `ad.N` query ([SearchAgenda]). */
class SearchViewModel(
    private val vaultFlow: StateFlow<Vault?>,
    private val sync: SyncTrigger,
    private val searchRepository: SearchRepository,
    private val database: GroveDatabase,
    private val keywordsFlow: StateFlow<OrgKeywords>,
    private val settings: SettingsSource,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val _snack = MutableStateFlow<OutlineSnack?>(null)
    val snack: StateFlow<OutlineSnack?> = _snack
    private var eventId = 0L
    private var undoSnapshot: List<Pair<String, String>>? = null

    private fun showSnack(message: String) {
        val s = OutlineSnack(message, ++eventId)
        _snack.value = s
        viewModelScope.launch {
            delay(4200)
            if (_snack.value?.id == s.id) _snack.value = null
        }
    }

    /** Reverts the auto-archive move (state change + refile) from the most recent [setState]. */
    fun undo() {
        val snap = undoSnapshot ?: return
        undoSnapshot = null
        _snack.value = null
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            snap.forEach { (name, text) ->
                vault.save(name, text)
                sync.requestReindex(name, text, "search undo")
            }
        }
    }

    val savedSearches: StateFlow<List<SavedSearch>> = searchRepository.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Configured TODO keywords, for the swipe-to-cycle state sheet. */
    val keywords: StateFlow<OrgKeywords> = keywordsFlow

    private val queryFlow = MutableStateFlow("")
    private val filtersFlow = MutableStateFlow(SearchFilters())

    // Whole-vault facet projection: no titles, no bodies. Backs the filter
    // catalog and the blank-state quick counts, and doubles as the
    // index-changed signal for re-running the active search. Result rows
    // themselves are loaded per search (see loadCandidates), so note text is
    // never resident for the whole vault.
    private val facets = MutableStateFlow<List<NoteFacets>?>(null)

    /** Whole-vault totals, for the Filters sheet's count while nothing is narrowed. */
    private var vaultNoteCount = 0
    private var vaultNotebookCount = 0

    /** The filter-derived tokens last spliced into the query text, so the next
     *  filter change can find-and-replace just that substring (see [runSearch]
     *  doc on filters staying authoritative; this splice is display-only). */
    private var lastFilterExpr: String = ""

    init {
        viewModelScope.launch {
            // Also keyed on savedSearches so a Quick Start override (see
            // QuickStartOverrides) recomputes that card's own count immediately,
            // instead of the card's subtitle staying frozen on the old preset's
            // count until some unrelated index change happens to fire next.
            combine(
                database.indexDao().noteFacets()
                    .map { rows -> rows.map { it.toFacets() } }
                    .flowOn(dispatchers.default),
                searchRepository.savedSearches,
            ) { rows, saved -> rows to saved }
                .collect { (rows, saved) ->
                    facets.value = rows
                    updateCatalogAndCounts(rows, saved)
                }
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            // Re-runs on new keystrokes, filter taps, and when the index
            // changes under an active query (e.g. a sync finishing while the
            // search screen is up).
            combine(queryFlow.debounce(300), facets.filterNotNull(), filtersFlow) { q, _, f -> q to f }
                .collect { (q, f) -> runSearch(q, f) }
        }
        viewModelScope.launch {
            // Mirrors Filters-panel selections into the editable query field as
            // t./i./b./p. expression tokens, so picking a facet is visible and
            // hand-editable right in the field instead of a separate read-only
            // preview. Matching itself still runs off the structured filters
            // (see matchesFilters), so hand-editing or deleting the mirrored
            // tokens only changes what's displayed, not what's excluded.
            filtersFlow.collect { f ->
                val newExpr = FilterExpression.of(f)
                if (newExpr != lastFilterExpr) {
                    val spliced = spliceFilterExpr(_state.value.query, lastFilterExpr, newExpr)
                    lastFilterExpr = newExpr
                    if (spliced != _state.value.query) {
                        _state.value = _state.value.copy(query = spliced)
                        queryFlow.value = spliced
                    }
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        queryFlow.value = query
    }

    /** Run immediately (drawer shortcuts, saved-search taps). */
    fun submit(query: String) {
        _state.value = _state.value.copy(query = query)
        queryFlow.value = query
    }

    /** Star-button dropdown: saves the current query under [name], updating an
     *  existing saved search in place (by exact case-insensitive name match)
     *  rather than creating a duplicate, so "overwrite" behaves like the
     *  confirmation dialog implies. A Quick Start card's label (see
     *  [QuickStartOverrides]) instead updates that card's own reserved
     *  override row, so overwriting it changes what the card itself runs. */
    fun saveOrOverwriteSearch(name: String) {
        val query = _state.value.query
        val trimmedName = name.trim()
        if (query.isBlank() || trimmedName.isBlank()) return
        val quickStartId = QuickStartOverrides.idForName(trimmedName)
        viewModelScope.launch {
            if (quickStartId != null) {
                searchRepository.setQuickStartOverride(quickStartId, trimmedName, query.trim())
                return@launch
            }
            val existing = searchRepository.savedSearches.first()
                .firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
            if (existing != null) searchRepository.updateSearchQuery(existing.id, query.trim())
            else searchRepository.saveSearch(trimmedName, query.trim())
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch { searchRepository.deleteSearch(id) }
    }

    fun renameSavedSearch(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { searchRepository.renameSearch(id, name.trim()) }
    }

    /**
     * Swipe-to-cycle-state action: set a result's TODO state to exactly the
     * keyword picked from the state sheet (null = clear it), via
     * [OrgMutations.changeKeyword], same "mark done" / "reopen and drop
     * CLOSED" semantics as the Outline's own state sheet. Results span many
     * files, so (unlike the Outline's single open document) the target file is
     * opened and saved on demand, the way Agenda's row swipes do.
     */
    fun setState(fileName: String, lineIndex: Int, keyword: String?) {
        viewModelScope.launch { applyState(fileName, lineIndex) { _, _ -> keyword } }
    }

    /**
     * The one-swipe Done action (Orgzly's swipe-to-done): sets an open
     * heading to the first configured done-type keyword, through the same
     * path as [setState], and always offers Undo. Not offered for a
     * keyword-less heading (an event has nothing to complete) or one that's
     * already done.
     */
    fun markDone(fileName: String, lineIndex: Int) {
        viewModelScope.launch {
            applyState(fileName, lineIndex, announceDone = true) { doc, headline ->
                val keyword = headline.keyword ?: return@applyState null
                if (keyword in doc.keywords.done) null else doc.keywords.done.firstOrNull()
            }
        }
    }

    /** [target] picks the new keyword from the freshly opened document;
     *  returning the heading's current keyword (or null for [markDone]'s
     *  "nothing to do") skips the write. */
    private suspend fun applyState(
        fileName: String,
        lineIndex: Int,
        announceDone: Boolean = false,
        target: (OrgDocument, OrgHeadline) -> String?,
    ) {
        val vault = vaultFlow.value ?: return
        val doc = vault.open(fileName) ?: return
        val headline = doc.headlineAtLine(lineIndex) ?: return
        val keyword = target(doc, headline)
        if (headline.keyword == keyword || (announceDone && keyword == null)) return
        val currentSettings = settings.settings.first()
        when (
            val result = AutoArchive.apply(vault, currentSettings, doc, fileName, headline, keyword, LocalDateTime.now())
        ) {
            is StateChangeResult.Plain -> {
                vault.save(fileName, result.text)
                sync.requestReindex(fileName, result.text, "search state set")
                // A recurring task keeps its keyword and just moves its date, so say where it went.
                val next = result.doc.headlines.firstOrNull { it.lineIndex == lineIndex }
                    ?.let { nextRepeatOccurrence(headline, it) }
                if (next != null || announceDone) {
                    undoSnapshot = listOf(fileName to doc.text)
                    showSnack(markedDoneMessage(next, LocalDate.now()))
                }
            }
            is StateChangeResult.Archived -> {
                undoSnapshot = if (result.sourceFile == result.destFile) {
                    listOf(result.sourceFile to doc.text)
                } else {
                    listOf(fileName to doc.text, result.destFile to result.destTextBefore)
                }
                vault.save(fileName, result.sourceText)
                sync.requestReindex(fileName, result.sourceText, "search state set")
                if (result.destFile != fileName) {
                    vault.save(result.destFile, result.destText)
                    sync.requestReindex(result.destFile, result.destText, "search state set")
                }
                showSnack("Marked done. Refiled to ${result.label}")
            }
        }
    }

    /** Swipe-to-schedule action: planning dates + the dedicated active line, as the Dates screen commits. */
    fun setPlanningDates(
        fileName: String,
        lineIndex: Int,
        scheduled: OrgTimestamp?,
        deadline: OrgTimestamp?,
        active: List<OrgTimestamp>,
    ) {
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            val doc = vault.open(fileName) ?: return@launch
            val headline = doc.headlineAtLine(lineIndex) ?: return@launch
            val newText = withContext(dispatchers.default) {
                OrgMutations.setPlanningAndActiveTimestamps(doc, headline, scheduled, deadline, active)
            }
            vault.save(fileName, newText)
            sync.requestReindex(fileName, newText, "search planning edit")
        }
    }

    fun toggleTag(tag: String) = filtersFlow.update {
        val (inc, exc) = cycleFacet(it.tags, it.excludedTags, tag)
        it.copy(tags = inc, excludedTags = exc)
    }

    fun toggleState(state: String) = filtersFlow.update {
        val (inc, exc) = cycleFacet(it.states, it.excludedStates, state)
        it.copy(states = inc, excludedStates = exc)
    }

    fun togglePriority(priority: String) = filtersFlow.update {
        val (inc, exc) = cycleFacet(it.priorities, it.excludedPriorities, priority)
        it.copy(priorities = inc, excludedPriorities = exc)
    }

    fun toggleNotebook(name: String) = filtersFlow.update {
        val (inc, exc) = cycleFacet(it.notebooks, it.excludedNotebooks, name)
        it.copy(notebooks = inc, excludedNotebooks = exc)
    }

    fun clearNotebooks() = filtersFlow.update { it.copy(notebooks = emptySet(), excludedNotebooks = emptySet()) }

    fun setScheduledPreset(preset: DatePreset) =
        filtersFlow.update {
            if (it.scheduled == preset) it.copy(scheduled = DatePreset.ANY, scheduledRange = null)
            else it.copy(scheduled = preset, scheduledRange = null)
        }

    fun setDeadlinePreset(preset: DatePreset) =
        filtersFlow.update {
            if (it.deadline == preset) it.copy(deadline = DatePreset.ANY, deadlineRange = null)
            else it.copy(deadline = preset, deadlineRange = null)
        }

    fun setScheduledRange(start: LocalDate, end: LocalDate) =
        filtersFlow.update { it.copy(scheduled = DatePreset.CUSTOM, scheduledRange = DateRange(start, end)) }

    fun setDeadlineRange(start: LocalDate, end: LocalDate) =
        filtersFlow.update { it.copy(deadline = DatePreset.CUSTOM, deadlineRange = DateRange(start, end)) }

    fun setActivePreset(preset: DatePreset) =
        filtersFlow.update {
            if (it.active == preset) it.copy(active = DatePreset.ANY, activeRange = null)
            else it.copy(active = preset, activeRange = null)
        }

    fun setActiveRange(start: LocalDate, end: LocalDate) =
        filtersFlow.update { it.copy(active = DatePreset.CUSTOM, activeRange = DateRange(start, end)) }

    fun setClosedPreset(preset: DatePreset) =
        filtersFlow.update {
            if (it.closed == preset) it.copy(closed = DatePreset.ANY, closedRange = null)
            else it.copy(closed = preset, closedRange = null)
        }

    fun setCreatedPreset(preset: DatePreset) =
        filtersFlow.update {
            if (it.created == preset) it.copy(created = DatePreset.ANY, createdRange = null)
            else it.copy(created = preset, createdRange = null)
        }

    fun setClosedRange(start: LocalDate, end: LocalDate) =
        filtersFlow.update { it.copy(closed = DatePreset.CUSTOM, closedRange = DateRange(start, end)) }

    fun setCreatedRange(start: LocalDate, end: LocalDate) =
        filtersFlow.update { it.copy(created = DatePreset.CUSTOM, createdRange = DateRange(start, end)) }

    /**
     * Scope the search to exactly one notebook, replacing any existing
     * notebook selection: the Outline's search action arrives with a file
     * already in mind, so this pins that file rather than toggling it in
     * alongside whatever else was selected.
     */
    fun pinNotebook(name: String) {
        filtersFlow.update { it.copy(notebooks = setOf(name), excludedNotebooks = emptySet()) }
    }

    fun clearFilters() {
        filtersFlow.value = SearchFilters()
    }

    /** Quick-start shortcuts (design spec §9 blank-state cards): replace the
     *  free-text query and filters wholesale with the shortcut's own facet. */
    fun applyQuickFilter(filters: SearchFilters) {
        queryFlow.value = ""
        _state.value = _state.value.copy(query = "")
        filtersFlow.value = filters
    }

    /** Quick-start shortcuts whose condition can't be expressed as one
     *  [SearchFilters] facet (e.g. "overdue on either date", which needs an OR
     *  across two different fields): drive the query text directly instead,
     *  replacing filters wholesale the same way [applyQuickFilter] does. */
    fun applyQuickQuery(query: String) {
        filtersFlow.value = SearchFilters()
        _state.value = _state.value.copy(query = query)
        queryFlow.value = query
    }

    /** Back button: clear back to the blank quick-start view instead of leaving
     *  the screen, when a query or filter is active (see SearchScreen's onBack). */
    fun resetToBlank() {
        queryFlow.value = ""
        _state.value = _state.value.copy(query = "")
        filtersFlow.value = SearchFilters()
    }

    /** What the current settings make a query mean (`o.state` order, `p.` default). */
    private suspend fun matchOptions(): MatchOptions = MatchOptions(
        stateOrder = keywordsFlow.value.all,
        defaultPriority = settings.settings.first().defaultPriority?.toString(),
    )

    /** Replaces the previous filter-token substring with the new one inside
     *  [current], leaving whatever the user typed themselves untouched. */
    private fun spliceFilterExpr(current: String, old: String, new: String): String {
        val withoutOld = if (old.isNotEmpty() && current.contains(old)) {
            current.replace(old, " ").replace(Regex("\\s+"), " ").trim()
        } else {
            current.trim()
        }
        return when {
            new.isEmpty() -> withoutOld
            withoutOld.isEmpty() -> new
            else -> "$withoutOld $new"
        }
    }

    private suspend fun runSearch(raw: String, filters: SearchFilters) {
        val textQuery = if (raw.isBlank()) null else QueryParser.parse(raw)

        // Nothing typed and nothing filtered: the screen shows the quick-start
        // cards, so there are no results to build. The Filters sheet still wants
        // the whole-vault totals for its "Show N results" button.
        if (raw.isBlank() && filters.activeCount == 0) {
            _state.value = _state.value.copy(
                filters = filters,
                groups = persistentListOf(),
                days = null,
                resultCount = vaultNoteCount,
                notebookCount = vaultNotebookCount,
                isBlank = true,
                matchedTerms = persistentListOf(),
            )
            return
        }

        val notes = loadCandidates(textQuery, filters)
        val facetRows = facets.value.orEmpty()

        withContext(dispatchers.default) {
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val options = matchOptions()
            val textMatched = textQuery?.let { QueryMatcher.filter(notes, it, today, options, now) } ?: notes
            val terms = textQuery?.textTerms ?: emptyList()
            val filtered = textMatched.filter { matchesFilters(it, filters, today) }

            val agendaDays = textQuery?.agendaDays
            if (agendaDays != null) {
                // Text snippets and file rows don't apply: every entry is a
                // dated heading (an intro has no planning line or events).
                val days = SearchAgenda.build(filtered.filter { it.lineIndex != INTRO_LINE_INDEX }, today, agendaDays)
                    .map { day ->
                        SearchDayGroup(
                            key = day.key,
                            label = day.label,
                            results = day.entries.map { toResult(it.note, today) }.toImmutableList(),
                        )
                    }
                _state.value = _state.value.copy(
                    filters = filters,
                    groups = persistentListOf(),
                    days = days.toImmutableList(),
                    resultCount = days.sumOf { it.results.size },
                    notebookCount = days.flatMap { d -> d.results.map { it.fileName } }.distinct().size,
                    isBlank = false,
                    matchedTerms = terms.toImmutableList(),
                )
                return@withContext
            }

            // Group by file, preserving first-seen file order. A note with
            // nothing to show (its words only meet across lines) is dropped.
            val byFile = LinkedHashMap<String, MutableList<SearchRow>>()
            val fileLevel = mutableSetOf<String>()
            filtered.forEach { note ->
                val groups = textQuery?.let { QueryMatcher.satisfiedGroups(note, it, today, options, now) } ?: listOf(emptyList())
                val rows = byFile.getOrPut(note.fileName) { mutableListOf() }
                if (note.lineIndex == INTRO_LINE_INDEX) {
                    if (groups.any { it.textTerms().isEmpty() }) fileLevel += note.fileName
                } else if (groups.any { g -> g.textTerms().all { note.matchesInHeading(it) } }) {
                    rows += SearchRow.Heading(toResult(note, today))
                }
                rows += lineSnippets(note, groups, ordinalFrom = rows.size)
            }
            byFile.entries.removeAll { (file, rows) -> rows.isEmpty() && file !in fileLevel }

            // Additive pass: a file whose *name* matches the query's text floats
            // above every content-only group and gets its own tappable file row,
            // even when nothing inside it matched. Notebook scoping from the
            // Filters sheet still applies.
            val nameHits = textQuery
                ?.let { FilenameMatcher.match(facetRows.map(NoteFacets::fileName), it) }
                ?.filter { hit ->
                    (filters.notebooks.isEmpty() || hit.fileName in filters.notebooks) &&
                        hit.fileName !in filters.excludedNotebooks
                }
                .orEmpty()
            val hitByFile = nameHits.associateBy { it.fileName }
            val lastModifiedByFile = facetRows.groupingBy { it.fileName }
                .fold(Long.MIN_VALUE) { acc, row -> maxOf(acc, row.lastModified) }
            val nameFiles = SearchResultOrder.nameMatchedFiles(nameHits, lastModifiedByFile)

            val orderedFiles = (nameFiles + byFile.keys.filterNot { it in hitByFile }).distinct()
            val groups = orderedFiles.map { file ->
                SearchFileGroup(
                    fileName = file,
                    rows = byFile[file].orEmpty().toImmutableList(),
                    nameMatch = hitByFile[file]?.let { hit ->
                        FilenameMatch(hit.ranges.toImmutableList(), hit.quality.ordinal)
                    },
                    fileLevelMatch = file in fileLevel,
                )
            }.toImmutableList()

            _state.value = _state.value.copy(
                filters = filters,
                groups = groups,
                days = null,
                resultCount = groups.sumOf { it.rows.size + if (it.hasFileRow) 1 else 0 },
                notebookCount = groups.size,
                isBlank = false,
                matchedTerms = terms.toImmutableList(),
            )
        }
    }

    /**
     * Loads only the rows this search could possibly match: the FTS5 index
     * supplies the text candidates and the facet chips / structured tokens
     * become SQL predicates. Both narrowings are supersets by construction, so
     * [QueryMatcher] and [matchesFilters] below still decide every result and
     * the visible behaviour is identical to scanning the whole vault.
     */
    private suspend fun loadCandidates(textQuery: SearchQuery?, filters: SearchFilters): List<NoteMeta> {
        val rows = loadSearchCandidates(database, textQuery, filters.toNarrowing())
        return withContext(dispatchers.default) { rows.map { it.toNoteMeta() } }
    }

    private fun SearchFilters.toNarrowing() = FacetNarrowing(
        // A superset-only optimization (see NoteCandidateQuery's doc comment):
        // excludes and multi-notebook selection aren't pushed to SQL, they're
        // just left for matchesFilters below to decide in Kotlin.
        notebook = notebooks.singleOrNull(),
        states = states - NO_STATE,
        includeNoState = NO_STATE in states,
        priorities = priorities,
        tags = tags,
        scheduled = scheduled.presence(),
        deadline = deadline.presence(),
        active = active.presence(),
        closed = closed.presence(),
        created = created.presence(),
    )

    /** Every date preset except "any" and "no date" needs a timestamp to exist. */
    private fun DatePreset.presence(): DatePresence = when (this) {
        DatePreset.ANY -> DatePresence.ANY
        DatePreset.NO_DATE -> DatePresence.ABSENT
        else -> DatePresence.PRESENT
    }

    private fun matchesFilters(note: NoteMeta, f: SearchFilters, today: LocalDate): Boolean {
        if (f.tags.isNotEmpty() && f.tags.none { note.inheritedTags.contains(it) }) return false
        if (f.excludedTags.isNotEmpty() && f.excludedTags.any { note.inheritedTags.contains(it) }) return false
        val state = note.keyword ?: NO_STATE
        if (f.states.isNotEmpty() && state !in f.states) return false
        if (f.excludedStates.isNotEmpty() && state in f.excludedStates) return false
        if (f.priorities.isNotEmpty() && note.priority !in f.priorities) return false
        if (f.excludedPriorities.isNotEmpty() && note.priority in f.excludedPriorities) return false
        if (f.scheduled != DatePreset.ANY &&
            !datePresetMatches(note.scheduledDate, f.scheduled, today, f.scheduledRange, note.isDoneKeyword)
        ) return false
        if (f.deadline != DatePreset.ANY &&
            !datePresetMatches(note.deadlineDate, f.deadline, today, f.deadlineRange, note.isDoneKeyword)
        ) return false
        if (f.active != DatePreset.ANY &&
            !activePresetMatches(note, f.active, today, f.activeRange)
        ) return false
        if (f.closed != DatePreset.ANY &&
            !datePresetMatches(note.closedDate, f.closed, today, f.closedRange, note.isDoneKeyword)
        ) return false
        if (f.created != DatePreset.ANY &&
            !datePresetMatches(note.createdDate, f.created, today, f.createdRange, note.isDoneKeyword)
        ) return false
        if (f.notebooks.isNotEmpty() && note.fileName !in f.notebooks) return false
        if (f.excludedNotebooks.isNotEmpty() && note.fileName in f.excludedNotebooks) return false
        return true
    }

    private fun datePresetMatches(
        date: LocalDate?,
        preset: DatePreset,
        today: LocalDate,
        range: DateRange?,
        isDone: Boolean,
    ): Boolean = when (preset) {
        DatePreset.ANY -> true
        DatePreset.NO_DATE -> date == null
        DatePreset.TODAY -> date == today
        DatePreset.NEXT_7_DAYS -> date != null && !date.isBefore(today) && !date.isAfter(today.plusDays(7))
        // Overdue is only meaningful for tasks that are still open.
        DatePreset.OVERDUE -> !isDone && date != null && date.isBefore(today)
        DatePreset.CUSTOM -> date != null && range != null && date in range
    }

    /** The list-valued counterpart for bare active timestamps: a preset matches
     *  when any day the events land on satisfies it. Events are never "done", so
     *  Overdue here just means an event that has fully passed (search shows those;
     *  the agenda hides them). */
    private fun activePresetMatches(
        note: NoteMeta,
        preset: DatePreset,
        today: LocalDate,
        range: DateRange?,
    ): Boolean {
        val dates = note.activeDates
        return when (preset) {
            DatePreset.ANY -> true
            DatePreset.NO_DATE -> dates.isEmpty()
            DatePreset.TODAY -> today in dates
            DatePreset.NEXT_7_DAYS -> dates.any { !it.isBefore(today) && !it.isAfter(today.plusDays(7)) }
            DatePreset.OVERDUE -> note.activeTimestamps.any { (it.rangeEnd ?: it.date).isBefore(today) }
            DatePreset.CUSTOM -> range != null && dates.any { it in range }
        }
    }

    /**
     * A snippet per match cluster on each body line that carries every word of
     * one of the note's satisfied AND-groups. Lines are rendered to plain text
     * first (links show their label), so the words and window are what you'd
     * read, not org syntax.
     */
    /** A text term the heading row itself shows: in the title or one of the
     *  note's own tags (plain text matches those too, see `QueryMatcher`). */
    private fun NoteMeta.matchesInHeading(term: String): Boolean = title.contains(term, ignoreCase = true) || inTags(term)

    private fun NoteMeta.inTags(term: String): Boolean = tags.any { it.contains(term, ignoreCase = true) }

    private fun lineSnippets(note: NoteMeta, groups: List<List<Term>>, ordinalFrom: Int): List<SearchRow.Text> {
        val groupTerms = groups.map { it.textTerms() }.filter { it.isNotEmpty() }
        if (groupTerms.isEmpty()) return emptyList()
        var ordinal = ordinalFrom
        return note.searchText.substringAfter('\n', "").lineSequence()
            .map { line -> InlineTokenizer.tokenize(line).joinToString("") { it.text }.trim() }
            .flatMap { line ->
                // A term found in the note's own tags is already satisfied, so
                // a line only needs the rest; it highlights what it contains.
                val lineTerms = groupTerms
                    .filter { terms -> terms.all { line.contains(it, ignoreCase = true) || note.inTags(it) } }
                    .flatten()
                    .filter { line.contains(it, ignoreCase = true) }
                    .distinct()
                if (lineTerms.isEmpty()) emptySequence() else Snippets.windows(line, lineTerms).asSequence()
            }
            .map { SearchRow.Text(note.fileName, note.lineIndex, it, ordinal++) }
            .toList()
    }

    private fun toResult(meta: NoteMeta, today: LocalDate): SearchResult {
        val (scheduledLabel, scheduledOverdue) = dateLabel(meta.scheduledTs, today)
        val (deadlineLabel, deadlineOverdue) = dateLabel(meta.deadlineTs, today)
        return SearchResult(
            fileName = meta.fileName,
            lineIndex = meta.lineIndex,
            title = meta.title,
            keyword = meta.keyword,
            isDone = meta.isDoneKeyword,
            priority = meta.priority,
            scheduledLabel = scheduledLabel,
            scheduledOverdue = scheduledOverdue,
            deadlineLabel = deadlineLabel,
            deadlineOverdue = deadlineOverdue,
            activeLabels = activeStampLabels(meta.activeTimestamps, today),
            tagLine = meta.tags.joinToString(" ") { ":$it:" },
            scheduledTs = meta.scheduled?.let { OrgTimestamp.parse(it) },
            deadlineTs = meta.deadline?.let { OrgTimestamp.parse(it) },
            activeTs = meta.active
                ?.let { OrgTimestamp.parseAll(it).filter { ts -> ts.active } }
                .orEmpty()
                .toImmutableList(),
        )
    }

    /** A SCHEDULED/DEADLINE pill: the day, plus its time-of-day (and end
     *  time) when set, like the event pills ("today 23:00"). An overdue one
     *  reads "3d overdue" with no time: the day count is what matters. */
    private fun dateLabel(ts: OrgTimestamp?, today: LocalDate): Pair<String?, Boolean> {
        if (ts == null) return null to false
        val n = ChronoUnit.DAYS.between(today, ts.date)
        val overdue = n < 0
        val day = when {
            n == 0L -> "today"
            n == 1L -> "tomorrow"
            overdue -> return "${-n}d overdue" to true
            else -> ts.date.format(DAY_FORMAT)
        }
        return day + clockLabel(ts) to false
    }

    /** " 14:00" or " 14:00-15:30" for a timed stamp, "" for an all-day one. */
    private fun clockLabel(ts: OrgTimestamp): String =
        ts.time?.let { t -> " " + t.format(CLOCK_FORMAT) + (ts.endTime?.let { "-${it.format(CLOCK_FORMAT)}" } ?: "") } ?: ""

    /** Every bare active timestamp as a row pill label, earliest first. Each
     *  keeps its time-of-day (and range span) when it has one, so a stamp like
     *  `<2026-09-15 14:00>` no longer vanishes behind the earliest all-day one. */
    private fun activeStampLabels(stamps: List<OrgTimestamp>, today: LocalDate): ImmutableList<String> =
        stamps
            .sortedWith(compareBy({ it.date }, { it.time }))
            .map { ts ->
                val start = activeDateLabel(ts.date, today)
                val span = ts.rangeEnd?.let { "$start – ${activeDateLabel(it, today)}" } ?: start
                span + clockLabel(ts)
            }
            .toImmutableList()

    /** Like [dateLabel] but framed as an event, not a task: a past day reads
     *  "3d ago", never "overdue". */
    private fun activeDateLabel(date: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, date)
        return when {
            n == 0L -> "today"
            n == 1L -> "tomorrow"
            n == -1L -> "yesterday"
            n < 0 -> "${-n}d ago"
            else -> date.format(DAY_FORMAT)
        }
    }

    /** Recomputed from the whole vault (not the active filters), so the
     *  Filters panel's chip catalog and the blank-state quick-start counts
     *  stay stable while the user is actively narrowing results. A card with a
     *  Quick Start override (see [QuickStartOverrides]) gets its count from
     *  actually running that override's query instead of the card's built-in
     *  preset predicate, so the subtitle reflects what overwriting the card
     *  changed it to search for. */
    private suspend fun updateCatalogAndCounts(notes: List<NoteFacets>, savedSearches: List<SavedSearch>) {
        val today = LocalDate.now()
        val keywords = keywordsFlow.value
        val tags = notes.flatMap { it.inheritedTags }.distinct().sorted().toImmutableList()
        // All configured states (not just ones currently in use), todo-type first
        // then done-type, "no state" last.
        val states = (keywords.active + keywords.done + NO_STATE).toImmutableList()
        val notebooks = notes.map { it.fileName }.distinct().sorted().toImmutableList()
        val overrides = savedSearches.filter { it.id in QuickStartOverrides.ids }.associateBy { it.id }

        val overdue = overrides[QuickStartOverrides.OVERDUE_ID]?.let { overrideCount(it.query) } ?: notes.count {
            !it.isDone && ((it.scheduledDate?.isBefore(today) == true) || (it.deadlineDate?.isBefore(today) == true))
        }
        val dueToday = overrides[QuickStartOverrides.TODAY_ID]?.let { overrideCount(it.query) }
            ?: notes.count { it.scheduledDate == today || it.deadlineDate == today }
        val openTasks = overrides[QuickStartOverrides.OPEN_TASKS_ID]?.let { overrideCount(it.query) }
            ?: notes.count { it.keyword != null && !it.isDone }
        val unscheduled = overrides[QuickStartOverrides.UNSCHEDULED_ID]?.let { overrideCount(it.query) } ?: notes.count {
            it.keyword != null && !it.isDone && it.scheduledDate == null && it.deadlineDate == null
        }
        vaultNoteCount = notes.size
        vaultNotebookCount = notebooks.size
        _state.value = _state.value.copy(
            catalog = SearchCatalog(tags, states, notebooks),
            quickCounts = QuickCounts(overdue, dueToday, openTasks, unscheduled),
            activeStates = keywords.active.toImmutableList(),
        )
    }

    /** Runs [query] through the same candidate-load + match pipeline as a real
     *  search (with no facet filters applied) and returns how many notes it
     *  matches, for an overridden Quick Start card's count. */
    private suspend fun overrideCount(query: String): Int {
        val textQuery = if (query.isBlank()) null else QueryParser.parse(query)
        val notes = loadCandidates(textQuery, SearchFilters())
        return withContext(dispatchers.default) {
            textQuery?.let {
                val now = LocalDateTime.now()
                QueryMatcher.filter(notes, it, now.toLocalDate(), matchOptions(), now).size
            } ?: notes.size
        }
    }

    /** Facet projection with its planning dates already parsed. */
    private data class NoteFacets(
        val fileName: String,
        val keyword: String?,
        val isDone: Boolean,
        val inheritedTags: List<String>,
        val scheduledDate: LocalDate?,
        val deadlineDate: LocalDate?,
        val lastModified: Long,
    )

    private fun NoteFacetRow.toFacets() = NoteFacets(
        fileName = fileName,
        keyword = keyword,
        isDone = isDone,
        inheritedTags = inheritedTags.split(':').filter { it.isNotEmpty() },
        scheduledDate = scheduled?.let { OrgTimestamp.parse(it)?.date },
        deadlineDate = deadline?.let { OrgTimestamp.parse(it)?.date },
        lastModified = lastModified,
    )

    companion object {
        val Factory = factory {
            SearchViewModel(
                it.vault, it.syncManager, it.searchRepository, it.database,
                it.keywords, it.settingsRepository, it.dispatchers,
            )
        }

        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    }
}
