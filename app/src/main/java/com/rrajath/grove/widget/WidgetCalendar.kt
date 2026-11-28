package com.rrajath.grove.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.MainActivity
import com.rrajath.grove.R
import com.rrajath.grove.data.toNoteMeta
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.org.orgLocale
import com.rrajath.grove.settings.AgendaCalendarView
import com.rrajath.grove.settings.AgendaStateFilter
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.ThemeSelection
import com.rrajath.grove.settings.WeekStart
import com.rrajath.grove.ui.agenda.AgendaBuckets
import com.rrajath.grove.ui.agenda.AgendaCalendarBuckets
import com.rrajath.grove.ui.agenda.AgendaRow
import com.rrajath.grove.ui.agenda.firstDay
import com.rrajath.grove.ui.agenda.fitCount
import com.rrajath.grove.ui.agenda.startTime
import com.rrajath.grove.ui.agenda.weeks
import com.rrajath.grove.ui.components.orgInlinePlainText
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.groveColorsFor
import com.rrajath.grove.ui.theme.systemDarkThemeSupported
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayNameStyle

private const val TAG = "GroveWidget" // i18n: log tag

private val CLOCK = DateTimeFormatter.ofPattern("H:mm")

/** Height above the grid (padding, title, weekday row) and the day number in each cell, in dp. */
private const val CHROME_HEIGHT = 56f
private const val DAY_NUMBER_HEIGHT = 26f

/** The settings this widget reads, so an unrelated write doesn't redraw it (mirrors `WidgetPrefs`). */
private data class CalendarPrefs(
    val themeSelection: ThemeSelection,
    val transparency: Float,
    val fontSize: FontSizePreference,
    val weekStart: WeekStart,
    val showTimes: Boolean,
    val showDone: Boolean,
) {
    constructor(s: GroveSettings) : this(
        s.themeSelection,
        s.agendaWidgetTransparency,
        s.agendaWidgetFontSize,
        s.weekStart,
        s.agendaCalendarShowTimes,
        s.agendaCalendarShowDone,
    )
}

/** Home-screen widget showing the current [view] (week or month) as the app's Calendar tab does. */
class CalendarWidget(private val view: AgendaCalendarView) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GroveApplication
        val initialSettings = app.settingsRepository.settings.first()
        val initialNotes = app.database.indexDao().plannedNotes().first()

        provideContent {
            val settingsFlow = remember { app.settingsRepository.settings.distinctUntilChangedBy { CalendarPrefs(it) } }
            val settings by settingsFlow.collectAsState(initial = initialSettings)
            val notes by app.database.indexDao().plannedNotes().collectAsState(initial = initialNotes)
            val systemIsDark by app.systemNightMode.collectAsState()

            val colors = groveColorsFor(settings.themeSelection.resolve(systemIsDark, systemDarkThemeSupported))
            val today = LocalDate.now()
            val firstDay = remember(settings.weekStart) { settings.weekStart.firstDay() }
            val weeks = remember(view, firstDay, today) { view.weeks(today, firstDay) }
            val days = remember(weeks) { weeks.flatten().filterNotNull() }
            val showDone = settings.agendaCalendarShowDone
            val rows = remember(notes, days, showDone) {
                try {
                    val filter = if (showDone) AgendaStateFilter.All else AgendaStateFilter.Open
                    val visible = notes.map { it.toNoteMeta() }.filter { AgendaBuckets.keep(it, filter) }
                    val plannable = visible.filterNot { AgendaBuckets.delayed(it, today) }
                    AgendaCalendarBuckets().byDay(visible, plannable, days, today, settings)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to build calendar week; showing no events", e)
                    emptyMap()
                }
            }
            val scale = settings.agendaWidgetFontSize.scale
            val available = ((LocalSize.current.height.value - CHROME_HEIGHT) / weeks.size - DAY_NUMBER_HEIGHT) / scale
            val expanded = weeks.size <= 2
            val title = if (view == AgendaCalendarView.MONTH) {
                LocalDates.format(YearMonth.from(today), "yMMMM", "MMMM yyyy")
            } else {
                context.getString(
                    R.string.agenda_calendar_range,
                    LocalDates.format(days.first(), "MMMd", "MMM d"),
                    LocalDates.format(days.last(), "MMMd", "MMM d"),
                )
            }
            val openAgenda = Intent(Intent.ACTION_VIEW, DeepLinks.agenda().toUri()).setClass(context, MainActivity::class.java)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(22.dp)
                    .background(ColorProvider(colors.surface.copy(alpha = 1f - settings.agendaWidgetTransparency)))
                    .padding(8.dp),
            ) {
                Text(
                    title,
                    modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp)
                        .clickable(actionStartActivity(openAgenda)),
                    style = TextStyle(color = ColorProvider(colors.ink), fontSize = (14f * scale).sp, fontWeight = FontWeight.Medium),
                )
                Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    WeekStart.weekdays(firstDay).forEach { weekday ->
                        Text(
                            weekday.getDisplayName(DayNameStyle.NARROW, orgLocale()),
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(
                                color = ColorProvider(colors.ink3),
                                fontSize = (10.5f * scale).sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    weeks.forEach { week ->
                        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                            week.forEach { day ->
                                val cell = GlanceModifier.defaultWeight().fillMaxHeight()
                                if (day == null) {
                                    Spacer(cell)
                                } else {
                                    DayColumn(
                                        cell, context, colors, scale, day, day == today, rows[day].orEmpty(),
                                        available, expanded, settings.agendaCalendarShowTimes,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class CalendarWeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget(AgendaCalendarView.WEEK)
}

class CalendarTwoWeeksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget(AgendaCalendarView.TWO_WEEKS)
}

class CalendarMonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget(AgendaCalendarView.MONTH)
}

@Composable
private fun DayColumn(
    modifier: GlanceModifier,
    context: Context,
    colors: GroveColors,
    scale: Float,
    day: LocalDate,
    isToday: Boolean,
    rows: List<AgendaRow>,
    available: Float,
    expanded: Boolean,
    showTimes: Boolean,
) {
    val shown = fitCount(rows, available, expanded, showTimes)
    Column(modifier = modifier.padding(horizontal = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = (if (isToday) GlanceModifier.background(ColorProvider(colors.accent)) else GlanceModifier)
                    .size(20.dp)
                    .cornerRadius(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    style = TextStyle(
                        color = ColorProvider(if (isToday) colors.accentInk else colors.ink),
                        fontSize = (11f * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
        rows.take(shown).forEach { EventChip(context, colors, scale, it, expanded, showTimes) }
        if (rows.size > shown) {
            Text(
                "+${rows.size - shown}",
                style = TextStyle(color = ColorProvider(colors.ink3), fontSize = (9f * scale).sp, fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun EventChip(context: Context, colors: GroveColors, scale: Float, row: AgendaRow, expanded: Boolean, showTimes: Boolean) {
    val time = if (showTimes) row.startTime()?.format(CLOCK) else null
    val open = Intent(Intent.ACTION_VIEW, noteUri(row)).setClass(context, MainActivity::class.java)
    Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
        // The stripe is the outer box's left padding showing through; the card sits on top of it.
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(4.dp)
                .background(ColorProvider(if (row.isDone) colors.line2 else colors.accent))
                .padding(start = if (expanded) 3.dp else 2.dp)
                .clickable(actionStartActivity(open)),
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ColorProvider(colors.surface2))
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                Text(
                    orgInlinePlainText(row.title),
                    maxLines = if (expanded) 2 else 1,
                    style = TextStyle(
                        color = ColorProvider(if (row.isDone) colors.ink3 else colors.ink),
                        fontSize = ((if (expanded) 10f else 9f) * scale).sp,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (row.isDone) TextDecoration.LineThrough else null,
                    ),
                )
                time?.let {
                    Text(
                        it,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(colors.ink3),
                            fontSize = (8.5f * scale).sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}