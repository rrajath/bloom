package com.rrajath.grove.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.capture.CaptureContext
import com.rrajath.grove.capture.CaptureInserter
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.RoamFileId
import com.rrajath.grove.capture.RoamNodeCreator
import com.rrajath.grove.capture.RoamNodeResult
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.capture.hasUserDefinedTitle
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.ui.editor.AutoLinkSuggestion
import com.rrajath.grove.ui.editor.autoLinkIndex
import com.rrajath.grove.ui.vault.NotebookItem
import com.rrajath.grove.ui.vault.allFolderDirs
import com.rrajath.grove.vault.TestVaultHook
import com.rrajath.grove.vault.Vault
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

sealed class SaveState {
    data object Idle : SaveState()
    data object Saving : SaveState()
    data class Saved(val filePath: String) : SaveState()
    data class Failed(val message: String) : SaveState()
}

class CaptureViewModel(
    private val templatesRepository: TemplatesRepository,
    private val database: GroveDatabase,
    private val sync: SyncTrigger,
    private val vaultFlow: StateFlow<Vault?>,
    private val settings: SettingsSource,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    val templates: StateFlow<List<CaptureTemplate>> = templatesRepository.templates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [templates] minus Roam-node templates when Settings § Roam Features is off. */
    val pickerTemplates: StateFlow<List<CaptureTemplate>> = combine(
        templatesRepository.templates,
        settings.settings.map { it.roamFeaturesEnabled },
    ) { all, roamEnabled ->
        if (roamEnabled) all else all.filterNot { it.kind == TemplateKind.ROAM_NODE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState

    // Snapshot for the metadata sheet's tag autocomplete, loaded once per screen
    // visit (same as EditorViewModel.load), not kept live: a capture draft is
    // short-lived, so a tag added by another edit mid-capture is not worth the
    // cost of a reactive query here.
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags

    init {
        viewModelScope.launch {
            _allTags.value = database.indexDao().allTagStrings()
                .flatMap { it.split(':') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
    }

    // Inline auto-link suggester (Settings § Roam Features): same vault-wide
    // id-linkable index as EditorViewModel.loadAutoLinkIndex, loaded once when
    // the capture screen opens.
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

    // Selection-triggered roam-node suggestions inside a Roam-kind capture
    // draft -- same mechanism as EditorViewModel's, see capture/RoamNodeSuggest.kt.

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

    /** Selection-triggered chip tap inside the capture draft; see EditorViewModel.createOrLinkRoamNode. */
    suspend fun createOrLinkRoamNode(template: CaptureTemplate, selectedTitle: String): RoamNodeResult? {
        val vault = vaultFlow.value ?: return null
        // See EditorViewModel.createOrLinkRoamNode: built on demand if the eager load hasn't landed.
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

    fun template(id: String): CaptureTemplate? = templates.value.firstOrNull { it.id == id }

    // Tracks the currently autosaved draft (if any) so the next autosave or the
    // final Save replaces it in place instead of inserting a duplicate copy.
    private var draftInsertion: CaptureInserter.Insertion? = null

    // Draft writes are read-modify-write over the whole target file and are
    // triggered from two places (the 5s idle autosave and the Save button), so
    // they must not interleave: the loser would re-insert against a stale
    // `draftInsertion` and leave a duplicate entry behind.
    private val writeMutex = Mutex()

    /**
     * Insert [entryText] into the template's target file, creating the file
     * if it doesn't exist yet. [bodyOnly]: see [CaptureInserter.isBodyOnly].
     */
    fun save(template: CaptureTemplate, entryText: String, context: CaptureContext, bodyOnly: Boolean = false) {
        if (entryText.isBlank()) {
            _saveState.value = SaveState.Failed("Nothing to save")
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            try {
                val (fileName, newText) = writeMutex.withLock { upsertEntry(template, entryText, context, bodyOnly) }
                sync.requestReindex(fileName, newText, "capture saved")
                draftInsertion = null
                _saveState.value = SaveState.Saved(fileName)
            } catch (e: Exception) {
                _saveState.value = SaveState.Failed(e.message ?: "Capture failed")
            }
        }
    }

    /**
     * Silently persist the in-progress capture so it survives the app being
     * killed mid-edit. Replaces the previous autosave in place (never
     * duplicates it) by stripping it out before re-inserting.
     */
    fun autosave(template: CaptureTemplate, entryText: String, context: CaptureContext, bodyOnly: Boolean = false) {
        if (entryText.isBlank()) return
        viewModelScope.launch {
            try {
                writeMutex.withLock { upsertEntry(template, entryText, context, bodyOnly) }
            } catch (_: Exception) {
                // Best-effort: a failed autosave just waits for the next tick
                // or the explicit Save tap, which surfaces errors to the user.
            }
        }
    }

    /** Remove a draft this session autosaved, e.g. when the user discards the capture. */
    fun discardDraft(template: CaptureTemplate) {
        val prev = draftInsertion ?: return
        draftInsertion = null
        viewModelScope.launch {
            writeMutex.withLock {
                val vault = vaultFlow.value ?: return@withLock
                val text = withContext(dispatchers.default) { vault.open(template.targetFile)?.text }
                    ?: return@withLock
                val newText = CaptureInserter.removeInsertion(text, prev)
                vault.save(template.targetFile, newText)
                sync.requestReindex(template.targetFile, newText, "capture discarded")
            }
        }
    }

    private suspend fun upsertEntry(
        template: CaptureTemplate,
        entryText: String,
        context: CaptureContext,
        bodyOnly: Boolean,
    ): Pair<String, String> {
        val currentSettings = settings.settings.first()
        // Throw rather than set state + return: the caller ([save]) continues
        // running after this returns, and would otherwise overwrite the failure
        // with SaveState.Saved and fire a spurious sync. [save]'s catch turns
        // this back into SaveState.Failed; [autosave] swallows it.
        //
        // TestVaultHook.root is the debug/benchmark direct-directory vault
        // (Maestro, :macrobenchmark): it feeds GroveApplication.fileStore
        // without ever writing vaultTreeUri, so treat it as a configured vault
        // here too. Always null in release (R8 strips the path).
        if (currentSettings.vaultTreeUri == null && TestVaultHook.root.value == null) {
            error("No sync folder configured")
        }
        // On a cold start (e.g. launched via app shortcut) the vault may
        // still be initializing even though a folder is configured; await it.
        val vault = vaultFlow.filterNotNull().first()
        // Parsing the target file and splicing the entry into it are pure CPU
        // over the whole document. The idle autosave fires while the user is
        // still typing, so this stays off the main thread: a parse stall there
        // desynchronizes the IME from the text field and swallows keystrokes.
        val result = withContext(dispatchers.default) {
            if (vault.open(template.targetFile) == null) {
                vault.createNotebook(template.targetFile)
            }
            val currentText = vault.open(template.targetFile)?.text ?: ""
            // Strip our own previous draft first so re-inserting replaces it in
            // place rather than leaving a stale duplicate behind.
            val baseText = draftInsertion?.let { CaptureInserter.removeInsertion(currentText, it) }
                ?: currentText
            CaptureInserter.insert(
                docText = baseText,
                location = template.location,
                entry = entryText,
                today = LocalDate.from(context.now),
                bodyOnly = bodyOnly,
            )
        }
        vault.save(template.targetFile, result.newText)
        draftInsertion = result
        return template.targetFile to result.newText
    }

    // --- Roam node capture (kind == TemplateKind.ROAM_NODE) ---
    //
    // entryText is the full expanded newFileTemplate draft (head + body); the
    // target path is computed by the screen from the draft's live #+title:
    // line, not fixed like template.targetFile, so it's passed in resolved.
    // The file-level :ID: drawer is never part of the draft: it's added here,
    // with context.id, to a new file and to an existing file that lacks one.

    private data class RoamDraft(
        val path: String,
        val insertion: CaptureInserter.Insertion?,
        /** True once this session created [path] fresh and owns its whole content. */
        val ownedNewFile: Boolean,
    )

    private var roamDraft: RoamDraft? = null

    /**
     * Writes [fullDraftText] (plus its `:ID:` drawer) to a brand-new [resolvedPath], or — an
     * existing file, e.g. a second capture into today's daily note — strips
     * its head (properties drawer + preamble) and appends just the body at
     * the bottom, same as [upsertEntry]'s insert-and-track mechanism.
     */
    fun saveRoam(resolvedPath: String, fullDraftText: String, context: CaptureContext) {
        if (fullDraftText.isBlank()) {
            _saveState.value = SaveState.Failed("Nothing to save")
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            try {
                val (fileName, newText) = writeMutex.withLock { upsertRoamEntry(resolvedPath, fullDraftText, context) }
                sync.requestReindex(fileName, newText, "capture saved")
                roamDraft = null
                _saveState.value = SaveState.Saved(fileName)
            } catch (e: Exception) {
                _saveState.value = SaveState.Failed(e.message ?: "Capture failed")
            }
        }
    }

    /** Silent equivalent of [saveRoam], mirroring [autosave]. */
    fun autosaveRoam(resolvedPath: String, fullDraftText: String, context: CaptureContext) {
        if (fullDraftText.isBlank()) return
        viewModelScope.launch {
            try {
                writeMutex.withLock { upsertRoamEntry(resolvedPath, fullDraftText, context) }
            } catch (_: Exception) {
                // Best-effort, same rationale as autosave().
            }
        }
    }

    /**
     * Remove a Roam draft this session autosaved: deletes a freshly-created
     * file outright, or strips just the tracked insertion out of a
     * pre-existing one. A no-op if [resolvedPath] no longer matches what was
     * last autosaved (e.g. the title changed since) — same accepted gap as
     * the stray-file risk already noted for a mid-edit title change.
     */
    fun discardRoamDraft(resolvedPath: String) {
        val prev = roamDraft ?: return
        if (prev.path != resolvedPath) return
        roamDraft = null
        viewModelScope.launch {
            writeMutex.withLock {
                val vault = vaultFlow.value ?: return@withLock
                if (prev.ownedNewFile) {
                    if (withContext(dispatchers.default) { vault.deleteNotebook(resolvedPath) }) {
                        database.indexDao().removeNotebook(resolvedPath)
                    }
                    sync.requestSync("capture discarded")
                } else {
                    val insertion = prev.insertion ?: return@withLock
                    val text = withContext(dispatchers.default) { vault.open(resolvedPath)?.text }
                        ?: return@withLock
                    val newText = CaptureInserter.removeInsertion(text, insertion)
                    vault.save(resolvedPath, newText)
                    sync.requestReindex(resolvedPath, newText, "capture discarded")
                }
            }
        }
    }

    private suspend fun upsertRoamEntry(
        resolvedPath: String,
        fullDraftText: String,
        context: CaptureContext,
    ): Pair<String, String> {
        val currentSettings = settings.settings.first()
        if (currentSettings.vaultTreeUri == null && TestVaultHook.root.value == null) {
            error("No sync folder configured")
        }
        val vault = vaultFlow.filterNotNull().first()
        val prev = roamDraft
        val result = withContext(dispatchers.default) {
            if (prev != null && prev.path == resolvedPath && prev.ownedNewFile) {
                // Still the same file this session created; it's ours alone,
                // so the freshest draft simply replaces its whole content.
                vault.save(resolvedPath, RoamFileId.withFileId(fullDraftText, context.id))
                RoamDraft(resolvedPath, null, ownedNewFile = true)
            } else if (vault.open(resolvedPath) == null) {
                vault.createNotebook(resolvedPath)
                vault.save(resolvedPath, RoamFileId.withFileId(fullDraftText, context.id))
                RoamDraft(resolvedPath, null, ownedNewFile = true)
            } else {
                val currentText = vault.open(resolvedPath)?.text ?: ""
                val baseText = RoamFileId.ensureFileId(
                    if (prev != null && prev.path == resolvedPath && !prev.ownedNewFile) {
                        prev.insertion?.let { CaptureInserter.removeInsertion(currentText, it) } ?: currentText
                    } else {
                        currentText
                    },
                    context.id,
                )
                // appendVerbatim, not insert(): the body is plain continuation
                // text/structure the template itself defines, not a new
                // top-level heading entry, so it must not be re-leveled.
                val insertion = CaptureInserter.appendVerbatim(baseText, roamBody(fullDraftText))
                vault.save(resolvedPath, insertion.newText)
                RoamDraft(resolvedPath, insertion, ownedNewFile = false)
            }
        }
        roamDraft = result
        val savedText = result.insertion?.newText ?: RoamFileId.withFileId(fullDraftText, context.id)
        return resolvedPath to savedText
    }

    /**
     * Everything after a Roam draft's head (file-level properties drawer +
     * `#+KEY:` preamble lines) — the part that gets appended, not the front
     * matter, when capturing into an already-existing resolved file.
     */
    private fun roamBody(text: String): String {
        val doc = OrgParser.parse(text)
        val drawerEnd = OrgMutations.fileDrawerRange(doc)?.last ?: -1
        val prefaceEnd = OrgMutations.prefaceRange(doc)?.last ?: -1
        val headEnd = maxOf(drawerEnd, prefaceEnd)
        return doc.lines.drop(headEnd + 1).joinToString("\n")
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
    }

    /**
     * The raw headline line (stars, keyword, title, tags) of the heading an
     * "under heading" [template] targets, or null when the file or heading
     * doesn't exist. Shown read-only above a body-only capture's field.
     */
    /**
     * The target heading path (ancestors first, the target heading last) of an
     * "under heading" template, or of a date tree that grows inside a heading;
     * null when the file or heading doesn't exist, or the target has no heading.
     */
    suspend fun loadTargetOutline(template: CaptureTemplate): List<String>? {
        val location = template.location
        if (location !is TargetLocation.UnderHeading &&
            !(location is TargetLocation.Datetree && location.hasHeading)
        ) return null
        val vault = vaultFlow.filterNotNull().first()
        return withContext(dispatchers.default) {
            val doc = vault.open(template.targetFile) ?: return@withContext null
            val target = when (location) {
                is TargetLocation.UnderHeading -> CaptureInserter.findTarget(doc, location)
                is TargetLocation.Datetree -> CaptureInserter.findTarget(doc, location)
                else -> null
            }
            target?.let { CaptureInserter.outlinePath(doc, it) }
        }
    }

    /** Whether a Roam capture's resolved [path] already exists, so it appends rather than creates. */
    suspend fun roamFileExists(path: String): Boolean {
        val vault = vaultFlow.filterNotNull().first()
        return withContext(dispatchers.default) { vault.open(path) != null }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as GroveApplication
                return CaptureViewModel(
                    app.templatesRepository,
                    app.database,
                    app.syncManager,
                    app.vault,
                    app.settingsRepository,
                    app.dispatchers,
                ) as T
            }
        }
    }
}

class TemplatesViewModel(
    private val templatesRepository: TemplatesRepository,
    database: GroveDatabase,
    private val vaultFlow: StateFlow<Vault?>,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    /**
     * [fileName] parsed, for the editor's heading picker; null when there's
     * no vault yet or the file doesn't exist (a template may target a file
     * its first capture will create).
     */
    suspend fun loadDoc(fileName: String): OrgDocument? {
        if (fileName.isBlank()) return null
        val vault = vaultFlow.value ?: return null
        // A half-typed name can be invalid; that just means "no file" here.
        return withContext(dispatchers.default) { runCatching { vault.open(fileName) }.getOrNull() }
    }

    val templates: StateFlow<List<CaptureTemplate>> = templatesRepository.templates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Existing vault notebook file names, for the target-file picker dropdown. */
    val notebooks: StateFlow<List<String>> = database.indexDao().notebooksFlow()
        .map { list -> list.map { it.fileName }.sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Existing vault directories, for a Roam template's directory field. */
    val directories: StateFlow<List<String>> = database.indexDao().notebooksFlow()
        .map { list ->
            allFolderDirs(list.map { NotebookItem(it.fileName, 0, 0, false) }).sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun upsert(template: CaptureTemplate) =
        viewModelScope.launch { templatesRepository.upsert(template) }

    fun delete(id: String) = viewModelScope.launch { templatesRepository.delete(id) }

    fun move(id: String, delta: Int) =
        viewModelScope.launch { templatesRepository.move(id, delta) }

    fun newId(): String = TemplatesRepository.newId()

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as GroveApplication
                return TemplatesViewModel(app.templatesRepository, app.database, app.vault, app.dispatchers) as T
            }
        }
    }
}
