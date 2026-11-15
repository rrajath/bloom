package com.rrajath.grove.vault.storage

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
