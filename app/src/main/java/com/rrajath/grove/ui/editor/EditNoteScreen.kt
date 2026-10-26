package com.rrajath.grove.ui.editor

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.capture.RoamNodeResult
import com.rrajath.grove.capture.formatLink
import com.rrajath.grove.org.LineEditing
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.NewNoteCursor
import com.rrajath.grove.ui.components.EditorFabCompactWidth
import com.rrajath.grove.ui.components.EditorFabMorphMillis
import com.rrajath.grove.ui.components.ReadEditToggle
import com.rrajath.grove.ui.components.EditorMenuFab
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.GroveUndoSnackbar
import com.rrajath.grove.ui.components.InsertTimestampScreen
import com.rrajath.grove.ui.components.ScrollJumpButtons
import com.rrajath.grove.ui.components.rememberImeVisible
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.screens.RefileSheet
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.DocumentUiState
import com.rrajath.grove.ui.vault.DocumentViewModel
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.headlineAtLine
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Raw org editor (design spec §6): syntax-highlighted subtree editing with
 * formatting toolbar and metadata sheet. Leaving with unsaved changes asks
 * to save or discard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditNoteScreen(
    noteRef: NoteRef,
    onBack: () -> Unit,
    onSwitchToRead: () -> Unit,
    /** True when the note was just created (e.g. via the outline + button). */
    isNewNote: Boolean = false,
    /**
     * Absolute doc line of a subheading double-tapped in read mode, if any.
     * The cursor and scroll position land there instead of the default
     * "end of the first line" placement.
     */
    initialCursorLine: Int? = null,
    /** Settings § Notes: font-size lever for the editor field. App chrome is unaffected. */
    editModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /** Settings § Notes: caret placement for a freshly created note (only used when [isNewNote]). */
    newNoteCursor: NewNoteCursor = NewNoteCursor.BODY,
    /** Settings § Roam Features (experimental) suggestions
     *  ([com.rrajath.grove.settings.GroveSettings.roamSuggestionsActive]): gates the Roam
     *  providers (file/heading link chips, roam-node chips) only. The suggestion strip slot
     *  itself is always present while the keyboard is up. */
    roamSuggestionsEnabled: Boolean = false,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
    /**
     * Drives the refile / link-picker sheets (a disk-level move against a loaded
     * on-disk document, separate from this screen's in-memory buffer). Hoisted to
     * a parameter so a test can supply one wired to fakes.
     */
    refileViewModel: DocumentViewModel = viewModel(factory = DocumentViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snack by viewModel.snack.collectAsStateWithLifecycle()
    val textState = rememberTextFieldState()
    var metadataOpen by remember { mutableStateOf(false) }
    var timestampPickerOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDiscardBlankHeading by remember { mutableStateOf(false) }
    var showEmptyHeadingAlert by remember { mutableStateOf(false) }
    var confirmRefile by remember { mutableStateOf(false) }
    // Timestamp of the most recent save (auto or manual), shown as a tappable
    // save (floppy) icon in the top bar: green + tap-to-save-now while dirty,
    // grey + tap-for-last-saved-toast once clean. Tracked in the ViewModel
    // (state.lastSavedAt) since it now also owns the idle auto-save timer.
    val lastAutoSavedAt = state.lastSavedAt?.toLocalTime()
    val focusRequester = remember { FocusRequester() }
    // False until the note has been loaded into the text field: the field's
    // pre-load contents are not the user's edits and must not be reported.
    var fieldLoaded by remember { mutableStateOf(false) }
    // Text last written into the field programmatically (initial load, metadata
    // sheet rewrite). The snapshotFlow below echoes every write straight back;
    // that echo must not be reported as a user edit, which would wrongly mark a
    // freshly opened note dirty. Cleared once consumed, so a later real edit
    // that happens to restore the same text is still reported.
    var echoToSkip by remember { mutableStateOf<String?>(null) }

    /** Replace the field's contents without it counting as a user edit. */
    fun setText(text: String, cursor: TextRange) {
        echoToSkip = text
        textState.edit {
            replace(0, length, text)
            selection = cursor
        }
    }

    // --- toolbar link picker (long-press the [[]] button) ---
    // The selection at the moment the picker was opened: its text is the link
    // description, and the picked link is spliced back over this range once the
    // sheet / dialog closes (by which time the field's own selection has
    // collapsed).
    var pendingLinkSel by remember { mutableStateOf<TextRange?>(null) }
    var pendingLinkDesc by remember { mutableStateOf<String?>(null) }
    // Set when a picked file or heading carries an ID and the user must choose
    // between the resilient id link and the plain one. Holds both, pre-formatted.
    var linkIdChoice by remember { mutableStateOf<LinkIdChoice?>(null) }
    val linkPicker by viewModel.linkPicker.collectAsStateWithLifecycle()
    val autoLinkIndex by viewModel.autoLinkIndex.collectAsStateWithLifecycle()
    // The word currently being typed at the cursor, once it reaches the
    // 3-character trigger threshold; null hides the suggestion strip. Recomputed
    // on every text/selection change, so moving the cursor away from the word
    // (tapping elsewhere in the field) dismisses the strip on its own.
    var autoLinkTrigger by remember { mutableStateOf<WordAtCursor?>(null) }
    // Chips currently showing their full (un-ellipsised) title. Keyed by
    // suggestion, so it naturally clears when the typed word changes.
    var expandedChipKeys by remember(autoLinkTrigger?.range) { mutableStateOf(emptySet<String>()) }
    val autoLinkSuggestions = remember(autoLinkTrigger?.text, autoLinkIndex) {
        val idx = autoLinkIndex
        val word = autoLinkTrigger?.text
        if (idx == null || word == null) emptyList() else filterAutoLinkSuggestions(idx, word)
    }

    // Selection-triggered roam-node suggestions: parallel to the typing-triggered
    // auto-link strip above, but for a non-collapsed selection instead of a word
    // at a collapsed cursor -- the two never fire at once (see wordAtCursor's own
    // collapsed-selection check), so they share the same docked strip location.
    val coroutineScope = rememberCoroutineScope()
    val roamNodeTemplates by viewModel.roamNodeSuggestionTemplates.collectAsStateWithLifecycle()
    var roamNodeSelection by remember { mutableStateOf<Pair<String, TextRange>?>(null) }
    var roamNodeExpandedKeys by remember(roamNodeSelection?.second) { mutableStateOf(emptySet<String>()) }
    val roamNodeSuggestionActive =
        roamNodeSelection != null && state.fileOrgId != null && roamNodeTemplates.isNotEmpty()

    fun captureLinkSelection() {
        val sel = textState.selection
        pendingLinkSel = sel
        pendingLinkDesc = if (sel.collapsed) null else textState.text.substring(sel.min, sel.max)
    }

    fun spliceLink(linkText: String) {
        val sel = pendingLinkSel ?: textState.selection
        val lo = sel.min.coerceIn(0, textState.text.length)
        val hi = sel.max.coerceIn(lo, textState.text.length)
        textState.edit {
            replace(lo, hi, linkText)
            selection = TextRange(lo + linkText.length)
        }
        pendingLinkSel = null
        pendingLinkDesc = null
    }

    /** Turn the picked file (top level) or heading into a link, asking about an ID first when there is one. */
    fun confirmLinkPick(doc: OrgDocument, file: String, heading: OrgHeadline?) {
        viewModel.linkPickerCancel()
        when (val pick = resolveLinkPick(state.fileName, doc, file, heading, pendingLinkDesc)) {
            is LinkPick.Direct -> spliceLink(pick.link)
            is LinkPick.AskId -> linkIdChoice = pick.choice
        }
    }

    /** Validate heading before saving; shows alert if blank, otherwise saves. */
    fun trySave(onSaved: () -> Unit) {
        if (viewModel.isCurrentHeadingBlank()) {
            showEmptyHeadingAlert = true
        } else {
            viewModel.save(onSaved = onSaved)
        }
    }

    fun leave() {
        when {
            // A blank heading can't be saved, so leaving always means discarding
            // the note (heading, any body text, all of it); always confirm
            // first rather than silently dropping whatever was typed.
            isNewNote && viewModel.isCurrentHeadingBlank() -> confirmDiscardBlankHeading = true
            state.dirty -> confirmLeave = true
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler { leave() }

    // Refile is a disk-level move-between-files operation; the editor only holds an in-memory
    // buffer until Save. A dedicated DocumentViewModel drives the refile picker itself (that
    // state machine works against a loaded on-disk document, not this screen's buffer) once the
    // buffer has been flushed to disk.
    val refileDocState by refileViewModel.state.collectAsStateWithLifecycle()
    val refileState by refileViewModel.refile.collectAsStateWithLifecycle()
    val refileSnack by refileViewModel.snack.collectAsStateWithLifecycle()
    var refileTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(refileDocState, refileTarget) {
        val target = refileTarget ?: return@LaunchedEffect
        val loaded = refileDocState as? DocumentUiState.Loaded ?: return@LaunchedEffect
        if (loaded.fileName != target.first) return@LaunchedEffect
        refileTarget = null
        loaded.document.headlineAtLine(target.second)?.let(refileViewModel::startRefile)
    }
    // Set on a completed move (refileConfirm/refileToArchive/refileToLastUsed), not a plain
    // cancel/back-out. The move itself (file write + the "Refiled to X" snack) runs async in
    // refileViewModel.viewModelScope *after* `refile` is already nulled out to close the sheet,
    // so this can't just watch `refile`: leaving immediately would pop this screen's back-stack
    // entry — and with it refileViewModel's scope — out from under that still-in-flight
    // coroutine. Instead it waits for the snack this move ends with to actually appear and then
    // clear, which both guarantees the write has landed and gives the user the undo window this
    // screen closing shouldn't cut short. A tap on Undo restores this note at its original
    // fileName/lineIndex, which the buffer this screen is editing still targets, so it clears
    // the flag instead of leaving.
    var refileAwaitingLeave by remember { mutableStateOf(false) }
    var refileSnackSeen by remember { mutableStateOf(false) }
    LaunchedEffect(refileSnack) {
        if (!refileAwaitingLeave) return@LaunchedEffect
        if (refileSnack != null) {
            refileSnackSeen = true
        } else if (refileSnackSeen) {
            refileAwaitingLeave = false
            refileSnackSeen = false
            leave()
        }
    }

    val scrollState = rememberScrollState()
    val editorLineHeightPx = with(LocalDensity.current) { (13.5f * 1.85f).sp.toPx() }

    // Whether the view model already holds unsaved changes for this note, i.e. we
    // are coming back from read mode through the Read/Edit toggle — which never
    // leaves this back-stack entry, so the buffer survived. Re-reading the file
    // would drop it. Read during composition so both effects below see it.
    val resumedDirty = remember(noteRef) {
        val s = viewModel.state.value
        s.dirty && s.region == null && s.fileName == noteRef.fileName
    }

    LaunchedEffect(noteRef) { if (!resumedDirty) viewModel.load(noteRef) }
    LaunchedEffect(Unit) { viewModel.loadAutoLinkIndex() }
    LaunchedEffect(state.loading) {
        if (!state.loading && state.error == null) {
            if (isNewNote && !resumedDirty) {
                // FAB-created heading has no body yet (just the "* " line, plus
                // an optional :PROPERTIES: drawer): append a blank body line, then
                // park the cursor per Settings § Notes — on that body line (the
                // default, keyboard ready for content) or right after the "* " to
                // type the title first.
                val bodyText = state.buffer + "\n"
                val edit = LineEditing.newNoteCaret(
                    bodyText,
                    heading = newNoteCursor == NewNoteCursor.HEADING,
                )
                setText(edit.text, TextRange(edit.cursor))
            } else {
                val targetOffset = charOffsetForLine(state.buffer, state.lineIndex, initialCursorLine)
                val cursor = targetOffset ?: state.buffer.length.coerceAtMost(
                    state.buffer.indexOf('\n').let { if (it == -1) state.buffer.length else it },
                )
                setText(state.buffer, TextRange(cursor))
            }
            fieldLoaded = true
            if (isNewNote && !resumedDirty) focusRequester.requestFocus()
            // Scroll the tapped subheading into view. The buffer/cursor were
            // just set above, so scrollState's layout (and thus maxValue) is
            // still stale for this frame; give it two frames to catch up
            // before reading/clamping against it.
            val relativeLine = initialCursorLine?.let { it - state.lineIndex }
            if (relativeLine != null && relativeLine > 0) {
                withFrameNanos {}
                withFrameNanos {}
                val targetPx = ((relativeLine - 2).coerceAtLeast(0) * editorLineHeightPx).toInt()
                scrollState.scrollTo(targetPx.coerceIn(0, scrollState.maxValue))
            }
        }
    }
    // Metadata-sheet mutations rewrite the buffer outside the text field. Keyed
    // on the view model's explicit revision counter, never on the buffer text:
    // the field reports its edits one frame behind, so a text comparison could
    // see the *previous* buffer alongside the newest field contents and push
    // the older text back in, eating the characters typed in between.
    LaunchedEffect(state.bufferRevision) {
        if (!fieldLoaded || state.bufferRevision == 0L) return@LaunchedEffect
        val buffer = state.buffer
        if (buffer != textState.text.toString()) {
            setText(buffer, TextRange(textState.selection.start.coerceAtMost(buffer.length)))
        }
    }
    // Report the user's own edits back to the view model. Programmatic writes
    // (setText above) are filtered out, so only real typing marks the note dirty.
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
    // Drives the inline auto-link suggestion strip: recomputed on every text or
    // cursor change so it tracks whatever word is being typed right now.
    LaunchedEffect(roamSuggestionsEnabled) {
        if (!roamSuggestionsEnabled) {
            autoLinkTrigger = null
            roamNodeSelection = null
            return@LaunchedEffect
        }
        snapshotFlow { textState.text.toString() to textState.selection }.collect { (text, selection) ->
            autoLinkTrigger = wordAtCursor(text, selection)?.takeIf { it.text.length >= 3 && !isInPreface(text, selection.start) }
            roamNodeSelection = selection.takeIf { !it.collapsed && !isInPreface(text, it.min) }
                ?.let { sel -> text.substring(sel.min, sel.max) to sel }
                ?.takeIf { (selected, _) -> !selected.contains('\n') }
        }
    }
    val blockTrigger by rememberBlockTrigger(textState)
    val highlight = remember(c, state.keywords) {
        OrgSyntaxHighlight(c, state.keywords, trailingBlankLines = EditorBottomMarginLines)
    }
    // Written from the field's onTextLayout; read only by KeepCursorClearOfBottom's flow.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    KeepCursorClearOfBottom(textState, scrollState, { textLayout }, EditorBottomMarginLines)

    // Idle auto-save (Settings § Notes → Auto-save notes) runs inside
    // EditorViewModel now, so it isn't a per-keystroke Compose effect here.

    // Five lines of editor text (13.5sp font * 1.85 line height), so the jump
    // buttons don't flash on every keystroke as typing nudges the view.
    val scrollButtonThresholdPx = with(LocalDensity.current) { (13.5f * 1.85f * 5).sp.toPx() }

    Scaffold(
        containerColor = c.bg,
        // Hand off all bottom-inset responsibility to the Column below so that
        // navigationBarsPadding + windowInsetsPadding(ime) work without the
        // Scaffold's own bottom insets creating a double-stacking gap.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GroveTopBar(
                leading = {
                    IconGlyph("←", onClick = ::leave)
                    // Shown once the note has either been edited or saved at least
                    // once; a freshly opened, never-touched note shows nothing.
                    if (state.dirty || lastAutoSavedAt != null) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = if (state.dirty) "Unsaved changes, tap to save" else "Saved",
                            // Green means there are unsaved changes and a tap saves
                            // immediately; grey means the buffer matches what's on
                            // disk (and blinks right after a save); a tap then just
                            // reports when that save happened.
                            tint = if (state.dirty) c.green else c.ink3,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .testTag("edit_note_save")
                                .clickable {
                                    if (state.dirty) {
                                        trySave {}
                                    } else {
                                        val message = lastAutoSavedAt?.let {
                                            "The note was last saved at: ${AutoSaveTimestamp.format(it)}"
                                        } ?: "This note hasn't been saved yet"
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(10.dp),
                        )
                    }
                },
                title = {},
                actions = {
                    // Switching to read mode never writes and never validates:
                    // read mode renders this buffer as-is (see PendingEdit). The
                    // note route's own leave path (back from read) is what checks
                    // for a blank heading before the file can actually change.
                    ReadEditToggle(isEditing = true, onToggle = onSwitchToRead)
                },
            )
        },
    ) { padding ->
        // ime.getBottom > 0 tracks the live keyboard height, unlike isImeVisible's
        // visibility flag, which can get stuck true after a gesture-dismiss that
        // leaves the field focused.
        val imeVisible by rememberImeVisible()
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // union (not sum) of nav-bar and ime bottom insets: the ime inset
                // already spans down to the screen edge when the keyboard is up,
                // so adding navigationBarsPadding on top double-counted it and
                // left a gap above the keyboard.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
        ) {
            state.error?.let { error ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error, fontFamily = PlexSans, color = c.ink2)
                }
                return@Column
            }
            if (state.staleFile) {
                StaleFileBanner(
                    onOverwrite = { viewModel.save(force = true) },
                    onReload = { viewModel.dismissStale(); viewModel.load(noteRef) },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The field owns its own vertical scrolling (rather than being
                // wrapped in Modifier.verticalScroll): that is what lets Compose
                // auto-scroll while a selection handle is dragged past the top or
                // bottom edge, and keeps the cursor visible when the keyboard
                // shrinks the viewport.
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
                        ),
                        cursorBrush = SolidColor(c.accent),
                        onTextLayout = { getResult -> textLayout = getResult() },
                        scrollState = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            // Scrolls away like Read mode's top contentPadding, so
                            // scrolled text reaches the top bar (no dead strip).
                            .scrollAwareTopInset(scrollState, 18.dp)
                            // Extra bottom room so the last lines scroll clear of the
                            // floating EditorMenuFab (54dp + 16dp inset) instead of
                            // sitting under it. With the keyboard up the FAB lives in
                            // the suggestion strip, so the room shrinks to exactly the
                            // strip and the text runs right down to its hairline.
                            // Mirrors CaptureEditorScreen's field.
                            .padding(
                                start = 18.dp, end = 18.dp,
                                bottom = if (imeVisible) SuggestionSlotHeight else 80.dp,
                            )
                            .focusRequester(focusRequester)
                            .testTag("edit_note_field"),
                    )
                }
                // Bottom bar: the scroll-jump buttons, the menu FAB and, while the keyboard
                // is up, the suggestion strip, whose trailing slot the FAB then shrinks into
                // (same place as Capture's Save).
                val scrollButtonsLift by animateDpAsState(
                    // Keyboard down: 10dp above the 54dp FAB. Up: 10dp above the strip,
                    // whose top sits 34dp above this row (it overhangs the 16dp padding).
                    if (imeVisible) SuggestionSlotHeight - 16.dp + 10.dp else 54.dp + 10.dp,
                    tween(EditorFabMorphMillis),
                    label = "scrollButtonsLift",
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) {
                    ScrollJumpButtons(
                        scrollState = scrollState,
                        minScrollDeltaPx = scrollButtonThresholdPx,
                        // 16dp end = the end gutter, matching the Read/Edit toggle in the top bar.
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = scrollButtonsLift),
                    )
                    // Suggestions only while typing: with the keyboard down they'd just cover the note.
                    // The slot is there whenever the keyboard is up, chips or not and whichever
                    // providers are on. It floats over the field's bottom clearance, which is
                    // exactly one strip tall while it shows, so it never covers text. Sits flush on
                    // the formatting toolbar like CaptureEditorScreen's strip: cancels this
                    // row's 16dp bottom padding.
                    if (imeVisible) SuggestionSlot(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(y = 16.dp),
                        // Holds the compact menu FAB's spot; the FAB itself is drawn below.
                        trailing = { Spacer(Modifier.size(width = EditorFabCompactWidth, height = 36.dp)) },
                    ) {
                        val block = blockTrigger
                        if (block != null) {
                            BlockTemplateSuggestionStrip(
                                template = block.template,
                                onPick = { textState.applyBlockTemplate(block) },
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        } else if (autoLinkSuggestions.isNotEmpty()) {
                            AutoLinkSuggestionStrip(
                                suggestions = autoLinkSuggestions,
                                expandedKeys = expandedChipKeys,
                                onToggleExpand = { key -> expandedChipKeys = expandedChipKeys + key },
                                onPick = { suggestion ->
                                    val range = autoLinkTrigger?.range ?: return@AutoLinkSuggestionStrip
                                    val linkText = formatAutoLinkInsertion(suggestion)
                                    textState.edit {
                                        replace(range.start, range.end, linkText)
                                        selection = TextRange(range.start + linkText.length)
                                    }
                                    autoLinkTrigger = null
                                },
                                modifier = Modifier.align(Alignment.CenterStart),
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
                                        val result = viewModel.createOrLinkRoamNode(template, selectedText)
                                        if (result == null) return@launch
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
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                    }
                    // One menu FAB for both states, drawn above the strip: it morphs between
                    // the 54dp FAB (keyboard down) and the compact 44x36dp button centred in
                    // the strip's trailing slot (keyboard up), rather than swapping instantly.
                    // +9dp = the strip's 16dp overhang below this row, less the 7dp that
                    // centres a 36dp button in the 50dp strip.
                    val fabDrop by animateDpAsState(
                        if (imeVisible) 16.dp - (SuggestionSlotHeight - 36.dp) / 2 else 0.dp,
                        tween(EditorFabMorphMillis),
                        label = "fabDrop",
                    )
                    EditorMenuFab(
                        onClick = { metadataOpen = true },
                        compact = imeVisible,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp)
                            .offset(y = fabDrop),
                    )
                }
                GroveUndoSnackbar(
                    snack = snack,
                    onUndo = viewModel::undo,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
                GroveUndoSnackbar(
                    snack = refileSnack,
                    onUndo = {
                        // Restores this note at its original fileName/lineIndex, which the
                        // buffer this screen is editing still targets, so the pending
                        // auto-leave from the move this snack belongs to must not fire.
                        refileAwaitingLeave = false
                        refileSnackSeen = false
                        refileViewModel.undo()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
            if (imeVisible) EditorToolbar(
                onWrap = { marker -> textState.applyEdit { wrapSelection(it, marker) } },
                onInsert = { snippet -> textState.applyEdit { insertAtCursor(it, snippet) } },
                onLink = { textState.applyToolbarLink(clipboard) },
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
                onLinkLongPress = {
                    captureLinkSelection()
                    viewModel.startLinkPicker()
                },
                onTimestampLongPress = { timestampPickerOpen = true },
            )
        }
    }

    linkPicker?.let { r ->
        LinkPickerSheet(
            state = r,
            onQueryChange = viewModel::linkPickerQueryChange,
            onPickNotebook = viewModel::linkPickerPickNotebook,
            onDrillInto = viewModel::linkPickerDrillInto,
            onBack = viewModel::linkPickerBack,
            onCancel = viewModel::linkPickerCancel,
            onConfirmFileOrHeading = {
                val doc = r.pickedDoc
                val file = r.pickedFile
                if (doc != null && file != null) {
                    confirmLinkPick(doc, file, r.path.lastOrNull()?.let { doc.headlineAtLine(it) })
                }
            },
            onSelectSearchResult = viewModel::linkPickerSelectSearchResult,
            onConfirmHeading = { doc, file, heading -> confirmLinkPick(doc, file, heading) },
        )
    }

    linkIdChoice?.let { choice ->
        LinkIdChoiceDialog(
            choice = choice,
            onUseId = { linkIdChoice = null; spliceLink(choice.withId) },
            onUsePlain = { linkIdChoice = null; spliceLink(choice.withPlain) },
            onDismiss = { linkIdChoice = null },
        )
    }

    if (metadataOpen) {
        val headline = remember(state.buffer, state.keywords) { viewModel.currentHeadline }
        MetadataSheet(
            headline = headline,
            keywords = state.keywords,
            allTags = state.allTags,
            onChangeKeyword = viewModel::changeKeyword,
            onSetPriority = viewModel::setPriority,
            onSetTags = viewModel::setTags,
            onSetPlanningDates = viewModel::setPlanningDates,
            onAddNote = viewModel::addNote,
            onGenerateId = viewModel::generateId,
            onRefile = {
                metadataOpen = false
                confirmRefile = true
            },
            onDismiss = { metadataOpen = false },
        )
    }

    if (timestampPickerOpen) {
        val headline = remember(state.buffer, state.keywords) { viewModel.currentHeadline }
        InsertTimestampScreen(
            title = headline?.title.orEmpty(),
            initial = remember {
                val now = LocalDateTime.now()
                OrgTimestamp(now.toLocalDate(), time = now.toLocalTime().withSecond(0).withNano(0), active = false)
            },
            onDismiss = { timestampPickerOpen = false },
            onConfirm = { ts ->
                textState.applyEdit { insertAtCursor(it, ts.format()) }
                timestampPickerOpen = false
            },
        )
    }

    if (confirmRefile) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRefile = false },
            containerColor = c.surface,
            title = {
                Text(
                    "Refile this note?",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
            },
            text = {
                Text(
                    "The note will be saved in its current state and refiled to the location you choose.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRefile = false
                    trySave {
                        val s = viewModel.state.value
                        refileTarget = s.fileName to s.lineIndex
                        refileViewModel.load(s.fileName)
                    }
                }) { Text("Continue", color = c.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRefile = false }) {
                    Text("Cancel", color = c.ink2)
                }
            },
        )
    }

    refileState?.let { refile ->
        val doc = (refileDocState as? DocumentUiState.Loaded)?.document
        RefileSheet(
            state = refile,
            currentFileName = state.fileName,
            currentDoc = doc,
            onPickNotebook = refileViewModel::refilePickNotebook,
            onDrillInto = refileViewModel::refileDrillInto,
            onBack = refileViewModel::refileBack,
            onCancel = refileViewModel::refileCancel,
            onConfirm = { refileAwaitingLeave = true; refileViewModel.refileConfirm() },
            onArchive = { refileAwaitingLeave = true; refileViewModel.refileToArchive() },
            onPickLastUsed = { refileAwaitingLeave = true; refileViewModel.refileToLastUsed() },
            onSelectSearchResult = refileViewModel::refileSelectSearchResult,
        )
    }

    if (confirmLeave) {
        UnsavedNoteDialog(
            onSave = {
                confirmLeave = false
                trySave(onSaved = onBack)
            },
            onDiscard = {
                confirmLeave = false
                onBack()
            },
            onDismiss = { confirmLeave = false },
        )
    }

    if (confirmDiscardBlankHeading) {
        DiscardBlankHeadingDialog(
            onDiscard = {
                confirmDiscardBlankHeading = false
                viewModel.deleteSubtree(onDeleted = onBack)
            },
            onKeepEditing = { confirmDiscardBlankHeading = false },
        )
    }

    if (showEmptyHeadingAlert) {
        EmptyHeadingAlertDialog(onDismiss = { showEmptyHeadingAlert = false })
    }
}

/**
 * Char offset of the start of absolute doc line [targetLineIndex] within
 * [buffer], whose own line 0 is [bufferStartLine] in the full document (see
 * `OrgMutations.subtreeText`). Returns null for no target, the buffer's own
 * first line (the root heading), or an out-of-range line — all of which fall
 * back to the caller's default cursor placement.
 */
private fun charOffsetForLine(buffer: String, bufferStartLine: Int, targetLineIndex: Int?): Int? {
    val relativeLine = (targetLineIndex ?: return null) - bufferStartLine
    if (relativeLine <= 0) return null
    val lines = buffer.split('\n')
    if (relativeLine >= lines.size) return null
    var offset = 0
    for (i in 0 until relativeLine) offset += lines[i].length + 1
    return offset
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

/**
 * "This note needs a heading before it can be saved." Shown by the editor on back
 * for a just-created, never-titled note, and by the note route's own leave path
 * when read mode is left in the same state.
 */
@Composable
fun DiscardBlankHeadingDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val c = MaterialTheme.grove
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onKeepEditing,
        containerColor = c.surface,
        title = {
            Text(
                "Discard note?",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Text(
                "This note needs a heading before it can be saved. Leaving now will discard it, including any text you've added.",
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDiscard) {
                Text("Discard", color = c.red)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onKeepEditing) {
                Text("Keep Editing", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/**
 * "Add a heading." Shown wherever an explicit save (the save icon, or Save from
 * [UnsavedNoteDialog]) is blocked because the heading is still blank.
 */
@Composable
fun EmptyHeadingAlertDialog(onDismiss: () -> Unit) {
    val c = MaterialTheme.grove
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                "Add a heading",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Text(
                "Please give this note a heading before saving.",
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("OK", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/**
 * "Save changes?" for a note about to be left with an unsaved buffer. Shown by the
 * editor on back, and by the note route when read mode (which renders that same
 * unsaved buffer) is left for good.
 */
@Composable
fun UnsavedNoteDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    message: String = "This note has unsaved changes.",
) {
    val c = MaterialTheme.grove
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
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
                message,
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSave) {
                Text("Save", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDiscard) {
                Text("Discard", color = c.red)
            }
        },
    )
}
