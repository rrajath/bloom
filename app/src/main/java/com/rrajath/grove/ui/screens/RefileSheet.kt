package com.rrajath.grove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.org.ArchiveTarget
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.ui.components.BrandMarkGlyph
import com.rrajath.grove.ui.components.HeadingTag
import com.rrajath.grove.ui.components.PickerSearchField
import com.rrajath.grove.ui.components.PickerSearchHeader
import com.rrajath.grove.ui.components.notebookIcon
import com.rrajath.grove.ui.components.pickerSearchResults
import com.rrajath.grove.ui.editor.LinkSearchItem
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.pluralCount
import com.rrajath.grove.ui.vault.RefileUiState
import com.rrajath.grove.ui.vault.filterRefileSearch
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.ui.vault.refileExcludedRanges
import kotlinx.coroutines.launch

/**
 * Two-step refile destination picker (design spec Gestures screen): choose a
 * notebook, then drill down per level: tapping a heading both selects it and
 * shows its children. "Refile here" targets the crumb's last heading (or the
 * file's top level).
 *
 * A live search field (shared with the insert-link picker) searches every file
 * and heading in the vault over [RefileUiState.searchIndex]; a non-empty query
 * swaps the drill-down list for results, and tapping one hands it to
 * [onSelectSearchResult], which leaves the picker where picking that target in
 * the drill-down would. The query is sheet-local: clearing it shows the
 * drill-down exactly where it was.
 *
 * File-scoped use (the capture template editor's heading picker): the caller
 * pre-picks one file and a search index of only its headings, and passes
 * [showBackAtTopLevel] = false so the sheet never returns to the notebook list,
 * plus the "Search headings" [searchPlaceholder] / [searchHeader].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefileSheet(
    state: RefileUiState,
    currentFileName: String?,
    currentDoc: OrgDocument?,
    onPickNotebook: (String) -> Unit,
    onDrillInto: (Int) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onArchive: () -> Unit,
    onPickLastUsed: () -> Unit,
    /** Applies a tapped search hit to the picker state; true once it is the selected target. */
    onSelectSearchResult: suspend (LinkSearchItem) -> Boolean,
    headerTitle: String = "Refile 1 note",
    confirmLabel: String = "Refile here",
    /** Shown in place of [confirmLabel] while sitting at a file's top level (no heading drilled into). */
    topLevelConfirmLabel: String? = null,
    /** False disables confirm at a file's top level, so a heading must be picked. */
    topLevelConfirmEnabled: Boolean = true,
    /** Crumb suffix after the file name while at its top level. */
    topLevelCrumbHint: String = "top level · or pick a heading",
    /** False hides the back button at a file's top level (a sheet scoped to one file). */
    showBackAtTopLevel: Boolean = true,
    searchPlaceholder: String? = null,
    searchHeader: String? = null,
    /** A small muted tag after a heading row's title (e.g. "ID"), or null for none. */
    headingTag: (OrgHeadline) -> String? = { null },
    /** Heading rows lead with the five-spoke brand asterisk (as search hits do) instead of `✳`. */
    brandMarkHeadings: Boolean = false,
) {
    val c = MaterialTheme.grove
    val doc = state.pickedDoc

    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }

    // Rows inside a source's own subtree are invalid targets: in the drill-down only
    // while browsing the source file, in search for that file's heading hits.
    // No exclusion at all when there's no active note (e.g. Settings' archive-location picker).
    val sourceRanges: List<IntRange> = remember(state.sourceLines, currentDoc) {
        refileExcludedRanges(currentDoc, state.sourceLines)
    }
    val excluded: List<IntRange> =
        if (state.pickedFile != null && state.pickedFile == currentFileName) sourceRanges else emptyList()

    val searching = query.isNotBlank()
    val results = remember(query, state.searchIndex, currentFileName, sourceRanges) {
        if (searching) {
            state.searchIndex?.let { filterRefileSearch(it, query, currentFileName, sourceRanges) }.orEmpty()
        } else emptyList()
    }

    fun selectSearchResult(item: LinkSearchItem) {
        scope.launch { if (onSelectSearchResult(item)) query = "" }
    }

    val crumb = when {
        doc == null -> "Choose a destination notebook"
        state.path.isEmpty() -> "${state.pickedFile?.removeSuffix(".org")} › $topLevelCrumbHint"
        else -> (listOf(state.pickedFile?.removeSuffix(".org")) +
                state.path.map { doc.headlineAtLine(it)?.title ?: "?" })
            .joinToString(" › ")
    }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            state.archiveTarget?.let { target ->
                ArchiveRow(sub = archiveCrumb(target), onClick = onArchive)
                Spacer(Modifier.height(10.dp))
            }
            state.lastUsedTarget?.let { target ->
                LastUsedRow(sub = archiveCrumb(target), onClick = onPickLastUsed)
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (doc != null && (showBackAtTopLevel || state.path.isNotEmpty())) {
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
                        headerTitle,
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, color = c.ink,
                    )
                    Text(crumb, fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2)
                }
            }
            Spacer(Modifier.height(12.dp))
            PickerSearchField(query = query, onQueryChange = { query = it }, placeholder = searchPlaceholder)
            Spacer(Modifier.height(10.dp))
            if (searching) PickerSearchHeader(searchHeader)
            val listState = remember(state.pickedFile, state.path) { LazyListState() }
            LazyColumn(
                Modifier.heightIn(max = 380.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (searching) {
                    pickerSearchResults(
                        results,
                        onClick = ::selectSearchResult,
                        headingTag = { hit ->
                            state.pickedDoc?.takeIf { hit.fileName == state.pickedFile }
                                ?.headlineAtLine(hit.lineIndex)?.let(headingTag)
                        },
                    )
                } else if (doc == null) {
                    items(state.notebooks.orEmpty(), key = { it.fileName }) { nb ->
                        RefileRow(
                            icon = notebookIcon(),
                            label = nb.fileName.removeSuffix(".org"),
                            sub = pluralCount(nb.noteCount, "heading"),
                            onClick = { onPickNotebook(nb.fileName) },
                        )
                    }
                } else {
                    val level = state.path.lastOrNull()?.let { doc.headlineAtLine(it) }
                    val rows = (level?.let { doc.directChildren(it) }
                        ?: doc.headlines.filter { doc.parent(it) == null })
                        .filter { h -> excluded.none { h.lineIndex in it } }
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
                        RefileRow(
                            glyph = "✳",
                            brandMark = brandMarkHeadings,
                            label = h.title,
                            labelTag = headingTag(h),
                            sub = headingCountLabel(doc, h),
                            onClick = { onDrillInto(h.lineIndex) },
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
                val enabled = state.pickedFile != null &&
                    (topLevelConfirmEnabled || state.path.isNotEmpty())
                val label = topLevelConfirmLabel?.takeIf { state.path.isEmpty() && doc != null }
                    ?: confirmLabel
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
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (enabled) c.accent else c.surface2)
                        .clickable(enabled = enabled, onClick = onConfirm)
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

private fun headingCountLabel(doc: OrgDocument, h: OrgHeadline): String? {
    val n = doc.directChildren(h).size
    return if (n == 0) null else pluralCount(n, "heading")
}

private fun archiveCrumb(target: ArchiveTarget): String {
    val fileLabel = target.fileName.removeSuffix(".org")
    return if (target.headingPath.isEmpty()) "$fileLabel › top level"
    else (listOf(fileLabel) + target.headingPath).joinToString(" › ")
}

/** Pinned quick action (design language of [RefileRow], filled with accentSoft to stand out). */
@Composable
private fun ArchiveRow(sub: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◆", fontFamily = PlexMono, fontSize = 15.sp, color = c.accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Archive",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, color = c.ink,
            )
            Text(sub, fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2)
        }
        Text("→", fontFamily = PlexSans, fontSize = 14.sp, color = c.accent)
    }
}

/** Pinned quick action for the destination of the most recent successful refile. */
@Composable
private fun LastUsedRow(sub: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↺", fontFamily = PlexMono, fontSize = 15.sp, color = c.accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Last used location",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, color = c.ink,
            )
            Text(sub, fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2)
        }
        Text("→", fontFamily = PlexSans, fontSize = 14.sp, color = c.accent)
    }
}

@Composable
private fun RefileRow(
    glyph: String? = null,
    brandMark: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    label: String,
    labelTag: String? = null,
    sub: String?,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(15.dp),
            )
        } else if (brandMark) {
            BrandMarkGlyph(size = 10.dp, color = c.accent)
        } else {
            Text(glyph.orEmpty(), fontFamily = PlexMono, fontSize = 15.sp, color = c.accent)
        }
        Spacer(Modifier.width(10.dp))
        // The tag sits on the title's baseline, so its caps line up with the title's lowercase.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontFamily = PlexMono, fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp, color = c.ink,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            labelTag?.let { HeadingTag(it, Modifier.padding(start = 7.dp).alignByBaseline()) }
        }
        sub?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3)
        }
    }
}
