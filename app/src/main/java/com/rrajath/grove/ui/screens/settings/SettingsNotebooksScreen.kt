package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.rrajath.grove.R
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.NotebookDisplayNameMode
import com.rrajath.grove.settings.NotebookSortKey
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Settings § Notebooks: everything that shapes the Notebooks list itself. Split
 * out from § Look and Feel — the file-icon toggle, folder flattening and the
 * display-name mode moved here, joined by the sort-order controls.
 */
@Composable
fun SettingsNotebooksScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetShowNotebookFileIcons: (Boolean) -> Unit,
    onSetFlattenNotebookFolders: (Boolean) -> Unit,
    onSetNotebookDisplayNameMode: (NotebookDisplayNameMode) -> Unit,
    onSetNotebookSortKey: (NotebookSortKey) -> Unit,
    onSetNotebookSortAscending: (Boolean) -> Unit,
    onSetIgnoreList: (String) -> Unit,
) {
    val c = MaterialTheme.grove
    var ignoreListText by remember(settings.ignoreList) { mutableStateOf(settings.ignoreList) }
    var ignoreListFieldFocused by remember { mutableStateOf(false) }

    // Commit a pending ignore-list edit when the screen leaves composition, however
    // that happens (back gesture included) — same rationale as Settings § Notes'
    // todoKeywords field.
    DisposableEffect(Unit) {
        onDispose {
            if (ignoreListText != settings.ignoreList) onSetIgnoreList(ignoreListText)
        }
    }

    SettingsPageScaffold(title = stringResource(R.string.settings_page_notebooks_title), onBack = onBack) { scrollState ->
        // Leaving this screen retires the "Ignore list" NEW dot from its whole
        // trail (menu glyph, drawer, Settings hub row, the block itself).
        MarkNewFeatureSeen(NewAnchors.SETTINGS_NOTEBOOKS_IGNORE_LIST)
        // Bring the field into view once the IME has actually reported itself visible, not on
        // focus alone: at focus time the keyboard hasn't resized the scroll viewport yet, so
        // scrolling immediately targets a stale (pre-keyboard) maxValue. Repeat across the
        // animation window so a later call lands after maxValue has grown to its final size.
        @OptIn(ExperimentalLayoutApi::class)
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible, ignoreListFieldFocused) {
            if (imeVisible && ignoreListFieldFocused) {
                repeat(10) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                    delay(30)
                }
            }
        }
        SettingsGroup {
            ToggleRow(
                spec = SettingSpecs.NotebooksFileIcons,
                checked = settings.showNotebookFileIcons,
                onToggle = onSetShowNotebookFileIcons,
            )
            RowDivider()
            ToggleRow(
                spec = SettingSpecs.NotebooksFlatten,
                checked = settings.flattenNotebookFolders,
                onToggle = onSetFlattenNotebookFolders,
            )
            RowDivider()
            SettingsRow(
                spec = SettingSpecs.NotebooksDisplayName,
                description = if (settings.notebookDisplayNameMode == NotebookDisplayNameMode.FILENAME) {
                    stringResource(R.string.settings_notebooks_display_name_filename_hint)
                } else {
                    stringResource(R.string.settings_notebooks_display_name_title_hint)
                },
            ) {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_notebooks_display_name_filename),
                        stringResource(R.string.settings_notebooks_display_name_title),
                    ),
                    selectedIndex = settings.notebookDisplayNameMode.ordinal,
                    onSelect = { onSetNotebookDisplayNameMode(NotebookDisplayNameMode.entries[it]) },
                    modifier = Modifier.width(200.dp),
                )
            }
            RowDivider()
            SettingsRow(spec = SettingSpecs.NotebooksSort) {
                SegmentedControl(
                    options = NotebookSortKey.entries.map { stringResource(it.labelRes) },
                    selectedIndex = settings.notebookSortKey.ordinal,
                    onSelect = { onSetNotebookSortKey(NotebookSortKey.entries[it]) },
                    modifier = Modifier.width(220.dp),
                )
            }
            RowDivider()
            SettingsRow(
                spec = SettingSpecs.NotebooksOrder,
                description = stringResource(
                    when (settings.notebookSortKey) {
                        NotebookSortKey.ALPHABETICAL ->
                            if (settings.notebookSortAscending) R.string.settings_notebooks_order_a_to_z else R.string.settings_notebooks_order_z_to_a
                        NotebookSortKey.LAST_MODIFIED ->
                            if (settings.notebookSortAscending) R.string.settings_notebooks_order_oldest_first else R.string.settings_notebooks_order_newest_first
                    },
                ),
            ) {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_notebooks_order_ascending),
                        stringResource(R.string.settings_notebooks_order_descending),
                    ),
                    selectedIndex = if (settings.notebookSortAscending) 0 else 1,
                    onSelect = { onSetNotebookSortAscending(it == 0) },
                    modifier = Modifier.width(200.dp),
                )
            }
            RowDivider()
            Column(Modifier.settingAnchor(SettingSpecs.NotebooksIgnoreList).padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        SettingSpecs.NotebooksIgnoreList.labelText(),
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    NewDot(NewAnchors.SETTINGS_NOTEBOOKS_IGNORE_LIST)
                }
                Text(
                    SettingSpecs.NotebooksIgnoreList.descriptionText().orEmpty(),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Text(
                    stringResource(
                        when {
                            settings.ignoreListImportedFromFile -> R.string.settings_notebooks_ignore_imported
                            settings.ignoreListLegacyFileEmpty -> R.string.settings_notebooks_ignore_legacy_empty
                            else -> R.string.settings_notebooks_ignore_no_file
                        },
                    ),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = ignoreListText,
                    onValueChange = { ignoreListText = it },
                    singleLine = false,
                    textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .onFocusChanged { ignoreListFieldFocused = it.isFocused },
                )
                Text(
                    stringResource(R.string.settings_notebooks_ignore_dot_folders),
                    fontFamily = PlexSans, fontSize = 11.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
