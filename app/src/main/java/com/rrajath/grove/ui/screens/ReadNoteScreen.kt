package com.rrajath.grove.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rrajath.grove.ui.components.GroveDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.data.containsIdOf
import com.rrajath.grove.data.matches
import com.rrajath.grove.org.BlockParser
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.org.OrgBlock
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.ui.components.ReadEditToggle
import com.rrajath.grove.ui.components.rememberTapFlash
import com.rrajath.grove.ui.vault.RepeatAdvance
import com.rrajath.grove.ui.components.CollapsibleBlockSection
import com.rrajath.grove.ui.components.CollapsibleKvSection
import com.rrajath.grove.ui.components.CollapsibleLogSection
import com.rrajath.grove.ui.components.FavoriteStar
import com.rrajath.grove.ui.components.GroveToast
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.GroveUndoSnackbar
import com.rrajath.grove.ui.editor.MetadataSheet
import com.rrajath.grove.ui.components.OrgTableView
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.components.annotateOrgInline
import com.rrajath.grove.ui.components.doubleTapToEdit
import com.rrajath.grove.ui.components.EditorMenuFab
import com.rrajath.grove.ui.components.linkPressHandler
import com.rrajath.grove.ui.components.orgInlineLinks
import com.rrajath.grove.ui.components.ScrollJumpButtons
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDotBadge
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.PlexSerif
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.priorityColor
import com.rrajath.grove.ui.util.IntSetSaver
import com.rrajath.grove.ui.vault.DocumentUiState
import com.rrajath.grove.ui.vault.DocumentViewModel
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.PendingEdit
import com.rrajath.grove.ui.vault.compactFileLabel
import com.rrajath.grove.ui.vault.headlineFor

/**
 * Read mode per design spec §5: rendered note (own body + subtree).
 * Edit mode is M5; the toggle shows a hint until then.
 */
@Composable
fun ReadNoteScreen(
    noteRef: NoteRef,
    onBack: () -> Unit,
    onOpenNote: (NoteRef) -> Unit,
    /**
     * Double-tap anywhere switches to edit mode. Double-tapping a specific
     * subheading's title/body passes that heading's line index so the editor
     * can land the cursor there instead of at the top; `null` for taps on
     * the note's own heading/blank space or the Read/Edit toggle.
     */
    onEdit: (Int?) -> Unit,
    /**
     * A breadcrumb segment was tapped: `null` for the file/notebook segment
     * (opens the full outline), or a heading's line index (narrows the
     * outline to that heading's subtree).
     */
    onOpenBreadcrumb: (Int?) -> Unit = {},
    /**
     * A tapped org link resolved to a whole file (`[[file:other.org]]`): open
     * that file's outline. Heading/`id:` links go through [onOpenNote] instead.
     */
    onOpenOutline: (fileName: String) -> Unit = {},
    /**
     * Double-tapping a `:PROPERTIES:` or `:LOGBOOK:` drawer opens an editor scoped to just
     * that drawer. [kind] is `"headingProps"` or `"headingLog"`; [ref] identifies the
     * owning heading.
     */
    onOpenDrawer: (kind: String, ref: NoteRef) -> Unit = { _, _ -> },
    /**
     * Double-tapping a `#+BEGIN_x … #+END_x` block opens an editor scoped to just that
     * block. [line] is the absolute doc line of its `#+BEGIN` (or first affiliated line).
     */
    onOpenBlock: (fileName: String, line: Int) -> Unit = { _, _ -> },
    /** Settings toggle: show collapsible sections for `:PROPERTIES:`/`:LOGBOOK:` drawers. */
    showPropertyDrawers: Boolean = true,
    /** Settings § Notes: font-size lever for the rendered note. App chrome is unaffected. */
    readModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /** Favorited headlines in this file, matched per-heading by customId, marked with a ★. */
    favorites: List<FavoriteNote> = emptyList(),
    /**
     * The intro (heading-less content) was just given a blank heading because a
     * metadata action needed one; the arg is that heading's line. The host re-opens
     * the note at that line so it continues as an ordinary note. Intro refs only.
     */
    onPromotedToHeading: (Int) -> Unit = {},
    /**
     * The note editor's unsaved buffer for this note, when it holds one. Read mode
     * renders it in place of what is on disk, and folds its own mutations back into
     * it, so switching Read ⇄ Edit never has to write the file.
     */
    pendingEdit: PendingEdit? = null,
    /** A read-mode mutation was folded into [pendingEdit]; the arg is the new subtree text. */
    onPendingBufferChanged: (String) -> Unit = {},
    /** [pendingEdit] had to go to disk with a restructuring mutation; the editor should let it go. */
    onPendingPersisted: () -> Unit = {},
    /** Tapping the unsaved-changes indicator: write [pendingEdit] to disk now. */
    onSavePending: () -> Unit = {},
    viewModel: DocumentViewModel = viewModel(factory = DocumentViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val refileState by viewModel.refile.collectAsStateWithLifecycle()
    val introPromotedLine by viewModel.introPromotedLine.collectAsStateWithLifecycle()
    LaunchedEffect(introPromotedLine) {
        introPromotedLine?.let { line ->
            viewModel.clearIntroPromoted()
            onPromotedToHeading(line)
        }
    }
    val snack by viewModel.snack.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val repeatAdvance by viewModel.repeatAdvanced.collectAsStateWithLifecycle()
    // Resolves a tapped org link (heading/id: → Read mode, whole file → outline,
    // external scheme → OS, unresolved → toast). See DocumentViewModel.openOrgLink.
    val onOpenLink: (String) -> Unit = remember(viewModel, onOpenNote, onOpenOutline, noteRef.fileName) {
        { target -> viewModel.openOrgLink(target, noteRef.fileName, onOpenNote, onOpenOutline) }
    }
    var metadataOpen by remember { mutableStateOf(false) }
    // Set on a completed move (refileConfirm/refileToArchive/refileToLastUsed), not a plain
    // cancel/back-out. The move itself (file write + the "Refiled to X" snack) runs async in
    // viewModel.viewModelScope *after* `refile` is already nulled out to close the sheet, so
    // this can't just watch `refile`: leaving immediately would pop this screen's back-stack
    // entry — and with it viewModel's scope — out from under that still-in-flight coroutine.
    // Instead it waits for the snack this move ends with to actually appear and then clear,
    // which both guarantees the write has landed and gives the user the undo window this
    // screen closing shouldn't cut short. A tap on Undo restores this exact note in place
    // (same fileName/lineIndex), so it clears the flag instead of leaving.
    var refileAwaitingLeave by remember { mutableStateOf(false) }
    var refileSnackSeen by remember { mutableStateOf(false) }
    LaunchedEffect(snack) {
        if (!refileAwaitingLeave) return@LaunchedEffect
        if (snack != null) {
            refileSnackSeen = true
        } else if (refileSnackSeen) {
            refileAwaitingLeave = false
            refileSnackSeen = false
            onBack()
        }
    }
    val currentHeadline = (state as? DocumentUiState.Loaded)?.document?.headlineFor(noteRef)
    // Reload whenever the screen comes back to the foreground (e.g. returning
    // from the editor) so saved edits show immediately. The ON_RESUME right
    // after this screen enters composition is skipped: the LaunchedEffect
    // below already loads on entry, so that first resume would just be a
    // redundant second load (and a second Loaded emission) of the same file.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var seenFirstResume by remember(noteRef.fileName) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, noteRef.fileName) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (seenFirstResume) {
                    viewModel.load(noteRef.fileName)
                } else {
                    seenFirstResume = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Registered before the load below (SideEffect runs while composition is
    // applied, LaunchedEffect bodies after) so the very first read already
    // splices in the editor's unsaved buffer.
    androidx.compose.runtime.SideEffect {
        viewModel.setPendingEdit(pendingEdit, onPendingBufferChanged, onPendingPersisted)
    }
    // Re-keyed on pendingEdit as well: every edit the buffer picks up (in the
    // editor, or from this screen's own mutations) re-splices into the rendered
    // document.
    LaunchedEffect(noteRef.fileName, pendingEdit) { viewModel.load(noteRef.fileName) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = {
                    IconGlyph("←", onClick = onBack)
                    // Mirrors the editor's save icon: green means what's rendered
                    // here is the editor's unsaved buffer, and a tap writes it.
                    if (pendingEdit != null) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = "Unsaved changes, tap to save",
                            tint = c.green,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onSavePending)
                                .padding(10.dp),
                        )
                    }
                },
                actions = {
                    ReadEditToggle(isEditing = false, onToggle = { onEdit(null) })
                },
                subtitle = {
                    (state as? DocumentUiState.Loaded)?.document?.let { doc ->
                        if (noteRef.isIntro) {
                            // No heading segment: the intro sits above every heading.
                            ReadModeBreadcrumb(noteRef.fileName, emptyList(), onOpenBreadcrumb)
                        } else doc.headlineFor(noteRef)?.let { h ->
                            val path = remember(doc, h) {
                                val chain = mutableListOf(h)
                                var p = doc.parent(h)
                                while (p != null) {
                                    chain.add(0, p)
                                    p = doc.parent(p)
                                }
                                chain.toList()
                            }
                            ReadModeBreadcrumb(noteRef.fileName, path, onOpenBreadcrumb)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().testTag("read_note_screen")) {
        when (val s = state) {
            is DocumentUiState.Loading -> {}
            is DocumentUiState.Error -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.message, fontFamily = PlexSans, color = c.ink2)
            }

            is DocumentUiState.Loaded -> {
                val doc = s.document
                val headline = doc.headlineFor(noteRef)
                if (noteRef.isIntro && doc.hasIntro) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        IntroContent(
                            doc = doc,
                            listState = listState,
                            onOpenLink = onOpenLink,
                            onEdit = { onEdit(null) },
                            onOpenBlock = { line -> onOpenBlock(noteRef.fileName, line) },
                            onToggleCheckbox = { line, longPress ->
                                if (longPress) viewModel.toggleChecklistProgress(line)
                                else viewModel.toggleChecklistDone(line)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(
                            Modifier
                                .align(Alignment.BottomEnd)
                                // 16dp end = the end gutter, matching the
                                // Read/Edit toggle above it.
                                .padding(end = 16.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ScrollJumpButtons(listState = listState)
                            NewDotBadge(NewAnchors.TOPBAR_MENU) {
                                EditorMenuFab(onClick = { metadataOpen = true })
                            }
                        }
                    }
                } else if (headline == null) {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { Text("Note not found", fontFamily = PlexSans, color = c.ink2) }
                } else {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ContentFontScale(readModeFontSize) {
                        NoteContent(
                            doc = doc,
                            headline = headline,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                // Fallback: double-tap on blank space (not over any
                                // rendered text run) still switches to edit mode. Taps
                                // that land on actual text are handled per-run below
                                // (doubleTapToEdit on each OrgText/line), which wins the
                                // gesture race against SelectionContainer's own
                                // double-tap-select-word, so in practice this outer
                                // catch-all only ever fires for empty margins.
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = { onEdit(null) })
                                },
                            onOpenLink = onOpenLink,
                            fileName = noteRef.fileName,
                            onEditAt = onEdit,
                            onOpenDrawer = onOpenDrawer,
                            onOpenBlock = { line -> onOpenBlock(noteRef.fileName, line) },
                            onToggleCheckbox = { line, longPress ->
                                if (longPress) viewModel.toggleChecklistProgress(line)
                                else viewModel.toggleChecklistDone(line)
                            },
                            showPropertyDrawers = showPropertyDrawers,
                            favorites = favorites,
                            repeatAdvance = repeatAdvance,
                            onRepeatAdvanceShown = viewModel::clearRepeatAdvanced,
                        )
                        }
                        Column(
                            Modifier
                                .align(Alignment.BottomEnd)
                                // 16dp end = the end gutter, matching the
                                // Read/Edit toggle above it.
                                .padding(end = 16.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ScrollJumpButtons(listState = listState)
                            NewDotBadge(NewAnchors.TOPBAR_MENU) {
                                EditorMenuFab(onClick = { metadataOpen = true })
                            }
                        }
                    }
                }
            }
        }
        GroveUndoSnackbar(
            snack = snack,
            onUndo = {
                // Restores this exact note (same fileName/lineIndex) in place, so the
                // pending auto-leave from the move this snack belongs to must not fire.
                refileAwaitingLeave = false
                refileSnackSeen = false
                viewModel.undo()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(padding)
                .padding(bottom = 16.dp),
        )
        GroveToast(
            toast = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(padding)
                .padding(bottom = 16.dp),
        )
        }
    }

    if (metadataOpen) {
        val headline = currentHeadline
        val doc = (state as? DocumentUiState.Loaded)?.document
        if (noteRef.isIntro && doc != null && doc.hasIntro) {
            // No heading yet: every chip first inserts a blank top-level heading
            // above the content (one atomic edit), then applies its change; the
            // screen then re-opens the note at that heading. Refile is hidden —
            // there's nothing to move until the content has a heading.
            val now = { java.time.LocalDateTime.now() }
            MetadataSheet(
                headline = null,
                keywords = doc.keywords,
                allTags = allTags,
                onChangeKeyword = { kw ->
                    metadataOpen = false
                    viewModel.withIntroHeading("State → ${kw ?: "none"}") { d, h ->
                        OrgMutations.changeKeyword(d, h, kw, d.keywords, now())
                    }
                },
                onSetPriority = { p ->
                    metadataOpen = false
                    viewModel.withIntroHeading("Priority → ${p?.let { "#$it" } ?: "none"}") { d, h ->
                        OrgMutations.setPriority(d, h, p)
                    }
                },
                onSetTags = { tags ->
                    metadataOpen = false
                    viewModel.withIntroHeading("") { d, h -> OrgMutations.setTags(d, h, tags) }
                },
                onSetPlanningDates = { sched, dead, active ->
                    metadataOpen = false
                    viewModel.withIntroHeading("Planning updated") { d, h ->
                        OrgMutations.setPlanningAndActiveTimestamps(d, h, sched, dead, active)
                    }
                },
                onAddNote = { note ->
                    metadataOpen = false
                    viewModel.withIntroHeading("Note added") { d, h ->
                        OrgMutations.appendLogbookNote(
                            d, h, note.trim(),
                            OrgTimestamp(
                                now().toLocalDate(),
                                time = now().toLocalTime().withSecond(0).withNano(0),
                                active = false,
                            ),
                        )
                    }
                },
                onGenerateId = {
                    metadataOpen = false
                    viewModel.withIntroHeading("org-id generated") { d, h ->
                        OrgMutations.upsertProperty(d, h, "ID", newOrgId())
                    }
                },
                onRefile = {},
                showRefile = false,
                showFavorite = true,
                isFavorite = favorites.any { it.lineIndex == INTRO_LINE_INDEX },
                onToggleFavorite = { viewModel.toggleIntroFavorite() },
                onDismiss = { metadataOpen = false },
            )
        } else if (headline != null && doc != null) {
            MetadataSheet(
                headline = headline,
                keywords = doc.keywords,
                allTags = allTags,
                onChangeKeyword = { kw -> viewModel.setState(headline, kw) },
                onSetPriority = { p -> viewModel.setPriority(headline, p) },
                onSetTags = { tags -> viewModel.setTags(headline, tags) },
                onSetPlanningDates = { sched, dead, active -> viewModel.setPlanningDates(headline, sched, dead, active) },
                onAddNote = { note -> viewModel.addNote(headline, note) },
                onGenerateId = { viewModel.generateId(headline) },
                onRefile = { metadataOpen = false; viewModel.startRefile(headline) },
                showFavorite = true,
                isFavorite = favorites.any { it.matches(headline) },
                onToggleFavorite = { viewModel.toggleFavorite(headline) },
                onDismiss = { metadataOpen = false },
            )
        }
    }

    refileState?.let { refile ->
        val doc = (state as? DocumentUiState.Loaded)?.document
        RefileSheet(
            state = refile,
            currentFileName = noteRef.fileName,
            currentDoc = doc,
            onPickNotebook = viewModel::refilePickNotebook,
            onDrillInto = viewModel::refileDrillInto,
            onBack = viewModel::refileBack,
            onCancel = viewModel::refileCancel,
            onConfirm = { refileAwaitingLeave = true; viewModel.refileConfirm() },
            onArchive = { refileAwaitingLeave = true; viewModel.refileToArchive() },
            onPickLastUsed = { refileAwaitingLeave = true; viewModel.refileToLastUsed() },
            onSelectSearchResult = viewModel::refileSelectSearchResult,
        )
    }
}

/**
 * Path to the current heading, e.g. "shopping.org › Errands › Groceries".
 * Every segment is tappable: the file segment opens the full outline, each
 * heading segment narrows the outline to that heading's subtree. The file
 * segment is folder-compacted (see [compactFileLabel]) and the whole trail
 * scrolls horizontally on one line instead of wrapping, so a long roam path
 * never crowds the top bar.
 */
@Composable
internal fun ReadModeBreadcrumb(
    fileName: String,
    path: List<OrgHeadline>,
    onOpenBreadcrumb: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            compactFileLabel(fileName),
            fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
            maxLines = 1,
            modifier = Modifier.clickable { onOpenBreadcrumb(null) },
        )
        path.forEach { h ->
            Text(" › ", fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3, maxLines = 1)
            Text(
                h.title,
                fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                maxLines = 1,
                modifier = Modifier.clickable { onOpenBreadcrumb(h.lineIndex) },
            )
        }
    }
}

/**
 * Read view for a file's heading-less content (everything before the first `*`).
 * Renders only that content, as org body blocks — no title, no heading, no
 * subtree. Double-tap switches to the intro editor; links and checkboxes work
 * as in [NoteContent].
 */
@Composable
private fun IntroContent(
    doc: OrgDocument,
    listState: LazyListState,
    onOpenLink: (String) -> Unit,
    onEdit: () -> Unit,
    onOpenBlock: (Int) -> Unit,
    /** Toggle a checklist box: `longPress` false = tap (done), true = long-press (in-progress). */
    onToggleCheckbox: (line: Int, longPress: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var linkMenuState by remember { mutableStateOf<Pair<String, IntOffset>?>(null) }
    val openLink = onOpenLink
    val onLinkLongPress: (String, Offset, LayoutCoordinates) -> Unit = remember {
        { target, textLocalPos, textCoords ->
            boxCoords?.let {
                val boxLocalPos = it.localPositionOf(textCoords, textLocalPos)
                linkMenuState = target to IntOffset(boxLocalPos.x.toInt(), boxLocalPos.y.toInt())
            }
        }
    }
    val body = remember(doc) { doc.introBody.toList() }

    Box(Modifier.onGloballyPositioned { boxCoords = it }) {
        LazyColumn(
            state = listState,
            modifier = modifier.pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onEdit() })
            },
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 48.dp),
        ) {
            item(key = "intro-body") {
                SelectionContainer {
                    Column {
                        BodyBlocks(body, doc.introStart, onToggleCheckbox, openLink, onLinkLongPress, onEdit, onOpenBlock)
                    }
                }
            }
            item(key = "bottom-spacer") { Spacer(Modifier.height(40.dp)) }
        }

        val (target, anchorOffset) = linkMenuState ?: (null to IntOffset.Zero)
        if (target != null && anchorOffset != IntOffset.Zero) {
            Box(Modifier.offset { anchorOffset }) {
                GroveDropdownMenu(
                    expanded = true,
                    onDismissRequest = { linkMenuState = null },
                ) {
                    LinkActionMenuItems(target, onDismiss = { linkMenuState = null })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteContent(
    doc: OrgDocument,
    headline: OrgHeadline,
    fileName: String,
    onOpenLink: (String) -> Unit,
    onEditAt: (Int?) -> Unit,
    onOpenDrawer: (kind: String, ref: NoteRef) -> Unit,
    onOpenBlock: (Int) -> Unit,
    /** Toggle a checklist box: `longPress` false = tap (done), true = long-press (in-progress). */
    onToggleCheckbox: (line: Int, longPress: Boolean) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    showPropertyDrawers: Boolean = true,
    favorites: List<FavoriteNote> = emptyList(),
    /** Pending "a repeater just advanced" event; see [DocumentViewModel.repeatAdvanced]. */
    repeatAdvance: RepeatAdvance? = null,
    /** [repeatAdvance] has been flashed; the arg is its id. */
    onRepeatAdvanceShown: (Long) -> Unit = {},
) {
    val c = MaterialTheme.grove
    // Marking a recurring task done keeps its keyword and only moves the date,
    // so flash just the SCHEDULED/DEADLINE chip(s) that moved, for the user to
    // notice. Same flash as the timestamp picker's preview.
    val scheduledFlash = rememberTapFlash("scheduledAdvanceFlash")
    val deadlineFlash = rememberTapFlash("deadlineAdvanceFlash")
    LaunchedEffect(repeatAdvance) {
        val advance = repeatAdvance ?: return@LaunchedEffect
        if (advance.lineIndex == headline.lineIndex) {
            if (advance.planning.scheduled) scheduledFlash.trigger()
            if (advance.planning.deadline) deadlineFlash.trigger()
        }
        onRepeatAdvanceShown(advance.id)
    }
    // Per-section collapse state, reset when the viewed note changes; all
    // sections start collapsed (design/Grove.dc.html lines 499-552).
    val collapsibleExpanded = remember(fileName, headline.lineIndex) { mutableStateMapOf<String, Boolean>() }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var linkMenuState by remember { mutableStateOf<Pair<String, IntOffset>?>(null) }
    val openLink = onOpenLink
    val onLinkLongPress: (String, Offset, LayoutCoordinates) -> Unit = remember {
        { target, textLocalPos, textCoords ->
            // Convert text-local position to outer-Box-local position
            boxCoords?.let {
                val boxLocalPos = it.localPositionOf(textCoords, textLocalPos)
                linkMenuState = target to IntOffset(boxLocalPos.x.toInt(), boxLocalPos.y.toInt())
            }
        }
    }

    // O(document) traversals, computed once per document instead of per
    // recomposition. Body lines are resolved per-row inside the LazyColumn
    // items below, so only on-screen headings pay that cost.
    //
    // This heading's OWN tags, not doc.inheritedTags(headline): every other
    // field in this same header row (keyword, priority, title) is this
    // heading's own, and the metadata sheet opened from this row edits (and
    // shows) headline.tags only. Rendering inherited tags here made a
    // successful, correctly-persisted tap-to-remove look like a no-op
    // whenever the removed tag was also present via an ancestor heading or
    // #+FILETAGS: — the sheet's own row lost the chip, but this header kept
    // showing it via inheritance.
    val tags = remember(doc, headline) { headline.tags }
    // Minus the leading bare active-timestamp line: it renders as a chip below the
    // title instead (headline.dedicatedActiveTimestamps).
    val ownBody = remember(doc, headline) { doc.bodyWithoutDedicatedTimestamp(headline) }
    val ownBodyStart = remember(doc, headline) { doc.bodyStartWithoutDedicatedTimestamp(headline) }
    val subtree = remember(doc, headline) { doc.subtree(headline) }
    // O(1) favorite lookup per row instead of an O(favorites) `.any` scan per row per
    // recomposition; split by whether the favorite matches on customId or raw lineIndex
    // (see FavoriteNote.matches).
    val favoriteCustomIds = remember(favorites) { favorites.mapNotNull { it.customId }.toSet() }
    val favoriteLineIndices = remember(favorites) {
        favorites.filter { it.customId == null }.map { it.lineIndex }.toSet()
    }
    fun isFavorite(h: OrgHeadline): Boolean {
        return favoriteCustomIds.containsIdOf(h) || h.lineIndex in favoriteLineIndices
    }

    // A heading whose subtree is huge or very deep (e.g. a 2000-heading "note")
    // used to render every descendant heading + body eagerly in one scrolling
    // Column, which janked the open, the scroll and the back navigation. Such
    // notes now open with their inner headings folded, so only the note body +
    // a one-level section list mount; small notes still open fully expanded.
    // doc/subtree are already loaded by the time this composable is reached, so
    // the default collapse set is seeded directly in the initializer — the
    // first frame renders already collapsed instead of a fully expanded frame
    // that folds one frame later. The rememberSaveable slot still protects any
    // later user expand/collapse across recomposition.
    var collapsed by rememberSaveable(fileName, headline.lineIndex, stateSaver = IntSetSaver) {
        mutableStateOf(defaultReadCollapse(doc, subtree))
    }
    val visibleRows = remember(subtree, collapsed) { visibleReadRows(subtree, collapsed) }

    Box(
        Modifier.onGloballyPositioned { boxCoords = it }
    ) {
        // One SelectionContainer per section (the note's own body, then each
        // visible heading). Cross-section selection was given up so the subtree
        // could stop being one eager Column and virtualize: a drag-select now
        // stays within a section.
        LazyColumn(
            state = listState,
            modifier = modifier.testTag("read_note_scroll"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 48.dp),
        ) {
            item(key = "own") {
                SelectionContainer {
                    Column {
                        // Tag chips
                        if (tags.isNotEmpty()) {
                            Row {
                                tags.forEach { tag ->
                                    Pill(tag, fg = c.accent, bg = c.accentSoft, outline = true)
                                    Spacer(Modifier.width(7.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // Title
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            headline.keyword?.let { kw ->
                                val (fg, bg) = if (doc.keywords.isDone(kw)) c.green to c.greenSoft
                                else c.amber to c.amberSoft
                                Pill(kw, fg = fg, bg = bg)
                                Spacer(Modifier.width(8.dp))
                            }
                            headline.priority?.let { p ->
                                Text(
                                    "[#$p]",
                                    fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp, color = c.priorityColor(p),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            OrgText(
                                headline.title, onOpenLink = openLink, onLinkLongPress = onLinkLongPress,
                                onDoubleTapAt = { onEditAt(null) },
                                style = TextStyle(
                                    fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold,
                                    fontSize = 25.sp, color = c.ink, lineHeight = 1.3.em,
                                ),
                                modifier = Modifier.weight(1f).testTag("read_note_title"),
                            )
                            if (remember(headline, favoriteCustomIds, favoriteLineIndices) { isFavorite(headline) }) {
                                Spacer(Modifier.width(8.dp))
                                FavoriteStar(modifier = Modifier.padding(top = 6.dp), size = 24.dp)
                            }
                        }

                        // Planning line (SCHEDULED/DEADLINE/CLOSED) is immediately after the
                        // heading in the raw org file, before any drawers, so it
                        // renders first here too, matching edit mode's line order.
                        // Both chips share a row and wrap to a second line only if
                        // they don't fit side by side.
                        if (headline.planning.scheduled != null || headline.planning.deadline != null ||
                            headline.planning.closed != null || headline.dedicatedActiveTimestamps.isNotEmpty()
                        ) {
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                headline.planning.scheduled?.let {
                                    PlanningChip(
                                        remember(it) { it.formatHuman() },
                                        icon = Icons.Outlined.CalendarMonth,
                                        fg = c.blue,
                                        bg = c.blueSoft,
                                        flash = scheduledFlash.color,
                                    )
                                }
                                headline.planning.deadline?.let {
                                    PlanningChip(
                                        remember(it) { it.formatHuman() },
                                        icon = Icons.Filled.Flag,
                                        fg = c.red,
                                        bg = c.redSoft,
                                        flash = deadlineFlash.color,
                                    )
                                }
                                // Bare active timestamps (events): a filled dot in the
                                // violet event colour. Only the dedicated line after the
                                // heading — inline `<…>` stamps stay in the body text.
                                headline.dedicatedActiveTimestamps.forEach {
                                    PlanningChip(
                                        remember(it) { it.formatHuman() },
                                        icon = Icons.Filled.Circle,
                                        fg = c.violet,
                                        bg = c.violetSoft,
                                        iconSize = 8.dp,
                                    )
                                }
                                // CLOSED: muted, since it's history rather than an upcoming date.
                                headline.planning.closed?.let {
                                    PlanningChip(remember(it) { it.formatHuman() }, icon = Icons.Filled.Check, fg = c.ink3, bg = c.surface2)
                                }
                            }
                        }

                        // Note's own :PROPERTIES: and :LOGBOOK: drawers (CREATED lives
                        // in :PROPERTIES:, shown there only, not as a separate line).
                        if (showPropertyDrawers && (headline.properties.isNotEmpty() || headline.logbook.isNotEmpty())) {
                            Spacer(Modifier.height(10.dp))
                            val ownRef = NoteRef(fileName, headline.lineIndex, headline.customId)
                            if (headline.properties.isNotEmpty()) {
                                CollapsibleKvSection(
                                    label = ":PROPERTIES:",
                                    entries = headline.properties.map { (k, v) -> ":$k:" to v },
                                    expanded = collapsibleExpanded["own"] == true,
                                    onToggle = {
                                        collapsibleExpanded["own"] = collapsibleExpanded["own"] != true
                                    },
                                    onDoubleTap = { onOpenDrawer("headingProps", ownRef) },
                                )
                            }
                            if (headline.logbook.isNotEmpty()) {
                                if (headline.properties.isNotEmpty()) Spacer(Modifier.height(6.dp))
                                CollapsibleLogSection(
                                    label = ":LOGBOOK:",
                                    lines = headline.logbook,
                                    expanded = collapsibleExpanded["own-logbook"] == true,
                                    onToggle = {
                                        collapsibleExpanded["own-logbook"] = collapsibleExpanded["own-logbook"] != true
                                    },
                                    onDoubleTap = { onOpenDrawer("headingLog", ownRef) },
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        Spacer(Modifier.height(16.dp))

                        // Own body
                        BodyBlocks(ownBody, ownBodyStart, onToggleCheckbox, openLink, onLinkLongPress, { onEditAt(null) }, onOpenBlock)
                    }
                }
            }

            // Subtree, one item per visible heading, sized by relative depth.
            // Headings nested under a folded ancestor aren't in `visibleRows`
            // at all, so they never compose.
            items(visibleRows, key = { it.lineIndex }) { child ->
                val childCollapsed = child.lineIndex in collapsed
                ReadHeadingRow(
                    doc = doc,
                    child = child,
                    fileName = fileName,
                    relLevel = (child.level - headline.level).coerceAtLeast(1),
                    isCollapsed = childCollapsed,
                    isFavorite = remember(child, favoriteCustomIds, favoriteLineIndices) { isFavorite(child) },
                    onToggleFold = {
                        collapsed = if (childCollapsed) collapsed - child.lineIndex
                        else collapsed + child.lineIndex
                    },
                    collapsibleExpanded = collapsibleExpanded,
                    showPropertyDrawers = showPropertyDrawers,
                    onOpenDrawer = onOpenDrawer,
                    onEditAt = onEditAt,
                    onToggleCheckbox = onToggleCheckbox,
                    onOpenLink = openLink,
                    onLinkLongPress = onLinkLongPress,
                    onOpenBlock = onOpenBlock,
                )
            }

            item(key = "bottom-spacer") { Spacer(Modifier.height(40.dp)) }
        }

        // Zero-size anchor Box at the press location; DropdownMenu anchors to it
        val (target, anchorOffset) = linkMenuState ?: (null to IntOffset.Zero)
        if (target != null && anchorOffset != IntOffset.Zero) {
            Box(
                Modifier.offset { anchorOffset }
            ) {
                GroveDropdownMenu(
                    expanded = true,
                    onDismissRequest = { linkMenuState = null },
                ) {
                    LinkActionMenuItems(target, onDismiss = { linkMenuState = null })
                }
            }
        }
    }
}

/**
 * One heading inside a Read-mode scroll: disclosure caret (foldable headings only),
 * keyword pill, priority, title sized by [relLevel], "… N" child count when folded,
 * ★, its own drawers, then its body (unmounted while folded). Shared by note Read
 * mode ([NoteContent], where [relLevel] is the depth below the enclosing note) and
 * the whole-file view (`ReadFileScreen`, which passes the heading's absolute level).
 * Each row is its own [SelectionContainer], so a drag-select stays within a heading.
 */
@Composable
internal fun ReadHeadingRow(
    doc: OrgDocument,
    child: OrgHeadline,
    fileName: String,
    relLevel: Int,
    isCollapsed: Boolean,
    isFavorite: Boolean,
    onToggleFold: () -> Unit,
    /** Expanded state of this row's `:PROPERTIES:` / `:LOGBOOK:` sections, keyed per heading. */
    collapsibleExpanded: SnapshotStateMap<String, Boolean>,
    showPropertyDrawers: Boolean,
    onOpenDrawer: (kind: String, ref: NoteRef) -> Unit,
    onEditAt: (Int?) -> Unit,
    onToggleCheckbox: (line: Int, longPress: Boolean) -> Unit,
    onOpenLink: (String) -> Unit,
    onLinkLongPress: (String, Offset, LayoutCoordinates) -> Unit,
    onOpenBlock: (Int) -> Unit,
) {
    val c = MaterialTheme.grove
    val body = remember(doc, child) { doc.bodyOf(child) }
    val foldable = remember(doc, child) { doc.hasDescendants(child) }
    Column {
        Spacer(Modifier.height(20.dp))
        SelectionContainer {
            Column {
                // Top-aligned so the keyword pill stays on the first line
                // when the title wraps.
                Row(verticalAlignment = Alignment.Top) {
                    // Disclosure control: a tap folds/unfolds this
                    // heading's subtree. Drawn like the outline's caret.
                    // Only foldable headings reserve the space, so leaf
                    // headings stay flush with the body like before.
                    if (foldable) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onToggleFold),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(9.dp)) {
                                val path = if (isCollapsed) {
                                    Path().apply {
                                        moveTo(0f, 0f); lineTo(0f, size.height)
                                        lineTo(size.width, size.height / 2f); close()
                                    }
                                } else {
                                    Path().apply {
                                        moveTo(0f, 0f); lineTo(size.width, 0f)
                                        lineTo(size.width / 2f, size.height); close()
                                    }
                                }
                                drawPath(path, color = c.ink3)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    child.keyword?.let { kw ->
                        val (fg, bg) = if (doc.keywords.isDone(kw)) c.green to c.greenSoft
                        else c.amber to c.amberSoft
                        Pill(kw, fg = fg, bg = bg)
                        Spacer(Modifier.width(8.dp))
                    }
                    child.priority?.let { p ->
                        Text(
                            "[#$p]",
                            fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                            fontSize = 12.sp, color = c.priorityColor(p),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    OrgText(
                        child.title, onOpenLink = onOpenLink, onLinkLongPress = onLinkLongPress,
                        onDoubleTapAt = { onEditAt(child.lineIndex) },
                        style = TextStyle(
                            fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold,
                            fontSize = when (relLevel) {
                                1 -> 19.sp
                                2 -> 17.sp
                                else -> 16.sp
                            },
                            color = c.ink,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (isCollapsed && foldable) {
                        Spacer(Modifier.width(6.dp))
                        val directChildCount = remember(doc, child) { doc.directChildren(child).size }
                        Text(
                            "… $directChildCount",
                            fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (isFavorite) {
                        Spacer(Modifier.width(8.dp))
                        FavoriteStar(modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (showPropertyDrawers && (child.properties.isNotEmpty() || child.logbook.isNotEmpty())) {
                    Spacer(Modifier.height(10.dp))
                    val childRef = NoteRef(fileName, child.lineIndex, child.customId)
                    if (child.properties.isNotEmpty()) {
                        CollapsibleKvSection(
                            label = ":PROPERTIES:",
                            entries = child.properties.map { (k, v) -> ":$k:" to v },
                            expanded = collapsibleExpanded["child:${child.lineIndex}"] == true,
                            onToggle = {
                                val key = "child:${child.lineIndex}"
                                collapsibleExpanded[key] = collapsibleExpanded[key] != true
                            },
                            onDoubleTap = { onOpenDrawer("headingProps", childRef) },
                        )
                    }
                    if (child.logbook.isNotEmpty()) {
                        if (child.properties.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        CollapsibleLogSection(
                            label = ":LOGBOOK:",
                            lines = child.logbook,
                            expanded = collapsibleExpanded["child-logbook:${child.lineIndex}"] == true,
                            onToggle = {
                                val key = "child-logbook:${child.lineIndex}"
                                collapsibleExpanded[key] = collapsibleExpanded[key] != true
                            },
                            onDoubleTap = { onOpenDrawer("headingLog", childRef) },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                // A folded heading shows only its title + "… N"; its
                // body and descendants stay unmounted.
                if (!isCollapsed) {
                    BodyBlocks(body, child.bodyStart, onToggleCheckbox, onOpenLink, onLinkLongPress, { onEditAt(child.lineIndex) }, onOpenBlock)
                }
            }
        }
    }
}

/**
 * A note whose subtree has more than this many headings opens with its inner
 * headings folded, so only the note body + a one-level section list mount.
 * Below it, the read view opens fully expanded (no behavior change).
 */
internal const val LARGE_SUBTREE_THRESHOLD = 60

/**
 * Line indices to fold when the note first opens: for a large subtree, every
 * descendant heading that itself has descendants, so the reader lands on the
 * note body + a one-level section list. Empty for a small subtree (opens fully
 * expanded, unchanged behavior).
 */
internal fun defaultReadCollapse(doc: OrgDocument, subtree: List<OrgHeadline>): Set<Int> =
    if (subtree.size > LARGE_SUBTREE_THRESHOLD) {
        subtree.asSequence().filter { doc.hasDescendants(it) }.map { it.lineIndex }.toSet()
    } else {
        emptySet()
    }

/**
 * The subset of [subtree] to actually render: every heading except those nested
 * under a folded ancestor. Same document-order walk as the outline's
 * `visibleHeadlines`.
 */
internal fun visibleReadRows(subtree: List<OrgHeadline>, collapsed: Set<Int>): List<OrgHeadline> {
    val result = ArrayList<OrgHeadline>(subtree.size)
    var hideDeeperThan: Int? = null
    for (h in subtree) {
        val hide = hideDeeperThan
        if (hide != null) {
            if (h.level > hide) continue
            hideDeeperThan = null
        }
        result.add(h)
        if (h.lineIndex in collapsed) hideDeeperThan = h.level
    }
    return result
}

/** Text that renders org inline markup and hands link taps/long-presses to [onOpenLink]/[onLinkLongPress]. */
@Composable
internal fun OrgText(
    text: String,
    onOpenLink: (String) -> Unit,
    onLinkLongPress: (String, Offset, LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /** Double-tap anywhere in this run switches to edit mode. Word-selection
     * via long-press, and link taps, are unaffected (see [doubleTapToEdit]). */
    onDoubleTapAt: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    val annotated = remember(text, c, onOpenLink) { annotateOrgInline(text, c, onOpenLink) }
    val links = remember(text) { orgInlineLinks(text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Wrapper to pass text's coordinates to the long-press handler
    val wrappedOnLongPress: (String, Offset) -> Unit = { target, textLocalPos ->
        textCoords?.let { onLinkLongPress(target, textLocalPos, it) }
    }

    Text(
        annotated,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it },
        modifier = modifier
            // Only the long-press menu reads textCoords, and only when there's a link to act
            // on; skip the per-run positioned callback otherwise (it fires every visible run
            // every LazyColumn scroll frame).
            .then(if (links.isNotEmpty()) Modifier.onGloballyPositioned { textCoords = it } else Modifier)
            .doubleTapToEdit(
                layoutResult = { layout },
                links = links,
                enabled = onDoubleTapAt != null,
                onDoubleTap = { onDoubleTapAt?.invoke() },
            )
            .linkPressHandler(
                links = links,
                layoutResult = { layout },
                onTap = onOpenLink,
                onLongPress = wrappedOnLongPress,
            ),
    )
}

/** Copy link / Share link: the actions offered when long-pressing a link in read mode. */
@Composable
internal fun LinkActionMenuItems(target: String, onDismiss: () -> Unit) {
    val c = MaterialTheme.grove
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    DropdownMenuItem(
        text = { Text("Copy link", fontFamily = PlexSans, fontSize = 14.sp, color = c.ink) },
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("link", target))) }
            onDismiss()
        },
    )
    DropdownMenuItem(
        text = { Text("Share link", fontFamily = PlexSans, fontSize = 14.sp, color = c.ink) },
        onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, target)
            }
            context.startActivity(Intent.createChooser(intent, null))
            onDismiss()
        },
    )
}

/**
 * A planning date shown as a soft-tinted chip (SCHEDULED blue calendar,
 * DEADLINE red flag, CLOSED muted ink3 check). [text] is the human form (`OrgTimestamp.formatHuman`),
 * not the raw org stamp: Read mode is prose, Edit mode is where the literal
 * `<2026-07-30 Thu>` belongs. [flash] is painted over [bg] (transparent at
 * rest; see [rememberTapFlash]) to point out a date that just moved.
 */
@Composable
private fun PlanningChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fg: Color,
    bg: Color,
    iconSize: Dp = 13.dp,
    flash: Color = Color.Transparent,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .background(flash)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
        Text(text, fontFamily = PlexMono, fontSize = 12.5.sp, color = fg)
    }
}

/**
 * Per-item ordinal (1-based) for a [OrgBlock.ListBlock]'s ordered items, so a
 * numbered sub-list nested under a bullet restarts at 1 instead of counting
 * through the whole flat [items] list. Counting resets whenever a shallower
 * item is seen (closing the sub-list) or a bullet appears at the same indent
 * (breaking the numbered run); unordered items get 0 (unused by the caller).
 */
private fun orderedListOrdinals(items: List<OrgBlock.ListItem>): IntArray {
    val ordinals = IntArray(items.size)
    val counts = mutableMapOf<Int, Int>()
    for ((idx, item) in items.withIndex()) {
        counts.keys.filter { it > item.indent }.forEach { counts.remove(it) }
        if (item.ordered) {
            val next = (counts[item.indent] ?: 0) + 1
            counts[item.indent] = next
            ordinals[idx] = next
        } else {
            counts.remove(item.indent)
        }
    }
    return ordinals
}

@Composable
internal fun BodyBlocks(
    bodyLines: List<String>,
    /** Absolute doc line of `bodyLines[0]`, to resolve a [OrgBlock.ListItem]'s body-relative `line`. */
    lineOffset: Int,
    /** Toggle a checklist box: `longPress` false = tap (done), true = long-press (in-progress). */
    onToggleCheckbox: (line: Int, longPress: Boolean) -> Unit,
    openTarget: (String) -> Unit,
    onLinkLongPress: (String, Offset, LayoutCoordinates) -> Unit,
    onEditAt: () -> Unit,
    /** Double-tap on a `#+BEGIN_x` block body; arg is the absolute doc line of its first line. */
    onOpenBlock: (Int) -> Unit = {},
) {
    val c = MaterialTheme.grove
    val haptics = LocalHapticFeedback.current
    // Hoisted so paragraph/list-item text doesn't allocate a fresh TextStyle per row per
    // recomposition.
    val paragraphStyle = remember(c) { TextStyle(fontFamily = PlexSerif, fontSize = 16.sp, lineHeight = 1.65.em, color = c.ink) }
    val listItemStyleActive = remember(c) {
        TextStyle(
            fontFamily = PlexSerif, fontSize = 16.sp,
            lineHeight = 1.55.em, color = c.ink,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }
    val listItemStyleDone = remember(c) {
        TextStyle(
            fontFamily = PlexSerif, fontSize = 16.sp,
            lineHeight = 1.55.em, color = c.ink3,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }
    val blocks = remember(bodyLines) { BlockParser.parse(bodyLines) }
    // Per-block expand state, keyed by the block's body-relative start line.
    // `#+BEGIN` blocks open expanded (unlike the collapsed-by-default metadata
    // drawers): their content is note content. A standalone keyword run is
    // metadata, so it opens collapsed.
    val blockExpanded = remember(bodyLines) { mutableStateMapOf<Int, Boolean>() }

    blocks.forEach { block ->
        when (block) {
            is OrgBlock.Paragraph -> {
                // Each source line in the paragraph keeps its own visual line
                // (join with "\n", not " "): a single newline in edit mode is a
                // line break in read mode too. A blank line already separates
                // paragraphs upstream in BlockParser.
                OrgText(
                    block.lines.joinToString("\n") { it.trim() },
                    onOpenLink = openTarget, onLinkLongPress = onLinkLongPress,
                    onDoubleTapAt = onEditAt,
                    style = paragraphStyle,
                )
                Spacer(Modifier.height(12.dp))
            }

            is OrgBlock.ListBlock -> {
                val ordinals = remember(block) { orderedListOrdinals(block.items) }
                Column(Modifier.padding(start = 8.dp)) {
                    block.items.forEachIndexed { i, item ->
                        val done = item.checkbox == 'X' || item.checkbox == 'x'
                        Row(
                            Modifier.padding(vertical = 2.dp).padding(start = (item.indent * 20).dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Sized to the first line's box (16sp * 1.55 line-height
                            // below = 24.8sp ≈ 25dp) so the glyph centers against just
                            // that line, not the full (possibly multi-line) height of
                            // the item text. Requires the text's LineHeightStyle to be
                            // Center + Trim.None (set below) so the first line really is
                            // 24.8sp tall with the glyph centered in it — otherwise the
                            // default trim collapses the first line and the marker sits
                            // low. Bullets/checkboxes are drawn on a Canvas rather than
                            // as Unicode glyphs: PlexSerif has no •/☐/☑/◧ glyphs, so
                            // those fall back to a different font whose vertical metrics
                            // don't match PlexSerif's, throwing off the centering above.
                            Box(
                                Modifier.width(20.dp).height(25.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val markColor = if (done) c.ink3 else c.ink2
                                when {
                                    item.checkbox != null -> {
                                        Canvas(
                                            Modifier
                                                .size(18.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                // Tap toggles done; long-press toggles in-progress.
                                                // Own haptic on long-press (hence hapticFeedbackEnabled
                                                // = false), so the buzz lands whatever combinedClickable's
                                                // default does in a future version.
                                                .combinedClickable(
                                                    hapticFeedbackEnabled = false,
                                                    onClick = { onToggleCheckbox(lineOffset + item.line, false) },
                                                    onLongClick = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onToggleCheckbox(lineOffset + item.line, true)
                                                    },
                                                )
                                                .padding(1.dp),
                                        ) {
                                            val stroke = 1.5.dp.toPx()
                                            val corner = CornerRadius(3.dp.toPx())
                                            when (item.checkbox) {
                                                'X', 'x' -> {
                                                    drawRoundRect(color = markColor, cornerRadius = corner)
                                                    val check = Path().apply {
                                                        moveTo(size.width * 0.22f, size.height * 0.52f)
                                                        lineTo(size.width * 0.42f, size.height * 0.72f)
                                                        lineTo(size.width * 0.78f, size.height * 0.28f)
                                                    }
                                                    drawPath(
                                                        check,
                                                        color = c.bg,
                                                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                                    )
                                                }
                                                '-' -> {
                                                    drawRoundRect(color = markColor, cornerRadius = corner, style = Stroke(width = stroke))
                                                    drawLine(
                                                        color = markColor,
                                                        start = Offset(size.width * 0.25f, size.height / 2f),
                                                        end = Offset(size.width * 0.75f, size.height / 2f),
                                                        strokeWidth = stroke,
                                                        cap = StrokeCap.Round,
                                                    )
                                                }
                                                else -> {
                                                    drawRoundRect(color = markColor, cornerRadius = corner, style = Stroke(width = stroke))
                                                }
                                            }
                                        }
                                    }
                                    item.ordered -> {
                                        Text("${ordinals[i]}.", fontFamily = PlexSerif, fontSize = 16.sp, color = markColor)
                                    }
                                    else -> {
                                        Canvas(Modifier.size(6.dp)) { drawCircle(color = markColor) }
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            OrgText(
                                item.text,
                                onOpenLink = openTarget, onLinkLongPress = onLinkLongPress,
                                onDoubleTapAt = onEditAt,
                                style = if (done) listItemStyleDone else listItemStyleActive,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            is OrgBlock.Block -> {
                // `#+BEGIN` blocks open expanded (their content is note content);
                // a standalone keyword run opens collapsed, like a metadata drawer.
                val expanded = blockExpanded[block.startLine] ?: !block.keywordRun
                CollapsibleBlockSection(
                    label = block.kind,
                    trailingLabel = block.language,
                    affiliated = block.affiliated,
                    contentLines = block.contentLines,
                    expanded = expanded,
                    onToggle = { blockExpanded[block.startLine] = !expanded },
                    onDoubleTap = { onOpenBlock(lineOffset + block.startLine) },
                )
                Spacer(Modifier.height(12.dp))
            }

            is OrgBlock.Table -> {
                OrgTableView(block.lines, onDoubleTap = onEditAt)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
