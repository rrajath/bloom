package com.rrajath.grove.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.ReminderLeadTime
import com.rrajath.grove.ui.components.DropdownPicker
import com.rrajath.grove.ui.components.SimpleTimePicker
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.org.LocalDates
import java.time.LocalTime

/** Settings § Reminders. */
@Composable
fun SettingsRemindersScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetRemindersEnabled: (Boolean) -> Unit,
    onSetMorningBriefEnabled: (Boolean) -> Unit,
    onSetNotifyUntimedTasks: (Boolean) -> Unit,
    onSetDefaultReminderTime: (LocalTime) -> Unit,
    onSetReminderLeadTime: (ReminderLeadTime) -> Unit,
) {
    val c = MaterialTheme.grove
    var showReminderTimePicker by remember { mutableStateOf(false) }

    // Unconditional: visiting this screen retires the badge even when "Enable reminders" is
    // off and the toggle row below never composes (otherwise the dot could never clear).
    MarkNewFeatureSeen(NewAnchors.SETTINGS_REMINDERS_UNTIMED)

    SettingsPageScaffold(title = "Reminders", onBack = onBack) {
        // Replaces ReminderPermissionBanner on this screen: the rows cover both
        // permissions, and only while off. Nothing is armed with reminders off.
        if (settings.remindersEnabled) ReminderAccessSection()
        SettingsGroup {
            ToggleRow(
                label = "Enable reminders",
                description = "Notify when a note's SCHEDULED or DEADLINE time arrives",
                checked = settings.remindersEnabled,
                onToggle = onSetRemindersEnabled,
            )
            if (settings.remindersEnabled) {
                RowDivider()
                Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                    Text(
                        "Notify me",
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    Text(
                        "When a task with a specific time is due",
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    DropdownPicker(
                        options = ReminderLeadTime.entries.map { it.label },
                        selectedIndex = settings.reminderLeadTime.ordinal,
                        onSelect = { onSetReminderLeadTime(ReminderLeadTime.entries[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RowDivider()
                ToggleRow(
                    label = "Notify for tasks without a time",
                    description = "A separate notification for each SCHEDULED, DEADLINE, or active timestamp on a day with no time of day",
                    checked = settings.notifyUntimedTasks,
                    labelBadge = { NewDot(NewAnchors.SETTINGS_REMINDERS_UNTIMED) },
                    onToggle = onSetNotifyUntimedTasks,
                )
            }
            RowDivider()
            ToggleRow(
                label = "Morning Brief",
                description = "Organize your day",
                checked = settings.morningBriefEnabled,
                onToggle = onSetMorningBriefEnabled,
            )
            if (settings.morningBriefEnabled || settings.notifyUntimedTasks) {
                RowDivider()
                SettingsRow(
                    label = "Send reminder at",
                    description = "Time of day for the Morning Brief and any timeless SCHEDULED, DEADLINE, or active stamp",
                    onClick = { showReminderTimePicker = true },
                ) {
                    Text(
                        settings.defaultReminderTime.format(LocalDates.clock(DateFormat.is24HourFormat(LocalContext.current))),
                        fontFamily = PlexMono, fontSize = 13.sp, color = c.accent,
                    )
                }
            }
        }
    }

    if (showReminderTimePicker) {
        SimpleTimePicker(
            initial = settings.defaultReminderTime,
            onDismiss = { showReminderTimePicker = false },
            onConfirm = { time ->
                onSetDefaultReminderTime(time)
                showReminderTimePicker = false
            },
        )
    }
}
