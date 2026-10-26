package com.rrajath.grove.ui.vault

import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewmodel.CreationExtras
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.NoteKey
import com.rrajath.grove.data.matches
import com.rrajath.grove.org.ArchiveLocation
import com.rrajath.grove.org.ArchiveTarget
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.org.OrgLinkParser
import com.rrajath.grove.org.OrgLinkTarget
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.markedDoneMessage
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.org.nextRepeatOccurrence
import com.rrajath.grove.settings.NotebookDisplayNameMode
import com.rrajath.grove.settings.PinKind
import com.rrajath.grove.settings.NotebookSortKey
import com.rrajath.grove.settings.PinnedItem
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.sync.SyncState
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.ui.util.pluralCount
import com.rrajath.grove.vault.AutoArchive
import com.rrajath.grove.vault.Notebook
import com.rrajath.grove.vault.StateChangeResult
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.vault.matchOpenedFileToNotebook
import com.rrajath.grove.vault.vaultPath
import java.time.LocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The vault folder's own name, from its SAF tree URI — e.g. a URI whose tree
 * document id is `primary:Documents/org` yields `org`. Used as the breadcrumb
 * root in the drill-down view; falls back to "Notebooks" when there's no vault
 * or the id can't be read.
 */
private fun vaultDisplayName(treeUri: String?): String {
    if (treeUri.isNullOrEmpty()) return "Notebooks"
    return runCatching {
        DocumentsContract.getTreeDocumentId(treeUri.toUri())
            .substringAfterLast('/')
            .substringAfterLast(':')
            .ifEmpty { "Notebooks" }
    }.getOrDefault("Notebooks")
}

data class NotebookItem(
    /** Vault-relative path with `/` separators, e.g. `projects/clients/acme.org`. */
    val fileName: String,
    val noteCount: Int,
    val lastModified: Long,
    val hasConflict: Boolean,
    /** User-chosen monogram palette key; null = derive one from the file name. */
    val color: String? = null,
    /** Position in the pinned list (0 = topmost). -1 means not pinned. */
    val pinnedIndex: Int = -1,
    /** Label to show in the notebooks list: the base file name or the cached `#+TITLE:`. */
    val displayName: String = fileName,
    /** False while this is a discovery stub whose content hasn't been parsed yet. */
    val isIndexed: Boolean = true,
) {
    val isPinned: Boolean get() = pinnedIndex >= 0

    /** Parent directory of [fileName], or `""` for a root-level file. */
    val dir: String get() = fileName.substringBeforeLast('/', "")
}

/** One row of the unified Pinned strip, in chronological pin order. */
sealed interface PinnedRow {
    data class File(val item: NotebookItem) : PinnedRow
    data class Folder(val node: FolderNode) : PinnedRow
}

sealed class NotebooksUiState {
    data object NoVault : NotebooksUiState()

    @Immutable
    data class Loaded(
        /** Every notebook, flat and sorted — for the top-bar sync icon and the row dialogs. */
        val notebooks: ImmutableList<NotebookItem>,
        /** Pinned notebooks, pin order; kept for the row dialogs / callers that only want files. */
        val pinned: ImmutableList<NotebookItem> = persistentListOf(),
        /**
         * The Pinned strip: pinned files and folders interleaved in the single
         * chronological pin order. Rendered as one block above the tree.
         */
        val pinnedStrip: ImmutableList<PinnedRow> = persistentListOf(),
        /**
         * The pins that resolve to something on screen, in strip order: what Move
         * up/down steps through, so a stale pin never swallows a move.
         */
        val livePins: ImmutableList<PinnedItem> = persistentListOf(),
        /** The inline tree (variant 1a), flattened to display rows for the current expansion. */
        val rows: ImmutableList<NotebookTreeRow> = persistentListOf(),
        /** Pinned folders, pin order; a pinned folder is shown ONLY in the strip, not in [rows]. */
        val pinnedFolders: ImmutableList<FolderNode> = persistentListOf(),
        /**
         * For each currently-expanded pinned folder, the display rows of its
         * subtree (depths shifted to indent under its flush strip row). Lets a
         * pinned folder expand in place in the strip; empty for collapsed ones.
         */
        val pinnedFolderExpansions: ImmutableMap<String, ImmutableList<NotebookTreeRow>> = persistentMapOf(),
        /** Directory paths currently expanded — lets the pinned strip's folder rows share the tree's caret state. */
        val expandedFolders: ImmutableSet<String> = persistentSetOf(),
        /** Per-folder icon-colour overrides, so the drill-down view tints its folder tiles like the tree. */
        val folderColors: ImmutableMap<String, String> = persistentMapOf(),
        /** True when the tree contains at least one folder (gates the expand/collapse-all button). */
        val hasFolders: Boolean = false,
        /** True when no folder is expanded (picks the expand-all vs collapse-all icon). */
        val allFoldersCollapsed: Boolean = true,
        /** The vault folder's own display name — the breadcrumb root in the drill-down view (1b). */
        val vaultDisplayName: String = "Notebooks",
        val syncState: SyncState = SyncState.Idle,
        val lastSyncAt: Long? = null,
        /** Reminders waiting on POST_NOTIFICATIONS/exact-alarm access (permission banner). */
        val remindersPendingPermission: Int = 0,
        /** Settings § Notebooks toggle: draw the per-file icon tile on each row. */
        val showFileIcons: Boolean = true,
        /** Settings § Notebooks toggle: render [flatRows]/[flatPinned] instead of the tree. */
        val flat: Boolean = false,
        /** Flat mode only: every file as a path-grouped row (pinned content excluded). */
        val flatRows: ImmutableList<NotebookItem> = persistentListOf(),
        /** Flat mode only: the Pinned strip as file rows (pinned files + pinned folders' files inline). */
        val flatPinned: ImmutableList<NotebookItem> = persistentListOf(),
        /** Settings § Notebooks sort order — for the drill-down view, which re-sorts one folder's rows. */
        val sort: NotebookSort = NotebookSort.DEFAULT,
        /**
         * Recursive `.org` count above which a folder row drills instead of
         * expanding. Always [FOLDER_DRILL_THRESHOLD] in release; debug builds can
         * lower it from Settings › Developer tools (see [folderDrillThresholdOverride]).
         */
        val drillThreshold: Int = FOLDER_DRILL_THRESHOLD,
    ) : NotebooksUiState()
}

/**
 * One-shot outcome of a notebook/folder create-or-rename, surfaced to
 * `NotebooksScreen` so it can dismiss the name dialog on success, or toast and
 * keep the dialog mounted (with the user's typed text) when the name collides.
 */
sealed interface NotebookEditEvent {
    /** The create/rename went through; the screen dismisses the open name dialog. */
    data object Succeeded : NotebookEditEvent
    /** [message] names the collision; the screen toasts it and leaves the dialog open. */
    data class NameTaken(val message: String) : NotebookEditEvent
}

class NotebooksViewModel(
    private val vaultFlow: StateFlow<Vault?>,
    private val database: GroveDatabase,
    private val settingsRepository: SettingsRepository,
    private val sync: SyncTrigger,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    // One-shot create/rename outcomes for the name dialogs (see NotebookEditEvent).
    private val _editEvents = MutableSharedFlow<NotebookEditEvent>(extraBufferCapacity = 1)
    val editEvents: SharedFlow<NotebookEditEvent> = _editEvents

    // Folder expansion, in-memory source of truth (PERFORMANCE_AUDIT_2026-09-16
    // D1): a folder row used to wait on a DataStore write (read-modify-write +
    // fsync) landing and the settings flow re-emitting before it could move.
    // Seeded once from the persisted value in init; the DataStore write below
    // is write-behind and debounced, so it never gates the tap-to-move.
    private val expandedFoldersFlow = MutableStateFlow<Set<String>?>(null)
    private val expandedFoldersToPersist =
        MutableSharedFlow<Set<String>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private data class TreeInputs(
        val items: List<NotebookItem>,
        val showFileIcons: Boolean,
        /** Settings § Notebooks: render one flat file list instead of the folder tree. */
        val flattenFolders: Boolean,
        val expandedFolders: Set<String>,
        val vaultDisplayName: String,
        val folderColors: Map<String, String>,
        val pinnedFolders: List<String>,
        /** The single ordered pin list (files + folders) that drives the strip. */
        val pinnedItems: List<PinnedItem>,
        /** Settings § Notebooks sort order; never reorders pinned notebooks/folders. */
        val sort: NotebookSort,
    )

    // Built separately from the sync banner inputs: syncManager.state ticks once
    // per pulled file during a sync, and must not re-map/re-group the whole tree.
    private val treeInputs = combine(
        vaultFlow,
        database.indexDao().notebooksFlow(),
        settingsRepository.settings,
        expandedFoldersFlow.filterNotNull(),
    ) { vault, notebooks, settings, expandedFolders ->
        if (vault == null) return@combine null
        val items = notebooks.map {
            NotebookItem(
                fileName = it.fileName,
                noteCount = it.noteCount,
                lastModified = it.lastModified,
                hasConflict = it.conflictFileName != null,
                color = settings.notebookColors[it.fileName],
                pinnedIndex = settings.pinnedNotebooks.indexOf(it.fileName),
                displayName = if (
                    settings.notebookDisplayNameMode == NotebookDisplayNameMode.TITLE &&
                    !it.title.isNullOrBlank()
                ) it.title else it.fileName.substringAfterLast('/'),
                isIndexed = it.isIndexed,
            )
        }
        TreeInputs(
            items,
            settings.showNotebookFileIcons,
            settings.flattenNotebookFolders,
            expandedFolders,
            vaultDisplayName(settings.vaultTreeUri),
            settings.folderColors,
            settings.pinnedFolders,
            settings.pinnedItems,
            NotebookSort(settings.notebookSortKey, settings.notebookSortAscending),
        )
    }.distinctUntilChanged()

    // The built tree, rebuilt only when the tree inputs actually change — and on
    // Dispatchers.Default, never the main thread. Keeping this out of the combine
    // below matters: syncManager.state ticks once per pulled file, so folding the
    // build into that combine re-grouped and re-sorted the whole vault once per
    // file of every sync (and did it on viewModelScope's Main.immediate).
    private val builtTree: Flow<NotebooksUiState.Loaded?> = treeInputs
        .map { inputs -> inputs?.let { buildLoaded(it) } }
        .flowOn(dispatchers.default)

    val state: StateFlow<NotebooksUiState> = combine(
        builtTree,
        sync.state,
        sync.lastResult,
        database.reminderDao().pendingCountFlow(System.currentTimeMillis()),
        folderDrillThresholdOverride,
    ) { loaded, syncState, lastResult, remindersPending, drillThreshold ->
        // Per-tick work is one shallow copy: the lists inside are shared, not rebuilt.
        loaded?.copy(
            syncState = syncState,
            lastSyncAt = lastResult?.completedAt,
            remindersPendingPermission = remindersPending,
            drillThreshold = drillThreshold,
        ) ?: NotebooksUiState.NoVault
        // WhileSubscribed, not Eagerly: this is the nav start destination, so its
        // NavBackStackEntry (and this ViewModel) outlives every screen the user
        // opens on top. Eagerly kept the whole tree pipeline — the deep
        // distinctUntilChanged over TreeInputs, buildLoaded — recomputing per
        // pulled file during background syncs the user never sees. The 5s stop
        // timeout still covers rotation and quick navigation.
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebooksUiState.NoVault)

    /**
     * Group, sort and flatten one [TreeInputs] snapshot into the screen's state.
     * The sync-derived fields ([NotebooksUiState.Loaded.syncState],
     * `lastSyncAt`, `remindersPendingPermission`) are left at their defaults and
     * filled in by the combine above.
     */
    private fun buildLoaded(inputs: TreeInputs): NotebooksUiState.Loaded {
        return if (inputs.flattenFolders) {
            // Flat mode: no tree, no drill-down, no strip folders — every file is a
            // path-subtitled row. A pinned folder contributes its files to the strip
            // inline (flatPinnedRows), so its subtree is pulled from flatRows.
            NotebooksUiState.Loaded(
                notebooks = inputs.items.sortedWith(
                    compareBy<NotebookItem> { if (it.isPinned) it.pinnedIndex else Int.MAX_VALUE }
                        .then(inputs.sort.fileComparator)
                ).toImmutableList(),
                pinned = inputs.items.filter { it.isPinned }.sortedBy { it.pinnedIndex }.toImmutableList(),
                flat = true,
                flatRows = flatNotebookRows(inputs.items, inputs.pinnedItems, inputs.sort),
                flatPinned = flatPinnedRows(inputs.items, inputs.pinnedItems, inputs.sort),
                livePins = inputs.pinnedItems.filter { pi ->
                    when (pi.kind) {
                        PinKind.FILE -> inputs.items.any { it.fileName == pi.path }
                        PinKind.FOLDER -> inputs.items.any { it.dir == pi.path || it.dir.startsWith("${pi.path}/") }
                    }
                }.toImmutableList(),
                folderColors = inputs.folderColors.toImmutableMap(),
                hasFolders = false,
                vaultDisplayName = inputs.vaultDisplayName,
                showFileIcons = inputs.showFileIcons,
                sort = inputs.sort,
            )
        } else {
            val flat = inputs.items.sortedWith(
                compareBy<NotebookItem> { if (it.isPinned) it.pinnedIndex else Int.MAX_VALUE }
                    .then(inputs.sort.fileComparator)
            ).toImmutableList()
            val pinned = inputs.items.filter { it.isPinned }.sortedBy { it.pinnedIndex }.toImmutableList()
            // Folder nodes for the strip come from the full item list (a folder can
            // hold nothing but pinned files and still deserves a strip row).
            val stripNodes = buildFolderNodes(inputs.items, inputs.folderColors, inputs.pinnedFolders)
            val pinnedFolderNodeList = pinnedFolderNodes(
                inputs.items, inputs.folderColors, inputs.pinnedFolders, prebuiltNodes = stripNodes,
            )
            val pinnedFileByPath = pinned.associateBy { it.fileName }
            val pinnedFolderByDir = pinnedFolderNodeList.associateBy { it.dir }
            // The single chronological strip: resolve each pin token to its live row.
            val pinnedStrip = inputs.pinnedItems.mapNotNull { pi ->
                when (pi.kind) {
                    PinKind.FILE -> pinnedFileByPath[pi.path]?.let { PinnedRow.File(it) }
                    PinKind.FOLDER -> pinnedFolderByDir[pi.path]?.let { PinnedRow.Folder(it) }
                }
            }.toImmutableList()
            val pinnedPaths = pinned.mapTo(mutableSetOf()) { it.fileName }
            val treeItems = inputs.items.filterNot { it.fileName in pinnedPaths }
            // Second (and last) node map: the tree and the strip's expand-in-place
            // subtrees are built over treeItems, whose recursive counts must not
            // include the pinned files pulled out above.
            val treeNodes = buildFolderNodes(treeItems, inputs.folderColors, inputs.pinnedFolders)
            val pinnedFolderDirs = inputs.pinnedFolders.toSet()
            fun underPinnedFolder(dir: String) =
                pinnedFolderDirs.any { p -> dir == p || dir.startsWith("$p/") }
            // A pinned folder's subtree is excluded from the tree (it lives only in
            // the strip), so gate the expand/collapse-all affordance on what's left.
            // treeNodes' keys are exactly allFolderDirs(treeItems), by construction.
            val folderDirs = treeNodes.keys.filterNot { underPinnedFolder(it) }
            // Expand-in-place for the strip: only an expanded pinned folder carries
            // its subtree rows (a large one drills to 1b instead and never expands).
            val pinnedFolderExpansions = pinnedFolderNodeList
                .filter { it.dir in inputs.expandedFolders }
                .associate { node ->
                    node.dir to pinnedFolderSubtreeRows(
                        treeItems, node.dir, inputs.expandedFolders,
                        inputs.folderColors, inputs.pinnedFolders, inputs.sort,
                        prebuiltNodes = treeNodes,
                    )
                }.toImmutableMap()
            NotebooksUiState.Loaded(
                notebooks = flat,
                pinned = pinned,
                pinnedStrip = pinnedStrip,
                livePins = pinnedStrip.map {
                    when (it) {
                        is PinnedRow.File -> PinnedItem(PinKind.FILE, it.item.fileName)
                        is PinnedRow.Folder -> PinnedItem(PinKind.FOLDER, it.node.dir)
                    }
                }.toImmutableList(),
                rows =buildNotebookTree(
                    treeItems, inputs.expandedFolders, inputs.folderColors, inputs.pinnedFolders, inputs.sort,
                    prebuiltNodes = treeNodes,
                ),
                pinnedFolders = pinnedFolderNodeList,
                pinnedFolderExpansions = pinnedFolderExpansions,
                expandedFolders = inputs.expandedFolders.toImmutableSet(),
                folderColors = inputs.folderColors.toImmutableMap(),
                hasFolders = folderDirs.isNotEmpty(),
                allFoldersCollapsed = folderDirs.none { it in inputs.expandedFolders },
                vaultDisplayName = inputs.vaultDisplayName,
                showFileIcons = inputs.showFileIcons,
                sort = inputs.sort,
            )
        }
    }

    init {
        // Seed the in-memory expansion state once from whatever's persisted;
        // treeInputs won't emit until this lands (see expandedFoldersFlow.filterNotNull()).
        viewModelScope.launch {
            expandedFoldersFlow.value = settingsRepository.settings.first().expandedFolders
        }
        // Write-behind: coalesce a burst of taps (or one expand/collapse-all)
        // into a single DataStore write, well after the UI has already moved.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            expandedFoldersToPersist.debounce(300).collect { settingsRepository.setExpandedFolders(it) }
        }
        // First open of a vault: expand every folder that recursively contains a
        // SCHEDULED/DEADLINE note, collapse the rest, then never re-run (a vault
        // that starts fully collapsed reads as empty). Waits for the index to
        // hold something so a pre-first-sync launch doesn't stamp an empty set.
        viewModelScope.launch {
            if (settingsRepository.settings.first().notebooksTreeDefaultsApplied) return@launch
            database.indexDao().notebooksFlow().first { it.isNotEmpty() }
            val planned = database.indexDao().plannedNotes().first()
            val defaults = firstOpenExpandedDirs(planned.map { it.fileName })
            settingsRepository.applyNotebooksTreeDefaults(defaults)
            expandedFoldersFlow.value = defaults
        }
    }

    fun requestSync() = sync.requestSync("manual")

    /** Folder row tap: flip that folder's expansion state (persisted write-behind, see D1). */
    fun toggleFolder(dir: String) {
        val current = expandedFoldersFlow.value ?: return
        val updated = if (dir in current) current - dir else current + dir
        expandedFoldersFlow.value = updated
        expandedFoldersToPersist.tryEmit(updated)
    }

    /** Top-bar expand/collapse-all: [expand] every folder in the current tree, or none. */
    fun setAllFoldersExpanded(expand: Boolean) {
        val loaded = state.value as? NotebooksUiState.Loaded ?: return
        val dirs = if (expand) {
            val pinnedDirs = loaded.pinnedFolders.map { it.dir }
            allFolderDirs(loaded.notebooks.filterNot { it.isPinned })
                .filterNot { dir -> pinnedDirs.any { dir == it || dir.startsWith("$it/") } }
                .toSet()
        } else {
            emptySet()
        }
        expandedFoldersFlow.value = dirs
        expandedFoldersToPersist.tryEmit(dirs)
    }

    fun saveVaultUri(uri: String) {
        viewModelScope.launch {
            settingsRepository.setVaultTreeUri(uri)
            // Picking a folder from the empty-vault state also completes onboarding;
            // stamp the current build as seen in the same write so the What's New
            // modal doesn't fire on this first run (see setOnboardingDone).
            settingsRepository.setOnboardingDone(true, com.rrajath.grove.BuildConfig.VERSION_CODE)
        }
    }

    /**
     * Create an empty notebook. [dir] is a vault-relative directory ("" = root);
     * the drill-down view passes the folder currently being browsed. [name] may
     * itself carry `/` segments, which [Vault.createNotebook] appends under [dir],
     * creating any missing folders.
     */
    fun createNotebook(name: String, dir: String = "") {
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            if (vault.createNotebook(name.trim(), dir.trim('/'))) {
                _editEvents.emit(NotebookEditEvent.Succeeded)
                sync.requestSync("notebook created")
            } else {
                _editEvents.emit(
                    NotebookEditEvent.NameTaken("A notebook with that name already exists")
                )
            }
        }
    }

    /**
     * Move a notebook into [newDir] (a vault-relative directory, "" = root),
     * keeping its file name. Re-keys the monogram colour and pin position onto
     * the new path via [com.rrajath.grove.settings.SettingsRepository.moveNotebookStyle]
     * and drops the stale index row so the next sync re-discovers it in place.
     */
    fun moveNotebook(path: String, newDir: String) {
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val newPath = vault.moveNotebook(path, newDir.trim('/'))
            if (newPath != null) {
                database.indexDao().removeNotebook(path)
                settingsRepository.moveNotebookStyle(path, newPath)
                sync.requestSync("notebook moved")
            }
        }
    }

    fun renameNotebook(oldName: String, newName: String) {
        val vault = vaultFlow.value ?: return
        val target = newName.trim().let { if (it.endsWith(".org")) it else "$it.org" }
        if (target == oldName) {
            // Confirmed without changing the name — nothing to do, just close.
            viewModelScope.launch { _editEvents.emit(NotebookEditEvent.Succeeded) }
            return
        }
        viewModelScope.launch {
            if (vault.renameNotebook(oldName, newName.trim())) {
                database.indexDao().removeNotebook(oldName)
                settingsRepository.moveNotebookStyle(oldName, target)
                _editEvents.emit(NotebookEditEvent.Succeeded)
            } else {
                _editEvents.emit(
                    NotebookEditEvent.NameTaken("A notebook with that name already exists")
                )
            }
            sync.requestSync("notebook renamed")
        }
    }

    fun deleteNotebook(name: String) {
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            if (vault.deleteNotebook(name)) {
                database.indexDao().removeNotebook(name)
            }
            sync.requestSync("notebook deleted")
        }
    }

    fun setNotebookColor(fileName: String, colorKey: String) {
        viewModelScope.launch { settingsRepository.setNotebookColor(fileName, colorKey) }
    }

    fun forceReload(name: String) {
        viewModelScope.launch { sync.forceReload(name) }
    }

    fun pinNotebook(fileName: String) {
        viewModelScope.launch { settingsRepository.pinNotebook(fileName) }
    }

    fun unpinNotebook(fileName: String) {
        viewModelScope.launch { settingsRepository.unpinNotebook(fileName) }
    }

    fun pinFolder(dir: String) {
        viewModelScope.launch { settingsRepository.pinFolder(dir) }
    }

    fun unpinFolder(dir: String) {
        viewModelScope.launch { settingsRepository.unpinFolder(dir) }
    }

    /** Swap [item] with its visible neighbour [delta] slots away in the Pinned strip (-1 up, +1 down). */
    fun movePin(item: PinnedItem, delta: Int) {
        val pins = (state.value as? NotebooksUiState.Loaded)?.livePins ?: return
        val from = pins.indexOf(item)
        val neighbor = if (from < 0) null else pins.getOrNull(from + delta)
        if (neighbor != null) viewModelScope.launch { settingsRepository.swapPinnedItems(item, neighbor) }
    }

    fun setFolderColor(dir: String, colorKey: String) {
        viewModelScope.launch { settingsRepository.setFolderColor(dir, colorKey) }
    }

    /**
     * Rename [dir] in place (keeping its parent). Moves every descendant `.org`
     * file, drops their stale index rows, re-keys the folder's (and every
     * descendant's) icon colour + pin state, and requests a sync.
     */
    fun renameFolder(dir: String, newName: String) {
        val vault = vaultFlow.value ?: return
        val trimmed = dir.trim('/')
        val newDir = vaultPath(trimmed.substringBeforeLast('/', ""), newName.trim().trim('/'))
        if (newDir == trimmed) {
            // Confirmed without changing the name — nothing to do, just close.
            viewModelScope.launch { _editEvents.emit(NotebookEditEvent.Succeeded) }
            return
        }
        viewModelScope.launch {
            val affected = affectedPaths(dir)
            val renamedTo = vault.renameFolder(dir, newName)
            if (renamedTo != null) {
                affected.forEach { database.indexDao().removeNotebook(it) }
                settingsRepository.renameFolderStyle(dir, renamedTo)
                _editEvents.emit(NotebookEditEvent.Succeeded)
                sync.requestSync("folder renamed")
            } else {
                _editEvents.emit(
                    NotebookEditEvent.NameTaken("A folder with that name already exists")
                )
            }
        }
    }

    /**
     * Permanently delete every `.org` file under [dir], drop their index rows,
     * clear the folder's icon colour + pin state, and request a sync.
     */
    fun deleteFolder(dir: String) {
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val affected = affectedPaths(dir)
            if (vault.deleteFolder(dir) > 0) {
                affected.forEach { database.indexDao().removeNotebook(it) }
                settingsRepository.deleteFolderStyle(dir)
                sync.requestSync("folder deleted")
            }
        }
    }

    /** Vault-relative paths of the notebooks currently indexed under [dir]. */
    private fun affectedPaths(dir: String): List<String> =
        (state.value as? NotebooksUiState.Loaded)?.notebooks
            ?.map { it.fileName }
            ?.filter { it.startsWith("$dir/") }
            .orEmpty()

    companion object {
        val Factory = factory {
            NotebooksViewModel(it.vault, it.database, it.settingsRepository, it.syncManager, it.dispatchers)
        }
    }
}

sealed class DocumentUiState {
    data object Loading : DocumentUiState()
    data class Loaded(
        val fileName: String,
        val document: OrgDocument,
        /**
         * Set by an outline edit that knows where every heading went: carries a
         * heading line of the previous document to its line in [document] (null
         * once deleted), so the outline's collapsed set follows the headings.
         */
        val lineRemap: ((Int) -> Int?)? = null,
    ) : DocumentUiState()
    data class Error(val message: String) : DocumentUiState()
}

/** Transient bottom-center pill message (design spec: ~1.9s). */
data class OutlineToast(val message: String, val id: Long)

/** Undoable-operation snackbar (design spec: ~4.2s with an UNDO action). */
data class OutlineSnack(val message: String, val id: Long)

/** Outcome of resolving a tapped org link; see [DocumentViewModel.openOrgLink]. */
sealed interface LinkResolution {
    /** Open Read mode of a heading (in this file or another). */
    data class Note(val ref: NoteRef) : LinkResolution
    /** Open a whole file's outline. */
    data class Outline(val fileName: String) : LinkResolution
    /** Hand a URI to the OS (`http`, `mailto`, `tel`, …). */
    data class External(val uri: String) : LinkResolution
    /** Nothing matched; [label] is shown to the user. */
    data class NotFound(val label: String) : LinkResolution
}

/** One step of refile-picker drill-down state (design spec Gestures screen). */
@Immutable
data class RefileNotebook(val fileName: String, val noteCount: Int)

data class RefileUiState(
    /** Line index of the headline being refiled (in the current document). */
    val sourceLine: Int,
    /**
     * Every headline being refiled, in document order: just [sourceLine], or a
     * bulk selection's top-level headlines (see [OrgMutations.selectedRoots]).
     */
    val sourceLines: List<Int> = listOf(sourceLine),
    /** Null while the notebook list is loading. */
    val notebooks: ImmutableList<RefileNotebook>? = null,
    val pickedFile: String? = null,
    val pickedDoc: OrgDocument? = null,
    /** Drill-down trail of headline lineIndexes inside [pickedDoc]; empty = top level. */
    val path: ImmutableList<Int> = persistentListOf(),
    /** Effective `ARCHIVE` target for the source headline (nearest-ancestor-wins), if any. */
    val archiveTarget: ArchiveTarget? = null,
    /** Destination of the most recent successful refile, if any. */
    val lastUsedTarget: ArchiveTarget? = null,
)

/**
 * A note editor's unsaved buffer, handed to [DocumentViewModel] while read mode is
 * showing the very note the editor holds. Read mode then renders what was typed
 * rather than what is on disk, which is what makes the Read/Edit toggle free of
 * any write of its own (Settings § Notes → Auto-save notes).
 *
 * @param lineIndex the edited headline's line in the on-disk file.
 * @param text the editor's current subtree text for that headline.
 * @param wholeFile [text] is the whole file (Dailies' whole-file editor), not one
 *   subtree; [lineIndex] is then ignored and read mode renders [text] outright.
 */
data class PendingEdit(val fileName: String, val lineIndex: Int, val text: String, val wholeFile: Boolean = false)

class DocumentViewModel(
    private val vaultFlow: StateFlow<Vault?>,
    private val keywordsFlow: StateFlow<OrgKeywords>,
    private val sync: SyncTrigger,
    private val database: GroveDatabase,
    private val settingsRepository: SettingsRepository,
    private val favoritesRepository: FavoritesRepository,
    private val dispatchers: AppDispatchers,
    private val app: GroveApplication,
) : ViewModel() {

    private val _state = MutableStateFlow<DocumentUiState>(DocumentUiState.Loading)
    val state: StateFlow<DocumentUiState> = _state

    private val _toast = MutableStateFlow<OutlineToast?>(null)
    val toast: StateFlow<OutlineToast?> = _toast

    private val _snack = MutableStateFlow<OutlineSnack?>(null)
    val snack: StateFlow<OutlineSnack?> = _snack

    /**
     * Headline lines the "Move & indent" command bar is acting on; empty = not in
     * focus mode. Each command applies to every selected headline in turn.
     */
    private val _selectedLines = MutableStateFlow<Set<Int>>(emptySet())
    val selectedLines: StateFlow<Set<Int>> = _selectedLines

    private val _refile = MutableStateFlow<RefileUiState?>(null)
    val refile: StateFlow<RefileUiState?> = _refile

    /** Tag autocomplete pool for the read-mode metadata sheet; refreshed on each [load]. */
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags

    /** Backlinks/mentions for the Backlinks bar+sheet; refreshed by [loadLinkedReferences]. */
    private val linkedRefs = LinkedReferencesLoader(viewModelScope, database, settingsRepository, dispatchers)
    val linkedReferences: StateFlow<LinkedReferencesResult> = linkedRefs.result

    /** See [LinkedReferencesLoader.load]. */
    fun loadLinkedReferences(fileName: String, lineIndex: Int, targetId: String?, title: String) =
        linkedRefs.load(fileName, lineIndex, targetId, title)

    /** Empties Backlinks (and cancels a pending load) for a target whose bar can't show. */
    fun clearLinkedReferences() = linkedRefs.clear()

    /**
     * Set to the line of the blank heading [withIntroHeading] just inserted, so
     * the read screen (currently showing the intro pseudo-note) can re-open it
     * as a real note. Cleared by [clearIntroPromoted] once consumed.
     */
    private val _introPromotedLine = MutableStateFlow<Int?>(null)
    val introPromotedLine: StateFlow<Int?> = _introPromotedLine

    fun clearIntroPromoted() { _introPromotedLine.value = null }

    private var eventId = 0L

    fun showToast(message: String) {
        val t = OutlineToast(message, ++eventId)
        _toast.value = t
        viewModelScope.launch {
            delay(1900)
            if (_toast.value?.id == t.id) _toast.value = null
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

    /** Enter focus mode with just [line] selected. */
    fun startSelection(line: Int) {
        _selectedLines.value = setOf(line)
    }

    /** Add or remove [line] from the selection; removing the last one leaves focus mode. */
    fun toggleSelected(line: Int) {
        _selectedLines.value = _selectedLines.value.let { if (line in it) it - line else it + line }
    }

    fun clearSelection() {
        _selectedLines.value = emptySet()
    }

    /** The open editor's unsaved buffer for this file, if any; see [setPendingEdit]. */
    private var pending: PendingEdit? = null
    private var onPendingBufferChanged: (String) -> Unit = {}
    private var onPendingPersisted: () -> Unit = {}

    /**
     * Hand this view model the note editor's unsaved buffer (null when there is
     * none). [onBufferChanged] receives the new subtree text whenever a read-mode
     * mutation is folded into that buffer instead of written to disk;
     * [onPersisted] fires when a mutation reshaped the file badly enough that the
     * buffer had to go to disk with it, so the editor should let go of it.
     */
    fun setPendingEdit(
        edit: PendingEdit?,
        onBufferChanged: (String) -> Unit = {},
        onPersisted: () -> Unit = {},
    ) {
        pending = edit
        onPendingBufferChanged = onBufferChanged
        onPendingPersisted = onPersisted
    }

    /** Splice the editor's unsaved subtree into a freshly read [doc], if it applies to it. */
    private suspend fun withPending(fileName: String, doc: OrgDocument): OrgDocument {
        val p = pending?.takeIf { it.fileName == fileName } ?: return doc
        return withContext(dispatchers.default) {
            if (p.wholeFile) return@withContext OrgParser.parse(p.text, doc.keywords)
            val headline = doc.headlines.firstOrNull { it.lineIndex == p.lineIndex }
                ?: return@withContext doc
            if (OrgMutations.subtreeText(doc, headline) == p.text) return@withContext doc
            OrgParser.parse(OrgMutations.replaceSubtree(doc, headline, p.text), doc.keywords)
        }
    }

    /**
     * Persist a mutation's [newText] — unless the note editor is holding unsaved
     * changes to a subtree of this same file, in which case the mutation is folded
     * back into that buffer and nothing reaches disk until the user saves. Only a
     * mutation that left the subtree where the editor expects it can be buffered;
     * one that restructured the file (a refile, delete or move) is written out as
     * it stands — its text already carries the unsaved edits, so that write *is*
     * the flush, and the editor is told to let its buffer go.
     */
    /**
     * [newDoc], when the caller already parsed [newText] to compute it (nearly
     * every mutation does), is handed to [Vault.save] to prime its cache under
     * the post-write key — the next load of this file (Read mode's
     * return-from-editor reload, Outline's return from a swipe) then hits the
     * cache instead of re-reading and re-parsing the file it just wrote.
     */
    private suspend fun saveDoc(fileName: String, newText: String, syncReason: String, newDoc: OrgDocument? = null) {
        val vault = vaultFlow.value ?: return
        val p = pending?.takeIf { it.fileName == fileName }
        if (p != null && p.wholeFile) {
            // The whole file is the buffer, so every mutation folds back into it.
            pending = p.copy(text = newText)
            onPendingBufferChanged(newText)
            return
        }
        if (p != null) {
            val subtree = withContext(dispatchers.default) {
                val doc = OrgParser.parse(newText, keywordsFlow.value)
                doc.headlines.firstOrNull { it.lineIndex == p.lineIndex }
                    ?.let { OrgMutations.subtreeText(doc, it) }
            }
            if (subtree != null) {
                pending = p.copy(text = subtree)
                onPendingBufferChanged(subtree)
                return
            }
            pending = null
            onPendingPersisted()
        }
        vault.save(fileName, newText, newDoc)
        sync.requestReindex(fileName, newText, syncReason)
    }

    /** Show an already-parsed [document] immediately, with no disk round-trip; a
     *  following [load] still refreshes it (and is a no-op if nothing changed). */
    fun show(fileName: String, document: OrgDocument) {
        dropSelectionIfChanged(fileName, document)
        _state.value = DocumentUiState.Loaded(fileName, document)
    }

    /**
     * The bulk selection is line indices into the document on screen; once the
     * file comes back from disk with different text (sync, another app), those
     * lines may name other headings, so clear it rather than let a command act
     * on the wrong ones.
     */
    private fun dropSelectionIfChanged(fileName: String, incoming: OrgDocument) {
        if (_selectedLines.value.isEmpty()) return
        val current = _state.value as? DocumentUiState.Loaded ?: return
        if (current.fileName != fileName || current.document.text == incoming.text) return
        _selectedLines.value = emptySet()
        showToast("Selection cleared: the file was synced or changed elsewhere")
    }

    fun load(fileName: String) {
        viewModelScope.launch {
            val vault = vaultFlow.value
            if (vault == null) {
                _state.value = DocumentUiState.Error("No sync folder configured")
                return@launch
            }
            _state.value = try {
                val doc = vault.open(fileName)
                if (doc == null) {
                    DocumentUiState.Error("$fileName not found")
                } else {
                    val spliced = withPending(fileName, doc)
                    val current = _state.value
                    if (current is DocumentUiState.Loaded && current.fileName == fileName && current.document === spliced) {
                        current
                    } else {
                        dropSelectionIfChanged(fileName, spliced)
                        DocumentUiState.Loaded(fileName, spliced)
                    }
                }
            } catch (e: Exception) {
                DocumentUiState.Error(e.message ?: "Could not open $fileName")
            }
        }
        viewModelScope.launch {
            _allTags.value = database.indexDao().allTagStrings()
                .flatMap { it.split(':') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
    }

    // --- org link navigation ---

    /**
     * Resolve a tapped org link ([rawTarget] is the string between `[[ ]]`, or a
     * bare URL) against the current document, the note index, and the vault, then
     * drive the matching navigation. [currentFile] is the file the link lives in
     * (relative `file:` links resolve against its directory). An `id:`, heading,
     * or `::#custom-id` target opens Read mode of that heading via [onOpenNote];
     * a whole-file target opens that file's outline via [onOpenOutline]; an
     * external scheme is handed to the OS; anything unresolved shows a toast.
     */
    fun openOrgLink(
        rawTarget: String,
        currentFile: String,
        onOpenNote: (NoteRef) -> Unit,
        onOpenOutline: (fileName: String) -> Unit,
    ) {
        val doc = (_state.value as? DocumentUiState.Loaded)?.document
        viewModelScope.launch {
            when (val resolution = resolveOrgLink(rawTarget, currentFile, doc)) {
                is LinkResolution.Note -> onOpenNote(resolution.ref)
                is LinkResolution.Outline -> onOpenOutline(resolution.fileName)
                is LinkResolution.External -> runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_VIEW, resolution.uri.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { showToast("Couldn't open $rawTarget") }
                is LinkResolution.NotFound -> showToast("Couldn't find “${resolution.label}”")
            }
        }
    }

    private suspend fun resolveOrgLink(
        rawTarget: String,
        currentFile: String,
        doc: OrgDocument?,
    ): LinkResolution {
        val dao = database.indexDao()
        val vault = vaultFlow.value

        fun noteIn(file: String, headline: OrgHeadline) =
            LinkResolution.Note(NoteRef(file, headline.lineIndex, headline.customId ?: headline.id))
        // [id] is the heading's :ID:/:CUSTOM_ID: when known, so the ref re-resolves
        // by id even if the indexed line has drifted (see OrgDocument.headlineFor).
        fun noteAt(key: NoteKey, id: String? = null) =
            LinkResolution.Note(NoteRef(key.fileName, key.lineIndex, id))

        return when (val t = OrgLinkParser.parse(rawTarget, currentFile)) {
            is OrgLinkTarget.External -> LinkResolution.External(t.uri)

            is OrgLinkTarget.Id -> {
                doc?.findById(t.id)?.let { return noteIn(currentFile, it) }
                // A file-level `:ID:` (the leading property drawer) targets the
                // whole file: land on its outline, the way `[[file:…]]` does.
                if (doc?.fileId == t.id) return LinkResolution.Outline(currentFile)
                dao.noteLocationByOrgId(t.id)?.let { return noteAt(it, t.id) }
                dao.notebookByOrgId(t.id)?.let { return LinkResolution.Outline(it) }
                LinkResolution.NotFound("id:${t.id}")
            }

            is OrgLinkTarget.CustomId -> {
                doc?.findByCustomId(t.customId)?.let { return noteIn(currentFile, it) }
                dao.noteLocationByCustomId(t.customId)?.let { return noteAt(it, t.customId) }
                LinkResolution.NotFound("#${t.customId}")
            }

            is OrgLinkTarget.Heading -> {
                doc?.let { findHeadlineByTitle(it, t.text) }?.let { return noteIn(currentFile, it) }
                LinkResolution.NotFound(t.text)
            }

            is OrgLinkTarget.File ->
                resolveNotebook(t.path, dao)?.let { LinkResolution.Outline(it) }
                    ?: LinkResolution.NotFound(t.path)

            is OrgLinkTarget.FileHeading -> {
                val file = resolveNotebook(t.path, dao) ?: return LinkResolution.NotFound(t.path)
                val headline = vault?.open(file)?.let { findHeadlineByTitle(it, t.text) }
                // The file resolved; if the heading is gone, its outline is the
                // best graceful landing rather than a dead end.
                if (headline != null) noteIn(file, headline) else LinkResolution.Outline(file)
            }

            is OrgLinkTarget.FileCustomId -> {
                val file = resolveNotebook(t.path, dao) ?: return LinkResolution.NotFound(t.path)
                val headline = vault?.open(file)?.findByCustomId(t.customId)
                if (headline != null) noteIn(file, headline) else LinkResolution.Outline(file)
            }

            is OrgLinkTarget.Fuzzy -> {
                // A no-scheme `[[Heading]]` link only ever targets a heading in
                // the current file (like `[[*Heading]]`, minus the leading star).
                doc?.let { findHeadlineByTitle(it, t.text) }?.let { return noteIn(currentFile, it) }
                LinkResolution.NotFound(t.text)
            }
        }
    }

    private fun findHeadlineByTitle(doc: OrgDocument, text: String): OrgHeadline? {
        val wanted = text.trim()
        return doc.findByTitle(wanted)
            ?: doc.headlines.firstOrNull { it.title.equals(wanted, ignoreCase = true) }
    }

    /** Match a link's file path to an indexed notebook (exact path, then basename). */
    private suspend fun resolveNotebook(path: String, dao: com.rrajath.grove.data.IndexDao): String? {
        val known = dao.notebooks().map { Notebook(it.fileName, it.noteCount, it.lastModified) }
        return matchOpenedFileToNotebook(path, known)?.fileName
    }

    // --- structural outline operations (PRD §5.3) ---

    /** Single-step undo: the pre-mutation text of every file the mutation touched. */
    private data class UndoSnapshot(val files: List<Pair<String, String>>)

    private var undoSnapshot: UndoSnapshot? = null

    fun undo() {
        val snap = undoSnapshot ?: return
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        undoSnapshot = null
        _snack.value = null
        // The focused line indexes the pre-undo document; don't let the
        // command bar act on whatever headline lands there after restore.
        _selectedLines.value = emptySet()
        // The snapshot predates whatever the editor is holding, so restoring it
        // would put the file back behind an unsaved buffer that could then clobber
        // it again; the editor lets go of the buffer instead.
        if (pending != null) {
            pending = null
            onPendingPersisted()
        }
        viewModelScope.launch {
            snap.files.forEach { (name, text) ->
                vault.save(name, text)
                sync.requestReindex(name, text, "undo")
            }
            snap.files.firstOrNull { it.first == loaded.fileName }?.let { (_, text) ->
                val doc = withContext(dispatchers.default) {
                    OrgParser.parse(text, loaded.document.keywords)
                }
                _state.value = DocumentUiState.Loaded(loaded.fileName, doc)
            }
            showToast("Undone")
        }
    }

    /**
     * Favoriting needs a stable `:ID:`/`:CUSTOM_ID:` on [headline] so the favorite survives
     * line drift from later edits; writes an `:ID:` only when it has neither already (never
     * overwrites an intentionally-set one). Grove used to write a UUID `:CUSTOM_ID:` here;
     * those are still honored when present. Goes through this class's own [_state] (like
     * every other mutation here) instead of a standalone vault read/write, so the id becomes
     * part of the same in-memory document lineage subsequent edits build from. Otherwise the
     * very next outline edit in this session would rebuild its saved text from a
     * pre-favorite snapshot and silently drop the id (the bug this was written to fix).
     * [onResolved] receives the heading's existing or newly-written id.
     */
    fun ensureStableId(headline: OrgHeadline, onResolved: (String?) -> Unit) {
        val existing = headline.id ?: headline.customId
        if (existing != null) {
            onResolved(existing)
            return
        }
        val loaded = _state.value as? DocumentUiState.Loaded ?: return onResolved(null)
        val vault = vaultFlow.value ?: return onResolved(null)
        viewModelScope.launch {
            val newId = newOrgId()
            val newText = withContext(dispatchers.default) {
                OrgMutations.upsertProperty(loaded.document, headline, "ID", newId)
            }
            val newDoc = withContext(dispatchers.default) {
                OrgParser.parse(newText, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "favorite added id", newDoc)
            onResolved(newId)
        }
    }

    /**
     * Read-mode metadata sheet: pin/unpin [headline] to the nav drawer's Favorites.
     * Adds resolve a stable `:ID:` first (via [ensureStableId]) so the favorite
     * survives external edits that shift line numbers.
     */
    fun toggleFavorite(headline: OrgHeadline) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val fileName = loaded.fileName
        viewModelScope.launch {
            val existing = favoritesRepository.favorites.first().firstOrNull { it.matches(headline) }
            if (existing != null) {
                favoritesRepository.removeFavorite(existing.fileName, existing.lineIndex, existing.customId)
                showToast("Removed favorite")
            } else {
                ensureStableId(headline) { customId ->
                    viewModelScope.launch {
                        favoritesRepository.addFavorite(
                            FavoriteNote(fileName, headline.lineIndex, headline.title, customId),
                        )
                    }
                    showToast("★ Added to favorites")
                }
            }
        }
    }

    /**
     * Favorite toggle for a file's heading-less intro. The favorite is keyed at
     * [INTRO_LINE_INDEX] (no `:CUSTOM_ID:`, since there's no heading to write one on),
     * so favoriting the intro never forces a blank heading onto the file.
     */
    fun toggleIntroFavorite() {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val doc = loaded.document
        val fileName = loaded.fileName
        if (!doc.hasIntro) return
        viewModelScope.launch {
            val existing = favoritesRepository.favorites.first()
                .firstOrNull { it.fileName == fileName && it.lineIndex == INTRO_LINE_INDEX }
            if (existing != null) {
                favoritesRepository.removeFavorite(fileName, INTRO_LINE_INDEX, null)
                showToast("Removed favorite")
            } else {
                val title = doc.introTitle.ifBlank { fileName.removeSuffix(".org") }
                favoritesRepository.addFavorite(FavoriteNote(fileName, INTRO_LINE_INDEX, title, null))
                showToast("★ Added to favorites")
            }
        }
    }

    /**
     * A metadata action was invoked on a file's intro, the heading-less content
     * before its first heading, which has
     * no headline to hang metadata on. In one atomic edit: insert a blank
     * top-level heading directly above the content (so the content becomes its
     * body), then apply [mutate] to that heading. [describe] is the metadata
     * action's own toast (shown alongside the "added a heading" snack).
     * Publishes the new heading's line via [introPromotedLine] so the read
     * screen can re-open it as a normal note. Undoable in a single step.
     */
    fun withIntroHeading(describe: String, mutate: (OrgDocument, OrgHeadline) -> String) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        if (!loaded.document.hasIntro) return
        viewModelScope.launch {
            val (finalText, newLine, finalDoc) = withContext(dispatchers.default) {
                val (wrapped, line) = OrgMutations.wrapIntroInHeading(loaded.document)
                val wrappedDoc = OrgParser.parse(wrapped, loaded.document.keywords)
                val h = wrappedDoc.headlineAtLine(line)
                    ?: return@withContext Triple(wrapped, line, wrappedDoc)
                val text = mutate(wrappedDoc, h)
                Triple(text, line, OrgParser.parse(text, loaded.document.keywords))
            }
            undoSnapshot = UndoSnapshot(listOf(loaded.fileName to loaded.document.text))
            _state.value = DocumentUiState.Loaded(loaded.fileName, finalDoc)
            saveDoc(loaded.fileName, finalText, "intro promoted to heading", finalDoc)
            showSnack("Added a blank heading for this content")
            if (describe.isNotEmpty()) showToast(describe)
            _introPromotedLine.value = newLine
        }
    }

    /**
     * Apply an undoable structural edit to each heading at [lines] (see
     * [OrgMutations.applyToEach]): one undo snapshot for the whole batch, publish
     * the in-memory parse immediately, persist and sync in the background. The
     * selection follows the headings to their new lines.
     */
    private fun applyStructure(
        lines: Set<Int>,
        op: OrgMutations.StructureOp,
        verb: String,
        blockedToast: String,
        singleMessage: String = verb,
    ) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val result = withContext(dispatchers.default) {
                OrgMutations.applyToEach(loaded.document, lines, op)
            }
            if (result == null) {
                showToast(blockedToast)
                return@launch
            }
            if (op == OrgMutations.StructureOp.DELETE) {
                val doc = loaded.document
                lines.mapNotNull(doc::headlineAtLine).forEach { h ->
                    dropFavoritesInRange(doc, loaded.fileName, h.lineIndex until doc.subtreeEndLine(h))
                }
            }
            undoSnapshot = UndoSnapshot(listOf(loaded.fileName to loaded.document.text))
            _selectedLines.value = _selectedLines.value.mapNotNull(result.remap).toSet()
            _state.value = DocumentUiState.Loaded(loaded.fileName, result.doc, result.remap)
            saveDoc(loaded.fileName, result.text, "outline edit", result.doc)
            showSnack(
                when {
                    result.attempted == 1 -> singleMessage
                    result.applied == result.attempted -> "$verb ${result.applied} notes"
                    else -> "$verb ${result.applied} of ${result.attempted} notes"
                }
            )
        }
    }

    fun moveUp(headline: OrgHeadline) = moveUp(setOf(headline.lineIndex))
    fun moveDown(headline: OrgHeadline) = moveDown(setOf(headline.lineIndex))
    fun promote(headline: OrgHeadline) = promote(setOf(headline.lineIndex))
    fun demote(headline: OrgHeadline) = demote(setOf(headline.lineIndex))
    fun deleteNote(headline: OrgHeadline) = deleteNotes(setOf(headline.lineIndex))

    fun moveUp(lines: Set<Int>) =
        applyStructure(lines, OrgMutations.StructureOp.MOVE_UP, "Moved up", "Can't move further")

    fun moveDown(lines: Set<Int>) =
        applyStructure(lines, OrgMutations.StructureOp.MOVE_DOWN, "Moved down", "Can't move further")

    fun promote(lines: Set<Int>) =
        applyStructure(lines, OrgMutations.StructureOp.PROMOTE, "Promoted", "Already top level")

    fun demote(lines: Set<Int>) = applyStructure(
        lines,
        OrgMutations.StructureOp.DEMOTE,
        "Demoted",
        "Can't demote: there's no heading above ${if (lines.size > 1) "them" else "it"} to go under",
    )

    fun deleteNotes(lines: Set<Int>) =
        applyStructure(lines, OrgMutations.StructureOp.DELETE, "Deleted", "", singleMessage = "Note deleted")

    /**
     * Same-session cleanup for an in-app delete/refile: a favorite whose *current* position
     * falls in the removed subtree's range is dropped. [document] is the pre-edit document
     * (the mutation hasn't been applied to [_state] yet at the call sites below), so a
     * favorite's stable customId still resolves against it even though the subtree is about to
     * be deleted/moved out of the file. Resolving by customId here — instead of trusting the
     * favorite's stored lineIndex directly — matters because that stored value is only a
     * snapshot from whenever the favorite was added: any unrelated edit elsewhere in the file
     * since then (adding/removing lines above it) shifts the note's true line without ever
     * updating the stored one, so a stale lineIndex can coincidentally land inside a range being
     * removed even though the favorited note itself isn't part of it. Favorites with no
     * customId (pre-id favorites) fall back to the raw stored lineIndex, same as elsewhere.
     */
    private fun dropFavoritesInRange(document: OrgDocument, fileName: String, range: IntRange) {
        viewModelScope.launch {
            favoritesRepository.favorites.first()
                .filter { it.fileName == fileName }
                .filter { fav ->
                    val currentLine = fav.customId
                        ?.let { document.findByCustomId(it) ?: document.findById(it) }
                        ?.lineIndex
                        ?: fav.lineIndex
                    currentLine in range
                }
                .forEach { favoritesRepository.removeFavorite(it.fileName, it.lineIndex, it.customId) }
        }
    }

    /** Create a blank child note and report its line index so the caller can open the editor. */
    fun newChild(headline: OrgHeadline, onCreated: (Int) -> Unit) = newNote(onCreated) { doc, options ->
        OrgMutations.newChild(doc, headline, "", options)
    }

    /** Insert menu: blank sibling note immediately above [headline] (same level). */
    fun insertSiblingAbove(headline: OrgHeadline, onCreated: (Int) -> Unit) = newNote(onCreated) { doc, options ->
        OrgMutations.insertSiblingAbove(doc, headline, "", options)
    }

    /** Insert menu: blank sibling note immediately below [headline]'s subtree (same level). */
    fun insertSiblingBelow(headline: OrgHeadline, onCreated: (Int) -> Unit) = newNote(onCreated) { doc, options ->
        OrgMutations.insertSiblingBelow(doc, headline, "", options)
    }

    /** Outline FAB: add a blank top-level note to this notebook (PRD §5.3). */
    fun newTopLevelNote(onCreated: (Int) -> Unit) = newNote(onCreated) { doc, options ->
        OrgMutations.newTopLevel(doc, "", options)
    }

    private fun newNote(
        onCreated: (Int) -> Unit,
        insert: (OrgDocument, OrgMutations.NewNoteOptions) -> Pair<String, Int>,
    ) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val (newText, lineIndex) = insert(
                loaded.document,
                OrgMutations.NewNoteOptions(
                    id = if (settings.addIdToNewNotes) newOrgId() else null,
                    createdAt = if (settings.addCreatedToNewNotes) LocalDateTime.now() else null,
                ),
            )
            val newDoc = withContext(dispatchers.default) {
                OrgParser.parse(newText, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "note added", newDoc)
            onCreated(lineIndex)
        }
    }

    /**
     * Swipe-right quick action: set the TODO state to exactly the keyword the
     * user picked from the state sheet (null = clear it). Picking a done-type
     * keyword applies full org-todo "mark done" semantics: a repeating
     * SCHEDULED/DEADLINE advances instead of closing, otherwise a CLOSED stamp
     * is written; leaving a done-type keyword (including clearing it to none)
     * drops that stamp, via [OrgMutations.changeKeyword], so marking a task
     * done/reopening it from the outline behaves the same as from the metadata
     * sheet, Search's state sheet, or the agenda swipe.
     */
    fun setState(headline: OrgHeadline, keyword: String?) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        if (headline.keyword == keyword) return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            when (
                val result = AutoArchive.apply(
                    vault, settings, loaded.document, loaded.fileName, headline, keyword, LocalDateTime.now(),
                )
            ) {
                is StateChangeResult.Plain -> {
                    _state.value = DocumentUiState.Loaded(loaded.fileName, result.doc)
                    saveDoc(loaded.fileName, result.text, "state set", result.doc)
                    // A recurring task keeps its keyword and just moves its date, so say where it went.
                    val next = result.doc.headlines.firstOrNull { it.lineIndex == headline.lineIndex }
                        ?.let { nextRepeatOccurrence(headline, it) }
                    if (next != null) {
                        undoSnapshot = UndoSnapshot(listOf(loaded.fileName to loaded.document.text))
                        showSnack(markedDoneMessage(next, LocalDate.now()))
                    } else {
                        showToast("State → ${keyword ?: "none"}")
                    }
                }
                is StateChangeResult.Archived -> {
                    undoSnapshot = UndoSnapshot(
                        if (result.sourceFile == result.destFile) {
                            listOf(result.sourceFile to loaded.document.text)
                        } else {
                            listOf(loaded.fileName to loaded.document.text, result.destFile to result.destTextBefore)
                        }
                    )
                    _selectedLines.value = emptySet()
                    _state.value = DocumentUiState.Loaded(loaded.fileName, result.sourceDoc)
                    if (result.destFile != loaded.fileName) vault.save(result.destFile, result.destText)
                    saveDoc(loaded.fileName, result.sourceText, "state set", result.sourceDoc)
                    showSnack("Marked done. Refiled to ${result.label}")
                }
            }
        }
    }

    /**
     * Read mode: tap a checklist item's box to toggle it done ([lineIndex] is
     * absolute into the document — a [BlockParser.ListItem]'s body-relative line
     * plus the owning headline's `bodyStart`).
     */
    fun toggleChecklistDone(lineIndex: Int) =
        mutateChecklistItem(lineIndex, OrgMutations::toggleCheckboxDone)

    /** Read mode: long-press a checklist item's box to toggle it in-progress. */
    fun toggleChecklistProgress(lineIndex: Int) =
        mutateChecklistItem(lineIndex, OrgMutations::toggleCheckboxProgress)

    private fun mutateChecklistItem(lineIndex: Int, mutate: (OrgDocument, Int) -> String?) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val newText = withContext(dispatchers.default) {
                mutate(loaded.document, lineIndex)
            } ?: return@launch
            val newDoc = withContext(dispatchers.default) {
                OrgParser.parse(newText, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "checklist toggled", newDoc)
        }
    }

    fun setScheduled(headline: OrgHeadline, ts: OrgTimestamp?) =
        setPlanning(headline, "Scheduled", ts) { d, h -> OrgMutations.setScheduled(d, h, ts) }

    fun setDeadline(headline: OrgHeadline, ts: OrgTimestamp?) =
        setPlanning(headline, "Deadline", ts) { d, h -> OrgMutations.setDeadline(d, h, ts) }

    /** Read mode's metadata sheet: priority/tag chips write straight to disk. */
    fun setPriority(headline: OrgHeadline, priority: Char?) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val (newText, newDoc) = withContext(dispatchers.default) {
                val text = OrgMutations.setPriority(loaded.document, headline, priority)
                text to OrgParser.parse(text, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "priority set", newDoc)
            showToast("Priority → ${priority?.let { "#$it" } ?: "none"}")
        }
    }

    fun setTags(headline: OrgHeadline, tags: List<String>) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val (newText, newDoc) = withContext(dispatchers.default) {
                val text = OrgMutations.setTags(loaded.document, headline, tags)
                text to OrgParser.parse(text, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "tags set", newDoc)
        }
    }

    /**
     * Both planning dates in one edit: what the Dates screen commits. The toast
     * names whichever dates survived so clearing one is still acknowledged.
     */
    fun setPlanningDates(
        headline: OrgHeadline,
        scheduled: OrgTimestamp?,
        deadline: OrgTimestamp?,
        active: List<OrgTimestamp>,
    ) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val (newText, newDoc) = withContext(dispatchers.default) {
                val text = OrgMutations.setPlanningAndActiveTimestamps(
                    loaded.document, headline, scheduled, deadline, active,
                )
                text to OrgParser.parse(text, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "planning edit", newDoc)
            val fmt = DateTimeFormatter.ofPattern("EEE, MMM d")
            val parts = listOfNotNull(
                scheduled?.let { "Scheduled · ${it.date.format(fmt)}" },
                deadline?.let { "Deadline · ${it.date.format(fmt)}" },
                active.firstOrNull()?.let {
                    "Event · ${it.date.format(fmt)}" + if (active.size > 1) " +${active.size - 1}" else ""
                },
            )
            showToast(if (parts.isEmpty()) "Planning cleared" else parts.joinToString("  ·  "))
        }
    }

    private fun setPlanning(
        headline: OrgHeadline,
        label: String,
        ts: OrgTimestamp?,
        block: (OrgDocument, OrgHeadline) -> String,
    ) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val (newText, newDoc) = withContext(dispatchers.default) {
                val text = block(loaded.document, headline)
                text to OrgParser.parse(text, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "planning edit", newDoc)
            showToast(
                if (ts == null) "$label cleared"
                else "$label · ${ts.date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}"
            )
        }
    }

    /** Metadata sheet's "Generate org-id": writes a fresh `:ID:` into the heading's PROPERTIES drawer. */
    fun generateId(headline: OrgHeadline) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val (newText, newDoc) = withContext(dispatchers.default) {
                val text = OrgMutations.upsertProperty(loaded.document, headline, "ID", newOrgId())
                text to OrgParser.parse(text, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "org-id generated", newDoc)
            showToast("org-id generated")
        }
    }

    /** Outline swipe "Note" action: org's C-c C-z, logged into the LOGBOOK drawer. */
    fun addNote(headline: OrgHeadline, note: String) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        if (note.isBlank()) return
        viewModelScope.launch {
            val (newText, newDoc) = withContext(dispatchers.default) {
                val now = LocalDateTime.now()
                val stamp = OrgTimestamp(
                    now.toLocalDate(),
                    time = now.toLocalTime().withSecond(0).withNano(0),
                    active = false,
                )
                val text = OrgMutations.appendLogbookNote(loaded.document, headline, note.trim(), stamp)
                text to OrgParser.parse(text, loaded.document.keywords)
            }
            _state.value = DocumentUiState.Loaded(loaded.fileName, newDoc)
            saveDoc(loaded.fileName, newText, "note added", newDoc)
            showToast("Note added")
        }
    }

    // --- bulk metadata (outline "Move & indent" selection) ---

    /**
     * The metadata sheet's state chips for several headings: each gets
     * [keyword] with the same done/repeat/auto-archive semantics as [setState]
     * (see [AutoArchive.applyToEach]). One undo step covers the whole batch,
     * archive destinations included.
     */
    fun setStateEach(lines: Set<Int>, keyword: String?) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            // Re-parses the file once per selected heading and again per archived
            // subtree: keep it off the main thread like applyStructure/editEach.
            val result = withContext(dispatchers.default) {
                AutoArchive.applyToEach(
                    vault, settings, loaded.document, loaded.fileName, lines, keyword, LocalDateTime.now(),
                )
            } ?: return@launch
            undoSnapshot = UndoSnapshot(
                listOf(loaded.fileName to loaded.document.text) +
                    result.destWrites.map { (file, before, _) -> file to before }
            )
            _selectedLines.value = _selectedLines.value.mapNotNull(result.remap).toSet()
            // Once anything was archived the remap is all-null; don't let it fold
            // the outline's collapsed set away.
            _state.value = DocumentUiState.Loaded(
                loaded.fileName, result.sourceDoc, result.remap.takeIf { result.archivedTo.isEmpty() },
            )
            result.destWrites.forEach { (file, _, after) ->
                vault.save(file, after)
                sync.requestReindex(file, after, "state set")
            }
            saveDoc(loaded.fileName, result.sourceText, "state set", result.sourceDoc)
            val stateMessage = "State → ${keyword ?: "none"} on ${pluralCount(result.changed, "note")}"
            val destinations = result.archivedTo.distinct()
            showSnack(
                when {
                    destinations.isEmpty() -> stateMessage
                    destinations.size == 1 ->
                        "$stateMessage. Refiled ${result.archivedTo.size} to ${destinations.single()}"
                    else -> "$stateMessage. Refiled ${result.archivedTo.size}"
                }
            )
        }
    }

    fun setPriorityEach(lines: Set<Int>, priority: Char?) =
        editEach(lines, "priority set", { "Priority → ${priority?.let { "#$it" } ?: "none"} on ${pluralCount(it, "note")}" }) { d, h ->
            if (h.priority == priority) null else OrgMutations.setPriority(d, h, priority)
        }

    /** Adds [add] to and removes [remove] from each heading's own tags, leaving its other tags alone. */
    fun editTagsEach(lines: Set<Int>, add: Collection<String>, remove: Collection<String>) =
        editEach(lines, "tags set", { "Tags updated on ${pluralCount(it, "note")}" }) { d, h ->
            val tags = (h.tags - remove.toSet() + add).distinct()
            if (tags == h.tags) null else OrgMutations.setTags(d, h, tags)
        }

    fun setPlanningEach(lines: Set<Int>, edit: BulkPlanningEdit) {
        if (edit.isEmpty) return
        editEach(lines, "planning edit", { "Dates set on ${pluralCount(it, "note")}" }) { d, h ->
            val (scheduled, deadline, active) = edit.applyTo(h)
            OrgMutations.setPlanningAndActiveTimestamps(d, h, scheduled, deadline, active)
        }
    }

    fun addNoteEach(lines: Set<Int>, note: String) {
        if (note.isBlank()) return
        val now = LocalDateTime.now()
        val stamp = OrgTimestamp(now.toLocalDate(), time = now.toLocalTime().withSecond(0).withNano(0), active = false)
        editEach(lines, "note added", { "Note added to ${pluralCount(it, "note")}" }) { d, h ->
            OrgMutations.appendLogbookNote(d, h, note.trim(), stamp)
        }
    }

    /** A fresh `:ID:` for each heading that has none yet. */
    fun generateIdEach(lines: Set<Int>) =
        editEach(lines, "org-id generated", { "org-id generated for ${pluralCount(it, "note")}" }) { d, h ->
            if (h.id != null) null else OrgMutations.upsertProperty(d, h, "ID", newOrgId())
        }

    /**
     * Apply a section-local [edit] to each heading at [lines] (see
     * [OrgMutations.editEach]) as one undoable step; [message] gets the number
     * of headings that changed.
     */
    private fun editEach(
        lines: Set<Int>,
        syncReason: String,
        message: (Int) -> String,
        edit: (OrgDocument, OrgHeadline) -> String?,
    ) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        vaultFlow.value ?: return
        viewModelScope.launch {
            val result = withContext(dispatchers.default) {
                OrgMutations.editEach(loaded.document, lines, edit)
            } ?: return@launch
            undoSnapshot = UndoSnapshot(listOf(loaded.fileName to loaded.document.text))
            _selectedLines.value = _selectedLines.value.mapNotNull(result.remap).toSet()
            _state.value = DocumentUiState.Loaded(loaded.fileName, result.doc, result.remap)
            saveDoc(loaded.fileName, result.text, syncReason, result.doc)
            showSnack(message(result.applied))
        }
    }

    // --- refile (design spec Gestures screen) ---

    fun startRefile(headline: OrgHeadline) = startRefile(listOf(headline))

    /**
     * Refile several headlines at once; one inside another selected headline's
     * subtree travels with it rather than being moved on its own.
     */
    fun startRefileEach(lines: Set<Int>) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        startRefile(OrgMutations.selectedRoots(loaded.document, lines))
    }

    private fun startRefile(sources: List<OrgHeadline>) {
        val headline = sources.firstOrNull() ?: return
        _refile.value = RefileUiState(sourceLine = headline.lineIndex, sourceLines = sources.map { it.lineIndex })
        viewModelScope.launch {
            // Read file names + note counts from the Room index, not vault.notebooks()
            // (which re-reads and re-parses every .org file just to count level-1
            // headlines). Both columns are already on NotebookEntity.
            val notebooks = database.indexDao().notebooks()
                .sortedBy { it.fileName }
                .map { RefileNotebook(it.fileName, it.noteCount) }
                .toImmutableList()
            val settings = settingsRepository.settings.first()
            // Offered for several headlines only when they all archive to the same place.
            val archiveTarget = (_state.value as? DocumentUiState.Loaded)?.let { loaded ->
                sources
                    .map { ArchiveLocation.resolve(loaded.document, it, AutoArchive.settingsFallback(settings), loaded.fileName) }
                    .distinct()
                    .singleOrNull()
            }
            val lastUsedTarget = settings.lastRefileFile?.let { fileName ->
                ArchiveTarget(fileName, settings.lastRefileHeadingPath.split('/').filter { it.isNotEmpty() })
            }
            _refile.value = _refile.value?.copy(
                notebooks = notebooks, archiveTarget = archiveTarget, lastUsedTarget = lastUsedTarget,
            )
        }
    }

    fun refilePickNotebook(fileName: String) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        viewModelScope.launch {
            val doc = if (fileName == loaded.fileName) loaded.document
            else vaultFlow.value?.open(fileName)
            if (doc == null) {
                showToast("Couldn't open ${fileName.removeSuffix(".org")}")
                return@launch
            }
            _refile.value = _refile.value?.copy(pickedFile = fileName, pickedDoc = doc, path = persistentListOf())
        }
    }

    fun refileDrillInto(line: Int) {
        _refile.value = _refile.value?.let { it.copy(path = (it.path + line).toImmutableList()) }
    }

    /** Pop one drill-down level, or return to the notebook list from a file's top level. */
    fun refileBack() {
        _refile.value = _refile.value?.let {
            if (it.path.isNotEmpty()) it.copy(path = it.path.dropLast(1).toImmutableList())
            else it.copy(pickedFile = null, pickedDoc = null)
        }
    }

    fun refileCancel() {
        _refile.value = null
    }

    fun refileConfirm() {
        val picker = _refile.value ?: return
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val destFile = picker.pickedFile ?: return
        val headingPath = picker.path.mapNotNull { picker.pickedDoc?.headlineAtLine(it)?.title }
        if (picker.sourceLines.size > 1) {
            _refile.value = null
            refileEachTo(picker.sourceLines.toSet(), destFile, picker.path.lastOrNull(), headingPath)
            return
        }
        val source = loaded.document.headlineAtLine(picker.sourceLine) ?: return
        _refile.value = null
        refileTo(source, destFile, picker.path.lastOrNull(), headingPath)
    }

    /** One-tap archive: refile the source subtree straight to its resolved `ARCHIVE` target, creating any missing file/heading. */
    fun refileToArchive() {
        val picker = _refile.value ?: return
        val target = picker.archiveTarget ?: return
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        if (picker.sourceLines.size > 1) {
            _refile.value = null
            refileEachToResolvedTarget(
                loaded, vault, picker.sourceLines, target,
                verb = "Archived", syncReason = "archive", createFileIfMissing = true,
                archivedAt = LocalDateTime.now(),
            )
            return
        }
        val source = loaded.document.headlineAtLine(picker.sourceLine) ?: return
        _refile.value = null
        val sourceEnd = loaded.document.subtreeEndLine(source)
        viewModelScope.launch {
            refileToResolvedTarget(
                loaded, vault, source, target,
                verb = "Archived", syncReason = "archive", createFileIfMissing = true,
                archivedAt = LocalDateTime.now(),
            )
            dropFavoritesInRange(loaded.document, loaded.fileName, source.lineIndex until sourceEnd)
        }
    }

    /** One-tap refile straight to the destination of the most recent successful refile. */
    fun refileToLastUsed() {
        val picker = _refile.value ?: return
        val target = picker.lastUsedTarget ?: return
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        if (picker.sourceLines.size > 1) {
            _refile.value = null
            refileEachToResolvedTarget(
                loaded, vault, picker.sourceLines, target,
                verb = "Refiled", syncReason = "refile", createFileIfMissing = false,
            )
            return
        }
        val source = loaded.document.headlineAtLine(picker.sourceLine) ?: return
        _refile.value = null
        val sourceEnd = loaded.document.subtreeEndLine(source)
        viewModelScope.launch {
            refileToResolvedTarget(
                loaded, vault, source, target,
                verb = "Refiled", syncReason = "refile", createFileIfMissing = false,
            )
            dropFavoritesInRange(loaded.document, loaded.fileName, source.lineIndex until sourceEnd)
        }
    }

    /** Shared refile-to-a-resolved-path logic behind both [refileToArchive] and [refileToLastUsed]. */
    private suspend fun refileToResolvedTarget(
        loaded: DocumentUiState.Loaded,
        vault: Vault,
        source: OrgHeadline,
        target: ArchiveTarget,
        verb: String,
        syncReason: String,
        createFileIfMissing: Boolean,
        archivedAt: LocalDateTime? = null,
    ) {
        val write = AutoArchive.refileSubtree(
            vault, loaded.document, loaded.fileName, source, target, createFileIfMissing, archivedAt,
        )
        if (write == null) {
            showToast("Couldn't open ${target.fileName.removeSuffix(".org")}")
            return
        }
        undoSnapshot = UndoSnapshot(
            if (write.sourceFile == write.destFile) {
                listOf(write.sourceFile to loaded.document.text)
            } else {
                listOf(loaded.fileName to loaded.document.text, write.destFile to write.destTextBefore)
            }
        )
        _selectedLines.value = emptySet()
        val newSourceDoc = OrgParser.parse(write.sourceText, loaded.document.keywords)
        _state.value = DocumentUiState.Loaded(loaded.fileName, newSourceDoc)
        if (write.destFile != loaded.fileName) {
            vault.save(write.destFile, write.destText)
            sync.requestReindex(write.destFile, write.destText, syncReason)
        }
        saveDoc(loaded.fileName, write.sourceText, syncReason, newSourceDoc)
        showSnack("$verb to ${write.label}")
    }

    /** [refileToResolvedTarget] for several headlines, all to [target], as one undo step. */
    private fun refileEachToResolvedTarget(
        loaded: DocumentUiState.Loaded,
        vault: Vault,
        lines: List<Int>,
        target: ArchiveTarget,
        verb: String,
        syncReason: String,
        createFileIfMissing: Boolean,
        archivedAt: LocalDateTime? = null,
    ) {
        viewModelScope.launch {
            val result = AutoArchive.refileEach(
                vault, loaded.document, loaded.fileName, lines.map { it to target }, createFileIfMissing, archivedAt,
            )
            if (result.labels.isEmpty()) {
                showToast("Couldn't open ${target.fileName.removeSuffix(".org")}")
                return@launch
            }
            commitBulkRefile(
                loaded, vault, lines, result.sourceText, result.sourceDoc, result.destWrites, syncReason,
                "$verb ${pluralCount(result.labels.size, "note")} to ${result.labels.first()}",
            )
        }
    }

    /** [refileTo] for several headlines: all go under the picked heading, in their original order. */
    private fun refileEachTo(lines: Set<Int>, destFile: String, targetLine: Int?, headingPath: List<String>) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        val roots = OrgMutations.selectedRoots(loaded.document, lines)
        viewModelScope.launch {
            val destLabel = destFile.removeSuffix(".org")
            if (destFile == loaded.fileName) {
                val targetTitle = targetLine?.let { loaded.document.headlineAtLine(it)?.title }
                val newDoc = withContext(dispatchers.default) {
                    OrgMutations.refileEachWithinFile(loaded.document, lines, targetLine)
                }
                if (newDoc == null) {
                    showToast("Can't refile into its own subtree")
                    return@launch
                }
                commitBulkRefile(
                    loaded, vault, roots.map { it.lineIndex }, newDoc.text, newDoc, emptyList(), "refile",
                    "Refiled ${pluralCount(roots.size, "note")} to $destLabel › ${targetTitle ?: "top level"}",
                )
            } else {
                val destDoc = vault.open(destFile)
                if (destDoc == null) {
                    showToast("Couldn't open $destLabel")
                    return@launch
                }
                val target = targetLine?.let { destDoc.headlineAtLine(it) }
                val result = withContext(dispatchers.default) {
                    val lifted = OrgMutations.liftSubtrees(loaded.document, lines)
                    OrgMutations.insertEach(destDoc, targetLine, lifted.subtrees)?.let { lifted.doc to it }
                }
                if (result == null) {
                    showToast("Couldn't find the destination heading")
                    return@launch
                }
                val (newSourceDoc, newDestDoc) = result
                commitBulkRefile(
                    loaded, vault, roots.map { it.lineIndex }, newSourceDoc.text, newSourceDoc,
                    listOf(Triple(destFile, destDoc.text, newDestDoc.text)), "refile",
                    "Refiled ${pluralCount(roots.size, "note")} to $destLabel › ${target?.title ?: "top level"}",
                )
            }
            rememberRefileTarget(destFile, headingPath)
        }
    }

    /**
     * Publish and persist a bulk refile of the headlines at [lines] as one undo
     * step: the source file plus each of [destWrites] (file, before, after).
     */
    private suspend fun commitBulkRefile(
        loaded: DocumentUiState.Loaded,
        vault: Vault,
        lines: List<Int>,
        sourceText: String,
        sourceDoc: OrgDocument,
        destWrites: List<Triple<String, String, String>>,
        syncReason: String,
        message: String,
    ) {
        undoSnapshot = UndoSnapshot(
            listOf(loaded.fileName to loaded.document.text) + destWrites.map { (file, before, _) -> file to before }
        )
        _selectedLines.value = emptySet()
        _state.value = DocumentUiState.Loaded(loaded.fileName, sourceDoc)
        destWrites.forEach { (file, _, after) ->
            vault.save(file, after)
            sync.requestReindex(file, after, syncReason)
        }
        saveDoc(loaded.fileName, sourceText, syncReason, sourceDoc)
        showSnack(message)
        val doc = loaded.document
        lines.mapNotNull(doc::headlineAtLine).forEach { h ->
            dropFavoritesInRange(doc, loaded.fileName, h.lineIndex until doc.subtreeEndLine(h))
        }
    }

    private fun rememberRefileTarget(fileName: String, headingPath: List<String>) {
        viewModelScope.launch {
            settingsRepository.setLastRefileTarget(fileName, headingPath)
        }
    }

    private fun refileTo(source: OrgHeadline, destFile: String, targetLine: Int?, headingPath: List<String>) {
        val loaded = _state.value as? DocumentUiState.Loaded ?: return
        val vault = vaultFlow.value ?: return
        val sourceEnd = loaded.document.subtreeEndLine(source)
        viewModelScope.launch {
            val destLabel = destFile.removeSuffix(".org")
            if (destFile == loaded.fileName) {
                val targetTitle = targetLine?.let { loaded.document.headlineAtLine(it)?.title }
                val result = withContext(dispatchers.default) {
                    OrgMutations.refileWithinFile(loaded.document, source, targetLine)
                        ?.let { (text, _) -> text to OrgParser.parse(text, loaded.document.keywords) }
                }
                if (result == null) {
                    showToast("Can't refile into its own subtree")
                    return@launch
                }
                undoSnapshot = UndoSnapshot(listOf(loaded.fileName to loaded.document.text))
                _selectedLines.value = emptySet()
                _state.value = DocumentUiState.Loaded(loaded.fileName, result.second)
                saveDoc(loaded.fileName, result.first, "refile", result.second)
                showSnack("Refiled to $destLabel › ${targetTitle ?: "top level"}")
                rememberRefileTarget(destFile, headingPath)
            } else {
                val destDoc = vault.open(destFile)
                if (destDoc == null) {
                    showToast("Couldn't open $destLabel")
                    return@launch
                }
                val target = targetLine?.let { destDoc.headlineAtLine(it) }
                val (newSourceText, newDestText, newSourceDoc) = withContext(dispatchers.default) {
                    val subtree = OrgMutations.subtreeText(loaded.document, source)
                    val srcText = OrgMutations.deleteSubtree(loaded.document, source)
                    val (dstText, _) = OrgMutations.refileInsert(destDoc, target, subtree)
                    Triple(srcText, dstText, OrgParser.parse(srcText, loaded.document.keywords))
                }
                undoSnapshot = UndoSnapshot(
                    listOf(loaded.fileName to loaded.document.text, destFile to destDoc.text)
                )
                _selectedLines.value = emptySet()
                _state.value = DocumentUiState.Loaded(loaded.fileName, newSourceDoc)
                vault.save(destFile, newDestText)
                sync.requestReindex(destFile, newDestText, "refile")
                saveDoc(loaded.fileName, newSourceText, "refile", newSourceDoc)
                showSnack("Refiled to $destLabel › ${target?.title ?: "top level"}")
                rememberRefileTarget(destFile, headingPath)
            }
            dropFavoritesInRange(loaded.document, loaded.fileName, source.lineIndex until sourceEnd)
        }
    }

    companion object {
        val Factory = factory {
            DocumentViewModel(
                it.vault, it.keywords, it.syncManager, it.database,
                it.settingsRepository, it.favoritesRepository, it.dispatchers, it,
            )
        }
    }
}

/**
 * Note identity: "fileName@headlineLineIndex[#customId]". [lineIndex] is a snapshot from
 * whenever this ref was created (e.g. a favorite added earlier, or an outline row tapped just
 * now); it can go stale if the file was edited externally (this app's primary edit path) or
 * elsewhere in-app between then and when the ref is resolved. [customId] — the heading's
 * `:CUSTOM_ID:`/`:ID:`, when known — is the stable identity to resolve against; see
 * [OrgDocument.headlineFor]. Null for refs with no id at hand (fresh outline taps resolve
 * against the live doc so [lineIndex] alone is already correct there).
 */
data class NoteRef(val fileName: String, val lineIndex: Int, val customId: String? = null) {
    fun encode(): String = if (customId != null) "$fileName@$lineIndex#$customId" else "$fileName@$lineIndex"

    /** True when this ref points at the file's intro (see [INTRO_LINE_INDEX]). */
    val isIntro: Boolean get() = lineIndex == INTRO_LINE_INDEX

    companion object {
        /** A ref to [fileName]'s intro: its heading-less content. */
        fun intro(fileName: String) = NoteRef(fileName, INTRO_LINE_INDEX)

        fun decode(noteId: String): NoteRef? {
            val hash = noteId.indexOf('#')
            val base = if (hash >= 0) noteId.substring(0, hash) else noteId
            val customId = if (hash >= 0) noteId.substring(hash + 1).takeIf { it.isNotEmpty() } else null
            val at = base.lastIndexOf('@')
            if (at <= 0) return null
            val line = base.substring(at + 1).toIntOrNull() ?: return null
            return NoteRef(base.substring(0, at), line, customId)
        }
    }
}

fun OrgDocument.headlineAtLine(lineIndex: Int): OrgHeadline? =
    headlines.firstOrNull { it.lineIndex == lineIndex }

/**
 * Resolves a [NoteRef] to its headline. Tries [NoteRef.customId] first (via `:CUSTOM_ID:` then
 * `:ID:`), which stays correct even when [NoteRef.lineIndex] has drifted from an external edit;
 * falls back to a raw line lookup when there's no id (older favorites, refs created directly
 * from a just-loaded doc, or an id search that came up empty).
 */
fun OrgDocument.headlineFor(ref: NoteRef): OrgHeadline? {
    val byId = ref.customId?.let { findByCustomId(it) ?: findById(it) }
    return byId ?: headlineAtLine(ref.lineIndex)
}

internal fun <T : ViewModel> factory(create: (GroveApplication) -> T) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>, extras: CreationExtras): V {
            val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as GroveApplication
            return create(app) as V
        }
    }
