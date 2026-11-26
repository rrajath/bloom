package com.rrajath.grove.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.org.orgLocale
import com.rrajath.grove.settings.AgendaSwipeAction
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.WeekStart
import com.rrajath.grove.ui.components.DropdownPicker
import com.rrajath.grove.ui.components.systemFirstDayOfWeek
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Only these two are offered from the swipe-action pickers below; [AgendaSwipeAction.SET_DEADLINE]
 * stays a valid stored value (an older export can still carry it) but is no longer user-selectable.
 */
private val SWIPE_ACTION_OPTIONS = listOf(AgendaSwipeAction.MARK_DONE, AgendaSwipeAction.SET_SCHEDULED)

/** Settings § Agenda. */
@Composable
fun SettingsAgendaScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetAgendaSwipeLeftAction: (AgendaSwipeAction) -> Unit,
    onSetAgendaSwipeRightAction: (AgendaSwipeAction) -> Unit,
    onSetAgendaDeadlineWarningDays: (Int) -> Unit,
    onSetWeekStart: (WeekStart) -> Unit,
    onSetAgendaCalendarShowTimes: (Boolean) -> Unit,
    onSetAgendaCalendarShowDone: (Boolean) -> Unit,
) {
    val c = MaterialTheme.grove
    // Leaving this screen retires the Deadline warning's NEW dot from its whole
    // trail (menu glyph, drawer, Settings hub row, the row itself).
    MarkNewFeatureSeen(NewAnchors.SETTINGS_AGENDA_DEADLINE_WARNING)
    MarkNewFeatureSeen(NewAnchors.SETTINGS_AGENDA_WEEK_START)
    val systemFirstDay = remember { systemFirstDayOfWeek() }
    SettingsPageScaffold(title = stringResource(R.string.settings_page_agenda_title), onBack = onBack) {
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.settings_agenda_swipe_left),
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    stringResource(swipeDescriptionRes(left = true, settings.agendaSwipeLeftAction)),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                DropdownPicker(
                    options = SWIPE_ACTION_OPTIONS.map { stringResource(it.labelRes) },
                    selectedIndex = SWIPE_ACTION_OPTIONS.indexOf(settings.agendaSwipeLeftAction).coerceAtLeast(0),
                    onSelect = { onSetAgendaSwipeLeftAction(SWIPE_ACTION_OPTIONS[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.settings_agenda_swipe_right),
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    stringResource(swipeDescriptionRes(left = false, settings.agendaSwipeRightAction)),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                DropdownPicker(
                    options = SWIPE_ACTION_OPTIONS.map { stringResource(it.labelRes) },
                    selectedIndex = SWIPE_ACTION_OPTIONS.indexOf(settings.agendaSwipeRightAction).coerceAtLeast(0),
                    onSelect = { onSetAgendaSwipeRightAction(SWIPE_ACTION_OPTIONS[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // End padding keeps the wrapped description off the number field.
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_agenda_deadline_warning),
                            fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                            fontSize = 14.5.sp, color = c.ink,
                        )
                        NewDot(NewAnchors.SETTINGS_AGENDA_DEADLINE_WARNING, Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.settings_agenda_deadline_warning_hint),
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IntSettingField(settings.agendaDeadlineWarningDays, minValue = 0, onSet = onSetAgendaDeadlineWarningDays)
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(
                    Modifier.padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_agenda_week_start),
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    NewDot(NewAnchors.SETTINGS_AGENDA_WEEK_START, Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(R.string.settings_agenda_week_start_hint),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                DropdownPicker(
                    options = WeekStart.entries.map { weekStartLabel(it, systemFirstDay) },
                    selectedIndex = WeekStart.entries.indexOf(settings.weekStart),
                    onSelect = { onSetWeekStart(WeekStart.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_agenda_calendar_show_times),
                description = stringResource(R.string.settings_agenda_calendar_show_times_hint),
                checked = settings.agendaCalendarShowTimes,
                onToggle = onSetAgendaCalendarShowTimes,
            )
            RowDivider()
            ToggleRow(
                label = stringResource(R.string.settings_agenda_calendar_show_done),
                description = stringResource(R.string.settings_agenda_calendar_show_done_hint),
                checked = settings.agendaCalendarShowDone,
                onToggle = onSetAgendaCalendarShowDone,
            )
        }
    }
}

/** The sentence under a swipe direction, naming what that swipe does. */
@StringRes
private fun swipeDescriptionRes(left: Boolean, action: AgendaSwipeAction): Int = when (action) {
    AgendaSwipeAction.SET_SCHEDULED ->
        if (left) R.string.settings_agenda_swipe_left_scheduled else R.string.settings_agenda_swipe_right_scheduled
    AgendaSwipeAction.SET_DEADLINE ->
        if (left) R.string.settings_agenda_swipe_left_deadline else R.string.settings_agenda_swipe_right_deadline
    AgendaSwipeAction.MARK_DONE ->
        if (left) R.string.settings_agenda_swipe_left_done else R.string.settings_agenda_swipe_right_done
}

/** "System default (Monday)" names the day the device region resolves to, in the app's language. */
@Composable
private fun weekStartLabel(weekStart: WeekStart, systemFirstDay: DayOfWeek): String =
    if (weekStart == WeekStart.SYSTEM) {
        stringResource(
            R.string.settings_week_start_system_with_day,
            systemFirstDay.getDisplayName(TextStyle.FULL, orgLocale()),
        )
    } else {
        stringResource(weekStart.labelRes)
    }
