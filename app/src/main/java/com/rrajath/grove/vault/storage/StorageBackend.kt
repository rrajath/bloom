package com.rrajath.grove.vault.storage

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/**
 * One child of a directory, as a [StorageBackend] reports it. [docId] is the
 * backend's own handle for the document (a SAF document id), opaque to callers.
 */
data class DocEntry(
    val docId: String,
    val name: String,
    val lastModified: Long,
    val size: Long,
    val isDir: Boolean,
    /**
     * An opaque token that changes when the file's content does: `"$lastModified:$size"`
     * on SAF, an ETag on a backend that has one. Null for directories and for
     * backends that can't tell.
     */
    val revision: String? = if (isDir) null else "$lastModified:$size",
    /** What the provider lets other apps do to this document ([StorageCapabilities.ALL] when it doesn't say). */
    val capabilities: StorageCapabilities = StorageCapabilities.ALL,
)

/**
 * What a directory listing could tell us. A listing the provider hasn't
 * finished producing is not an empty directory: callers must not act on
 * absence (prune, delete, forget) unless the listing is [Complete].
 */
sealed interface Listing {
    /** Whatever rows the backend returned; empty for [Failed]. */
    val entries: List<DocEntry>

    /** The provider finished: [entries] is every child. */
    data class Complete(override val entries: List<DocEntry>) : Listing

    /**
     * The provider was still loading when the deadline passed. [entries] is
     * what the last answer held; children may be missing, so absence proves
     * nothing.
     */
    data class Partial(override val entries: List<DocEntry>) : Listing

    /** The provider reported an error, rejected the id, or couldn't be reached. */
    data class Failed(val reason: String, val cause: Throwable? = null) : Listing {
        override val entries: List<DocEntry> get() = emptyList()
    }
}

/** What an operation was trying to do, for [StorageError.Unsupported]. */
enum class StorageOp { Create, CreateFolder, Rename, Move, Delete, Write, Read }

/**
 * The operations a storage provider must offer the vault, in terms of document
 * ids: transport only, with no path logic and no caching (that is
 * [BackendFileStore]'s job). [SafBackend] is the only implementation; the
 * interface is the seam for a future built-in network client, so nothing above
 * it may assume SAF.
 *
 * Mutations return a [StorageResult] and never throw (cancellation aside): a
 * provider that throws, returns null, or lacks the operation is mapped to the
 * matching [StorageError].
 */
interface StorageBackend {
    /** The document id of the vault's root directory. */
    val rootId: String

    /** What has to be declared about this provider because no signal shows it. */
    val profile: ProviderProfile

    /** True when the provider matches names in any case, so `inbox.org` is an existing `Inbox.org`. */
    val caseInsensitive: Boolean get() = profile.caseInsensitiveNames

    /**
     * The document id of the file or folder at the vault-relative [path], for a
     * provider whose ids are paths (local storage); null when ids are opaque
     * and only a listing can find a document.
     */
    fun docIdForPath(path: String): String?

    /** The device filesystem path of [name] (vault-relative), or null when the backend can't know one. */
    fun absolutePath(name: String): String?

    /**
     * A URI another app can read for [docId], possibly slow to produce (a
     * network backend would download first, hence callers must not use it on the
     * main thread or in tight loops).
     */
    fun contentUri(docId: String): Uri?

    /**
     * Children of the directory [dirId]. Suspends while the provider reports
     * it is still loading, up to the backend's deadline, then returns
     * [Listing.Partial] with what it has.
     */
    suspend fun listChildren(dirId: String): Listing

    /** Metadata for [docId]; [StorageError.NotFound] when it's gone. */
    suspend fun stat(docId: String): StorageResult<DocEntry>

    /** Opens [docId] for reading. The caller closes the stream. */
    suspend fun openRead(docId: String): StorageResult<InputStream>

    /** Opens the existing file [docId] for writing, truncating it. The caller closes the stream. */
    suspend fun openWrite(docId: String): StorageResult<OutputStream>

    /**
     * Replaces the content of [docId] with [content]. When [ifMatch] is set and
     * the file's current revision differs, returns [StorageError.Conflict]
     * without writing; a backend with no atomic compare-and-set emulates that
     * with a stat first. Returns the new revision.
     */
    suspend fun write(docId: String, content: ByteArray, ifMatch: String? = null): StorageResult<String>

    /**
     * Creates an empty file called exactly [name] in [parentId] and returns its
     * document id. A provider that renames to dodge a clash (`a (1).org`) gets
     * the stray removed and [StorageError.NameTaken] returned.
     */
    suspend fun createFile(parentId: String, name: String): StorageResult<String>

    /** Creates a folder called exactly [name] in [parentId]; same naming rule as [createFile]. */
    suspend fun createDir(parentId: String, name: String): StorageResult<String>

    /**
     * Creates the file at [relativePath] (slash-separated, through folders that
     * may not exist yet) under the existing folder [ancestorId], for a provider
     * whose first write makes the missing folders
     * ([ProviderProfile.writeCreatesFolders]). Others answer
     * [StorageError.Unsupported] for [StorageOp.CreateFolder].
     */
    suspend fun createFileAtPath(ancestorId: String, relativePath: String): StorageResult<String> =
        StorageError.Unsupported(StorageOp.CreateFolder)

    /** Deletes [docId] (a folder goes with its children where the provider allows it). */
    suspend fun delete(docId: String): StorageResult<Unit>

    /** Renames [docId] to [newName] in place; returns its (possibly new) document id. */
    suspend fun rename(docId: String, newName: String): StorageResult<String>

    /** Moves [docId] from [fromParent] to [toParent]; returns its (possibly new) document id. */
    suspend fun move(docId: String, fromParent: String, toParent: String): StorageResult<String>
}
