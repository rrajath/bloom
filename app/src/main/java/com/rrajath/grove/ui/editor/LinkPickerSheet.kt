package com.rrajath.grove.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.ui.components.PickerRow
import com.rrajath.grove.ui.components.PickerSearchField
import com.rrajath.grove.ui.components.PickerSearchHeader
import com.rrajath.grove.ui.components.notebookIcon
import com.rrajath.grove.ui.components.pickerSearchResults
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.pluralCount
import com.rrajath.grove.ui.vault.headlineAtLine
import kotlinx.coroutines.launch

/**
 * Full-height "insert a link" bottom sheet (toolbar `[[ ]]` long-press): a
 * notebook drill-down (design language of the refile picker) plus a live,
 * vault-wide search over [LinkPickerUiState.searchIndex]. Always opens at the
 * notebook list -- never the file being edited -- with the search field
 * focused and the keyboard already up.
 *
 * Tapping a heading either drills into it (it has sub-headings) or hands it
 * straight to [onConfirmHeading] (a leaf heading commits immediately, instead
 * of drilling into an empty "no sub-headings" screen first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPickerSheet(
    state: LinkPickerUiState,
    onQueryChange: (String) -> Unit,
    onPickNotebook: (String) -> Unit,
    onDrillInto: (Int) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onConfirmFileOrHeading: () -> Unit,
    onSelectSearchResult: suspend (LinkSearchItem) -> LinkPickerSearchOutcome,
    onConfirmHeading: (OrgDocument, String, OrgHeadline) -> Unit,
) {
    val c = MaterialTheme.grove
    val doc = state.pickedDoc
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val crumb = when {
        doc == null -> "Choose a notebook, or search files and headings"
        state.path.isEmpty() -> "${state.pickedFile?.removeSuffix(".org")} › top level · or pick a heading"
        else -> (listOf(state.pickedFile?.removeSuffix(".org")) +
            state.path.map { doc.headlineAtLine(it)?.title ?: "?" })
            .joinToString(" › ")
    }

    fun selectSearchResult(item: LinkSearchItem) {
        scope.launch {
            val outcome = onSelectSearchResult(item)
            if (outcome is LinkPickerSearchOutcome.ReadyToConfirm) {
                onConfirmHeading(outcome.doc, outcome.fileName, outcome.heading)
            }
        }
    }

    fun selectBrowseHeading(h: OrgHeadline) {
        val d = doc ?: return
        val file = state.pickedFile ?: return
        if (d.hasDescendants(h)) onDrillInto(h.lineIndex) else onConfirmHeading(d, file, h)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (doc != null) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c.surface2)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("←", fontFamily = PlexSans, fontSize = 16.sp, color = c.ink)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column {
                    Text(
                        "Insert a link",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, color = c.ink,
                    )
                    Text(crumb, fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2)
                }
            }
            Spacer(Modifier.height(12.dp))
            PickerSearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                focusRequester = focusRequester,
            )
            Spacer(Modifier.height(10.dp))

            val searching = state.query.isNotBlank()
            val results = remember(state.query, state.searchIndex) {
                if (searching) state.searchIndex?.let { filterLinkSearch(it, state.query) }.orEmpty() else emptyList()
            }
            if (searching) PickerSearchHeader()

            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (searching) {
                    pickerSearchResults(results, onClick = ::selectSearchResult)
                } else if (doc == null) {
                    items(state.notebooks.orEmpty(), key = { it.fileName }) { nb ->
                        PickerRow(
                            icon = notebookIcon(),
                            title = nb.fileName.removeSuffix(".org"),
                            meta = pluralCount(nb.noteCount, "heading"),
                            onClick = { onPickNotebook(nb.fileName) },
                        )
                    }
                } else {
                    val level = state.path.lastOrNull()?.let { doc.headlineAtLine(it) }
                    val rows = level?.let { doc.directChildren(it) }
                        ?: doc.headlines.filter { doc.parent(it) == null }
                    if (rows.isEmpty()) {
                        item {
                            Text(
                                "No sub-headings here",
                                fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(rows, key = { it.lineIndex }) { h ->
                        PickerRow(
                            asterisk = true,
                            title = h.title,
                            meta = doc.directChildren(h).size.takeIf { it > 0 }?.let { pluralCount(it, "heading") },
                            onClick = { selectBrowseHeading(h) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = c.line)
            Row(
                Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface2)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        "Cancel",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, color = c.ink2,
                    )
                }
                val enabled = state.pickedFile != null
                val label = if (state.path.isEmpty() && doc != null) "Link to this file" else "Link to this heading"
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (enabled) c.accent else c.surface2)
                        .clickable(enabled = enabled, onClick = onConfirmFileOrHeading)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, color = if (enabled) c.accentInk else c.ink3,
                    )
                }
            }
        }
    }
}
