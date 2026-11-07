package com.rrajath.grove.vault

import android.net.Uri
import com.rrajath.grove.attach.AttachConfig
import java.io.InputStream
import java.io.OutputStream

/** Metadata for one file in the vault. [name] is a vault-relative path. */
data class FileEntry(
    val name: String,
    val lastModified: Long,
    val size: Long,
)

/**
 * Abstraction over the sync directory. The app uses a Storage Access Framework
 * implementation; unit tests use [JvmFileStore].
 *
 * Names are vault-relative paths with `/` separators, e.g. `projects/acme.org`.
 * A file at the root of the vault has a name equal to its bare file name. The
 * vault is a tree: [list] descends into subdirectories, and [create] / [rename]
 * create intermediate directories as needed.
 *
 * Traversal skips dot-directories (`.stversions`, `.stfolder`, `.git`, and any
 * other name starting with `.`); Syncthing's `.stversions` in particular holds
 * `.org` copies that would otherwise surface as phantom notebooks. With
 * attachments on, it also skips org-attach shard folders inside `data/` (see
 * [isAttachShard]).
 *
 * The "raw" members at the bottom (binary streams and directory operations)
 * address attachment files by explicit path: they ignore every skip and
 * ignore rule, and see every file (including `.part` partials). Stores that
 * don't support them keep the throwing defaults.
 */
interface FileStore {
    /** All files in the vault, recursively (not filtered to .org; callers apply ignore rules). */
    suspend fun list(): List<FileEntry>

    /**
     * Files directly inside [dir] (a vault-relative directory, "" for the root),
     * not descending into subdirectories. Names are still vault-relative paths.
     * Empty when [dir] doesn't exist. For callers that care about one folder
     * (Dailies): implementations should answer without walking the whole vault;
     * the default falls back to [list] for stores where that's already cheap.
     */
    suspend fun listDir(dir: String): List<FileEntry> {
        val trimmed = dir.trim('/')
        val prefix = if (trimmed.isEmpty()) "" else "$trimmed/"
        return list().filter { it.name.startsWith(prefix) && '/' !in it.name.removePrefix(prefix) }
    }

    /**
     * Metadata for a single file, or null if it doesn't exist. Implementations
     * should avoid enumerating the whole vault when only one file is needed; the
     * default falls back to [list] for stores where that's already cheap.
     */
    suspend fun stat(name: String): FileEntry? = list().firstOrNull { it.name == name }

    suspend fun read(name: String): String

    /**
     * Write full content, truncating. Implementations MUST truncate properly
     * (SAF streams need mode "wt" or shorter content leaves trailing bytes).
     */
    suspend fun write(name: String, content: String)

    /**
     * Create an empty file, creating any missing parent directories; returns
     * false if it already exists.
     */
    suspend fun create(name: String): Boolean

    /**
     * Rename or move a file. [newName] may be in a different directory, in which
     * case intermediate directories are created. Returns false if the target
     * already exists or the source is missing.
     */
    suspend fun rename(oldName: String, newName: String): Boolean

    suspend fun delete(name: String): Boolean

    suspend fun exists(name: String): Boolean

    /**
     * Remove [dir] (a vault-relative directory) and any now-empty ancestor
     * directories, stopping at the first one that still holds files or
     * subdirectories, and never touching the vault root. Called after a
     * delete or a folder move empties a directory so stale empty folders
     * don't linger (and don't block a later folder rename onto that name).
     * No-op for the root, and for stores where directories aren't real
     * entities.
     */
    suspend fun pruneEmptyDirs(dir: String) {}

    /**
     * The device filesystem path of [name] (vault-relative), or null when the
     * store can't know one (e.g. a SAF provider that isn't local storage).
     */
    fun absolutePath(name: String): String? = null

    // --- Raw access for attachments (no skip/ignore rules) ---

    /** Opens [name] for reading. Throws [java.io.FileNotFoundException] if it isn't a file. */
    suspend fun openInput(name: String): InputStream = unsupported()

    /**
     * Creates an empty file at [name], creating missing parent directories.
     * False if anything already exists there or the provider refused.
     */
    suspend fun createBinary(name: String): Boolean = unsupported()

    /** Opens the existing file [name] for writing, truncating it (mode "wt"). */
    suspend fun openOutput(name: String): OutputStream = unsupported()

    /** Metadata for the file [name], or null when there is no file there. */
    suspend fun statRaw(name: String): FileEntry? = unsupported()

    /**
     * Renames the file [name] within its directory to [newLeaf]. False if the
     * source is missing or [newLeaf] is taken.
     */
    suspend fun renameRaw(name: String, newLeaf: String): Boolean = unsupported()

    /** Deletes the file [name]. False if it wasn't there or couldn't be removed. */
    suspend fun deleteRaw(name: String): Boolean = unsupported()

    /** True when [dir] is an existing directory ("" is the vault root). */
    suspend fun dirExists(dir: String): Boolean = unsupported()

    /** Files directly inside [dir], every name included. Empty when [dir] is missing. */
    suspend fun listDirRaw(dir: String): List<FileEntry> = unsupported()

    /** Names (leaves, not paths) of the directories directly inside [dir]. */
    suspend fun listSubdirs(dir: String): List<String> = unsupported()

    /** Deletes [dir] and everything in it. Never the root. False if missing or refused. */
    suspend fun deleteDir(dir: String): Boolean = unsupported()

    /**
     * Moves [src] (keeping its leaf name) into [dstParent], creating
     * [dstParent] when missing. False when the source is missing or the
     * destination already exists (merging is [com.rrajath.grove.attach.AttachmentFiles]'
     * job). Uses a provider move when possible, else copy then delete.
     */
    suspend fun moveDir(src: String, dstParent: String): Boolean = unsupported()

    /** A content URI for the file [name] to hand to intents and image loaders; null when there's none. */
    suspend fun contentUri(name: String): Uri? = null

    /**
     * Forget short-lived raw lookup snapshots (recent directory listings and
     * just-verified ids), so the next raw lookup asks the provider. Called when
     * files may have changed outside the store: a finished sync, the app
     * returning to the foreground.
     */
    fun dropRawSnapshots() {}
}

private fun unsupported(): Nothing =
    throw UnsupportedOperationException("This FileStore doesn't support attachment file access")

/** Whether vault traversal skips attachment shards by default. */
internal const val SKIP_ATTACH_SHARDS: Boolean = true

/**
 * Directory names never descended into during vault traversal: dot-dirs,
 * and (when [skipAttachShards]) org-attach shards inside the attach root
 * (`data/`), so sync polls, the notebook tree and the index never walk
 * attachment folders. [parentPath] is the vault-relative path of the
 * directory holding [name] ("" for the root).
 */
internal fun isSkippedVaultDir(
    name: String,
    parentPath: String,
    skipAttachShards: Boolean = SKIP_ATTACH_SHARDS,
): Boolean =
    name.startsWith(".") ||
        (skipAttachShards &&
            parentPath.isNotEmpty() &&
            parentPath.substringAfterLast('/') == AttachConfig.Default.idDirLeaf &&
            isAttachShard(name))

/**
 * A first-level org-attach folder under the attach root: a 2-char uuid shard
 * (`3F/`), a 6-digit ts shard (`202611/`), or the fallback root (`__/`).
 * A user folder named like one (`data/ab/`) is hidden too; accepted trade-off.
 */
internal fun isAttachShard(name: String): Boolean =
    name.length == 2 ||
        (name.length == 6 && name.all { it in '0'..'9' }) ||
        name == "__"
