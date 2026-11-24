package com.rrajath.grove.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.PlexSerif
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.countLabel
import com.rrajath.grove.ui.util.uppercaseForUi
import com.rrajath.grove.ui.vault.LinkedReferenceFileGroup
import com.rrajath.grove.ui.vault.LinkedReferenceHit
import com.rrajath.grove.ui.vault.LinkedReferencesResult
import com.rrajath.grove.ui.vault.UnlinkedMentionHit

/**
 * Full "References to <title>" bottom sheet (design: Roam Links.dc.html,
 * variant 1b): grouped by source file, with a Linked/Unlinked tab pair. Opened
 * by tapping the collapsed [LinkedReferencesBar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedReferencesSheet(
    title: String,
    result: LinkedReferencesResult,
    onOpenReference: (fileName: String, lineIndex: Int, id: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Linked, 1 = Unlinked
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(
                    stringResource(R.string.links_references_to).uppercaseForUi(),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp, letterSpacing = 0.07.em, color = c.ink3,
                )
                Text(
                    title,
                    fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold,
                    fontSize = 21.sp, color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ReferenceTab(
                        label = stringResource(R.string.links_tab_backlinks, result.linkedCount),
                        selected = tab == 0,
                        onClick = { tab = 0 },
                    )
                    if (result.unlinkedCount > 0) {
                        ReferenceTab(
                            label = stringResource(R.string.links_tab_unlinked, result.unlinkedCount),
                            selected = tab == 1,
                            onClick = { tab = 1 },
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            LazyColumn(Modifier.weight(1f)) {
                if (tab == 0 || result.unlinkedCount == 0) {
                    if (result.linkedByFile.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.links_no_backlinks),
                                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink3,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(result.linkedByFile, key = { it.fileName }) { group ->
                        LinkedFileGroupSection(
                            group = group,
                            colors = c,
                            onOpen = onOpenReference,
                        )
                    }
                } else {
                    if (result.unlinked.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.links_no_unlinked),
                                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink3,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(result.unlinked, key = { it.fileName to it.lineIndex }) { hit ->
                        UnlinkedMentionRow(hit = hit, colors = c, onOpen = onOpenReference)
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            HorizontalDivider(color = c.line)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.links_tap_to_jump),
                    fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.action_close),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp, color = c.accent,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun ReferenceTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) c.accent else c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp, color = if (selected) c.accentInk else c.ink2,
        )
    }
}

@Composable
private fun LinkedFileGroupSection(
    group: LinkedReferenceFileGroup,
    colors: GroveColors,
    onOpen: (fileName: String, lineIndex: Int, id: String?) -> Unit,
) {
    val c = colors
    Column(Modifier.padding(bottom = 14.dp)) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            // Top, not CenterVertically: the icon sits level with the file name,
            // not between it and the folder/count line.
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                notebookIcon(), contentDescription = null, tint = c.accent,
                modifier = Modifier.padding(top = 1.dp).size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Two lines so a long (e.g. timestamped roam) name wraps on its own line
            // instead of squeezing the count into a one-letter-wide column.
            Column(Modifier.weight(1f)) {
                val folder = group.fileName.substringBeforeLast('/', missingDelimiterValue = "")
                val count = countLabel(R.plurals.count_references, group.hits.size)
                Text(
                    group.fileName.substringAfterLast('/'), fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp, color = c.ink,
                )
                Text(
                    if (folder.isEmpty()) count else "$folder/ · $count",
                    fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(14.dp)),
        ) {
            group.hits.forEachIndexed { index, hit ->
                if (index > 0) HorizontalDivider(color = c.line)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit.fileName, hit.lineIndex, hit.orgId ?: hit.customId) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (hit.crumb.isNotEmpty()) {
                        Text(
                            hit.crumb, fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        highlightedOrgLinkSnippet(hit, c),
                        fontFamily = PlexSerif, fontSize = 14.5.sp, lineHeight = 1.5.em,
                        color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlinkedMentionRow(
    hit: UnlinkedMentionHit,
    colors: GroveColors,
    onOpen: (fileName: String, lineIndex: Int, id: String?) -> Unit,
) {
    val c = colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.fileName, hit.lineIndex, hit.orgId ?: hit.customId) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        // File name on its own (wrapping) line; folder and heading crumb beneath,
        // so a long roam file name can't squeeze the crumb into a sliver.
        Text(
            hit.fileName.substringAfterLast('/'),
            fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.ink,
        )
        val folder = hit.fileName.substringBeforeLast('/', missingDelimiterValue = "")
        val context = listOf(if (folder.isEmpty()) "" else "$folder/", hit.crumb).filter { it.isNotEmpty() }
        if (context.isNotEmpty()) {
            Text(
                context.joinToString(" · "), fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            highlightedPlainSnippet(hit, c),
            fontFamily = PlexSerif, fontSize = 14.5.sp, lineHeight = 1.5.em,
            color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = c.line)
}

/** [hit]'s body rendered with org markup, plus a soft background over the link that resolved it. */
private fun highlightedOrgLinkSnippet(hit: LinkedReferenceHit, c: GroveColors): AnnotatedString {
    val base = annotateOrgInline(hit.body, c)
    val link = orgInlineLinks(hit.body).firstOrNull { it.target == hit.matchedTarget } ?: return base
    val end = (link.range.last + 1).coerceAtMost(base.length)
    if (link.range.first >= end) return base
    return buildAnnotatedString {
        append(base)
        addStyle(
            SpanStyle(background = c.blueSoft, color = c.blue, fontWeight = FontWeight.SemiBold),
            link.range.first, end,
        )
    }
}

/** [hit]'s plain-text body (markup stripped), with a soft highlight over the mentioned title. */
private fun highlightedPlainSnippet(hit: UnlinkedMentionHit, c: GroveColors): AnnotatedString {
    val plain = orgInlinePlainText(hit.body)
    val end = (hit.plainTextRange.last + 1).coerceAtMost(plain.length)
    val start = hit.plainTextRange.first.coerceAtMost(end)
    return buildAnnotatedString {
        append(plain)
        addStyle(SpanStyle(color = c.ink, textDecoration = TextDecoration.Underline), start, end)
    }
}
