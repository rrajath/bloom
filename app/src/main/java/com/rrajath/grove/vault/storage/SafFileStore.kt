package com.rrajath.grove.vault.storage

import com.rrajath.grove.attach.AttachSkipRoots
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.IgnorePatterns
import com.rrajath.grove.vault.SKIP_ATTACH_SHARDS
import com.rrajath.grove.vault.TreeListing
import com.rrajath.grove.vault.isSkippedVaultDir
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
 * Storage Access Framework implementation over a persisted tree URI
 * (ACTION_OPEN_DOCUMENT_TREE).
 *
 * The vault is a tree: [list] walks every subdirectory (one child-documents
 * cursor query per directory), skipping dot-directories and attachment shards
 * ([isSkippedVaultDir]). Names are
 * vault-relative paths with `/` separators. Writes use mode "wt": without
 * truncate, writing shorter content than the existing file leaves trailing
 * garbage.
 *
 * Path -> document-id caches (files and directories) are warmed by [list] and
 * rebuilt on a cache miss. Recursive listing costs one query per directory;
 * fine for typical org vaults, a watch item for very large trees.
 */
class SafFileStore(
    private val context: Context,
    private val treeUri: Uri,
    private val ignore: IgnorePatterns = IgnorePatterns(""),
    skipAttachShards: Boolean = SKIP_ATTACH_SHARDS,
    /** Where shards are skipped (from the attach config); a config change builds a new store. */
    attachRoots: AttachSkipRoots = AttachSkipRoots.DataOnly,
    /** Monotonic millis, for how long a raw file id counts as just verified. */
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
    /** Where listings come from; tests shorten the loading deadline through it. */
    private val backend: StorageBackend = SafBackend(context, treeUri),
) : FileStore {

    private val skipRoots = attachRoots.takeIf { skipAttachShards }

    private val resolver get() = context.contentResolver
    private val rootDocId get() = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Shared storage and SD cards (FAT/exFAT) match names case-insensitively:
     * there `inbox.org` *is* an existing `Inbox.org`, and creating it makes the
     * provider pick a free name (`inbox (1).org`) instead. Lookups here then
     * resolve to the existing file's spelling ([resolve]).
     */
    private val caseInsensitive = treeUri.authority == EXTERNAL_STORAGE_AUTHORITY

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

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

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
                            visit(path, child.docId)
                        } else {
                            if (ignore.isFileIgnored(child.name, path)) continue
                            files[path] = child.docId
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
                seenDirs += path
            } else {
                if (ignore.isFileIgnored(child.name, path)) continue
                docIds[path] = child.docId
                seenFiles += path
                entries.add(FileEntry(path, child.lastModified, child.size))
            }
        }
        if (listing !is Listing.Complete) return entries
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        fun isDirectChild(path: String) = path.startsWith(prefix) && '/' !in path.removePrefix(prefix)
        docIds.keys.removeAll { isDirectChild(it) && it !in seenFiles }
        // A vanished subdirectory takes its cached descendants with it.
        val goneDirs = dirDocIds.keys.filter { isDirectChild(it) && it !in seenDirs }
        dirDocIds.keys.removeAll { key -> goneDirs.any { key == it || key.startsWith("$it/") } }
        docIds.keys.removeAll { key -> goneDirs.any { key.startsWith("$it/") } }
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
        data class Found(val docId: String) : DirectLookup
    }

    /**
     * Answers "is [name] a file in the vault" with one query, no listing, on the
     * local-storage provider (what a Syncthing folder is), whose document ids are
     * `<volume>:<path>`: the file's id is then the tree root's id plus [name].
     * Applies the same skip/ignore rules as [list], and defers to a listing when
     * the provider's name differs in case, so the answer always matches [list]'s.
     */
    private fun directLookup(name: String): DirectLookup {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return DirectLookup.Unsupported
        val root = rootDocId
        if (':' !in root) return DirectLookup.Unsupported
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
        val docId = if (root.endsWith(':')) "$root$name" else "$root/$name"
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        // A missing file surfaces as a null cursor or a thrown FileNotFoundException,
        // depending on the platform version.
        val cursor = runCatching { resolver.query(uri, projection, null, null, null) }.getOrNull()
            ?: return DirectLookup.Missing
        return cursor.use {
            when {
                !it.moveToFirst() -> DirectLookup.Missing
                it.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR -> DirectLookup.Missing
                it.getString(1) != segments.last() -> DirectLookup.Unsupported
                else -> DirectLookup.Found(it.getString(0) ?: docId)
            }
        }
    }

    /** The subdirectory [name] of [parent]; null when absent in what the provider listed, or when it couldn't list. */
    private suspend fun findChildDir(parent: DirRef, name: String): DirRef? =
        backend.listChildren(parent.docId).entries
            .firstOrNull { it.isDir && sameName(it.name, name) }
            ?.let { DirRef(childPath(parent.path, it.name), it.docId) }

    override suspend fun stat(name: String): FileEntry? = withContext(Dispatchers.IO) {
        val key = resolve(name) ?: return@withContext null
        resolver.query(
            uriOf(key) ?: return@withContext null,
            arrayOf(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) FileEntry(key, cursor.getLong(0), cursor.getLong(1)) else null
        }
    }

    override suspend fun canonicalName(name: String): String = resolve(name) ?: name

    override suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        val uri = documentUri(name) ?: error("File not found in vault: $name")
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Cannot open $name")
    }

    override suspend fun write(name: String, content: String): Unit = withContext(Dispatchers.IO) {
        val uri = documentUri(name) ?: error("File not found in vault: $name")
        // "wt" = write + truncate. Plain "w" does NOT truncate on all providers.
        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("Cannot write $name")
    }

    override suspend fun create(name: String): Boolean = withContext(Dispatchers.IO) {
        if (resolve(name) != null) return@withContext false
        val (dir, leaf) = splitPath(name)
        val parent = resolveDir(dir, createMissing = true) ?: return@withContext false
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parent.docId)
        // "application/octet-stream" has no mapped extension, so stock providers
        // keep the name as-is ("text/plain" would make "test.org" -> "test.org.txt").
        var created = listingChanged(DocumentsContract.createDocument(
            resolver, parentUri, "application/octet-stream", leaf,
        )) ?: return@withContext false
        val actualName = displayNameOf(created)
        if (actualName != null && actualName != leaf) {
            listingChanged(DocumentsContract.renameDocument(resolver, created, leaf))?.let { created = it }
            val finalName = displayNameOf(created)
            if (finalName != null && finalName != leaf) {
                // Still not [leaf]: a file the cache didn't know about holds that
                // name (in another case, on a case-insensitive provider), and the
                // provider made this one `name (2).org`. Drop it and learn the
                // real file, so a write to [name] reaches that one instead.
                runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, created)) }
                queryDir(parent.path, parent.docId)
                return@withContext false
            }
        }
        docIds[childPath(parent.path, leaf)] = DocumentsContract.getDocumentId(created)
        true
    }

    override suspend fun rename(oldName: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldName == newName) return@withContext false
            val oldKey = resolve(oldName) ?: return@withContext false
            val newKey = resolve(newName)
            val (oldDir, _) = splitPath(oldKey)
            val (newDir, newLeaf) = splitPath(newName)
            // A case-only rename (Work.org -> work.org) resolves both names to
            // the same file; anything else already at [newName] blocks it.
            val sameDir = sameName(oldDir, newDir)
            val caseOnly = newKey == oldKey && sameDir
            if (newKey != null && !caseOnly) return@withContext false
            val uri = uriOf(oldKey) ?: return@withContext false

            val result: Uri? = when {
                // The provider sees [newLeaf] as taken (by this very file) and
                // would pick `work (1).org`; going through a free name avoids that.
                caseOnly -> renameVia(uri, ".$newLeaf.renaming", newLeaf)
                sameDir ->
                    runCatching { listingChanged(DocumentsContract.renameDocument(resolver, uri, newLeaf)) }.getOrNull()
                else -> moveAcrossDirs(uri, oldKey, oldDir, newDir, newLeaf, newName)
            }

            if (result != null) {
                docIds.remove(oldKey)
                docIds.remove(newName)
                true
            } else {
                false
            }
        }

    /** Renames [uri] to [temp], then to [leaf]; puts the old name back if the second step fails. */
    private fun renameVia(uri: Uri, temp: String, leaf: String): Uri? {
        val oldLeaf = displayNameOf(uri) ?: return null
        val tempUri = runCatching { listingChanged(DocumentsContract.renameDocument(resolver, uri, temp)) }
            .getOrNull() ?: return null
        return runCatching { listingChanged(DocumentsContract.renameDocument(resolver, tempUri, leaf)) }.getOrNull()
            ?: run {
                runCatching { listingChanged(DocumentsContract.renameDocument(resolver, tempUri, oldLeaf)) }
                null
            }
    }

    /**
     * Cross-directory move via [DocumentsContract.moveDocument], falling back to
     * copy + delete for providers that refuse it. Returns a non-null URI on
     * success.
     */
    private suspend fun moveAcrossDirs(
        uri: Uri,
        oldName: String,
        oldDir: String,
        newDir: String,
        newLeaf: String,
        newName: String,
    ): Uri? {
        val sourceParent = resolveDir(oldDir, createMissing = false) ?: return null
        val targetParent = resolveDir(newDir, createMissing = true) ?: return null
        val sourceParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, sourceParent.docId)
        val targetParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetParent.docId)

        // DocumentsContract.moveDocument needs API 24; below that, skip straight
        // to the copy + delete fallback below instead of ever attempting it.
        val moved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                listingChanged(DocumentsContract.moveDocument(resolver, uri, sourceParentUri, targetParentUri))
            }.getOrNull()
        } else {
            null
        }

        if (moved == null) {
            // Provider refused moveDocument: copy the bytes then delete the source.
            val content = runCatching { read(oldName) }.getOrNull() ?: return null
            if (!create(newName)) return null
            write(newName, content)
            return if (delete(oldName)) documentUri(newName) else null
        }

        val currentName = displayNameOf(moved)
        if (currentName != null && currentName != newLeaf) {
            runCatching { listingChanged(DocumentsContract.renameDocument(resolver, moved, newLeaf)) }
                .getOrNull()?.let { return it }
        }
        return moved
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val key = resolve(name) ?: return@withContext false
        val uri = uriOf(key) ?: return@withContext false
        val ok = runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }
            .getOrDefault(false)
        if (ok) docIds.remove(key)
        ok
    }

    override suspend fun exists(name: String): Boolean = resolve(name) != null

    override fun absolutePath(name: String): String? =
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) null
        else externalStoragePath(rootDocId, name)

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

        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val removed = runCatching {
            listingChanged(DocumentsContract.deleteDocument(resolver, uri))
        }.getOrDefault(false)

        if (removed) dirDocIds.remove(path)
        return removed
    }

    private fun displayNameOf(uri: Uri): String? =
        resolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private suspend fun documentUri(name: String): Uri? = resolve(name)?.let(::uriOf)

    /** Null when a concurrent [list] dropped [key] since it was resolved. */
    private fun uriOf(key: String): Uri? =
        docIds[key]?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }

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
                cachedKey(name) ?: name.also { docIds[it] = direct.docId }
            } else {
                name.also { docIds[it] = direct.docId }
            }
        }
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
    private suspend fun resolveDir(dir: String, createMissing: Boolean): DirRef? {
        if (dir.isEmpty()) return DirRef("", rootDocId)
        fun cached() = dirKey(dir)?.let { path -> dirDocIds[path]?.let { DirRef(path, it) } }
        cached()?.let { return it }
        list()
        cached()?.let { return it }
        if (!createMissing) return null

        var current = DirRef("", rootDocId)
        for (segment in dir.split('/')) {
            current = dirKey(childPath(current.path, segment))?.let { path -> dirDocIds[path]?.let { DirRef(path, it) } }
                ?: run {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, current.docId)
                    val newDir = listingChanged(DocumentsContract.createDocument(
                        resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, segment,
                    )) ?: return null
                    if (displayNameOf(newDir).let { it != null && it != segment }) {
                        // Something unlisted (a skipped or ignored folder, in any
                        // case) holds the name; the provider made `name (1)`.
                        runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, newDir)) }
                        return null
                    }
                    val path = childPath(current.path, segment)
                    DirRef(path, DocumentsContract.getDocumentId(newDir)).also { dirDocIds[path] = it.docId }
                }
        }
        return current
    }

    // --- Raw access for attachments ---------------------------------------
    //
    // Attachment files live under skipped shard dirs, so the listing-backed
    // caches above never hold them and documentUri() treats them as missing.
    // These members resolve paths on their own, ignoring every skip/ignore
    // rule: on the local-storage provider (path document ids) one query per
    // lookup, elsewhere a cached id validated with one query, falling back to
    // a walk from the nearest known parent. Their ids go in separate caches
    // that list() never wipes.

    private data class DocMeta(
        val docId: String,
        val name: String,
        val lastModified: Long,
        val size: Long,
        val isDir: Boolean,
    )

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
    private class Listing(val at: Long, val names: Set<String>)

    /**
     * raw dir path -> its last listing. A lookup of a path whose parent was
     * listed within [RAW_FRESH_MS] and didn't hold it answers "missing" with no
     * query; otherwise each miss re-lists the parent on providers without path
     * ids (a screen of broken chips in one dir cost one listing apiece). Any
     * create, rename, move or delete through this store drops every listing
     * ([listingChanged]); changes from outside (Syncthing) show up once the
     * window has passed, or at once after [dropRawSnapshots] (sync, foreground).
     */
    private val rawListings: MutableMap<String, Listing> = ConcurrentHashMap()

    /** Drops every [rawListings] snapshot after a structural change; returns [result]. */
    private fun <T> listingChanged(result: T): T {
        rawListings.clear()
        return result
    }

    override fun dropRawSnapshots() {
        rawListings.clear()
        rawVerifiedAt.clear()
    }

    private fun recordListing(path: String, children: List<DocMeta>) {
        rawListings[path] = Listing(now(), children.mapTo(HashSet()) { it.name })
    }

    /** True when [path]'s parent was listed recently and didn't contain it. */
    private fun knownAbsent(path: String): Boolean {
        val listing = rawListings[path.substringBeforeLast('/', "")] ?: return false
        if (now() - listing.at > RAW_FRESH_MS) return false
        return path.substringAfterLast('/') !in listing.names
    }

    private fun metaOf(cursor: android.database.Cursor, fallbackId: String): DocMeta? {
        val name = cursor.getString(1) ?: return null
        return DocMeta(
            docId = cursor.getString(0) ?: fallbackId,
            name = name,
            lastModified = cursor.getLong(2),
            size = cursor.getLong(3),
            isDir = cursor.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    /** One query for [docId]'s metadata; null when it's gone or the provider refuses. */
    private fun queryDoc(docId: String): DocMeta? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val cursor = runCatching { resolver.query(uri, projection, null, null, null) }.getOrNull()
            ?: return null
        return cursor.use { if (it.moveToFirst()) metaOf(it, docId) else null }
    }

    /** A directory's children as the raw side sees them, and whether the provider finished listing. */
    private class RawChildren(val metas: List<DocMeta>, val complete: Boolean)

    /** Children of [docId], or null when the provider rejects the id or can't be reached. */
    private suspend fun queryChildren(docId: String): RawChildren? {
        val listing = backend.listChildren(docId)
        if (listing is Listing.Failed) return null
        return RawChildren(listing.entries.map { it.toMeta() }, complete = listing is Listing.Complete)
    }

    private fun DocEntry.toMeta() = DocMeta(docId, name, lastModified, size, isDir)

    private fun remember(path: String, meta: DocMeta) {
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

    /** The provider's document id for [path] when ids are paths (local storage), else null. */
    private fun pathDocId(path: String): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val root = rootDocId
        if (':' !in root) return null
        return if (root.endsWith(':')) "$root$path" else "$root/$path"
    }

    /** Metadata for whatever is at [path] (file or directory), or null. Never consults skip rules. */
    private suspend fun rawLookup(path: String): DocMeta? {
        if (path.isEmpty()) return queryDoc(rootDocId)
        val leaf = path.substringAfterLast('/')
        val cached = rawFileIds[path] ?: rawDirIds[path] ?: docIds[path] ?: dirDocIds[path]
        if (cached != null) {
            val meta = queryDoc(cached)
            if (meta != null && meta.name == leaf) return meta.also { remember(path, it) }
            forgetTree(path)
        }
        if (knownAbsent(path)) return null
        pathDocId(path)?.let { id ->
            val meta = queryDoc(id) ?: return null
            // A different-case match on a case-insensitive volume isn't this
            // name: settle it from the parent's listing below.
            if (meta.name == leaf) return meta.also { remember(path, it) }
        }
        val parent = rawLookup(path.substringBeforeLast('/', ""))?.takeIf { it.isDir } ?: return null
        return rawChild(path.substringBeforeLast('/', ""), parent.docId, leaf)
    }

    /** Finds [leaf] among the children of [parentDocId] (at [parentPath]), caching every child seen. */
    private suspend fun rawChild(parentPath: String, parentDocId: String, leaf: String): DocMeta? {
        var found: DocMeta? = null
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
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = runCatching {
            listingChanged(DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, leaf))
        }.getOrNull() ?: return null
        val docId = DocumentsContract.getDocumentId(created)
        rawDirIds[path] = docId
        return docId
    }

    private suspend fun rawFileUri(name: String): Uri? {
        val path = name.trim('/')
        val id = freshFileId(path) ?: rawLookup(path)?.takeIf { !it.isDir }?.docId ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
    }

    // Recycle: the stream is the return value; FileStore callers close it.
    @SuppressLint("Recycle")
    override suspend fun openInput(name: String): InputStream = withContext(Dispatchers.IO) {
        val uri = rawFileUri(name) ?: throw FileNotFoundException(name)
        resolver.openInputStream(uri) ?: throw FileNotFoundException(name)
    }

    override suspend fun createBinary(name: String): Boolean = withContext(Dispatchers.IO) {
        val path = name.trim('/')
        if (rawLookup(path) != null) return@withContext false
        val (dir, leaf) = splitPath(path)
        val parentId = ensureRawDir(dir) ?: return@withContext false
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        // octet-stream so the provider doesn't append an extension (see create()).
        var created = runCatching {
            listingChanged(DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", leaf))
        }.getOrNull() ?: return@withContext false
        val actualName = displayNameOf(created)
        if (actualName != null && actualName != leaf) {
            val renamed = runCatching { listingChanged(DocumentsContract.renameDocument(resolver, created, leaf)) }.getOrNull()
            if (renamed == null) {
                // Couldn't get the exact name: don't leave a stray "x (1).jpg" behind.
                runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, created)) }
                return@withContext false
            }
            created = renamed
        }
        rawFileIds[path] = DocumentsContract.getDocumentId(created)
        true
    }

    // Recycle: the stream is the return value; FileStore callers close it.
    @SuppressLint("Recycle")
    override suspend fun openOutput(name: String): OutputStream = withContext(Dispatchers.IO) {
        val uri = rawFileUri(name) ?: throw FileNotFoundException(name)
        resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(name)
    }

    override suspend fun statRaw(name: String): FileEntry? = withContext(Dispatchers.IO) {
        val path = name.trim('/')
        rawLookup(path)?.takeIf { !it.isDir }?.let { FileEntry(path, it.lastModified, it.size) }
    }

    override suspend fun renameRaw(name: String, newLeaf: String): Boolean = withContext(Dispatchers.IO) {
        require('/' !in newLeaf && newLeaf.isNotBlank()) { "Bad leaf name: $newLeaf" }
        val path = name.trim('/')
        val meta = rawLookup(path)?.takeIf { !it.isDir } ?: return@withContext false
        val dir = path.substringBeforeLast('/', "")
        val newPath = if (dir.isEmpty()) newLeaf else "$dir/$newLeaf"
        if (rawLookup(newPath) != null) return@withContext false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, meta.docId)
        val renamed = runCatching { listingChanged(DocumentsContract.renameDocument(resolver, uri, newLeaf)) }.getOrNull()
            ?: return@withContext false
        forgetTree(path)
        rawFileIds[newPath] = DocumentsContract.getDocumentId(renamed)
        true
    }

    override suspend fun deleteRaw(name: String): Boolean = withContext(Dispatchers.IO) {
        val path = name.trim('/')
        val meta = rawLookup(path)?.takeIf { !it.isDir } ?: return@withContext false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, meta.docId)
        val ok = runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }.getOrDefault(false)
        if (ok) forgetTree(path)
        ok
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
    private suspend fun rawChildren(path: String): List<Pair<String, DocMeta>> {
        val docId = if (path.isEmpty()) rootDocId else rawLookup(path)?.takeIf { it.isDir }?.docId
            ?: return emptyList()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = queryChildren(docId) ?: return emptyList()
        if (children.complete) recordListing(path, children.metas)
        return children.metas.map { child ->
            (prefix + child.name).let { childPath -> remember(childPath, child); childPath to child }
        }
    }

    override suspend fun deleteDir(dir: String): Boolean = withContext(Dispatchers.IO) {
        val path = dir.trim('/')
        require(path.isNotEmpty()) { "Refusing to delete the vault root" }
        val meta = rawLookup(path)?.takeIf { it.isDir } ?: return@withContext false
        val ok = deleteTree(path, meta.docId)
        forgetTree(path)
        ok
    }

    /**
     * Deletes a directory document. Providers delete children along with a
     * directory (the platform's local-storage one does); for any that refuse
     * a non-empty one, empty it first and retry.
     */
    private suspend fun deleteTree(path: String, docId: String): Boolean {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        if (runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }.getOrDefault(false)) return true
        val children = queryChildren(docId) ?: return false
        for (child in children.metas) {
            val childPath = "$path/${child.name}"
            val ok = if (child.isDir) {
                deleteTree(childPath, child.docId)
            } else {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
                runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, childUri)) }.getOrDefault(false)
            }
            if (!ok) return false
        }
        return runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }.getOrDefault(false)
    }

    override suspend fun moveDir(src: String, dstParent: String): Boolean = withContext(Dispatchers.IO) {
        val srcPath = src.trim('/')
        val parentPath = dstParent.trim('/')
        require(srcPath.isNotEmpty()) { "Refusing to move the vault root" }
        val meta = rawLookup(srcPath)?.takeIf { it.isDir } ?: return@withContext false
        val dstPath = if (parentPath.isEmpty()) meta.name else "$parentPath/${meta.name}"
        if (dstPath == srcPath || dstPath.startsWith("$srcPath/")) return@withContext false
        if (rawLookup(dstPath) != null) return@withContext false
        val srcParentId = rawLookup(srcPath.substringBeforeLast('/', ""))?.docId ?: return@withContext false
        val dstParentId = ensureRawDir(parentPath) ?: return@withContext false

        // moveDocument needs API 24 and isn't supported by every provider.
        val moved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                listingChanged(DocumentsContract.moveDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, meta.docId),
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, srcParentId),
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, dstParentId),
                ))
            }.getOrNull()
        } else {
            null
        }
        forgetTree(srcPath)
        forgetTree(dstPath)
        if (moved != null) return@withContext true

        // Fallback: copy the tree, and only delete the source once it's all there.
        val copied = copyTree(srcPath, meta.docId, dstPath)
        if (!copied) {
            rawLookup(dstPath)?.takeIf { it.isDir }?.let { deleteTree(dstPath, it.docId) }
            forgetTree(dstPath)
            return@withContext false
        }
        deleteTree(srcPath, meta.docId).also { forgetTree(srcPath) }
    }

    private suspend fun copyTree(srcPath: String, srcDocId: String, dstPath: String): Boolean {
        val dstId = ensureRawDir(dstPath) ?: return false
        val dstUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dstId)
        val children = queryChildren(srcDocId) ?: return false
        // The caller deletes the source after a successful copy: a copy from a
        // listing that wasn't finished would silently drop the unseen files.
        if (!children.complete) return false
        for (child in children.metas) {
            if (child.isDir) {
                if (!copyTree("$srcPath/${child.name}", child.docId, "$dstPath/${child.name}")) return false
                continue
            }
            val created = runCatching {
                listingChanged(DocumentsContract.createDocument(resolver, dstUri, "application/octet-stream", child.name))
            }.getOrNull() ?: return false
            val srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
            val ok = runCatching {
                resolver.openInputStream(srcUri)!!.use { input ->
                    resolver.openOutputStream(created, "wt")!!.use { output -> input.copyTo(output, COPY_BUFFER) }
                }
            }.isSuccess
            if (!ok) return false
            val actual = displayNameOf(created)
            if (actual != null && actual != child.name) {
                runCatching { listingChanged(DocumentsContract.renameDocument(resolver, created, child.name)) }.getOrNull() ?: return false
            }
        }
        return true
    }

    override suspend fun contentUri(name: String): Uri? = withContext(Dispatchers.IO) { rawFileUri(name) }

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

/** The platform's local-storage documents provider, whose document ids are paths. */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Maps a local-storage tree root id (`<volume>:<path>`) plus a vault-relative
 * [name] to its filesystem path: the `primary` volume is internal shared storage
 * (`/storage/emulated/0`), any other volume id (an SD card's `1234-ABCD`) mounts
 * at `/storage/<id>`. Null for an id that isn't in that form.
 */
internal fun externalStoragePath(rootDocId: String, name: String): String? {
    val colon = rootDocId.indexOf(':')
    if (colon <= 0) return null
    val volume = rootDocId.substring(0, colon)
    val volumeRoot = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
    return listOf(volumeRoot, rootDocId.substring(colon + 1).trim('/'), name.trim('/'))
        .filter { it.isNotEmpty() }
        .joinToString("/")
}
