package com.rrajath.grove.widget

import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaRow
import java.time.LocalDate

/**
 * Which view an Agenda widget instance shows: the day-grouped agenda (the
 * default) or one saved search's results. Each widget stores only the saved
 * search's id (see [LedgerWidget]'s per-widget state), so a rename shows up
 * under the new name and a delete falls back to the agenda.
 *
 * Pure, so the picker list and the fallback stay testable on the JVM.
 */
object WidgetViews {

    /** One row in the view picker. [searchId] null is the agenda. */
    data class PickerItem(val searchId: String?, val label: String, val selected: Boolean)

    /**
     * The saved searches the picker offers, in their saved order. Quick Start
     * override rows ([QuickStartOverrides.ids]) are hidden, the same way the
     * Search screen's "Saved Searches" list hides them: they're the blank-state
     * cards' query storage, not searches the user saved.
     */
    fun selectable(saved: List<SavedSearch>): List<SavedSearch> =
        saved.filterNot { it.id in QuickStartOverrides.ids }

    /**
     * The saved search a widget whose stored selection is [selectedId] shows,
     * or null for the agenda: nothing stored, the search was deleted, or the id
     * now names a hidden Quick Start override.
     */
    fun resolve(selectedId: String?, saved: List<SavedSearch>): SavedSearch? =
        selectedId?.let { id -> selectable(saved).firstOrNull { it.id == id } }

    /**
     * "Agenda" ([agendaLabel]) first, then [selectable] searches, with exactly
     * one item marked selected: the resolved selection, so a dangling id marks
     * the agenda, matching what the widget itself falls back to.
     */
    fun pickerItems(agendaLabel: String, saved: List<SavedSearch>, selectedId: String?): List<PickerItem> {
        val current = resolve(selectedId, saved)?.id
        return listOf(PickerItem(null, agendaLabel, selected = current == null)) +
            selectable(saved).map { PickerItem(it.id, it.name, selected = it.id == current) }
    }

    /**
     * Saved-search results ([headings], already in the Search screen's order,
     * see `SearchResultOrder.flatHeadings`) as widget rows. Every row carries
     * its date chip, since there are no day sections to name the day.
     */
    fun searchRows(headings: List<NoteMeta>, today: LocalDate, settings: GroveSettings): List<AgendaRow> =
        headings.map { LedgerBuckets.widgetRow(it, today, showDate = true, settings) }
}
