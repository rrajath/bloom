package com.rrajath.grove.ui.editor

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rrajath.grove.R
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgTags
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.ui.components.FlyUpPositionProvider
import com.rrajath.grove.ui.components.GroveModalBottomSheet
import com.rrajath.grove.ui.components.PlanningDatesScreen
import com.rrajath.grove.ui.components.orgInlinePlainText
import com.rrajath.grove.ui.screens.NoteDialog
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.priorityColor
import com.rrajath.grove.ui.theme.prioritySoftColor
import com.rrajath.grove.ui.util.asString
import com.rrajath.grove.ui.util.text
import kotlinx.coroutines.launch

/**
 * Note metadata sheet (PRD §5.2): state, priority, tags, SCHEDULED, DEADLINE.
 * Stateless: shared by the editor (mutates the in-memory buffer) and read mode
 * (writes each change straight to disk), which each wire the callbacks to
 * their own view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSheet(
    headline: OrgHeadline?,
    keywords: OrgKeywords,
    allTags: List<String>,
    onChangeKeyword: (String?) -> Unit,
    onSetPriority: (Char?) -> Unit,
    onSetTags: (List<String>) -> Unit,
    onSetPlanningDates: (OrgTimestamp?, OrgTimestamp?, List<OrgTimestamp>) -> Unit,
    onAddNote: (String) -> Unit,
    onGenerateId: () -> Unit,
    onRefile: () -> Unit,
    onDismiss: () -> Unit,
    /** Hidden for an unsaved capture draft: nothing exists on disk yet to refile. */
    showRefile: Boolean = true,
    /** Read mode only: offer a Favorite toggle that pins this note to the nav drawer. */
    showFavorite: Boolean = false,
    /** Whether the note shown is already a favorite; flips the action's label to "Favorited". */
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    /** Several headings with differing keywords: no state chip is current. */
    stateMixed: Boolean = false,
    /** Several headings with differing priorities: no priority chip is current. */
    priorityMixed: Boolean = false,
    /** Several headings: date kinds whose values differ, shown as "multiple". */
    datesMixed: Set<PlanningKind> = emptySet(),
    /**
     * Several headings: replaces [onSetPlanningDates] and also gets the mixed
     * kinds the user removed from all of them on the Dates screen.
     */
    onSetPlanningDatesBulk: (
        (OrgTimestamp?, OrgTimestamp?, List<OrgTimestamp>, Set<PlanningKind>) -> Unit
    )? = null,
    /**
     * Header naming what the sheet edits, in place of [headline]'s title: e.g.
     * "3 headings" for a multi-select, or the intro's not-yet-created heading.
     */
    targetLabel: String? = null,
    /** Called after tapping "Copy ID" puts the heading's existing `:ID:` on the clipboard. */
    onIdCopied: () -> Unit = {},
    /** When non-null, shows the destructive Delete tile in the action grid. */
    onDelete: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val untitled = stringResource(R.string.metadata_untitled_heading)
    val header = targetLabel
        ?: headline?.let { h -> orgInlinePlainText(h.title).trim().ifEmpty { untitled } }
    val stars = if (targetLabel == null) headline?.let { "*".repeat(it.level.coerceAtLeast(1)) } else null
    var planningOpen by remember { mutableStateOf(false) }
    var noteDialogOpen by remember { mutableStateOf(false) }

    // Open fully: the half-expanded state cut the action row off below the fold,
    // behind the nav bar with 3-button navigation, so its taps hit Home.
    GroveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 30.dp),
        ) {
            // Before Android 9 a new window gives initial focus to its first
            // focusable view even in touch mode. Here that's the Tags field, so
            // the sheet opened with the keyboard up, covering the action row.
            // This blank target ahead of it takes that focus instead; API 28+
            // never assigns it, so the target isn't needed there.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Box(Modifier.clearAndSetSemantics {}.focusable())
            }
            // Names the heading every chip below acts on, so it's clear which one
            // the sheet edits (the cursor's heading in Edit mode, a row in Outline).
            // A single heading leads with its org stars (as the attach sheet does);
            // a multi-select or intro label has no one level, so it shows none.
            if (header != null) {
                Row(
                    Modifier.padding(top = 2.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (stars != null) {
                        Text(
                            stars,
                            fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = c.synStar,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    Text(
                        header,
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, lineHeight = 21.sp, color = c.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                HorizontalDivider(color = c.line)
            }
            SheetLabel(stringResource(R.string.metadata_section_state))
            // FlowRow, not Row: vaults can define arbitrarily many TODO keywords,
            // which must wrap onto more lines instead of squeezing each other.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val current = headline?.keyword
                StateChip(
                    label = stringResource(R.string.metadata_none),
                    active = !stateMixed && current == null,
                    fg = c.ink2, bg = c.surface2,
                ) { onChangeKeyword(null) }
                keywords.all.forEach { kw ->
                    val done = keywords.isDone(kw)
                    StateChip(
                        label = kw,
                        active = !stateMixed && current == kw,
                        fg = if (done) c.green else c.amber,
                        bg = if (done) c.greenSoft else c.amberSoft,
                    ) {
                        onChangeKeyword(kw)
                    }
                }
            }

            SheetLabel(stringResource(R.string.metadata_section_priority))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf<Char?>(null, 'A', 'B', 'C').forEach { p ->
                    StateChip(
                        label = p?.let { "#$it" } ?: stringResource(R.string.metadata_none),
                        active = !priorityMixed && headline?.priority == p,
                        fg = p?.let { c.priorityColor(it) } ?: c.ink2,
                        bg = p?.let { c.prioritySoftColor(it) } ?: c.surface2,
                    ) { onSetPriority(p) }
                }
            }

            SheetLabel(stringResource(R.string.metadata_section_tags))
            val selectedTags = headline?.tags.orEmpty()
            var tagQuery by remember { mutableStateOf("") }
            var tagMenuOpen by remember { mutableStateOf(false) }

            fun pickTag(tag: String) {
                val cleaned = tag.trim()
                if (OrgTags.error(cleaned) != null) return
                onSetTags((selectedTags + cleaned).distinct())
                tagQuery = ""
            }

            Box {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it; tagMenuOpen = true },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = PlexSans, fontSize = 14.sp, color = c.ink),
                    placeholder = { Text(stringResource(R.string.metadata_tag_search), fontFamily = PlexSans, color = c.ink3) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) tagMenuOpen = true },
                )
                // Existing tags matching the query, minus ones already applied to this
                // heading (picking one that's already on would be a silent no-op).
                val filtered = remember(tagQuery, allTags, selectedTags) {
                    allTags.filter { it !in selectedTags && it.contains(tagQuery, ignoreCase = true) }.take(8)
                }
                val exactMatch = allTags.any { it.equals(tagQuery, ignoreCase = true) }
                // A new tag must survive being written to the headline (OrgTags); say why when it can't.
                val createError = if (tagQuery.isNotBlank() && !exactMatch) OrgTags.error(tagQuery)?.text() else null
                val showCreate = tagQuery.isNotBlank() && !exactMatch
                val hasSuggestions = showCreate || filtered.isNotEmpty()

                if (tagMenuOpen && hasSuggestions) {
                    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
                    Popup(
                        // Fly up: the list opens above the field so it never sits
                        // under the IME, which is docked directly below the field
                        // once it has focus.
                        popupPositionProvider = remember(gapPx) { FlyUpPositionProvider(gapPx) },
                        // Dismissal must not wipe what's been typed: the popup can be
                        // dismissed by transient causes (keyboard/sheet still settling
                        // into place) that have nothing to do with the user abandoning
                        // their input. Only picking a tag clears the query.
                        onDismissRequest = { tagMenuOpen = false },
                        // Non-focusable: the popup must not steal IME focus from the
                        // OutlinedTextField above it, or typed characters never reach
                        // the field it's supposed to be filtering as you type.
                        properties = PopupProperties(focusable = false),
                    ) {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                // surface2 (one layer up from the sheet's own surface)
                                // plus a hairline border does the separating: the design
                                // system reserves Modifier.shadow for the FAB and sheets.
                                .background(c.surface2)
                                .border(1.dp, c.line, RoundedCornerShape(12.dp))
                                .width(230.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            if (showCreate) {
                                if (createError != null) {
                                    Text(
                                        createError.asString(),
                                        fontFamily = PlexSans, fontSize = 13.sp, color = c.red,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 9.dp)
                                            .testTag("tag_create_error"),
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.metadata_tag_create, tagQuery),
                                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.accent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { pickTag(tagQuery) }
                                            .padding(horizontal = 8.dp, vertical = 9.dp),
                                    )
                                }
                                if (filtered.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    HorizontalDivider(color = c.line)
                                }
                            }
                            filtered.forEach { tag ->
                                Text(
                                    tag,
                                    fontFamily = PlexSans, fontSize = 13.sp, color = c.ink,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { pickTag(tag) }
                                        .padding(horizontal = 8.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (selectedTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TagsColonRow(tags = selectedTags, onRemove = { tag -> onSetTags(selectedTags - tag) })
                Text(
                    stringResource(R.string.metadata_tag_remove_hint),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SheetLabel(stringResource(R.string.metadata_section_dates))
            CombinedPlanningRow(
                scheduled = headline?.planning?.scheduled,
                deadline = headline?.planning?.deadline,
                active = headline?.dedicatedActiveTimestamps.orEmpty(),
                mixed = datesMixed,
                onPick = { planningOpen = true },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = c.line)
            Spacer(Modifier.height(14.dp))

            val idClipLabel = stringResource(R.string.metadata_generate_id)
            val existingId = headline?.id
            val actions = buildList {
                add(
                    MetadataAction(
                        icon = Icons.Default.Add,
                        label = stringResource(R.string.metadata_add_note),
                        testTag = "metadata_action_add_note",
                        onClick = { noteDialogOpen = true },
                    ),
                )
                if (showRefile) {
                    add(
                        MetadataAction(
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            label = stringResource(R.string.metadata_refile),
                            testTag = "metadata_action_refile",
                            onClick = onRefile,
                        ),
                    )
                }
                if (showFavorite) {
                    add(
                        MetadataAction(
                            icon = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            label = stringResource(if (isFavorite) R.string.metadata_unfavorite else R.string.metadata_favorite),
                            tone = if (isFavorite) ActionTileTone.FAVORITE else ActionTileTone.DEFAULT,
                            testTag = "metadata_action_favorite",
                            onClick = onToggleFavorite,
                        ),
                    )
                }
                if (existingId != null) {
                    add(
                        MetadataAction(
                            icon = Icons.Outlined.ContentCopy,
                            label = stringResource(R.string.metadata_copy_id),
                            testTag = "metadata_action_copy_id",
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(idClipLabel, existingId)))
                                    onIdCopied()
                                }
                            },
                        ),
                    )
                } else {
                    add(
                        MetadataAction(
                            icon = Icons.Default.Tag,
                            label = stringResource(R.string.metadata_generate_id),
                            testTag = "metadata_action_org_id",
                            onClick = onGenerateId,
                        ),
                    )
                }
                if (onDelete != null) {
                    add(
                        MetadataAction(
                            icon = Icons.Outlined.Delete,
                            label = stringResource(R.string.action_delete),
                            tone = ActionTileTone.DESTRUCTIVE,
                            testTag = "metadata_action_delete",
                            onClick = onDelete,
                        ),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowItems.forEach { action ->
                            MetadataActionTile(
                                action = action,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (planningOpen) {
        val scheduled = headline?.planning?.scheduled
        val deadline = headline?.planning?.deadline
        val activeDates = headline?.dedicatedActiveTimestamps.orEmpty()
        PlanningDatesScreen(
            title = headline?.title.orEmpty(),
            scheduled = scheduled,
            deadline = deadline,
            active = activeDates,
            // Opens on whichever surface is most relevant: an unset planning
            // field when only one is set; the ACTIVE tab when only events exist;
            // otherwise SCHEDULED.
            focus = when {
                scheduled == null && deadline != null -> PlanningKind.DEADLINE
                scheduled == null && deadline == null && activeDates.isNotEmpty() -> PlanningKind.ACTIVE
                else -> PlanningKind.SCHEDULED
            },
            onDismiss = { planningOpen = false },
            onConfirm = { sched, dead, active ->
                onSetPlanningDates(sched, dead, active)
                planningOpen = false
            },
            mixed = datesMixed,
            onConfirmBulk = onSetPlanningDatesBulk?.let { bulk ->
                { sched, dead, active, removed ->
                    bulk(sched, dead, active, removed)
                    planningOpen = false
                }
            },
        )
    }

    if (noteDialogOpen) {
        NoteDialog(
            title = headline?.title.orEmpty(),
            onDismiss = { noteDialogOpen = false },
            onConfirm = { note ->
                onAddNote(note)
                noteDialogOpen = false
            },
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, letterSpacing = 1.sp,
        color = MaterialTheme.grove.accent,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

/** Selected tags rendered as one org-style colon string (`:work:planning:`); tapping
 *  a segment removes that tag. Zero horizontal spacing keeps the colons flush so the
 *  run reads as a single continuous string, not a list of separate chips. */
@Composable
private fun TagsColonRow(tags: List<String>, onRemove: (String) -> Unit) {
    val c = MaterialTheme.grove
    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        // Matches each tag segment's own vertical padding below: without it, the
        // leading colon (unpadded) sits higher than the padded "tag:" segments,
        // reading as a baseline misalignment even though it's the same glyph.
        Text(":", fontFamily = PlexMono, fontSize = 11.sp, color = c.synTag, modifier = Modifier.padding(vertical = 4.dp))
        tags.forEach { tag ->
            Text(
                "$tag:",
                fontFamily = PlexMono, fontSize = 11.sp, color = c.synTag,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onRemove(tag) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * [fg] always paints the label, active or not, so a done-type keyword (green)
 * reads the same as its badge everywhere else in the app (Outline rows, Search
 * results, Agenda rows); the active chip additionally gets the filled [bg] and
 * bold weight to mark it as the current selection.
 */
@Composable
private fun StateChip(
    label: String,
    active: Boolean,
    fg: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) bg else c.surface2.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontFamily = PlexMono,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            color = fg,
            // A long keyword must keep its chip on one line; FlowRow wraps chips,
            // never their labels.
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Combined SCHEDULED/DEADLINE summary: both values (blue/red) in one tappable pill. */
@Composable
private fun CombinedPlanningRow(
    scheduled: OrgTimestamp?,
    deadline: OrgTimestamp?,
    active: List<OrgTimestamp>,
    onPick: () -> Unit,
    /** Kinds that differ across several headings: a "multiple" line in place of a date. */
    mixed: Set<PlanningKind> = emptySet(),
) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface2)
            .clickable(onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (scheduled == null && deadline == null && active.isEmpty() && mixed.isEmpty()) {
            Text(stringResource(R.string.metadata_set_date), fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink3)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (scheduled == null && PlanningKind.SCHEDULED in mixed) MultipleLine("SCHEDULED", c.blue)
                scheduled?.let {
                    Text(
                        "SCHEDULED " + it.format(), // i18n: org planning keyword
                        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp, color = c.blue,
                    )
                }
                if (deadline == null && PlanningKind.DEADLINE in mixed) MultipleLine("DEADLINE", c.red)
                deadline?.let {
                    Text(
                        "DEADLINE " + it.format(), // i18n: org planning keyword
                        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp, color = c.red,
                    )
                }
                if (active.isEmpty() && PlanningKind.ACTIVE in mixed) MultipleLine("●", c.violet)
                active.forEach {
                    Text(
                        "● " + it.format(),
                        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp, color = c.violet,
                    )
                }
            }
        }
    }
}

/** "SCHEDULED multiple": the kind keeps its colour, the word is `ink3` like "set date…". */
@Composable
private fun MultipleLine(label: String, color: Color) {
    val c = MaterialTheme.grove
    val multiple = stringResource(R.string.metadata_multiple)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(label) }
            withStyle(SpanStyle(color = c.ink3, fontWeight = FontWeight.Normal)) { append(" $multiple") }
        },
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
    )
}

private enum class ActionTileTone { DEFAULT, FAVORITE, DESTRUCTIVE }

private class MetadataAction(
    val icon: ImageVector,
    val label: String,
    val tone: ActionTileTone = ActionTileTone.DEFAULT,
    val testTag: String,
    val onClick: () -> Unit,
)

/** Squircle icon-above-label tile for the 3-column action grid at the bottom of the metadata sheet. */
@Composable
private fun MetadataActionTile(
    action: MetadataAction,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    val bg = when (action.tone) {
        ActionTileTone.DEFAULT -> c.surface2
        ActionTileTone.FAVORITE -> c.amberSoft
        ActionTileTone.DESTRUCTIVE -> c.redSoft
    }
    val iconTint = when (action.tone) {
        ActionTileTone.DEFAULT -> c.accent
        ActionTileTone.FAVORITE -> c.amber
        ActionTileTone.DESTRUCTIVE -> c.red
    }
    val labelColor = when (action.tone) {
        ActionTileTone.DEFAULT -> c.ink
        ActionTileTone.FAVORITE -> c.amber
        ActionTileTone.DESTRUCTIVE -> c.red
    }
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .clickable(onClick = action.onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .testTag(action.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = action.label,
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            lineHeight = 14.sp,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
