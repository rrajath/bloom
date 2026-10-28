package com.rrajath.grove.ui.capture

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.capture.CaptureContext
import com.rrajath.grove.capture.CaptureInserter
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.FilenamePattern
import com.rrajath.grove.capture.ExpandedTemplate
import com.rrajath.grove.capture.PlaceholderExpander
import com.rrajath.grove.capture.RoamFileId
import com.rrajath.grove.capture.RoamNodeResult
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.formatLink
import com.rrajath.grove.capture.templateSlug
import com.rrajath.grove.org.LineEditing
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.ui.components.ReadEditToggle
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.components.annotateOrgInline
import com.rrajath.grove.ui.components.rememberImeVisible
import com.rrajath.grove.ui.screens.BodyBlocks
import com.rrajath.grove.ui.editor.AutoLinkSuggestionStrip
import com.rrajath.grove.ui.editor.AutoSaveTimestamp
import com.rrajath.grove.ui.editor.BlockTemplateSuggestionStrip
import com.rrajath.grove.ui.editor.applyBlockTemplate
import com.rrajath.grove.ui.editor.rememberBlockTrigger
import com.rrajath.grove.ui.editor.EditorBottomMarginLines
import com.rrajath.grove.ui.editor.EditorToolbar
import com.rrajath.grove.ui.editor.KeepCursorClearOfBottom
import com.rrajath.grove.ui.editor.MetadataSheet
import com.rrajath.grove.ui.editor.RoamNodeSuggestionStrip
import com.rrajath.grove.ui.editor.SuggestionSlot
import com.rrajath.grove.ui.editor.SuggestionSlotHeight
import com.rrajath.grove.ui.editor.WordAtCursor
import com.rrajath.grove.ui.editor.scrollAwareTopInset
import com.rrajath.grove.ui.editor.filterAutoLinkSuggestions
import com.rrajath.grove.ui.editor.formatAutoLinkInsertion
import com.rrajath.grove.ui.editor.isInPreface
import com.rrajath.grove.ui.editor.wordAtCursor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.rrajath.grove.ui.editor.orgInputTransformation
import com.rrajath.grove.ui.editor.OrgSyntaxHighlight
import com.rrajath.grove.ui.editor.applyEdit
import com.rrajath.grove.ui.editor.applyToolbarLink
import com.rrajath.grove.ui.editor.insertAtCursor
import com.rrajath.grove.ui.editor.wrapSelection
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.ui.theme.ContentFontScale
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.PlexSerif
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.priorityColor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Whether a Roam capture is a brand-new node or a continuation of a file that
 * already exists (e.g. a second capture into today's daily note). Resolved
 * once from the template's fresh expansion, before the user types anything,
 * and held fixed for the rest of the capture session -- it must not flip
 * back and forth as a title-driven filename changes while typing.
 */
private sealed class RoamAppendState {
    data object Checking : RoamAppendState()
    data object New : RoamAppendState()
    data class ExistingFile(val path: String) : RoamAppendState()
}

/**
 * Capture editor (design spec §8): prompts for `%^{…}` values, then a
 * pre-expanded mono editor with the cursor at `%cursor`. Save inserts into
 * the template's target file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureEditorScreen(
    templateId: String,
    onClose: () -> Unit,
    /**
     * Fired once the draft is persisted. [savedRoamFilePath] is the vault path
     * of the file just written, but only for a [TemplateKind.ROAM_NODE]
     * capture (null for a PLAIN entry) -- that's what the caller uses to
     * offer a "Visit file" snackbar action; a PLAIN capture inserts into an
     * existing notebook the user is presumably already looking at, so no
     * snackbar is warranted there.
     */
    onSaved: (savedRoamFilePath: String?) -> Unit,
    /** Settings § Notes: font-size lever for the editor field. App chrome is unaffected. */
    editModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /**
     * Settings § Roam Features (experimental) suggestions
     * ([com.rrajath.grove.settings.GroveSettings.roamSuggestionsActive]): gates the Roam
     * providers (file/heading link chips, roam-node chips), which only ever apply to a
     * [TemplateKind.ROAM_NODE] capture. The suggestion strip slot itself is present for
     * every capture kind while the keyboard is up.
     */
    roamSuggestionsEnabled: Boolean = false,
    viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    // Deep links (launcher shortcuts, widget, or a hand-typed bloom://capture/x
    // URL) pass the template's opaque id, but a manually-typed link is more
    // likely to use the visible name (e.g. "TODO") or the dash-separated slug
    // shown on the template editor's capture-link section, so fall back to a
    // case-insensitive name match, then a slug match, before giving up.
    val template = templates.firstOrNull { it.id == templateId }
        ?: templates.firstOrNull { it.name.equals(templateId, ignoreCase = true) }
        ?: templates.firstOrNull { templateSlug(it.name) == templateId.lowercase() }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
            as com.rrajath.grove.GroveApplication
    val keywords by app.keywords.collectAsStateWithLifecycle()

    LaunchedEffect(saveState) {
        val saved = saveState as? SaveState.Saved
        if (saved != null) {
            viewModel.resetSaveState()
            onSaved(saved.filePath.takeIf { template?.kind == TemplateKind.ROAM_NODE })
        }
    }

    // Only once templates have actually loaded (they start as an empty list
    // while the DataStore read is in flight) is a still-missing match a real
    // "no such template" rather than a load-in-progress false negative.
    val notFoundContext = LocalContext.current
    LaunchedEffect(template, templates) {
        if (template == null && templates.isNotEmpty()) {
            Toast.makeText(
                notFoundContext,
                "Couldn't find a capture template named \"$templateId\".",
                Toast.LENGTH_LONG,
            ).show()
            onClose()
        }
    }

    if (template == null) return

    val now = remember { LocalDateTime.now() }
    val prompts = remember(template) {
        val body = if (template.kind == TemplateKind.ROAM_NODE) template.newFileTemplate else template.template
        PlaceholderExpander.prompts(body)
    }
    var promptValues by remember(template) { mutableStateOf<Map<String, String>?>(null) }

    if (prompts.isNotEmpty() && promptValues == null) {
        PromptDialog(
            prompts = prompts,
            onCancel = onClose,
            onDone = { promptValues = it },
        )
        return
    }

    val context = remember(template, promptValues) {
        val share = app.pendingShare.value
        val body = if (template.kind == TemplateKind.ROAM_NODE) template.newFileTemplate else template.template
        // Only read the clipboard when the template actually uses %clipboard.
        // Android 13+ shows a system toast on every clipboard read, so reading
        // unconditionally would confuse users whose templates don't need it.
        val clipboardText =
            if (body.contains("%clipboard")) {
                // getClipEntry() has no real suspension point on Android (it's a
                // synchronous Binder call under the hood), so runBlocking here
                // just reads it inline instead of introducing an async gap that
                // could race with textState below reading a stale, clipboard-less
                // CaptureContext.
                runBlocking { clipboard.getClipEntry() }?.clipData?.let { data ->
                    if (data.itemCount > 0) data.getItemAt(0)?.text?.toString() else null
                } ?: ""
            } else {
                ""
            }
        CaptureContext(
            now = now,
            clipboard = clipboardText,
            sharedText = share?.text ?: "",
            sharedUrl = share?.url ?: "",
            promptValues = promptValues ?: emptyMap(),
            // Generated once per capture, not regenerated on every expand() call.
            id = newOrgId(),
        )
    }
    // The share payload is one-shot: consumed by this capture.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { app.pendingShare.value = null }
    }
    // A Roam draft never shows the file-level :PROPERTIES: drawer: the :ID: is
    // added by the view model on save, and any other properties the template's
    // drawer carries are held here and put back on top of the draft on save.
    val roamSplit = remember(template, context) {
        if (template.kind != TemplateKind.ROAM_NODE) return@remember null
        // The title is never prompted for: it expands blank with the
        // cursor on it, and the user types it straight into the draft.
        val full = PlaceholderExpander.expand(template.newFileTemplate, context)
        RoamFileId.splitDrawer(full.text, full.cursorOffset)
    }
    val expanded = remember(template, context) {
        if (roamSplit != null) {
            ExpandedTemplate(roamSplit.second, roamSplit.third)
        } else {
            CaptureInserter.withHeadingStars(
                PlaceholderExpander.expand(template.template, context),
                template.location,
            )
        }
    }

    // An "under heading" template whose first line isn't a heading (e.g. a
    // `- [ ] %?` checklist item under "Shopping List") captures content into
    // that heading's body. The heading shows read-only above the field, and
    // the draft is body text, so the empty-heading guard doesn't apply.
    val bodyOnly = remember(template, expanded) {
        template.kind == TemplateKind.PLAIN && CaptureInserter.isBodyOnly(expanded.text, template.location)
    }
    // An "under heading" target's outline path for the target bar, and whether
    // the heading couldn't be found in the file, so the user is warned before
    // saving (the save would fail).
    var targetOutline by remember(template) { mutableStateOf<List<String>?>(null) }
    var targetHeadingMissing by remember(template) { mutableStateOf(false) }
    LaunchedEffect(template) {
        if (template.kind != TemplateKind.PLAIN || template.location !is TargetLocation.UnderHeading) return@LaunchedEffect
        val outline = viewModel.loadTargetOutline(template)
        targetOutline = outline
        targetHeadingMissing = outline == null
    }

    /** [text]'s preamble (`#+KEY:` lines) head vs. everything after it --
     *  the same boundary CaptureViewModel.roamBody splits on when a Roam
     *  capture lands on an already-existing file. */
    fun roamBodySplit(text: String): Pair<String, String> {
        val doc = OrgParser.parse(text, keywords)
        val drawerEnd = OrgMutations.fileDrawerRange(doc)?.last ?: -1
        val prefaceEnd = OrgMutations.prefaceRange(doc)?.last ?: -1
        val headLineCount = maxOf(drawerEnd, prefaceEnd) + 1
        val lines = doc.lines
        return lines.take(headLineCount).joinToString("\n") to lines.drop(headLineCount).joinToString("\n")
    }

    fun resolvedRoamPathFor(text: String): String? {
        val title = FilenamePattern.titleFromDraft(text) ?: return null
        val slug = FilenamePattern.slugFromTitle(title)
        val stem = FilenamePattern.expand(template.filenamePattern, now, slug)
        return if (template.roamDirectory.isBlank()) "$stem.org" else "${template.roamDirectory}/$stem.org"
    }

    var roamAppendState by remember(expanded) {
        mutableStateOf<RoamAppendState>(
            if (template.kind == TemplateKind.ROAM_NODE) RoamAppendState.Checking else RoamAppendState.New,
        )
    }
    LaunchedEffect(expanded) {
        if (template.kind != TemplateKind.ROAM_NODE) return@LaunchedEffect
        val path = resolvedRoamPathFor(expanded.text)
        roamAppendState = if (path == null) {
            RoamAppendState.New
        } else {
            if (viewModel.roamFileExists(path)) RoamAppendState.ExistingFile(path) else RoamAppendState.New
        }
    }

    // For a continuation of an existing file, only the new body is editable
    // (the file's existing content isn't shown, just like a plain capture;
    // the target bar says it's an existing file), so the field starts
    // out holding just the body half of the fresh expansion, cursor re-based
    // into it.
    val initialText = remember(expanded, roamAppendState) {
        (roamAppendState as? RoamAppendState.ExistingFile)?.let { roamBodySplit(expanded.text).second } ?: expanded.text
    }
    val textState = remember(expanded, roamAppendState) {
        val appendState = roamAppendState
        if (appendState is RoamAppendState.ExistingFile) {
            val (head, body) = roamBodySplit(expanded.text)
            val headOffset = if (head.isEmpty()) 0 else head.length + 1
            TextFieldState(body, TextRange((expanded.cursorOffset - headOffset).coerceIn(0, body.length)))
        } else {
            TextFieldState(expanded.text, TextRange(expanded.cursorOffset))
        }
    }
    // Snapshot-backed, so every keystroke recomposes the draft-dependent UI
    // (auto-save indicator, discard prompt) just as the old TextFieldValue did.
    // derivedStateOf so an unrelated recomposition (readMode, metadataOpen, …)
    // doesn't re-copy the whole buffer; only an actual text change does. Keyed
    // on textState itself: a Roam capture's Checking -> New/ExistingFile
    // transition recreates textState with a fresh TextFieldState (see its own
    // remember above), and an unkeyed derivedStateOf here would permanently
    // close over the original Checking-era instance -- silently orphaning
    // every keystroke into the field the user actually sees and edits.
    val draftText by remember(textState) { derivedStateOf { textState.text.toString() } }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(roamAppendState) {
        if (roamAppendState !is RoamAppendState.Checking) focusRequester.requestFocus()
    }
    val scrollState = rememberScrollState()
    // Written from the field's onTextLayout; read only by KeepCursorClearOfBottom's flow.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    KeepCursorClearOfBottom(textState, scrollState, { textLayout }, EditorBottomMarginLines)

    var showDiscardDialog by remember { mutableStateOf(false) }
    var showEmptyHeadingAlert by remember { mutableStateOf(false) }
    var showEmptyTitleAlert by remember { mutableStateOf(false) }
    var metadataOpen by remember { mutableStateOf(false) }
    var readMode by remember { mutableStateOf(false) }
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()

    // Inline auto-link suggestion strip -- same mechanism as EditNoteScreen,
    // gated on the Roam capture flow only.
    val roamSuggestionsActive = roamSuggestionsEnabled && template.kind == TemplateKind.ROAM_NODE
    LaunchedEffect(Unit) { if (roamSuggestionsActive) viewModel.loadAutoLinkIndex() }
    val autoLinkIndex by viewModel.autoLinkIndex.collectAsStateWithLifecycle()
    var autoLinkTrigger by remember { mutableStateOf<WordAtCursor?>(null) }
    var expandedChipKeys by remember(autoLinkTrigger?.range) { mutableStateOf(emptySet<String>()) }
    val autoLinkSuggestions = remember(autoLinkTrigger?.text, autoLinkIndex) {
        val idx = autoLinkIndex
        val word = autoLinkTrigger?.text
        if (idx == null || word == null) emptyList() else filterAutoLinkSuggestions(idx, word)
    }

    // Selection-triggered roam-node suggestions: parallel to the typing-triggered
    // auto-link strip above, but for a non-collapsed selection instead of a word
    // at a collapsed cursor. Gated the same way roamSuggestionsActive already gates
    // the typing-based strip.
    val coroutineScope = rememberCoroutineScope()
    val roamNodeTemplates by viewModel.roamNodeSuggestionTemplates.collectAsStateWithLifecycle()
    var roamNodeSelection by remember { mutableStateOf<Pair<String, TextRange>?>(null) }
    var roamNodeExpandedKeys by remember(roamNodeSelection?.second) { mutableStateOf(emptySet<String>()) }
    // One parse per draft change, shared by draftHeadline and the Read-mode
    // preview (each used to parse the draft on its own).
    val draftDoc = remember(draftText, keywords) { OrgParser.parse(draftText, keywords) }
    // Every Roam capture's file gets an :ID: on save (RoamFileId), so it's always
    // a linkable roam file; roamNodeSelection is only ever set for a Roam capture.
    val roamNodeSuggestionActive = roamNodeSelection != null && roamNodeTemplates.isNotEmpty()

    // Keyed on textState too: a Roam capture's Checking -> New/ExistingFile
    // transition (below) recreates textState with a fresh TextFieldState, same
    // as draftText's derivedStateOf above. Keying on roamSuggestionsActive alone
    // left this permanently watching the abandoned Checking-era instance,
    // so real keystrokes never reached it and the strip never had anything
    // to show.
    LaunchedEffect(roamSuggestionsActive, textState) {
        if (!roamSuggestionsActive) {
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

    // `<q` / `<e` / `<s` block-template chip: every capture kind, no setting gate.
    val blockTrigger by rememberBlockTrigger(textState)

    // Outside a body-only capture the draft is a single heading (the template
    // or withHeadingStars above supplies its "* " line), so this is what the
    // metadata sheet and the read-mode preview both edit/render.
    val draftHeadline = draftDoc.headlines.firstOrNull()

    /** Metadata-sheet edits: parse the draft, apply an [OrgMutations] transform,
     *  write the result back into the field. No auto-archive path (unlike
     *  EditorViewModel.changeKeyword): the entry isn't saved anywhere yet, so
     *  marking it done has nothing to archive. */
    fun mutateDraft(block: (OrgDocument, OrgHeadline) -> String) {
        textState.applyEdit { tfv ->
            val doc = OrgParser.parse(tfv.text, keywords)
            val headline = doc.headlines.firstOrNull() ?: return@applyEdit null
            val newText = block(doc, headline)
            if (newText == tfv.text) null else TextFieldValue(newText, TextRange(tfv.selection.start.coerceAtMost(newText.length)))
        }
    }

    // Mirrors the note editor's auto-save indicator: a tappable save (floppy)
    // icon in the top bar, shown once the draft has been edited or saved at
    // least once. Green + tap-to-save-now while dirty; grey + tap-for-last-
    // saved-toast once clean, exactly like EditNoteScreen/EditRegionScreen.
    var lastAutoSavedAt by remember { mutableStateOf<LocalTime?>(null) }
    // Text as of the last auto-save, so dirty can be computed by comparison.
    var lastAutoSavedText by remember(expanded, roamAppendState) { mutableStateOf(initialText) }
    val dirty = draftText != lastAutoSavedText
    val toastContext = LocalContext.current

    /**
     * The Roam node's target file. A continuation of an already-existing file
     * keeps the path resolved once at capture start ([RoamAppendState]); a
     * brand-new node keeps recomputing from the draft's live `#+title:` line
     * (same `now` the rest of the draft's timestamps use, so a `%<...>`
     * filename pattern doesn't drift while the user is still typing). Null
     * when the title is blank/missing, or the existence check hasn't landed yet.
     */
    /** What the view model writes for a Roam capture: a new file's draft with the
     *  template's hidden non-ID drawer properties back on top, or just the body. */
    fun roamSaveText(): String =
        if (roamAppendState is RoamAppendState.ExistingFile) draftText
        else RoamFileId.joinDrawer(roamSplit?.first.orEmpty(), draftText)

    fun resolvedRoamPath(): String? = when (val appendState = roamAppendState) {
        is RoamAppendState.ExistingFile -> appendState.path
        RoamAppendState.Checking -> null
        RoamAppendState.New -> resolvedRoamPathFor(draftText)
    }

    /** Immediate autosave, used by the idle timer and by tapping the dirty save icon. */
    fun saveNow() {
        if (template.kind == TemplateKind.ROAM_NODE) {
            resolvedRoamPath()?.let { path ->
                viewModel.autosaveRoam(path, roamSaveText(), context)
                lastAutoSavedAt = LocalTime.now()
                lastAutoSavedText = draftText
            }
        } else if (bodyOnly) {
            if (draftText.isNotBlank()) {
                viewModel.autosave(template, draftText, context, bodyOnly = true)
                lastAutoSavedAt = LocalTime.now()
                lastAutoSavedText = draftText
            }
        } else if (!CaptureInserter.hasBlankHeading(draftText)) {
            viewModel.autosave(template, draftText, context)
            lastAutoSavedAt = LocalTime.now()
            lastAutoSavedText = draftText
        }
    }

    fun tryClose() {
        if (draftText != initialText) showDiscardDialog = true else onClose()
    }

    fun discard() {
        if (template.kind == TemplateKind.ROAM_NODE) {
            resolvedRoamPath()?.let { viewModel.discardRoamDraft(it) }
        } else {
            viewModel.discardDraft(template)
        }
        onClose()
    }

    // Idle auto-save: wait for a 5s pause in typing before persisting the
    // draft. A note with no heading yet (just the auto-inserted "* ") is
    // skipped rather than saved, the same blank-heading state that blocks
    // the explicit Save button in trySave() below, so autosave never writes
    // a heading-less entry the user hasn't confirmed.
    LaunchedEffect(draftText) {
        delay(5_000)
        if (dirty) saveNow()
    }

    fun trySave() {
        if (template.kind == TemplateKind.ROAM_NODE) {
            val resolvedPath = resolvedRoamPath()
            if (resolvedPath == null) {
                showEmptyTitleAlert = true
            } else {
                viewModel.saveRoam(resolvedPath, roamSaveText(), context)
            }
        } else if (bodyOnly) {
            // A blank body is refused by the view model ("Nothing to save").
            viewModel.save(template, draftText, context, bodyOnly = true)
        } else if (CaptureInserter.hasBlankHeading(draftText)) {
            showEmptyHeadingAlert = true
        } else {
            viewModel.save(template, draftText, context)
        }
    }

    Scaffold(
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GroveTopBar(
                leading = {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = ::tryClose)
                            .padding(12.dp),
                    ) {
                        Text("×", fontFamily = PlexSans, fontSize = 22.sp, color = c.ink)
                    }
                    if (dirty || lastAutoSavedAt != null) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = if (dirty) "Unsaved changes, tap to save" else "Saved",
                            tint = if (dirty) c.green else c.ink3,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (dirty) {
                                        saveNow()
                                    } else {
                                        val formatted = lastAutoSavedAt?.let(AutoSaveTimestamp::format)
                                        Toast.makeText(
                                            toastContext,
                                            "The note auto saved at: $formatted",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                .padding(10.dp),
                        )
                    }
                },
                title = {
                    Text(
                        template.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.ink,
                    )
                },
                actions = {
                    // The metadata sheet edits keyword/priority/tags/planning on the
                    // draft's single headline, which a Roam-node draft doesn't have
                    // (it starts headline-less: file-level :PROPERTIES:/#+title: plus
                    // body). Hidden for that kind rather than shown-but-inert, and for
                    // a body-only capture, whose heading already exists in the file.
                    if (template.kind != TemplateKind.ROAM_NODE && !bodyOnly) {
                        IconGlyph("☰", onClick = { metadataOpen = true })
                    }
                    ReadEditToggle(isEditing = !readMode, onToggle = { readMode = !readMode })
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
            val roamPath = remember(draftText, roamAppendState) {
                if (template.kind == TemplateKind.ROAM_NODE) resolvedRoamPath() else null
            }
            CaptureTargetBar(
                template = template,
                today = now.toLocalDate(),
                targetOutline = targetOutline,
                targetHeadingMissing = targetHeadingMissing,
                bodyOnly = bodyOnly,
                roamPath = roamPath,
                roamFileExists = roamAppendState is RoamAppendState.ExistingFile,
            )
            (saveState as? SaveState.Failed)?.let { failed ->
                Text(
                    failed.message,
                    fontFamily = PlexSans, fontSize = 13.sp, color = c.red,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
            // The suggestion slot: there for every capture kind whenever the keyboard
            // is up, chips or not and whichever providers are on (the Roam ones only
            // ever fill it for a Roam capture).
            val slotShown = roamAppendState !is RoamAppendState.Checking &&
                (imeVisible || roamNodeSuggestionActive)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (readMode) {
                    if (bodyOnly) {
                        DraftIntroPreview(
                            doc = draftDoc,
                            showTitle = false,
                            modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                        )
                    } else if (draftHeadline != null) {
                        DraftPreview(
                            doc = draftDoc,
                            headline = draftHeadline,
                            modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                        )
                    } else {
                        // A Roam-node draft starts headline-less (#+title: plus
                        // body, no leading "* " yet), so
                        // there's no OrgHeadline for DraftPreview to key off of.
                        DraftIntroPreview(
                            doc = draftDoc,
                            modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                        )
                    }
                } else if (roamAppendState !is RoamAppendState.Checking) {
                    ContentFontScale(editModeFontSize) {
                        // The field owns its own vertical scrolling (rather than
                        // being wrapped in Modifier.verticalScroll): that is what
                        // lets Compose auto-scroll while a selection handle is
                        // dragged past the top or bottom edge, and keeps the cursor
                        // visible when the keyboard shrinks the viewport.
                        Column(Modifier.fillMaxSize()) {
                            if (targetHeadingMissing) {
                                TargetHeadingMissingWarning(template.targetFile)
                            }
                            BasicTextField(
                                state = textState,
                                inputTransformation = remember(keywords) { orgInputTransformation(keywords) },
                                outputTransformation = remember(c, keywords) {
                                    OrgSyntaxHighlight(c, keywords, trailingBlankLines = EditorBottomMarginLines)
                                },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                lineLimits = TextFieldLineLimits.MultiLine(),
                                textStyle = TextStyle(
                                    fontFamily = PlexMono, fontSize = 14.sp,
                                    lineHeight = 1.9.em, color = c.ink,
                                ),
                                cursorBrush = SolidColor(c.accent),
                                onTextLayout = { getResult -> textLayout = getResult() },
                                scrollState = scrollState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    // Scrolls away like Read mode's top contentPadding.
                                    .scrollAwareTopInset(scrollState, 20.dp)
                                    // Clears the floating Save button; while the
                                    // suggestion strip shows (Save inside it), exactly
                                    // the strip, so no gap opens above its hairline.
                                    .padding(
                                        start = 20.dp, end = 20.dp,
                                        bottom = if (slotShown) SuggestionSlotHeight else 80.dp,
                                    )
                                    .testTag("capture_body_field")
                                    .focusRequester(focusRequester),
                            )
                        }
                    }
                }
                // Save: a compact 36dp button that fits inside the suggestion strip.
                // Stays available in Read mode too, so a metadata-only capture
                // (state/dates/tags set from the sheet, no further typing) can be
                // saved without switching back to Edit.
                val saveButton: @Composable (Modifier) -> Unit = { modifier ->
                    Box(
                        modifier
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.accent)
                            .clickable(enabled = saveState !is SaveState.Saving && roamAppendState !is RoamAppendState.Checking) {
                                trySave()
                            }
                            .testTag("capture_save")
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (saveState is SaveState.Saving) "Saving…" else "Save",
                            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp, color = c.accentInk,
                        )
                    }
                }
                // The slot floats over the field's bottom clearance (one strip tall while
                // it shows), so it never covers text.
                // Bottom bar: the strip sits flush on the toolbar with Save at its end;
                // without it, Save floats bottom-right on its own (above the keyboard
                // while it's up, since the column is ime-padded).
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(bottom = if (slotShown) 0.dp else 16.dp),
                ) {
                    if (!slotShown) {
                        saveButton(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
                    } else {
                        SuggestionSlot(
                            Modifier.align(Alignment.BottomStart),
                            trailing = { saveButton(Modifier) },
                        ) {
                            // Link and block chips only while typing: with the keyboard down they'd just cover the draft.
                            val block = blockTrigger
                            if (imeVisible && block != null) {
                                BlockTemplateSuggestionStrip(
                                    template = block.template,
                                    onPick = { textState.applyBlockTemplate(block) },
                                    modifier = Modifier.align(Alignment.CenterStart),
                                )
                            } else if (imeVisible && autoLinkSuggestions.isNotEmpty()) {
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
                                // Unlike link chips, not tied to the keyboard: a long-press
                                // selection is often made with it down, and the strip sits in
                                // the field's bottom clearance either way.
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
                                            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.CenterStart),
                                )
                            }
                        }
                    }
                }
            }
            if (!readMode && imeVisible) EditorToolbar(
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
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
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
                    "Your changes will be lost.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    trySave()
                }) {
                    Text("Save", color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false; discard() }) {
                    Text("Discard", color = c.red)
                }
            },
        )
    }

    if (showEmptyHeadingAlert) {
        AlertDialog(
            onDismissRequest = { showEmptyHeadingAlert = false },
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
                TextButton(onClick = { showEmptyHeadingAlert = false }) {
                    Text("OK", color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    if (showEmptyTitleAlert) {
        AlertDialog(
            onDismissRequest = { showEmptyTitleAlert = false },
            containerColor = c.surface,
            title = {
                Text(
                    "Add a title",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
            },
            text = {
                Text(
                    "Please give this note a title before saving.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { showEmptyTitleAlert = false }) {
                    Text("OK", color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    if (metadataOpen) {
        MetadataSheet(
            headline = draftHeadline,
            keywords = keywords,
            allTags = allTags,
            onChangeKeyword = { kw ->
                mutateDraft { d, h -> OrgMutations.changeKeyword(d, h, kw, d.keywords, LocalDateTime.now()) }
            },
            onSetPriority = { p -> mutateDraft { d, h -> OrgMutations.setPriority(d, h, p) } },
            onSetTags = { tags -> mutateDraft { d, h -> OrgMutations.setTags(d, h, tags) } },
            onSetPlanningDates = { sched, dead, active ->
                mutateDraft { d, h -> OrgMutations.setPlanningAndActiveTimestamps(d, h, sched, dead, active) }
            },
            onAddNote = { note ->
                val stamp = LocalDateTime.now().let {
                    OrgTimestamp(it.toLocalDate(), time = it.toLocalTime().withSecond(0).withNano(0), active = false)
                }
                mutateDraft { d, h -> OrgMutations.appendLogbookNote(d, h, note.trim(), stamp) }
            },
            onGenerateId = {
                mutateDraft { d, h -> OrgMutations.upsertProperty(d, h, "ID", newOrgId()) }
            },
            onRefile = {},
            showRefile = false,
            onDismiss = { metadataOpen = false },
        )
    }
}

/**
 * Read mode's inline preview (design spec §8 hamburger/read-edit toggle): a
 * lighter render of the draft than [com.rrajath.grove.ui.screens.ReadNoteScreen]'s
 * NoteContent, since a capture draft has no file/vault identity yet to
 * navigate from (links, checkbox-toggle-writes-to-disk, refile all assume a
 * saved note). Body lines get inline org markup via [annotateOrgInline]; block
 * structure (lists, tables, code blocks) renders as plain lines.
 */
@Composable
private fun DraftPreview(doc: OrgDocument, headline: OrgHeadline, modifier: Modifier = Modifier) {
    val c = MaterialTheme.grove
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        val tags = doc.inheritedTags(headline)
        if (tags.isNotEmpty()) {
            Row {
                tags.forEach { tag ->
                    Pill(tag, fg = c.accent, bg = c.accentSoft, outline = true)
                    Spacer(Modifier.width(7.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            headline.keyword?.let { kw ->
                val (fg, bg) = if (doc.keywords.isDone(kw)) c.green to c.greenSoft else c.amber to c.amberSoft
                Pill(kw, fg = fg, bg = bg)
                Spacer(Modifier.width(8.dp))
            }
            headline.priority?.let { p ->
                Text(
                    "[#$p]", fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                    fontSize = 12.sp, color = c.priorityColor(p),
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            annotateOrgInline(headline.title.ifBlank { "(no heading yet)" }, c),
            fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp, color = if (headline.title.isBlank()) c.ink3 else c.ink, lineHeight = 1.3.em,
        )
        if (headline.planning.scheduled != null || headline.planning.deadline != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                headline.planning.scheduled?.let {
                    Pill("SCHEDULED " + it.formatHuman(), fg = c.blue, bg = c.blueSoft)
                }
                headline.planning.deadline?.let {
                    Pill("DEADLINE " + it.formatHuman(), fg = c.red, bg = c.redSoft)
                }
            }
        }
        val body = doc.bodyOf(headline)
        if (body.any { it.isNotBlank() }) {
            Spacer(Modifier.height(16.dp))
            body.forEach { line ->
                if (line.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        annotateOrgInline(line, c),
                        fontFamily = PlexSans, fontSize = 14.sp, color = c.ink, lineHeight = 1.5.em,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Read mode's inline preview for a headline-less draft: a Roam-node capture
 * expanded from `newFileTemplate` (file-level `:PROPERTIES:`/`#+title:` plus
 * body, no leading `* ` headline yet). Renders the `#+title:` in place of a
 * headline title, then the heading-less content -- what the app elsewhere
 * calls the "intro" (see [OrgDocument.introBody]) -- via [BodyBlocks], the
 * same block renderer [com.rrajath.grove.ui.screens.ReadNoteScreen] uses for
 * a saved file's intro, so lists/tables/code blocks render structured rather
 * than as plain lines. Links and checkboxes are inert here, same rationale as
 * [DraftPreview]: a capture draft has no file/vault identity yet to navigate
 * from or write a checkbox toggle to. Any headlines the user has since typed
 * into the draft render below the intro, in file order.
 */
@Composable
private fun DraftIntroPreview(doc: OrgDocument, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val c = MaterialTheme.grove
    val title = doc.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }?.second
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // A body-only capture hides the title: its heading already exists in the file.
        if (showTitle) {
            Text(
                annotateOrgInline(title?.takeIf { it.isNotBlank() } ?: "(no title yet)", c),
                fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                color = if (title.isNullOrBlank()) c.ink3 else c.ink,
                lineHeight = 1.3.em,
            )
        }
        if (doc.introBody.any { it.isNotBlank() }) {
            if (showTitle) Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Column {
                    BodyBlocks(
                        bodyLines = doc.introBody,
                        lineOffset = doc.introStart,
                        onToggleCheckbox = { _, _ -> },
                        openTarget = {},
                        onLinkLongPress = { _, _, _ -> },
                        onEditAt = {},
                    )
                }
            }
        }
        doc.headlines.forEach { h ->
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                h.keyword?.let { kw ->
                    val (fg, bg) = if (doc.keywords.isDone(kw)) c.green to c.greenSoft else c.amber to c.amberSoft
                    Pill(kw, fg = fg, bg = bg)
                    Spacer(Modifier.width(8.dp))
                }
                h.priority?.let { p ->
                    Text(
                        "[#$p]", fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, color = c.priorityColor(p),
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                annotateOrgInline(h.title, c),
                fontFamily = PlexSerif, fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp, color = c.ink, lineHeight = 1.3.em,
            )
            val body = doc.bodyOf(h)
            if (body.any { it.isNotBlank() }) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column {
                        BodyBlocks(
                            bodyLines = body,
                            lineOffset = h.bodyStart,
                            onToggleCheckbox = { _, _ -> },
                            openTarget = {},
                            onLinkLongPress = { _, _, _ -> },
                            onEditAt = {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown above a body-only capture's field when the target heading couldn't be
 * found in [targetFile], so the user learns before saving that the capture has
 * nowhere to go.
 */
@Composable
private fun TargetHeadingMissingWarning(targetFile: String) {
    val c = MaterialTheme.grove
    Text(
        "Heading not found in $targetFile. Saving will fail until it exists.",
        fontFamily = PlexSans, fontSize = 13.sp, color = c.red,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
    )
}

/** Where the capture lands, below the top bar; datetree targets add the date trail and day chip. */
@Composable
private fun CaptureTargetBar(
    template: CaptureTemplate,
    today: LocalDate,
    /** Under-heading target's ancestors + the heading itself; null while loading or missing. */
    targetOutline: List<String>?,
    targetHeadingMissing: Boolean,
    bodyOnly: Boolean,
    /** Roam node: the resolved file path, or null until the draft has a title. */
    roamPath: String?,
    roamFileExists: Boolean,
) {
    val c = MaterialTheme.grove
    val location = template.location
    if (template.kind == TemplateKind.PLAIN && location.isDatetree) {
        DatetreeBreadcrumb(template, today)
        return
    }
    val label = buildAnnotatedString {
        val fileStyle = SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)
        if (template.kind == TemplateKind.ROAM_NODE) {
            if (roamPath != null) {
                withStyle(fileStyle) { append(roamPath) }
            } else {
                if (template.roamDirectory.isNotBlank()) {
                    withStyle(fileStyle) { append(template.roamDirectory.trimEnd('/') + "/") }
                }
                withStyle(SpanStyle(color = c.ink3)) { append("…") }
            }
        } else {
            withStyle(fileStyle) { append(template.targetFile) }
            withStyle(SpanStyle(color = c.ink2)) {
                val headings = when {
                    targetOutline != null -> targetOutline
                    targetHeadingMissing && location is TargetLocation.UnderHeading ->
                        listOf(location.headingKey?.let { "#$it" } ?: location.title.orEmpty())
                    else -> emptyList()
                }
                headings.forEach { append(" › "); append(it) }
            }
        }
    }
    val (pill, pillFg, pillBg) = when {
        template.kind == TemplateKind.ROAM_NODE ->
            if (roamFileExists) Triple("existing", c.accent, c.accentSoft)
            else Triple("new file", c.green, c.greenSoft)
        location is TargetLocation.UnderHeading -> when {
            targetHeadingMissing -> Triple("not found", c.red, c.redSoft)
            bodyOnly -> Triple("under heading", c.accent, c.accentSoft)
            location.appendLast -> Triple("last child", c.accent, c.accentSoft)
            else -> Triple("first child", c.accent, c.accentSoft)
        }
        location is TargetLocation.TopOfFile -> Triple("top of file", c.accent, c.accentSoft)
        else -> Triple("bottom of file", c.accent, c.accentSoft)
    }
    CaptureTargetBarFrame {
        // Middle-ellipsized so a long path keeps both the file and the target heading visible.
        Text(
            label,
            fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink2,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f).testTag("capture_target_path"),
        )
        Spacer(Modifier.width(8.dp))
        Pill(pill, fg = pillFg, bg = pillBg)
    }
}

@Composable
private fun CaptureTargetBarFrame(content: @Composable RowScope.() -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(1.dp, c.line)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun DatetreeBreadcrumb(template: CaptureTemplate, today: LocalDate) {
    val c = MaterialTheme.grove
    CaptureTargetBarFrame {
        // The  t breadcrumb shares the row with the trailing pill; only the file
        // name may shrink (ellipsized), so nothing ever wraps vertically.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                template.targetFile,
                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp, color = c.accent,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                " › ${CaptureInserter.yearTitle(today)} › ${today.month.name.lowercase().replaceFirstChar { it.uppercase() }} › ",
                fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink2,
                maxLines = 1,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(c.accentSoft)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    "${OrgTimestamp.dayAbbrev(today)} ${today.dayOfMonth}",
                    fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Pill("auto-created", fg = c.green, bg = c.greenSoft)
    }
}

@Composable
private fun PromptDialog(
    prompts: List<String>,
    onCancel: () -> Unit,
    onDone: (Map<String, String>) -> Unit,
) {
    val c = MaterialTheme.grove
    var values by remember { mutableStateOf(prompts.associateWith { "" }) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = {
            Text(
                prompts.singleOrNull() ?: "Fill in",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink,
            )
        },
        text = {
            Column {
                prompts.forEach { prompt ->
                    if (prompts.size > 1) {
                        Text(prompt, fontFamily = PlexSans, fontSize = 13.sp, color = c.ink2)
                    }
                    OutlinedTextField(
                        value = values[prompt].orEmpty(),
                        onValueChange = { values = values + (prompt to it) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = PlexSans, color = c.ink),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(values) }) {
                Text("Continue", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = c.ink2) }
        },
    )
}
