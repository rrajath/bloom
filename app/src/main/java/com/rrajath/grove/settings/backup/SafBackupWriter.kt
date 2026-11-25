package com.rrajath.grove.settings.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.OutputStream

/**
 * The few document operations [SafBackupWriter] needs, over string URIs so the
 * write/fallback logic runs in plain JVM tests. [SafBackupDocuments] in the app.
 */
interface BackupDocuments {
    /** The document named [name] directly inside the folder [treeUri], or null. */
    suspend fun find(treeUri: String, name: String): String?

    /** Create [name] in [treeUri]; the provider may pick a different name. Null when it refuses. */
    suspend fun create(treeUri: String, name: String): String?

    /** Throws when the provider refuses [mode] or the document is gone. */
    suspend fun open(docUri: String, mode: String): OutputStream

    /** False when the provider can't (Seafile has no delete). */
    suspend fun remove(docUri: String): Boolean
}

/**
 * Writes [FILE_NAME] into the automatic-backup folder, overwriting it in place.
 *
 * Always opens with "wt" (write + truncate): on Android 10+ some providers don't
 * truncate on bare "w", which would leave a stale tail and corrupt the JSON. A
 * provider that rejects "wt" gets the file removed and recreated, then a bare
 * "w" on the fresh, empty document; one that can't remove (Seafile) fails into
 * the error status instead of risking a corrupt file.
 */
class SafBackupWriter(private val docs: BackupDocuments) : BackupWriter {

    /** Folder tree URI to its backup document URI, from the last successful write. */
    @Volatile
    private var cached: Pair<String, String>? = null

    /** The backup file in [treeUri], or null when there's none. Used by Restore. */
    suspend fun find(treeUri: String): String? = docs.find(treeUri, FILE_NAME)

    override suspend fun write(treeUri: String, text: String): Result<Unit> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        cached?.takeIf { it.first == treeUri }?.second?.let { doc ->
            if (tryWrite(doc, "wt", bytes)) return Result.success(Unit)
            // Gone or moved: resolve once more below.
            cached = null
        }
        return try {
            val doc = find(treeUri) ?: create(treeUri) ?: throw IOException("can't create $FILE_NAME")
            if (tryWrite(doc, "wt", bytes)) {
                cached = treeUri to doc
                return Result.success(Unit)
            }
            if (!docs.remove(doc)) throw IOException("provider supports neither truncate nor remove")
            val fresh = create(treeUri) ?: throw IOException("can't recreate $FILE_NAME")
            docs.open(fresh, "w").use { it.write(bytes) }
            cached = treeUri to fresh
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "automatic backup write failed", e)
            Result.failure(e)
        }
    }

    private suspend fun create(treeUri: String): String? = docs.create(treeUri, FILE_NAME)

    private suspend fun tryWrite(doc: String, mode: String, bytes: ByteArray): Boolean = try {
        docs.open(doc, mode).use { it.write(bytes) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "open($mode) failed on $doc", e)
        false
    }

    companion object {
        const val FILE_NAME = "bloom-settings.json"
        private const val TAG = "AutoBackup" // i18n: log tag
    }
}

/** [BackupDocuments] over the Storage Access Framework. */
class SafBackupDocuments(private val resolver: ContentResolver) : BackupDocuments {

    override suspend fun find(treeUri: String, name: String): String? {
        val tree = Uri.parse(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        // Some providers (Seafile) first answer with an empty "still loading" cursor;
        // creating on that would add a duplicate, so wait for the real listing.
        repeat(LOADING_RETRIES) {
            val cursor = resolver.query(children, projection, null, null, null) ?: return null
            cursor.use { c ->
                val loading = c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString()
                    }
                }
                if (!loading) return null
            }
            delay(LOADING_WAIT_MS)
        }
        return null
    }

    override suspend fun create(treeUri: String, name: String): String? {
        val tree = Uri.parse(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, "application/json", name) ?: return null
        Log.i(TAG, "created backup file $doc")
        return doc.toString()
    }

    override suspend fun open(docUri: String, mode: String): OutputStream =
        resolver.openOutputStream(Uri.parse(docUri), mode) ?: throw IOException("no output stream for $docUri")

    override suspend fun remove(docUri: String): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(docUri)) }.getOrDefault(false)

    private companion object {
        const val TAG = "AutoBackup" // i18n: log tag
        const val LOADING_RETRIES = 10
        const val LOADING_WAIT_MS = 500L
    }
}
