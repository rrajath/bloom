package com.rrajath.grove.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.org.orgLocale
import com.rrajath.grove.settings.AgendaCalendarView
import com.rrajath.grove.settings.WeekStart
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.NoteRef
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val DAY_NUMBER_HEIGHT = 26.dp
private const val MORE_LINE_HEIGHT = 12f
private val CLOCK = DateTimeFormatter.ofPattern("H:mm")

@Composable
internal fun AgendaCalendar(
    state: AgendaUiState,
    onCalendar: (LocalDate, AgendaCalendarView) -> Unit,
    onOpenNote: (NoteRef) -> Unit,
) {
    val c = MaterialTheme.grove
    val today = LocalDate.now()
    val anchor = state.anchor
    val view = state.calendarView
    val locale = orgLocale()
    val firstDay = remember(state.weekStart, locale) { state.weekStart.firstDay() }
    val weeks = remember(anchor, view, firstDay) { view.weeks(anchor, firstDay) }
    val title = if (view == AgendaCalendarView.MONTH) {
        remember(anchor) { LocalDates.format(YearMonth.from(anchor), "yMMMM", "MMMM yyyy") }
    } else {
        val (first, last) = remember(weeks) {
            val days = weeks.flatten().filterNotNull()
            LocalDates.format(days.first(), "MMMd", "MMM d") to LocalDates.format(days.last(), "MMMd", "MMM d")
        }
        stringResource(R.string.agenda_calendar_range, first, last)
    }
    val expanded = weeks.size <= 2

    Column(Modifier.fillMaxSize().padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                Modifier.weight(1f),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BarButton(stringResource(view.labelRes), 12.5.sp) {
                onCalendar(anchor, AgendaCalendarView.entries[(view.ordinal + 1) % AgendaCalendarView.entries.size])
            }
            BarButton(stringResource(R.string.agenda_day_today), 12.5.sp) { onCalendar(today, view) }
            BarButton("‹") { onCalendar(view.shift(anchor, -1), view) }
            BarButton("›") { onCalendar(view.shift(anchor, 1), view) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            WeekStart.weekdays(firstDay).forEach { weekday ->
                Text(
                    weekday.getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f),
                    fontFamily = PlexMono, fontSize = 10.5.sp, color = c.ink3, textAlign = TextAlign.Center,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val available = (maxHeight / weeks.size - DAY_NUMBER_HEIGHT).value
            Column(Modifier.fillMaxSize()) {
                weeks.forEach { week ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        week.forEach { day ->
                            val cell = Modifier.weight(1f).fillMaxHeight()
                            if (day == null) {
                                Spacer(cell)
                            } else {
                                DayCell(
                                    day, state.calendar[day] ?: persistentListOf(), day == today, available, expanded, state.calendarShowTimes, cell, onOpenNote,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, fontSize: TextUnit = 18.sp, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = fontSize, color = c.ink2)
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    rows: ImmutableList<AgendaRow>,
    isToday: Boolean,
    available: Float,
    expanded: Boolean,
    showTimes: Boolean,
    modifier: Modifier,
    onOpenNote: (NoteRef) -> Unit,
) {
    val c = MaterialTheme.grove
    val shown = remember(rows, available, expanded, showTimes) { fitCount(rows, available, expanded, showTimes) }
    Box(modifier.clipToBounds().padding(2.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (isToday) c.accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 11.sp,
                    color = if (isToday) c.accentInk else c.ink,
                )
            }
            rows.take(shown).forEach { EventLine(it, expanded, showTimes, onOpenNote) }
            if (rows.size > shown) {
                Text("+${rows.size - shown}", fontFamily = PlexMono, fontSize = 9.sp, color = c.ink3)
            }
        }
    }
}

@Composable
private fun EventLine(row: AgendaRow, expanded: Boolean, showTimes: Boolean, onOpenNote: (NoteRef) -> Unit) {
    val c = MaterialTheme.grove
    val stripe = if (row.isDone) c.line2 else c.accent
    val time = remember(row, showTimes) { row.startTime()?.takeIf { showTimes }?.format(CLOCK) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(c.surface2)
            .clickable { onOpenNote(NoteRef(row.fileName, row.lineIndex)) },
    ) {
        Box(Modifier.width(if (expanded) 3.dp else 2.dp).fillMaxHeight().background(stripe))
        Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
            Text(
                row.title,
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = if (expanded) 10.sp else 9.sp, lineHeight = 12.sp,
                color = if (row.isDone) c.ink3 else c.ink,
                textDecoration = if (row.isDone) TextDecoration.LineThrough else null,
                maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            time?.let { Text(it, fontFamily = PlexMono, fontSize = 8.5.sp, lineHeight = 10.sp, color = c.ink3) }
        }
    }
}

internal fun AgendaRow.startTime(): LocalTime? = activeTs?.time ?: (scheduledTs ?: deadlineTs)?.time

internal fun fitCount(rows: List<AgendaRow>, available: Float, expanded: Boolean, showTimes: Boolean): Int {
    val heights = rows.map { (if (expanded) 24f else 12f) + (if (showTimes && it.startTime() != null) 10f else 0f) + 6f }
    var used = heights.sum()
    var n = rows.size
    while (n > 0 && used + (if (n < rows.size) MORE_LINE_HEIGHT else 0f) > available) used -= heights[--n]
    return n
}