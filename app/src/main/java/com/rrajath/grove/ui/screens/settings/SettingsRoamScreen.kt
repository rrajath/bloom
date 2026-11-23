package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.asString
import com.rrajath.grove.ui.vault.WHOLE_FILE_LINE_LIMIT
import kotlinx.coroutines.delay

/**
 * Settings § Roam Features (experimental): a master toggle plus three sub-toggles
 * for the Backlinks bar, inline auto-link suggestions, and
 * opening small files as one note. Every sub-toggle is gated on
 * [GroveSettings.roamFeaturesEnabled] as well as its own stored value — see call
 * sites in `GroveApp` (all `settings.roamFeaturesEnabled && settings.roamX`).
 * A separate "Dailies" section below it holds the per-day-note settings, gated
 * on the same master toggle.
 */
@Composable
fun SettingsRoamScreen(
    settings: GroveSettings,
    directories: List<String>,
    onBack: () -> Unit,
    onSetRoamFeaturesEnabled: (Boolean) -> Unit,
    onSetRoamShowBacklinks: (Boolean) -> Unit,
    onSetRoamShowSuggestions: (Boolean) -> Unit,
    onSetRoamOpenWholeFile: (Boolean) -> Unit,
    onSetDailiesDirectory: (String) -> Unit,
    onSetDailiesFilenamePattern: (String) -> Unit,
    onSetDailiesHeaderTemplate: (String) -> Unit,
) {
    val c = MaterialTheme.grove

    // Buffered locally and committed when this screen leaves composition, rather than
    // bound straight to `settings.dailies*` (a DataStore-backed flow): pushing every
    // keystroke through the async write races the round trip, so a recomposition that
    // still sees the pre-write value can undo what was just typed. Same rationale as
    // Settings § Notebooks' ignore-list field and § Notes' todoKeywords field.
    var dailiesDirectoryText by remember(settings.dailiesDirectory) { mutableStateOf(settings.dailiesDirectory) }
    var dailiesPatternText by remember(settings.dailiesFilenamePattern) { mutableStateOf(settings.dailiesFilenamePattern) }
    var dailiesHeaderText by remember(settings.dailiesHeaderTemplate) { mutableStateOf(settings.dailiesHeaderTemplate) }
    var dailiesFieldFocused by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            if (dailiesDirectoryText != settings.dailiesDirectory) onSetDailiesDirectory(dailiesDirectoryText)
            if (dailiesPatternText != settings.dailiesFilenamePattern) onSetDailiesFilenamePattern(dailiesPatternText)
            if (dailiesHeaderText != settings.dailiesHeaderTemplate) onSetDailiesHeaderTemplate(dailiesHeaderText)
        }
    }

    SettingsPageScaffold(title = "Roam Features", onBack = onBack) { scrollState ->
        // Leaving this screen retires the "Open roam files directly in read mode"
        // dot from its whole trail (menu glyph, drawer, Settings hub row, the row itself).
        MarkNewFeatureSeen(NewAnchors.SETTINGS_ROAM_WHOLE_FILE)

        // Bring a focused Dailies field into view once the IME has actually reported itself
        // visible, not on focus alone: at focus time the keyboard hasn't resized the scroll
        // viewport yet, so scrolling immediately targets a stale (pre-keyboard) maxValue.
        // Repeat across the animation window so a later call lands after maxValue has grown
        // to its final size. Same pattern as Settings § Notebooks' ignore-list field.
        @OptIn(ExperimentalLayoutApi::class)
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible, dailiesFieldFocused) {
            if (imeVisible && dailiesFieldFocused) {
                repeat(10) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                    delay(30)
                }
            }
        }

        Column(Modifier.padding(bottom = 10.dp)) {
            Text(
                "These are experimental features and are subject to change.",
                fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink2,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
        SettingsGroup {
            ToggleRow(
                label = "Roam Features",
                checked = settings.roamFeaturesEnabled,
                description = "Adds backlinks (a Backlinks bar showing notes that reference " +
                    "this one) and auto suggestions for files or headings as you type.",
                onToggle = onSetRoamFeaturesEnabled,
            )
            if (settings.roamFeaturesEnabled) {
                RowDivider()
                ToggleRow(
                    label = "Show backlinks",
                    checked = settings.roamShowBacklinks,
                    description = "Show the Backlinks bar on roam files (file-level ID) in the Outline and the whole-file Read and Edit views.",
                    onToggle = onSetRoamShowBacklinks,
                )
                RowDivider()
                ToggleRow(
                    label = "Show suggestions while typing",
                    checked = settings.roamShowSuggestions,
                    description = "Suggest matching files or headings to link to as you type in Edit mode.",
                    onToggle = onSetRoamShowSuggestions,
                )
                RowDivider()
                ToggleRow(
                    label = "Open roam files directly in read mode",
                    checked = settings.roamOpenWholeFile,
                    description = "For org-roam files (ones with a file-level :ID:) of up to " +
                        "$WHOLE_FILE_LINE_LIMIT lines, opening the notebook skips the outline and " +
                        "shows the whole file in your default note mode, like a single note. Larger " +
                        "and non-roam files still open in the outline. Turning this off opens roam " +
                        "files in the outline too, regardless of size.",
                    labelBadge = { NewDot(NewAnchors.SETTINGS_ROAM_WHOLE_FILE, Modifier.padding(start = 6.dp)) },
                    onToggle = onSetRoamOpenWholeFile,
                )
            }
        }

        if (settings.roamFeaturesEnabled) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("DAILIES")
            SettingsGroup {
                SettingsRow(label = "Dailies location", description = "Where daily notes are created; blank = vault root.") {}
                Box(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 0.dp)) {
                    com.rrajath.grove.ui.components.DirectoryField(
                        value = dailiesDirectoryText,
                        onValueChange = { dailiesDirectoryText = it },
                        directories = directories,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RowDivider()
                SettingsRow(label = "File name pattern", description = "FilenamePattern tokens, e.g. %<%Y-%m-%d>.org.") {}
                Box(Modifier.fillMaxWidth().padding(horizontal = 15.dp)) {
                    val patternError = com.rrajath.grove.capture.FilenamePattern.errorFor(dailiesPatternText)
                    androidx.compose.material3.OutlinedTextField(
                        value = dailiesPatternText,
                        onValueChange = { dailiesPatternText = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { dailiesFieldFocused = it.isFocused },
                        isError = patternError != null,
                        supportingText = {
                            if (patternError != null) {
                                Text(patternError.asString(), color = c.red, fontFamily = PlexSans, fontSize = 12.sp)
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = com.rrajath.grove.ui.theme.PlexMono),
                    )
                }
                RowDivider()
                SettingsRow(label = "Header template", description = "Expanded into a new day's file. %? marks the cursor.") {}
                Box(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 6.dp)) {
                    val headerInvalid = remember(dailiesHeaderText) {
                        com.rrajath.grove.capture.PlaceholderExpander.findInvalid(dailiesHeaderText)
                            .map { it.token }.distinct()
                    }
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = dailiesHeaderText,
                            onValueChange = { dailiesHeaderText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .onFocusChanged { dailiesFieldFocused = it.isFocused },
                            isError = headerInvalid.isNotEmpty(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = com.rrajath.grove.ui.theme.PlexMono, fontSize = 13.5.sp,
                            ),
                        )
                        if (headerInvalid.isNotEmpty()) {
                            Text(
                                "Unsupported placeholder${if (headerInvalid.size > 1) "s" else ""}: " +
                                    headerInvalid.joinToString(", "),
                                fontFamily = PlexSans, fontSize = 12.sp, color = c.red,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
