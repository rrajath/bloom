package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.NewNoteCursor
import com.rrajath.grove.settings.NoteOpenMode
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/** Settings § Notes. */
@Composable
fun SettingsNotesScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetTodoKeywords: (String) -> Unit,
    onSetDefaultPriority: (Char?) -> Unit,
    onSetNoteOpenMode: (NoteOpenMode) -> Unit,
    onSetReadModeFontSize: (FontSizePreference) -> Unit,
    onSetEditModeFontSize: (FontSizePreference) -> Unit,
    onSetShowPreface: (Boolean) -> Unit,
    onSetShowPropertyDrawers: (Boolean) -> Unit,
    onSetAddId: (Boolean) -> Unit,
    onSetAddCreated: (Boolean) -> Unit,
    onSetNewNoteCursor: (NewNoteCursor) -> Unit,
    onSetAutoSaveNotes: (Boolean) -> Unit,
    onSetCapitalizeHeadings: (Boolean) -> Unit,
    onSetAutoArchiveDoneItems: (Boolean) -> Unit,
    onOpenArchiveLocationPicker: () -> Unit,
) {
    val c = MaterialTheme.grove
    var keywordsText by remember(settings.todoKeywords) {
        mutableStateOf(settings.todoKeywords)
    }

    // Apply a pending TODO-keywords edit when the screen leaves composition, however that
    // happens, so back doesn't drop it. A BackHandler would work too but steals the whole
    // predictive-back gesture from NavHost, breaking the previous screen's preview animation.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (keywordsText != settings.todoKeywords && keywordsText.isNotBlank()) {
                onSetTodoKeywords(keywordsText)
            }
        }
    }

    SettingsPageScaffold(title = "Notes", onBack = onBack) {
        // Leaving this screen retires each of its NEW dots from its whole trail
        // (menu glyph, drawer, Settings hub row, the row itself).
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_NEW_NOTE_CURSOR)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_CAPITALIZE_HEADINGS)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_AUTO_SAVE)

        SectionLabel("TASKS")
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "TODO keywords",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                )
                Text(
                    "Keywords after | are done-type",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = keywordsText,
                    onValueChange = { keywordsText = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Always visible (not just after an edit): also doubles as a manual
                // "rebuild index" action, e.g. to recompute isDone for notebooks whose
                // stale Room rows predate the keywords they were indexed under.
                Text(
                    "Apply (re-indexes all notebooks)",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, color = c.accent,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSetTodoKeywords(keywordsText) }
                        .padding(6.dp),
                )
            }
            RowDivider()
            SettingsRow(
                label = "Default priority",
                description = "Search's p. counts headings with no priority as this one",
            ) {
                SegmentedControl(
                    options = listOf("None", "A", "B", "C"),
                    selectedIndex = when (settings.defaultPriority) {
                        'A' -> 1; 'B' -> 2; 'C' -> 3; else -> 0
                    },
                    onSelect = { onSetDefaultPriority(listOf(null, 'A', 'B', 'C')[it]) },
                    optionIcons = listOf(Icons.Filled.Block, null, null, null),
                    modifier = Modifier.width(200.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("DISPLAY")
        SettingsGroup {
            SettingsRow(
                label = "Default note mode",
                description = if (settings.defaultNoteOpenMode == NoteOpenMode.READ) {
                    "Notes open in read mode"
                } else {
                    "Notes open in edit mode"
                },
            ) {
                SegmentedControl(
                    options = listOf("Read", "Edit"),
                    selectedIndex = settings.defaultNoteOpenMode.ordinal,
                    onSelect = { onSetNoteOpenMode(NoteOpenMode.entries[it]) },
                    modifier = Modifier.width(160.dp),
                )
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    "Font size",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                )
                Text(
                    "Read and edit mode scale independently. The rest of the app is unaffected.",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
                ModeFontSizePicker("Read mode", settings.readModeFontSize, onSetReadModeFontSize)
                ModeFontSizePicker("Edit mode", settings.editModeFontSize, onSetEditModeFontSize)
            }
            RowDivider()
            ToggleRow(
                label = "Show preface",
                description = "Display file-level #+ keywords in org files",
                checked = settings.showPreface,
                onToggle = onSetShowPreface,
            )
            RowDivider()
            ToggleRow(
                label = "Show property drawers",
                description = "Display :PROPERTIES: drawers in org files",
                checked = settings.showPropertyDrawers,
                onToggle = onSetShowPropertyDrawers,
            )
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("NEW NOTES & EDITING")
        SettingsGroup {
            ToggleRow(
                label = "Add ID to new notes",
                description = "Adds an ID property while creating new notes",
                checked = settings.addIdToNewNotes,
                onToggle = onSetAddId,
            )
            RowDivider()
            ToggleRow(
                label = "Add CREATED timestamp",
                description = "Adds a CREATED property with the current timestamp when creating new notes",
                checked = settings.addCreatedToNewNotes,
                onToggle = onSetAddCreated,
            )
            RowDivider()
            SettingsRow(
                label = "New note cursor",
                description = "Where the cursor lands in a note you just created",
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_NEW_NOTE_CURSOR) },
            ) {
                SegmentedControl(
                    options = listOf("Heading", "Body"),
                    selectedIndex = settings.newNoteCursor.ordinal,
                    onSelect = { onSetNewNoteCursor(NewNoteCursor.entries[it]) },
                    modifier = Modifier.width(160.dp),
                )
            }
            RowDivider()
            ToggleRow(
                label = "Auto-save notes",
                description = "Saves an open editor 5 seconds after you stop typing. Off means a note is " +
                        "written only when you tap the save icon, so read mode shows your unsaved edits until then",
                checked = settings.autoSaveNotes,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_AUTO_SAVE) },
                onToggle = onSetAutoSaveNotes,
            )
            RowDivider()
            ToggleRow(
                label = "Auto-capitalize headings",
                description = "Capitalizes the first letter typed into a heading. Off leaves capitalization to your keyboard",
                checked = settings.capitalizeHeadings,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_CAPITALIZE_HEADINGS) },
                onToggle = onSetCapitalizeHeadings,
            )
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("ARCHIVING")
        SettingsGroup {
            ToggleRow(
                label = "Auto-archive done items?",
                description = "Refiles a task the moment it's marked done, to its ARCHIVE property/keyword " +
                        "if it has one, otherwise the location below",
                checked = settings.autoArchiveDoneItems,
                onToggle = onSetAutoArchiveDoneItems,
            )
            if (settings.autoArchiveDoneItems) {
                RowDivider()
                SettingsRow(
                    label = "Archive location",
                    description = "Fallback when a task has no ARCHIVE property/keyword of its own",
                    onClick = onOpenArchiveLocationPicker,
                ) {
                    Text(
                        archiveLocationSummary(settings.autoArchiveFile, settings.autoArchiveHeadingPath),
                        fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                    )
                }
            }
        }
    }
}

/** One "Read mode" / "Edit mode" labelled Small/Medium/Large picker inside the Font size block. */
@Composable
private fun ModeFontSizePicker(
    label: String,
    selected: FontSizePreference,
    onSelect: (FontSizePreference) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            label,
            fontFamily = PlexSans, fontSize = 12.5.sp, color = MaterialTheme.grove.ink2,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        SegmentedControl(
            options = listOf("Small", "Medium", "Large"),
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(FontSizePreference.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun archiveLocationSummary(file: String?, headingPath: String): String {
    if (file == null) return "Not set"
    val segments = listOf(file.removeSuffix(".org")) + headingPath.split('/').filter { it.isNotEmpty() }
    return segments.joinToString(" › ")
}
