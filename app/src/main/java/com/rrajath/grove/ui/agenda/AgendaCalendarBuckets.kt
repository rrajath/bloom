package com.rrajath.grove.ui.agenda

import com.rrajath.grove.org.orgLocale
import com.rrajath.grove.org.repeats
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.AgendaCalendarView
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.WeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.concurrent.ConcurrentHashMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

internal fun WeekStart.firstDay(): DayOfWeek = resolve(WeekFields.of(orgLocale()).firstDayOfWeek)

internal fun AgendaCalendarView.weeks(anchor: LocalDate, firstDay: DayOfWeek): List<List<LocalDate?>> {
    val start = anchor.with(TemporalAdjusters.previousOrSame(firstDay))
    fun week(from: LocalDate): List<LocalDate?> = List(7) { from.plusDays(it.toLong()) }
    return when (this) {
        AgendaCalendarView.WEEK -> listOf(week(start))
        AgendaCalendarView.TWO_WEEKS -> listOf(week(start), week(start.plusWeeks(1)))
        AgendaCalendarView.MONTH -> {
            val month = YearMonth.from(anchor)
            val lead = WeekStart.leadingBlanks(month.atDay(1).dayOfWeek, firstDay)
            val days: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)
            (days + List((7 - days.size % 7) % 7) { null }).chunked(7)
        }
    }
}

internal fun AgendaCalendarView.shift(anchor: LocalDate, steps: Long): LocalDate =
    if (this == AgendaCalendarView.MONTH) anchor.plusMonths(steps) else anchor.plusWeeks(steps)

/** Rows per day of a calendar range, memoized; the owner drops the instance when its inputs change. */
internal class AgendaCalendarBuckets {
    private val ranges = ConcurrentHashMap<Triple<LocalDate, LocalDate, LocalDate>, ImmutableMap<LocalDate, ImmutableList<AgendaRow>>>()

    fun byDay(
        visible: List<NoteMeta>,
        plannable: List<NoteMeta>,
        days: List<LocalDate>,
        today: LocalDate,
        p: GroveSettings,
    ) = ranges.getOrPut(Triple(days.first(), days.last(), today)) {
        val plannedByDay = plannable.groupBy(AgendaBuckets::whenDate)
        val repeating = plannable.filter { it.scheduledTs?.repeats == true || it.deadlineTs?.repeats == true }
        val withEvents = visible.filter { it.activeTimestamps.isNotEmpty() }
        days.associateWith { rowsOn(it, plannedByDay[it].orEmpty(), repeating, withEvents, today, p) }
            .filterValues { it.isNotEmpty() }
            .toImmutableMap()
    }

    private fun rowsOn(
        day: LocalDate,
        plannedOnDay: List<NoteMeta>,
        repeating: List<NoteMeta>,
        withEvents: List<NoteMeta>,
        today: LocalDate,
        p: GroveSettings,
    ): ImmutableList<AgendaRow> {
        val planned = plannedOnDay.sortedWith(AgendaBuckets.BY_TIME)
        val repeats = AgendaBuckets.repeatsOn(repeating, day, today)
        val taken = (planned + repeats.map { it.meta }).mapTo(HashSet()) { it.fileName to it.lineIndex }
        val events = AgendaBuckets.activeEventsOn(withEvents, day)
            .filter { (m, _) -> (m.fileName to m.lineIndex) !in taken }
            .sortedBy { (_, ts) -> ts.time ?: LocalTime.MAX }
        return (
            planned.map { AgendaViewModel.row(it, today, showDate = false, p = p) } +
                repeats.map {
                    AgendaViewModel.row(it.meta, today, showDate = false, p = p, placement = RowPlacement.Repeat(it.kind, it.ts))
                } +
                events.map { (m, ts) ->
                    AgendaViewModel.row(m, today, showDate = false, p = p, activeTs = ts, eventDay = day)
                }
            ).toImmutableList()
    }
}
