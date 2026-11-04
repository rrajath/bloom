package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileStore
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** One file the user picked: where to read it and what to call it. */
data class AttachSource(
    /** Opaque handle the [SourceOpener] understands (a content URI string on device). */
    val uri: String,
    /** The name the picker reported (`OpenableColumns.DISPLAY_NAME`), unsanitized. */
    val displayName: String,
    /** Byte size when the picker knows it, for determinate progress. */
    val size: Long?,
    /** MIME type the provider reported, when known; picks HEIC/JPEG for photo processing. */
    val mimeType: String? = null,
)

/** Opens an [AttachSource] for reading; null or a [FileNotFoundException] means it's gone. */
fun interface SourceOpener {
    fun open(uri: String): InputStream?
}

/** Snapshot of a running import, for the progress snackbar. */
data class ImportProgress(
    /** 1-based index of the item being copied. */
    val index: Int,
    val count: Int,
    /** Final name of the item being copied. */
    val name: String,
    val bytesDone: Long,
    /** Total bytes of the current item, or null when unknown (indeterminate). */
    val bytesTotal: Long?,
)

/** Why a copy failed, worded for the `Couldn't attach <name>: <reason>` toast. */
enum class ImportFailure(val reason: String) {
    StorageFull("storage full"),
    SourceUnavailable("file unavailable"),
    WriteFailed("couldn't write to vault"),
}

sealed interface ImportResult {
    /** Every item landed. [names] are the final names, in pick order. */
    data class Success(val names: List<String>, val renamed: Set<String>) : ImportResult

    /** Item [name] failed; nothing from this import is left behind. */
    data class Failed(val name: String, val failure: ImportFailure) : ImportResult
}

/**
 * Streams picked files into an attach dir (6.4). Each item is written to
 * `<name>.part` and renamed into place on success, so Emacs and Syncthing never
 * see a half-written file. Any failure or cancellation removes everything this
 * import wrote, finished items included, and prunes directories it created, so
 * an aborted attach leaves no trace.
 */
class AttachmentImporter(
    private val store: FileStore,
    private val opener: SourceOpener,
    /** Resizes and converts Camera/Photos images; null copies everything byte for byte. */
    private val photoProcessor: PhotoProcessor? = null,
) {
    private val files = AttachmentFiles(store)

    /**
     * Copies [sources] into [dir] (vault-relative, created when missing).
     * [pruneStop] bounds the empty-directory cleanup on failure (the org
     * file's own directory). Cancellation is cooperative and rethrown after
     * cleanup. With [processPhotos] (Camera and Photos, never Files), JPEG and
     * HEIC sources go through the [photoProcessor] first: resized to
     * [photoLongEdge] when set, and HEIC always converted to `.jpg`.
     */
    suspend fun import(
        dir: String,
        sources: List<AttachSource>,
        pruneStop: String,
        processPhotos: Boolean = false,
        photoLongEdge: Int? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult {
        require(sources.isNotEmpty()) { "nothing to import" }
        val dirExisted = store.dirExists(dir)
        val written = mutableListOf<String>()
        val renamed = mutableSetOf<String>()

        suspend fun cleanUp(partial: String?) {
            partial?.let { store.deleteRaw(it) }
            written.forEach { store.deleteRaw(joinPath(dir, it)) }
            if (!dirExisted) files.pruneEmpty(dir, pruneStop)
        }

        // Listed once per batch: only one copy runs at a time (AttachmentCopyManager),
        // so the names this batch claims are the only ones that can appear meanwhile.
        val taken = files.takenNames(dir)
        for ((i, src) in sources.withIndex()) {
            var wanted = sanitizeAttachmentName(src.displayName)
            val processor = photoProcessor
            val processed: File? = if (processPhotos && processor != null && isTranscodableImage(src)) {
                onProgress(ImportProgress(i + 1, sources.size, wanted, 0, null))
                try {
                    processor.process(src, { opener.open(src.uri) }, photoLongEdge)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { cleanUp(null) }
                    throw e
                }
            } else {
                null
            }
            try {
                if (processed != null) wanted = withJpegExtension(wanted)
                val name = taken.claim(wanted)
                if (name != wanted) renamed += name
                val partPath = joinPath(dir, name + AttachmentFiles.PART_SUFFIX)
                val total = processed?.length() ?: src.size
                val open: () -> InputStream? =
                    if (processed != null) ({ FileInputStream(processed) }) else ({ opener.open(src.uri) })
                onProgress(ImportProgress(i + 1, sources.size, name, 0, total))
                val failure = try {
                    copyOne(open, partPath) { done -> onProgress(ImportProgress(i + 1, sources.size, name, done, total)) }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { cleanUp(partPath) }
                    throw e
                }
                if (failure != null) {
                    cleanUp(partPath)
                    return ImportResult.Failed(name, failure)
                }
                if (!store.renameRaw(partPath, name)) {
                    cleanUp(partPath)
                    return ImportResult.Failed(name, ImportFailure.WriteFailed)
                }
                written += name
            } finally {
                processed?.delete()
            }
        }
        return ImportResult.Success(written.toList(), renamed)
    }

    /** Streams one source into [partPath]. Null on success, else why it failed. */
    private suspend fun copyOne(open: () -> InputStream?, partPath: String, onBytes: (Long) -> Unit): ImportFailure? {
        val input = try {
            open()
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return ImportFailure.SourceUnavailable
        input.use { stream ->
            if (!store.createBinary(partPath)) return ImportFailure.WriteFailed
            try {
                store.openOutput(partPath).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = try {
                            stream.read(buffer)
                        } catch (e: IOException) {
                            return ImportFailure.SourceUnavailable
                        }
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= REPORT_EVERY) {
                            lastReport = done
                            onBytes(done)
                        }
                    }
                    onBytes(done)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return if (isNoSpace(e)) ImportFailure.StorageFull else ImportFailure.WriteFailed
            } catch (e: Exception) {
                return ImportFailure.WriteFailed
            }
        }
        return null
    }

    private fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message.orEmpty()
            if ("ENOSPC" in msg || "No space left" in msg) return true
            t = t.cause
        }
        return false
    }

    companion object {
        private const val COPY_BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256 * 1024L
    }
}

/** `182 KB`, `2.4 MB` (one decimal from 1 MB), `0 B`. */
fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < MB -> "${(bytes + 512) / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / MB.toDouble())
}

/** The snackbar subline for one item: `2.1 of 4.1 MB`, `120 of 180 KB`, or just `2.1 MB` when the total is unknown. */
fun formatCopyBytes(done: Long, total: Long?): String {
    if (total == null || total <= 0) return formatAttachmentSize(done)
    return if (total >= MB) {
        String.format(java.util.Locale.US, "%.1f of %.1f MB", done / MB.toDouble(), total / MB.toDouble())
    } else {
        "${(done + 512) / 1024} of ${(total + 512) / 1024} KB"
    }
}

private const val MB = 1024L * 1024L
