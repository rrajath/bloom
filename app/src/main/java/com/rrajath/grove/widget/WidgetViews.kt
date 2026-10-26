package com.rrajath.grove.widget

import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaRow
import com.rrajath.grove.ui.search.QuickStarts
import java.time.LocalDate

/**
 * Which view an Agenda widget instance shows: the day-grouped agenda (the
 * default), one of the four Quick Start searches, or one saved search. Each
 * widget stores only the view's id (see [LedgerWidget]'s per-widget state): a
 * Quick Start's reserved [QuickStartOverrides] id, or the saved search's id. So
 * a rename shows up under the new name, a delete falls back to the agenda, and
 * a Quick Start always runs its current query (override, else built-in).
 *
 * Pure, so the picker list and the fallback stay testable on the JVM. Quick
 * Start names come in through [QuickStartName] because they live in string
 * resources.
 */
object WidgetViews {

    /** A Quick Start's display name, normally `context.getString(card.labelRes)`. */
    fun interface QuickStartName {
        fun of(card: QuickStarts.Card): String
    }

    /** A selectable search view: [id] is what the widget stores, [query] what it runs. */
    data class SearchView(val id: String, val name: String, val query: String)

    /**
     * One row in the view picker. [searchId] null is the agenda. [subtitle] is
     * the search expression, or the agenda's window summary.
     */
    data class PickerItem(val searchId: String?, val label: String, val subtitle: String, val selected: Boolean)

    /**
     * The saved searches the picker offers, in their saved order. Quick Start
     * override rows ([QuickStartOverrides.ids]) are left out here, the same way
     * the Search screen's "Saved Searches" list leaves them out: they're the
     * Quick Start cards' query storage, offered through [searchViews]' Quick
     * Start entries instead.
     */
    fun selectable(saved: List<SavedSearch>): List<SavedSearch> =
        saved.filterNot { it.id in QuickStartOverrides.ids }

    /**
     * Every search view, in picker order: the four Quick Starts (each with its
     * override query when one exists, else its built-in; [activeStates] are the
     * vault's todo-type keywords) then the [selectable] saved searches.
     */
    fun searchViews(saved: List<SavedSearch>, activeStates: List<String>, names: QuickStartName): List<SearchView> =
        QuickStarts.Card.entries.map { card ->
            SearchView(card.id, names.of(card), QuickStarts.query(card, saved, activeStates))
        } + selectable(saved).map { SearchView(it.id, it.name, it.query) }

    /**
     * The search view a widget whose stored selection is [selectedId] shows, or
     * null for the agenda: nothing stored, or the saved search was deleted.
     */
    fun resolve(
        selectedId: String?,
        saved: List<SavedSearch>,
        activeStates: List<String>,
        names: QuickStartName,
    ): SearchView? = selectedId?.let { id -> searchViews(saved, activeStates, names).firstOrNull { it.id == id } }

    /**
     * "Agenda" ([agendaLabel], subtitled [agendaSubtitle]) first, then
     * [searchViews], each subtitled with its query. Exactly one item is marked
     * selected: the resolved selection, so a dangling id marks the agenda,
     * matching what the widget itself falls back to.
     */
    fun pickerItems(
        agendaLabel: String,
        agendaSubtitle: String,
        saved: List<SavedSearch>,
        activeStates: List<String>,
        names: QuickStartName,
        selectedId: String?,
    ): List<PickerItem> {
        val views = searchViews(saved, activeStates, names)
        val current = selectedId?.takeIf { id -> views.any { it.id == id } }
        return listOf(PickerItem(null, agendaLabel, agendaSubtitle, selected = current == null)) +
            views.map { PickerItem(it.id, it.name, it.query, selected = it.id == current) }
    }

    /**
     * Search results ([headings], already in the Search screen's order, see
     * `SearchResultOrder.flatHeadings`) as widget rows. Every row carries its
     * date chip, since there are no day sections to name the day.
     */
    fun searchRows(headings: List<NoteMeta>, today: LocalDate, settings: GroveSettings): List<AgendaRow> =
        headings.map { LedgerBuckets.widgetRow(it, today, showDate = true, settings) }
}
