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

    SettingsPageScaffold(title = "Notebooks", onBack = onBack) { scrollState ->
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
                label = "Show file icons in notebooks",
                description = "Display the icon tile on each row of the notebooks list",
                checked = settings.showNotebookFileIcons,
                onToggle = onSetShowNotebookFileIcons,
            )
            RowDivider()
            ToggleRow(
                label = "Flatten folders",
                description = "Hide folders and list every note as one flat list, each showing its folder path",
                checked = settings.flattenNotebookFolders,
                onToggle = onSetFlattenNotebookFolders,
            )
            RowDivider()
            SettingsRow(
                label = "Notebook display name",
                description = if (settings.notebookDisplayNameMode == NotebookDisplayNameMode.FILENAME) {
                    "Notebooks are displayed by their filenames"
                } else {
                    "Notebooks are displayed by their titles, falling back to filename"
                },
            ) {
                SegmentedControl(
                    options = listOf("Filename", "Title"),
                    selectedIndex = settings.notebookDisplayNameMode.ordinal,
                    onSelect = { onSetNotebookDisplayNameMode(NotebookDisplayNameMode.entries[it]) },
                    modifier = Modifier.width(200.dp),
                )
            }
            RowDivider()
            SettingsRow(
                label = "Notebooks sort order",
                description = "Pinned notebooks and folders are excluded from sorting; they keep their pinned order",
            ) {
                SegmentedControl(
                    options = NotebookSortKey.entries.map { stringResource(it.labelRes) },
                    selectedIndex = settings.notebookSortKey.ordinal,
                    onSelect = { onSetNotebookSortKey(NotebookSortKey.entries[it]) },
                    modifier = Modifier.width(220.dp),
                )
            }
            RowDivider()
            SettingsRow(
                label = "Order",
                description = when (settings.notebookSortKey) {
                    NotebookSortKey.ALPHABETICAL ->
                        if (settings.notebookSortAscending) "A to Z" else "Z to A"
                    NotebookSortKey.LAST_MODIFIED ->
                        if (settings.notebookSortAscending) "Oldest edited first" else "Most recently edited first"
                },
            ) {
                SegmentedControl(
                    options = listOf("Ascending", "Descending"),
                    selectedIndex = if (settings.notebookSortAscending) 0 else 1,
                    onSelect = { onSetNotebookSortAscending(it == 0) },
                    modifier = Modifier.width(200.dp),
                )
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Ignore list",
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    NewDot(NewAnchors.SETTINGS_NOTEBOOKS_IGNORE_LIST)
                }
                Text(
                    "Files, folders, and patterns listed below are skipped during indexing. One per line.",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Text(
                    when {
                        settings.ignoreListImportedFromFile -> "Imported from .orgzlyignore."
                        settings.ignoreListLegacyFileEmpty -> "Found a .orgzlyignore file and it's empty."
                        else -> "No .orgzlyignore file found."
                    },
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
                    "Dot-prefixed folders (e.g. .git, .stversions, .stfolder) are always skipped automatically, no need to add them here.",
                    fontFamily = PlexSans, fontSize = 11.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
