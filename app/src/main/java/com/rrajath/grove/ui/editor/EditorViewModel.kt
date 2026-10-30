package com.rrajath.grove.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.RoamNodeCreator
import com.rrajath.grove.capture.RoamNodeResult
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.capture.hasUserDefinedTitle
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.markedDoneMessage
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.org.nextRepeatOccurrence
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.ui.vault.LinkedReferencesResult
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.OutlineSnack
import com.rrajath.grove.ui.vault.RefileNotebook
import com.rrajath.grove.ui.vault.LinkedReferencesLoader
import com.rrajath.grove.ui.vault.factory
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.ui.vault.headlineFor
import com.rrajath.grove.vault.AutoArchive
import com.rrajath.grove.vault.StateChangeResult
import java.time.LocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Which scoped region of a file an [EditorViewModel] session is editing, if not a
 * headline subtree. [WHOLE_FILE] is the degenerate region covering every line: the
 * buffer is the file, written back verbatim (the whole-file editor behind `Routes.FILE`).
 */
enum class EditRegion { INTRO, PREFACE, FILE_PROPERTIES, HEADING_PROPERTIES, HEADING_LOGBOOK, BLOCK, WHOLE_FILE }

data class EditorUiState(
    val loading: Boolean = true,
    val fileName: String = "",
    val lineIndex: Int = 0,
    /** The whole file's file-level `:ID:` ([OrgDocument.fileId]), captured once at [EditorViewModel.load]
     *  time -- null for a non-roam file, and always null for a scoped [region] (loaded via
     *  [EditorViewModel.loadRegion], which never sets it): selection-triggered roam-node
     *  suggestions only ever apply to the main subtree editor. */
    val fileOrgId: String? = null,
    /** Non-null when this session edits a scoped region (see [EditorViewModel.loadRegion])
     *  rather than a headline's subtree. [lineIndex] anchors the headline for the
     *  HEADING_* regions and is meaningless for INTRO / PREFACE / FILE_PROPERTIES. */
    val region: EditRegion? = null,
    /** The line range the region was loaded from, markers included; the save path
     *  recomputes it from the fresh file and falls back to this. */
    val regionRange: IntRange? = null,
    /** The note's subtree text being edited (or the scoped region's raw text, when [region] is set). */
    val buffer: String = "",
    /** Explicit initial cursor offset into [buffer], set only by [EditorViewModel.loadNewWholeFile]'s
     *  `%?`-placeholder seed; every other load path leaves this null and the field falls back to
     *  end-of-buffer on first load. */
    val cursor: Int? = null,
    val loadedRevision: String? = null,
    val keywords: OrgKeywords = OrgKeywords.DEFAULT,
    val dirty: Boolean = false,
    val error: String? = null,
    /** File changed on disk since load; offer overwrite (Force Save). */
    val staleFile: Boolean = false,
    val allTags: ImmutableList<String> = persistentListOf(),
    /**
     * Counts buffer rewrites that did *not* come from the text field: today
     * only the metadata sheet's mutations. The editor screen pushes the buffer
     * into the field when this changes, and never otherwise: inferring
     * "external rewrite" from the buffer text alone made fast typing race the
     * one-frame lag of the field→view-model report, and re-pushing the older
     * buffer swallowed the characters typed in between. Reset to 0 by [load].
     */
    val bufferRevision: Long = 0,
    /** When the buffer was last written to disk (auto-save or explicit Save), for the
     *  top bar's save icon. Tracked here (not per-screen `remember`) so both
     *  [EditorViewModel]'s own idle auto-save and an explicit save update it the same way. */
    val lastSavedAt: LocalDateTime? = null,
)

/**
 * State for the toolbar's "insert a link" bottom sheet: a notebook drill-down
 * (browse mode) plus a vault-wide live search over [searchIndex].
 */
data class LinkPickerUiState(
    /** Null while the notebook list / search index is still loading. */
    val notebooks: ImmutableList<RefileNotebook>? = null,
    val pickedFile: String? = null,
    val pickedDoc: OrgDocument? = null,
    /** Drill-down trail of headline lineIndexes inside [pickedDoc]; empty = top level. */
    val path: ImmutableList<Int> = persistentListOf(),
    val query: String = "",
    /** Vault-wide flat index for [query] to filter; null while still loading. */
    val searchIndex: ImmutableList<LinkSearchItem>? = null,
)

class EditorViewModel(
    private val vaultFlow: StateFlow<Vault?>,
    private val sync: SyncTrigger,
    private val database: GroveDatabase,
    private val settings: SettingsSource,
    private val keywords: StateFlow<OrgKeywords>,
    private val dispatchers: AppDispatchers,
    private val templatesRepository: TemplatesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state

    /** Serializes writes so an idle auto-save and an explicit save can't race. */
    private val saveMutex = Mutex()

    private val _snack = MutableStateFlow<OutlineSnack?>(null)
    val snack: StateFlow<OutlineSnack?> = _snack
    private var eventId = 0L

    /** Backlinks/mentions for the Backlinks bar+sheet; refreshed by [loadLinkedReferences]. */
    private val linkedRefs = LinkedReferencesLoader(viewModelScope, database, settings, dispatchers)
    val linkedReferences: StateFlow<LinkedReferencesResult> = linkedRefs.result

    /** See [LinkedReferencesLoader.load] -- same loader as `DocumentViewModel`, own copy of the state. */
    fun loadLinkedReferences(fileName: String, lineIndex: Int, targetId: String?, title: String) =
        linkedRefs.load(fileName, lineIndex, targetId, title)

    /** Empties Backlinks (and cancels a pending load) for a target whose bar can't show. */
    fun clearLinkedReferences() = linkedRefs.clear()

    /** Everything needed to put the buffer back where [changeKeyword]'s auto-archive found it. */
    private data class ArchiveUndo(
        val files: List<Pair<String, String>>,
        val fileName: String,
        val lineIndex: Int,
        val buffer: String,
    )

    private var archiveUndo: ArchiveUndo? = null

    // Idle auto-save (Settings § Notes → Auto-save notes): waits for a 5s pause
    // after an edit, then saves if the buffer is still dirty. Lives here instead
    // of a per-screen `LaunchedEffect(state.buffer, autoSaveNotes)` so typing
    // doesn't restart a Compose effect on every keystroke (PERFORMANCE_AUDIT
    // 2026-09-16 C3); EditNoteScreen and EditRegionScreen share this ViewModel
    // class, so one collector covers both the subtree and scoped-region editors.
    init {
        viewModelScope.launch {
            state
                .map { it.buffer to it.dirty }
                .distinctUntilChanged()
                .collectLatest { (_, dirty) ->
                    if (!dirty) return@collectLatest
                    delay(5_000)
                    if (settings.settings.first().autoSaveNotes) save()
                }
        }
    }

    private fun showSnack(message: String) {
        val s = OutlineSnack(message, ++eventId)
        _snack.value = s
        viewModelScope.launch {
            delay(4200)
            if (_snack.value?.id == s.id) _snack.value = null
        }
    }

    /** Reverts the auto-archive move (state change + refile) from the most recent [changeKeyword]. */
    fun undo() {
        val snap = archiveUndo ?: return
        archiveUndo = null
        _snack.value = null
        viewModelScope.launch {
            val vault = vaultFlow.value ?: return@launch
            snap.files.forEach { (name, text) ->
                vault.save(name, text)
                sync.requestReindex(name, text, "note undo")
            }
            val revision = vault.revision(snap.fileName)
            _state.update {
                it.copy(
                    fileName = snap.fileName,
                    lineIndex = snap.lineIndex,
                    buffer = snap.buffer,
                    dirty = false,
                    loadedRevision = revision,
                    bufferRevision = it.bufferRevision + 1,
                )
            }
        }
    }

    fun load(ref: NoteRef) {
        // Republished as loading first so a re-load (the stale-file banner's
        // "Reload") is a visible false→true→false transition: that edge is what
        // makes the editor screen re-seed its text field from the fresh buffer.
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val vault = vaultFlow.value ?: run {
                _state.value = EditorUiState(loading = false, error = "No sync folder configured")
                return@launch
            }
            val doc = vault.open(ref.fileName) ?: run {
                _state.value = EditorUiState(loading = false, error = "${ref.fileName} not found")
                return@launch
            }
            // Resolve by customId first (survives an external edit shifting ref.lineIndex);
            // headline.lineIndex below is then the current, correct line, not ref's possibly
            // stale snapshot — every later lookup in this session (save, delete, keyword
            // change) keys off state.lineIndex, so this is where drift gets healed.
            val headline = doc.headlineFor(ref) ?: run {
                _state.value = EditorUiState(loading = false, error = "Note not found")
                return@launch
            }
            val tags = database.indexDao().allTagStrings()
                .flatMap { it.split(':') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .toImmutableList()
            _state.value = EditorUiState(
                loading = false,
                fileName = ref.fileName,
                lineIndex = headline.lineIndex,
                fileOrgId = doc.fileId,
                buffer = OrgMutations.subtreeText(doc, headline),
                loadedRevision = vault.revision(ref.fileName),
                keywords = keywords.value,
                allTags = tags,
            )
        }
    }

    /**
     * Loads a scoped [region] of [fileName] for the intro / preface / drawer editors, opened
     * via a double-tap on the matching section. Unlike [load] there may be no headline subtree
     * to anchor to: [writeBuffer] detects [EditorUiState.region] and splices the buffer back
     * over the region's line range via [OrgMutations.replaceLines] instead.
     *
     * [noteId] is the encoded [NoteRef] of the owning headline, required for the HEADING_*
     * regions and ignored otherwise.
     */
    fun loadRegion(fileName: String, noteId: String?, region: EditRegion, blockLine: Int = -1) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val vault = vaultFlow.value ?: run {
                _state.value = EditorUiState(loading = false, error = "No sync folder configured")
                return@launch
            }
            val doc = vault.open(fileName) ?: run {
                _state.value = EditorUiState(loading = false, error = "$fileName not found")
                return@launch
            }
            var lineIndex = 0
            val (buffer, range) = when (region) {
                EditRegion.INTRO -> {
                    val r = OrgMutations.introRange(doc) ?: run {
                        _state.value = EditorUiState(loading = false, error = "This note is no longer here")
                        return@launch
                    }
                    OrgMutations.regionText(doc, r) to r
                }
                EditRegion.PREFACE -> {
                    val r = OrgMutations.prefaceRange(doc) ?: run {
                        _state.value = EditorUiState(loading = false, error = "No preface here")
                        return@launch
                    }
                    OrgMutations.regionText(doc, r) to r
                }
                EditRegion.FILE_PROPERTIES -> {
                    val r = OrgMutations.fileDrawerRange(doc) ?: run {
                        _state.value = EditorUiState(loading = false, error = "No file property drawer here")
                        return@launch
                    }
                    OrgMutations.regionText(doc, r) to r
                }
                EditRegion.HEADING_PROPERTIES, EditRegion.HEADING_LOGBOOK -> {
                    val ref = noteId?.let { NoteRef.decode(it) } ?: run {
                        _state.value = EditorUiState(loading = false, error = "Note not found")
                        return@launch
                    }
                    val headline = doc.headlineFor(ref) ?: run {
                        _state.value = EditorUiState(loading = false, error = "Note not found")
                        return@launch
                    }
                    val marker = if (region == EditRegion.HEADING_LOGBOOK) ":LOGBOOK:" else ":PROPERTIES:"
                    val r = OrgMutations.headingDrawerRange(doc, headline, marker) ?: run {
                        _state.value = EditorUiState(loading = false, error = "This drawer is no longer here")
                        return@launch
                    }
                    lineIndex = headline.lineIndex
                    OrgMutations.regionText(doc, r) to r
                }
                EditRegion.BLOCK -> {
                    val r = OrgMutations.blockRange(doc, blockLine) ?: run {
                        _state.value = EditorUiState(loading = false, error = "This block is no longer here")
                        return@launch
                    }
                    OrgMutations.regionText(doc, r) to r
                }
                // The whole buffer, exactly as on disk; the range is informational
                // only (writeBuffer replaces the file rather than splicing a range).
                EditRegion.WHOLE_FILE -> doc.text to doc.lines.indices
            }
            _state.value = EditorUiState(
                loading = false,
                fileName = fileName,
                lineIndex = lineIndex,
                region = region,
                regionRange = range,
                buffer = buffer,
                loadedRevision = vault.revision(fileName),
                keywords = keywords.value,
            )
        }
    }

    /**
     * Seed a brand-new whole-file editing session for [fileName], which does not
     * exist on disk yet, with [seedText] and a cursor at [cursor]. Starts clean
     * (not dirty): the seed is just a template, not something the user asked to
     * save, so nothing should hit disk -- and the idle auto-save timer shouldn't
     * even start -- until [onBufferChange] reports a real edit. The first [save]
     * (auto or manual) after that creates the file — see [writeBuffer]'s
     * WHOLE_FILE branch, which no longer requires [Vault.open] to succeed.
     */
    fun loadNewWholeFile(fileName: String, seedText: String, cursor: Int) {
        _state.value = EditorUiState(
            loading = false,
            fileName = fileName,
            lineIndex = 0,
            region = EditRegion.WHOLE_FILE,
            regionRange = IntRange.EMPTY,
            buffer = seedText,
            cursor = cursor,
            loadedRevision = null,
            keywords = keywords.value,
            dirty = false,
        )
    }

    /**
     * Drop the current session without saving, back to a fresh not-yet-loaded state.
     * For a screen that reuses this ViewModel across files (Dailies switches days in
     * place): without it, a buffer the user chose to discard would stay dirty and the
     * idle auto-save below would still write it 5s later.
     */
    fun reset() {
        _state.value = EditorUiState()
    }

    /** The text field reporting the user's own typing; never echoed back to it. */
    fun onBufferChange(text: String) {
        _state.update { it.copy(buffer = text, dirty = true) }
    }

    /**
     * Read mode folded one of its own mutations (a checkbox tap, a metadata-sheet
     * edit) into this still-unsaved buffer rather than writing the file. Bumps
     * [EditorUiState.bufferRevision] so the editor field picks the text up the next
     * time it composes; the buffer stays dirty, since nothing reached disk.
     */
    fun onBufferChangedExternally(text: String) {
        _state.update {
            if (it.buffer == text) it
            else it.copy(buffer = text, dirty = true, bufferRevision = it.bufferRevision + 1)
        }
    }

    /**
     * A read-mode mutation restructured the file and carried this buffer to disk
     * with it (see `DocumentViewModel.saveDoc`). The buffer is now what the file
     * holds, so clear [EditorUiState.dirty] rather than let a later save write a
     * stale subtree back over the result.
     */
    fun onBufferPersistedElsewhere() {
        viewModelScope.launch {
            val fileName = _state.value.fileName
            val revision = vaultFlow.value?.revision(fileName)
            _state.update { it.copy(dirty = false, loadedRevision = revision) }
        }
    }

    // Memoize the most recent parse so repeated currentHeadline reads and the
    // metadata mutations don't re-parse the same buffer over and over.
    private var parsedBuffer: String? = null
    private var parsedKeywords: OrgKeywords? = null
    private var parsedResult: Pair<OrgDocument, OrgHeadline>? = null

    /** Parse the buffer alone; its first headline is the note being edited. */
    private fun bufferHeadline(): Pair<OrgDocument, OrgHeadline>? {
        val buffer = _state.value.buffer
        val keywords = _state.value.keywords
        if (buffer == parsedBuffer && keywords == parsedKeywords) return parsedResult
        val doc = OrgParser.parse(buffer, keywords)
        val result = doc.headlines.firstOrNull()?.let { doc to it }
        parsedBuffer = buffer
        parsedKeywords = keywords
        parsedResult = result
        return result
    }

    val currentHeadline: OrgHeadline?
        get() = bufferHeadline()?.second

    /** Metadata-sheet edits: rewrite the buffer outside the text field, and bump
     *  [EditorUiState.bufferRevision] so the screen mirrors it into the field. */
    private fun mutateBuffer(block: (OrgDocument, OrgHeadline) -> String) {
        val (doc, h) = bufferHeadline() ?: return
        _state.update {
            it.copy(buffer = block(doc, h), dirty = true, bufferRevision = it.bufferRevision + 1)
        }
    }

    /**
     * Metadata sheet's state chips: routes through [OrgMutations.changeKeyword] so
     * picking a plain (non-done) keyword away from a done state also clears the
     * stale CLOSED stamp, the same as `OrgMutations.reopen`.
     *
     * Picking a done-type keyword may auto-archive (Settings § Notes), which needs the
     * vault and Settings and so can't stay inside the synchronous [mutateBuffer] path
     * every other chip uses; it flushes the buffer to disk first (auto-archive resolves
     * against the on-disk file, not the in-memory buffer) and, on a successful archive,
     * repoints [EditorUiState.fileName]/[EditorUiState.lineIndex] at the note's new
     * location so editing continues transparently.
     */
    fun changeKeyword(keyword: String?) {
        val (bufDoc, bufHeadline) = bufferHeadline() ?: return
        if (bufHeadline.keyword == keyword) return
        val maybeArchives = keyword != null && bufDoc.keywords.isDone(keyword)
        if (!maybeArchives) {
            mutateBuffer { d, h -> OrgMutations.changeKeyword(d, h, keyword, d.keywords, LocalDateTime.now()) }
            return
        }
        viewModelScope.launch {
            val vault = vaultFlow.value
            if (vault == null) {
                mutateBuffer { d, h -> OrgMutations.changeKeyword(d, h, keyword, d.keywords, LocalDateTime.now()) }
                return@launch
            }
            saveMutex.withLock { writeBuffer(force = true) }
            val s = _state.value
            val doc = vault.open(s.fileName)
            val headline = doc?.headlines?.firstOrNull { it.lineIndex == s.lineIndex }
            if (doc == null || headline == null) {
                mutateBuffer { d, h -> OrgMutations.changeKeyword(d, h, keyword, d.keywords, LocalDateTime.now()) }
                return@launch
            }
            val currentSettings = settings.settings.first()
            when (
                val result = AutoArchive.apply(vault, currentSettings, doc, s.fileName, headline, keyword, LocalDateTime.now())
            ) {
                is StateChangeResult.Plain -> {
                    vault.save(s.fileName, result.text)
                    sync.requestReindex(s.fileName, result.text, "note state set")
                    val newHeadline = result.doc.headlines.firstOrNull { it.lineIndex == s.lineIndex }
                    val revision = vault.revision(s.fileName)
                    _state.update {
                        it.copy(
                            buffer = newHeadline?.let { h -> OrgMutations.subtreeText(result.doc, h) } ?: it.buffer,
                            dirty = false,
                            loadedRevision = revision,
                            bufferRevision = it.bufferRevision + 1,
                        )
                    }
                    // A recurring task keeps its keyword and just moves its date, so say where it went.
                    val next = newHeadline?.let { nextRepeatOccurrence(headline, it) }
                    if (next != null) {
                        archiveUndo = ArchiveUndo(
                            files = listOf(s.fileName to doc.text),
                            fileName = s.fileName,
                            lineIndex = s.lineIndex,
                            buffer = s.buffer,
                        )
                        showSnack(markedDoneMessage(next, LocalDate.now()))
                    }
                }
                is StateChangeResult.Archived -> {
                    archiveUndo = ArchiveUndo(
                        files = if (result.sourceFile == result.destFile) {
                            listOf(result.sourceFile to doc.text)
                        } else {
                            listOf(s.fileName to doc.text, result.destFile to result.destTextBefore)
                        },
                        fileName = s.fileName,
                        lineIndex = s.lineIndex,
                        buffer = s.buffer,
                    )
                    vault.save(s.fileName, result.sourceText)
                    sync.requestReindex(s.fileName, result.sourceText, "note state set")
                    if (result.destFile != s.fileName) {
                        vault.save(result.destFile, result.destText)
                        sync.requestReindex(result.destFile, result.destText, "note state set")
                    }
                    val destDoc = OrgParser.parse(result.destText, doc.keywords)
                    val destHeadline = destDoc.headlines.firstOrNull { it.lineIndex == result.destLineIndex }
                    val destRevision = vault.revision(result.destFile)
                    _state.update {
                        it.copy(
                            fileName = result.destFile,
                            lineIndex = result.destLineIndex,
                            buffer = destHeadline?.let { h -> OrgMutations.subtreeText(destDoc, h) } ?: it.buffer,
                            dirty = false,
                            loadedRevision = destRevision,
                            bufferRevision = it.bufferRevision + 1,
                        )
                    }
                    showSnack("Marked done. Refiled to ${result.label}")
                }
            }
        }
    }
    fun setPriority(priority: Char?) = mutateBuffer { d, h -> OrgMutations.setPriority(d, h, priority) }
    fun setTags(tags: List<String>) = mutateBuffer { d, h -> OrgMutations.setTags(d, h, tags) }
    fun setScheduled(ts: OrgTimestamp?) = mutateBuffer { d, h -> OrgMutations.setScheduled(d, h, ts) }
    fun setDeadline(ts: OrgTimestamp?) = mutateBuffer { d, h -> OrgMutations.setDeadline(d, h, ts) }

    /** Planning dates + the dedicated active line in one edit: what the Dates screen commits. */
    fun setPlanningDates(
        scheduled: OrgTimestamp?,
        deadline: OrgTimestamp?,
        active: List<OrgTimestamp>,
    ) = mutateBuffer { d, h -> OrgMutations.setPlanningAndActiveTimestamps(d, h, scheduled, deadline, active) }

    /** Metadata sheet's "Add note": org's C-c C-z, logged into the LOGBOOK drawer. */
    fun addNote(note: String) {
        if (note.isBlank()) return
        val now = LocalDateTime.now()
        val stamp = OrgTimestamp(now.toLocalDate(), time = now.toLocalTime().withSecond(0).withNano(0), active = false)
        mutateBuffer { d, h -> OrgMutations.appendLogbookNote(d, h, note.trim(), stamp) }
    }

    /** Metadata sheet's "Generate org-id": writes a fresh `:ID:` into the heading's PROPERTIES drawer. */
    fun generateId() = mutateBuffer { d, h -> OrgMutations.upsertProperty(d, h, "ID", newOrgId()) }

    /**
     * Write the buffer back into the file. Refuses (sets [EditorUiState.staleFile])
     * if the file changed on disk since load, unless [force].
     *
     * A save runs concurrently with typing (idle auto-save fires on a timer, and
     * the write itself is slow SAF I/O), so it must never publish a snapshot of
     * the state it captured when it started: doing so would rewind the buffer
     * to the pre-save text and drop whatever was typed in between. Every write
     * back into [_state] therefore goes through `update`, touching only the
     * fields this save actually owns, and [dirty] is recomputed by comparing the
     * text that reached disk against the text the buffer holds *now*.
     */
    fun save(force: Boolean = false, onSaved: () -> Unit = {}) {
        val initial = _state.value
        if (!initial.dirty || initial.error != null) {
            onSaved()
            return
        }
        viewModelScope.launch {
            // Serialized: two overlapping saves would both compute a new file
            // text from the same on-disk revision, and the slower one would
            // silently undo the faster one.
            val saved = saveMutex.withLock { writeBuffer(force) }
            if (saved) onSaved()
        }
    }

    /** @return true when the buffer is on disk (including "already was"). */
    private suspend fun writeBuffer(force: Boolean): Boolean {
        val s = _state.value
        if (s.error != null) return false
        // Another save (e.g. the idle auto-save that fired while the leave
        // dialog was up) got there first; the caller's "then leave" follow-up
        // is still owed.
        if (!s.dirty) return true
        val vault = vaultFlow.value ?: return false
        // The exact text this save is responsible for. Anything typed after
        // this line belongs to the next save, not this one.
        val savedBuffer = s.buffer
        val currentRevision = vault.revision(s.fileName)
        if (!force && currentRevision != s.loadedRevision) {
            _state.update { it.copy(staleFile = true) }
            return false
        }
        // Parsing and the subtree splice are pure CPU on a whole file; keep them
        // off the main thread so a large notebook can't stall the keyboard
        // mid-keystroke while an auto-save runs.
        val newText = if (s.region == EditRegion.WHOLE_FILE) {
            // The buffer *is* the file: nothing to open, nothing to splice —
            // and, for a brand-new file (see loadNewWholeFile), there is
            // nothing to open yet either.
            savedBuffer
        } else withContext(dispatchers.default) {
            val doc = vault.open(s.fileName) ?: return@withContext null
            // Extreme edge case for every branch below: the region/note vanished
            // from the file (heavy external edit) and no stored range survives;
            // append the buffer at the end rather than lose the user's work.
            val appendFallback = doc.text.trimEnd('\n') + "\n" + savedBuffer.trimEnd('\n') + "\n"
            when (s.region) {
                EditRegion.INTRO -> {
                    val range = OrgMutations.introRange(doc) ?: s.regionRange
                    if (range != null) OrgMutations.replaceLines(doc, range, savedBuffer) else appendFallback
                }
                EditRegion.PREFACE -> {
                    val range = OrgMutations.prefaceRange(doc) ?: s.regionRange
                    if (range != null) OrgMutations.replaceLines(doc, range, savedBuffer) else appendFallback
                }
                EditRegion.FILE_PROPERTIES -> {
                    val range = OrgMutations.fileDrawerRange(doc) ?: s.regionRange
                    if (range != null) OrgMutations.replaceLines(doc, range, savedBuffer) else appendFallback
                }
                EditRegion.HEADING_PROPERTIES, EditRegion.HEADING_LOGBOOK -> {
                    val marker = if (s.region == EditRegion.HEADING_LOGBOOK) ":LOGBOOK:" else ":PROPERTIES:"
                    val headline = doc.headlines.firstOrNull { it.lineIndex == s.lineIndex }
                    val range = headline?.let { OrgMutations.headingDrawerRange(doc, it, marker) } ?: s.regionRange
                    if (range != null) OrgMutations.replaceLines(doc, range, savedBuffer) else appendFallback
                }
                EditRegion.BLOCK -> {
                    val range = s.regionRange?.first?.let { OrgMutations.blockRange(doc, it) } ?: s.regionRange
                    if (range != null) OrgMutations.replaceLines(doc, range, savedBuffer) else appendFallback
                }
                null -> {
                    val headline = doc.headlines.firstOrNull { it.lineIndex == s.lineIndex }
                    if (headline != null) OrgMutations.replaceSubtree(doc, headline, savedBuffer) else appendFallback
                }
                // Unreachable: handled by the outer `if` above, before this
                // withContext block (and its vault.open) ever runs.
                EditRegion.WHOLE_FILE -> error("WHOLE_FILE is handled before this withContext block")
            }
        } ?: return false
        vault.save(s.fileName, newText)
        val newRevision = vault.revision(s.fileName)
        // Only this one file changed and we hold its new text: reindex it
        // directly instead of a full-vault list+diff (PERFORMANCE_AUDIT #1).
        sync.requestReindex(s.fileName, newText, "note saved")
        _state.update { current ->
            current.copy(
                // Still dirty if the user typed while the write was in flight:
                // those characters are only in memory, and the idle timer (keyed
                // on the buffer) will have already re-armed for them.
                dirty = current.buffer != savedBuffer,
                staleFile = false,
                loadedRevision = newRevision,
                lastSavedAt = LocalDateTime.now(),
            )
        }
        return true
    }

    fun dismissStale() {
        _state.update { it.copy(staleFile = false) }
    }

    fun isCurrentHeadingBlank(): Boolean = currentHeadline?.title.isNullOrBlank()

    /**
     * Remove the edited subtree from the file without saving the buffer.
     * Used when the user discards a freshly created note that still has no heading.
     */
    fun deleteSubtree(onDeleted: () -> Unit = {}) {
        val s = _state.value
        viewModelScope.launch {
            val vault = vaultFlow.value ?: run { onDeleted(); return@launch }
            val doc = vault.open(s.fileName) ?: run { onDeleted(); return@launch }
            val headline = doc.headlines.firstOrNull { it.lineIndex == s.lineIndex }
            if (headline != null) {
                val newText = OrgMutations.deleteSubtree(doc, headline)
                vault.save(s.fileName, newText)
                sync.requestReindex(s.fileName, newText, "empty note discarded")
            }
            onDeleted()
        }
    }

    // --- link picker (toolbar link flyout: "File or heading") ---
    // A drill-down over the vault (browse mode) plus a vault-wide live search.
    // Always starts at the notebook list, never the file being edited: unlike
    // refile/archive there is no "natural" source location to pre-pick here. At
    // a file's top level the picker links to the whole file; drilled into a
    // heading it links to that heading. EditNoteScreen decides between an id
    // link and a plain one once a target is committed.

    private val _linkPicker = MutableStateFlow<LinkPickerUiState?>(null)
    val linkPicker: StateFlow<LinkPickerUiState?> = _linkPicker

    // --- inline auto-link suggester (prototype) ---
    // Vault-wide index of id-linkable files/headings, loaded once when the
    // editor opens (not lazily on first keystroke) so the strip can appear the
    // moment a 3-character word is typed. Null while still loading.
    private val _autoLinkIndex = MutableStateFlow<ImmutableList<AutoLinkSuggestion>?>(null)
    val autoLinkIndex: StateFlow<ImmutableList<AutoLinkSuggestion>?> = _autoLinkIndex

    /** Eager load for the typing suggester; a no-op unless Roam suggestions are on. */
    fun loadAutoLinkIndex() {
        viewModelScope.launch {
            if (!settings.settings.first().roamSuggestionsActive) return@launch
            _autoLinkIndex.value = fetchAutoLinkIndex()
        }
    }

    private suspend fun fetchAutoLinkIndex(): ImmutableList<AutoLinkSuggestion> =
        database.headingOutlines.snapshot().autoLinkIndex()

    // --- selection-triggered roam-node suggestions ---
    // Parallel to the typing-triggered auto-link suggester above: offers to
    // turn a non-collapsed selection into a link to a new or existing roam
    // node. See capture/RoamNodeSuggest.kt.

    /** Roam-kind templates whose title the user actually types (see [hasUserDefinedTitle]).
     *  Empty unless Settings § Roam Features suggestions are on ([com.rrajath.grove.settings.GroveSettings.roamSuggestionsActive]),
     *  the same gate as the typing-triggered [loadAutoLinkIndex]. */
    val roamNodeSuggestionTemplates: StateFlow<List<CaptureTemplate>> = combine(
        templatesRepository.templates,
        settings.settings.map { it.roamSuggestionsActive },
    ) { all, suggestionsActive ->
        if (!suggestionsActive) emptyList()
        else all.filter { it.kind == TemplateKind.ROAM_NODE && it.hasUserDefinedTitle() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Selection-triggered chip tap: links [selectedTitle] to an already-existing
     * same-titled node, or expands [template] into a brand-new one. Refreshes
     * [autoLinkIndex] after a create so a second identical selection later in
     * this session hits the match branch instead of duplicating the node.
     */
    suspend fun createOrLinkRoamNode(template: CaptureTemplate, selectedTitle: String): RoamNodeResult? {
        val vault = vaultFlow.value ?: return null
        // The eager load may not have landed yet (or the screen never asked for it);
        // build it now, on the one tap that needs it, so an existing same-titled node still matches.
        val index = autoLinkIndex.value ?: fetchAutoLinkIndex()
        val result = RoamNodeCreator.createOrLink(
            vault, sync, template, selectedTitle, index, LocalDateTime.now(),
        )
        if (result is RoamNodeResult.Created) {
            database.headingOutlines.invalidate()
            _autoLinkIndex.value = fetchAutoLinkIndex()
        }
        return result
    }

    fun startLinkPicker() {
        _linkPicker.value = LinkPickerUiState()
        viewModelScope.launch {
            // File names + counts + heading titles come from the Room index; opening
            // and re-parsing every .org file just to list/search them would be
            // O(files) work on every picker open instead of two indexed queries.
            // Cached across opens until the index changes (HeadingOutlineCache).
            val snapshot = database.headingOutlines.snapshot()
            val sortedNotebooks = snapshot.notebooks.sortedBy { it.fileName }
                .map { RefileNotebook(it.fileName, it.noteCount) }
                .toImmutableList()
            val searchIndex = snapshot.linkSearchIndex()
            _linkPicker.update { it?.copy(notebooks = sortedNotebooks, searchIndex = searchIndex) }
        }
    }

    fun linkPickerQueryChange(query: String) {
        _linkPicker.update { it?.copy(query = query) }
    }

    fun linkPickerPickNotebook(fileName: String) {
        viewModelScope.launch {
            val doc = vaultFlow.value?.open(fileName) ?: run {
                showSnack("Couldn't open ${fileName.removeSuffix(".org")}")
                return@launch
            }
            _linkPicker.update {
                it?.copy(pickedFile = fileName, pickedDoc = doc, path = persistentListOf(), query = "")
            }
        }
    }

    fun linkPickerDrillInto(line: Int) {
        _linkPicker.update { it?.copy(path = (it.path + line).toImmutableList()) }
    }

    /** Pop one drill level, or return to the notebook list from a file's top level. */
    fun linkPickerBack() {
        _linkPicker.update { r ->
            r ?: return@update r
            if (r.path.isNotEmpty()) r.copy(path = r.path.dropLast(1).toImmutableList())
            else r.copy(pickedFile = null, pickedDoc = null)
        }
    }

    fun linkPickerCancel() {
        _linkPicker.value = null
    }

    /**
     * A tap on a live-search result. A file drills into it, same as picking it
     * from the notebook list; a heading with sub-headings drills into it too
     * (jumping straight past whatever level the search skipped); a leaf heading
     * (no sub-headings) comes back ready to confirm right away instead of
     * drilling into an empty "no sub-headings" screen.
     */
    suspend fun linkPickerSelectSearchResult(item: LinkSearchItem): LinkPickerSearchOutcome {
        val vault = vaultFlow.value ?: return LinkPickerSearchOutcome.Failed
        val cur = _linkPicker.value
        val doc = cur?.takeIf { it.pickedFile == item.fileName }?.pickedDoc ?: vault.open(item.fileName)
        if (doc == null) {
            showSnack("Couldn't open ${item.fileName.removeSuffix(".org")}")
            return LinkPickerSearchOutcome.Failed
        }
        return when (item) {
            is LinkFileHit -> {
                _linkPicker.update {
                    it?.copy(pickedFile = item.fileName, pickedDoc = doc, path = persistentListOf(), query = "")
                }
                LinkPickerSearchOutcome.Drilled
            }
            is LinkHeadingHit -> {
                val heading = doc.headlineAtLine(item.lineIndex) ?: run {
                    showSnack("That heading no longer exists")
                    return LinkPickerSearchOutcome.Failed
                }
                if (doc.hasDescendants(heading)) {
                    val ancestry = generateSequence(heading, doc::parent).toList().asReversed()
                        .map { it.lineIndex }
                        .toImmutableList()
                    _linkPicker.update {
                        it?.copy(pickedFile = item.fileName, pickedDoc = doc, path = ancestry, query = "")
                    }
                    LinkPickerSearchOutcome.Drilled
                } else {
                    _linkPicker.update { it?.copy(pickedFile = item.fileName, pickedDoc = doc, query = "") }
                    LinkPickerSearchOutcome.ReadyToConfirm(doc, item.fileName, heading)
                }
            }
        }
    }

    companion object {
        val Factory = factory {
            EditorViewModel(
                it.vault, it.syncManager, it.database, it.settingsRepository, it.keywords, it.dispatchers,
                it.templatesRepository,
            )
        }
    }
}
