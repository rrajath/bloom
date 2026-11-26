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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
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
    onSetHighlightCurrentLine: (Boolean) -> Unit,
    onSetShowLineNumbers: (Boolean) -> Unit,
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

    SettingsPageScaffold(title = stringResource(R.string.settings_page_notes_title), onBack = onBack) {
        // Leaving this screen retires each of its NEW dots from its whole trail
        // (menu glyph, drawer, Settings hub row, the row itself).
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_NEW_NOTE_CURSOR)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_CAPITALIZE_HEADINGS)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_AUTO_SAVE)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_HIGHLIGHT_LINE)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTES_LINE_NUMBERS)

        SectionLabel(stringResource(R.string.settings_notes_section_tasks))
        SettingsGroup {
            Column(Modifier.settingAnchor(SettingSpecs.NotesTodoKeywords).padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    SettingSpecs.NotesTodoKeywords.labelText(),
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                )
                Text(
                    SettingSpecs.NotesTodoKeywords.descriptionText().orEmpty(),
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
                    stringResource(R.string.settings_notes_todo_keywords_apply),
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
            SettingsRow(spec = SettingSpecs.NotesDefaultPriority) {
                SegmentedControl(
                    options = listOf(stringResource(R.string.settings_notes_priority_none), "A", "B", "C"),
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

        SectionLabel(stringResource(R.string.settings_notes_section_display))
        SettingsGroup {
            SettingsRow(
                spec = SettingSpecs.NotesDefaultMode,
                description = if (settings.defaultNoteOpenMode == NoteOpenMode.READ) {
                    stringResource(R.string.settings_notes_default_mode_read_hint)
                } else {
                    stringResource(R.string.settings_notes_default_mode_edit_hint)
                },
            ) {
                SegmentedControl(
                    options = listOf(stringResource(R.string.mode_read), stringResource(R.string.mode_edit)),
                    selectedIndex = settings.defaultNoteOpenMode.ordinal,
                    onSelect = { onSetNoteOpenMode(NoteOpenMode.entries[it]) },
                    modifier = Modifier.width(160.dp),
                )
            }
            RowDivider()
            Column(Modifier.settingAnchor(SettingSpecs.NotesFontSize).padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    SettingSpecs.NotesFontSize.labelText(),
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                )
                Text(
                    SettingSpecs.NotesFontSize.descriptionText().orEmpty(),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
                ModeFontSizePicker(stringResource(R.string.settings_notes_font_size_read), settings.readModeFontSize, onSetReadModeFontSize)
                ModeFontSizePicker(stringResource(R.string.settings_notes_font_size_edit), settings.editModeFontSize, onSetEditModeFontSize)
            }
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesShowPreface,
                checked = settings.showPreface,
                onToggle = onSetShowPreface,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesShowDrawers,
                checked = settings.showPropertyDrawers,
                onToggle = onSetShowPropertyDrawers,
            )
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel(stringResource(R.string.settings_notes_section_new_notes))
        SettingsGroup {
            ToggleRow(
                spec = SettingSpecs.NotesAddId,
                checked = settings.addIdToNewNotes,
                onToggle = onSetAddId,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesAddCreated,
                checked = settings.addCreatedToNewNotes,
                onToggle = onSetAddCreated,
            )
            RowDivider()
            SettingsRow(
                spec = SettingSpecs.NotesNewNoteCursor,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_NEW_NOTE_CURSOR) },
            ) {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_notes_new_note_cursor_heading),
                        stringResource(R.string.settings_notes_new_note_cursor_body),
                    ),
                    selectedIndex = settings.newNoteCursor.ordinal,
                    onSelect = { onSetNewNoteCursor(NewNoteCursor.entries[it]) },
                    modifier = Modifier.width(160.dp),
                )
            }
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesAutoSave,
                checked = settings.autoSaveNotes,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_AUTO_SAVE) },
                onToggle = onSetAutoSaveNotes,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesCapitalize,
                checked = settings.capitalizeHeadings,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_CAPITALIZE_HEADINGS) },
                onToggle = onSetCapitalizeHeadings,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesHighlightLine,
                checked = settings.highlightCurrentLine,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_HIGHLIGHT_LINE) },
                onToggle = onSetHighlightCurrentLine,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotesLineNumbers,
                checked = settings.showLineNumbers,
                labelBadge = { NewDot(NewAnchors.SETTINGS_NOTES_LINE_NUMBERS) },
                onToggle = onSetShowLineNumbers,
            )
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel(stringResource(R.string.settings_notes_section_archiving))
        SettingsGroup {
            ToggleRow(
                spec = SettingSpecs.NotesAutoArchive,
                checked = settings.autoArchiveDoneItems,
                onToggle = onSetAutoArchiveDoneItems,
            )
            if (settings.autoArchiveDoneItems) {
                RowDivider()
                SettingsRow(
                    spec = SettingSpecs.NotesArchiveLocation,
                    onClick = onOpenArchiveLocationPicker,
                ) {
                    Text(
                        archiveLocationSummary(settings.autoArchiveFile, settings.autoArchiveHeadingPath)
                            ?: stringResource(R.string.settings_location_not_set),
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
            options = FontSizePreference.entries.map { stringResource(it.labelRes) },
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(FontSizePreference.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The archive location as a `file › heading` path, or null when none is set. */
private fun archiveLocationSummary(file: String?, headingPath: String): String? {
    if (file == null) return null
    val segments = listOf(file.removeSuffix(".org")) + headingPath.split('/').filter { it.isNotEmpty() }
    return segments.joinToString(" › ")
}
