package com.rrajath.grove.ui.search

import com.rrajath.grove.org.occurrenceCovering
import com.rrajath.grove.org.repeats
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.search.QueryMatcher
import com.rrajath.grove.ui.agenda.AgendaBuckets
import java.time.LocalDate
import java.time.LocalTime

/**
 * Search's `ad.N` view (Orgzly's agenda): the matched notes laid out by day
 * instead of by notebook. Pure, so the placement rules are JVM-testable; the
 * ViewModel maps each [Entry] to a result row.
 *
 * Placement follows the Agenda screen's rule for planned headings
 * ([AgendaBuckets.whenDate]): one day per heading, its SCHEDULED date, else
 * its DEADLINE. The one difference is a SCHEDULED date past the window: the
 * heading then lands on its DEADLINE instead, since that is what put it in
 * the window. A planned day before today goes to [OVERDUE_KEY]. Events (bare
 * active timestamps) are separate entries on every window day they cover,
 * and never overdue. Within a day, timed entries come first in time order,
 * then untimed ones, each by title. Days with nothing on them are left out.
 */
internal object SearchAgenda {

    const val OVERDUE_KEY = "overdue"

    /** One placement of a note: its planned day or one day of an event. */
    data class Entry(val note: NoteMeta, val time: LocalTime?)

    data class Day(
        /** [OVERDUE_KEY], or the ISO date: stable across recompositions. */
        val key: String,
        val label: String,
        val entries: List<Entry>,
    )

    fun build(notes: List<NoteMeta>, today: LocalDate, days: Int): List<Day> {
        val horizon = QueryMatcher.agendaHorizon(today, days)
        val overdue = mutableListOf<Pair<LocalDate, Entry>>()
        val byDay = sortedMapOf<LocalDate, MutableList<Entry>>()

        for (note in notes) {
            planned(note, horizon)?.let { (day, time) ->
                val entry = Entry(note, time)
                if (day.isBefore(today)) overdue += day to entry
                else byDay.getOrPut(day) { mutableListOf() } += entry
            }
            for (ts in note.activeTimestamps) {
                var day = maxOf(ts.date, today)
                // A repeating stamp recurs through the whole window; a plain one stops at its own end.
                val last = if (ts.repeats) horizon else minOf(ts.rangeEnd ?: ts.date, horizon)
                while (!day.isAfter(last)) {
                    val start = ts.occurrenceCovering(day)
                    if (start != null) {
                        // A ranged event's time belongs to each occurrence's first day only.
                        byDay.getOrPut(day) { mutableListOf() } += Entry(note, ts.time.takeIf { day == start })
                    }
                    day = day.plusDays(1)
                }
            }
        }

        val out = mutableListOf<Day>()
        if (overdue.isNotEmpty()) {
            val sorted = overdue.sortedWith(
                compareBy<Pair<LocalDate, Entry>> { it.first }.thenBy { it.second.note.title.lowercase() },
            )
            out += Day(OVERDUE_KEY, "Overdue", sorted.map { it.second })
        }
        byDay.forEach { (day, entries) ->
            out += Day(day.toString(), label(day, today), entries.sortedWith(BY_TIME))
        }
        return out
    }

    /** The planned day and time a heading lands on, or null when it has none in reach. */
    private fun planned(note: NoteMeta, horizon: LocalDate): Pair<LocalDate, LocalTime?>? {
        note.scheduledDate?.takeIf { !it.isAfter(horizon) }?.let { return it to note.scheduledTime }
        note.deadlineDate?.takeIf { !it.isAfter(horizon) }?.let { return it to note.deadlineTime }
        return null
    }

    /** Agenda's day label, with the date added to a near day so the header always names it. */
    private fun label(day: LocalDate, today: LocalDate): String {
        val base = AgendaBuckets.dayLabel(day, today)
        return if (base.contains(',')) base else "$base, ${day.format(AgendaBuckets.SHORT_DATE)}"
    }

    /** Timed first by time (untimed sort as [LocalTime.MAX]), then by title. */
    private val BY_TIME: Comparator<Entry> =
        compareBy<Entry> { it.time ?: LocalTime.MAX }.thenBy { it.note.title.lowercase() }
}
