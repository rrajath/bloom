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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.org.ArchiveTarget
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.ui.components.notebookIcon
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.pluralCount
import com.rrajath.grove.ui.vault.RefileUiState
import com.rrajath.grove.ui.vault.headlineAtLine

/**
 * Two-step refile destination picker (design spec Gestures screen): choose a
 * notebook, then drill down per level: tapping a heading both selects it and
 * shows its children. "Refile here" targets the crumb's last heading (or the
 * file's top level).
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
    headerTitle: String = "Refile 1 note",
    confirmLabel: String = "Refile here",
    /** Shown in place of [confirmLabel] while sitting at a file's top level (no heading drilled into). */
    topLevelConfirmLabel: String? = null,
) {
    val c = MaterialTheme.grove
    val doc = state.pickedDoc

    // For a same-file refile, rows inside a source's own subtree are invalid targets.
    // No exclusion at all when there's no active note (e.g. Settings' archive-location picker).
    val excluded: List<IntRange> = remember(state.pickedFile, state.sourceLines, currentDoc) {
        if (currentDoc != null && state.pickedFile == currentFileName) {
            state.sourceLines.mapNotNull { line ->
                currentDoc.headlineAtLine(line)?.let { it.lineIndex until currentDoc.subtreeEndLine(it) }
            }
        } else emptyList()
    }

    val crumb = when {
        doc == null -> "Choose a destination notebook"
        state.path.isEmpty() -> "${state.pickedFile?.removeSuffix(".org")} › top level · or pick a heading"
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
                        headerTitle,
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, color = c.ink,
                    )
                    Text(crumb, fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2)
                }
            }
            Spacer(Modifier.height(12.dp))
            val listState = remember(state.pickedFile, state.path) { LazyListState() }
            LazyColumn(
                Modifier.heightIn(max = 380.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (doc == null) {
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
                            label = h.title,
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
                val enabled = state.pickedFile != null
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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    label: String,
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
        } else {
            Text(glyph.orEmpty(), fontFamily = PlexMono, fontSize = 15.sp, color = c.accent)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontFamily = PlexMono, fontWeight = FontWeight.Medium,
            fontSize = 14.5.sp, color = c.ink,
            modifier = Modifier.weight(1f),
        )
        sub?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3)
        }
    }
}
