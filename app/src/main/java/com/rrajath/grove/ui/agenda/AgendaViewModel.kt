package com.rrajath.grove.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.toNoteMeta
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.org.markedDoneMessage
import com.rrajath.grove.org.nextRepeatOccurrence
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.AgendaGrouping
import com.rrajath.grove.settings.AgendaStateFilter
import com.rrajath.grove.settings.AgendaSwipeAction
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.ui.vault.OutlineSnack
import com.rrajath.grove.ui.vault.factory
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.vault.AutoArchive
import com.rrajath.grove.vault.StateChangeResult
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.org.orgLocale
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Which theme color one meta chip on an agenda row renders in. */
enum class AgendaMetaTone { NORMAL, MUTED, DANGER, TAG, EVENT }

/**
 * Renders like Read mode's planning chip (`PlanningChip` in `ReadNoteScreen`),
 * minus its pill background: an icon plus colored text instead of plain text.
 * [AgendaMetaIcon.NONE] keeps a meta entry as plain mono text.
 */
enum class AgendaMetaIcon { NONE, CALENDAR, EVENT_DOT }

/** One entry in a row's mono meta strip: the date, a `⚑` deadline, an event day, a time range, `↻` repeater, tags, or the file. */
@Immutable
data class AgendaMeta(val text: String, val tone: AgendaMetaTone, val icon: AgendaMetaIcon = AgendaMetaIcon.NONE)

@Immutable
data class AgendaRow(
    val fileName: String,
    val lineIndex: Int,
    val title: String,
    val keyword: String?,
    val isDone: Boolean,
    val priority: String?,
    val meta: ImmutableList<AgendaMeta>,
    /** Prefills the Dates screen for the swipe-to-schedule/deadline actions. */
    val scheduledTs: OrgTimestamp?,
    val deadlineTs: OrgTimestamp?,
    /** Set when a bare active timestamp (an event) is what put this row on this day. */
    val activeTs: OrgTimestamp? = null,
    /** Every active timestamp on the heading, so the Dates screen's ACTIVE tab prefills. */
    val activeTimestamps: ImmutableList<OrgTimestamp> = persistentListOf(),
)

/**
 * A heading with no TODO keyword is an event, not a task: it appears in the
 * agenda only because of a timestamp (a bare active date, SCHEDULED, or
 * DEADLINE) and there is nothing to "complete" — so no checkbox, no Done swipe,
 * and no done circle in the widget. A heading that carries a keyword stays a
 * task even when it also has an active timestamp.
 */
val AgendaRow.isEvent: Boolean get() = keyword == null

/**
 * A keyword-less row's own advanceable repeater, if it has one: SCHEDULED or
 * DEADLINE's repeater when that's what placed the row on this day, or the
 * bare active timestamp's when [AgendaRow.activeTs] is what did (see its own
 * doc). Null for a heading with a keyword (that row's done affordance is the
 * ordinary task checkbox instead) and for a plain event with no repeater at
 * all, which has nothing to advance.
 */
val AgendaRow.repeaterKind: PlanningKind?
    get() = when {
        keyword != null -> null
        activeTs?.repeater != null -> PlanningKind.ACTIVE
        scheduledTs?.repeater != null -> PlanningKind.SCHEDULED
        deadlineTs?.repeater != null -> PlanningKind.DEADLINE
        else -> null
    }

/**
 * Whether this row gets a done affordance at all (checkbox + swipe): a real
 * task, or a keyword-less row with a repeater to advance. A plain event
 * ([isEvent] true, [repeaterKind] null) gets neither.
 */
val AgendaRow.hasDoneAffordance: Boolean get() = keyword != null || repeaterKind != null

/** One "Group by" bucket: an uppercase key, its count, and its rows. */
@Immutable
data class AgendaGroup(val key: String, val count: Int, val rows: ImmutableList<AgendaRow>)

/** The Today/Upcoming segmented control. Navigational, so it is not persisted. */
enum class AgendaTab { TODAY, UPCOMING }

data class AgendaUiState(
    /** "Wednesday": the big header line. */
    val headerDay: String = "",
    /** "July 29": the mono sub-line, followed by [todayCount]. */
    val headerDate: String = "",
    val todayCount: Int = 0,
    val tab: AgendaTab = AgendaTab.TODAY,
    val leversOpen: Boolean = false,
    val overdueOpen: Boolean = false,
    val overdue: ImmutableList<AgendaRow> = persistentListOf(),
    val groups: ImmutableList<AgendaGroup> = persistentListOf(),
    val grouping: AgendaGrouping = AgendaGrouping.DATE,
    val stateFilter: AgendaStateFilter = AgendaStateFilter.Open,
    /** The vault's active (todo-type) keywords: one "Show" chip each, between Open and Everything. */
    val activeKeywords: ImmutableList<String> = persistentListOf(),
    val showTags: Boolean = true,
    val showFile: Boolean = false,
    val showTimestamps: Boolean = false,
    val swipeLeftAction: AgendaSwipeAction = AgendaSwipeAction.MARK_DONE,
    val swipeRightAction: AgendaSwipeAction = AgendaSwipeAction.SET_SCHEDULED,
) {
    val overdueCount: Int get() = overdue.size
    val isEmpty: Boolean get() = overdue.isEmpty() && groups.isEmpty()
}

/**
 * Projection of [GroveSettings] onto just the fields [AgendaViewModel] reads, so
 * an unrelated preference write does not trigger a full agenda recompute. All
 * `equals`/`hashCode` via `data class`; used only as a `distinctUntilChangedBy` key.
 */
private data class AgendaPrefs(
    val groupingToday: AgendaGrouping,
    val groupingUpcoming: AgendaGrouping,
    val stateFilterToday: AgendaStateFilter,
    val stateFilterUpcoming: AgendaStateFilter,
    val showTags: Boolean,
    val showFile: Boolean,
    val showTimestamps: Boolean,
    val swipeLeft: AgendaSwipeAction,
    val swipeRight: AgendaSwipeAction,
) {
    constructor(s: GroveSettings) : this(
        s.agendaGroupingToday,
        s.agendaGroupingUpcoming,
        s.agendaStateFilterToday,
        s.agendaStateFilterUpcoming,
        s.agendaShowTags,
        s.agendaShowFile,
        s.agendaShowTimestamps,
        s.agendaSwipeLeftAction,
        s.agendaSwipeRightAction,
    )
}

/**
 * The "Agenda A · focus" screen from `design/Grove.dc.html`: today's work first,
 * an overdue card above it, and a levers panel that re-buckets the same list by
 * date, priority, tag, or file.
 *
 * An item belongs to exactly one day (its SCHEDULED date if it has one, else
 * its DEADLINE), which is what makes the "Group by · Date" buckets disjoint. A
 * heading that has both shows on its scheduled day with a red `⚑ <date>` chip
 * announcing the deadline, rather than appearing twice.
 *
 * Unlike Search, Agenda also owns a mutation path: the row checkbox toggles
 * done, swipe gestures (configured in Settings § Agenda) set SCHEDULED/DEADLINE
 * or mark done, and the overdue card's "Move to today" rewrites every overdue
 * planning date at once. All of them are undoable while the snackbar is up.
 */
class AgendaViewModel(
    private val settingsRepository: SettingsRepository,
    private val keywordsFlow: StateFlow<OrgKeywords>,
    private val database: GroveDatabase,
    private val vaultFlow: StateFlow<Vault?>,
    private val sync: SyncTrigger,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(AgendaUiState())
    val state: StateFlow<AgendaUiState> = _state

    /** Undo snackbar for the mutating actions (design spec: ~4.2s with an UNDO action). */
    private val _snack = MutableStateFlow<OutlineSnack?>(null)
    val snack: StateFlow<OutlineSnack?> = _snack

    private var matched: List<NoteMeta> = emptyList()
    private var windowDays = INITIAL_WINDOW_DAYS

    @Volatile
    private var prefs: GroveSettings = GroveSettings()

    @Volatile
    private var keywords: OrgKeywords = OrgKeywords.DEFAULT

    // Session-only view state; the levers themselves persist via settings.
    private var tab = AgendaTab.TODAY
    private var leversOpen = false
    private var overdueOpen = false

    @Volatile
    private var loadingMore = false

    private var eventId = 0L

    /** Pre-mutation text of every file one action touched: "Move to today" spans several. */
    private data class FileSnapshot(val fileName: String, val text: String)

    private var undoSnapshot: List<FileSnapshot> = emptyList()

    init {
        // Combined into one collector so a settings write, a keywords change,
        // and a Room `plannedNotes` emission arriving close together each
        // update this ViewModel's fields and call buildState() exactly once,
        // in order — not as three independently launched recompute()
        // coroutines racing to write `_state.value` last (PERFORMANCE_AUDIT
        // 2026-09-16 F2). Gated on the handful of settings fields the agenda
        // actually reads: without that, any DataStore write re-buckets the
        // whole agenda — expanding a folder in Notebooks, pinning a notebook,
        // a colour change, a dismissed NEW badge. Mirrors `TreeInputs` in
        // VaultViewModels. Only rows with a SCHEDULED, a DEADLINE, or a bare
        // active timestamp: the agenda places notes purely by those dates, so
        // a note with none of them can never surface here and there is no
        // reason to hold one in memory.
        viewModelScope.launch(dispatchers.default) {
            combine(
                settingsRepository.settings.distinctUntilChangedBy { AgendaPrefs(it) },
                keywordsFlow,
                database.indexDao().plannedNotes().map { rows -> rows.map { it.toNoteMeta() } },
            ) { settings, kw, notes -> Triple(settings, kw, notes) }
                .collect { (settings, kw, notes) ->
                    prefs = settings
                    keywords = kw
                    matched = notes
                    _state.value = buildState()
                }
        }
    }

    // --- view state ---

    fun setTab(next: AgendaTab) {
        tab = next
        recompute()
    }

    fun toggleLevers() {
        leversOpen = !leversOpen
        recompute()
    }

    fun toggleOverdue() {
        overdueOpen = !overdueOpen
        recompute()
    }

    /** Writes whichever tab is currently showing, so Today and Upcoming keep independent choices. */
    fun setGrouping(grouping: AgendaGrouping) = persist {
        if (tab == AgendaTab.TODAY) it.setAgendaGroupingToday(grouping) else it.setAgendaGroupingUpcoming(grouping)
    }

    fun setStateFilter(filter: AgendaStateFilter) = persist {
        if (tab == AgendaTab.TODAY) it.setAgendaStateFilterToday(filter) else it.setAgendaStateFilterUpcoming(filter)
    }

    fun setShowTags(show: Boolean) = persist { it.setAgendaShowTags(show) }

    fun setShowFile(show: Boolean) = persist { it.setAgendaShowFile(show) }

    fun setShowTimestamps(show: Boolean) = persist { it.setAgendaShowTimestamps(show) }

    private fun persist(block: suspend (com.rrajath.grove.settings.SettingsRepository) -> Unit) {
        viewModelScope.launch { block(settingsRepository) }
    }

    /** Grows the future-day window on scroll; overdue and today never repage. */
    fun loadMoreDays() {
        if (loadingMore || windowDays >= MAX_WINDOW_DAYS) return
        loadingMore = true
        windowDays = (windowDays + PAGE_SIZE).coerceAtMost(MAX_WINDOW_DAYS)
        viewModelScope.launch(dispatchers.default) {
            _state.value = buildState()
            loadingMore = false
        }
    }

    private fun recompute() {
        viewModelScope.launch(dispatchers.default) { _state.value = buildState() }
    }

    // --- list construction ---

    private fun buildState(): AgendaUiState {
        val today = LocalDate.now()
        val p = prefs
        val active = keywords.active
        val isTodayTab = tab == AgendaTab.TODAY
        // Each tab keeps its own grouping and state filter (Settings § Agenda
        // levers), so changing one on Today never re-buckets or re-filters
        // Upcoming, and vice versa.
        val grouping = if (isTodayTab) p.agendaGroupingToday else p.agendaGroupingUpcoming
        val rawFilter = if (isTodayTab) p.agendaStateFilterToday else p.agendaStateFilterUpcoming
        // A persisted keyword filter can outlive the keyword itself (the user
        // edited todoKeywords); fall back rather than showing an empty list
        // with no chip selected.
        val filter = rawFilter
            .takeUnless { it is AgendaStateFilter.Keyword && it.name !in active }
            ?: AgendaStateFilter.Open
        val visible = matched.filter { AgendaBuckets.keep(it, filter) }

        val todayItems = AgendaBuckets.onDay(visible, today)
        val overdueItems = AgendaBuckets.overdue(visible, today)
        val futureItems = AgendaBuckets.upcoming(visible, today, windowDays)
        // Events (bare active timestamps) dated today, for the Today tab and the count.
        val todayEvents = AgendaBuckets.activeEventsOn(visible, today)

        val list = if (isTodayTab) todayItems else futureItems
        // Date grouping already puts the day in the section header; every other
        // grouping mixes days together, so the rows have to carry it themselves.
        val showDate = grouping != AgendaGrouping.DATE && !isTodayTab

        // Events only weave into the Date grouping (both tabs); Priority/Tag/File
        // are deliberate slices and stay planned-rows-only.
        val groups = if (grouping == AgendaGrouping.DATE) {
            dateGroups(visible, list, today, isTodayTab, windowDays, p)
        } else {
            AgendaBuckets.group(list, today, grouping, isTodayTab)
                .map { b ->
                    AgendaGroup(b.key, b.notes.size, b.notes.map { row(it, today, showDate, p) }.toImmutableList())
                }
        }

        return AgendaUiState(
            headerDay = today.dayOfWeek.getDisplayName(TextStyle.FULL, orgLocale()),
            headerDate = today.format(HEADER_DATE),
            todayCount = todayItems.size + todayEvents.size,
            tab = tab,
            leversOpen = leversOpen,
            overdueOpen = overdueOpen,
            overdue = overdueItems.map { row(it, today, showDate = true, p = p) }.toImmutableList(),
            groups = groups.toImmutableList(),
            grouping = grouping,
            stateFilter = filter,
            activeKeywords = active.toImmutableList(),
            showTags = p.agendaShowTags,
            showFile = p.agendaShowFile,
            showTimestamps = p.agendaShowTimestamps,
            swipeLeftAction = p.agendaSwipeLeftAction,
            swipeRightAction = p.agendaSwipeRightAction,
        )
    }

    /** One row's worth of "something on this day": a planned heading or a bare-timestamp event. */
    private sealed interface DayEntry {
        val meta: NoteMeta

        data class Planned(override val meta: NoteMeta) : DayEntry
        data class Event(override val meta: NoteMeta, val ts: OrgTimestamp) : DayEntry

        /** Untimed entries sort last within a day, matching [AgendaBuckets.BY_TIME]. */
        fun sortTime(): LocalTime = when (this) {
            is Planned -> (if (meta.scheduledDate != null) meta.scheduledTime else meta.deadlineTime) ?: LocalTime.MAX
            is Event -> ts.time ?: LocalTime.MAX
        }
    }

    /**
     * The "Group by · Date" buckets with bare-timestamp events woven in. Planned
     * headings keep their one-day-each rule; each event contributes one row per
     * day it covers, sorted into that day's bucket by time. A heading that is
     * both scheduled/deadlined and carries an active date lands twice only when
     * those dates differ — on the same day it is one plan, not a plan plus an
     * event, so the active-timestamp occurrence for that day is dropped.
     */
    private fun dateGroups(
        visible: List<NoteMeta>,
        planned: List<NoteMeta>,
        today: LocalDate,
        isTodayTab: Boolean,
        windowDays: Int,
        p: GroveSettings,
    ): List<AgendaGroup> {
        fun bucket(key: String, day: LocalDate, entries: List<DayEntry>): AgendaGroup {
            val sorted = entries.sortedWith(
                compareBy<DayEntry> { it.sortTime() }.thenBy { it.meta.title.lowercase() },
            )
            val rows = sorted.map { e ->
                when (e) {
                    is DayEntry.Planned -> row(e.meta, today, showDate = false, p = p)
                    is DayEntry.Event -> row(e.meta, today, showDate = false, p = p, activeTs = e.ts, eventDay = day)
                }
            }
            return AgendaGroup(key, rows.size, rows.toImmutableList())
        }

        /** Drops event occurrences whose heading is already planned for this same day. */
        fun eventsNotAlreadyPlanned(
            events: List<Pair<NoteMeta, OrgTimestamp>>,
            dayPlanned: List<NoteMeta>,
        ): List<Pair<NoteMeta, OrgTimestamp>> {
            val plannedKeys = dayPlanned.map { it.fileName to it.lineIndex }.toSet()
            return events.filter { (m, _) -> (m.fileName to m.lineIndex) !in plannedKeys }
        }

        if (isTodayTab) {
            val events = eventsNotAlreadyPlanned(AgendaBuckets.activeEventsOn(visible, today), planned)
            val entries = planned.map { DayEntry.Planned(it) } + events.map { DayEntry.Event(it.first, it.second) }
            return if (entries.isEmpty()) emptyList() else listOf(bucket("Scheduled today", today, entries))
        }

        val horizon = today.plusDays((windowDays - 1).coerceAtLeast(0).toLong())
        val plannedByDay = planned.groupBy { AgendaBuckets.whenDate(it)!! }
        val eventsByDay = generateSequence(today.plusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(horizon) }
            .associateWith { day -> eventsNotAlreadyPlanned(AgendaBuckets.activeEventsOn(visible, day), plannedByDay[day].orEmpty()) }
            .filterValues { it.isNotEmpty() }
        return (plannedByDay.keys + eventsByDay.keys).toSortedSet().map { day ->
            val entries = plannedByDay[day].orEmpty().map { DayEntry.Planned(it) } +
                eventsByDay[day].orEmpty().map { DayEntry.Event(it.first, it.second) }
            bucket(AgendaBuckets.dayLabel(day, today), day, entries)
        }
    }

    // --- swipe-to-act mutations ---

    fun setScheduled(fileName: String, lineIndex: Int, ts: OrgTimestamp?) =
        mutatePlanning(fileName, lineIndex) { doc, h -> OrgMutations.setScheduled(doc, h, ts) }

    fun setDeadline(fileName: String, lineIndex: Int, ts: OrgTimestamp?) =
        mutatePlanning(fileName, lineIndex) { doc, h -> OrgMutations.setDeadline(doc, h, ts) }

    /** Planning dates + the dedicated active line in one edit: what the Dates screen commits. */
    fun setPlanningDates(
        fileName: String,
        lineIndex: Int,
        scheduled: OrgTimestamp?,
        deadline: OrgTimestamp?,
        active: List<OrgTimestamp>,
    ) = mutatePlanning(fileName, lineIndex) { doc, h ->
        OrgMutations.setPlanningAndActiveTimestamps(doc, h, scheduled, deadline, active)
    }

    private fun mutatePlanning(fileName: String, lineIndex: Int, block: (OrgDocument, OrgHeadline) -> String) {
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            val doc = vault.open(fileName) ?: return@launch
            val headline = doc.headlineAtLine(lineIndex) ?: return@launch
            val newText = withContext(dispatchers.default) { block(doc, headline) }
            vault.save(fileName, newText)
            sync.requestReindex(fileName, newText, "agenda planning edit")
        }
    }

    /**
     * Swipe-to-done: sets the heading to the first configured done-type keyword
     * (never hardcoded "DONE", so a custom `todoKeywords` config is respected),
     * via org "mark done" semantics (`OrgMutations.markDone`: repeater advance
     * if the planning date repeats, else a CLOSED stamp).
     */
    fun markDone(fileName: String, lineIndex: Int) = toggleDone(fileName, lineIndex)

    /**
     * The row checkbox. Marks an open heading done, and reopens a done one,
     * reachable whenever the "Everything" filter is showing completed rows.
     */
    fun toggleDone(fileName: String, lineIndex: Int) {
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            val doc = vault.open(fileName) ?: return@launch
            val headline = doc.headlineAtLine(lineIndex) ?: return@launch
            // A keyword-less heading is a bare-timestamp event: nothing to complete,
            // and marking it done would fabricate a DONE keyword it never had.
            if (headline.keyword == null) return@launch
            if (headline.keyword in doc.keywords.done) {
                val newText = withContext(dispatchers.default) {
                    OrgMutations.reopen(doc, headline, doc.keywords.active.firstOrNull())
                }
                undoSnapshot = listOf(FileSnapshot(fileName, doc.text))
                vault.save(fileName, newText)
                sync.requestReindex(fileName, newText, "agenda toggle done")
                showSnack("Reopened")
                return@launch
            }
            val doneKeyword = doc.keywords.done.firstOrNull() ?: return@launch
            val settings = settingsRepository.settings.first()
            when (
                val result = AutoArchive.apply(vault, settings, doc, fileName, headline, doneKeyword, LocalDateTime.now())
            ) {
                is StateChangeResult.Plain -> {
                    undoSnapshot = listOf(FileSnapshot(fileName, doc.text))
                    vault.save(fileName, result.text)
                    sync.requestReindex(fileName, result.text, "agenda toggle done")
                    val next = result.doc.headlines.firstOrNull { it.lineIndex == lineIndex }
                        ?.let { nextRepeatOccurrence(headline, it) }
                    showSnack(markedDoneMessage(next, LocalDate.now()))
                }
                is StateChangeResult.Archived -> {
                    undoSnapshot = if (result.sourceFile == result.destFile) {
                        listOf(FileSnapshot(result.sourceFile, doc.text))
                    } else {
                        listOf(FileSnapshot(fileName, doc.text), FileSnapshot(result.destFile, result.destTextBefore))
                    }
                    vault.save(fileName, result.sourceText)
                    sync.requestReindex(fileName, result.sourceText, "agenda toggle done")
                    if (result.destFile != fileName) {
                        vault.save(result.destFile, result.destText)
                        sync.requestReindex(result.destFile, result.destText, "agenda toggle done")
                    }
                    showSnack("Marked done. Refiled to ${result.label}")
                }
            }
        }
    }

    /**
     * The done affordance on a keyword-less row with a repeater ([AgendaRow.repeaterKind]
     * non-null): advances only that row's own timestamp to its next occurrence, the
     * same clean mutation the notification's Complete action uses for this case --
     * no keyword, LOGBOOK, or LAST_REPEAT is touched, and [AutoArchive] never runs
     * since there's no keyword to archive on.
     */
    fun advanceRepeater(row: AgendaRow) {
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            val doc = vault.open(row.fileName) ?: return@launch
            val headline = doc.headlineAtLine(row.lineIndex) ?: return@launch
            val kind = row.repeaterKind
            val newText = withContext(dispatchers.default) {
                when (kind) {
                    PlanningKind.SCHEDULED, PlanningKind.DEADLINE ->
                        OrgMutations.advanceRepeatingPlanning(doc, headline, kind, LocalDateTime.now())
                    PlanningKind.ACTIVE ->
                        row.activeTs?.date?.let { OrgMutations.advanceActiveTimestamp(doc, headline, it, LocalDateTime.now()) }
                    null -> null
                }
            } ?: return@launch
            undoSnapshot = listOf(FileSnapshot(row.fileName, doc.text))
            vault.save(row.fileName, newText)
            sync.requestReindex(row.fileName, newText, "agenda advance repeater")
            val next = OrgParser.parse(newText, doc.keywords).headlines
                .firstOrNull { it.lineIndex == row.lineIndex }
                ?.let { nextRepeatOccurrence(headline, it) }
            showSnack(markedDoneMessage(next, LocalDate.now()))
        }
    }

    /** Swipe panel's "Note" action: org's C-c C-z, logged into the LOGBOOK drawer. */
    fun addNote(fileName: String, lineIndex: Int, note: String) {
        if (note.isBlank()) return
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            val doc = vault.open(fileName) ?: return@launch
            val headline = doc.headlineAtLine(lineIndex) ?: return@launch
            val newText = withContext(dispatchers.default) {
                val now = LocalDateTime.now()
                val stamp = OrgTimestamp(
                    now.toLocalDate(),
                    time = now.toLocalTime().withSecond(0).withNano(0),
                    active = false,
                )
                OrgMutations.appendLogbookNote(doc, headline, note.trim(), stamp)
            }
            vault.save(fileName, newText)
            sync.requestReindex(fileName, newText, "agenda note added")
            showSnack("Note added")
        }
    }

    /**
     * The overdue card's "Move to today": rewrites each overdue heading's
     * SCHEDULED to today, or its DEADLINE when that is the only date it has.
     * Time of day, repeater, and warning cookies all survive the move.
     *
     * Headings are rewritten highest-line-first within a file and the document
     * re-parsed between edits, since adding a planning line shifts every line
     * index below it.
     */
    fun moveOverdueToToday() {
        val rows = _state.value.overdue
        if (rows.isEmpty()) return
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            val today = LocalDate.now()
            val snapshots = mutableListOf<FileSnapshot>()
            var moved = 0

            for ((fileName, fileRows) in rows.groupBy { it.fileName }) {
                val doc = vault.open(fileName) ?: continue
                val original = doc.text
                var text = original
                withContext(dispatchers.default) {
                    for (r in fileRows.sortedByDescending { it.lineIndex }) {
                        val parsed = OrgParser.parse(text, doc.keywords)
                        val h = parsed.headlineAtLine(r.lineIndex) ?: continue
                        val scheduled = h.planning.scheduled
                        val deadline = h.planning.deadline
                        text = when {
                            scheduled != null && scheduled.date.isBefore(today) ->
                                OrgMutations.setScheduled(parsed, h, scheduled.copy(date = today))
                            scheduled == null && deadline != null && deadline.date.isBefore(today) ->
                                OrgMutations.setDeadline(parsed, h, deadline.copy(date = today))
                            else -> continue
                        }
                        moved++
                    }
                }
                if (text != original) {
                    snapshots += FileSnapshot(fileName, original)
                    vault.save(fileName, text)
                    sync.requestReindex(fileName, text, "agenda move overdue")
                }
            }

            if (snapshots.isEmpty()) return@launch
            undoSnapshot = snapshots
            overdueOpen = false
            recompute()
            showSnack("Moved $moved to today")
        }
    }

    fun undo() {
        val snaps = undoSnapshot
        if (snaps.isEmpty()) return
        undoSnapshot = emptyList()
        _snack.value = null
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            snaps.forEach {
                vault.save(it.fileName, it.text)
                sync.requestReindex(it.fileName, it.text, "agenda undo")
            }
        }
    }

    private fun showSnack(message: String) {
        val s = OutlineSnack(message, ++eventId)
        _snack.value = s
        viewModelScope.launch {
            delay(4200)
            if (_snack.value?.id == s.id) _snack.value = null
        }
    }

    companion object {
        val Factory = factory {
            AgendaViewModel(it.settingsRepository, it.keywords, it.database, it.vault, it.syncManager, it.dispatchers)
        }

        private const val INITIAL_WINDOW_DAYS = 14
        private const val PAGE_SIZE = 14

        // Safety cap: stops loadMoreDays() from growing forever if the user
        // scrolls through a long stretch with nothing scheduled.
        private const val MAX_WINDOW_DAYS = 366

        private val HEADER_DATE: DateTimeFormatter get() = LocalDates.formatter("MMMMd", "MMMM d")
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /**
         * Builds one agenda row from a matched [NoteMeta]. Pure (no instance
         * state), so it lives here for direct unit testing.
         *
         * When [activeTs] is set the row is an *event* placed on that bare
         * active timestamp: it renders its own time range and repeater, and
         * never the overdue / `⚑` deadline styling. It also gets a violet `●`
         * day chip *unless* [eventDay] is set — a non-null [eventDay] means the
         * caller is rendering the row under a day section whose header already
         * names the day, so the chip would just repeat it. `p.agendaShowTimestamps`
         * overrides that suppression, and separately adds the SCHEDULED date chip
         * on non-event rows even when the day is already implied — both stay
         * alongside the DEADLINE indicators, which always show regardless.
         * [scheduledTs]/[deadlineTs] on the row still carry the heading's real
         * planning so swipe-to-schedule prefills correctly.
         */
        internal fun row(
            m: NoteMeta,
            today: LocalDate,
            showDate: Boolean,
            p: GroveSettings,
            activeTs: OrgTimestamp? = null,
            eventDay: LocalDate? = null,
        ): AgendaRow {
            val scheduledTs = m.scheduled?.let { OrgTimestamp.parse(it) }
            val deadlineTs = m.deadline?.let { OrgTimestamp.parse(it) }
            val allActive = m.active
                ?.let { OrgTimestamp.parseAll(it).filter { ts -> ts.active } }
                .orEmpty()
                .toImmutableList()

            if (activeTs != null) {
                val meta = buildList {
                    // Omitted under a day section (eventDay set): the section
                    // header already names the day. agendaShowTimestamps
                    // overrides that suppression, per-row.
                    if (eventDay == null || p.agendaShowTimestamps) {
                        add(
                            AgendaMeta(
                                // eventDay is the day this occurrence is shown under, which for a
                                // ranged timestamp differs from activeTs.date (the range's start).
                                AgendaBuckets.dayLabel(eventDay ?: activeTs.date, today),
                                AgendaMetaTone.EVENT,
                                icon = AgendaMetaIcon.EVENT_DOT,
                            ),
                        )
                    }
                    activeTs.time?.let { start ->
                        val range = start.format(CLOCK) + (activeTs.endTime?.let { "–${it.format(CLOCK)}" } ?: "")
                        add(AgendaMeta(range, AgendaMetaTone.NORMAL))
                    }
                    activeTs.repeater?.let { add(AgendaMeta("↻ $it", AgendaMetaTone.MUTED)) }
                    if (p.agendaShowTags) {
                        m.inheritedTags.takeIf { it.isNotEmpty() }
                            ?.let { add(AgendaMeta(it.joinToString(":", ":", ":"), AgendaMetaTone.TAG)) }
                    }
                    if (p.agendaShowFile) add(AgendaMeta(m.fileName, AgendaMetaTone.MUTED))
                }
                return AgendaRow(
                    fileName = m.fileName,
                    lineIndex = m.lineIndex,
                    title = m.title,
                    keyword = m.keyword,
                    isDone = m.isDoneKeyword,
                    priority = m.priority,
                    meta = meta.toImmutableList(),
                    scheduledTs = scheduledTs,
                    deadlineTs = deadlineTs,
                    activeTs = activeTs,
                    activeTimestamps = allActive,
                )
            }

            // The timestamp that decides which day the row lands on.
            val anchor = scheduledTs ?: deadlineTs
            val anchorDate = anchor?.date
            val overdue = anchorDate != null && anchorDate.isBefore(today)
            // Deadline-only: the row's own date *is* the deadline, so it reads red.
            val deadlineOnly = deadlineTs != null && scheduledTs == null

            val meta = buildList {
                // agendaShowTimestamps adds the SCHEDULED date chip even under
                // Date grouping (where the day section already names the day) —
                // but never for a deadline-only row, whose "⚑ due" already covers it.
                if ((showDate || (p.agendaShowTimestamps && scheduledTs != null)) && anchorDate != null) {
                    val late = ChronoUnit.DAYS.between(anchorDate, today)
                    val text = (if (deadlineOnly) "⚑ " else "") +
                        if (overdue) "${anchorDate.format(AgendaBuckets.SHORT_DATE)} · ${late}d late"
                        else AgendaBuckets.dayLabel(anchorDate, today)
                    // Only a plain scheduled date (not overdue, not deadline-only)
                    // gets the calendar-icon treatment; overdue/deadline chips stay
                    // plain red text, unchanged.
                    add(
                        AgendaMeta(
                            text,
                            if (overdue || deadlineOnly) AgendaMetaTone.DANGER else AgendaMetaTone.NORMAL,
                            icon = if (overdue || deadlineOnly) AgendaMetaIcon.NONE else AgendaMetaIcon.CALENDAR,
                        ),
                    )
                } else if (deadlineOnly) {
                    add(AgendaMeta("⚑ due", AgendaMetaTone.DANGER))
                }
                anchor?.time?.let { start ->
                    val range = start.format(CLOCK) + (anchor.endTime?.let { "–${it.format(CLOCK)}" } ?: "")
                    add(AgendaMeta(range, AgendaMetaTone.NORMAL))
                }
                if (deadlineTs != null && scheduledTs != null) {
                    add(AgendaMeta("⚑ ${deadlineTs.date.format(AgendaBuckets.SHORT_DATE)}", AgendaMetaTone.DANGER))
                }
                (scheduledTs?.repeater ?: deadlineTs?.repeater)?.let {
                    add(AgendaMeta("↻ $it", AgendaMetaTone.MUTED))
                }
                if (p.agendaShowTags) {
                    // Own tags + ancestor tags, per org-mode tag inheritance (see
                    // OrgDocument.inheritedTags): a sub-heading's row must show its
                    // parents' tags too, not just its own.
                    m.inheritedTags.takeIf { it.isNotEmpty() }
                        ?.let { add(AgendaMeta(it.joinToString(":", ":", ":"), AgendaMetaTone.TAG)) }
                }
                if (p.agendaShowFile) add(AgendaMeta(m.fileName, AgendaMetaTone.MUTED))
            }

            return AgendaRow(
                fileName = m.fileName,
                lineIndex = m.lineIndex,
                title = m.title,
                keyword = m.keyword,
                isDone = m.isDoneKeyword,
                priority = m.priority,
                meta = meta.toImmutableList(),
                scheduledTs = scheduledTs,
                deadlineTs = deadlineTs,
                activeTimestamps = allActive,
            )
        }
    }
}
