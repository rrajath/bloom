package com.rrajath.grove.vault

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    /** The [FileStore] backing this vault, for callers (e.g. Dailies) that need
     *  it directly rather than going through a `Vault`-level method. */
    fun fileStore(): FileStore = store

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
     * appended under [dir]. Missing directories are created. Returns false if
     * the resulting path is already taken.
     */
    suspend fun createNotebook(name: String, dir: String = ""): Boolean {
        val leaf = if (name.endsWith(".org")) name else "$name.org"
        val fileName = vaultPath(dir, leaf)
        if (pathTaken(fileName)) return false
        return store.create(fileName)
    }

    /**
     * Create an empty file at exactly [path] (vault-relative), with no `.org`
     * appended, e.g. an Emacs-style `agenda.org_archive` archive target.
     * Returns false if the path is already taken.
     */
    suspend fun createFile(path: String): Boolean {
        if (pathTaken(path)) return false
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
     * the target exists or the source is missing.
     */
    suspend fun renameNotebook(oldName: String, newName: String): Boolean {
        val target = if (newName.endsWith(".org")) newName else "$newName.org"
        // Block a rename onto an existing (case-insensitively) file, but allow a
        // case-only self-rename (Work.org -> work.org).
        if (!target.equals(oldName, ignoreCase = true) && pathTaken(target)) return false
        val ok = store.rename(oldName, target)
        if (ok) evictParse(oldName)
        return ok
    }

    /**
     * Move a notebook to a different directory, keeping its file name. [newDir]
     * is a vault-relative directory ("" for the root). Returns the new path on
     * success, or null if the move failed or was a no-op.
     */
    suspend fun moveNotebook(path: String, newDir: String): String? {
        val leaf = path.substringAfterLast('/')
        val target = vaultPath(newDir, leaf)
        if (target == path) return null
        val ok = store.rename(path, target)
        if (ok) evictParse(path)
        return if (ok) target else null
    }

    /**
     * Permanently delete a notebook. SAF has no OS-level trash, so the file is
     * removed outright; recovery relies on the sync backend's own history
     * (Syncthing file versioning). Any directory left empty by the delete is
     * pruned so stale folders don't linger.
     */
    suspend fun deleteNotebook(name: String): Boolean {
        val ok = store.delete(name)
        if (ok) {
            evictParse(name)
            store.pruneEmptyDirs(name.substringBeforeLast('/', ""))
        }
        return ok
    }

    /**
     * Rename a folder in place, keeping its parent directory. Every `.org`
     * notebook under [dir] is moved to the matching path under the new
     * directory; missing intermediate directories are created by the FileStore.
     * Returns the new vault-relative directory path, or null on a no-op (name
     * unchanged) or if any destination path is already taken.
     */
    suspend fun renameFolder(dir: String, newName: String): String? {
        val trimmed = dir.trim('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/', "")
        val leaf = newName.trim().trim('/')
        if (leaf.isEmpty()) return null
        val newDir = vaultPath(parent, leaf)
        if (newDir == trimmed) return null

        val prefix = "$trimmed/"
        val moves = listOrgFiles()
            .map { it.name }
            .filter { it.startsWith(prefix) }
            .map { it to newDir + "/" + it.removePrefix(prefix) }
        if (moves.isEmpty()) return null
        // Reject if the destination folder already exists (any file already sits
        // under it) or a moved file's target path is taken — both compared
        // case-insensitively, since the sync target is often a case-insensitive
        // filesystem.
        val allFiles = store.list().map { it.name }
        if (allFiles.any { it.startsWith("$newDir/", ignoreCase = true) }) return null
        if (moves.any { (_, to) -> allFiles.any { it.equals(to, ignoreCase = true) } }) return null

        var movedAny = false
        for ((from, to) in moves) {
            if (store.rename(from, to)) {
                evictParse(from)
                movedAny = true
            }
        }
        if (movedAny) store.pruneEmptyDirs(trimmed)
        return if (movedAny) newDir else null
    }

    /**
     * Permanently delete every `.org` notebook under [dir] (recursively), then
     * prune the emptied directory. See [deleteNotebook] on the lack of an OS
     * trash. Returns the count of files deleted.
     */
    suspend fun deleteFolder(dir: String): Int {
        val trimmed = dir.trim('/')
        val prefix = "$trimmed/"
        val deleted = listOrgFiles()
            .map { it.name }
            .filter { it.startsWith(prefix) }
            .count {
                val ok = store.delete(it)
                if (ok) evictParse(it)
                ok
            }
        if (deleted > 0) store.pruneEmptyDirs(trimmed)
        return deleted
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
        if (store.stat(fileName) == null) store.create(fileName)
        store.write(fileName, content)
        val entry = if (parsed != null) store.stat(fileName) else null
        if (entry != null) {
            val key = CacheKey(entry.name, entry.lastModified, entry.size)
            cacheMutex.withLock {
                cache.keys.removeAll { it.name == fileName }
                if (cache.size > 64) cache.remove(cache.keys.first())
                cache[key] = parsed!!
            }
        } else {
            evictParse(fileName)
        }
    }

    /** Drop every cached parse of [fileName] (any revision). */
    private suspend fun evictParse(fileName: String) = cacheMutex.withLock {
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
        val deferred = cacheMutex.withLock {
            cache[key]?.let { return it }
            inFlight.getOrPut(key) {
                owner = true
                CompletableDeferred()
            }
        }
        if (!owner) return deferred.await()

        return try {
            val doc = withContext(parseDispatcher) { OrgParser.parse(store.read(entry.name), keywords) }
            cacheMutex.withLock {
                // Drop stale parses of the same file, cap total cache size.
                cache.keys.removeAll { it.name == entry.name }
                if (cache.size > 64) cache.remove(cache.keys.first())
                cache[key] = doc
                inFlight.remove(key)
            }
            deferred.complete(doc)
            doc
        } catch (e: Throwable) {
            cacheMutex.withLock { inFlight.remove(key) }
            deferred.completeExceptionally(e)
            throw e
        }
    }
}
