package com.rrajath.grove.vault.storage

import com.rrajath.grove.attach.AttachSkipRoots
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.IgnorePatterns
import com.rrajath.grove.vault.SKIP_ATTACH_SHARDS
import com.rrajath.grove.vault.TreeListing
import com.rrajath.grove.vault.isSkippedVaultDir
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault as a [FileStore], over any [StorageBackend] (today the Storage
 * Access Framework, via [SafBackend]). It owns what is the same for every
 * provider: vault-relative paths, the path -> document-id caches, skip/ignore
 * rules, and the emulations (a case-only rename, move as copy + delete). The
 * backend owns the transport.
 *
 * The vault is a tree: [list] walks every subdirectory (one children query per
 * directory), skipping dot-directories and attachment shards
 * ([isSkippedVaultDir]). Names are vault-relative paths with `/` separators.
 *
 * Path -> document-id caches (files and directories) are warmed by [list] and
 * rebuilt on a cache miss. Recursive listing costs one query per directory;
 * fine for typical org vaults, a watch item for very large trees.
 */
class BackendFileStore(
    private val backend: StorageBackend,
    private val ignore: IgnorePatterns = IgnorePatterns(""),
    skipAttachShards: Boolean = SKIP_ATTACH_SHARDS,
    /** Where shards are skipped (from the attach config); a config change builds a new store. */
    attachRoots: AttachSkipRoots = AttachSkipRoots.DataOnly,
    /** Monotonic millis, for how long a raw file id counts as just verified. */
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) : FileStore {

    private val skipRoots = attachRoots.takeIf { skipAttachShards }

    private val rootDocId get() = backend.rootId

    /**
     * Whether the provider matches names in any case: there `inbox.org` *is* an
     * existing `Inbox.org`, and creating it makes the provider pick a free name
     * (`inbox (1).org`) instead. Lookups here then resolve to the existing
     * file's spelling ([resolve]).
     */
    private val caseInsensitive = backend.caseInsensitive

    // ConcurrentHashMap, not plain maps: Vault (and therefore this store) is a
    // process-wide singleton reached concurrently by SyncEngine on appScope and
    // by ViewModels on viewModelScope. list() swaps these wholesale while
    // create/rename/delete mutate them and pruneEmptyDirs iterates their keys —
    // a CME (or silent corruption) window with a LinkedHashMap.

    /** relative file path -> document id */
    @Volatile
    private var docIds: MutableMap<String, String> = ConcurrentHashMap()

    /** relative directory path ("" == vault root) -> document id */
    @Volatile
    private var dirDocIds: MutableMap<String, String> = ConcurrentHashMap()

    /** Serializes tree walks so concurrent cache misses don't each rebuild the maps. */
    private val listMutex = Mutex()

    /**
     * True once [list] has walked the whole tree at least once. A lookup that
     * misses both maps then answers "not found" without re-walking: the maps are
     * authoritative because [create]/[rename]/[delete] keep them coherent, and
     * every sync begins with a fresh [list]. The one thing this does not catch is
     * a file appearing via the sync backend between two [list] calls, but the
     * only names Grove looks up are ones a prior [list] surfaced (from the index
     * or a sync pass), so that window does not arise in practice.
     */
    @Volatile
    private var listed = false

    /** Why the vault root couldn't be listed completely on the last walk; null when it could. */
    @Volatile
    private var rootProblem: String? = null

    // Per-document capabilities, keyed like [docIds] / [dirDocIds]. Filled from
    // the same listings; a missing entry means "never listed", which operations
    // treat as allowed (the provider's refusal is then mapped instead).
    private val fileCaps: MutableMap<String, StorageCapabilities> = ConcurrentHashMap()
    private val dirCaps: MutableMap<String, StorageCapabilities> = ConcurrentHashMap()

    @Volatile
    private var rootCaps: StorageCapabilities = StorageCapabilities.ALL

    @Volatile
    private var rootCapsLoaded = false

    private val _capabilities = MutableStateFlow(VaultCapabilities(profile = backend.profile))
    override val capabilities: StateFlow<VaultCapabilities> = _capabilities.asStateFlow()

    /** Republishes [capabilities] from the caches (walks drop vanished paths from them). */
    private fun publishCapabilities() {
        _capabilities.value = VaultCapabilities(
            profile = backend.profile,
            root = rootCaps,
            files = HashMap(fileCaps),
            dirs = HashMap(dirCaps),
        )
    }

    /** What the file at [key] (a [docIds] key) allows; ALL when it was never listed. */
    private fun capsOfFile(key: String): StorageCapabilities = fileCaps[key] ?: StorageCapabilities.ALL

    private val ok: StorageResult<Unit> = StorageResult.Ok(Unit)

    /** What a missing source or parent means: the provider being unreachable, or really not there. */
    private fun missing(): StorageError = if (rootProblem != null) StorageError.Offline else StorageError.NotFound

    override suspend fun list(): List<FileEntry> = listTree().entries

    /**
     * Walks the whole tree, a few folders at a time so slow (loading) ones
     * overlap. A folder the provider couldn't finish listing ([Listing.Partial]
     * or [Listing.Failed]) is reported in [TreeListing.incompleteDirs] and keeps
     * its cached ids: absence under it proves nothing, so the caches merge
     * instead of being replaced, and [listed] stays false until a walk is whole.
     */
    override suspend fun listTree(): TreeListing = withContext(Dispatchers.IO) {
        listMutex.withLock { walkTree() }
    }

    private suspend fun walkTree(): TreeListing {
        val files = ConcurrentHashMap<String, String>()
        val dirs = ConcurrentHashMap<String, String>().apply { put("", rootDocId) }
        val entries = java.util.Collections.synchronizedList(mutableListOf<FileEntry>())
        val incomplete: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var rootReason: String? = null
        val permits = Semaphore(WALK_CONCURRENCY)

        coroutineScope {
            fun CoroutineScope.visit(dir: String, docId: String) {
                launch {
                    val listing = permits.withPermit { backend.listChildren(docId) }
                    when (listing) {
                        is Listing.Complete -> Unit
                        is Listing.Partial -> {
                            incomplete += dir
                            if (dir.isEmpty()) rootReason = "The provider is still loading the vault"
                        }
                        is Listing.Failed -> {
                            incomplete += dir
                            if (dir.isEmpty()) rootReason = listing.reason
                        }
                    }
                    for (child in listing.entries) {
                        val path = childPath(dir, child.name)
                        if (child.isDir) {
                            if (isSkippedVaultDir(child.name, dir, skipRoots) || ignore.isDirIgnored(child.name, path)) continue
                            dirs[path] = child.docId
                            dirCaps[path] = child.capabilities
                            visit(path, child.docId)
                        } else {
                            if (ignore.isFileIgnored(child.name, path)) continue
                            files[path] = child.docId
                            fileCaps[path] = child.capabilities
                            entries.add(FileEntry(path, child.lastModified, child.size))
                        }
                    }
                }
            }
            visit("", rootDocId)
        }

        // Keep what we knew under any folder we couldn't finish listing.
        fun underIncomplete(path: String) = incomplete.any { it.isEmpty() || path.startsWith("$it/") }
        docIds.forEach { (path, id) -> if (underIncomplete(path)) files.putIfAbsent(path, id) }
        dirDocIds.forEach { (path, id) -> if (path.isNotEmpty() && underIncomplete(path)) dirs.putIfAbsent(path, id) }

        docIds = files
        dirDocIds = dirs
        listed = incomplete.isEmpty()
        rootProblem = rootReason
        fileCaps.keys.retainAll(files.keys)
        dirCaps.keys.retainAll(dirs.keys)
        // The root's own flags: one query, the first time the provider answers
        // for it (a root's flags don't change while its tree is in use).
        if (!rootCapsLoaded && rootReason == null) {
            backend.stat(rootDocId).getOrNull()?.let {
                rootCaps = it.capabilities
                rootCapsLoaded = true
            }
        }
        publishCapabilities()
        return TreeListing(entries.sortedBy { it.name }, incomplete.toSet(), rootReason)
    }


    /**
     * One children query for [dir] (plus one per path segment whose id isn't
     * cached yet), instead of [list]'s query per directory in the vault. Warms
     * [docIds] for the files found, so a follow-up [stat]/[read] of one of them
     * doesn't fall back to a full walk either, and drops cached ids of direct
     * children that have since disappeared. Deliberately not under [listMutex]:
     * waiting out a concurrent full walk is exactly the cost this avoids.
     */
    override suspend fun listDir(dir: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val trimmed = dir.trim('/')
        val cachedPath = dirKey(trimmed)
        val entries = cachedPath?.let { path -> dirDocIds[path]?.let { queryDir(path, it) } }
            ?: run {
                // Not cached, or the cached id went stale (folder moved/deleted
                // outside Grove): resolve it afresh, segment by segment.
                if (cachedPath != null) dirDocIds.remove(cachedPath)
                val found = lookupDir(trimmed) ?: return@withContext emptyList()
                queryDir(found.path, found.docId) ?: emptyList()
            }
        entries.sortedBy { it.name }
    }

    /**
     * Direct children files of the directory [docId] (at relative path [dir]),
     * or null if the provider rejects the id or can't be reached. The cache
     * only forgets vanished children when the listing is [Listing.Complete]:
     * a partial one proves nothing about what's missing.
     */
    private suspend fun queryDir(dir: String, docId: String): List<FileEntry>? {
        val entries = mutableListOf<FileEntry>()
        val seenFiles = mutableSetOf<String>()
        val seenDirs = mutableSetOf<String>()
        val listing = backend.listChildren(docId)
        if (listing is Listing.Failed) return null
        for (child in listing.entries) {
            val path = childPath(dir, child.name)
            if (child.isDir) {
                if (isSkippedVaultDir(child.name, dir, skipRoots) || ignore.isDirIgnored(child.name, path)) continue
                dirDocIds[path] = child.docId
                dirCaps[path] = child.capabilities
                seenDirs += path
            } else {
                if (ignore.isFileIgnored(child.name, path)) continue
                docIds[path] = child.docId
                fileCaps[path] = child.capabilities
                seenFiles += path
                entries.add(FileEntry(path, child.lastModified, child.size))
            }
        }
        if (listing !is Listing.Complete) return entries.also { publishCapabilities() }
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        fun isDirectChild(path: String) = path.startsWith(prefix) && '/' !in path.removePrefix(prefix)
        docIds.keys.removeAll { isDirectChild(it) && it !in seenFiles }
        // A vanished subdirectory takes its cached descendants with it.
        val goneDirs = dirDocIds.keys.filter { isDirectChild(it) && it !in seenDirs }
        dirDocIds.keys.removeAll { key -> goneDirs.any { key == it || key.startsWith("$it/") } }
        docIds.keys.removeAll { key -> goneDirs.any { key.startsWith("$it/") } }
        publishCapabilities()
        return entries
    }

    /** A vault directory as the provider spells it, with its document id. */
    private data class DirRef(val path: String, val docId: String)

    /**
     * An existing relative directory, found one segment at a time from the
     * nearest cached ancestor (one query per uncached segment), never via a
     * full [list]. Null if any segment is missing, skipped, or ignored,
     * matching what [list] would surface.
     */
    private suspend fun lookupDir(dir: String): DirRef? {
        if (dir.isEmpty()) return DirRef("", rootDocId)
        var current = DirRef("", rootDocId)
        for (segment in dir.split('/')) {
            val nextPath = childPath(current.path, segment)
            if (isSkippedVaultDir(segment, current.path, skipRoots) || ignore.isDirIgnored(segment, nextPath)) {
                return null
            }
            current = dirKey(nextPath)?.let { path -> dirDocIds[path]?.let { DirRef(path, it) } }
                ?: findChildDir(current, segment)?.also { dirDocIds[it.path] = it.docId }
                ?: return null
        }
        return current
    }

    private fun childPath(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun sameName(a: String, b: String) = if (caseInsensitive) a.equals(b, ignoreCase = true) else a == b

    /** The [dirDocIds] key for [dir]: itself, or its real spelling on a case-insensitive provider. */
    private fun dirKey(dir: String): String? {
        if (dir in dirDocIds) return dir
        if (!caseInsensitive) return null
        return dirDocIds.keys.firstOrNull { it.equals(dir, ignoreCase = true) }
    }

    private sealed interface DirectLookup {
        /** This provider's document ids aren't paths: only a listing can answer. */
        data object Unsupported : DirectLookup
        data object Missing : DirectLookup
        data class Found(val docId: String, val caps: StorageCapabilities) : DirectLookup
    }

    /**
     * Answers "is [name] a file in the vault" with one query, no listing, on the
     * local-storage provider (what a Syncthing folder is), whose document ids are
     * `<volume>:<path>`: the file's id is then the tree root's id plus [name].
     * Applies the same skip/ignore rules as [list], and defers to a listing when
     * the provider's name differs in case, so the answer always matches [list]'s.
     */
    private suspend fun directLookup(name: String): DirectLookup {
        val docId = backend.docIdForPath(name) ?: return DirectLookup.Unsupported
        val segments = name.split('/')
        var path = ""
        for (dir in segments.dropLast(1)) {
            val parent = path
            path = if (path.isEmpty()) dir else "$path/$dir"
            if (isSkippedVaultDir(dir, parent, skipRoots) || ignore.isDirIgnored(dir, path)) {
                return DirectLookup.Missing
            }
        }
        if (ignore.isFileIgnored(segments.last(), name)) return DirectLookup.Missing
        // A missing file surfaces as NotFound however the platform reports it.
        val entry = backend.stat(docId).getOrNull() ?: return DirectLookup.Missing
        return when {
            entry.isDir -> DirectLookup.Missing
            entry.name != segments.last() -> DirectLookup.Unsupported
            else -> DirectLookup.Found(entry.docId, entry.capabilities)
        }
    }

    /** The subdirectory [name] of [parent]; null when absent in what the provider listed, or when it couldn't list. */
    private suspend fun findChildDir(parent: DirRef, name: String): DirRef? =
        backend.listChildren(parent.docId).entries
            .firstOrNull { it.isDir && sameName(it.name, name) }
            ?.let { DirRef(childPath(parent.path, it.name), it.docId) }

    override suspend fun stat(name: String): FileEntry? = withContext(Dispatchers.IO) {
        val key = resolve(name) ?: return@withContext null
        val entry = backend.stat(docIds[key] ?: return@withContext null).getOrNull() ?: return@withContext null
        FileEntry(key, entry.lastModified, entry.size)
    }

    override suspend fun canonicalName(name: String): String = resolve(name) ?: name

    override suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        val docId = documentId(name) ?: error("File not found in vault: $name")
        val stream = backend.openRead(docId).getOrNull() ?: error("Cannot open $name")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override suspend fun write(name: String, content: String, ifMatch: String?): StorageResult<String> =
        withContext(Dispatchers.IO) {
            val docId = documentId(name) ?: return@withContext missing()
            backend.write(docId, content.toByteArray(Charsets.UTF_8), ifMatch)
        }

    override suspend fun create(name: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        if (resolve(name) != null) return@withContext StorageError.NameTaken
        val (dir, leaf) = splitPath(name)
        val parent = when (val r = resolveDir(dir, createMissing = true)) {
            is StorageResult.Ok -> r.value
            is StorageError -> return@withContext r
        }
        val created = backend.createFile(parent.docId, leaf)
        listingChanged(Unit)
        when (created) {
            is StorageResult.Ok -> {
                docIds[childPath(parent.path, leaf)] = created.value
                ok
            }
            StorageError.NameTaken -> {
                // Still not [leaf]: a file the cache didn't know about holds that
                // name (in another case, on a case-insensitive provider), and the
                // provider made this one `name (2).org`; the backend dropped it.
                // Learn the real file, so a write to [name] reaches that one instead.
                queryDir(parent.path, parent.docId)
                StorageError.NameTaken
            }
            is StorageError -> created
        }
    }

    override suspend fun rename(oldName: String, newName: String): StorageResult<Unit> =
        withContext(Dispatchers.IO) {
            if (oldName == newName) return@withContext StorageError.NameTaken
            val oldKey = resolve(oldName) ?: return@withContext missing()
            val newKey = resolve(newName)
            val (oldDir, _) = splitPath(oldKey)
            val (newDir, newLeaf) = splitPath(newName)
            // A case-only rename (Work.org -> work.org) resolves both names to
            // the same file; anything else already at [newName] blocks it.
            val sameDir = sameName(oldDir, newDir)
            val caseOnly = newKey == oldKey && sameDir
            if (newKey != null && !caseOnly) return@withContext StorageError.NameTaken
            val docId = docIds[oldKey] ?: return@withContext StorageError.NotFound
            val caps = capsOfFile(oldKey)
            val oldLeaf = oldKey.substringAfterLast('/')

            val result = when {
                // A same-folder rename has no emulation: copying to the new name
                // would leave the original behind, since it can't be removed either.
                (caseOnly || sameDir) && !caps.rename -> StorageError.Unsupported(StorageOp.Rename)
                // The provider sees [newLeaf] as taken (by this very file) and
                // would pick `work (1).org`; going through a free name avoids that.
                caseOnly -> renameVia(docId, ".$newLeaf.renaming", newLeaf)
                sameDir -> backend.rename(docId, newLeaf).map { }
                else -> moveAcrossDirs(docId, caps, oldKey, oldLeaf, oldDir, newDir, newLeaf, newName)
            }
            listingChanged(Unit)

            if (result.isOk) {
                docIds.remove(oldKey)
                docIds.remove(newName)
                fileCaps.remove(oldKey)?.let { fileCaps[newName] = it }
                publishCapabilities()
            }
            result
        }

    /** Renames [docId] to [temp], then to [leaf]; puts the old name back if the second step fails. */
    private suspend fun renameVia(docId: String, temp: String, leaf: String): StorageResult<Unit> {
        val oldLeaf = backend.stat(docId).getOrNull()?.name ?: return StorageError.NotFound
        val tempId = when (val first = backend.rename(docId, temp)) {
            is StorageResult.Ok -> first.value
            is StorageError -> return first
        }
        val second = backend.rename(tempId, leaf)
        if (second is StorageError) {
            backend.rename(tempId, oldLeaf)
            return second
        }
        return ok
    }

    /**
     * Cross-directory move through the backend, falling back to copy + delete
     * for providers that refuse it (an unsupported or failed move), but only
     * when the file can be deleted: otherwise the copy would leave it in two
     * places, so the answer is [StorageError.Unsupported] before anything is
     * copied (or any target folder created). Any other error is the answer.
     * When the delete half of the fallback fails, the copy is removed again.
     */
    private suspend fun moveAcrossDirs(
        docId: String,
        caps: StorageCapabilities,
        oldName: String,
        oldLeaf: String,
        oldDir: String,
        newDir: String,
        newLeaf: String,
        newName: String,
    ): StorageResult<Unit> {
        // A native move keeps the leaf; a new leaf on top needs a rename too.
        val nativeMove = caps.move && (newLeaf == oldLeaf || caps.rename)
        if (!nativeMove && !caps.delete) return StorageError.Unsupported(StorageOp.Move)

        val sourceParent = when (val r = resolveDir(oldDir, createMissing = false)) {
            is StorageResult.Ok -> r.value
            is StorageError -> return r
        }
        val targetParent = when (val r = resolveDir(newDir, createMissing = true)) {
            is StorageResult.Ok -> r.value
            is StorageError -> return r
        }

        if (nativeMove) {
            when (val moved = backend.move(docId, sourceParent.docId, targetParent.docId)) {
                is StorageResult.Ok -> {
                    val currentName = backend.stat(moved.value).getOrNull()?.name
                    if (currentName != null && currentName != newLeaf) backend.rename(moved.value, newLeaf)
                    return ok
                }
                is StorageError.Unsupported, is StorageError.ProviderError ->
                    if (!caps.delete) return moved
                is StorageError -> return moved
            }
        }

        // Provider refused the move: copy the bytes then delete the source.
        val content = try {
            read(oldName)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return StorageError.ProviderError(e.message)
        }
        val created = create(newName)
        if (created is StorageError) return created
        val written = write(newName, content)
        if (written is StorageError) {
            delete(newName)
            return written
        }
        val deleted = delete(oldName)
        if (deleted is StorageError) {
            delete(newName)
            return deleted
        }
        return ok
    }

    override suspend fun delete(name: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val key = resolve(name) ?: return@withContext missing()
        val docId = docIds[key] ?: return@withContext StorageError.NotFound
        // No provider call for what the file's flags already rule out.
        if (!capsOfFile(key).delete) return@withContext StorageError.Unsupported(StorageOp.Delete)
        val result = backend.delete(docId)
        listingChanged(Unit)
        if (result.isOk) {
            docIds.remove(key)
            fileCaps.remove(key)
            publishCapabilities()
        }
        result
    }

    override suspend fun exists(name: String): Boolean = resolve(name) != null

    override fun absolutePath(name: String): String? = backend.absolutePath(name)

    override suspend fun pruneEmptyDirs(dir: String): Unit = withContext(Dispatchers.IO) {
        val asked = dir.trim('/')
        if (asked.isEmpty()) return@withContext
        if (dirKey(asked) == null) list()
        val trimmed = dirKey(asked) ?: asked

        // Descendants (and the dir itself) deepest-first, so an emptied parent
        // whose only child was an also-empty subdirectory still collapses.
        dirDocIds.keys
            .filter { it == trimmed || it.startsWith("$trimmed/") }
            .sortedByDescending { path -> path.count { it == '/' } }
            .forEach { removeDirIfEmpty(it) }

        var current = trimmed.substringBeforeLast('/', "")
        while (current.isNotEmpty() && removeDirIfEmpty(current)) {
            current = current.substringBeforeLast('/', "")
        }
    }

    /**
     * Deletes [path] if the provider confirms it has no child documents.
     * Returns whether it was removed. A listing that is still loading, partial
     * or failed is not "empty": deleting then could take real files with it.
     */
    private suspend fun removeDirIfEmpty(path: String): Boolean {
        val docId = dirDocIds[path] ?: return false

        val listing = backend.listChildren(docId)
        if (listing !is Listing.Complete || listing.entries.isNotEmpty()) return false

        val removed = backend.delete(docId).isOk
        listingChanged(Unit)

        if (removed) dirDocIds.remove(path)
        return removed
    }

    /** The document id of the file at [name]; null when it's absent, or a concurrent [list] dropped it. */
    private suspend fun documentId(name: String): String? = resolve(name)?.let { docIds[it] }

    /**
     * The [docIds] key for the file at [name] (its real spelling, on a
     * case-insensitive provider), or null when there's no such file. Only
     * re-walks the tree if it has never been listed; once listed, a miss is
     * authoritative (see [listed]). This is what keeps exists()/create()/
     * rename() on an absent name from triggering a full recursive walk.
     * Before the first walk, a path-id provider answers with one direct query
     * instead (Dailies opening today's note on a cold store).
     */
    private suspend fun resolve(name: String): String? {
        cachedKey(name)?.let { return it }
        if (listed) return null
        return when (val direct = directLookup(name)) {
            DirectLookup.Unsupported -> {
                list()
                cachedKey(name)
            }
            DirectLookup.Missing -> null
            is DirectLookup.Found -> if (caseInsensitive) {
                // The provider echoes the spelling asked for, whatever the file's
                // real one is; the parent's listing has the real one.
                lookupDir(name.substringBeforeLast('/', ""))?.let { queryDir(it.path, it.docId) }
                cachedKey(name) ?: name.also { learnFile(it, direct) }
            } else {
                name.also { learnFile(it, direct) }
            }
        }
    }

    private fun learnFile(key: String, found: DirectLookup.Found) {
        docIds[key] = found.docId
        fileCaps[key] = found.caps
        publishCapabilities()
    }

    private fun cachedKey(name: String): String? {
        if (name in docIds) return name
        if (!caseInsensitive) return null
        return docIds.keys.firstOrNull { it.equals(name, ignoreCase = true) }
    }

    /**
     * A relative directory path (its real spelling, on a case-insensitive
     * provider), walking (and optionally creating) one segment at a time.
     * The tree root for "".
     */
    private suspend fun resolveDir(dir: String, createMissing: Boolean): StorageResult<DirRef> {
        if (dir.isEmpty()) return StorageResult.Ok(DirRef("", rootDocId))
        fun cached() = dirKey(dir)?.let { path -> dirDocIds[path]?.let { DirRef(path, it) } }
        cached()?.let { return StorageResult.Ok(it) }
        list()
        cached()?.let { return StorageResult.Ok(it) }
        if (!createMissing) return missing()
        // The root couldn't be listed, so the folder may well exist: creating it now would duplicate it.
        if (rootProblem != null) return StorageError.Offline

        var current = DirRef("", rootDocId)
        for (segment in dir.split('/')) {
            current = dirKey(childPath(current.path, segment))?.let { path -> dirDocIds[path]?.let { DirRef(path, it) } }
                ?: run {
                    // NameTaken: something unlisted (a skipped or ignored folder, in
                    // any case) holds the name; the provider made `name (1)` and the
                    // backend removed it again.
                    val created = backend.createDir(current.docId, segment).also { listingChanged(Unit) }
                    val newDirId = when (created) {
                        is StorageResult.Ok -> created.value
                        is StorageError -> return created
                    }
                    val path = childPath(current.path, segment)
                    DirRef(path, newDirId).also { dirDocIds[path] = it.docId }
                }
        }
        return StorageResult.Ok(current)
    }

    // --- Raw access for attachments ---------------------------------------
    //
    // Attachment files live under skipped shard dirs, so the listing-backed
    // caches above never hold them and documentId() treats them as missing.
    // These members resolve paths on their own, ignoring every skip/ignore
    // rule: on the local-storage provider (path document ids) one query per
    // lookup, elsewhere a cached id validated with one query, falling back to
    // a walk from the nearest known parent. Their ids go in separate caches
    // that list() never wipes.

    /** raw relative path -> document id, for files / directories resolved here. */
    private val rawFileIds: MutableMap<String, String> = ConcurrentHashMap()
    private val rawDirIds: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * raw file path -> when its id was last confirmed by a query. Opening a
     * file (or building its URI) right after a stat reuses the id without
     * asking the provider again: read mode stats an image, then opens it for
     * its header and hands its URI to Coil, all within a few milliseconds. A
     * file replaced inside the window fails the open, which callers already
     * treat as missing or unreadable. Metadata ([statRaw]) is always queried.
     */
    private val rawVerifiedAt: MutableMap<String, Long> = ConcurrentHashMap()

    /** A directory's child names as one query listed them, and when. */
    private class DirSnapshot(val at: Long, val names: Set<String>)

    /**
     * raw dir path -> its last listing. A lookup of a path whose parent was
     * listed within [RAW_FRESH_MS] and didn't hold it answers "missing" with no
     * query; otherwise each miss re-lists the parent on providers without path
     * ids (a screen of broken chips in one dir cost one listing apiece). Any
     * create, rename, move or delete through this store drops every listing
     * ([listingChanged]); changes from outside (Syncthing) show up once the
     * window has passed, or at once after [dropRawSnapshots] (sync, foreground).
     */
    private val rawListings: MutableMap<String, DirSnapshot> = ConcurrentHashMap()

    /** Drops every [rawListings] snapshot after a structural change; returns [result]. */
    private fun <T> listingChanged(result: T): T {
        rawListings.clear()
        return result
    }

    override fun dropRawSnapshots() {
        rawListings.clear()
        rawVerifiedAt.clear()
    }

    private fun recordListing(path: String, children: List<DocEntry>) {
        rawListings[path] = DirSnapshot(now(), children.mapTo(HashSet()) { it.name })
    }

    /** True when [path]'s parent was listed recently and didn't contain it. */
    private fun knownAbsent(path: String): Boolean {
        val listing = rawListings[path.substringBeforeLast('/', "")] ?: return false
        if (now() - listing.at > RAW_FRESH_MS) return false
        return path.substringAfterLast('/') !in listing.names
    }

    /** One query for [docId]'s metadata; null when it's gone or the provider refuses. */
    private suspend fun queryDoc(docId: String): DocEntry? = backend.stat(docId).getOrNull()

    /** A directory's children as the raw side sees them, and whether the provider finished listing. */
    private class RawChildren(val metas: List<DocEntry>, val complete: Boolean)

    /** Children of [docId], or null when the provider rejects the id or can't be reached. */
    private suspend fun queryChildren(docId: String): RawChildren? {
        val listing = backend.listChildren(docId)
        if (listing is Listing.Failed) return null
        return RawChildren(listing.entries, complete = listing is Listing.Complete)
    }

    private fun remember(path: String, meta: DocEntry) {
        if (meta.isDir) {
            rawDirIds[path] = meta.docId
            rawFileIds.remove(path)
            rawVerifiedAt.remove(path)
        } else {
            rawFileIds[path] = meta.docId
            rawDirIds.remove(path)
            rawVerifiedAt[path] = now()
        }
    }

    /** [path]'s file id when a query confirmed it within [RAW_FRESH_MS], else null. */
    private fun freshFileId(path: String): String? {
        val at = rawVerifiedAt[path] ?: return null
        if (now() - at > RAW_FRESH_MS) return null
        return rawFileIds[path]
    }

    /** Drops [path] and everything under it from every cache. */
    private fun forgetTree(path: String) {
        fun drop(map: MutableMap<String, String>) {
            map.keys.removeAll { it == path || it.startsWith("$path/") }
        }
        drop(rawFileIds)
        drop(rawDirIds)
        rawVerifiedAt.keys.removeAll { it == path || it.startsWith("$path/") }
        drop(docIds)
        drop(dirDocIds)
        rawListings.clear()
    }

    /** Metadata for whatever is at [path] (file or directory), or null. Never consults skip rules. */
    private suspend fun rawLookup(path: String): DocEntry? {
        if (path.isEmpty()) return queryDoc(rootDocId)
        val leaf = path.substringAfterLast('/')
        val cached = rawFileIds[path] ?: rawDirIds[path] ?: docIds[path] ?: dirDocIds[path]
        if (cached != null) {
            val meta = queryDoc(cached)
            if (meta != null && meta.name == leaf) return meta.also { remember(path, it) }
            forgetTree(path)
        }
        if (knownAbsent(path)) return null
        backend.docIdForPath(path)?.let { id ->
            val meta = queryDoc(id) ?: return null
            // A different-case match on a case-insensitive volume isn't this
            // name: settle it from the parent's listing below.
            if (meta.name == leaf) return meta.also { remember(path, it) }
        }
        val parent = rawLookup(path.substringBeforeLast('/', ""))?.takeIf { it.isDir } ?: return null
        return rawChild(path.substringBeforeLast('/', ""), parent.docId, leaf)
    }

    /** Finds [leaf] among the children of [parentDocId] (at [parentPath]), caching every child seen. */
    private suspend fun rawChild(parentPath: String, parentDocId: String, leaf: String): DocEntry? {
        var found: DocEntry? = null
        val children = queryChildren(parentDocId) ?: return null
        // Only a finished listing may answer "missing" for later lookups.
        if (children.complete) recordListing(parentPath, children.metas)
        children.metas.forEach { child ->
            val childPath = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
            remember(childPath, child)
            if (child.name == leaf) found = child
        }
        return found
    }

    /** Document id of directory [path], creating it and missing ancestors. Null on failure or a file in the way. */
    private suspend fun ensureRawDir(path: String): String? {
        if (path.isEmpty()) return rootDocId
        rawLookup(path)?.let { return if (it.isDir) it.docId else null }
        val parentId = ensureRawDir(path.substringBeforeLast('/', "")) ?: return null
        val leaf = path.substringAfterLast('/')
        val docId = backend.createDir(parentId, leaf).also { listingChanged(Unit) }.getOrNull() ?: return null
        rawDirIds[path] = docId
        return docId
    }

    private suspend fun rawFileId(name: String): String? {
        val path = name.trim('/')
        return freshFileId(path) ?: rawLookup(path)?.takeIf { !it.isDir }?.docId
    }

    override suspend fun openInput(name: String): InputStream = withContext(Dispatchers.IO) {
        val id = rawFileId(name) ?: throw FileNotFoundException(name)
        backend.openRead(id).getOrNull() ?: throw FileNotFoundException(name)
    }

    override suspend fun createBinary(name: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val path = name.trim('/')
        if (rawLookup(path) != null) return@withContext StorageError.NameTaken
        val (dir, leaf) = splitPath(path)
        val parentId = ensureRawDir(dir) ?: return@withContext StorageError.ProviderError(null)
        // The backend keeps the exact name (octet-stream, so no extension is
        // appended) and doesn't leave a stray "x (1).jpg" behind when it can't.
        val created = backend.createFile(parentId, leaf)
        listingChanged(Unit)
        when (created) {
            is StorageResult.Ok -> {
                rawFileIds[path] = created.value
                ok
            }
            is StorageError -> created
        }
    }

    override suspend fun openOutput(name: String): OutputStream = withContext(Dispatchers.IO) {
        val id = rawFileId(name) ?: throw FileNotFoundException(name)
        backend.openWrite(id).getOrNull() ?: throw FileNotFoundException(name)
    }

    override suspend fun statRaw(name: String): FileEntry? = withContext(Dispatchers.IO) {
        val path = name.trim('/')
        rawLookup(path)?.takeIf { !it.isDir }?.let { FileEntry(path, it.lastModified, it.size) }
    }

    override suspend fun renameRaw(name: String, newLeaf: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        require('/' !in newLeaf && newLeaf.isNotBlank()) { "Bad leaf name: $newLeaf" }
        val path = name.trim('/')
        val meta = rawLookup(path)?.takeIf { !it.isDir } ?: return@withContext StorageError.NotFound
        if (!meta.capabilities.rename) return@withContext StorageError.Unsupported(StorageOp.Rename)
        val dir = path.substringBeforeLast('/', "")
        val newPath = if (dir.isEmpty()) newLeaf else "$dir/$newLeaf"
        if (rawLookup(newPath) != null) return@withContext StorageError.NameTaken
        val renamed = backend.rename(meta.docId, newLeaf)
        listingChanged(Unit)
        when (renamed) {
            is StorageResult.Ok -> {
                forgetTree(path)
                rawFileIds[newPath] = renamed.value
                ok
            }
            is StorageError -> renamed
        }
    }

    override suspend fun deleteRaw(name: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val path = name.trim('/')
        val meta = rawLookup(path)?.takeIf { !it.isDir } ?: return@withContext StorageError.NotFound
        if (!meta.capabilities.delete) return@withContext StorageError.Unsupported(StorageOp.Delete)
        val result = backend.delete(meta.docId)
        listingChanged(Unit)
        if (result.isOk) forgetTree(path)
        result
    }

    override suspend fun dirExists(dir: String): Boolean = withContext(Dispatchers.IO) {
        val path = dir.trim('/')
        path.isEmpty() || rawLookup(path)?.isDir == true
    }

    override suspend fun listDirRaw(dir: String): List<FileEntry> = withContext(Dispatchers.IO) {
        rawChildren(dir.trim('/')).filter { !it.second.isDir }
            .map { (path, m) -> FileEntry(path, m.lastModified, m.size) }
            .sortedBy { it.name }
    }

    override suspend fun listSubdirs(dir: String): List<String> = withContext(Dispatchers.IO) {
        rawChildren(dir.trim('/')).filter { it.second.isDir }.map { it.second.name }.sorted()
    }

    /**
     * True only when the provider finished listing [dir] and it holds nothing.
     * Unlike an empty [listDirRaw], a loading, partial or failed listing is not
     * "empty": callers delete directories on this answer.
     */
    override suspend fun isDirEmptyRaw(dir: String): Boolean = withContext(Dispatchers.IO) {
        val path = dir.trim('/')
        val docId = if (path.isEmpty()) rootDocId else rawLookup(path)?.takeIf { it.isDir }?.docId
            ?: return@withContext false
        val listing = backend.listChildren(docId)
        listing is Listing.Complete && listing.entries.isEmpty()
    }

    /**
     * (path, meta) for each child of directory [path]; empty when it isn't a
     * directory. A listing the provider didn't finish returns the rows it had
     * (use [isDirEmptyRaw], not emptiness of this, to decide a directory is empty).
     */
    private suspend fun rawChildren(path: String): List<Pair<String, DocEntry>> {
        val docId = if (path.isEmpty()) rootDocId else rawLookup(path)?.takeIf { it.isDir }?.docId
            ?: return emptyList()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = queryChildren(docId) ?: return emptyList()
        if (children.complete) recordListing(path, children.metas)
        return children.metas.map { child ->
            (prefix + child.name).let { childPath -> remember(childPath, child); childPath to child }
        }
    }

    override suspend fun deleteDir(dir: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val path = dir.trim('/')
        require(path.isNotEmpty()) { "Refusing to delete the vault root" }
        val meta = rawLookup(path)?.takeIf { it.isDir } ?: return@withContext StorageError.NotFound
        if (!meta.capabilities.delete) return@withContext StorageError.Unsupported(StorageOp.Delete)
        val result = deleteTree(path, meta.docId)
        forgetTree(path)
        result
    }

    /**
     * Deletes a directory document. Providers delete children along with a
     * directory (the platform's local-storage one does); for any that refuse
     * a non-empty one, empty it first and retry.
     */
    private suspend fun deleteTree(path: String, docId: String): StorageResult<Unit> {
        val first = backend.delete(docId).also { listingChanged(Unit) }
        if (first.isOk) return first
        val children = queryChildren(docId) ?: return first
        for (child in children.metas) {
            val childPath = "$path/${child.name}"
            val result = if (child.isDir) {
                deleteTree(childPath, child.docId)
            } else {
                backend.delete(child.docId).also { listingChanged(Unit) }
            }
            if (result is StorageError) return result
        }
        return backend.delete(docId).also { listingChanged(Unit) }
    }

    override suspend fun moveDir(src: String, dstParent: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val srcPath = src.trim('/')
        val parentPath = dstParent.trim('/')
        require(srcPath.isNotEmpty()) { "Refusing to move the vault root" }
        val meta = rawLookup(srcPath)?.takeIf { it.isDir } ?: return@withContext StorageError.NotFound
        // Copy + delete is the fallback; without delete it would leave the tree in two places.
        if (!meta.capabilities.canRelocate) return@withContext StorageError.Unsupported(StorageOp.Move)
        val dstPath = if (parentPath.isEmpty()) meta.name else "$parentPath/${meta.name}"
        if (dstPath == srcPath || dstPath.startsWith("$srcPath/")) return@withContext StorageError.NameTaken
        if (rawLookup(dstPath) != null) return@withContext StorageError.NameTaken
        val srcParentId = rawLookup(srcPath.substringBeforeLast('/', ""))?.docId
            ?: return@withContext StorageError.NotFound
        val dstParentId = ensureRawDir(parentPath) ?: return@withContext StorageError.ProviderError(null)

        // A provider move isn't available everywhere (API 24, and not every provider).
        val moved: StorageResult<String> = if (meta.capabilities.move) {
            backend.move(meta.docId, srcParentId, dstParentId).also { listingChanged(Unit) }
        } else {
            StorageError.Unsupported(StorageOp.Move)
        }
        forgetTree(srcPath)
        forgetTree(dstPath)
        when (moved) {
            is StorageResult.Ok -> return@withContext ok
            is StorageError -> if (!meta.capabilities.delete) return@withContext moved
        }

        // Fallback: copy the tree, and only delete the source once it's all there.
        val copied = copyTree(srcPath, meta.docId, dstPath)
        if (copied is StorageError) {
            rawLookup(dstPath)?.takeIf { it.isDir }?.let { deleteTree(dstPath, it.docId) }
            forgetTree(dstPath)
            return@withContext copied
        }
        deleteTree(srcPath, meta.docId).also { forgetTree(srcPath) }
    }

    private suspend fun copyTree(srcPath: String, srcDocId: String, dstPath: String): StorageResult<Unit> {
        val dstId = ensureRawDir(dstPath) ?: return StorageError.ProviderError(null)
        val children = queryChildren(srcDocId) ?: return StorageError.ProviderError(null)
        // The caller deletes the source after a successful copy: a copy from a
        // listing that wasn't finished would silently drop the unseen files.
        if (!children.complete) return StorageError.Offline
        for (child in children.metas) {
            if (child.isDir) {
                val sub = copyTree("$srcPath/${child.name}", child.docId, "$dstPath/${child.name}")
                if (sub is StorageError) return sub
                continue
            }
            val created = backend.createFile(dstId, child.name).also { listingChanged(Unit) }
            val createdId = when (created) {
                is StorageResult.Ok -> created.value
                is StorageError -> return created
            }
            val input = when (val r = backend.openRead(child.docId)) {
                is StorageResult.Ok -> r.value
                is StorageError -> return r
            }
            val output = when (val r = backend.openWrite(createdId)) {
                is StorageResult.Ok -> r.value
                is StorageError -> { input.close(); return r }
            }
            val copied = runCatching {
                input.use { i -> output.use { o -> i.copyTo(o, COPY_BUFFER) } }
            }
            copied.exceptionOrNull()?.let { return StorageError.ProviderError(it.message) }
        }
        return ok
    }

    override suspend fun contentUri(name: String): Uri? = withContext(Dispatchers.IO) {
        rawFileId(name)?.let(backend::contentUri)
    }

    private fun splitPath(path: String): Pair<String, String> {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" to path else path.substring(0, i) to path.substring(i + 1)
    }
}

private const val COPY_BUFFER = 64 * 1024

/** Folders listed at once during a tree walk, so slow (loading) ones overlap. */
private const val WALK_CONCURRENCY = 4

/** How long a just-queried raw file id is reused for opens without another query. */
internal const val RAW_FRESH_MS = 2_000L
