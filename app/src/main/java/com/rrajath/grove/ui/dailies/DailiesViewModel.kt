package com.rrajath.grove.ui.dailies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.capture.FilenamePattern
import com.rrajath.grove.dailies.DailiesRepository
import com.rrajath.grove.dailies.DailyNoteLookup
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.ui.vault.factory
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class DailiesNavState(
    val date: LocalDate,
    val fileName: String,
    val exists: Boolean,
    val isToday: Boolean,
    val nextDate: LocalDate,
    val previousDate: LocalDate,
    val existingDates: Set<LocalDate>,
)

/**
 * Date navigation state for `DailyNoteScreen`, which switches days in place (one
 * screen instance, one ViewModel) rather than re-navigating per date.
 *
 * Day-to-day switching has to feel instant, so nothing on that path touches the
 * disk: the sorted list of existing days is built once by [refresh] (a listing of
 * the dailies folder only, see FileStore.listDir) and every [select] derives the
 * new [DailiesNavState] from it synchronously. The documents for the selected day
 * and both of its neighbours are parsed ahead of time into [docCache], so the screen
 * can render the next/previous day from memory the moment it's selected.
 *
 * Before that index exists (opening the screen), [select] doesn't wait for it: a
 * [DailyNoteLookup] answers for the one requested day (warmed from the drawer, or a
 * single-file lookup), and a provisional [DailiesNavState] with plain ±1-day
 * neighbours and no date-picker dots is published until the index replaces it.
 */
class DailiesViewModel(
    private val vaultFlow: StateFlow<Vault?>,
    private val settings: SettingsSource,
    private val dispatchers: AppDispatchers,
    private val lookup: DailyNoteLookup? = null,
    /** [count] fresh vault-unique IDs ([com.rrajath.grove.org.OrgIdAllocator] in the app). */
    private val orgIds: suspend (count: Int) -> List<String> = { n -> List(n) { newOrgId() } },
) : ViewModel() {

    private val _state = MutableStateFlow<DailiesNavState?>(null)
    val state: StateFlow<DailiesNavState?> = _state

    /** True once [refresh] has observed a null [vaultFlow]: distinguishes "no sync folder
     *  configured" from "still loading" for a [state] that is null either way. */
    private val _vaultMissing = MutableStateFlow(false)
    val vaultMissing: StateFlow<Boolean> = _vaultMissing

    /** Everything [select] needs to resolve a day without I/O. [existing] is empty (and
     *  [parseable] false) when the filename pattern can't be reverse-parsed, in which
     *  case [select] falls back to [resolveSlow]. */
    private class DayIndex(
        val vault: Vault,
        val repo: DailiesRepository,
        val settings: GroveSettings,
        val existing: List<LocalDate>,
        val existingSet: Set<LocalDate>,
        val parseable: Boolean,
    )

    private var index: DayIndex? = null
    private var currentDate: LocalDate? = null
    private var refreshJob: Job? = null
    private var slowJob: Job? = null
    private var quickJob: Job? = null

    /** fileName -> parsed document, for the selected day and its neighbours. Only touched
     *  on the main thread (the prefetch writes back after its IO hop). */
    private val docCache = object : LinkedHashMap<String, OrgDocument>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OrgDocument>?) = size > 12
    }

    /** Delegates to the [DailiesRepository]/settings snapshot [refresh] last resolved, so the
     *  empty-day "start typing" affordance can seed the header template without this screen
     *  needing its own [Vault] access. */
    suspend fun expandedHeaderFor(date: LocalDate): com.rrajath.grove.capture.ExpandedTemplate {
        val idx = index ?: return com.rrajath.grove.capture.ExpandedTemplate("", 0)
        val template = idx.settings.dailiesHeaderTemplate
        val ids = orgIds(maxOf(1, com.rrajath.grove.capture.PlaceholderExpander.idCount(template)))
        return idx.repo.expandHeaderTemplate(template, date, ids)
    }

    /** The prefetched parse of [fileName], if the prefetch has reached it. */
    fun cachedDocument(fileName: String): OrgDocument? = docCache[fileName]

    /**
     * Make [date] the current day. Returns its [DailiesNavState] synchronously once the
     * day index exists (always, after the first [refresh]). Before that, returns the
     * provisional state when [lookup] already holds [date], else null and [state]
     * updates when the lookup (or the index) lands. Never starts a listing itself:
     * the screen calls [refresh] on every resume, including the first.
     */
    fun select(date: LocalDate): DailiesNavState? {
        currentDate = date
        val idx = index ?: return selectProvisional(date)
        quickJob?.cancel()
        if (!idx.parseable) {
            resolveSlow(idx, date)
            return null
        }
        val nav = navFor(idx, date)
        _state.value = nav
        prefetch(idx, nav)
        return nav
    }

    /**
     * (Re)build the day index in the background (on first use, after a save, and on
     * screen resume, since a sync may have added days), then re-publish the current day.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val vault = vaultFlow.value ?: run {
                _vaultMissing.value = true
                _state.value = null
                return@launch
            }
            _vaultMissing.value = false
            val s = settings.settings.first()
            val repo = DailiesRepository(vault.fileStore())
            val existing = withContext(dispatchers.default) {
                repo.existingDates(s.dailiesDirectory, s.dailiesFilenamePattern)
            }
            val parseable = FilenamePattern.toDateRegex(s.dailiesFilenamePattern) != null
            index = DayIndex(vault, repo, s, existing, existing.toSet(), parseable)
            currentDate?.let { select(it) }
        }
    }

    /**
     * [fileName] (the note for [date]) was just written. Updates the index in place so
     * the day reads as existing right away (a brand-new note's day would otherwise show
     * the empty state until the background re-listing lands) and drops the stale parse.
     * Re-lists only when the save created the day (or no index exists yet): re-saving an
     * existing day, which the idle autosave does every few seconds while typing, can't
     * change the index, and external changes are picked up by the resume refresh.
     */
    fun noteSaved(date: LocalDate, fileName: String) {
        docCache.remove(fileName)
        val idx = index
        val newDay = idx == null || (idx.parseable && date !in idx.existingSet)
        if (idx != null && idx.parseable && date !in idx.existingSet) {
            val existing = (idx.existing + date).sorted()
            index = DayIndex(idx.vault, idx.repo, idx.settings, existing, existing.toSet(), true)
        }
        currentDate?.let { select(it) }
        if (newDay) refresh()
    }

    /** Keep [docCache] current with a document the screen already holds (e.g. after a
     *  Read-mode checkbox toggle), so coming back to that day doesn't flash an old parse. */
    fun rememberDocument(fileName: String, document: OrgDocument) {
        if (fileName in docCache) docCache[fileName] = document
    }

    private fun navFor(idx: DayIndex, date: LocalDate): DailiesNavState {
        val s = idx.settings
        val existing = idx.existing
        val today = LocalDate.now()
        // Forward from today always lands on tomorrow, existing or not -- today is
        // the one date the user is always actively adding to, so skip-to-next-existing
        // would trap them on today's own note. From any other date (including a future
        // one already reached this way), forward instead jumps to the nearest existing
        // entry ahead of it, only falling back to a plain +1 day when there isn't one.
        val next = if (date == today) {
            date.plusDays(1)
        } else {
            existing.firstOrNull { it > date } ?: date.plusDays(1)
        }
        return DailiesNavState(
            date = date,
            fileName = idx.repo.resolveFileName(s.dailiesDirectory, s.dailiesFilenamePattern, date),
            exists = date in idx.existingSet,
            isToday = date == today,
            nextDate = next,
            previousDate = existing.lastOrNull { it < date } ?: date.minusDays(1),
            existingDates = idx.existingSet,
        )
    }

    private fun selectProvisional(date: LocalDate): DailiesNavState? {
        quickJob?.cancel()
        val lookup = lookup ?: return null
        lookup.cached(date)?.let { return publishProvisional(it) }
        quickJob = viewModelScope.launch {
            val result = lookup.resolve(date) ?: return@launch
            // The index may have landed (it wins) or the user moved on meanwhile.
            if (index == null && currentDate == date) publishProvisional(result)
        }
        return null
    }

    private fun publishProvisional(r: DailyNoteLookup.Result): DailiesNavState {
        r.document?.let { docCache[r.fileName] = it }
        val nav = DailiesNavState(
            date = r.date,
            fileName = r.fileName,
            exists = r.exists,
            isToday = r.date == LocalDate.now(),
            nextDate = r.date.plusDays(1),
            previousDate = r.date.minusDays(1),
            existingDates = if (r.exists) setOf(r.date) else emptySet(),
        )
        _state.value = nav
        return nav
    }

    /** Unparseable-pattern fallback: there's no index to derive from, so existence and
     *  the previous day come from direct (bounded) lookups instead. */
    private fun resolveSlow(idx: DayIndex, date: LocalDate) {
        slowJob?.cancel()
        slowJob = viewModelScope.launch {
            val s = idx.settings
            val nav = withContext(dispatchers.default) {
                DailiesNavState(
                    date = date,
                    fileName = idx.repo.resolveFileName(s.dailiesDirectory, s.dailiesFilenamePattern, date),
                    exists = idx.repo.existsForDate(s.dailiesDirectory, s.dailiesFilenamePattern, date),
                    isToday = date == LocalDate.now(),
                    nextDate = date.plusDays(1),
                    previousDate = idx.repo.previousExistingDate(s.dailiesDirectory, s.dailiesFilenamePattern, date),
                    existingDates = emptySet(),
                )
            }
            _state.value = nav
            prefetch(idx, nav)
        }
    }

    /** Parse the current day and both neighbours into [docCache], current day first. */
    private fun prefetch(idx: DayIndex, nav: DailiesNavState) {
        val s = idx.settings
        val targets = listOf(nav.date, nav.previousDate, nav.nextDate)
            .filter { !idx.parseable || it in idx.existingSet }
            .map { idx.repo.resolveFileName(s.dailiesDirectory, s.dailiesFilenamePattern, it) }
            .filter { it !in docCache }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            for (fileName in targets) {
                val doc = withContext(dispatchers.io) { runCatching { idx.vault.open(fileName) }.getOrNull() }
                if (doc != null) docCache[fileName] = doc
            }
        }
    }

    companion object {
        val Factory = factory {
            DailiesViewModel(it.vault, it.settingsRepository, it.dispatchers, it.dailyNoteLookup, it.orgIdAllocator::allocate)
        }
    }
}
