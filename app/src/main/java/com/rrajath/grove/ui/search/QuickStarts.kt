package com.rrajath.grove.ui.search

import androidx.annotation.StringRes
import com.rrajath.grove.R
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.search.SavedSearch

/**
 * The four Quick Start searches (Search's blank-state cards), as one source for
 * both the Search screen and the Agenda widget's view picker.
 *
 * Each card runs its [QuickStartOverrides] row's query when one exists (the
 * user overwrote the card from the save dropdown), otherwise its built-in
 * search. Built-ins are either query text (Overdue, an OR across two date
 * fields that [SearchFilters] can't express) or a [SearchFilters] preset (the
 * other three, so the Search screen can light up the matching Filters chips).
 * [builtInQuery] names the exact query text a preset mirrors to (see
 * [FilterExpression]), which is what the widget runs.
 *
 * Pure, so it stays testable on the JVM.
 */
object QuickStarts {

    enum class Card(val id: String, @StringRes val labelRes: Int) {
        OVERDUE(QuickStartOverrides.OVERDUE_ID, R.string.quick_start_overdue),
        TODAY(QuickStartOverrides.TODAY_ID, R.string.quick_start_today),
        OPEN_TASKS(QuickStartOverrides.OPEN_TASKS_ID, R.string.quick_start_open_tasks),
        UNSCHEDULED(QuickStartOverrides.UNSCHEDULED_ID, R.string.quick_start_unscheduled),
        ;

        companion object {
            fun forId(id: String): Card? = entries.firstOrNull { it.id == id }
        }
    }

    /** The override row's query for [card], or null when the card is not overridden. */
    fun overrideQuery(card: Card, saved: List<SavedSearch>): String? =
        saved.firstOrNull { it.id == card.id }?.query

    /**
     * The card's built-in [SearchFilters], or null for a card whose built-in is
     * query text ([Card.OVERDUE]). [activeStates] are the vault's todo-type keywords.
     */
    fun builtInFilters(card: Card, activeStates: List<String>): SearchFilters? = when (card) {
        Card.OVERDUE -> null
        Card.TODAY -> SearchFilters(scheduled = DatePreset.TODAY)
        Card.OPEN_TASKS -> SearchFilters(states = activeStates.toSet())
        Card.UNSCHEDULED -> SearchFilters(
            states = activeStates.toSet(),
            scheduled = DatePreset.NO_DATE,
            deadline = DatePreset.NO_DATE,
        )
    }

    /**
     * The card's built-in search as query text. "Overdue" is open tasks whose
     * scheduled OR deadline date has passed: equivalent to
     * "(i.KW1 OR i.KW2 OR ...) AND (s.overdue OR d.overdue)", expanded into the
     * grammar's flat OR-of-AND-groups since it has no parens. The others are
     * their [builtInFilters] mirrored to query text.
     */
    fun builtInQuery(card: Card, activeStates: List<String>): String =
        builtInFilters(card, activeStates)?.let { FilterExpression.of(it) }
            ?: if (activeStates.isEmpty()) {
                "s.overdue OR d.overdue"
            } else {
                activeStates.flatMap { kw -> listOf("i.$kw s.overdue", "i.$kw d.overdue") }.joinToString(" OR ")
            }

    /** What [card] searches for right now: its override, else its built-in query. */
    fun query(card: Card, saved: List<SavedSearch>, activeStates: List<String>): String =
        overrideQuery(card, saved) ?: builtInQuery(card, activeStates)
}
