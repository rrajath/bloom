package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Vault-facing file operations on attachment directories. Goes through the
 * store's raw APIs only, so it reaches into the `data/` shards that vault
 * traversal skips. Paths are vault-relative, as everywhere else.
 */
class AttachmentFiles(private val store: FileStore) : DirExists {

    override suspend fun exists(vaultDir: String): Boolean = store.dirExists(vaultDir)

    /** Metadata for one attachment file, or null when it's missing. */
    suspend fun stat(path: String): FileEntry? = store.statRaw(path)

    /** The attachments in [dir], without in-flight `.part` partials. */
    suspend fun list(dir: String): List<FileEntry> =
        store.listDirRaw(dir).filterNot { it.name.endsWith(PART_SUFFIX) }

    /**
     * [wanted] (sanitized), made unique in [dir] case-insensitively. Names
     * held by in-flight `.part` files count as taken, so two imports into
     * one dir can't pick the same name.
     */
    suspend fun uniqueName(dir: String, wanted: String): String {
        val taken = store.listDirRaw(dir).map { it.name.substringAfterLast('/').removeSuffix(PART_SUFFIX) }
        return uniqueAttachmentName(taken, sanitizeAttachmentName(wanted))
    }

    /** Deletes one attachment file. */
    suspend fun delete(path: String): Boolean = store.deleteRaw(path)

    /** Deletes an attach dir with everything in it. */
    suspend fun deleteDir(dir: String): Boolean = store.deleteDir(dir)

    /** Moves [src] into [dstParent], keeping its name. False if the destination exists. */
    suspend fun moveDir(src: String, dstParent: String): Boolean = store.moveDir(src, dstParent)

    /**
     * Copies the tree at [src] to [dst] (created when missing), renaming a file
     * that collides with an existing one. True when every file was copied.
     */
    suspend fun copyDir(src: String, dst: String): Boolean {
        var ok = true
        for (file in store.listDirRaw(src)) {
            val leaf = file.name.substringAfterLast('/')
            ok = copyFile(file.name, joinPath(dst, uniqueName(dst, leaf))) && ok
        }
        for (sub in store.listSubdirs(src)) {
            ok = copyDir(joinPath(src, sub), joinPath(dst, sub)) && ok
        }
        return ok
    }

    /**
     * Moves the contents of [src] into [dst]. When [dst] doesn't exist and
     * shares [src]'s leaf name, it's one directory move; otherwise files move
     * one by one with collision renames (`name-1.ext`). [src] is removed once
     * empty. True when everything moved.
     */
    suspend fun mergeDir(src: String, dst: String): Boolean {
        val srcLeaf = src.trimEnd('/').substringAfterLast('/')
        val dstParent = dst.trimEnd('/').substringBeforeLast('/', "")
        if (!store.dirExists(dst) && dst.trimEnd('/').substringAfterLast('/') == srcLeaf &&
            store.moveDir(src, dstParent)
        ) {
            return true
        }
        var ok = true
        for (file in store.listDirRaw(src)) {
            val leaf = file.name.substringAfterLast('/')
            val target = joinPath(dst, uniqueName(dst, leaf))
            ok = (copyFile(file.name, target) && store.deleteRaw(file.name)) && ok
        }
        for (sub in store.listSubdirs(src)) {
            ok = mergeDir(joinPath(src, sub), joinPath(dst, sub)) && ok
        }
        if (ok) store.deleteDir(src)
        return ok
    }

    /** Total bytes and file count under [dir], recursively. */
    suspend fun sizeOf(dir: String): DirSize {
        var bytes = 0L
        var files = 0
        store.listDirRaw(dir).forEach { bytes += it.size; files++ }
        for (sub in store.listSubdirs(dir)) {
            val s = sizeOf(joinPath(dir, sub))
            bytes += s.bytes
            files += s.files
        }
        return DirSize(bytes, files)
    }

    /**
     * Removes [dir] and then each emptied ancestor, stopping at [stopAt]
     * (exclusive) or the first directory that still holds anything. Used after
     * moving or deleting an attach dir so empty shards and `data/` don't linger.
     */
    suspend fun pruneEmpty(dir: String, stopAt: String = "") {
        var current = dir.trim('/')
        val stop = stopAt.trim('/')
        while (current.isNotEmpty() && current != stop) {
            if (!store.dirExists(current)) {
                current = current.substringBeforeLast('/', "")
                continue
            }
            if (store.listDirRaw(current).isNotEmpty() || store.listSubdirs(current).isNotEmpty()) return
            if (!store.deleteDir(current)) return
            current = current.substringBeforeLast('/', "")
        }
    }

    /**
     * Streams [src] to a new file [dst]. Removes [dst] again on failure or
     * cancellation (cancellation is rethrown).
     */
    suspend fun copyFile(src: String, dst: String): Boolean {
        if (!store.createBinary(dst)) return false
        try {
            store.openInput(src).use { input ->
                store.openOutput(dst).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.deleteRaw(dst) }
            throw e
        } catch (e: Exception) {
            store.deleteRaw(dst)
            return false
        }
    }

    data class DirSize(val bytes: Long, val files: Int)

    companion object {
        /** Suffix of a file still being written; renamed away on success. */
        const val PART_SUFFIX = ".part"
        private const val COPY_BUFFER = 64 * 1024
    }
}
