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
    /**
     * The backup document directly inside [treeUri]: [name] itself or a copy the provider
     * renamed on create (`name (1).json`), whichever was modified last (see
     * [SafBackupWriter.bestMatch]). Null when there's none.
     */
    suspend fun find(treeUri: String, name: String): String?

    /** Create [name] in [treeUri]; the provider may pick a different name. Null when it refuses. */
    suspend fun create(treeUri: String, name: String): String?

    /** Throws when the provider refuses [mode] or the document is gone. */
    suspend fun open(docUri: String, mode: String): OutputStream

    /** False when the provider can't (Seafile has no delete). */
    suspend fun remove(docUri: String): Boolean
}

/**
 * Writes [fileName] (see [fileNameFor]) into the automatic-backup folder, overwriting it in place.
 *
 * Always opens with "wt" (write + truncate): on Android 10+ some providers don't
 * truncate on bare "w", which would leave a stale tail and corrupt the JSON. A
 * provider that rejects "wt" gets the file removed and recreated, then a bare
 * "w" on the fresh, empty document; one that can't remove (Seafile) fails into
 * the error status instead of risking a corrupt file.
 */
class SafBackupWriter(
    private val docs: BackupDocuments,
    /** `<app name>-settings.json`, from [fileNameFor]. */
    val fileName: String,
) : BackupWriter {

    /** Folder tree URI to its backup document URI, from the last successful write. */
    @Volatile
    private var cached: Pair<String, String>? = null

    /** The backup file in [treeUri], or null when there's none. Used by Restore. */
    suspend fun find(treeUri: String): String? = docs.find(treeUri, fileName)

    override suspend fun exists(treeUri: String): Boolean = find(treeUri) != null

    override suspend fun write(treeUri: String, text: String): Result<Unit> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        cached?.takeIf { it.first == treeUri }?.second?.let { doc ->
            if (tryWrite(doc, "wt", bytes)) return Result.success(Unit)
            // Gone or moved: resolve once more below.
            cached = null
        }
        return try {
            val doc = find(treeUri) ?: create(treeUri) ?: throw IOException("can't create $fileName")
            if (tryWrite(doc, "wt", bytes)) {
                cached = treeUri to doc
                return Result.success(Unit)
            }
            if (!docs.remove(doc)) throw IOException("provider supports neither truncate nor remove")
            val fresh = create(treeUri) ?: throw IOException("can't recreate $fileName")
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

    private suspend fun create(treeUri: String): String? = docs.create(treeUri, fileName)

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
        /** One child document of the backup folder, as [bestMatch] weighs it. */
        data class Entry(val id: String, val name: String, val lastModified: Long)

        /**
         * The backup among [entries]: [fileName] or a provider-renamed copy of it
         * ("bloom-settings (1).json", made when a create raced a stale listing), the most
         * recently modified first, an exact name winning a tie. Writes go to whichever doc
         * was resolved, so the newest one holds the latest backup.
         */
        fun bestMatch(fileName: String, entries: List<Entry>): Entry? {
            val stem = fileName.removeSuffix(".json")
            val renamed = Regex("^" + Regex.escape(stem) + """ \(\d+\)\.json$""")
            return entries
                .filter { it.name == fileName || renamed.matches(it.name) }
                .maxWithOrNull(compareBy<Entry>({ it.lastModified }, { it.name == fileName }))
        }

        /** "Bloom" -> "bloom-settings.json", "Bloom Debug" -> "bloom-debug-settings.json". */
        fun fileNameFor(appName: String): String =
            appName.lowercase(java.util.Locale.ROOT).replace(' ', '-') + "-settings.json" // i18n: file name suffix
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
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        // Some providers (Seafile) first answer with an empty "still loading" cursor;
        // creating on that would add a duplicate, so wait for the real listing.
        repeat(LOADING_RETRIES) {
            val cursor = resolver.query(children, projection, null, null, null) ?: return null
            cursor.use { c ->
                val loading = c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                val entries = buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val lastModified = if (c.isNull(2)) 0L else c.getLong(2)
                        add(SafBackupWriter.Companion.Entry(id, c.getString(1).orEmpty(), lastModified))
                    }
                }
                SafBackupWriter.bestMatch(name, entries)?.let {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString()
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
