package com.rrajath.grove.widget

import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaBuckets
import com.rrajath.grove.ui.agenda.AgendaMeta
import com.rrajath.grove.ui.agenda.AgendaMetaTone
import com.rrajath.grove.ui.agenda.AgendaRow
import com.rrajath.grove.ui.agenda.AgendaViewModel
import kotlinx.collections.immutable.toImmutableList
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure bucketing for the "Widget A · ledger" home-screen widget
 * (`design/Grove.dc.html`'s `wGroups('a')`, adapted per the widget-specific spec:
 * grouped by day rather than priority, with priority-then-time ordering *within*
 * each day).
 *
 * Deliberately free of Glance/Android so it stays unit-testable on the JVM, the
 * same way [AgendaBuckets] is; the widget itself only renders what [build] returns.
 */
object LedgerBuckets {

    /** One widget section: a header key/count and its already-sorted rows. */
    data class Section(val key: String, val count: Int, val rows: List<AgendaRow>)

    /** No-priority sort key, so unprioritised rows (and events) fall after A/B/C. */
    private const val NO_PRIORITY_SORT_KEY = "Z"

    /** One row's worth of "something on this day": a planned heading or a bare-timestamp event. */
    private sealed interface DayEntry {
        val meta: NoteMeta

        data class Planned(override val meta: NoteMeta) : DayEntry
        data class Event(override val meta: NoteMeta, val ts: OrgTimestamp) : DayEntry

        fun priorityKey(): String = meta.priority ?: NO_PRIORITY_SORT_KEY

        /** Untimed entries sort last within a day. */
        fun sortTime(): LocalTime = when (this) {
            is Planned -> (if (meta.scheduledDate != null) meta.scheduledTime else meta.deadlineTime) ?: LocalTime.MAX
            is Event -> ts.time ?: LocalTime.MAX
        }
    }

    /** Within a day: priority (A/B/C/none), then time, then title — the widget's own order. */
    private val BY_DAY: Comparator<DayEntry> =
        compareBy<DayEntry> { it.priorityKey() }.thenBy { it.sortTime() }.thenBy { it.meta.title.lowercase() }

    /**
     * Overdue (unbounded, oldest-first — same as the Agenda screen) first, then
     * one section per day from [today] through [today] + [windowDays] - 1 that
     * actually has something on it, in date order. Bare active-timestamp events
     * are woven into their day alongside planned headings (a multi-day event
     * shows on each day it spans); an event never ages into Overdue. A day with
     * nothing on it is omitted entirely rather than rendered empty.
     */
    fun build(notes: List<NoteMeta>, today: LocalDate, windowDays: Int, settings: GroveSettings): List<Section> {
        val overdueNotes = AgendaBuckets.overdue(notes, today)
        val overdueSection = if (overdueNotes.isEmpty()) null else Section(
            key = "Overdue",
            count = overdueNotes.size,
            rows = overdueNotes.map { widgetRow(it, today, showDate = true, settings) },
        )

        val horizon = today.plusDays((windowDays - 1).coerceAtLeast(0).toLong())
        val plannedByDay = notes
            .filter {
                val d = AgendaBuckets.whenDate(it)
                d != null && !d.isBefore(today) && !d.isAfter(horizon)
            }
            .groupBy { AgendaBuckets.whenDate(it)!! }

        val daySections = generateSequence(today) { it.plusDays(1) }
            .takeWhile { !it.isAfter(horizon) }
            .mapNotNull { day ->
                val entries = (
                    plannedByDay[day].orEmpty().map { DayEntry.Planned(it) } +
                        AgendaBuckets.activeEventsOn(notes, day).map { DayEntry.Event(it.first, it.second) }
                    ).sortedWith(BY_DAY)
                if (entries.isEmpty()) return@mapNotNull null
                Section(
                    key = dayHeader(day, today),
                    count = entries.size,
                    rows = entries.map { e ->
                        when (e) {
                            is DayEntry.Planned -> widgetRow(e.meta, today, showDate = false, settings)
                            is DayEntry.Event ->
                                widgetRow(e.meta, today, showDate = false, settings, activeTs = e.ts, eventDay = day)
                        }
                    },
                )
            }
            .toList()

        return listOfNotNull(overdueSection) + daySections
    }

    /**
     * Builds one widget row from [AgendaViewModel.row], then overrides its
     * tags/file/timestamps/priority display with the widget's own settings —
     * deliberately independent of [GroveSettings.agendaShowTags] /
     * [GroveSettings.agendaShowFile] / [GroveSettings.agendaShowTimestamps],
     * which govern the in-app Agenda screen instead (Settings § Agenda › Widget).
     *
     * [activeTs]/[eventDay] mark the row as a bare-timestamp event placed on that
     * day (violet `● <day>` chip, its own time range, no done circle in the widget).
     */
    internal fun widgetRow(
        m: NoteMeta,
        today: LocalDate,
        showDate: Boolean,
        settings: GroveSettings,
        activeTs: OrgTimestamp? = null,
        eventDay: LocalDate? = null,
    ): AgendaRow {
        val rowSettings = settings.copy(
            agendaShowTags = settings.agendaWidgetShowTags,
            agendaShowFile = false,
            agendaShowTimestamps = false,
        )
        val row = AgendaViewModel.row(m, today, showDate, p = rowSettings, activeTs = activeTs, eventDay = eventDay)
        val meta = if (settings.agendaWidgetShowFileName) {
            (row.meta + AgendaMeta(contractFileName(m.fileName), AgendaMetaTone.MUTED)).toImmutableList()
        } else {
            row.meta
        }
        return row.copy(meta = meta, priority = row.priority.takeIf { settings.agendaWidgetShowPriority })
    }

    /**
     * Contracts a nested vault path to just its folders' initials, keeping the
     * file's own name whole: `"Work/Projects/meeting-notes.org"` →
     * `"W/P/meeting-notes.org"`. A top-level file (no `/`) is returned as-is.
     */
    fun contractFileName(fileName: String): String {
        val parts = fileName.split("/")
        if (parts.size <= 1) return fileName
        return parts.dropLast(1).joinToString("/") { it.take(1) } + "/" + parts.last()
    }

    /**
     * Splits [rows] into what a widget should render (at most [max]) and how many
     * were cut. Pulled out of [LedgerWidget]'s composition so it stays testable on
     * the JVM: Glance's `LazyColumn` ships every item inline in a single RemoteViews
     * binder call (there's no out-of-process adapter like classic AppWidget
     * ListViews), which Android caps around 1MB, so an unbounded section can take
     * the whole widget down.
     */
    fun truncate(rows: List<AgendaRow>, max: Int): Pair<List<AgendaRow>, Int> {
        val visible = rows.take(max)
        return visible to (rows.size - visible.size)
    }

    /** "Today · Aug 5" / "Tomorrow · Aug 6" / plain "Aug 7" past that. */
    fun dayHeader(day: LocalDate, today: LocalDate): String {
        val short = day.format(AgendaBuckets.SHORT_DATE)
        return when (day) {
            today -> "Today · $short"
            today.plusDays(1) -> "Tomorrow · $short"
            else -> short
        }
    }
}
