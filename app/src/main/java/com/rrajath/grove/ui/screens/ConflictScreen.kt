package com.rrajath.grove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.rrajath.grove.R
import com.rrajath.grove.sync.ConflictResolution
import com.rrajath.grove.ui.components.StorageNoticeBanner
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.ConflictUiState
import com.rrajath.grove.ui.vault.ConflictViewModel

/** Conflict picker (design spec §10) over a sync tool's conflict copy (any `ConflictScheme`). */
@Composable
fun ConflictScreen(
    notebookId: String,
    onBack: () -> Unit,
    viewModel: ConflictViewModel = viewModel(factory = ConflictViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(notebookId) { viewModel.load(notebookId) }
    LaunchedEffect(state) { if (state is ConflictUiState.Resolved) onBack() }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph("←", onClick = onBack) },
                title = {
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(
                            "Resolve conflict",
                            style = MaterialTheme.typography.titleMedium, color = c.ink,
                        )
                        Text(notebookId, fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink2)
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is ConflictUiState.Loading, is ConflictUiState.Resolved -> {}
            is ConflictUiState.NoConflict -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No conflict on this notebook", fontFamily = PlexSans, color = c.ink2)
            }

            is ConflictUiState.CleanupNeeded -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                StorageNoticeBanner(
                    message = stringResource(
                        R.string.conflict_cleanup_needed,
                        s.copyName,
                        stringResource(s.provider),
                        stringResource(R.string.app_name),
                    ),
                )
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Done", onClick = onBack)
            }

            is ConflictUiState.Loaded -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // Warning banner
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(c.amberSoft)
                        .border(1.dp, c.amber, RoundedCornerShape(13.dp))
                        .padding(13.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(c.amber),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("!", fontFamily = PlexSans, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.surface)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "This file changed in two places. ${s.schemeName ?: "Your sync tool"} kept one " +
                                "version and saved the other as a conflict copy. Pick what to keep.",
                        fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 1.5.em, color = c.ink,
                    )
                }
                Spacer(Modifier.height(16.dp))

                UnifiedDiffCard(
                    dateLabel = s.copyLabel,
                    current = s.currentText,
                    copy = s.copyText,
                )
                Spacer(Modifier.height(18.dp))

                // Actions
                PrimaryButton("Keep both (merge under CONFLICT)") {
                    viewModel.resolve(s.fileName, ConflictResolution.KEEP_BOTH)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    SecondaryButton("Keep current", Modifier.weight(1f)) {
                        viewModel.resolve(s.fileName, ConflictResolution.KEEP_CURRENT)
                    }
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Keep copy", Modifier.weight(1f)) {
                        viewModel.resolve(s.fileName, ConflictResolution.KEEP_CONFLICT_COPY)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    s.copyKeptBy?.let { stringResource(R.string.conflict_copy_kept_note, stringResource(it)) }
                        ?: "Either way the conflict copy is removed afterwards.",
                    fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One row of a rendered unified diff: a hunk boundary label, or a single line. */
private sealed class DiffRow {
    data class Hunk(val label: String) : DiffRow()
    data class Line(val prefix: Char, val text: String) : DiffRow()
}

private val HUNK_HEADER = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** Line diff between [current] and [copy], 5 lines of context around each hunk. */
private fun computeUnifiedDiff(current: String, copy: String): List<DiffRow> {
    val originalLines = current.lines()
    val revisedLines = copy.lines()
    val patch = DiffUtils.diff(originalLines, revisedLines)
    if (patch.deltas.isEmpty()) return emptyList()

    val unified = UnifiedDiffUtils.generateUnifiedDiff("current", "copy", originalLines, patch, 5)
    val rows = mutableListOf<DiffRow>()
    for (line in unified.drop(2)) { // drop the "---"/"+++" file header lines
        val hunkMatch = HUNK_HEADER.find(line)
        when {
            hunkMatch != null -> rows += DiffRow.Hunk("Line ${hunkMatch.groupValues[1]}")
            line.startsWith("+") -> rows += DiffRow.Line('+', line.drop(1))
            line.startsWith("-") -> rows += DiffRow.Line('-', line.drop(1))
            line.startsWith(" ") -> rows += DiffRow.Line(' ', line.drop(1))
        }
    }
    return rows
}

@Composable
private fun UnifiedDiffCard(dateLabel: String, current: String, copy: String) {
    val c = MaterialTheme.grove
    val rows = remember(current, copy) { computeUnifiedDiff(current, copy) }
    Column {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.red)) { append("CURRENT VERSION") }
                append(" → ")
                withStyle(SpanStyle(color = c.green)) { append("CONFLICT COPY") }
                append(" · $dateLabel")
            },
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = 0.8.sp, color = c.ink3,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(13.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            if (rows.isEmpty()) {
                Text(
                    "No textual differences between the two copies.",
                    fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            rows.forEach { row ->
                when (row) {
                    is DiffRow.Hunk -> DiffHunkSeparator(row.label)
                    is DiffRow.Line -> DiffLineRow(row)
                }
            }
        }
    }
}

@Composable
private fun DiffHunkSeparator(label: String) {
    val c = MaterialTheme.grove
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = c.line)
        Text(
            label,
            fontFamily = PlexSans, fontSize = 10.5.sp, color = c.ink3,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = c.line)
    }
}

@Composable
private fun DiffLineRow(row: DiffRow.Line) {
    val c = MaterialTheme.grove
    val (bg, fg, gutter) = when (row.prefix) {
        '+' -> Triple(c.greenSoft, c.green, "+")
        '-' -> Triple(c.redSoft, c.red, "-")
        else -> Triple(Color.Transparent, c.ink, " ")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 1.dp),
    ) {
        Text(
            gutter,
            fontFamily = PlexMono, fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp, color = fg,
            modifier = Modifier.width(14.dp),
        )
        Text(
            row.text.ifEmpty { " " },
            fontFamily = PlexMono, fontSize = 12.5.sp, lineHeight = 1.5.em,
            color = if (row.prefix == ' ') c.ink else fg,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(c.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 14.5.sp, color = c.accentInk,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = c.ink,
        )
    }
}
