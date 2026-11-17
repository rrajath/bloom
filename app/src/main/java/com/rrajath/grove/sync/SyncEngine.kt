package com.rrajath.grove.sync

import com.rrajath.grove.sync.conflicts.ConflictSchemes
import com.rrajath.grove.vault.UnverifiedReadException
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sync state machine (PRD §13): Idle → Checking → Pulling → Done/Conflict/Error.
 * For the v1 Local Directory backend the .org files on disk ARE the source of
 * truth, so "pulling" means re-indexing files whose revision changed (Syncthing
 * or any other tool may rewrite them at any time). Merging/Pushing states exist
 * for the v2 remote backends (WebDAV/Dropbox) which implement [FileStore]
 * against a remote and add an upload leg.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data object Checking : SyncState()
    data class Pulling(val fileName: String, val index: Int, val total: Int) : SyncState()
    data class Done(val result: SyncResult) : SyncState()
    data class Error(val message: String) : SyncState()

    /**
     * The vault's provider couldn't list the root (unreachable, erroring, or
     * still loading past the deadline). The index was left untouched, so the
     * last known notebooks stay visible; nothing was removed.
     */
    data class Offline(val reason: String) : SyncState()
}

data class SyncResult(
    val pulled: List<String>,
    val removed: List<String>,
    /** Notebook file name → its conflict copy's file name (any [com.rrajath.grove.sync.conflicts.ConflictScheme]). */
    val conflicts: Map<String, String>,
    val completedAt: Long,
    /** Folders the provider couldn't finish listing; their notebooks were left as indexed. */
    val incompleteDirs: Set<String> = emptySet(),
    /** Notebooks whose download couldn't be verified; left as indexed, retried next sync. */
    val unread: List<String> = emptyList(),
)

/**
 * Indexed state the engine diffs against the store. [isIndexed] is false for a
 * stub row (file discovered but not yet parsed) so the engine re-parses it even
 * if the revision already matches, e.g. after a sync interrupted mid-parse.
 */
data class KnownNotebook(
    val revision: String,
    val conflictFileName: String?,
    val isIndexed: Boolean = true,
)

/** Lightweight placeholder for a newly-discovered file, written before parsing. */
data class NotebookStub(
    val fileName: String,
    val revision: String,
    val lastModified: Long,
    val conflictFileName: String?,
)

/** Persistence boundary so the engine is unit-testable without Room. */
interface NoteIndex {
    /** Last indexed state per notebook file name. */
    suspend fun knownNotebooks(): Map<String, KnownNotebook>

    /**
     * Bulk-write placeholder rows for newly-discovered files in one shot, so
     * the notebook list surfaces every file immediately; [indexNotebook] then
     * fills in each notebook's real detail in the background.
     */
    suspend fun stubNotebooks(stubs: List<NotebookStub>)

    suspend fun indexNotebook(
        fileName: String,
        revision: String,
        text: String,
        lastModified: Long,
        conflictFileName: String?,
    )

    /** Update only the conflict marker (file content unchanged). */
    suspend fun setConflict(fileName: String, conflictFileName: String?)

    suspend fun removeNotebook(fileName: String)
}

class SyncEngine(
    private val store: FileStore,
    private val index: NoteIndex,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    val revision: (FileEntry) -> String = { "${it.lastModified}:${it.size}" }

    companion object {
        private const val PARSE_CONCURRENCY = 3
    }

    /**
     * Shows [SyncState.Offline] now, after an operation outside sync found the
     * provider unreachable. A sync in progress decides for itself; the next
     * sync replaces this either way.
     */
    fun markOffline(reason: String) {
        _state.update { if (it is SyncState.Checking || it is SyncState.Pulling) it else SyncState.Offline(reason) }
    }

    suspend fun sync(log: (String) -> Unit = {}): SyncResult? {
        _state.value = SyncState.Checking
        return try {
            val tree = store.listTree()
            if ("" in tree.incompleteDirs) {
                // Without the root there is nothing trustworthy to diff against:
                // leave the index exactly as it was.
                val reason = tree.rootProblem ?: "The vault's provider isn't available"
                log("offline: $reason")
                _state.value = SyncState.Offline(reason)
                return null
            }
            val entries = tree.entries

            // Notebook file name -> conflict copy; non-.org conflict copies (an
            // image, a stray file Syncthing touched) are dropped here so they
            // never reach the notification/UI layer, which only understands
            // notebooks.
            val conflicts = ConflictSchemes.detect(entries.map { it.name })
                .filterKeys { it.endsWith(".org") }
            val notebooks = entries.filter {
                it.name.endsWith(".org") && !ConflictSchemes.isConflictFile(it.name)
            }

            val known = index.knownNotebooks()
            val current = notebooks.associateBy({ it.name }, revision)
            // Re-parse anything not already fully indexed at the current revision:
            // brand-new files, leftover stubs, and content changes alike.
            val changed = notebooks.filter {
                val k = known[it.name]
                k == null || !k.isIndexed || k.revision != current[it.name]
            }
            // Absence under a folder the provider didn't finish listing proves
            // nothing: those notebooks stay indexed.
            val removed = (known.keys - current.keys).filterNot { tree.isUnderIncompleteDir(it) }
            // A conflict copy we couldn't see is not a resolved conflict: keep the
            // marker already on record for notebooks in such folders.
            fun conflictFor(name: String): String? =
                conflicts[name] ?: if (tree.isUnderIncompleteDir(name)) known[name]?.conflictFileName else null

            // Discovery/parse split: surface every newly-seen file as a stub in
            // ONE batch (a single notebooksFlow emission) so the list appears in
            // full instantly, then parse each below to fill in title/note count.
            // Only files with no existing row are stubbed, so a content change to
            // an already-shown notebook keeps its detail until the re-parse lands.
            val stubs = changed
                .filter { it.name !in known }
                .map {
                    NotebookStub(
                        fileName = it.name,
                        revision = current.getValue(it.name),
                        lastModified = it.lastModified,
                        conflictFileName = conflicts[it.name],
                    )
                }
            if (stubs.isNotEmpty()) index.stubNotebooks(stubs)

            // Bounded concurrency, not one file at a time: the read (I/O) and
            // parse (CPU) for up to PARSE_CONCURRENCY files now overlap instead
            // of serializing, and a slow per-file side effect (reminder
            // reconcile, inside index.indexNotebook) no longer blocks the next
            // file's read from starting (PERFORMANCE_AUDIT_2026-09-16 F1, F5).
            // No dispatcher is forced here: this inherits whatever dispatcher
            // the caller is already on (Dispatchers.Default in production via
            // SyncManager's appScope), which is also what keeps this safe under
            // a single-threaded TestDispatcher in tests.
            val unread = java.util.Collections.synchronizedList(mutableListOf<String>())
            coroutineScope {
                val semaphore = Semaphore(PARSE_CONCURRENCY)
                val completed = AtomicInteger(0)
                changed.map { entry ->
                    async {
                        semaphore.withPermit {
                            // A download that couldn't be verified indexes nothing: the
                            // row keeps its old revision (or stays a stub), so the next
                            // sync tries again.
                            val text = try {
                                store.read(entry.name)
                            } catch (e: UnverifiedReadException) {
                                unread += entry.name
                                log("skipped ${entry.name}: ${e.message}")
                                return@withPermit
                            }
                            index.indexNotebook(
                                fileName = entry.name,
                                revision = current.getValue(entry.name),
                                text = text,
                                lastModified = entry.lastModified,
                                conflictFileName = conflictFor(entry.name),
                            )
                            _state.value = SyncState.Pulling(entry.name, completed.incrementAndGet(), changed.size)
                            log("pulled ${entry.name}")
                        }
                    }
                }.awaitAll()
            }
            removed.forEach {
                index.removeNotebook(it)
                log("removed $it from index")
            }
            // Conflict markers can change without the file content changing
            // (a sync tool dropping a conflict copy next to it).
            val changedNames = changed.mapTo(HashSet()) { it.name }
            notebooks.forEach { nb ->
                if (nb.name !in changedNames && known[nb.name]?.conflictFileName != conflictFor(nb.name)) {
                    index.setConflict(nb.name, conflictFor(nb.name))
                }
            }
            conflicts.forEach { (base, copy) -> log("conflict: $base vs $copy") }
            if (!tree.isComplete) log("incomplete listing under: ${tree.incompleteDirs.sorted()}")

            val result = SyncResult(
                pulled = changed.map { it.name } - unread.toSet(),
                removed = removed,
                conflicts = conflicts,
                completedAt = clock(),
                incompleteDirs = tree.incompleteDirs,
                unread = unread.sorted(),
            )
            _state.value = SyncState.Done(result)
            result
        } catch (e: Exception) {
            log("sync failed: ${e.message}")
            _state.value = SyncState.Error(e.message ?: "Sync failed")
            null
        }
    }

    /**
     * Re-index a single already-known file the caller just wrote, skipping the
     * whole-directory [list] + revision diff [sync] does. Reads only this file's
     * metadata via [FileStore.stat] (which [com.rrajath.grove.vault.storage.BackendFileStore]
     * serves as a targeted single-document query, not a full enumeration), so the
     * cost is independent of vault size. For the in-app single-note save path,
     * where the changed file and its new text are already in hand.
     * See PERFORMANCE_AUDIT_2026-08-27 #1. No-op if the file has since vanished.
     */
    suspend fun reindexOne(fileName: String, text: String, conflictFileName: String?) {
        val stat = store.stat(fileName) ?: return
        index.indexNotebook(
            fileName = fileName,
            revision = revision(stat),
            text = text,
            lastModified = stat.lastModified,
            conflictFileName = conflictFileName,
        )
    }
}
