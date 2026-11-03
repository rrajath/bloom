package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.vault.FileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Whether a read-mode attachment link points at a file that's there. */
sealed interface AttachmentStatus {
    /** The file exists; [entry]'s name is its vault path. */
    data class Present(val entry: FileEntry) : AttachmentStatus {
        val path: String get() = entry.name
    }

    data object Missing : AttachmentStatus
}

/** An image's pixel size, read from its header. */
data class ImageBounds(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height
}

/**
 * In-memory existence and image-size cache for read mode (tech design 7.5), so
 * chips and images don't stat or read headers again on every recomposition.
 *
 * Missing entries are dropped after each sync ([invalidateMissing]), so a file
 * that arrives later (Syncthing) shows up without reopening the note; the whole
 * status map is dropped when a read screen resumes ([clear]). Image sizes are
 * keyed by path + mtime + size, so they stay valid until the file changes.
 * [generation] ticks on every invalidation; composables key their lookups on it.
 */
class AttachmentStatusCache {
    private val statuses = ConcurrentHashMap<String, AttachmentStatus>()
    private val bounds = ConcurrentHashMap<String, ImageBounds>()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    fun status(key: String): AttachmentStatus? = statuses[key]

    fun putStatus(key: String, status: AttachmentStatus) {
        statuses[key] = status
    }

    fun bounds(entry: FileEntry): ImageBounds? = bounds[boundsKey(entry)]

    fun putBounds(entry: FileEntry, value: ImageBounds) {
        bounds[boundsKey(entry)] = value
    }

    /** Forget every Missing entry so it is checked again. */
    fun invalidateMissing() {
        val removed = statuses.entries.removeIf { it.value is AttachmentStatus.Missing }
        if (removed) _generation.value++
    }

    /** Forget every status (image sizes stay; they are keyed by mtime). */
    fun clear() {
        if (statuses.isEmpty()) return
        statuses.clear()
        _generation.value++
    }

    private fun boundsKey(entry: FileEntry) = "${entry.name}:${entry.lastModified}:${entry.size}"

    companion object {
        /** Cache key for a link at [docLine] (-1 when unknown) of [orgFile]. */
        fun key(orgFile: String, rawTarget: String, docLine: Int?): String =
            "$orgFile\u0000$rawTarget\u0000${docLine ?: -1}"
    }
}

/**
 * Resolves read-mode attachment links to files through [lookup], memoized in
 * [cache]. [docLine] pins the owning heading when the caller knows the link's
 * line (images); chips search [doc] for the link instead, [preferLines] first.
 */
class AttachmentResolver(
    private val lookup: AttachmentLookup,
    private val cache: AttachmentStatusCache,
) {
    suspend fun status(
        link: LocalAttachment,
        rawTarget: String,
        orgFile: String,
        doc: OrgDocument?,
        docLine: Int? = null,
        preferLines: IntRange? = null,
    ): AttachmentStatus {
        val key = AttachmentStatusCache.key(orgFile, rawTarget, docLine)
        cache.status(key)?.let { return it }
        val entry = try {
            lookup.findEntry(link, rawTarget, orgFile, doc, preferLines, docLine)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // An unreadable provider reads as missing; the next sync re-checks it.
        }
        val status = entry?.let { AttachmentStatus.Present(it) } ?: AttachmentStatus.Missing
        cache.putStatus(key, status)
        return status
    }
}
