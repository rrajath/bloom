package com.rrajath.grove.testing

import android.net.Uri
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.TreeListing
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.StorageResult
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [FileStore] for tests. Backed by a `MutableMap` of vault-relative
 * path to content, with a monotonic logical clock standing in for mtime so a
 * test can make a file's revision ("mtime:size") change under a ViewModel
 * ([touch]) to exercise stale-file handling.
 *
 * Semantics mirror [com.rrajath.grove.vault.JvmFileStore] and the cases pinned
 * by `JvmFileStoreTest`: [list] returns paths sorted ascending and skips
 * anything under a dot-directory, [read] on a missing file throws, [create] /
 * [rename] refuse to clobber. Directories are not real entities here, so
 * [pruneEmptyDirs] is a no-op (as the interface allows).
 *
 * See internal/test-suite-00-overview.md § Fakes to build.
 */
class FakeFileStore(
    initial: Map<String, String> = emptyMap(),
    /** When true, [write] on a path with no existing entry throws — matching
     *  [com.rrajath.grove.vault.storage.BackendFileStore]'s real contract (its `write` needs an
     *  already-resolved SAF document URI; only [create] makes one). Default false
     *  keeps every existing caller's permissive-write assumption unchanged. */
    private val strictWrite: Boolean = false,
) : FileStore {

    private data class Node(val content: String, val modified: Long)

    private val files = HashMap<String, Node>()
    private val mutex = Mutex()
    private var clock = 0L
    private val injected = java.util.concurrent.ConcurrentHashMap<StorageOp, StorageError>()

    /**
     * Makes every [op] return [error] until cleared with `null`, as a provider
     * that refuses it would. Lets a ViewModel test drive each result kind.
     */
    fun failWith(op: StorageOp, error: StorageError?) {
        if (error == null) injected.remove(op) else injected[op] = error
    }

    init {
        initial.forEach { (name, content) -> files[normalize(name)] = Node(content, tick()) }
    }

    private fun tick(): Long = ++clock

    private fun normalize(name: String): String {
        require(name.isNotBlank()) { "Empty vault path" }
        require(name.split('/').none { it == ".." }) { "Vault path must not contain '..': $name" }
        return name.trim('/')
    }

    private fun underDotDir(name: String): Boolean =
        name.split('/').dropLast(1).any { it.startsWith(".") }

    override suspend fun list(): List<FileEntry> = mutex.withLock {
        files.entries
            .filterNot { underDotDir(it.key) }
            .map { (name, node) -> FileEntry(name, node.modified, node.content.length.toLong()) }
            .sortedBy { it.name }
    }

    /** Always complete: an in-memory store never has a folder still loading. */
    override suspend fun listTree(): TreeListing = TreeListing(list())

    /** The attachment (raw) side isn't modelled here, like [listDirRaw]. */
    override suspend fun isDirEmptyRaw(dir: String): Boolean =
        throw UnsupportedOperationException("This FileStore doesn't support attachment file access")

    override suspend fun stat(name: String): FileEntry? = mutex.withLock {
        val key = normalize(name)
        files[key]?.let { FileEntry(key, it.modified, it.content.length.toLong()) }
    }

    override suspend fun read(name: String): String = mutex.withLock {
        files[normalize(name)]?.content ?: throw FileNotFoundException(name)
    }

    override suspend fun write(name: String, content: String, ifMatch: String?): StorageResult<String> = mutex.withLock {
        injected[StorageOp.Write]?.let { return@withLock it }
        val key = normalize(name)
        if (strictWrite && !files.containsKey(key)) throw FileNotFoundException(name)
        if (ifMatch != null) {
            val current = files[key] ?: return@withLock StorageError.NotFound
            val revision = "${current.modified}:${current.content.length}"
            if (revision != ifMatch) return@withLock StorageError.Conflict(revision)
        }
        val node = Node(content, tick())
        files[key] = node
        StorageResult.Ok("${node.modified}:${node.content.length}")
    }

    override suspend fun create(name: String): StorageResult<Unit> = mutex.withLock {
        injected[StorageOp.Create]?.let { return@withLock it }
        val key = normalize(name)
        if (files.containsKey(key)) return@withLock StorageError.NameTaken
        files[key] = Node("", tick())
        StorageResult.Ok(Unit)
    }

    override suspend fun rename(oldName: String, newName: String): StorageResult<Unit> = mutex.withLock {
        injected[StorageOp.Rename]?.let { return@withLock it }
        val from = normalize(oldName)
        val to = normalize(newName)
        val node = files[from] ?: return@withLock StorageError.NotFound
        if (files.containsKey(to)) return@withLock StorageError.NameTaken
        files.remove(from)
        files[to] = node // keep the original mtime, like File.renameTo
        StorageResult.Ok(Unit)
    }

    override suspend fun delete(name: String): StorageResult<Unit> = mutex.withLock {
        injected[StorageOp.Delete]?.let { return@withLock it }
        if (files.remove(normalize(name)) != null) StorageResult.Ok(Unit) else StorageError.NotFound
    }

    override suspend fun exists(name: String): Boolean = mutex.withLock {
        files.containsKey(normalize(name))
    }

    // --- FileStore members that have a default ---
    //
    // Every member with a default body is overridden here on purpose, even where
    // the override only repeats the default. An inherited default compiles to a
    // bridge here that does `invokespecial FileStore.member`. With minSdk 23, D8
    // moves the default body out of FileStore in the app APK, leaving the
    // interface method abstract, but doesn't rewrite this bridge in the
    // instrumented-test APK, so the call throws AbstractMethodError on the
    // emulator (never on the JVM). FakeFileStoreOverridesTest fails when a
    // FileStore member has no explicit override here.

    override suspend fun listDir(dir: String): List<FileEntry> {
        val trimmed = dir.trim('/')
        val prefix = if (trimmed.isEmpty()) "" else "$trimmed/"
        return list().filter { it.name.startsWith(prefix) && '/' !in it.name.removePrefix(prefix) }
    }

    /** Names match exactly here, like [com.rrajath.grove.vault.JvmFileStore]. */
    override suspend fun canonicalName(name: String): String = name

    /** Directories aren't real entities in this fake. */
    override suspend fun pruneEmptyDirs(dir: String) {}

    override fun absolutePath(name: String): String? = null

    override suspend fun openInput(name: String): InputStream = unsupported()

    override suspend fun createBinary(name: String): StorageResult<Unit> = StorageError.Unsupported(StorageOp.Create)

    override suspend fun openOutput(name: String): OutputStream = unsupported()

    override suspend fun statRaw(name: String): FileEntry? = unsupported()

    override suspend fun renameRaw(name: String, newLeaf: String): StorageResult<Unit> =
        StorageError.Unsupported(StorageOp.Rename)

    override suspend fun deleteRaw(name: String): StorageResult<Unit> = StorageError.Unsupported(StorageOp.Delete)

    override suspend fun dirExists(dir: String): Boolean = unsupported()

    override suspend fun listDirRaw(dir: String): List<FileEntry> = unsupported()

    override suspend fun listSubdirs(dir: String): List<String> = unsupported()

    override suspend fun deleteDir(dir: String): StorageResult<Unit> = StorageError.Unsupported(StorageOp.Delete)

    override suspend fun moveDir(src: String, dstParent: String): StorageResult<Unit> =
        StorageError.Unsupported(StorageOp.Move)

    override suspend fun contentUri(name: String): Uri? = null

    override fun dropRawSnapshots() {}

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("This FileStore doesn't support attachment file access")

    // --- test helpers ---

    /** Bump [name]'s logical mtime without changing content, simulating an external edit. */
    suspend fun touch(name: String) = mutex.withLock {
        val key = normalize(name)
        val node = files[key] ?: throw FileNotFoundException(name)
        files[key] = node.copy(modified = tick())
    }

    /** The revision string an indexer would compute for [name] ("mtime:size"), or null if absent. */
    suspend fun revisionOf(name: String): String? = mutex.withLock {
        files[normalize(name)]?.let { "${it.modified}:${it.content.length}" }
    }

    /** Immutable snapshot of every file's content, for assertions. */
    suspend fun snapshot(): Map<String, String> = mutex.withLock {
        files.mapValues { it.value.content }.toSortedMap()
    }
}
