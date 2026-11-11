package com.rrajath.grove.vault

import com.rrajath.grove.attach.AttachSkipRoots
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : FileStore {

    private val skipRoots = attachRoots.takeIf { skipAttachShards }

    private val resolver get() = context.contentResolver
    private val rootDocId get() = DocumentsContract.getTreeDocumentId(treeUri)

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

    override suspend fun list(): List<FileEntry> = withContext(Dispatchers.IO) {
        listMutex.withLock { listTree() }
    }

    private fun listTree(): List<FileEntry> {
        val files = mutableMapOf<String, String>()
        val dirs = mutableMapOf("" to rootDocId)
        val entries = mutableListOf<FileEntry>()

        val queue = ArrayDeque<Pair<String, String>>() // relativeDir to docId
        queue.add("" to rootDocId)
        while (queue.isNotEmpty()) {
            val (dir, docId) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(4)
                    val path = if (dir.isEmpty()) name else "$dir/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (isSkippedVaultDir(name, dir, skipRoots) || ignore.isDirIgnored(name, path)) continue
                        dirs[path] = childDocId
                        queue.add(path to childDocId)
                    } else {
                        if (ignore.isFileIgnored(name, path)) continue
                        files[path] = childDocId
                        entries.add(FileEntry(path, cursor.getLong(2), cursor.getLong(3)))
                    }
                }
            }
        }
        docIds = ConcurrentHashMap(files)
        dirDocIds = ConcurrentHashMap(dirs)
        listed = true
        return entries.sortedBy { it.name }
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
        val cached = dirDocIds[trimmed]
        val entries = cached?.let { queryDir(trimmed, it) }
            ?: run {
                // Not cached, or the cached id went stale (folder moved/deleted
                // outside Grove): resolve it afresh, segment by segment.
                if (cached != null) dirDocIds.remove(trimmed)
                val docId = lookupDir(trimmed) ?: return@withContext emptyList()
                queryDir(trimmed, docId) ?: emptyList()
            }
        entries.sortedBy { it.name }
    }

    /** Direct children files of the directory [docId] (at relative path [dir]),
     *  or null if the provider rejects the id. */
    private fun queryDir(dir: String, docId: String): List<FileEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val entries = mutableListOf<FileEntry>()
        val seenFiles = mutableSetOf<String>()
        val seenDirs = mutableSetOf<String>()
        val cursor = runCatching { resolver.query(childrenUri, projection, null, null, null) }.getOrNull()
            ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val childDocId = it.getString(0)
                val name = it.getString(1) ?: continue
                val path = if (dir.isEmpty()) name else "$dir/$name"
                if (it.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (isSkippedVaultDir(name, dir, skipRoots) || ignore.isDirIgnored(name, path)) continue
                    dirDocIds[path] = childDocId
                    seenDirs += path
                } else {
                    if (ignore.isFileIgnored(name, path)) continue
                    docIds[path] = childDocId
                    seenFiles += path
                    entries.add(FileEntry(path, it.getLong(2), it.getLong(3)))
                }
            }
        }
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        fun isDirectChild(path: String) = path.startsWith(prefix) && '/' !in path.removePrefix(prefix)
        docIds.keys.removeAll { isDirectChild(it) && it !in seenFiles }
        // A vanished subdirectory takes its cached descendants with it.
        val goneDirs = dirDocIds.keys.filter { isDirectChild(it) && it !in seenDirs }
        dirDocIds.keys.removeAll { key -> goneDirs.any { key == it || key.startsWith("$it/") } }
        docIds.keys.removeAll { key -> goneDirs.any { key.startsWith("$it/") } }
        return entries
    }

    /**
     * Document id for an existing relative directory, found one segment at a
     * time from the nearest cached ancestor (one query per uncached segment),
     * never via a full [list]. Null if any segment is missing, skipped, or
     * ignored, matching what [list] would surface.
     */
    private fun lookupDir(dir: String): String? {
        if (dir.isEmpty()) return rootDocId
        var currentPath = ""
        var currentDocId = rootDocId
        for (segment in dir.split('/')) {
            val nextPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            if (isSkippedVaultDir(segment, currentPath, skipRoots) || ignore.isDirIgnored(segment, nextPath)) {
                return null
            }
            currentDocId = dirDocIds[nextPath]
                ?: findChildDir(currentDocId, segment)?.also { dirDocIds[nextPath] = it }
                ?: return null
            currentPath = nextPath
        }
        return currentDocId
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

    private fun findChildDir(parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = runCatching { resolver.query(childrenUri, projection, null, null, null) }.getOrNull()
            ?: return null
        return cursor.use {
            var found: String? = null
            while (found == null && it.moveToNext()) {
                if (it.getString(1) == name && it.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    found = it.getString(0)
                }
            }
            found
        }
    }

    override suspend fun stat(name: String): FileEntry? = withContext(Dispatchers.IO) {
        val uri = documentUri(name) ?: return@withContext null
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) FileEntry(name, cursor.getLong(0), cursor.getLong(1)) else null
        }
    }

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
        if (documentUri(name) != null) return@withContext false
        val (dir, leaf) = splitPath(name)
        val parentDocId = resolveDir(dir, createMissing = true) ?: return@withContext false
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        // "application/octet-stream" has no mapped extension, so stock providers
        // keep the name as-is ("text/plain" would make "test.org" -> "test.org.txt").
        var created = listingChanged(DocumentsContract.createDocument(
            resolver, parentUri, "application/octet-stream", leaf,
        )) ?: return@withContext false
        val actualName = displayNameOf(created)
        if (actualName != null && actualName != leaf) {
            listingChanged(DocumentsContract.renameDocument(resolver, created, leaf))?.let { created = it }
        }
        docIds[name] = DocumentsContract.getDocumentId(created)
        true
    }

    override suspend fun rename(oldName: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldName == newName) return@withContext false
            if (documentUri(newName) != null) return@withContext false
            val uri = documentUri(oldName) ?: return@withContext false
            val (oldDir, _) = splitPath(oldName)
            val (newDir, newLeaf) = splitPath(newName)

            val result: Uri? = if (oldDir == newDir) {
                runCatching { listingChanged(DocumentsContract.renameDocument(resolver, uri, newLeaf)) }.getOrNull()
            } else {
                moveAcrossDirs(uri, oldName, oldDir, newDir, newLeaf, newName)
            }

            if (result != null) {
                docIds.remove(oldName)
                docIds.remove(newName)
                true
            } else {
                false
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
        val sourceParentId = resolveDir(oldDir, createMissing = false) ?: return null
        val targetParentId = resolveDir(newDir, createMissing = true) ?: return null
        val sourceParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, sourceParentId)
        val targetParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetParentId)

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
        val uri = documentUri(name) ?: return@withContext false
        val ok = runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }
            .getOrDefault(false)
        if (ok) docIds.remove(name)
        ok
    }

    override suspend fun exists(name: String): Boolean = documentUri(name) != null

    override fun absolutePath(name: String): String? =
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) null
        else externalStoragePath(rootDocId, name)

    override suspend fun pruneEmptyDirs(dir: String): Unit = withContext(Dispatchers.IO) {
        val trimmed = dir.trim('/')
        if (trimmed.isEmpty()) return@withContext
        if (dirDocIds.keys.none { it == trimmed || it.startsWith("$trimmed/") }) list()

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

    /** Deletes [path] if it has no child documents. Returns whether it was removed. */
    private fun removeDirIfEmpty(path: String): Boolean {
        val docId = dirDocIds[path] ?: return false
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val empty = resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { !it.moveToFirst() } ?: false
        if (!empty) return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val removed = runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }
            .getOrDefault(false)
        if (removed) dirDocIds.remove(path)
        return removed
    }

    private fun displayNameOf(uri: Uri): String? =
        resolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private suspend fun documentUri(name: String): Uri? {
        // Only re-walk the tree if we have never listed it; once listed, a miss
        // is authoritative (see [listed]). This is what keeps exists()/create()/
        // rename() on an absent name from triggering a full recursive walk.
        // Before the first walk, a path-id provider answers with one direct
        // query instead (Dailies opening today's note on a cold store).
        if (name !in docIds && !listed) {
            when (val direct = directLookup(name)) {
                DirectLookup.Unsupported -> list()
                DirectLookup.Missing -> return null
                is DirectLookup.Found -> docIds[name] = direct.docId
            }
        }
        val docId = docIds[name] ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    /**
     * Document id for a relative directory path, walking (and optionally
     * creating) one segment at a time. Returns the tree root id for "".
     */
    private suspend fun resolveDir(dir: String, createMissing: Boolean): String? {
        if (dir.isEmpty()) return rootDocId
        dirDocIds[dir]?.let { return it }
        list()
        dirDocIds[dir]?.let { return it }
        if (!createMissing) return null

        var currentPath = ""
        var currentDocId = rootDocId
        for (segment in dir.split('/')) {
            val nextPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            currentDocId = dirDocIds[nextPath] ?: run {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
                val newDir = listingChanged(DocumentsContract.createDocument(
                    resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, segment,
                )) ?: return null
                DocumentsContract.getDocumentId(newDir).also { dirDocIds[nextPath] = it }
            }
            currentPath = nextPath
        }
        return currentDocId
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

    /** Children of [docId], or null when the provider rejects the id. */
    private fun queryChildren(docId: String): List<DocMeta>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cursor = runCatching { resolver.query(uri, projection, null, null, null) }.getOrNull()
            ?: return null
        return cursor.use {
            buildList { while (it.moveToNext()) metaOf(it, "")?.takeIf { m -> m.docId.isNotEmpty() }?.let(::add) }
        }
    }

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
    private fun rawLookup(path: String): DocMeta? {
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
    private fun rawChild(parentPath: String, parentDocId: String, leaf: String): DocMeta? {
        var found: DocMeta? = null
        val children = queryChildren(parentDocId) ?: return null
        recordListing(parentPath, children)
        children.forEach { child ->
            val childPath = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
            remember(childPath, child)
            if (child.name == leaf) found = child
        }
        return found
    }

    /** Document id of directory [path], creating it and missing ancestors. Null on failure or a file in the way. */
    private fun ensureRawDir(path: String): String? {
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

    private fun rawFileUri(name: String): Uri? {
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

    /** (path, meta) for each child of directory [path]; empty when it isn't a directory. */
    private fun rawChildren(path: String): List<Pair<String, DocMeta>> {
        val docId = if (path.isEmpty()) rootDocId else rawLookup(path)?.takeIf { it.isDir }?.docId
            ?: return emptyList()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = queryChildren(docId) ?: return emptyList()
        recordListing(path, children)
        return children.map { child ->
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
    private fun deleteTree(path: String, docId: String): Boolean {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        if (runCatching { listingChanged(DocumentsContract.deleteDocument(resolver, uri)) }.getOrDefault(false)) return true
        val children = queryChildren(docId) ?: return false
        for (child in children) {
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

    private fun copyTree(srcPath: String, srcDocId: String, dstPath: String): Boolean {
        val dstId = ensureRawDir(dstPath) ?: return false
        val dstUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dstId)
        val children = queryChildren(srcDocId) ?: return false
        for (child in children) {
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
