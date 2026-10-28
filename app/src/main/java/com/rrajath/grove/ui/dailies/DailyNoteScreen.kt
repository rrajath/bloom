package com.rrajath.grove.ui.dailies

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.ui.components.ReadEditToggle
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.KeyboardHiddenLinkedReferencesBar
import com.rrajath.grove.ui.components.LinkedReferencesSheet
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.ui.components.InsertTimestampScreen
import com.rrajath.grove.ui.components.rememberImeVisible
import com.rrajath.grove.ui.editor.EditRegion
import com.rrajath.grove.ui.editor.LinkIdChoice
import com.rrajath.grove.ui.editor.LinkIdChoiceDialog
import com.rrajath.grove.ui.editor.LinkPick
import com.rrajath.grove.ui.editor.LinkPickerSheet
import com.rrajath.grove.ui.editor.applyEdit
import com.rrajath.grove.ui.editor.applyToolbarLink
import com.rrajath.grove.ui.editor.insertAtCursor
import com.rrajath.grove.ui.editor.resolveLinkPick
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.ui.editor.EditorBottomMarginLines
import com.rrajath.grove.ui.editor.EditorViewModel
import com.rrajath.grove.ui.editor.OrgSyntaxHighlight
import com.rrajath.grove.ui.editor.WholeFileEditorBody
import com.rrajath.grove.ui.editor.WordAtCursor
import com.rrajath.grove.ui.editor.filterAutoLinkSuggestions
import com.rrajath.grove.ui.editor.isInPreface
import com.rrajath.grove.ui.editor.wordAtCursor
import com.rrajath.grove.ui.screens.FileContent
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.screens.ReadModeBreadcrumb
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.DocumentUiState
import com.rrajath.grove.ui.vault.DocumentViewModel
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.PendingEdit
import com.rrajath.grove.org.LocalDates
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyNoteScreen(
    initialDate: LocalDate,
    onBack: () -> Unit,
    onOpenNote: (NoteRef) -> Unit,
    onOpenOutline: (fileName: String) -> Unit,
    showBacklinks: Boolean,
    /** Settings § Roam Features suggestions ([com.rrajath.grove.settings.GroveSettings.roamSuggestionsActive]):
     *  gates the Roam providers (file/heading link chips, roam-node chips) in Edit mode. The
     *  suggestion strip slot itself is always reserved there while the keyboard is up. */
    roamSuggestionsEnabled: Boolean,
    showPreface: Boolean,
    showPropertyDrawers: Boolean,
    readModeFontSize: FontSizePreference,
    editModeFontSize: FontSizePreference,
    dailiesViewModel: DailiesViewModel = viewModel(factory = DailiesViewModel.Factory),
    documentViewModel: DocumentViewModel = viewModel(factory = DocumentViewModel.Factory),
    editorViewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val haptic = LocalHapticFeedback.current
    val nav by dailiesViewModel.state.collectAsStateWithLifecycle()
    val vaultMissing by dailiesViewModel.vaultMissing.collectAsStateWithLifecycle()
    // The day being shown. Switching days happens in place on this one screen (not a
    // route per date), so the ViewModels, their day index and prefetched neighbours
    // survive every swipe -- see DailiesViewModel.
    var date by rememberSaveable { mutableStateOf(initialDate) }
    var mode by rememberSaveable(date) { mutableStateOf("read") }
    val editState by editorViewModel.state.collectAsStateWithLifecycle()
    val docState by documentViewModel.state.collectAsStateWithLifecycle()
    val linkedReferences by documentViewModel.linkedReferences.collectAsStateWithLifecycle()
    val isRoamFile = (docState as? DocumentUiState.Loaded)?.document?.fileId != null

    // Set when a Today-button tap / prev-next pill / Back needs to navigate away
    // from a dirty Edit-mode buffer; the leave-confirm dialog below routes there.
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var linkedRefsOpen by remember { mutableStateOf(false) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }

    // The Read/Edit toggle never writes, so an unsaved buffer can outlive Edit mode:
    // Read mode renders it from memory (see the pendingEdit effect below), and it
    // stays unsaved until the idle auto-save, the save icon, or the leave dialog.
    val pendingEdit = if (
        editState.dirty && editState.region == EditRegion.WHOLE_FILE && editState.fileName == nav?.fileName
    ) {
        PendingEdit(editState.fileName, 0, editState.buffer, wholeFile = true)
    } else {
        null
    }

    fun runGuarded(action: () -> Unit) {
        if (editState.dirty) {
            pendingAction = action
            confirmLeave = true
        } else {
            action()
        }
    }
    fun leave() = runGuarded(onBack)

    // Never saves, as on a regular note: Read mode shows the unsaved buffer as-is.
    fun switchToRead() {
        mode = "read"
    }
    // Back out of Edit lands in Read, where the user came from (as on a regular note,
    // whose editor sits on top of its Read screen); only Back from Read leaves Dailies.
    fun back() = if (mode == "edit") switchToRead() else leave()

    // Everything here runs synchronously in the tap/swipe handler, so the new day's
    // nav state and (when prefetched) its document land in the very next frame.
    fun goTo(newDate: LocalDate) {
        val edit = editorViewModel.state.value
        if (edit.lastSavedAt != null && edit.fileName.isNotEmpty()) {
            // Saved (e.g. via the leave dialog) in the same frame as this switch, so the
            // lastSavedAt effect below may never see it: record it here instead.
            dailiesViewModel.noteSaved(date, edit.fileName)
        }
        (documentViewModel.state.value as? DocumentUiState.Loaded)?.let {
            dailiesViewModel.rememberDocument(it.fileName, it.document)
        }
        // Past runGuarded, so any unsaved buffer was either saved or discarded on
        // purpose; clear it so the idle auto-save can't resurrect a discarded one.
        editorViewModel.reset()
        date = newDate
        val n = dailiesViewModel.select(newDate) ?: return
        if (n.exists) dailiesViewModel.cachedDocument(n.fileName)?.let { documentViewModel.show(n.fileName, it) }
    }
    fun navigateDate(newDate: LocalDate) = runGuarded { goTo(newDate) }
    androidx.activity.compose.BackHandler { back() }

    // Shared by the empty-day tap-to-type affordance and the top-right Read/Edit
    // toggle: both are ways into Edit mode, and a brand-new (not-yet-existing)
    // day's buffer must be seeded exactly once, not clobbered on a later toggle.
    fun startEditing(n: DailiesNavState) {
        if (!n.exists && editorViewModel.state.value.fileName != n.fileName) {
            val expanded = dailiesViewModel.expandedHeaderFor(date)
            editorViewModel.loadNewWholeFile(n.fileName, expanded.text, expanded.cursorOffset)
        }
        mode = "edit"
    }

    val latestDate by androidx.compose.runtime.rememberUpdatedState(date)
    // Every resume (first open, and coming back from a linked note or another app)
    // shows the current day from the in-memory index at once, then re-lists the vault
    // in the background in case a sync added or removed days meanwhile.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        dailiesViewModel.select(latestDate)
        dailiesViewModel.refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(nav?.fileName, nav?.exists) {
        val n = nav ?: return@LaunchedEffect
        if (n.exists) {
            // Render the already-parsed note (prewarmed from the drawer, or prefetched)
            // at once; load() then re-checks the file and only swaps in a newer parse.
            if ((documentViewModel.state.value as? DocumentUiState.Loaded)?.fileName != n.fileName) {
                dailiesViewModel.cachedDocument(n.fileName)?.let { documentViewModel.show(n.fileName, it) }
            }
            documentViewModel.load(n.fileName)
        }
    }
    // Backlinks needs the document's real fileId/title, so it can only
    // fire once the document has actually loaded -- not eagerly alongside the
    // load() call above (which would race with a null fileId/empty title).
    // Keyed on (fileName, fileId, title), not the document: every save reloads a new
    // parse, but backlinks come from other files, so only an :ID: or title change can
    // move them. No file :ID: means the bar is hidden, so no scan at all.
    val loadedDaily = docState as? DocumentUiState.Loaded
    val dailyFileId = loadedDaily?.document?.fileId
    val dailyTitle = loadedDaily?.let { loaded ->
        loaded.document.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }
            ?.second ?: loaded.fileName.removeSuffix(".org")
    }
    LaunchedEffect(loadedDaily?.fileName, dailyFileId, dailyTitle) {
        val fileName = loadedDaily?.fileName ?: return@LaunchedEffect
        if (dailyFileId != null && dailyTitle != null) {
            documentViewModel.loadLinkedReferences(fileName, com.rrajath.grove.org.INTRO_LINE_INDEX, dailyFileId, dailyTitle)
        } else {
            documentViewModel.clearLinkedReferences()
        }
    }
    // Read mode over an unsaved buffer: render the buffer, not the file (which may
    // not even exist yet for a new day), and let Read-mode mutations such as a
    // checkbox tap fold back into that buffer instead of writing to disk. Keyed on
    // the buffer only in Read mode, so typing in Edit mode doesn't restart it.
    LaunchedEffect(if (mode == "read") pendingEdit else null, mode) {
        documentViewModel.setPendingEdit(
            pendingEdit,
            onBufferChanged = editorViewModel::onBufferChangedExternally,
            onPersisted = editorViewModel::onBufferPersistedElsewhere,
        )
        if (mode == "read" && pendingEdit != null) {
            val doc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.rrajath.grove.org.OrgParser.parse(pendingEdit.text, editState.keywords)
            }
            documentViewModel.show(pendingEdit.fileName, doc)
        }
    }
    // Refresh both the date-navigation state (so nav.exists stops being stale
    // for a brand-new file) and the Read-mode document after every save.
    LaunchedEffect(editState.lastSavedAt) {
        if (editState.lastSavedAt != null && editState.fileName.isNotEmpty()) {
            dailiesViewModel.noteSaved(date, editState.fileName)
            documentViewModel.load(editState.fileName)
        }
    }

    Scaffold(
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GroveTopBar(
                leading = {
                    IconGlyph("←", onClick = ::back)
                    if ((mode == "edit" && (editState.dirty || editState.lastSavedAt != null)) ||
                        (mode == "read" && pendingEdit != null)
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.Save,
                            contentDescription = if (editState.dirty) "Unsaved changes, tap to save" else "Saved",
                            tint = if (editState.dirty) c.green else c.ink3,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { if (editState.dirty) editorViewModel.save() }
                                .padding(6.dp),
                        )
                    }
                },
                title = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            LocalDates.format(date, "EEEMMMd", "EEE, MMM d"),
                            fontFamily = PlexSans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            fontSize = 17.sp, color = c.ink,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                subtitle = {
                    nav?.let { n ->
                        ReadModeBreadcrumb(
                            fileName = n.fileName,
                            path = emptyList(),
                            onOpenBreadcrumb = { onOpenOutline(n.fileName) },
                        )
                    }
                },
                actions = {
                    // Always visible, regardless of isToday: the empty-day hint text
                    // ("Long-press the calendar icon...") and the docs both assume this
                    // button is always present, including on today's own empty day (the
                    // drawer's default entry point). A tap while already on today is a
                    // harmless same-date re-navigation, not worth special-casing.
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(
                                onClick = { navigateDate(LocalDate.now()) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    datePickerOpen = true
                                },
                            )
                            .padding(10.dp)
                            .testTag("dailies_today_button"),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        // Accent while viewing any other day, as the cue that a tap jumps
                        // back to today; neutral on today itself (replaces a TODAY pill).
                        val todayTint = if (date == LocalDate.now()) c.ink2 else c.accent
                        androidx.compose.material3.Icon(
                            Icons.Filled.CalendarToday,
                            contentDescription = "Today",
                            tint = todayTint,
                            modifier = Modifier.size(24.dp),
                        )
                        // Mirrors the day-number overlay real calendar-app icons use, so the
                        // glyph reads as "jump to today" rather than a generic date picker.
                        Text(
                            LocalDate.now().dayOfMonth.toString(),
                            fontFamily = PlexSans, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 9.sp, color = todayTint,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                    ReadEditToggle(
                        isEditing = mode == "edit",
                        onToggle = { if (mode == "edit") switchToRead() else nav?.let { startEditing(it) } },
                    )
                },
            )
        },
        bottomBar = {
            val imeVisible by rememberImeVisible()
            KeyboardHiddenLinkedReferencesBar(
                visible = showBacklinks && isRoamFile && !imeVisible,
                linkedCount = linkedReferences.linkedCount,
                unlinkedCount = linkedReferences.unlinkedCount,
                onClick = { linkedRefsOpen = true },
            )
        },
    ) { padding ->
        val contentImeVisible by rememberImeVisible()
        // The editor's suggestion strip docks at the bottom of this Box, right where the
        // date pills float; with the keyboard down (a selection's roam-node chips) the
        // pills would cover it.
        var editorStripShown by remember { mutableStateOf(false) }
        val n = nav
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                // On-device swipe-gesture risk checklist (no automated test can cover these --
                // see DailySwipeGesture.kt's KDoc for the authoritative, expanded list):
                // 1. A rightward swipe starting at the screen's left edge can be intercepted by
                //    the system's edge-swipe-back gesture (gesture nav mode) instead of reaching
                //    this handler -- there's no reliable way to exclude just that edge across the
                //    full screen height with this implementation.
                // 2. Horizontal-scrolling children (org tables in Read mode, or any wide content
                //    with its own horizontal scroll) claim the gesture themselves and swallow it.
                // 3. EditorToolbar (if present) sits inside this same swipeable content Box, so a
                //    finger dragging across it can trigger day-navigation instead of a toolbar
                //    action.
                // 4. Swipes silently no-op while nav == null, i.e. until the first listing of
                //    the dailies folder lands (DailiesViewModel.refresh) on opening the screen.
                .pointerInput(nav?.date) {
                    var totalDrag = 0f
                    val velocityTracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f; velocityTracker.resetTracking() },
                        onHorizontalDrag = { change, dragAmount ->
                            totalDrag += dragAmount
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            // No explicit change.consume() here: Compose Foundation's
                            // detectHorizontalDragGestures already consumes the gesture
                            // internally once it crosses touch-slop, independent of any
                            // distance threshold of ours. This callback only needs to
                            // accumulate distance/velocity for onDragEnd's
                            // isDeliberateSwipe() check below.
                        },
                        onDragEnd = {
                            val navState = nav ?: return@detectHorizontalDragGestures
                            val velocity = velocityTracker.calculateVelocity().x
                            when (isDeliberateSwipe(totalDrag, velocity, size.width.toFloat())) {
                                SwipeDirection.PREVIOUS -> navigateDate(navState.previousDate)
                                SwipeDirection.NEXT -> navigateDate(navState.nextDate)
                                null -> {}
                            }
                        },
                    )
                },
        ) {
            // Keyed on the day so per-day UI state (read-mode scroll position, the edit
            // field and its load bookkeeping) starts fresh on every switch instead of
            // carrying over from the previous day.
            androidx.compose.runtime.key(n?.date) { when {
                n == null -> {
                    if (vaultMissing) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("No sync folder configured", fontFamily = PlexSans, color = c.ink2)
                        }
                    }
                    // else: still loading -- render nothing, a brief transient state.
                }
                !n.exists && mode == "read" && pendingEdit == null -> DailyEmptyState(
                    fileName = n.fileName,
                    onStartTyping = { startEditing(n) },
                )
                mode == "read" -> {
                    // Still the previous day's document: render nothing for the frame or two
                    // until this day's arrives, rather than the wrong day's content.
                    when (val s = docState.takeUnless { it is DocumentUiState.Loaded && it.fileName != n.fileName }) {
                        is DocumentUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(s.message, fontFamily = PlexSans, color = c.ink2)
                        }
                        is DocumentUiState.Loaded -> {
                            ContentFontScale(readModeFontSize) {
                                FileContent(
                                    doc = s.document,
                                    fileName = n.fileName,
                                    listState = androidx.compose.foundation.lazy.rememberLazyListState(),
                                    showPreface = showPreface,
                                    showPropertyDrawers = showPropertyDrawers,
                                    favorites = emptyList(),
                                    onEdit = { startEditing(n) },
                                    onOpenLink = { target -> documentViewModel.openOrgLink(target, n.fileName, onOpenNote, onOpenOutline) },
                                    onOpenDrawer = { _, _ -> },
                                    onOpenBlock = {},
                                    onOpenPreface = {},
                                    onOpenFileProperties = {},
                                    onToggleCheckbox = { line, longPress ->
                                        if (longPress) documentViewModel.toggleChecklistProgress(line)
                                        else documentViewModel.toggleChecklistDone(line)
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // Fallback, as on ReadFileScreen: FileContent handles a
                                        // double-tap over a text run itself; this catches one on
                                        // blank space, which is most of a short daily note.
                                        .pointerInput(Unit) {
                                            detectTapGestures(onDoubleTap = { startEditing(n) })
                                        },
                                )
                            }
                        }
                        else -> {}
                    }
                }
                else -> {
                    val textState = rememberTextFieldState()
                    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
                    val linkPicker by editorViewModel.linkPicker.collectAsStateWithLifecycle()
                    // Selection captured when the link picker opens, so the pick
                    // replaces it (and uses it as the description) on return.
                    var pendingLinkSel by remember { mutableStateOf<TextRange?>(null) }
                    var pendingLinkDesc by remember { mutableStateOf<String?>(null) }
                    var linkIdChoice by remember { mutableStateOf<LinkIdChoice?>(null) }
                    var timestampPickerOpen by remember { mutableStateOf(false) }
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
                    var fieldLoaded by remember(n.fileName) { mutableStateOf(false) }
                    var echoToSkip by remember { mutableStateOf<String?>(null) }
                    val focusRequester = remember { FocusRequester() }
                    val scrollState = rememberScrollState()
                    val highlight = remember(c, editState.keywords) {
                        OrgSyntaxHighlight(c, editState.keywords, trailingBlankLines = EditorBottomMarginLines)
                    }
                    // Link suggestions while typing: same mechanism as EditNoteScreen /
                    // EditRegionScreen -- an index loaded once, and a trigger word
                    // recomputed on every text/selection change.
                    val autoLinkIndex by editorViewModel.autoLinkIndex.collectAsStateWithLifecycle()
                    val roamNodeTemplates by editorViewModel.roamNodeSuggestionTemplates.collectAsStateWithLifecycle()
                    // From the loaded buffer, not docState: a brand-new day has no parsed
                    // document yet, but its header template usually seeds a file-level :ID:.
                    // Parsed once per load, not per keystroke (the ID doesn't change mid-edit).
                    val bufferIsRoamFile = remember(editState.fileName, editState.loading) {
                        !editState.loading &&
                            com.rrajath.grove.org.OrgParser.parse(editState.buffer, editState.keywords).fileId != null
                    }
                    var autoLinkTrigger by remember { mutableStateOf<WordAtCursor?>(null) }
                    var expandedChipKeys by remember(autoLinkTrigger?.range) { mutableStateOf(emptySet<String>()) }
                    val autoLinkSuggestions = remember(autoLinkTrigger?.text, autoLinkIndex) {
                        val idx = autoLinkIndex
                        val word = autoLinkTrigger?.text
                        if (idx == null || word == null) emptyList() else filterAutoLinkSuggestions(idx, word)
                    }
                    LaunchedEffect(roamSuggestionsEnabled) {
                        if (!roamSuggestionsEnabled) {
                            autoLinkTrigger = null
                            return@LaunchedEffect
                        }
                        editorViewModel.loadAutoLinkIndex()
                        snapshotFlow { textState.text.toString() to textState.selection }.collect { (text, selection) ->
                            autoLinkTrigger = wordAtCursor(text, selection)?.takeIf { it.text.length >= 3 && !isInPreface(text, selection.start) }
                        }
                    }

                    LaunchedEffect(n.fileName, n.exists) {
                        // A file that already exists but is being opened straight
                        // into Edit (toggle tapped from Read) loads normally; a
                        // brand-new file was already seeded by startEditing()
                        // above and must not be clobbered by a fresh load here.
                        if (n.exists && editorViewModel.state.value.fileName != n.fileName) {
                            editorViewModel.loadRegion(n.fileName, null, EditRegion.WHOLE_FILE)
                        }
                    }
                    LaunchedEffect(editState.loading, editState.bufferRevision) {
                        if (!editState.loading && editState.error == null && editState.fileName == n.fileName) {
                            val cursor = if (!fieldLoaded) (editState.cursor ?: editState.buffer.length) else textState.selection.start
                            echoToSkip = editState.buffer
                            textState.edit {
                                replace(0, length, editState.buffer)
                                selection = TextRange(cursor.coerceIn(0, editState.buffer.length))
                            }
                            fieldLoaded = true
                        }
                    }
                    LaunchedEffect(Unit) {
                        snapshotFlow { textState.text.toString() }.collect { text ->
                            if (!fieldLoaded) return@collect
                            if (text == echoToSkip) { echoToSkip = null; return@collect }
                            editorViewModel.onBufferChange(text)
                        }
                    }

                    WholeFileEditorBody(
                        state = editState,
                        textState = textState,
                        scrollState = scrollState,
                        highlight = highlight,
                        focusRequester = focusRequester,
                        autoLinkSuggestions = autoLinkSuggestions,
                        expandedChipKeys = expandedChipKeys,
                        autoLinkTriggerRange = autoLinkTrigger?.range,
                        onToggleExpandChip = { key -> expandedChipKeys = expandedChipKeys + key },
                        onClearAutoLinkTrigger = { autoLinkTrigger = null },
                        onOverwriteStale = { editorViewModel.save(force = true) },
                        onReloadStale = {
                            editorViewModel.dismissStale()
                            editorViewModel.loadRegion(n.fileName, null, EditRegion.WHOLE_FILE)
                        },
                        editModeFontSize = editModeFontSize,
                        showBacklinks = false, // this screen's own bottom bar owns Backlinks (Task 17)
                        isRoamFile = false,
                        linkedCount = 0,
                        unlinkedCount = 0,
                        onOpenLinkedRefs = {},
                        imeVisible = contentImeVisible,
                        onLink = { textState.applyToolbarLink(clipboard) },
                        onLinkLongPress = {
                            val sel = textState.selection
                            pendingLinkSel = sel
                            pendingLinkDesc = if (sel.collapsed) null else textState.text.substring(sel.min, sel.max)
                            editorViewModel.startLinkPicker()
                        },
                        onTimestampLongPress = { timestampPickerOpen = true },
                        modifier = Modifier.fillMaxSize(),
                        bottomClearance = 80.dp,
                        suggestionSlotEnabled = true,
                        roamNodeEnabled = roamSuggestionsEnabled && bufferIsRoamFile,
                        roamNodeTemplates = roamNodeTemplates,
                        autoLinkIndex = autoLinkIndex,
                        createOrLinkRoamNode = editorViewModel::createOrLinkRoamNode,
                        onSuggestionSlotShownChange = { editorStripShown = it },
                        bottomMarginLines = EditorBottomMarginLines,
                    )

                    // Toolbar long-press pickers, as in EditNoteScreen: the file/heading
                    // link picker and the Insert Timestamp screen (active/inactive toggle).
                    linkPicker?.let { r ->
                        fun confirm(doc: com.rrajath.grove.org.OrgDocument, file: String, heading: com.rrajath.grove.org.OrgHeadline?) {
                            editorViewModel.linkPickerCancel()
                            when (val pick = resolveLinkPick(n.fileName, doc, file, heading, pendingLinkDesc)) {
                                is LinkPick.Direct -> spliceLink(pick.link)
                                is LinkPick.AskId -> linkIdChoice = pick.choice
                            }
                        }
                        LinkPickerSheet(
                            state = r,
                            onQueryChange = editorViewModel::linkPickerQueryChange,
                            onPickNotebook = editorViewModel::linkPickerPickNotebook,
                            onDrillInto = editorViewModel::linkPickerDrillInto,
                            onBack = editorViewModel::linkPickerBack,
                            onCancel = editorViewModel::linkPickerCancel,
                            onConfirmFileOrHeading = {
                                val doc = r.pickedDoc
                                val file = r.pickedFile
                                if (doc != null && file != null) {
                                    confirm(doc, file, r.path.lastOrNull()?.let { doc.headlineAtLine(it) })
                                }
                            },
                            onSelectSearchResult = editorViewModel::linkPickerSelectSearchResult,
                            onConfirmHeading = { doc, file, heading -> confirm(doc, file, heading) },
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
                    if (timestampPickerOpen) {
                        InsertTimestampScreen(
                            title = LocalDates.format(date, "EEEMMMd", "EEE, MMM d"),
                            initial = remember {
                                val now = java.time.LocalDateTime.now()
                                OrgTimestamp(now.toLocalDate(), time = now.toLocalTime().withSecond(0).withNano(0), active = false)
                            },
                            onDismiss = { timestampPickerOpen = false },
                            onConfirm = { ts ->
                                textState.applyEdit { insertAtCursor(it, ts.format()) }
                                timestampPickerOpen = false
                            },
                        )
                    }
                }
            } }
            // Scaffold's own content padding already reserves exactly the bottomBar's
            // height (the LinkedReferencesBar, when shown), so the content Box's bottom
            // edge already sits flush above it -- a further bump here would double-count
            // that space and float the pills far higher than intended.
            // Hidden while typing: with the keyboard up the bottom of this Box is the
            // formatting toolbar's row, and the pills would only crowd it.
            if (!contentImeVisible && !editorStripShown) nav?.let { DateNavPills(it, ::navigateDate) }
        }
    }

    if (datePickerOpen) {
        DailyDatePickerSheet(
            initialMonth = date,
            existingDates = nav?.existingDates.orEmpty(),
            onPick = { picked ->
                datePickerOpen = false
                // Picking the date already being viewed just closes the sheet -- a full
                // re-navigation would reset Edit mode back to Read and lose scroll position
                // for no reason.
                if (picked != date) navigateDate(picked)
            },
            onDismiss = { datePickerOpen = false },
        )
    }

    if (confirmLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = c.surface,
            title = {
                Text(
                    "Save changes?",
                    fontFamily = PlexSans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
            },
            text = {
                Text(
                    "This daily note has unsaved changes.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmLeave = false
                    val action = pendingAction.also { pendingAction = null }
                    editorViewModel.save(onSaved = { action?.invoke() })
                }) { Text("Save", color = c.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmLeave = false
                    val action = pendingAction.also { pendingAction = null }
                    action?.invoke()
                }) { Text("Discard", color = c.red) }
            },
        )
    }

    if (linkedRefsOpen) {
        val title = (docState as? DocumentUiState.Loaded)?.document
            ?.preambleKeywords?.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }
            ?.second ?: nav?.fileName?.removeSuffix(".org") ?: ""
        LinkedReferencesSheet(
            title = title,
            result = linkedReferences,
            onOpenReference = { refFileName, lineIndex, id ->
                linkedRefsOpen = false
                onOpenNote(NoteRef(refFileName, lineIndex, id))
            },
            onDismiss = { linkedRefsOpen = false },
        )
    }
}

@Composable
private fun DailyEmptyState(fileName: String, onStartTyping: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onStartTyping)
            .padding(32.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            "Nothing here yet. Tap to start typing and create $fileName. " +
                "Long-press the calendar icon to create a note for another date.",
            fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun shortLabel(date: LocalDate): String =
    LocalDates.format(date, "EEEd", "EEE d")

@Composable
private fun androidx.compose.foundation.layout.BoxScope.DateNavPills(n: DailiesNavState, onNavigate: (LocalDate) -> Unit) {
    Box(
        Modifier.align(androidx.compose.ui.Alignment.BottomStart)
            .padding(start = 16.dp, bottom = 16.dp),
    ) { DateNavPill(label = shortLabel(n.previousDate), leading = true) { onNavigate(n.previousDate) } }
    Box(
        Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 16.dp),
    ) { DateNavPill(label = shortLabel(n.nextDate), leading = false) { onNavigate(n.nextDate) } }
}

@Composable
private fun DateNavPill(label: String, leading: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            // Floats over note text, so it needs a lift to read as a control
            // (DESIGN_SYSTEM.md › Elevation & Shadow).
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            if (leading) "‹ $label" else "$label ›",
            fontFamily = PlexSans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            fontSize = 13.sp, color = c.ink,
        )
    }
}
