package com.rrajath.grove.vault

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Storage Access Framework implementation over a persisted tree URI
 * (ACTION_OPEN_DOCUMENT_TREE).
 *
 * The vault is a tree: [list] walks every subdirectory (one child-documents
 * cursor query per directory), skipping dot-directories. Names are
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
) : FileStore {

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
            queryChildreen(docId).use { cursor ->
                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(4)
                    val path = if (dir.isEmpty()) name else "$dir/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (isSkippedVaultDir(name) || ignore.isDirIgnored(name, path)) continue
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

    private fun queryChildreen(docId: String): Cursor {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

        // I suspect seafile type sync-apps send completions notifications on URIs out of tree
        val notificationUri =
            DocumentsContract.buildChildDocumentsUri(requireNotNull(treeUri.authority), docId)

        // increases whenever the external app finishes the loading
        val loadCompletion = Semaphore(0)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                loadCompletion.release()
            }
        }

        val timeout = 15_000L // I assume 15sec is enough time
        val deadline = SystemClock.elapsedRealtime() + timeout

        try {
            // observe 1st; query after;
            resolver.registerContentObserver(notificationUri, true, observer)
            if (childrenUri != notificationUri)
                resolver.registerContentObserver(childrenUri, true, observer)

            while (true) {
                val cursor = resolver.query(childrenUri, projection, null, null, null)
                    ?: throw IOException("Sync dir app gave a null cursor")

                var done = false
                try {
                    val extras = cursor.extras

                    extras?.getString(DocumentsContract.EXTRA_ERROR)?.let {
                        throw IOException("Sync dir app error: $it")
                    }

                    val isLoading = extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                    done = !isLoading

                    if (done)
                        return cursor
                } finally {
                    if (!done)
                        cursor.close()
                }

                val msg = "Sync dir app timed out on loading"
                val remainingDuration = deadline - SystemClock.elapsedRealtime()
                val timedOut = remainingDuration <= 0
                if (timedOut)
                    throw IOException(msg)

                if (!loadCompletion.tryAcquire(remainingDuration, TimeUnit.MILLISECONDS))
                    throw IOException(msg)

            }
        } finally {
            resolver.unregisterContentObserver(observer)
        }
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
        val entries = mutableListOf<FileEntry>()
        val seenFiles = mutableSetOf<String>()
        val seenDirs = mutableSetOf<String>()
        val cursor = try {
            queryChildreen(docId)
        } catch (e: java.io.FileNotFoundException) {
            return null
        }
        cursor.use {
            while (it.moveToNext()) {
                val childDocId = it.getString(0)
                val name = it.getString(1) ?: continue
                val path = if (dir.isEmpty()) name else "$dir/$name"
                if (it.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (isSkippedVaultDir(name) || ignore.isDirIgnored(name, path)) continue
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
            if (isSkippedVaultDir(segment) || ignore.isDirIgnored(segment, nextPath)) return null
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
            path = if (path.isEmpty()) dir else "$path/$dir"
            if (isSkippedVaultDir(dir) || ignore.isDirIgnored(dir, path)) return DirectLookup.Missing
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

    private fun findChildDir(parentDocId: String, name: String): String? =
        queryChildreen(parentDocId).use { cursor ->
            var found: String? = null
            while (found == null && cursor.moveToNext()) {
              val cursorName = cursor.getString(1)
              val cursorMimeType = cursor.getString(4)
              val isMatch = cursorName == name && cursorMimeType == DocumentsContract.Document.MIME_TYPE_DIR
              if (isMatch)
                  found = cursor.getString(0)
            }
            found
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
        var created = DocumentsContract.createDocument(
            resolver, parentUri, "application/octet-stream", leaf,
        ) ?: return@withContext false
        val actualName = displayNameOf(created)
        if (actualName != null && actualName != leaf) {
            DocumentsContract.renameDocument(resolver, created, leaf)?.let { created = it }
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
                runCatching { DocumentsContract.renameDocument(resolver, uri, newLeaf) }.getOrNull()
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
                DocumentsContract.moveDocument(resolver, uri, sourceParentUri, targetParentUri)
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
            runCatching { DocumentsContract.renameDocument(resolver, moved, newLeaf) }
                .getOrNull()?.let { return it }
        }
        return moved
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val uri = documentUri(name) ?: return@withContext false
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
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

        val isEmpty = queryChildreen(docId).use { cursor ->
            !cursor.moveToFirst()
        }
        if (!isEmpty) return false

        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val removed = runCatching {
            DocumentsContract.deleteDocument(resolver, uri)
        }.getOrDefault(false)

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
                val newDir = DocumentsContract.createDocument(
                    resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, segment,
                ) ?: return null
                DocumentsContract.getDocumentId(newDir).also { dirDocIds[nextPath] = it }
            }
            currentPath = nextPath
        }
        return currentDocId
    }

    private fun splitPath(path: String): Pair<String, String> {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" to path else path.substring(0, i) to path.substring(i + 1)
    }
}

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
