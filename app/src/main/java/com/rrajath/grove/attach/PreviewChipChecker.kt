package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Existence checks for the outline (1i) and search (1j) preview chips, so a
 * chip shows broken without its note being opened first (e.g. a notebook moved
 * without its attachments).
 *
 * Shares [cache] with read mode, keyed by [ownerLine], the heading the preview
 * sits under: a result found here or in read mode is reused by the other, and
 * the cache's sync and resume invalidation applies to both. At most
 * [parallelism] checks touch the store at once, app-wide, so flinging through
 * a long list can't pile up storage queries; a check waiting for a permit is
 * dropped when its row's coroutine is cancelled.
 */
class PreviewChipChecker(
    private val store: () -> FileStore?,
    private val cache: AttachmentStatusCache,
    /** The parsed doc for an org file, when a caller (search) has none of its own. */
    private val loadDoc: suspend (orgFile: String) -> OrgDocument?,
    private val io: CoroutineDispatcher,
    parallelism: Int = DEFAULT_PARALLELISM,
) {
    private val permits = Semaphore(parallelism)

    /**
     * Whether [link] (written as [rawTarget]) in [orgFile] is there, or null
     * when it can't be told (no vault). [ownerLine] is the line of the heading
     * whose preview shows the link, or -1 for text before the first heading;
     * [doc] is [orgFile] parsed, loaded on demand when null.
     */
    suspend fun status(
        link: LocalAttachment,
        rawTarget: String,
        orgFile: String,
        doc: OrgDocument?,
        ownerLine: Int,
    ): AttachmentStatus? {
        cache.status(AttachmentStatusCache.key(orgFile, rawTarget, ownerLine))?.let { return it }
        val s = store() ?: return null
        return permits.withPermit {
            withContext(io) {
                val parsed = if (doc == null && link.base == AttachmentBase.ATTACH_DIR) {
                    runCatching { loadDoc(orgFile) }.getOrNull()
                } else {
                    doc
                }
                AttachmentResolver(AttachmentLookup(AttachmentFiles(s)), cache)
                    .status(link, rawTarget, orgFile, parsed, docLine = ownerLine)
            }
        }
    }

    companion object {
        const val DEFAULT_PARALLELISM = 4
    }
}
