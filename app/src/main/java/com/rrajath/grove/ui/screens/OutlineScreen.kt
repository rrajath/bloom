package com.rrajath.grove.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.R
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.settings.OutlineToggle
import com.rrajath.grove.ui.components.BrandMarkGlyph
import com.rrajath.grove.ui.components.CollapsibleKvSection
import com.rrajath.grove.ui.components.FavoriteStar
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.GroveToast
import com.rrajath.grove.ui.components.GroveUndoSnackbar
import com.rrajath.grove.ui.components.LinkedReferencesBar
import com.rrajath.grove.ui.components.LinkedReferencesSheet
import com.rrajath.grove.ui.components.PlanningDatesScreen
import com.rrajath.grove.ui.components.ScrollJumpButtons
import com.rrajath.grove.ui.components.StatePickerSheet
import com.rrajath.grove.ui.components.SwipeAction
import com.rrajath.grove.ui.components.SwipeRevealRow
import com.rrajath.grove.ui.components.annotateOrgInline
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.data.containsIdOf
import com.rrajath.grove.data.matches
import com.rrajath.grove.ui.components.favoriteIcon
import com.rrajath.grove.ui.components.favoriteIconFilled
import com.rrajath.grove.ui.components.searchIcon
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.newbadge.NewDotBadge
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.priorityColor
import com.rrajath.grove.ui.theme.starColor
import com.rrajath.grove.ui.util.IntSetSaver
import com.rrajath.grove.ui.util.pluralCount
import com.rrajath.grove.ui.editor.MetadataSheet
import com.rrajath.grove.ui.vault.BulkPlanningEdit
import com.rrajath.grove.ui.vault.DocumentUiState
import com.rrajath.grove.ui.vault.DocumentViewModel
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.WHOLE_FILE_LINE_LIMIT
import com.rrajath.grove.ui.vault.bulkMetadataSummary
import com.rrajath.grove.ui.vault.breadcrumbFileLabel
import com.rrajath.grove.ui.vault.fitsWholeFileView
import com.rrajath.grove.ui.vault.headlineAtLine

data class OutlineDisplayFlags(
    val tags: Boolean = true,
    val timestamps: Boolean = true,
    val keywords: Boolean = true,
)

/** Outline view per design spec §4: collapsible heading tree with node ops. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(
    notebookId: String,
    onBack: () -> Unit,
    onOpenNote: (NoteRef) -> Unit,
    onCreateNote: (NoteRef) -> Unit,
    /**
     * Line index of a heading to narrow the outline to (org-narrow-to-subtree):
     * only that heading and its descendants are shown, until [onWiden].
     */
    narrowLineIndex: Int? = null,
    /** Line index of a heading to unfold to, scroll into view and briefly
     *  highlight once the document loads (Search's "show in notebook"). */
    revealLineIndex: Int? = null,
    /** Return to the full, unnarrowed outline. */
    onWiden: () -> Unit = {},
    /** Top-bar ⌕: opens Search with the notebook filter already pinned to this file. */
    onSearchInNotebook: () -> Unit = {},
    /** Adds a favorite; [customId] is resolved (via [DocumentViewModel.ensureStableId]) before this is called. */
    onFavorite: (fileName: String, lineIndex: Int, title: String, customId: String?) -> Unit = { _, _, _, _ -> },
    onUnfavorite: (fileName: String, lineIndex: Int, customId: String?) -> Unit = { _, _, _ -> },
    /** Favorited headlines in this notebook, matched per-row by customId, marked with a ★. */
    favorites: List<FavoriteNote> = emptyList(),
    displayFlags: OutlineDisplayFlags = OutlineDisplayFlags(),
    onToggleDisplay: (OutlineToggle, Boolean) -> Unit = { _, _ -> },
    /** Settings toggle: show a collapsible section for file-level `#+` keywords, pinned at the top. */
    showPreface: Boolean = true,
    /**
     * Settings toggle "Show property drawers" (the same flag that gates heading
     * `:PROPERTIES:` drawers in Read mode). When on, a leading file-level
     * `:PROPERTIES:` drawer is shown as a collapsed key/value section above the
     * PREFACE box.
     */
    showPropertyDrawers: Boolean = true,
    /**
     * Settings § Roam Features (experimental): show the Backlinks bar
     * (backlinks). Only ever renders for a roam file (one with a file-level
     * `:ID:`) -- this toggle alone doesn't force it on for a non-roam file.
     */
    showBacklinks: Boolean = false,
    /** Double-tapping the PREFACE section: opens an editor scoped to just the `#+KEY:`
     *  (everything before the first heading), mirroring double-tap-to-edit elsewhere. */
    onOpenPreface: (fileName: String) -> Unit = {},
    /** Double-tapping the file-level `:PROPERTIES:` section: opens an editor scoped to just
     *  that drawer. */
    onOpenFileProperties: (fileName: String) -> Unit = {},
    /** Overflow menu's "View file": open the whole file as one note (`Routes.FILE`). */
    onViewFile: (fileName: String) -> Unit = {},
    /**
     * Largest file, in lines, that "View file" and the auto-open below will open
     * whole; above it the menu item is disabled with a hint to drill into a heading.
     */
    wholeFileLineLimit: Int = WHOLE_FILE_LINE_LIMIT,
    /**
     * Settings § Roam Features "Open roam files directly in read mode", already
     * combined with the route's `auto` flag by the caller: when the loaded file is
     * an org-roam file (has a file-level `:ID:`, i.e. `fileId != null`) and fits
     * under [wholeFileLineLimit], this outline never renders and
     * [onAutoOpenWholeFile] fires once instead. Off while narrowed.
     */
    autoOpenWholeFile: Boolean = false,
    onAutoOpenWholeFile: (fileName: String) -> Unit = {},
    viewModel: DocumentViewModel = viewModel(factory = DocumentViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedLines by viewModel.selectedLines.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val snack by viewModel.snack.collectAsStateWithLifecycle()
    val refileState by viewModel.refile.collectAsStateWithLifecycle()
    LaunchedEffect(notebookId) { viewModel.load(notebookId) }

    val loadedDoc = (state as? DocumentUiState.Loaded)?.document

    // Roam auto-open: an org-roam file (one with a file-level :ID:, i.e.
    // loadedDoc.fileId != null) under the line limit skips this outline and
    // lands on the whole-file view instead. Nothing renders here while that is
    // still undecided (loading) or decided (redirecting), so the outline never
    // flashes; a non-roam or over-limit file falls through and renders as usual
    // once loaded.
    val autoOpenPending = autoOpenWholeFile && narrowLineIndex == null
    val redirectToWholeFile = autoOpenPending && loadedDoc != null &&
        loadedDoc.fileId != null &&
        fitsWholeFileView(loadedDoc.lines.size, wholeFileLineLimit)
    if (redirectToWholeFile || (autoOpenPending && state is DocumentUiState.Loading)) {
        if (redirectToWholeFile) {
            // Once per back-stack entry: the host replaces this entry with the file
            // view, so a later emission of the same document must not fire again.
            var fired by rememberSaveable(notebookId) { mutableStateOf(false) }
            LaunchedEffect(notebookId) {
                if (!fired) {
                    fired = true
                    onAutoOpenWholeFile(notebookId)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(c.bg))
        return
    }

    // Whole-file backlinks, independent of any narrow -- narrowing to a
    // subtree doesn't change which file's backlinks/mentions are being shown.
    val linkedReferences by viewModel.linkedReferences.collectAsStateWithLifecycle()
    var linkedRefsOpen by remember { mutableStateOf(false) }
    // Keyed on (fileId, title), not the document: edits re-parse the file but can't
    // change what links here. No file :ID: means no bar, so no scan.
    val fileTitle = loadedDoc?.let { doc ->
        doc.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }
            ?.second ?: notebookId.removeSuffix(".org")
    }
    LaunchedEffect(notebookId, loadedDoc?.fileId, fileTitle) {
        val title = fileTitle ?: return@LaunchedEffect
        val fileId = loadedDoc?.fileId
        if (fileId != null) {
            viewModel.loadLinkedReferences(notebookId, INTRO_LINE_INDEX, fileId, title)
        } else {
            viewModel.clearLinkedReferences()
        }
    }
    // Resolved once here (not separately in the top bar and the body) so both
    // stay in sync. Falls back to the full outline if the target heading no
    // longer exists (e.g. it was deleted since the breadcrumb was shown).
    val narrowTarget = remember(loadedDoc, narrowLineIndex) {
        narrowLineIndex?.let { idx -> loadedDoc?.headlineAtLine(idx) }
    }
    val scopedHeadlines = remember(loadedDoc, narrowTarget) {
        loadedDoc?.let { doc ->
            if (narrowTarget != null) listOf(narrowTarget) + doc.subtree(narrowTarget) else doc.headlines
        } ?: emptyList()
    }

    // Collapsed line-indices and scroll survive navigating into a note and back
    // (rememberSaveable persists across the destination leaving composition).
    // Keyed also on whether the doc has loaded yet: the slot the initializer
    // below fills in is the one live the *first* frame headings are visible, so
    // a freshly opened notebook renders already collapsed instead of a full
    // expanded frame that collapses one frame later. Once that key is set, later
    // recompositions (doc reloads, resumes) keep the same slot, so user toggles
    // persist as before.
    var collapsed by rememberSaveable(notebookId, loadedDoc != null, stateSaver = IntSetSaver) {
        mutableStateOf(
            loadedDoc?.headlines
                ?.filter { loadedDoc.hasDescendants(it) }
                ?.map { it.lineIndex }
                ?.toSet()
                ?: emptySet()
        )
    }
    // A move/indent/delete shifts heading lines; carry the collapsed set along in
    // the same composition the new document arrives in, so a collapsed subtree
    // never flashes open (or a crossed one unfolds) for a frame.
    val loaded = state as? DocumentUiState.Loaded
    var remappedFor by remember { mutableStateOf<DocumentUiState.Loaded?>(null) }
    val lineRemap = loaded?.lineRemap
    if (lineRemap != null && remappedFor !== loaded) {
        remappedFor = loaded
        collapsed = collapsed.mapNotNull(lineRemap).toSet()
    }
    var prefaceExpanded by rememberSaveable(notebookId) { mutableStateOf(false) }
    // Collapsed by default, like heading `:PROPERTIES:` drawers in Read mode.
    var filePropsExpanded by rememberSaveable(notebookId) { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var revealDone by rememberSaveable(notebookId) { mutableStateOf(false) }
    var revealedLine by remember { mutableStateOf<Int?>(null) }
    val revealHighlight = remember { Animatable(0f) }

    // Only one swipe panel open at a time; any mutation snaps it shut.
    var openRowLine by remember { mutableStateOf<Int?>(null) }
    // Which headline the date-picker dialog targets: lineIndex to "scheduled"/"deadline".
    var datePickerFor by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Line index whose TODO state the swipe panel's "State" action is picking.
    var statePickerFor by remember { mutableStateOf<Int?>(null) }
    // Line index whose swipe panel "Note" action opened the note-input dialog.
    var noteDialogFor by remember { mutableStateOf<Int?>(null) }
    // The command bar's metadata sheet, acting on every selected headline.
    var metadataOpen by remember { mutableStateOf(false) }
    val selectionEmpty = selectedLines.isEmpty()
    LaunchedEffect(selectionEmpty) { if (selectionEmpty) metadataOpen = false }

    // Hoisted to screen scope so every row's swipe panel shares one call instead of
    // one per row per recomposition (vectorResource can't be called inside remember{}
    // — it's itself a @Composable that memoizes on (theme, id)).
    val icNote = ImageVector.vectorResource(id = R.drawable.ic_note)
    val favIconOutline = favoriteIcon()
    val favIconFilled = favoriteIconFilled()
    // O(1) favorite lookup per row instead of an O(favorites) `.any` scan per row per
    // recomposition; split by whether the favorite matches on customId or raw lineIndex
    // (see FavoriteNote.matches).
    val favoriteCustomIds = remember(favorites) { favorites.mapNotNull { it.customId }.toSet() }
    val favoriteLineIndices = remember(favorites) {
        favorites.filter { it.customId == null }.map { it.lineIndex }.toSet()
    }
    fun toggleFavoriteOf(h: OrgHeadline, isFavorite: Boolean) {
        if (isFavorite) {
            val existing = favorites.firstOrNull { it.matches(h) }
            onUnfavorite(notebookId, h.lineIndex, existing?.customId)
            viewModel.showToast("Removed favorite")
        } else {
            viewModel.ensureStableId(h) { customId ->
                onFavorite(notebookId, h.lineIndex, h.title, customId)
            }
            viewModel.showToast("★ Added to favorites")
        }
    }

    // The command bar takes over the top bar in focus mode; back exits it.
    BackHandler(enabled = selectedLines.isNotEmpty()) { viewModel.clearSelection() }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            if (selectedLines.isNotEmpty()) {
                StructureCommandBar(
                    selectedCount = selectedLines.size,
                    onExit = { viewModel.clearSelection() },
                    onMetadata = { metadataOpen = true },
                    viewModel = viewModel,
                )
            } else {
                GroveTopBar(
                    leading = {
                        if (narrowTarget != null) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClick = onWiden)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("←", fontFamily = PlexMono, fontSize = 19.sp, color = c.ink)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "widen",
                                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, color = c.accent,
                                )
                            }
                        } else {
                            IconGlyph("←", onClick = onBack)
                        }
                    },
                    title = {
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(
                                breadcrumbFileLabel(notebookId),
                                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp, color = c.ink,
                                modifier = Modifier.testTag("outline_file_label"),
                            )
                            if (narrowTarget != null) {
                                Text(
                                    "narrowed to “${narrowTarget.title}”",
                                    fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            } else {
                                (state as? DocumentUiState.Loaded)?.let {
                                    // Matches RoomNoteIndex.noteCount: top-level headings
                                    // plus the intro, if any.
                                    val noteCount = remember(it.document) {
                                        it.document.headlines.count { h -> h.level == 1 } +
                                            (if (it.document.hasIntro) 1 else 0)
                                    }
                                    Text(
                                        pluralCount(noteCount, "note"),
                                        fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        (state as? DocumentUiState.Loaded)?.let { loaded ->
                            val foldable = remember(loaded.document, scopedHeadlines) {
                                scopedHeadlines
                                    .filter { loaded.document.hasDescendants(it) }
                                    .map { it.lineIndex }
                                    .toSet()
                            }
                            if (foldable.isNotEmpty()) {
                                // Chevrons pointing apart = all folded (tap to expand
                                // all); chevrons pointing together = expanded (tap to
                                // collapse all).
                                val allCollapsed = collapsed.containsAll(foldable)
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            collapsed = if (allCollapsed) emptySet() else foldable
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (allCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                                        contentDescription = if (allCollapsed) "Expand all headings" else "Collapse all headings",
                                        tint = c.ink,
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onSearchInNotebook),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                searchIcon(),
                                contentDescription = "Search in this notebook",
                                tint = c.ink,
                            )
                        }
                        var displayMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            NewDotBadge(NewAnchors.OUTLINE_MENU) {
                                IconGlyph("⋮", onClick = { displayMenuOpen = true })
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = displayMenuOpen,
                                onDismissRequest = { displayMenuOpen = false },
                                containerColor = c.surface,
                            ) {
                                // "View file": the whole file as one note. Disabled
                                // (with the reason) once the file is over the line
                                // limit, since the whole-file editor is a single text
                                // field that would freeze on a huge buffer.
                                val lineCount = (state as? DocumentUiState.Loaded)?.document?.lines?.size
                                val fitsWhole = lineCount != null && fitsWholeFileView(lineCount, wholeFileLineLimit)
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "View file",
                                                    fontFamily = PlexSans, fontSize = 14.sp,
                                                    color = if (fitsWhole) c.ink else c.ink3,
                                                )
                                                if (fitsWhole) NewDot(NewAnchors.OUTLINE_VIEW_FILE, Modifier.padding(start = 6.dp))
                                            }
                                            if (!fitsWhole && lineCount != null) {
                                                Text(
                                                    "Too large to open whole (${pluralCount(lineCount, "line")}). " +
                                                        "Open a heading instead.",
                                                    fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3,
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = null,
                                            tint = if (fitsWhole) c.accent else c.ink3,
                                        )
                                    },
                                    enabled = fitsWhole,
                                    onClick = { displayMenuOpen = false; onViewFile(notebookId) },
                                    modifier = Modifier.testTag("outline_view_file"),
                                )
                                HorizontalDivider(color = c.line)
                                @Composable
                                fun toggleItem(label: String, value: Boolean, toggle: OutlineToggle) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (value) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (value) c.accent else c.ink3,
                                            )
                                        },
                                        onClick = { onToggleDisplay(toggle, !value) },
                                    )
                                }
                                toggleItem("Show tags", displayFlags.tags, OutlineToggle.TAGS)
                                toggleItem("Show timestamps", displayFlags.timestamps, OutlineToggle.TIMESTAMPS)
                                toggleItem("Show keywords", displayFlags.keywords, OutlineToggle.KEYWORDS)
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Backlinks are roam-only: the bar never shows for a file without a
            // file-level :ID:, regardless of the "Show backlinks" toggle.
            val isRoamFile = (state as? DocumentUiState.Loaded)?.document?.fileId != null
            if (showBacklinks && isRoamFile && selectedLines.isEmpty()) {
                LinkedReferencesBar(
                    linkedCount = linkedReferences.linkedCount,
                    unlinkedCount = linkedReferences.unlinkedCount,
                    onClick = { linkedRefsOpen = true },
                )
            }
        },
        floatingActionButton = {
            // PRD §5.3: FAB adds a new top-level note to this notebook. Hidden while
            // narrowed: adding a top-level note doesn't belong to the focused subtree.
            if (narrowTarget == null) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.accent)
                        .testTag("outline_fab")
                        .clickable(onClick = {
                            viewModel.newTopLevelNote { line -> onCreateNote(NoteRef(notebookId, line)) }
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", fontFamily = PlexSans, fontSize = 26.sp, color = c.accentInk)
                }
            }
        },
    ) { padding ->
        when (val s = state) {
            is DocumentUiState.Loading -> {}
            is DocumentUiState.Error -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.message, fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2)
            }

            is DocumentUiState.Loaded -> {
                val doc = s.document
                // Every mutation produces a new document; snap any open panel shut.
                LaunchedEffect(doc) { openRowLine = null }
                val visible = remember(scopedHeadlines, collapsed) { visibleHeadlines(scopedHeadlines, collapsed) }
                // Rows the LazyColumn below places before the first heading; keep in
                // step with its item list so a heading's list index can be computed.
                val leadingItems = listOf(
                    showPropertyDrawers && doc.filePropertyDrawer.isNotEmpty(),
                    showPreface && doc.preambleKeywords.isNotEmpty(),
                    doc.hasIntro && narrowTarget == null,
                ).count { it }
                // Search's "show in notebook": unfold the heading's ancestors, scroll
                // it into view, then fade an accentSoft wash off it. Once per entry,
                // so returning from a note doesn't replay it.
                LaunchedEffect(revealLineIndex) {
                    val line = revealLineIndex ?: return@LaunchedEffect
                    if (revealDone) return@LaunchedEffect
                    revealDone = true
                    val target = doc.headlineAtLine(line) ?: return@LaunchedEffect
                    val ancestors = generateSequence(doc.parent(target)) { doc.parent(it) }.map { it.lineIndex }.toSet()
                    val unfolded = collapsed - ancestors
                    collapsed = unfolded
                    val index = visibleHeadlines(scopedHeadlines, unfolded).indexOfFirst { it.lineIndex == line }
                    if (index < 0) return@LaunchedEffect
                    val listIndex = leadingItems + index
                    snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > listIndex }
                    listState.scrollToItem(listIndex)
                    revealedLine = line
                    revealHighlight.snapTo(1f)
                    delay(REVEAL_HOLD_MS)
                    revealHighlight.animateTo(0f, tween(REVEAL_FADE_MS))
                    revealedLine = null
                }
                // A file can hold real content before its first heading (or
                // with no heading at all). It's not a headline, so it gets its
                // own tap-only row above the outline, opening in read mode.
                val showIntroRow = doc.hasIntro && narrowTarget == null
                // A leading file-level `:PROPERTIES:` drawer, and the file-level
                // `#+` keyword box, are only pinned here when there's no list
                // below to scroll them with (no headings and no intro row).
                // Otherwise the in-list items render them instead.
                val pinnedHeader = doc.headlines.isEmpty() && !showIntroRow
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (pinnedHeader && showPropertyDrawers && doc.filePropertyDrawer.isNotEmpty()) {
                        CollapsibleKvSection(
                            label = ":PROPERTIES:",
                            entries = doc.filePropertyDrawer,
                            expanded = filePropsExpanded,
                            onToggle = { filePropsExpanded = !filePropsExpanded },
                            modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp),
                            onDoubleTap = { onOpenFileProperties(notebookId) },
                        )
                    }
                    if (pinnedHeader && showPreface && doc.preambleKeywords.isNotEmpty()) {
                        CollapsibleKvSection(
                            label = "PREFACE",
                            entries = doc.preambleKeywords,
                            expanded = prefaceExpanded,
                            onToggle = { prefaceExpanded = !prefaceExpanded },
                            modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp),
                            onDoubleTap = { onOpenPreface(notebookId) },
                        )
                    }
                    Box(Modifier.fillMaxSize().weight(1f)) {
                    if (scopedHeadlines.isEmpty() && !showIntroRow) {
                        // Empty state still needs the overlays below: undoing a
                        // delete/refile of the last note happens from here.
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BrandMarkGlyph(size = 28.dp, color = c.ink3)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "This notebook is empty",
                                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp, color = c.ink2,
                            )
                            Text(
                                "Tap + to write your first note",
                                fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
                            )
                        }
                    } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp)
                            .testTag("outline_list"),
                        // Bottom inset so the last row scrolls clear of the FAB
                        // (54.dp) instead of sitting underneath it.
                        contentPadding = PaddingValues(bottom = 86.dp),
                    ) {
                        // File header metadata first, then the content row, then
                        // the headings — the file's own top-to-bottom order.
                        // Scrolls away with the rest of the outline instead of
                        // staying pinned above the list.
                        if (showPropertyDrawers && doc.filePropertyDrawer.isNotEmpty()) {
                            item(key = "fileProperties") {
                                CollapsibleKvSection(
                                    label = ":PROPERTIES:",
                                    entries = doc.filePropertyDrawer,
                                    expanded = filePropsExpanded,
                                    onToggle = { filePropsExpanded = !filePropsExpanded },
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    onDoubleTap = { onOpenFileProperties(notebookId) },
                                )
                            }
                        }
                        if (showPreface && doc.preambleKeywords.isNotEmpty()) {
                            item(key = "preface") {
                                CollapsibleKvSection(
                                    label = "PREFACE",
                                    entries = doc.preambleKeywords,
                                    expanded = prefaceExpanded,
                                    onToggle = { prefaceExpanded = !prefaceExpanded },
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    onDoubleTap = { onOpenPreface(notebookId) },
                                )
                            }
                        }
                        if (showIntroRow) {
                            item(key = "introRow") {
                                IntroRow(
                                    doc = doc,
                                    onTap = { onOpenNote(NoteRef.intro(notebookId)) },
                                )
                            }
                        }
                        items(visible, key = { it.lineIndex }) { h ->
                            val isFavorite = remember(h.lineIndex, h.customId, h.id, favoriteCustomIds, favoriteLineIndices) {
                                favoriteCustomIds.containsIdOf(h) || h.lineIndex in favoriteLineIndices
                            }
                            val toggleFavorite = { toggleFavoriteOf(h, isFavorite) }
                            // Right-swipe panel: state / schedule / note / favorite.
                            val leftActions = remember(h.lineIndex, isFavorite, c) {
                                listOf(
                                    SwipeAction("⟳", "State", c.amber, c.amberSoft) {
                                        statePickerFor = h.lineIndex
                                    },
                                    SwipeAction(
                                        label = "Schedule",
                                        fg = c.blue,
                                        bg = c.blueSoft,
                                        icon = Icons.Outlined.CalendarMonth,
                                    ) {
                                        // Both SCHEDULED and DEADLINE live on one screen now, so a
                                        // single entry point opens it (defaulting to the SCHEDULED tab).
                                        datePickerFor = h.lineIndex to "scheduled"
                                    },
                                    SwipeAction(
                                        label = "Note",
                                        fg = c.green,
                                        bg = c.greenSoft,
                                        icon = icNote,
                                    ) {
                                        noteDialogFor = h.lineIndex
                                    },
                                    SwipeAction(
                                        // Filled star + "Unfav" once the row is a
                                        // favorite, so the panel reflects current state.
                                        label = if (isFavorite) "Unfav" else "Fav",
                                        fg = c.accent,
                                        bg = c.accentSoft,
                                        icon = if (isFavorite) favIconFilled else favIconOutline,
                                        onClick = toggleFavorite,
                                    ),
                                )
                            }
                            // Left-swipe panel: insert above / below / sub-note / refile.
                            val rightActions = remember(h.lineIndex, c) {
                                listOf(
                                    SwipeAction("↑+", "Above", c.amber, c.amberSoft) {
                                        viewModel.insertSiblingAbove(h) { line ->
                                            onCreateNote(NoteRef(notebookId, line))
                                        }
                                    },
                                    SwipeAction("↓+", "Below", c.blue, c.blueSoft) {
                                        viewModel.insertSiblingBelow(h) { line ->
                                            onCreateNote(NoteRef(notebookId, line))
                                        }
                                    },
                                    SwipeAction("↳", "Sub", c.green, c.greenSoft) {
                                        viewModel.newChild(h) { line ->
                                            onCreateNote(NoteRef(notebookId, line))
                                        }
                                    },
                                    SwipeAction("➜", "Refile", c.accent, c.accentSoft) {
                                        viewModel.startRefile(h)
                                    },
                                )
                            }
                            // derivedStateOf: openRowLine/selectedLines change on every open/select
                            // toggle, but only the previously- and newly-affected rows actually
                            // flip these booleans, so only those rows recompose.
                            val forceCloseRow by remember(h.lineIndex) { derivedStateOf { openRowLine != h.lineIndex } }
                            val isFocusedRow by remember(h.lineIndex) { derivedStateOf { h.lineIndex in selectedLines } }
                            SwipeRevealRow(
                                leftActions = leftActions,
                                rightActions = rightActions,
                                enabled = selectedLines.isEmpty(),
                                forceClose = forceCloseRow,
                                onOpenChanged = { open ->
                                    if (open) openRowLine = h.lineIndex
                                    else if (openRowLine == h.lineIndex) openRowLine = null
                                },
                                // In focus mode a tap adds/removes the row from the
                                // bulk selection instead of opening it.
                                onTap = {
                                    if (selectedLines.isNotEmpty()) viewModel.toggleSelected(h.lineIndex)
                                    else onOpenNote(NoteRef(notebookId, h.lineIndex))
                                },
                                onLongPress = { viewModel.startSelection(h.lineIndex) },
                                modifier = if (isFocusedRow) Modifier.zIndex(1f) else Modifier,
                                // Search's result-row radius, so the action panels get rounded ends too.
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                OutlineNode(
                                    doc = doc,
                                    headline = h,
                                    isCollapsed = h.lineIndex in collapsed,
                                    isFocused = isFocusedRow,
                                    highlight = if (revealedLine == h.lineIndex) revealHighlight.value else 0f,
                                    onToggle = {
                                        collapsed = if (h.lineIndex in collapsed) collapsed - h.lineIndex
                                        else collapsed + h.lineIndex
                                    },
                                    isFavorite = isFavorite,
                                    flags = displayFlags,
                                )
                            }
                        }
                    }
                    ScrollJumpButtons(
                        listState = listState,
                        // Stacked above the FAB (54.dp + its own padding) so the two
                        // never overlap.
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 86.dp, end = 16.dp),
                    )
                    }
                    // Lifted above the FAB (54.dp + insets) so UNDO stays tappable.
                    GroveUndoSnackbar(
                        snack = snack,
                        onUndo = viewModel::undo,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 86.dp),
                    )
                    GroveToast(
                        toast = toast,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp),
                    )
                    }
                }

                statePickerFor?.let { line ->
                    doc.headlineAtLine(line)?.let { headline ->
                        StatePickerSheet(
                            title = headline.title,
                            keywords = doc.keywords,
                            current = headline.keyword,
                            onPick = { keyword ->
                                viewModel.setState(headline, keyword)
                                statePickerFor = null
                            },
                            onDismiss = { statePickerFor = null },
                        )
                    }
                }

                datePickerFor?.let { (line, target) ->
                    val headline = doc.headlineAtLine(line)
                    PlanningDatesScreen(
                        title = headline?.title.orEmpty(),
                        scheduled = headline?.planning?.scheduled,
                        deadline = headline?.planning?.deadline,
                        active = headline?.dedicatedActiveTimestamps.orEmpty(),
                        focus = if (target == "scheduled") PlanningKind.SCHEDULED else PlanningKind.DEADLINE,
                        onDismiss = { datePickerFor = null },
                        onConfirm = { sched, dead, active ->
                            if (headline != null) viewModel.setPlanningDates(headline, sched, dead, active)
                            datePickerFor = null
                        },
                    )
                }

                noteDialogFor?.let { line ->
                    val headline = doc.headlineAtLine(line)
                    if (headline != null) {
                        NoteDialog(
                            title = headline.title,
                            onDismiss = { noteDialogFor = null },
                            onConfirm = { note ->
                                viewModel.addNote(headline, note)
                                noteDialogFor = null
                            },
                        )
                    }
                }

                if (metadataOpen && !selectionEmpty) {
                    val selected = doc.headlines.filter { it.lineIndex in selectedLines }
                    val single = selected.singleOrNull()
                    if (single != null) {
                        // One headline: exactly read mode's sheet.
                        val isFavorite = favorites.any { it.matches(single) }
                        MetadataSheet(
                            headline = single,
                            keywords = doc.keywords,
                            allTags = allTags,
                            onChangeKeyword = { kw -> viewModel.setState(single, kw) },
                            onSetPriority = { p -> viewModel.setPriority(single, p) },
                            onSetTags = { tags -> viewModel.setTags(single, tags) },
                            onSetPlanningDates = { sched, dead, active ->
                                viewModel.setPlanningDates(single, sched, dead, active)
                            },
                            onAddNote = { note -> viewModel.addNote(single, note) },
                            onGenerateId = { viewModel.generateId(single) },
                            onRefile = {
                                metadataOpen = false
                                viewModel.startRefile(single)
                            },
                            showFavorite = true,
                            isFavorite = isFavorite,
                            onToggleFavorite = { toggleFavoriteOf(single, isFavorite) },
                            onDismiss = { metadataOpen = false },
                        )
                    } else {
                        bulkMetadataSummary(selected)?.let { summary ->
                            val shown = summary.headline
                            val lines = selected.map { it.lineIndex }.toSet()
                            MetadataSheet(
                                headline = shown,
                                keywords = doc.keywords,
                                allTags = allTags,
                                onChangeKeyword = { kw -> viewModel.setStateEach(lines, kw) },
                                onSetPriority = { p -> viewModel.setPriorityEach(lines, p) },
                                // The sheet edits the tags every heading shares; apply
                                // just what was added or removed to each one.
                                onSetTags = { tags ->
                                    viewModel.editTagsEach(lines, add = tags - shown.tags, remove = shown.tags - tags)
                                },
                                onSetPlanningDates = { sched, dead, active ->
                                    viewModel.setPlanningEach(lines, BulkPlanningEdit.between(shown, sched, dead, active))
                                },
                                datesMixed = summary.datesMixed,
                                onSetPlanningDatesBulk = { sched, dead, active, removed ->
                                    viewModel.setPlanningEach(lines, BulkPlanningEdit.between(shown, sched, dead, active, removed))
                                },
                                onAddNote = { note -> viewModel.addNoteEach(lines, note) },
                                onGenerateId = { viewModel.generateIdEach(lines) },
                                onRefile = {
                                    metadataOpen = false
                                    viewModel.startRefileEach(lines)
                                },
                                stateMixed = summary.stateMixed,
                                priorityMixed = summary.priorityMixed,
                                onDismiss = { metadataOpen = false },
                            )
                        }
                    }
                }

                refileState?.let { refile ->
                    RefileSheet(
                        state = refile,
                        headerTitle = "Refile ${pluralCount(refile.sourceLines.size, "note")}",
                        currentFileName = notebookId,
                        currentDoc = doc,
                        onPickNotebook = viewModel::refilePickNotebook,
                        onDrillInto = viewModel::refileDrillInto,
                        onBack = viewModel::refileBack,
                        onCancel = viewModel::refileCancel,
                        onConfirm = viewModel::refileConfirm,
                        onArchive = viewModel::refileToArchive,
                        onPickLastUsed = viewModel::refileToLastUsed,
                    )
                }

                if (linkedRefsOpen) {
                    val title = doc.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }
                        ?.second ?: notebookId.removeSuffix(".org")
                    LinkedReferencesSheet(
                        title = title,
                        result = linkedReferences,
                        onOpenReference = { fileName, lineIndex, id ->
                            linkedRefsOpen = false
                            onOpenNote(NoteRef(fileName, lineIndex, id))
                        },
                        onDismiss = { linkedRefsOpen = false },
                    )
                }
            }
        }
    }
}

/** Swipe panel's "Note" action: free text logged into the headline's LOGBOOK drawer. */
@Composable
internal fun NoteDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = MaterialTheme.grove
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Column {
                Text(
                    "Add note",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
                Text(
                    title,
                    fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink2,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Note contents", fontFamily = PlexSans, color = c.ink3) },
                textStyle = TextStyle(fontFamily = PlexSans, color = c.ink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = true,
                ),
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("Add", color = c.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) }
        },
    )
}

/**
 * "Move & indent" bar that replaces the top bar in focus mode (design spec
 * Gestures screen): 56dp, accentSoft bg. Commands act on every selected
 * headline in turn; each handler reads the selection at click time since
 * line indices shift after every mutation.
 */
@Composable
private fun StructureCommandBar(
    selectedCount: Int,
    onExit: () -> Unit,
    onMetadata: () -> Unit,
    viewModel: DocumentViewModel,
) {
    val c = MaterialTheme.grove
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = { viewModel.selectedLines.value }
    // Background first so the accentSoft wash extends behind the status bar,
    // then the inset padding (Scaffold does not pad the topBar slot).
    Column(Modifier.background(c.accentSoft).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onExit),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", fontFamily = PlexSans, fontSize = 16.sp, color = c.accent)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "$selectedCount selected",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp, color = c.accent,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 6dp apart so neighbouring touch targets aren't flush.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CommandButton(Icons.Default.KeyboardArrowUp, "Move up", c.ink) { viewModel.moveUp(selected()) }
                CommandButton(Icons.Default.KeyboardArrowDown, "Move down", c.ink) { viewModel.moveDown(selected()) }
                CommandButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Promote", c.ink) { viewModel.promote(selected()) }
                CommandButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Demote", c.ink) { viewModel.demote(selected()) }
                // ☰, like read mode's metadata button: state, priority, tags, dates.
                CommandButton(Icons.Default.Menu, "Metadata", c.ink, onClick = onMetadata)
                CommandButton(Icons.Default.Delete, "Delete", c.red) { confirmDelete = true }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = {
                Text(
                    if (selectedCount > 1) "Delete $selectedCount notes?" else "Delete this note?",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
            },
            text = {
                Text(
                    if (selectedCount > 1) "This will delete the headings and everything under them."
                    else "This will delete the heading and everything under it.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNotes(selected())
                    confirmDelete = false
                    onExit()
                }) { Text("Delete", color = c.red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.ink2) }
            },
        )
    }
}

@Composable
private fun CommandButton(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.grove.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** [headlines] is either the whole document or, when narrowed, just a target heading + its subtree. */
private fun visibleHeadlines(headlines: List<OrgHeadline>, collapsed: Set<Int>): List<OrgHeadline> {
    val result = mutableListOf<OrgHeadline>()
    var hideDeeperThan: Int? = null
    for (h in headlines) {
        val hideLevel = hideDeeperThan
        if (hideLevel != null) {
            if (h.level > hideLevel) continue
            hideDeeperThan = null
        }
        result.add(h)
        if (h.lineIndex in collapsed) hideDeeperThan = h.level
    }
    return result
}

/**
 * Row for a file's heading-less content (everything before the first `*`), shown
 * above the outline. Renders that content in full as flowing body text with
 * inline org markup applied — no synthesized heading, no leaf bullet, no
 * truncation. Tap-only: opens read mode scoped to that content.
 */
@Composable
private fun IntroRow(doc: OrgDocument, onTap: () -> Unit) {
    val c = MaterialTheme.grove
    val content = remember(doc, c) {
        annotateOrgInline(doc.introBody.joinToString("\n").trim(), c)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg)
            .clickable(onClick = onTap)
            // Left edge lines up with the leaf-row bullets, with matching padding
            // on the right so the block reads as evenly inset.
            .padding(vertical = 10.dp, horizontal = 11.dp),
    ) {
        Text(
            content,
            fontFamily = PlexSans,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = c.ink,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutlineNode(
    doc: OrgDocument,
    headline: OrgHeadline,
    isCollapsed: Boolean,
    isFocused: Boolean,
    onToggle: () -> Unit,
    isFavorite: Boolean = false,
    flags: OutlineDisplayFlags = OutlineDisplayFlags(),
    /** 1 = full accentSoft "landing" wash, 0 = none (see revealLineIndex). */
    highlight: Float = 0f,
) {
    val c = MaterialTheme.grove
    val hasChildren = remember(doc, headline) { doc.hasDescendants(headline) }
    // Only needed for the "… N" collapsed indicator below.
    val childCount = remember(doc, headline) { doc.directChildren(headline).size }
    val isDone = headline.keyword != null && doc.keywords.isDone(headline.keyword)
    // Tokenizing the title allocates a new AnnotatedString; rows recompose on
    // scroll and swipe, so keep it across recompositions.
    val titleAnnotated = remember(headline.title, c) { annotateOrgInline(headline.title, c) }

    // Selected rows get an accentSoft fill, the same "active row" wash the
    // dropdown pickers and the command bar above use. It's drawn inset 2dp top
    // and bottom (not padded) so adjacent selections read as separate rows and
    // a row doesn't shift when it's selected.
    val focusModifier = if (isFocused) {
        Modifier.drawBehind {
            val inset = 2.dp.toPx()
            drawRoundRect(
                color = c.accentSoft,
                topLeft = Offset(0f, inset),
                size = Size(size.width, size.height - 2 * inset),
                cornerRadius = CornerRadius(12.dp.toPx()),
            )
        }
    } else {
        Modifier.clip(RoundedCornerShape(10.dp)).background(lerp(c.bg, c.accentSoft, highlight))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(focusModifier)
            .padding(
                start = (22 * (headline.level - 1)).dp,
                top = 9.dp, bottom = 9.dp, end = 6.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        // Caret + asterisk centered together so the caret sits on the same
        // line as the level markers, independent of the title's own height.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Drawn on a Canvas rather than as ○/▸/▾ glyphs: those characters'
            // ink sits at different offsets within their own line box depending
            // on glyph and font size, so Box's contentAlignment=Center couldn't
            // make all three states line up with each other or the asterisk.
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .testTag("outline_toggle")
                    .clickable(enabled = hasChildren, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (hasChildren) {
                    Canvas(Modifier.size(9.dp)) {
                        val path = if (isCollapsed) {
                            Path().apply {
                                moveTo(0f, 0f)
                                lineTo(0f, size.height)
                                lineTo(size.width, size.height / 2f)
                                close()
                            }
                        } else {
                            Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width, 0f)
                                lineTo(size.width / 2f, size.height)
                                close()
                            }
                        }
                        drawPath(path, color = c.ink3)
                    }
                } else {
                    Canvas(Modifier.size(7.dp)) {
                        drawCircle(color = c.ink3, style = Stroke(width = 1.2.dp.toPx()))
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "*".repeat(headline.level),
                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp, color = c.starColor(headline.level),
            )
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            // Baseline-aligned so the keyword/priority chips sit on the first
            // line when the title wraps, instead of centering on the block.
            Row {
                headline.keyword?.takeIf { flags.keywords }?.let { kw ->
                    val (fg, bg) = when {
                        doc.keywords.isDone(kw) -> c.green to c.greenSoft
                        kw == "IN-PROGRESS" -> c.blue to c.blueSoft
                        else -> c.amber to c.amberSoft
                    }
                    Text(
                        kw,
                        fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, color = fg,
                        modifier = Modifier
                            .alignByBaseline()
                            .clip(RoundedCornerShape(5.dp))
                            .background(bg)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                headline.priority?.let { p ->
                    Text(
                        "[#$p]",
                        fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, color = c.priorityColor(p),
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    titleAnnotated,
                    fontFamily = PlexSans,
                    fontWeight = if (headline.level == 1) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 14.5.sp,
                    color = if (isDone) c.ink3 else c.ink,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                    // fill = true reserves the trailing tag/star width on every
                    // wrapped line so they land flush at the row's right edge,
                    // baseline-aligned to the title's first line.
                    modifier = Modifier.alignByBaseline().weight(1f, fill = true),
                )
                if (isCollapsed && childCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "… $childCount", fontFamily = PlexMono, fontSize = 11.sp, color = c.ink3,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                if (flags.tags && headline.tags.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        headline.tags.joinToString(":", prefix = ":", postfix = ":"),
                        fontFamily = PlexMono, fontSize = 11.sp, color = c.synTag,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                if (isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    FavoriteStar(modifier = Modifier.alignByBaseline())
                }
            }
            // Body preview: first two non-empty lines, keeping the line breaks
            // so multi-line content stays readable. Feed the Text a third line
            // (when there is one) so it overflows past maxLines = 2 and draws a
            // trailing ellipsis — the cue that there's more content below.
            val preview = remember(doc, headline) {
                doc.bodyWithoutDedicatedTimestamp(headline)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(3)
                    .joinToString("\n")
                    .take(240)
            }
            if (preview.isNotEmpty()) {
                Text(
                    preview,
                    fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink3,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Scheduled / deadline chips, side by side, wrapping to a second
            // line only if they don't both fit on one.
            val hasScheduled = headline.planning.scheduled != null && flags.timestamps
            val hasDeadline = headline.planning.deadline != null && flags.timestamps
            val hasActive = headline.dedicatedActiveTimestamps.isNotEmpty() && flags.timestamps
            if (hasScheduled || hasDeadline || hasActive) {
                FlowRow(
                    modifier = Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    headline.planning.scheduled?.takeIf { flags.timestamps }?.let { ts ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(c.blueSoft)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = c.blue, modifier = Modifier.size(11.dp))
                            Text(
                                remember(ts) { ts.formatHuman() },
                                fontFamily = PlexMono, fontSize = 11.sp, color = c.blue,
                            )
                        }
                    }
                    headline.planning.deadline?.takeIf { flags.timestamps }?.let { ts ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(c.redSoft)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Filled.Flag, contentDescription = null, tint = c.red, modifier = Modifier.size(11.dp))
                            Text(
                                remember(ts) { ts.formatHuman() },
                                fontFamily = PlexMono, fontSize = 11.sp, color = c.red,
                            )
                        }
                    }
                    // Bare active timestamps (events): violet dot chip. Only the
                    // dedicated line right after the heading — inline `<…>` stamps in
                    // prose stay as text in the preview above, not chipped here.
                    if (flags.timestamps) {
                        headline.dedicatedActiveTimestamps.forEach { ts ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(c.violetSoft)
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(Icons.Filled.Circle, contentDescription = null, tint = c.violet, modifier = Modifier.size(7.dp))
                                Text(
                                    remember(ts) { ts.formatHuman() },
                                    fontFamily = PlexMono, fontSize = 11.sp, color = c.violet,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search's "show in notebook" landing wash: held, then faded out. */
private const val REVEAL_HOLD_MS = 300L
private const val REVEAL_FADE_MS = 1200
