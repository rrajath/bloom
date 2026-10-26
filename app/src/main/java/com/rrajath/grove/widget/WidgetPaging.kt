package com.rrajath.grove.widget

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * "+N more" paging for the Agenda widget's lists, kept pure so the math stays
 * testable on the JVM.
 *
 * Each list (an agenda day section, or a search view's single result list)
 * starts at one [PAGE] of rows and grows by one page per "+N more" tap. Glance's
 * `LazyColumn` ships every item inline in one RemoteViews binder call, capped
 * around 1MB (see `MAX_ROWS_PER_SECTION` in LedgerWidget.kt), so the rows
 * rendered across the *whole widget* never exceed [MAX_TOTAL_ROWS]: lists are
 * filled in order and, once the budget is spent, a truncated list keeps only
 * its "view all" link.
 */
object WidgetPaging {

    /** Rows per page, and per list before any "+N more" tap. */
    const val PAGE = 20

    /** Rows rendered across the whole widget, whatever has been expanded. */
    const val MAX_TOTAL_ROWS = 100

    /** The single list key of a search view (an agenda view keys by section). */
    const val SEARCH_LIST_KEY = "results"

    /**
     * How much of one list to render: its first [visible] rows, [remaining]
     * rows left out, and whether "+N more" is offered ([canLoadMore]: rows
     * remain and the widget-wide budget isn't spent). [remaining] > 0 always
     * gets a "view all".
     */
    data class Plan(val key: String, val visible: Int, val remaining: Int, val canLoadMore: Boolean)

    /**
     * Plans every list in display order. [lists] are (key, row count) pairs;
     * [pages] is how many pages each key has been expanded to (absent = 1).
     */
    fun plan(
        lists: List<Pair<String, Int>>,
        pages: Map<String, Int>,
        page: Int = PAGE,
        maxTotal: Int = MAX_TOTAL_ROWS,
    ): List<Plan> {
        var budget = maxTotal
        return lists.map { (key, size) ->
            val wanted = (pages[key] ?: 1).coerceAtLeast(1).toLong() * page
            val visible = minOf(size.toLong(), wanted, budget.toLong()).toInt()
            budget -= visible
            val remaining = size - visible
            Plan(key, visible, remaining, canLoadMore = remaining > 0 && budget > 0)
        }
    }

    /** [pages] with [key] grown by one page. */
    fun expand(pages: Map<String, Int>, key: String): Map<String, Int> =
        pages + (key to ((pages[key] ?: 1).coerceAtLeast(1) + 1))

    private val json = Json { ignoreUnknownKeys = true }

    /** Stored form of the per-list page counts (Glance state holds strings, not maps). */
    fun encode(pages: Map<String, Int>): String = json.encodeToString(pages)

    /** Inverse of [encode]; anything unreadable is "nothing expanded". */
    fun decode(text: String?): Map<String, Int> =
        text?.let { runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull() } ?: emptyMap()

    /**
     * The page counts that apply to [viewKey], given what's stored: expansion
     * belongs to the view it was made in ([storedViewKey]), so it reads as empty
     * once the widget shows another view (including a deleted saved search
     * falling back to the agenda).
     */
    fun pagesFor(viewKey: String, storedViewKey: String?, storedPages: String?): Map<String, Int> =
        if (storedViewKey == viewKey) decode(storedPages) else emptyMap()
}
