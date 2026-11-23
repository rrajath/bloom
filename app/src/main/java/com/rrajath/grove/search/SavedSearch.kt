package com.rrajath.grove.search

import android.content.Context
import com.rrajath.grove.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedSearch(
    val id: String,
    val name: String,
    val query: String,
)

object SavedSearchSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(searches: List<SavedSearch>): String = json.encodeToString(Wrapper(searches))

    fun decode(text: String): List<SavedSearch> =
        runCatching { json.decodeFromString<Wrapper>(text).searches }.getOrDefault(emptyList())

    @Serializable
    private data class Wrapper(val searches: List<SavedSearch>)
}

/**
 * PRD §5.5 default saved searches. [all] keeps the English names (tests, and the
 * ids and queries never change); [localized] is what a fresh install shows, named
 * in the UI language until the first write persists them.
 */
object DefaultSavedSearches {
    // i18n-begin: English reference copy; the app shows localized()
    val all = listOf(
        SavedSearch("builtin-scheduled-today", "Scheduled Today", "s.eq.today"),
        SavedSearch("builtin-all-todo", "All TODO", "i.todo"),
        SavedSearch("builtin-this-week", "This Week", "s.7d"),
    )
    // i18n-end

    private val nameRes = mapOf(
        "builtin-scheduled-today" to R.string.search_default_scheduled_today,
        "builtin-all-todo" to R.string.search_default_all_todo,
        "builtin-this-week" to R.string.search_default_this_week,
    )

    fun localized(context: Context): List<SavedSearch> =
        all.map { s -> nameRes[s.id]?.let { s.copy(name = context.getString(it)) } ?: s }
}

/**
 * Reserved [SavedSearch] ids for the blank-state Quick Start cards (Overdue,
 * Today, Open tasks, Unscheduled). Those cards normally build their query
 * dynamically (see SearchScreen's BlankState); overwriting one from the star
 * button's save dropdown persists a row under its reserved id here instead of
 * a fresh saved search, so the card's own query is what changes. Rows with
 * these ids are hidden from the "Saved Searches" list and are never seeded by
 * default — absent means "use the card's built-in dynamic query."
 */
object QuickStartOverrides {
    const val OVERDUE_ID = "quickstart-overdue"
    const val TODAY_ID = "quickstart-today"
    const val OPEN_TASKS_ID = "quickstart-open-tasks"
    const val UNSCHEDULED_ID = "quickstart-unscheduled"

    val ids = setOf(OVERDUE_ID, TODAY_ID, OPEN_TASKS_ID, UNSCHEDULED_ID)
}
