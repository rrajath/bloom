package com.rrajath.grove.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.robolectric.Robolectric

/**
 * A java.io-backed documents provider with path document ids
 * (`primary:vault/a/b.org`) like the platform's ExternalStorageProvider. Lets
 * Robolectric tests drive [BackendFileStore] end to end and count the cursor
 * queries it issues.
 *
 * A plain [ContentProvider] speaking the DocumentsContract protocol, not a
 * `DocumentsProvider`: Robolectric routes `ContentResolver.query` to the
 * 5-arg provider method, which `DocumentsProvider` makes final and throwing.
 *
 * [supportsMove] = false makes moveDocument fail, exercising copy + delete.
 * [caseInsensitive] = true mimics shared storage: names match in any case, a
 * single-document query echoes the spelling asked for, and create/rename pick
 * `name (n).ext` when the name is taken in any case (AOSP's buildUniqueFile).
 */
class FakeDocumentsProvider : ContentProvider() {

    lateinit var root: File
    val queries = AtomicInteger()
    var supportsMove = true
    var caseInsensitive = false

    /** Each makes that operation fail the way a refusing provider does: DocumentsContract answers null/false. */
    var failCreate = false
    var failRename = false
    var failDelete = false

    /**
     * When set, every document reports these `COLUMN_FLAGS` (what Seafile does:
     * write, but no rename/delete/move). Null leaves the column unreported.
     */
    var reportedFlags: Int? = null

    /**
     * Per-document `COLUMN_FLAGS`, by vault-relative path ("" is the root);
     * wins over [reportedFlags]. Models a provider with mixed flags (Seafile's
     * repos versus the files in them).
     */
    val flagsByPath: MutableMap<String, Int> = ConcurrentHashMap()
    private lateinit var authority: String

    /**
     * Document ids of directories whose children query answers an empty cursor
     * flagged EXTRA_LOADING, until [finishLoading]. A dir that is never
     * finished models a provider that never loads.
     */
    val loadingDirs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Document ids of directories whose children query answers EXTRA_ERROR. */
    val errorDirs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Seafile-style lazy create: creating a file only hands back an id, and the
     * file exists once it is opened for writing. Folders are still made at once.
     */
    var lazyCreate = false

    /**
     * Seafile-style upload: a lazily created file's first write makes the
     * folders its path names (`relative_path`), so a file can be created
     * through folders that don't exist yet.
     */
    var writeMakesFolders = false

    /** Seafile 4.0.15's bug: every folder create throws. */
    var failDirCreate = false

    /**
     * Seafile-style stale listings: files created through this provider stay
     * out of children listings (as a listing cache taken before the create
     * would) until [revealCreated].
     */
    var hideCreatedFromListings = false

    /** Relative paths of files created while [hideCreatedFromListings] was on and not yet revealed. */
    val hiddenCreated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Ends [hideCreatedFromListings]' staleness, as the provider's cache expiring would. */
    fun revealCreated() = hiddenCreated.clear()

    /** When true, the first children query of every directory is a loading one. */
    var everyDirLoadsFirst = false

    /** When >= 0, a loading directory finishes by itself this many ms after its first loading query. */
    var autoFinishLoadingMs = -1L

    /** Notify on the non-tree children URI (what Seafile does) instead of the tree-scoped one the query used. */
    var notifyOutOfTree = false

    private val loadedOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val autoFinishScheduled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Ends a loading directory and notifies, as a real provider does once its fetch lands. */
    fun finishLoading(docId: String) {
        loadingDirs.remove(docId)
        val uri = if (notifyOutOfTree) {
            DocumentsContract.buildChildDocumentsUri(authority, docId)
        } else {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri(authority), docId)
        }
        context!!.contentResolver.notifyChange(uri, null)
    }

    /** [finishLoading] from another thread after [delayMs], so the waiting side really suspends first. */
    fun finishLoadingLater(docId: String, delayMs: Long) {
        thread(isDaemon = true) {
            Thread.sleep(delayMs)
            finishLoading(docId)
        }
    }

    override fun onCreate(): Boolean = true

    override fun attachInfo(context: android.content.Context?, info: ProviderInfo) {
        super.attachInfo(context, info)
        authority = info.authority
    }

    private fun fileFor(docId: String): File {
        if (docId == ROOT_ID) return root
        require(docId.startsWith("$ROOT_ID/")) { "Not in this tree: $docId" }
        val rel = docId.removePrefix("$ROOT_ID/")
        if (!caseInsensitive) return File(root, rel)
        return rel.split('/').fold(root) { dir, segment -> childOf(dir, segment) ?: File(dir, segment) }
    }

    /** [dir]'s child named [name], in any case when [caseInsensitive]. */
    private fun childOf(dir: File, name: String): File? =
        dir.listFiles().orEmpty().firstOrNull { if (caseInsensitive) it.name.equals(name, ignoreCase = true) else it.name == name }

    private fun taken(dir: File, name: String) = childOf(dir, name) != null

    /** AOSP FileUtils.buildUniqueFile: `name (n).ext` until free. */
    private fun uniqueName(dir: File, name: String): String {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = name
        var n = 0
        while (taken(dir, candidate)) candidate = "$base (${++n})$ext"
        return candidate
    }

    private fun idFor(f: File): String {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        return if (rel.isEmpty()) ROOT_ID else "$ROOT_ID/$rel"
    }

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri(authority), docId)

    private fun MatrixCursor.addFile(f: File, displayName: String = f.name) {
        val row = newRow()
        (flagsByPath[f.relativeTo(root).invariantSeparatorsPath] ?: reportedFlags)?.let { row.add(Document.COLUMN_FLAGS, it) }
        row.add(Document.COLUMN_DOCUMENT_ID, idFor(f))
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
        row.add(Document.COLUMN_SIZE, if (f.isFile) f.length() else 0L)
        row.add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queries.incrementAndGet()
        val docId = DocumentsContract.getDocumentId(uri)
        val f = fileFor(docId)
        val cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION)
        if (uri.lastPathSegment == "children") {
            if (!f.isDirectory) throw FileNotFoundException(docId)
            if (docId in errorDirs) {
                cursor.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Simulated provider error") }
                return cursor
            }
            if (everyDirLoadsFirst && loadedOnce.add(docId)) loadingDirs.add(docId)
            if (docId in loadingDirs) {
                // Seafile-style: an empty cursor now, the rows after the provider notifies.
                cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                cursor.setNotificationUri(context!!.contentResolver, uri)
                if (autoFinishLoadingMs >= 0 && autoFinishScheduled.add(docId)) finishLoadingLater(docId, autoFinishLoadingMs)
                return cursor
            }
            f.listFiles().orEmpty().sortedBy { it.name }
                .filter { it.relativeTo(root).invariantSeparatorsPath !in hiddenCreated }
                .forEach { cursor.addFile(it) }
        } else {
            if (!f.exists()) throw FileNotFoundException(docId)
            // ExternalStorageProvider reports File.getName() of the path asked for.
            cursor.addFile(f, if (caseInsensitive) docId.substringAfterLast('/') else f.name)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(DocumentsContract.getDocumentId(uri))
        // A lazily created file comes into being on its first write.
        if (lazyCreate && !f.exists() && 'w' in mode) {
            if (writeMakesFolders) {
                // Folders the upload makes are as stale in listings as the files.
                var d = f.parentFile
                val made = mutableListOf<File>()
                while (d != null && !d.exists()) { made += d; d = d.parentFile }
                f.parentFile?.mkdirs()
                if (hideCreatedFromListings) made.forEach { hiddenCreated += it.relativeTo(root).invariantSeparatorsPath }
            }
            if (f.parentFile?.isDirectory == true) f.createNewFile()
        }
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val inUri = requireNotNull(extras?.uri(EXTRA_URI)) { "No document uri" }
        val docId = DocumentsContract.getDocumentId(inUri)
        val out = Bundle()
        when (method) {
            METHOD_CREATE_DOCUMENT -> {
                check(!failCreate) { "Simulated create failure" }
                val parent = fileFor(docId)
                val name = extras!!.getString(Document.COLUMN_DISPLAY_NAME)!!
                var target = File(parent, name)
                if (caseInsensitive) {
                    target = File(parent, uniqueName(parent, name))
                } else {
                    var n = 1
                    while (target.exists()) target = File(parent, "$name (${n++})")
                }
                val isDir = extras.getString(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR
                check(!(isDir && failDirCreate)) { "Simulated folder create failure" }
                // Lazy: only an id; the file appears on its first write (openFile).
                if (isDir || !lazyCreate) check(if (isDir) target.mkdir() else target.createNewFile()) { "Couldn't create $target" }
                if (!isDir && hideCreatedFromListings) hiddenCreated += target.relativeTo(root).invariantSeparatorsPath
                out.putParcelable(EXTRA_URI, docUri(idFor(target)))
            }
            METHOD_RENAME_DOCUMENT -> {
                check(!failRename) { "Simulated rename failure" }
                val f = fileFor(docId)
                val name = extras!!.getString(Document.COLUMN_DISPLAY_NAME)!!
                val target = File(f.parentFile, if (caseInsensitive) uniqueName(f.parentFile!!, name) else name)
                check(!target.exists() && f.renameTo(target)) { "Couldn't rename $f" }
                out.putParcelable(EXTRA_URI, docUri(idFor(target)))
            }
            METHOD_DELETE_DOCUMENT -> {
                check(!failDelete) { "Simulated delete failure" }
                val doomed = fileFor(docId)
                if (!doomed.exists()) throw FileNotFoundException(docId)
                check(doomed.deleteRecursively()) { "Couldn't delete $docId" }
            }
            METHOD_MOVE_DOCUMENT -> {
                if (!supportsMove) throw UnsupportedOperationException("Move not supported")
                val targetParent = DocumentsContract.getDocumentId(extras!!.uri(EXTRA_TARGET_URI))
                val f = fileFor(docId)
                val target = File(fileFor(targetParent), f.name)
                check(!target.exists() && f.renameTo(target)) { "Couldn't move $f" }
                out.putParcelable(EXTRA_URI, docUri(idFor(target)))
            }
            else -> throw UnsupportedOperationException(method)
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun Bundle.uri(key: String): Uri? = getParcelable(key)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ROOT_ID = "primary:vault"

        /** A provider whose ids BackendFileStore doesn't treat as paths, so it has to walk. */
        const val OPAQUE_AUTHORITY = "com.example.fake.documents"

        // DocumentsContract's @hide call protocol.
        private const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        private const val METHOD_RENAME_DOCUMENT = "android:renameDocument"
        private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
        private const val METHOD_MOVE_DOCUMENT = "android:moveDocument"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TARGET_URI = "android.content.extra.TARGET_URI"

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
        )

        fun treeUri(authority: String = AUTHORITY): Uri = DocumentsContract.buildTreeDocumentUri(authority, ROOT_ID)

        /** Registers a provider over [root] with Robolectric's content resolver. */
        fun install(root: File, authority: String = AUTHORITY): FakeDocumentsProvider {
            val info = ProviderInfo().apply { this.authority = authority }
            return Robolectric.buildContentProvider(FakeDocumentsProvider::class.java)
                .create(info).get()
                .also { it.root = root }
        }
    }
}
