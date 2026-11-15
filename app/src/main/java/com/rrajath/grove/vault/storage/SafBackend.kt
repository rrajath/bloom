package com.rrajath.grove.vault.storage

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * [StorageBackend] over the Storage Access Framework, for any documents
 * provider behind a persisted tree URI.
 *
 * Providers that fetch from a server (Seafile, Nextcloud) answer a children
 * query with an empty cursor flagged [DocumentsContract.EXTRA_LOADING] and
 * notify when the real rows are ready. [listChildren] waits for that, without
 * pinning a thread, and never reports such a directory as empty.
 */
class SafBackend(
    private val context: Context,
    private val treeUri: Uri,
    /** How long a loading directory is waited on before the listing is [Listing.Partial]. */
    private val listingDeadlineMs: Long = DEFAULT_LISTING_DEADLINE_MS,
) : StorageBackend {

    private val resolver get() = context.contentResolver

    override val rootId: String get() = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Shared storage and SD cards (FAT/exFAT) match names case-insensitively:
     * there `inbox.org` *is* an existing `Inbox.org`, and creating it makes the
     * provider pick a free name (`inbox (1).org`) instead.
     */
    override val caseInsensitive: Boolean = treeUri.authority == EXTERNAL_STORAGE_AUTHORITY

    override fun docIdForPath(path: String): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val root = rootId
        if (':' !in root) return null
        return if (root.endsWith(':')) "$root$path" else "$root/$path"
    }

    override fun absolutePath(name: String): String? =
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) null else externalStoragePath(rootId, name)

    override fun contentUri(docId: String): Uri? = docUri(docId)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** One children query's result: its rows, whether the provider is still loading, and where it will notify. */
    private class Answer(
        val entries: List<DocEntry>,
        val loading: Boolean,
        val notificationUris: List<Uri>,
    )

    private sealed interface Outcome {
        class Rows(val answer: Answer) : Outcome
        class Failed(val listing: Listing.Failed) : Outcome
    }

    override suspend fun listChildren(dirId: String): Listing = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val first = when (val outcome = query(childrenUri)) {
            is Outcome.Failed -> return@withContext outcome.listing
            is Outcome.Rows -> outcome.answer
        }
        // The common case (local storage, Syncthing) is never loading: no observers, no waiting.
        if (!first.loading) return@withContext Listing.Complete(first.entries)

        // Some providers notify on the non-tree children URI rather than the tree one.
        val fallbackUri = treeUri.authority?.let { DocumentsContract.buildChildDocumentsUri(it, dirId) }
        // Conflated: one pending wake-up is enough, however many notifications arrive.
        val signal = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                signal.trySend(Unit)
            }
        }
        val watched = mutableSetOf<Uri>()
        var last = first.entries
        try {
            val settled = withTimeoutOrNull<Listing>(listingDeadlineMs) {
                var answer = first
                while (true) {
                    val toWatch = (answer.notificationUris + listOfNotNull(childrenUri, fallbackUri))
                        .filter { it !in watched }
                    if (toWatch.isNotEmpty()) {
                        toWatch.forEach {
                            resolver.registerContentObserver(it, true, observer)
                            watched += it
                        }
                        // Fall through to a re-query: a change between the last query
                        // and this registration would otherwise never wake us.
                    } else {
                        signal.receive()
                    }
                    answer = when (val outcome = query(childrenUri)) {
                        is Outcome.Failed -> return@withTimeoutOrNull outcome.listing
                        is Outcome.Rows -> outcome.answer
                    }
                    last = answer.entries
                    if (!answer.loading) return@withTimeoutOrNull Listing.Complete(answer.entries)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            settled ?: Listing.Partial(last)
        } finally {
            resolver.unregisterContentObserver(observer)
            signal.close()
        }
    }

    override suspend fun stat(docId: String): StorageResult<DocEntry> = withContext(Dispatchers.IO) {
        val cursor = try {
            resolver.query(docUri(docId), PROJECTION, null, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            return@withContext StorageError.NotFound
        } catch (e: Exception) {
            return@withContext providerError(e)
        } ?: return@withContext StorageError.NotFound
        cursor.use {
            if (!it.moveToFirst()) return@withContext StorageError.NotFound
            StorageResult.Ok(entryOf(it, docId) ?: return@withContext StorageError.NotFound)
        }
    }

    // Recycle: the stream is the return value; callers close it.
    @SuppressLint("Recycle")
    override suspend fun openRead(docId: String): StorageResult<InputStream> = withContext(Dispatchers.IO) {
        guarded(StorageOp.Read) { resolver.openInputStream(docUri(docId)) }
            .let { it.orNotFound() }
    }

    // Recycle: the stream is the return value; callers close it.
    @SuppressLint("Recycle")
    override suspend fun openWrite(docId: String): StorageResult<OutputStream> = withContext(Dispatchers.IO) {
        // "wt" = write + truncate. Plain "w" does NOT truncate on all providers.
        guarded(StorageOp.Write) { resolver.openOutputStream(docUri(docId), "wt") }
            .let { it.orNotFound() }
    }

    override suspend fun write(docId: String, content: ByteArray, ifMatch: String?): StorageResult<String> =
        withContext(Dispatchers.IO) {
            if (ifMatch != null) {
                // No atomic compare-and-set over SAF: look first. A race between this
                // and the write is possible, so this narrows the window rather than closing it.
                when (val current = stat(docId)) {
                    is StorageError -> return@withContext current
                    is StorageResult.Ok -> if (current.value.revision != ifMatch) {
                        return@withContext StorageError.Conflict(current.value.revision)
                    }
                }
            }
            val written = guarded(StorageOp.Write) {
                resolver.openOutputStream(docUri(docId), "wt")?.use { it.write(content) }
            }
            when (written) {
                is StorageError -> written
                is StorageResult.Ok -> when {
                    written.value == null -> StorageError.NotFound
                    else -> when (val after = stat(docId)) {
                        is StorageResult.Ok -> StorageResult.Ok(after.value.revision.orEmpty())
                        // The write landed but the follow-up stat didn't: the content is saved.
                        is StorageError -> StorageResult.Ok("")
                    }
                }
            }
        }

    override suspend fun createFile(parentId: String, name: String): StorageResult<String> =
        createNamed(parentId, name, "application/octet-stream")

    override suspend fun createDir(parentId: String, name: String): StorageResult<String> =
        createNamed(parentId, name, DocumentsContract.Document.MIME_TYPE_DIR)

    /**
     * "application/octet-stream" has no mapped extension, so stock providers keep
     * a file's name as-is ("text/plain" would make "test.org" -> "test.org.txt").
     * A provider that still picked another name (`name (1).org`, because something
     * we can't see holds [name]) gets one rename attempt, then the stray removed.
     */
    private suspend fun createNamed(parentId: String, name: String, mime: String): StorageResult<String> =
        withContext(Dispatchers.IO) {
            var created = guarded(StorageOp.Create) {
                DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
            }.orRefused(StorageOp.Create, parentId, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
                .let { if (it is StorageError) return@withContext it else (it as StorageResult.Ok).value }
            val actual = displayNameOf(created)
            if (actual != null && actual != name) {
                guarded(StorageOp.Rename) { DocumentsContract.renameDocument(resolver, created, name) }
                    .getOrNull()?.let { created = it }
                val final = displayNameOf(created)
                if (final != null && final != name) {
                    guarded(StorageOp.Delete) { DocumentsContract.deleteDocument(resolver, created) }
                    return@withContext StorageError.NameTaken
                }
            }
            StorageResult.Ok(DocumentsContract.getDocumentId(created))
        }

    override suspend fun delete(docId: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        when (val r = guarded(StorageOp.Delete) { DocumentsContract.deleteDocument(resolver, docUri(docId)) }) {
            is StorageError.ProviderError -> refused(StorageOp.Delete, docId, DocumentsContract.Document.FLAG_SUPPORTS_DELETE, r)
            is StorageError -> r
            is StorageResult.Ok ->
                if (r.value) StorageResult.Ok(Unit)
                else refused(StorageOp.Delete, docId, DocumentsContract.Document.FLAG_SUPPORTS_DELETE, StorageError.ProviderError(null))
        }
    }

    override suspend fun rename(docId: String, newName: String): StorageResult<String> = withContext(Dispatchers.IO) {
        guarded(StorageOp.Rename) { DocumentsContract.renameDocument(resolver, docUri(docId), newName) }
            .orRefused(StorageOp.Rename, docId, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
            .map { DocumentsContract.getDocumentId(it) }
    }

    override suspend fun move(docId: String, fromParent: String, toParent: String): StorageResult<String> =
        withContext(Dispatchers.IO) {
            // moveDocument needs API 24 and isn't supported by every provider.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext StorageError.Unsupported(StorageOp.Move)
            guarded(StorageOp.Move) {
                DocumentsContract.moveDocument(resolver, docUri(docId), docUri(fromParent), docUri(toParent))
            }.orRefused(StorageOp.Move, docId, DocumentsContract.Document.FLAG_SUPPORTS_MOVE)
                .map { DocumentsContract.getDocumentId(it) }
        }

    private fun displayNameOf(uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    private fun entryOf(cursor: Cursor, fallbackId: String): DocEntry? {
        val name = cursor.getString(1) ?: return null
        return DocEntry(
            docId = cursor.getString(0) ?: fallbackId,
            name = name,
            lastModified = cursor.getLong(2),
            size = cursor.getLong(3),
            isDir = cursor.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    /** Runs a provider call and maps whatever it throws; cancellation passes through. */
    private inline fun <T> guarded(op: StorageOp, block: () -> T): StorageResult<T> = try {
        StorageResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnsupportedOperationException) {
        StorageError.Unsupported(op)
    } catch (e: FileNotFoundException) {
        StorageError.NotFound
    } catch (e: Exception) {
        providerError(e)
    }

    private fun providerError(e: Exception) = StorageError.ProviderError(e.message ?: e.javaClass.simpleName)

    /**
     * DocumentsContract's helpers swallow a provider's exception and answer null
     * (or false), so a refusal arrives without a reason. The document's own flags
     * usually give it: a provider that doesn't advertise [flag] doesn't offer [op].
     */
    private fun <T : Any> StorageResult<T?>.orRefused(op: StorageOp, docId: String, flag: Int): StorageResult<T> =
        when (this) {
            is StorageError.ProviderError -> refused(op, docId, flag, this)
            is StorageError -> this
            is StorageResult.Ok -> value?.let { StorageResult.Ok(it) }
                ?: refused(op, docId, flag, StorageError.ProviderError(null))
        }

    /** [StorageError.Unsupported] when [docId] doesn't advertise [flag]; else [fallback]. */
    private fun refused(op: StorageOp, docId: String, flag: Int, fallback: StorageError): StorageError {
        val flags = flagsOf(docId)
        return if (flags != null && flags and flag == 0) StorageError.Unsupported(op) else fallback
    }

    /** [docId]'s `COLUMN_FLAGS`; null when the provider doesn't say. */
    private fun flagsOf(docId: String): Int? = runCatching {
        resolver.query(docUri(docId), arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }
    }.getOrNull()

    /** A null stream means the document couldn't be opened. */
    private fun <T : Any> StorageResult<T?>.orNotFound(): StorageResult<T> = when (this) {
        is StorageError -> this
        is StorageResult.Ok -> value?.let { StorageResult.Ok(it) } ?: StorageError.NotFound
    }

    private fun query(childrenUri: Uri): Outcome {
        val cursor = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed(Listing.Failed(e.message ?: e.javaClass.simpleName, e))
        } ?: return Outcome.Failed(Listing.Failed("The provider returned no cursor"))
        return cursor.use { read(it) }
    }

    private fun read(cursor: Cursor): Outcome {
        val extras = cursor.extras
        extras?.getString(DocumentsContract.EXTRA_ERROR)?.let {
            return Outcome.Failed(Listing.Failed(it))
        }
        val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
        val entries = buildList {
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val isDir = cursor.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR
                add(DocEntry(docId, name, cursor.getLong(2), cursor.getLong(3), isDir))
            }
        }
        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.notificationUris.orEmpty()
        } else {
            listOfNotNull(cursor.notificationUri)
        }
        return Outcome.Rows(Answer(entries, loading, uris))
    }

    companion object {
        const val DEFAULT_LISTING_DEADLINE_MS = 15_000L

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

/** The platform's local-storage documents provider, whose document ids are paths. */
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

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
