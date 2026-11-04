package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.settings.AgendaSwipeAction
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.DropdownPicker
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

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
) {
    val c = MaterialTheme.grove
    SettingsPageScaffold(title = "Agenda", onBack = onBack) {
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "Swipe left",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    "Swiping an agenda item left will " + swipeActionDescription(settings.agendaSwipeLeftAction),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                DropdownPicker(
                    options = SWIPE_ACTION_OPTIONS.map { it.label },
                    selectedIndex = SWIPE_ACTION_OPTIONS.indexOf(settings.agendaSwipeLeftAction).coerceAtLeast(0),
                    onSelect = { onSetAgendaSwipeLeftAction(SWIPE_ACTION_OPTIONS[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "Swipe right",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    "Swiping an agenda item right will " + swipeActionDescription(settings.agendaSwipeRightAction),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                DropdownPicker(
                    options = SWIPE_ACTION_OPTIONS.map { it.label },
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
                Column(Modifier.weight(1f)) {
                    Text(
                        "Deadline warning",
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    Text(
                        "Days before a deadline that it shows under Due soon on Today. " +
                            "A warning on the deadline itself, like -3d, wins. 0 turns this off.",
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IntSettingField(settings.agendaDeadlineWarningDays, minValue = 0, onSet = onSetAgendaDeadlineWarningDays)
            }
        }
    }
}
