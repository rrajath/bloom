package com.rrajath.grove.ui.editor

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.ui.components.PlanningDatesScreen
import com.rrajath.grove.ui.screens.NoteDialog
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.priorityColor
import com.rrajath.grove.ui.theme.prioritySoftColor

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
) {
    val c = MaterialTheme.grove
    var planningOpen by remember { mutableStateOf(false) }
    var noteDialogOpen by remember { mutableStateOf(false) }

    // Open fully: the half-expanded state cut the action row off below the fold,
    // behind the nav bar with 3-button navigation, so its taps hit Home.
    ModalBottomSheet(
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
            SheetLabel("State")
            // FlowRow, not Row: vaults can define arbitrarily many TODO keywords,
            // which must wrap onto more lines instead of squeezing each other.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val current = headline?.keyword
                StateChip(
                    label = "none",
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

            SheetLabel("Priority")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf<Char?>(null, 'A', 'B', 'C').forEach { p ->
                    StateChip(
                        label = p?.let { "#$it" } ?: "none",
                        active = !priorityMixed && headline?.priority == p,
                        fg = p?.let { c.priorityColor(it) } ?: c.ink2,
                        bg = p?.let { c.prioritySoftColor(it) } ?: c.surface2,
                    ) { onSetPriority(p) }
                }
            }

            SheetLabel("Tags")
            val selectedTags = headline?.tags.orEmpty()
            var tagQuery by remember { mutableStateOf("") }
            var tagMenuOpen by remember { mutableStateOf(false) }

            fun pickTag(tag: String) {
                val cleaned = tag.trim()
                if (cleaned.isNotEmpty()) onSetTags((selectedTags + cleaned).distinct())
                tagQuery = ""
            }

            Box {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it; tagMenuOpen = true },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = PlexSans, fontSize = 14.sp, color = c.ink),
                    placeholder = { Text("Search or create tag", fontFamily = PlexSans, color = c.ink3) },
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
                                Text(
                                    "Create tag “$tagQuery”",
                                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.accent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { pickTag(tagQuery) }
                                        .padding(horizontal = 8.dp, vertical = 9.dp),
                                )
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
                    "tap an existing tag to remove it",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SheetLabel("Dates")
            CombinedPlanningRow(
                scheduled = headline?.planning?.scheduled,
                deadline = headline?.planning?.deadline,
                active = headline?.dedicatedActiveTimestamps.orEmpty(),
                onPick = { planningOpen = true },
            )

            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "+ Add note",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, color = c.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { noteDialogOpen = true }
                        .padding(vertical = 6.dp),
                )
                if (showRefile) {
                    Text(
                        "→ Refile",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, color = c.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRefile)
                            .padding(vertical = 6.dp),
                    )
                }
                if (showFavorite) {
                    Text(
                        if (isFavorite) "★ Unfavorite" else "★ Favorite",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, color = c.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleFavorite)
                            .padding(vertical = 6.dp),
                    )
                }
                if (headline?.id == null) {
                    Text(
                        "+ Generate org-id",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, color = c.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onGenerateId)
                            .padding(vertical = 6.dp),
                    )
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

/**
 * Positions a popup so its bottom edge sits [gapPx] above the anchor, left edges
 * aligned. Opens downward instead only when there isn't room above (the field is
 * near the top of the screen). Keeps the tag suggestion list clear of the IME,
 * which docks directly under the focused text field.
 */
private class FlyUpPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (above >= 0) above else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
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
        if (scheduled == null && deadline == null && active.isEmpty()) {
            Text("set date…", fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink3)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scheduled?.let {
                    Text(
                        "SCHEDULED " + it.format(),
                        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp, color = c.blue,
                    )
                }
                deadline?.let {
                    Text(
                        "DEADLINE " + it.format(),
                        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp, color = c.red,
                    )
                }
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
