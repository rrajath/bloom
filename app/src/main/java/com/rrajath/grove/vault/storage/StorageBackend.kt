package com.rrajath.grove.vault.storage

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

/**
 * The operations a storage provider must offer the vault, in terms of document
 * ids. [SafBackend] is the only implementation; the interface is the seam for
 * a future built-in network client, so nothing above it may assume SAF.
 */
interface StorageBackend {
    /**
     * Children of the directory [dirId]. Suspends while the provider reports
     * it is still loading, up to the backend's deadline, then returns
     * [Listing.Partial] with what it has.
     */
    suspend fun listChildren(dirId: String): Listing
}
