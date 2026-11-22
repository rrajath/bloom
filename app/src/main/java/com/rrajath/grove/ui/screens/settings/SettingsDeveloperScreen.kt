// i18n: developer tools (debug builds only), never translate
package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.FOLDER_DRILL_THRESHOLD
import com.rrajath.grove.ui.vault.WHOLE_FILE_LINE_LIMIT
import com.rrajath.grove.ui.vault.folderDrillThresholdOverride
import com.rrajath.grove.ui.vault.wholeFileLineLimitOverride

/**
 * Debug-only tools, reached from Settings › Developer (that entry is gated on
 * `BuildConfig.DEBUG`). Structured so future debug actions slot in as more rows.
 */
@Composable
fun SettingsDeveloperScreen(
    onBack: () -> Unit,
    onResetNewBadges: () -> Unit,
) {
    val c = MaterialTheme.grove
    var confirmation by remember { mutableStateOf<String?>(null) }

    SettingsPageScaffold(title = "Developer", onBack = onBack) {
        SectionLabel("NEW BADGES")
        SettingsGroup {
            SettingsRow(
                label = "Reset New badges",
                description = "Re-arm every New badge so the trail shows again",
                onClick = {
                    onResetNewBadges()
                    confirmation = "New badges re-armed. Open the drawer to see them."
                },
            ) {
                Text("↺", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
            }
        }
        confirmation?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("FOLDER DRILL-DOWN")
        SettingsGroup {
            val threshold by folderDrillThresholdOverride.collectAsState()
            SettingsRow(
                label = "Drill-down file count",
                description = "Folders with more than this many .org files open the " +
                    "drill-down view instead of expanding in place",
            ) {
                DrillThresholdField(
                    value = threshold,
                    onSet = { folderDrillThresholdOverride.value = it },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Not saved. Resets to $FOLDER_DRILL_THRESHOLD when the app restarts.",
            fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
            modifier = Modifier.padding(start = 4.dp),
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel("WHOLE FILE")
        SettingsGroup {
            val limit by wholeFileLineLimitOverride.collectAsState()
            SettingsRow(
                label = "Line limit",
                description = "Files with more lines than this can't open as one note: the " +
                    "Outline's \"View file\" is disabled and the Roam auto-open falls back " +
                    "to the outline. Raise it to find where Read/Edit mode starts to lag",
            ) {
                DrillThresholdField(
                    value = limit,
                    onSet = { wholeFileLineLimitOverride.value = it },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Not saved. Resets to $WHOLE_FILE_LINE_LIMIT when the app restarts.",
            fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Positive-integer-only field: non-digit keystrokes are filtered as typed, and an
 * invalid value (blank or 0) reverts to the last committed value on blur.
 */
@Composable
private fun DrillThresholdField(value: Int, onSet: (Int) -> Unit) {
    val c = MaterialTheme.grove
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(4) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontFamily = PlexMono, fontSize = 14.sp, color = c.accent),
        modifier = Modifier
            .width(72.dp)
            .onFocusChanged { state ->
                if (!state.isFocused) {
                    val parsed = text.toIntOrNull()
                    if (parsed != null && parsed > 0) onSet(parsed) else text = value.toString()
                }
            },
    )
}
