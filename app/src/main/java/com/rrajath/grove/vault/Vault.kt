package com.rrajath.grove.vault

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.storage.isOk
import com.rrajath.grove.vault.storage.map
import com.rrajath.grove.vault.storage.VaultCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A notebook = one .org file in the vault. [fileName] is the identity: a
 * vault-relative path like `projects/acme.org` (bare name for a root file).
 */
data class Notebook(
    val fileName: String,
    val noteCount: Int,
    val lastModified: Long,
) {
    /** Vault-relative directory holding this file; "" for a root-level file. */
    val dir: String get() = fileName.substringBeforeLast('/', "")

    /** File name without its directory or `.org` extension. */
    val displayName: String get() = fileName.substringAfterLast('/').removeSuffix(".org")
}

/** Join a vault-relative directory and a file name into a path. "" dir → bare name. */
fun vaultPath(dir: String, name: String): String =
    if (dir.isBlank()) name else "${dir.trim('/')}/$name"

/**
 * Matches an externally-opened .org file (tapped in a file manager, or
 * launched via a VIEW/EDIT intent per the manifest's file-open intent-filter)
 * against a notebook already indexed in this vault. The incoming URI usually
 * comes from a different `content://` authority than our own SAF tree grant,
 * so there's nothing to match on except the file's name; compared
 * case-insensitively since providers don't agree on casing. Pure Kotlin so
 * this is JVM-unit-testable independent of the Uri/ContentResolver plumbing
 * that resolves [requestedFileName] on the Android side.
 *
 * Now that the vault is a tree, [requestedFileName] (usually a bare name from a
 * foreign provider) can match several notebooks in different folders. Order of
 * preference: an exact vault-relative-path match, then a unique basename match,
 * then — if the basename is ambiguous — the first such notebook in list order.
 */
fun matchOpenedFileToNotebook(requestedFileName: String, notebooks: List<Notebook>): Notebook? {
    notebooks.firstOrNull { it.fileName.equals(requestedFileName, ignoreCase = true) }?.let { return it }
    val wantedBase = requestedFileName.substringAfterLast('/')
    return notebooks.firstOrNull {
        it.fileName.substringAfterLast('/').equals(wantedBase, ignoreCase = true)
    }
}

/** Separators between the components of a Denote file name. */
private val DENOTE_COMPONENT_PREFIXES = listOf("@@", "==", "--", "__")

/**
 * The Denote identifier in [fileName], or null when it has none. Denote names a
 * note `IDENTIFIER==SIGNATURE--TITLE__KEYWORDS.org` (all but the identifier
 * optional), e.g. `20240101T120000--trip-plan__travel.org`. When the
 * components are reordered (`denote-file-name-components-order`), the
 * identifier is marked with a `@@` prefix instead of leading the name.
 */
fun denoteIdentifier(fileName: String): String? {
    val stem = fileName.substringAfterLast('/').substringBefore('.')
    val marked = stem.indexOf("@@")
    val start = when {
        marked >= 0 -> marked + 2
        DENOTE_COMPONENT_PREFIXES.any { stem.startsWith(it) } -> return null
        else -> 0
    }
    val end = DENOTE_COMPONENT_PREFIXES
        .map { stem.indexOf(it, start) }
        .filter { it >= 0 }
        .minOrNull() ?: stem.length
    return stem.substring(start, end).ifEmpty { null }
}

/**
 * The notebook whose Denote [identifier] (see [denoteIdentifier]) matches, from
 * anywhere in the vault. Denote identifiers are unique, but if a copy of a note
 * leaves two files with the same one, a file in [preferredDir] (the linking
 * file's directory) wins, then the first in list order.
 */
fun matchDenoteNotebook(identifier: String, notebooks: List<Notebook>, preferredDir: String = ""): Notebook? {
    val wanted = identifier.trim()
    if (wanted.isEmpty()) return null
    val matches = notebooks.filter { denoteIdentifier(it.fileName) == wanted }
    return matches.firstOrNull { it.dir == preferredDir } ?: matches.firstOrNull()
}

/**
 * A storage failure surfaced as an exception, for the callers that save text
 * and can't usefully carry on without it. [Vault.save] throws it; code that
 * wants to tell the user why uses [Vault.trySave] and reads the [error].
 */
class StorageException(val error: StorageError) : java.io.IOException("Storage operation failed: $error")

/**
 * Vault facade over a [FileStore]: lists notebooks (applying ignore rules),
 * parses documents, creates new notebooks. Pure Kotlin; android code supplies
 * the FileStore. Parses are cached by (name, mtime, size).
 */
class Vault(
    private val store: FileStore,
    private val keywords: OrgKeywords = OrgKeywords.DEFAULT,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class CacheKey(val name: String, val mtime: Long, val size: Long)

    // accessOrder = true → iteration starts at the least-recently-used entry,
    // so the size-cap eviction in document() drops the LRU parse, not the oldest.
    // accessOrder also means a *read* structurally mutates the list, so every
    // touch of this map goes through [cacheMutex]: Vault is a process-wide
    // singleton reached concurrently by SyncEngine and any number of ViewModels,
    // and a racing LinkedHashMap.get can loop forever rather than throw.
    private val cache = LinkedHashMap<CacheKey, OrgDocument>(16, 0.75f, true)
    private val cacheMutex = Mutex()

    // One in-flight parse per key: a second caller for the same (name, mtime,
    // size) awaits the first's result instead of racing it with its own
    // read+parse. Only ever touched under [cacheMutex].
    private val inFlight = mutableMapOf<CacheKey, CompletableDeferred<OrgDocument>>()

    /**
     * Per file, bumped by every save and eviction. A parse only enters [cache]
     * if no save happened while it read: on a provider whose mtime and size lag
     * its writes (Seafile), a save can leave the key unchanged, so a parse of the
     * old text that finished late would be served as current. Under [cacheMutex].
     */
    private val generations = mutableMapOf<String, Long>()

    /** The [FileStore] backing this vault, for callers (e.g. Dailies) that need
     *  it directly rather than going through a `Vault`-level method. */
    fun fileStore(): FileStore = store

    /** What the vault's provider lets other apps do, per file and folder (see [FileStore.capabilities]). */
    val capabilities: StateFlow<VaultCapabilities> get() = store.capabilities

    suspend fun notebooks(): List<Notebook> =
        listOrgFiles().map { entry ->
            val doc = document(entry)
            Notebook(entry.name, doc.headlines.count { it.level == 1 }, entry.lastModified)
        }

    /**
     * Every `.org` file in the vault (sync-conflict copies excluded; ignored
     * files/folders are already excluded by the `FileStore`), without parsing
     * any of them. For callers that only need file paths — [renameFolder] and
     * [deleteFolder] used to go through [notebooks], which parses every file
     * just to throw the parse away (PERFORMANCE_AUDIT_2026-09-16 F7).
     */
    private suspend fun listOrgFiles(): List<FileEntry> =
        store.list().filter {
            it.name.endsWith(".org") && !it.name.contains(".sync-conflict-")
        }

    /**
     * True when [fileName] holds exactly the text whose [contentHash] a caller
     * recorded when it last loaded or saved the file; false when [contentHash] is
     * null or the file is missing, unreadable or different. Reads the file. For
     * telling a real change from a revision marker that moved on its own: a
     * provider like Seafile reports a write's new mtime and size only once its
     * listing cache refreshes, seconds after Bloom's own save.
     */
    suspend fun hasContent(fileName: String, contentHash: String?): Boolean {
        if (contentHash == null) return false
        val text = try {
            store.read(store.canonicalName(fileName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return contentHash(text) == contentHash
    }

    /** Current revision marker ("mtime:size") of a file, or null if missing. */
    suspend fun revision(fileName: String): String? =
        store.stat(fileName)?.let { "${it.lastModified}:${it.size}" }

    /** [fileName]'s device path, or the vault-relative [fileName] itself when the store can't tell. */
    fun absolutePath(fileName: String): String = store.absolutePath(fileName) ?: fileName

    suspend fun open(fileName: String): OrgDocument? {
        val entry = store.stat(fileName) ?: return null
        return document(entry)
    }

    /**
     * Create an empty notebook file, in [dir] (a vault-relative directory, ""
     * for the vault root). [name] may itself contain `/` segments, which are
     * appended under [dir]. Missing directories are created. [content] is
     * written in the same step, so a lazy-create provider uploads once.
     * [StorageError.NameTaken] if the resulting path is already taken.
     */
    suspend fun createNotebook(name: String, dir: String = "", content: String = ""): StorageResult<Unit> {
        val leaf = if (name.endsWith(".org")) name else "$name.org"
        val fileName = vaultPath(dir, leaf)
        if (pathTaken(fileName)) return StorageError.NameTaken
        return if (content.isEmpty()) store.create(fileName) else store.createWithContent(fileName, content)
    }

    /**
     * Create an empty file at exactly [path] (vault-relative), with no `.org`
     * appended, e.g. an Emacs-style `agenda.org_archive` archive target.
     * [StorageError.NameTaken] if the path is already taken.
     */
    suspend fun createFile(path: String): StorageResult<Unit> {
        if (pathTaken(path)) return StorageError.NameTaken
        return store.create(path)
    }

    /**
     * Whether [path] already names a file in the vault, compared
     * case-insensitively. SAF providers over FAT/exFAT (and the stock Documents
     * provider) treat file names case-insensitively, so the exact-name
     * [FileStore.exists] check would miss `Work.org` when asked about `work.org`
     * and let a colliding create/rename through. The case-insensitive [list]
     * scan strictly subsumes an exact-name lookup, so `exists` is redundant here;
     * dropping it avoids a second tree walk on every create/rename. Same
     * rationale as [matchOpenedFileToNotebook].
     */
    private suspend fun pathTaken(path: String): Boolean =
        store.list().any { it.name.equals(path, ignoreCase = true) }

    /**
     * Rename and/or move a notebook. [newName] may be a bare name (kept in the
     * same directory) or a full vault-relative path (moved). Returns false if
     * the target exists ([StorageError.NameTaken]) or the source is missing.
     */
    suspend fun renameNotebook(oldName: String, newName: String): StorageResult<Unit> {
        val target = if (newName.endsWith(".org")) newName else "$newName.org"
        // Block a rename onto an existing (case-insensitively) file, but allow a
        // case-only self-rename (Work.org -> work.org).
        if (!target.equals(oldName, ignoreCase = true) && pathTaken(target)) return StorageError.NameTaken
        val result = store.rename(oldName, target)
        if (result.isOk) evictParse(oldName)
        return result
    }

    /**
     * Move a notebook to a different directory, keeping its file name. [newDir]
     * is a vault-relative directory ("" for the root). Returns the new path on
     * success; a move onto its own path is [StorageError.NameTaken].
     */
    suspend fun moveNotebook(path: String, newDir: String): StorageResult<String> {
        val leaf = path.substringAfterLast('/')
        val target = vaultPath(newDir, leaf)
        if (target == path) return StorageError.NameTaken
        val result = store.rename(path, target)
        if (result.isOk) evictParse(path)
        return result.map { target }
    }

    /**
     * Permanently delete a notebook. SAF has no OS-level trash, so the file is
     * removed outright; recovery relies on the sync backend's own history
     * (Syncthing file versioning). Any directory left empty by the delete is
     * pruned so stale folders don't linger.
     */
    suspend fun deleteNotebook(name: String): StorageResult<Unit> {
        val result = store.delete(name)
        if (result.isOk) {
            evictParse(name)
            store.pruneEmptyDirs(name.substringBeforeLast('/', ""))
        }
        return result
    }

    /**
     * Rename a folder in place, keeping its parent directory. Every `.org`
     * notebook under [dir] is moved to the matching path under the new
     * directory; missing intermediate directories are created by the FileStore.
     * Returns the new vault-relative directory path. A name unchanged, or any
     * destination path already taken, is [StorageError.NameTaken]; a folder with
     * no notebooks is [StorageError.NotFound]. Stops at the first move the
     * provider refuses and returns its error (earlier moves stay done).
     */
    suspend fun renameFolder(dir: String, newName: String): StorageResult<String> {
        val trimmed = dir.trim('/')
        if (trimmed.isEmpty()) return StorageError.NotFound
        val parent = trimmed.substringBeforeLast('/', "")
        val leaf = newName.trim().trim('/')
        if (leaf.isEmpty()) return StorageError.NameTaken
        val newDir = vaultPath(parent, leaf)
        if (newDir == trimmed) return StorageError.NameTaken

        val prefix = "$trimmed/"
        val moves = listOrgFiles()
            .map { it.name }
            .filter { it.startsWith(prefix) }
            .map { it to newDir + "/" + it.removePrefix(prefix) }
        if (moves.isEmpty()) return StorageError.NotFound
        // Refuse before moving anything: a provider that can't move these files
        // would otherwise leave the folder half renamed.
        val caps = store.capabilities.value
        if (moves.any { (from, _) -> !caps.ofFile(from).canRelocate }) return StorageError.Unsupported(StorageOp.Move)
        // Reject if the destination folder already exists (any file already sits
        // under it) or a moved file's target path is taken — both compared
        // case-insensitively, since the sync target is often a case-insensitive
        // filesystem.
        val allFiles = store.list().map { it.name }
        if (allFiles.any { it.startsWith("$newDir/", ignoreCase = true) }) return StorageError.NameTaken
        if (moves.any { (_, to) -> allFiles.any { it.equals(to, ignoreCase = true) } }) return StorageError.NameTaken

        var movedAny = false
        var failure: StorageError? = null
        for ((from, to) in moves) {
            val result = store.rename(from, to)
            if (result is StorageError) {
                failure = result
                break
            }
            evictParse(from)
            movedAny = true
        }
        if (movedAny) store.pruneEmptyDirs(trimmed)
        return failure ?: StorageResult.Ok(newDir)
    }

    /**
     * Permanently delete every `.org` notebook under [dir] (recursively), then
     * prune the emptied directory. See [deleteNotebook] on the lack of an OS
     * trash. Returns the count of files deleted; stops at the first delete the
     * provider refuses and returns its error (earlier deletes stay done).
     */
    suspend fun deleteFolder(dir: String): StorageResult<Int> {
        val trimmed = dir.trim('/')
        val prefix = "$trimmed/"
        var deleted = 0
        var failure: StorageError? = null
        val doomed = listOrgFiles().map { it.name }.filter { it.startsWith(prefix) }
        // All or nothing up front: don't delete half a folder and then hit a file the provider protects.
        val caps = store.capabilities.value
        if (doomed.any { !caps.ofFile(it).delete }) return StorageError.Unsupported(StorageOp.Delete)
        for (name in doomed) {
            val result = store.delete(name)
            if (result is StorageError) {
                failure = result
                break
            }
            evictParse(name)
            deleted++
        }
        if (deleted > 0) store.pruneEmptyDirs(trimmed)
        return failure ?: StorageResult.Ok(deleted)
    }

    /**
     * Write [content] to [fileName]. If the caller already has the parse of
     * [content] on hand (nearly every mutation does — it re-parses to compute
     * the new document before writing it back), pass it as [parsed] so the
     * cache is primed under the post-write (mtime, size) key instead of
     * evicted; the next read (e.g. Read mode's return-from-editor reload)
     * hits the cache instead of re-parsing the file it just wrote.
     */
    suspend fun save(fileName: String, content: String, parsed: OrgDocument? = null) {
        val result = trySave(fileName, content, parsed)
        if (result is StorageError) throw StorageException(result)
    }

    /**
     * [save] that reports a failure as a [StorageError] instead of throwing, for
     * callers that tell the user why the text wasn't saved. Nothing is written
     * and the cache is untouched on failure.
     */
    suspend fun trySave(fileName: String, content: String, parsed: OrgDocument? = null): StorageResult<Unit> {
        // On a case-insensitive store `inbox.org` is an existing `Inbox.org`: write
        // that file under its real name (the line-ending memo and cache key on it).
        var name = store.canonicalName(fileName)
        // A new file is created with its content in one step (one upload on a lazy-create provider).
        val createdWithContent = if (store.stat(name) == null) {
            val created = store.createWithContent(name, content)
            // NameTaken: the file turned up under another spelling meanwhile; write to that one.
            if (created is StorageError && created != StorageError.NameTaken) return created
            if (created is StorageError) name = store.canonicalName(fileName)
            created.isOk
        } else {
            false
        }
        if (!createdWithContent) {
            val written = store.write(name, content)
            if (written is StorageError) return written
        }
        val entry = if (parsed != null) store.stat(name) else null
        if (entry != null) {
            val key = CacheKey(entry.name, entry.lastModified, entry.size)
            cacheMutex.withLock {
                generations[name] = (generations[name] ?: 0) + 1
                inFlight.keys.removeAll { it.name == name }
                cache.keys.removeAll { it.name == name }
                if (cache.size > 64) cache.remove(cache.keys.first())
                cache[key] = parsed!!
            }
        } else {
            evictParse(name)
        }
        return StorageResult.Ok(Unit)
    }

    /** Drop every cached parse of [fileName] (any revision). */
    private suspend fun evictParse(fileName: String) = cacheMutex.withLock {
        generations[fileName] = (generations[fileName] ?: 0) + 1
        // A parse already reading keeps its waiters, but later callers start afresh.
        inFlight.keys.removeAll { it.name == fileName }
        cache.keys.removeAll { it.name == fileName }
    }

    /**
     * Look up or parse [entry]'s document. The cache check/insert is the only
     * part done under [cacheMutex]; the read+parse itself runs on
     * [parseDispatcher], off both the mutex and the caller's dispatcher (every
     * UI call site is on Main), so it no longer competes with a screen's enter
     * animation or blocks an unrelated notebook's open. Concurrent callers for
     * the same key share one parse via [inFlight] instead of each doing their
     * own read+parse of the same bytes.
     */
    private suspend fun document(entry: FileEntry): OrgDocument {
        val key = CacheKey(entry.name, entry.lastModified, entry.size)

        var owner = false
        var generation = 0L
        val deferred = cacheMutex.withLock {
            cache[key]?.let { return it }
            generation = generations[entry.name] ?: 0
            inFlight.getOrPut(key) {
                owner = true
                CompletableDeferred()
            }
        }
        if (!owner) return deferred.await()

        return try {
            val doc = withContext(parseDispatcher) { OrgParser.parse(store.read(entry.name), keywords) }
            cacheMutex.withLock {
                // A save landed while this read: it may hold the old text, so serve it to
                // its waiters but don't cache it (the next lookup reads again).
                if ((generations[entry.name] ?: 0) == generation) {
                    // Drop stale parses of the same file, cap total cache size.
                    cache.keys.removeAll { it.name == entry.name }
                    if (cache.size > 64) cache.remove(cache.keys.first())
                    cache[key] = doc
                }
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
            deferred.complete(doc)
            doc
        } catch (e: Throwable) {
            cacheMutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
            deferred.completeExceptionally(e)
            throw e
        }
    }
}

/** SHA-256 of [text] (UTF-8), hex: what [Vault.hasContent] compares. */
fun contentHash(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
