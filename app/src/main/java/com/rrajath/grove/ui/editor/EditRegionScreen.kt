package com.rrajath.grove.ui.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.FeatureFlags
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.RoamNodeResult
import com.rrajath.grove.capture.formatLink
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.org.LineEditing
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.ui.components.ReadEditToggle
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.KeyboardHiddenLinkedReferencesBar
import com.rrajath.grove.ui.components.LinkedReferencesSheet
import com.rrajath.grove.ui.components.ScrollJumpButtons
import com.rrajath.grove.ui.components.rememberImeVisible
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.compactFileLabel
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * Raw org editor scoped to the file's preface: its leading `#+KEY:` lines. Opened by
 * double-tapping the outline's PREFACE section. Covers only that keyword run: the
 * property drawer above it and the intro below it have their own editors.
 */
@Composable
fun EditPrefaceScreen(
    fileName: String,
    onBack: () -> Unit,
    editModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) = EditRegionScreen(
    fileName, EditRegion.PREFACE, noteId = null, onBack = onBack,
    editModeFontSize = editModeFontSize, viewModel = viewModel,
)

/**
 * Raw org editor scoped to the file's intro: the heading-less content between the
 * preface and the first `*` heading. Opened by double-tapping that content in read
 * mode. Covers only the content itself, not the `#+KEY:` lines or the property
 * drawer above it.
 */
@Composable
fun EditIntroScreen(
    fileName: String,
    onBack: () -> Unit,
    editModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) = EditRegionScreen(
    fileName, EditRegion.INTRO, noteId = null, onBack = onBack,
    editModeFontSize = editModeFontSize, viewModel = viewModel,
)

/** One-word label for [region], used in this screen's title bar, save toast and leave dialog. */
private fun regionLabel(region: EditRegion): String = when (region) {
    EditRegion.INTRO -> "Intro"
    EditRegion.PREFACE -> "Preface"
    EditRegion.FILE_PROPERTIES, EditRegion.HEADING_PROPERTIES -> "Properties"
    EditRegion.HEADING_LOGBOOK -> "Logbook"
    EditRegion.BLOCK -> "Block"
    EditRegion.WHOLE_FILE -> "File"
}

/**
 * Title-cased block kind for this screen's title bar. Pulled from the buffer's
 * `#+BEGIN_x` line (e.g. "Quote", "Src"); for a standalone `#+KEYWORD:` run it is
 * "Keywords" (several lines) or the keyword's own name ("Caption"). "Block" as a
 * last resort.
 */
private fun blockLabelFromBuffer(buffer: String): String {
    val lines = buffer.lineSequence().toList()
    val begin = lines.firstOrNull { Regex("""^\s*#\+(?i:BEGIN_)\S+""").containsMatchIn(it) }
    if (begin != null) {
        val kind = Regex("""^\s*#\+(?i:BEGIN_)(\S+)""").find(begin)?.groupValues?.get(1) ?: return "Block"
        return kind.lowercase().replaceFirstChar { it.uppercase() }
    }
    val keyword = Regex("""^\s*#\+(?!(?i:BEGIN_|END_))([A-Za-z][\w-]*):""")
    val names = lines.mapNotNull { keyword.find(it)?.groupValues?.get(1) }
    return when {
        names.isEmpty() -> "Block"
        names.size == 1 -> names[0].lowercase().replaceFirstChar { it.uppercase() }
        else -> "Keywords"
    }
}

/**
 * Raw org editor scoped to one region of a file: its preface, its intro, a file-level
 * or per-heading `:PROPERTIES:` drawer, or a `:LOGBOOK:` drawer. Opened by double-tapping
 * the matching section (see [doubleTapToEdit] usage in `CollapsibleKvSection` /
 * `CollapsibleLogSection`). Deliberately a smaller sibling of [EditNoteScreen]: no
 * metadata sheet or blank-heading validation, just the same syntax-highlighted text
 * field, dirty/save affordance, idle auto-save, and stale-file handling. The one
 * region with a Read/Edit toggle is [EditRegion.WHOLE_FILE] (see [onSwitchToRead]).
 */

@Composable
fun EditRegionScreen(
    fileName: String,
    region: EditRegion,
    noteId: String?,
    onBack: () -> Unit,
    /** Absolute doc line of the tapped `#+BEGIN` (or its first affiliated line); [EditRegion.BLOCK] only. */
    blockLine: Int = -1,
    /** Settings § Notes: font-size lever for the editor field. App chrome is unaffected. */
    editModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /**
     * When set, the top bar carries the [ReadEditToggle] (Edit selected), and both it
     * and Back call this. Neither saves: the FILE route hoists [viewModel] so its
     * Read view renders the unsaved buffer from memory (as note Read mode does), and
     * leaving that Read view is what asks to save.
     */
    onSwitchToRead: (() -> Unit)? = null,
    /**
     * Settings § Roam Features (experimental): show the Backlinks bar
     * (backlinks). Only wired for [EditRegion.WHOLE_FILE], and only ever
     * renders for a roam file (one with a file-level `:ID:`).
     */
    showBacklinks: Boolean = false,
    /**
     * Settings § Roam Features (experimental) "Show suggestions while typing"
     * ([com.rrajath.grove.settings.GroveSettings.roamSuggestionsActive]): gates the
     * Roam suggestion providers (file/heading link chips and roam-node chips) only.
     * The suggestion strip slot itself is always present in the [EditRegion.WHOLE_FILE]
     * editor while the keyboard is up; the other region editors have no strip.
     */
    roamSuggestionsEnabled: Boolean = false,
    /** [EditRegion.WHOLE_FILE] only: the Backlinks sheet's "open" action. */
    onOpenNote: (NoteRef) -> Unit = {},
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val label = if (region == EditRegion.BLOCK) blockLabelFromBuffer(state.buffer) else regionLabel(region)
    val textState = rememberTextFieldState()
    var confirmLeave by remember { mutableStateOf(false) }
    // Set when a Backlinks tap needs to navigate away from a dirty
    // buffer; the leave-confirm dialog below routes there instead of onBack.
    var pendingOpenNote by remember { mutableStateOf<NoteRef?>(null) }
    // Whole-file editor only: (fileId, title) parsed once the buffer first
    // loads, not on every keystroke -- fileId/title don't change mid-edit, and
    // reparsing the whole buffer per keystroke would be wasted work.
    var wholeFileMeta by remember(fileName) { mutableStateOf<Pair<String?, String>?>(null) }
    val linkedReferences by viewModel.linkedReferences.collectAsStateWithLifecycle()
    var linkedRefsOpen by remember { mutableStateOf(false) }
    // Inline auto-link suggestion strip (whole-file editor only) -- same
    // mechanism as EditNoteScreen: an index loaded once, and a trigger word
    // recomputed on every text/selection change.
    val autoLinkIndex by viewModel.autoLinkIndex.collectAsStateWithLifecycle()
    val roamNodeTemplates by viewModel.roamNodeSuggestionTemplates.collectAsStateWithLifecycle()
    var autoLinkTrigger by remember { mutableStateOf<WordAtCursor?>(null) }
    var expandedChipKeys by remember(autoLinkTrigger?.range) { mutableStateOf(emptySet<String>()) }
    val autoLinkSuggestions = remember(autoLinkTrigger?.text, autoLinkIndex) {
        val idx = autoLinkIndex
        val word = autoLinkTrigger?.text
        if (idx == null || word == null) emptyList() else filterAutoLinkSuggestions(idx, word)
    }
    // Timestamp of the most recent save (auto or manual); tracked in the
    // ViewModel (state.lastSavedAt) since it now also owns the idle auto-save timer.
    val lastAutoSavedAt = state.lastSavedAt?.toLocalTime()
    val focusRequester = remember { FocusRequester() }
    // False until the region has been loaded into the text field: the field's
    // pre-load contents are not the user's edits and must not be reported.
    var fieldLoaded by remember { mutableStateOf(false) }
    // Experimental attachments (debug builds only), for the whole-file editor.
    val attach = if (FeatureFlags.ATTACHMENTS && region == EditRegion.WHOLE_FILE) {
        rememberAttachController(textState, fileName, fieldLoaded)
    } else {
        null
    }
    // Text last written into the field programmatically (initial load only, here).
    // The snapshotFlow below echoes every write straight back; that echo must not
    // be reported as a user edit, which would wrongly mark a freshly opened
    // region dirty.
    var echoToSkip by remember { mutableStateOf<String?>(null) }

    fun setText(text: String, cursor: TextRange) {
        echoToSkip = text
        textState.edit {
            replace(0, length, text)
            selection = cursor
        }
    }

    fun leave() {
        when {
            // Back from the whole-file editor lands in its Read view, which keeps
            // showing the unsaved buffer; only leaving that view asks to save.
            onSwitchToRead != null -> onSwitchToRead()
            state.dirty -> confirmLeave = true
            else -> onBack()
        }
    }
    fun openNote(target: NoteRef) {
        if (state.dirty) {
            pendingOpenNote = target
            confirmLeave = true
        } else {
            onOpenNote(target)
        }
    }
    androidx.activity.compose.BackHandler { leave() }

    // Coming back from the whole-file Read view with unsaved changes: the buffer
    // survived in the hoisted view model, and re-reading the file would drop it.
    val resumedDirty = remember(fileName, region) {
        val s = viewModel.state.value
        s.dirty && s.region == region && s.fileName == fileName
    }
    LaunchedEffect(fileName, noteId, region) {
        if (!resumedDirty) viewModel.loadRegion(fileName, noteId, region, blockLine)
    }
    LaunchedEffect(Unit) { if (region == EditRegion.WHOLE_FILE) viewModel.loadAutoLinkIndex() }
    // Recomputed on every text/selection change so it tracks whatever word is
    // being typed right now; see EditNoteScreen's identical wiring.
    LaunchedEffect(roamSuggestionsEnabled, region) {
        if (!roamSuggestionsEnabled || region != EditRegion.WHOLE_FILE) {
            autoLinkTrigger = null
            return@LaunchedEffect
        }
        snapshotFlow { textState.text.toString() to textState.selection }.collect { (text, selection) ->
            autoLinkTrigger = wordAtCursor(text, selection)?.takeIf { it.text.length >= 3 && !isInPreface(text, selection.start) }
        }
    }
    LaunchedEffect(state.loading) {
        if (!state.loading && state.error == null) {
            setText(state.buffer, TextRange(0))
            fieldLoaded = true
            if (region == EditRegion.WHOLE_FILE) {
                val doc = OrgParser.parse(state.buffer, state.keywords)
                val title = doc.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }
                    ?.second ?: fileName.removeSuffix(".org")
                wholeFileMeta = doc.fileId to title
                // No file :ID: means no bar, so no scan.
                if (doc.fileId != null) {
                    viewModel.loadLinkedReferences(fileName, INTRO_LINE_INDEX, doc.fileId, title)
                } else {
                    viewModel.clearLinkedReferences()
                }
            }
        }
    }
    // Report the user's own edits back to the view model. The programmatic write
    // above is filtered out, so only real typing marks the region dirty.
    LaunchedEffect(Unit) {
        snapshotFlow { textState.text.toString() }.collect { text ->
            if (!fieldLoaded) return@collect
            if (text == echoToSkip) {
                echoToSkip = null
                return@collect
            }
            viewModel.onBufferChange(text)
        }
    }
    val highlight = remember(c, state.keywords) { OrgSyntaxHighlight(c, state.keywords) }

    // Idle auto-save (Settings § Notes → Auto-save notes) runs inside
    // EditorViewModel now, so it isn't a per-keystroke Compose effect here.

    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GroveTopBar(
                leading = {
                    IconGlyph("←", onClick = ::leave)
                    if (state.dirty || lastAutoSavedAt != null) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = if (state.dirty) "Unsaved changes, tap to save" else "Saved",
                            tint = if (state.dirty) c.green else c.ink3,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (state.dirty) {
                                        viewModel.save()
                                    } else {
                                        val message = lastAutoSavedAt?.let {
                                            "$label last saved at: ${AutoSaveTimestamp.format(it)}"
                                        } ?: "This ${label.lowercase()} hasn't been saved yet"
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(10.dp),
                        )
                    }
                },
                title = {
                    // Whole-file editor has no region label to show; the file name
                    // itself moves to the subtitle row below (see ReadFileScreen).
                    if (onSwitchToRead == null) {
                        Text(
                            label,
                            fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp, color = c.ink,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
                subtitle = {
                    // Folder-compacted, single line: scrolls horizontally like the
                    // Read mode breadcrumb instead of wrapping the fixed-height row.
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            compactFileLabel(fileName),
                            fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    if (onSwitchToRead != null) {
                        // Never saves: the FILE route's Read view renders this buffer
                        // from memory (PendingEdit), as note Read mode does.
                        ReadEditToggle(isEditing = true, onToggle = onSwitchToRead)
                    }
                },
            )
        },
    ) { padding ->
        // ime.getBottom > 0 tracks the live keyboard height, unlike isImeVisible's
        // visibility flag; see EditNoteScreen's identical wiring.
        val imeVisible by rememberImeVisible()
        val isRoamFile = region == EditRegion.WHOLE_FILE && wholeFileMeta?.first != null
        // While the Backlinks bar is up it runs to the screen's bottom edge, under
        // the nav bar, exactly like the Read view's Scaffold bottomBar does.
        val backlinksBarShown = showBacklinks && isRoamFile && !imeVisible
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // union (not safeDrawing alone): the bar below and the toolbar must
                // sit inside this same inset-padded Column -- see EditNoteScreen.
                .then(
                    if (backlinksBarShown) Modifier
                    else Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom),
                    ),
                ),
        ) {
            state.error?.let { error ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error, fontFamily = PlexSans, color = c.ink2)
                }
                return@Column
            }
            WholeFileEditorBody(
                attach = attach,
                state = state,
                textState = textState,
                scrollState = scrollState,
                highlight = highlight,
                focusRequester = focusRequester,
                autoLinkSuggestions = autoLinkSuggestions,
                expandedChipKeys = expandedChipKeys,
                autoLinkTriggerRange = autoLinkTrigger?.range,
                onToggleExpandChip = { key -> expandedChipKeys = expandedChipKeys + key },
                onClearAutoLinkTrigger = { autoLinkTrigger = null },
                onOverwriteStale = { viewModel.save(force = true) },
                onReloadStale = { viewModel.dismissStale(); viewModel.loadRegion(fileName, noteId, region, blockLine) },
                editModeFontSize = editModeFontSize,
                showBacklinks = showBacklinks,
                isRoamFile = isRoamFile,
                linkedCount = linkedReferences.linkedCount,
                unlinkedCount = linkedReferences.unlinkedCount,
                onOpenLinkedRefs = { linkedRefsOpen = true },
                imeVisible = imeVisible,
                onLink = { textState.applyToolbarLink(clipboard) },
                modifier = Modifier.fillMaxSize(),
                // Intro gets the slot too, for the block-template chip; the Roam
                // providers below stay whole-file only.
                suggestionSlotEnabled = region == EditRegion.WHOLE_FILE || region == EditRegion.INTRO,
                roamNodeEnabled = roamSuggestionsEnabled && region == EditRegion.WHOLE_FILE && wholeFileMeta?.first != null,
                roamNodeTemplates = roamNodeTemplates,
                autoLinkIndex = autoLinkIndex,
                createOrLinkRoamNode = viewModel::createOrLinkRoamNode,
            )
        }
    }

    if (confirmLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = c.surface,
            title = {
                Text(
                    "Save changes?",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
            },
            text = {
                Text(
                    "This ${label.lowercase()} has unsaved changes.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmLeave = false
                    val target = pendingOpenNote.also { pendingOpenNote = null }
                    viewModel.save(onSaved = { if (target != null) onOpenNote(target) else onBack() })
                }) { Text("Save", color = c.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmLeave = false
                    val target = pendingOpenNote.also { pendingOpenNote = null }
                    // Drop the buffer: a hoisted view model (whole-file editor) would
                    // otherwise bring the discarded text back on return.
                    viewModel.reset()
                    if (target != null) onOpenNote(target) else onBack()
                }) { Text("Discard", color = c.red) }
            },
        )
    }

    if (linkedRefsOpen) {
        LinkedReferencesSheet(
            title = wholeFileMeta?.second ?: fileName.removeSuffix(".org"),
            result = linkedReferences,
            onOpenReference = { refFileName, lineIndex, id ->
                linkedRefsOpen = false
                openNote(NoteRef(refFileName, lineIndex, id))
            },
            onDismiss = { linkedRefsOpen = false },
        )
    }
}

/**
 * The whole-file editor's content area (text field, toolbar, stale-file banner,
 * scroll jump buttons, auto-link suggestions, Backlinks bar) -- everything
 * [EditRegionScreen] puts inside its `Scaffold`'s content padding, minus the
 * `Scaffold`/`GroveTopBar` wrapper itself. Shared with `com.rrajath.grove.ui.dailies.DailyNoteScreen`,
 * which supplies its own top bar around the same body.
 */
@Composable
internal fun WholeFileEditorBody(
    state: EditorUiState,
    textState: TextFieldState,
    scrollState: ScrollState,
    highlight: OrgSyntaxHighlight,
    focusRequester: FocusRequester,
    autoLinkSuggestions: List<AutoLinkSuggestion>,
    expandedChipKeys: Set<String>,
    autoLinkTriggerRange: TextRange?,
    onToggleExpandChip: (String) -> Unit,
    onClearAutoLinkTrigger: () -> Unit,
    onOverwriteStale: () -> Unit,
    onReloadStale: () -> Unit,
    editModeFontSize: FontSizePreference,
    showBacklinks: Boolean,
    isRoamFile: Boolean,
    linkedCount: Int,
    unlinkedCount: Int,
    onOpenLinkedRefs: () -> Unit,
    imeVisible: Boolean,
    onLink: () -> Unit,
    modifier: Modifier = Modifier,
    /** Toolbar `[[]]` long-press (link picker); null keeps the button tap-only. */
    onLinkLongPress: (() -> Unit)? = null,
    /** Toolbar clock long-press (Insert Timestamp picker); null inserts a stamp directly. */
    onTimestampLongPress: (() -> Unit)? = null,
    /** Empty room below the last line. Dailies passes 80dp so its floating date
     *  pills (shown with the keyboard down) don't cover the last line. */
    bottomClearance: androidx.compose.ui.unit.Dp = 18.dp,
    /** Whether this editor has the suggestion strip at all. When true, its [SuggestionSlot]
     *  is reserved above the toolbar whenever the keyboard is up, chips or not, regardless
     *  of which suggestion providers are on (callers gate those: [autoLinkSuggestions] and
     *  [roamNodeEnabled]). */
    suggestionSlotEnabled: Boolean = false,
    /** Selection-triggered roam-node chips (as in EditNoteScreen): the caller gates
     *  this on Settings § Roam Features suggestions and the file having a file-level `:ID:`.
     *  Only takes effect with [suggestionSlotEnabled]. */
    roamNodeEnabled: Boolean = false,
    roamNodeTemplates: List<CaptureTemplate> = emptyList(),
    /** For flipping the roam-node prompt to "link" when the selection names an existing node. */
    autoLinkIndex: List<AutoLinkSuggestion>? = null,
    createOrLinkRoamNode: suspend (CaptureTemplate, String) -> RoamNodeResult? = { _, _ -> null },
    /** Whether the suggestion slot is on screen; Dailies hides its floating date pills
     *  while it is, since with the keyboard down they'd sit on top of the strip. */
    onSuggestionSlotShownChange: (Boolean) -> Unit = {},
    /** Lines kept between the cursor and the field's bottom edge while typing
     *  ([KeepCursorClearOfBottom]); pass the same count as [highlight]'s
     *  `trailingBlankLines`. 0 leaves the field's own cursor tracking alone. */
    bottomMarginLines: Int = 0,
    /** Experimental attachments: paperclip + sheet for this field, or null (no paperclip). */
    attach: AttachController? = null,
) {
    val c = MaterialTheme.grove
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val scrollButtonThresholdPx = with(LocalDensity.current) { (13.5f * 1.85f * 5).sp.toPx() }
    // A non-collapsed, single-line selection; the auto-link trigger needs a collapsed
    // cursor (see wordAtCursor), so the two strips never compete for the same row.
    var roamNodeSelection by remember { mutableStateOf<Pair<String, TextRange>?>(null) }
    var roamNodeExpandedKeys by remember(roamNodeSelection?.second) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(roamNodeEnabled) {
        if (!roamNodeEnabled) {
            roamNodeSelection = null
            return@LaunchedEffect
        }
        snapshotFlow { textState.text.toString() to textState.selection }.collect { (text, selection) ->
            roamNodeSelection = selection.takeIf { !it.collapsed && !isInPreface(text, it.min) }
                ?.let { sel -> text.substring(sel.min, sel.max) to sel }
                ?.takeIf { (selected, _) -> !selected.contains('\n') }
        }
    }
    val roamNodeSuggestionActive = suggestionSlotEnabled && roamNodeSelection != null && roamNodeTemplates.isNotEmpty()
    // `<q` / `<e` / `<s` block-template chip: on wherever the slot is, no setting gate.
    val blockTrigger by rememberBlockTrigger(textState)
    // Reserved while typing (so the text never jumps as link chips come and go), and
    // also shown for a selection with the keyboard down: entering Edit doesn't raise
    // the keyboard, so a long-press selection is often made without it, and the
    // docked strip covers nothing there.
    val suggestionSlotShown = (imeVisible && suggestionSlotEnabled) || roamNodeSuggestionActive
    // Written from the field's onTextLayout; read only by KeepCursorClearOfBottom's flow.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    if (bottomMarginLines > 0) {
        KeepCursorClearOfBottom(textState, scrollState, { textLayout }, bottomMarginLines)
    }
    DisposableEffect(suggestionSlotShown) {
        onSuggestionSlotShownChange(suggestionSlotShown)
        onDispose { onSuggestionSlotShownChange(false) }
    }
    Column(modifier) {
        if (state.staleFile) {
            StaleFileBanner(onOverwrite = onOverwriteStale, onReload = onReloadStale)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ContentFontScale(editModeFontSize) {
                BasicTextField(
                    state = textState,
                    inputTransformation = remember(state.keywords) { orgInputTransformation(state.keywords) },
                    outputTransformation = highlight,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    textStyle = TextStyle(
                        fontFamily = PlexMono, fontSize = 13.5.sp,
                        lineHeight = 1.85.em, color = c.ink,
                        textDirection = EditorTextDirection,
                    ),
                    cursorBrush = SolidColor(c.accent),
                    onTextLayout = { getResult -> textLayout = getResult() },
                    scrollState = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        // Scrolls away like Read mode's top contentPadding (see EditNoteScreen).
                        .scrollAwareTopInset(scrollState, 18.dp)
                        // With the suggestion slot below, the space above the strip's 32dp
                        // chips (in its 50dp height) is the gap under the last line, so no more here.
                        .padding(
                            start = 18.dp, end = 18.dp,
                            bottom = if (suggestionSlotShown) 0.dp else bottomClearance,
                        )
                        .focusRequester(focusRequester),
                )
            }
            ScrollJumpButtons(
                scrollState = scrollState,
                minScrollDeltaPx = scrollButtonThresholdPx,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
        // Suggestions only while typing: with the keyboard down they'd just cover the text.
        // Docked in their own slot between the text and the toolbar (not overlaid on the
        // field), so the line being typed is never hidden behind the chips. The slot stays
        // reserved (empty) while there are no chips, and even with every suggestion
        // provider off, so the text never jumps up and down as suggestions come and go.
        if (suggestionSlotShown) SuggestionSlot {
            val stripModifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)
            val block = blockTrigger
            if (imeVisible && block != null) {
                BlockTemplateSuggestionStrip(
                    template = block.template,
                    onPick = { textState.applyBlockTemplate(block) },
                    modifier = stripModifier,
                )
            } else if (imeVisible && autoLinkSuggestions.isNotEmpty()) {
                AutoLinkSuggestionStrip(
                    suggestions = autoLinkSuggestions,
                    expandedKeys = expandedChipKeys,
                    onToggleExpand = onToggleExpandChip,
                    onPick = { suggestion ->
                        val range = autoLinkTriggerRange ?: return@AutoLinkSuggestionStrip
                        val linkText = formatAutoLinkInsertion(suggestion)
                        textState.edit {
                            replace(range.start, range.end, linkText)
                            selection = TextRange(range.start + linkText.length)
                        }
                        onClearAutoLinkTrigger()
                    },
                    modifier = stripModifier,
                )
            } else if (roamNodeSuggestionActive) {
                val (selectedText, selectedRange) = roamNodeSelection!!
                RoamNodeSuggestionStrip(
                    templates = roamNodeTemplates,
                    selectedText = selectedText,
                    matchesExistingNode = remember(selectedText, autoLinkIndex) {
                        autoLinkIndex?.any { it.titleLower == selectedText.lowercase() } == true
                    },
                    expandedKeys = roamNodeExpandedKeys,
                    onToggleExpand = { key -> roamNodeExpandedKeys = roamNodeExpandedKeys + key },
                    onPick = { template ->
                        roamNodeSelection = null
                        coroutineScope.launch {
                            val result = createOrLinkRoamNode(template, selectedText) ?: return@launch
                            val lo = selectedRange.min.coerceIn(0, textState.text.length)
                            val hi = selectedRange.max.coerceIn(lo, textState.text.length)
                            val linkText = result.formatLink()
                            textState.edit {
                                replace(lo, hi, linkText)
                                selection = TextRange(lo + linkText.length)
                            }
                            val message = when (result) {
                                is RoamNodeResult.Linked -> "Linked to existing roam node: ${result.title}"
                                is RoamNodeResult.Created ->
                                    "A roam node with title \"${result.title}\" has been created."
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = stripModifier,
                )
            }
            attach?.let { AttachOverlays(it, bottomInset = if (suggestionSlotShown) SuggestionSlotHeight else 0.dp) }
        }
        // Hidden while the keyboard is up: the bar and the toolbar both sit at
        // the bottom of this Column, and only one of them should own that row
        // at a time -- the toolbar takes it while typing (see EditNoteScreen).
        KeyboardHiddenLinkedReferencesBar(
            visible = showBacklinks && isRoamFile && !imeVisible,
            linkedCount = linkedCount,
            unlinkedCount = unlinkedCount,
            onClick = onOpenLinkedRefs,
        )
        if (imeVisible) EditorToolbar(
            onWrap = { marker -> textState.applyEdit { wrapSelection(it, marker) } },
            onInsert = { snippet -> textState.applyEdit { insertAtCursor(it, snippet) } },
            onLink = { onLink() },
            onHeading = {
                textState.applyEdit {
                    val edit = LineEditing.insertHeadingStar(it.text, it.selection.start)
                    TextFieldValue(edit.text, TextRange(edit.cursor))
                }
            },
            onIndent = { delta ->
                textState.applyEdit {
                    LineEditing.changeListIndent(it.text, it.selection.start, delta)
                        ?.let { edit -> TextFieldValue(edit.text, TextRange(edit.cursor)) }
                }
            },
            onLinkLongPress = onLinkLongPress,
            onTimestampLongPress = onTimestampLongPress,
            onAttach = attach?.let { a ->
                {
                    keyboard?.hide()
                    a.open()
                }
            },
        )
    }
}

@Composable
private fun StaleFileBanner(onOverwrite: () -> Unit, onReload: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.amberSoft)
            .border(1.dp, c.amber, RoundedCornerShape(11.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "File changed on disk while editing",
            fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Overwrite",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
            color = c.red,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onOverwrite)
                .padding(6.dp),
        )
        Text(
            "Reload",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
            color = c.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onReload)
                .padding(6.dp),
        )
    }
}
