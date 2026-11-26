package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.widget.QuickAddTarget
import kotlin.math.roundToInt

/** Settings § Widget: every lever for the home-screen Agenda ledger widget, plus a live preview. */
@Composable
fun SettingsWidgetScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetAgendaWidgetTransparency: (Float) -> Unit,
    onSetAgendaWidgetDaysAhead: (Int) -> Unit,
    onSetAgendaWidgetShowFileName: (Boolean) -> Unit,
    onSetAgendaWidgetShowTags: (Boolean) -> Unit,
    onSetAgendaWidgetShowPriority: (Boolean) -> Unit,
    onSetAgendaWidgetFontSize: (FontSizePreference) -> Unit,
    onOpenQuickAddTargetPicker: () -> Unit,
) {
    val c = MaterialTheme.grove
    SettingsPageScaffold(title = stringResource(R.string.settings_page_widget_title), onBack = onBack) {
        // Leaving this screen retires the Widget page's NEW dot from its whole
        // trail (menu glyph, drawer, Settings hub row, the page itself).
        MarkNewFeatureSeen(NewAnchors.SETTINGS_WIDGET_PAGE)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_WIDGET_QUICK_ADD_TARGET)

        // Local, not the committed setting: Slider's onValueChange fires on every
        // drag tick, and writing to the settings DataStore that often made both
        // the drag and the live preview below feel jittery. Dragging now only
        // updates this in-memory value (smooth, and the preview tracks it live);
        // onValueChangeFinished commits it to disk once, when the drag ends.
        var localTransparency by remember(settings.agendaWidgetTransparency) {
            mutableFloatStateOf(settings.agendaWidgetTransparency)
        }
        SettingsGroup {
            Column(Modifier.settingAnchor(SettingSpecs.WidgetTransparency).padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SettingSpecs.WidgetTransparency.labelText(),
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.settings_widget_percent, (localTransparency * 100).roundToInt()),
                        fontFamily = PlexMono, fontSize = 13.sp, color = c.accent,
                    )
                }
                Text(
                    SettingSpecs.WidgetTransparency.descriptionText().orEmpty(),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                )
                Slider(
                    value = localTransparency,
                    onValueChange = { localTransparency = it },
                    onValueChangeFinished = { onSetAgendaWidgetTransparency(localTransparency) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = c.accent,
                        activeTrackColor = c.accent,
                        inactiveTrackColor = c.line,
                    ),
                )
            }
            RowDivider()
            Row(
                Modifier.settingAnchor(SettingSpecs.WidgetDaysAhead).fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        SettingSpecs.WidgetDaysAhead.labelText(),
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    Text(
                        SettingSpecs.WidgetDaysAhead.descriptionText().orEmpty(),
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IntSettingField(settings.agendaWidgetDaysAhead, minValue = 2, onSet = onSetAgendaWidgetDaysAhead)
            }
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.WidgetShowFileName,
                checked = settings.agendaWidgetShowFileName,
                onToggle = onSetAgendaWidgetShowFileName,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.WidgetShowTags,
                checked = settings.agendaWidgetShowTags,
                onToggle = onSetAgendaWidgetShowTags,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.WidgetShowPriority,
                checked = settings.agendaWidgetShowPriority,
                onToggle = onSetAgendaWidgetShowPriority,
            )
            RowDivider()
            SettingsRow(
                spec = SettingSpecs.WidgetQuickAddTarget,
                description = if (settings.quickAddTargetFile == null) {
                    stringResource(R.string.settings_widget_quick_add_target_default_hint)
                } else {
                    stringResource(R.string.settings_widget_quick_add_target_hint)
                },
                labelBadge = { NewDot(NewAnchors.SETTINGS_WIDGET_QUICK_ADD_TARGET) },
                onClick = onOpenQuickAddTargetPicker,
            ) {
                Text(
                    QuickAddTarget.from(settings).label,
                    fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
            RowDivider()
            Column(Modifier.settingAnchor(SettingSpecs.WidgetFontSize).padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    SettingSpecs.WidgetFontSize.labelText(),
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                SegmentedControl(
                    options = FontSizePreference.entries.map { stringResource(it.labelRes) },
                    selectedIndex = settings.agendaWidgetFontSize.ordinal,
                    onSelect = { onSetAgendaWidgetFontSize(FontSizePreference.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            stringResource(R.string.settings_widget_preview_hint),
            fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        )
        Box(Modifier.padding(bottom = 14.dp)) {
            AgendaWidgetPreview(settings, transparencyOverride = localTransparency)
        }
    }
}

