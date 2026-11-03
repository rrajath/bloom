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
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.Robolectric

/**
 * A java.io-backed documents provider with path document ids
 * (`primary:vault/a/b.org`) like the platform's ExternalStorageProvider. Lets
 * Robolectric tests drive [SafFileStore] end to end and count the cursor
 * queries it issues.
 *
 * A plain [ContentProvider] speaking the DocumentsContract protocol, not a
 * `DocumentsProvider`: Robolectric routes `ContentResolver.query` to the
 * 5-arg provider method, which `DocumentsProvider` makes final and throwing.
 *
 * [supportsMove] = false makes moveDocument fail, exercising copy + delete.
 */
class FakeDocumentsProvider : ContentProvider() {

    lateinit var root: File
    val queries = AtomicInteger()
    var supportsMove = true
    private lateinit var authority: String

    override fun onCreate(): Boolean = true

    override fun attachInfo(context: android.content.Context?, info: ProviderInfo) {
        super.attachInfo(context, info)
        authority = info.authority
    }

    private fun fileFor(docId: String): File {
        if (docId == ROOT_ID) return root
        require(docId.startsWith("$ROOT_ID/")) { "Not in this tree: $docId" }
        return File(root, docId.removePrefix("$ROOT_ID/"))
    }

    private fun idFor(f: File): String {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        return if (rel.isEmpty()) ROOT_ID else "$ROOT_ID/$rel"
    }

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri(authority), docId)

    private fun MatrixCursor.addFile(f: File) {
        val row = newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, idFor(f))
        row.add(Document.COLUMN_DISPLAY_NAME, f.name)
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
            f.listFiles().orEmpty().sortedBy { it.name }.forEach { cursor.addFile(it) }
        } else {
            if (!f.exists()) throw FileNotFoundException(docId)
            cursor.addFile(f)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(DocumentsContract.getDocumentId(uri))
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val inUri = requireNotNull(extras?.uri(EXTRA_URI)) { "No document uri" }
        val docId = DocumentsContract.getDocumentId(inUri)
        val out = Bundle()
        when (method) {
            METHOD_CREATE_DOCUMENT -> {
                val parent = fileFor(docId)
                val name = extras!!.getString(Document.COLUMN_DISPLAY_NAME)!!
                var target = File(parent, name)
                var n = 1
                while (target.exists()) target = File(parent, "$name (${n++})")
                val isDir = extras.getString(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR
                check(if (isDir) target.mkdir() else target.createNewFile()) { "Couldn't create $target" }
                out.putParcelable(EXTRA_URI, docUri(idFor(target)))
            }
            METHOD_RENAME_DOCUMENT -> {
                val f = fileFor(docId)
                val target = File(f.parentFile, extras!!.getString(Document.COLUMN_DISPLAY_NAME)!!)
                check(!target.exists() && f.renameTo(target)) { "Couldn't rename $f" }
                out.putParcelable(EXTRA_URI, docUri(idFor(target)))
            }
            METHOD_DELETE_DOCUMENT -> check(fileFor(docId).deleteRecursively()) { "Couldn't delete $docId" }
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

        /** A provider whose ids SafFileStore doesn't treat as paths, so it has to walk. */
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
