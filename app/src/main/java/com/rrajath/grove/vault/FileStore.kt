package com.rrajath.grove.vault

import android.net.Uri
import com.rrajath.grove.attach.AttachSkipRoots
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.storage.VaultCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

/** Metadata for one file in the vault. [name] is a vault-relative path. */
data class FileEntry(
    val name: String,
    val lastModified: Long,
    val size: Long,
)

/**
 * A whole-vault listing and how far it can be trusted. [incompleteDirs] holds
 * the vault-relative directories ("" is the root) the provider couldn't finish
 * listing (still loading past the deadline, or an error): [entries] may be
 * missing files under them and under their unreached subfolders, so a file's
 * absence there proves nothing. Never delete or forget based on absence under
 * one of them.
 */
data class TreeListing(
    val entries: List<FileEntry>,
    val incompleteDirs: Set<String> = emptySet(),
    /** Why the root couldn't be listed completely; null when it could. */
    val rootProblem: String? = null,
) {
    val isComplete: Boolean get() = incompleteDirs.isEmpty()

    /** True when [path] sits in, or under, a directory that wasn't fully listed. */
    fun isUnderIncompleteDir(path: String): Boolean =
        incompleteDirs.any { it.isEmpty() || path.startsWith("$it/") }
}

/**
 * Abstraction over the sync directory. The app uses [com.rrajath.grove.vault.storage.BackendFileStore]
 * over a storage provider; unit tests use [JvmFileStore].
 *
 * Mutations return a [StorageResult]: [StorageResult.Ok], or the
 * [StorageError] saying why not (name taken, not found, unsupported by the
 * provider, provider error...), never a bare `false`.
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
    /**
     * What the provider lets other apps do, per listed file and folder, as of
     * the last listing. Stores with no provider (tests, plain files) allow
     * everything.
     */
    val capabilities: StateFlow<VaultCapabilities> get() = ALL_CAPABILITIES

    /** All files in the vault, recursively (not filtered to .org; callers apply ignore rules). */
    suspend fun list(): List<FileEntry>

    /**
     * [list] plus which directories couldn't be fully listed. Anything that
     * acts on a file's absence (sync dropping a notebook, pruning folders) must
     * use this and honour [TreeListing.incompleteDirs]. The default suits
     * stores whose listing is always complete.
     */
    suspend fun listTree(): TreeListing = TreeListing(list())

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

    /**
     * [name] as the store actually spells it. Differs from [name] only on a
     * store that matches names case-insensitively (Android shared storage, SD
     * cards), where asking for `inbox.org` finds an existing `Inbox.org`; [stat]
     * then reports that spelling too. [name] itself when nothing matches.
     */
    suspend fun canonicalName(name: String): String = name

    suspend fun read(name: String): String

    /**
     * Write full content, truncating; returns the file's new revision.
     * Implementations MUST truncate properly (SAF streams need mode "wt" or
     * shorter content leaves trailing bytes). When [ifMatch] is set and the
     * file's current revision (see [com.rrajath.grove.vault.storage.DocEntry.revision])
     * differs, nothing is written and the result is [StorageError.Conflict].
     * [StorageError.NotFound] when [name] doesn't exist (see [create]).
     */
    suspend fun write(name: String, content: String, ifMatch: String? = null): StorageResult<String>

    /**
     * Create an empty file, creating any missing parent directories;
     * [StorageError.NameTaken] if it already exists.
     */
    suspend fun create(name: String): StorageResult<Unit>

    /**
     * [create] the file with [content] in it, in one step. On a provider whose
     * create only reserves the name ([com.rrajath.grove.vault.storage.ProviderProfile.createIsLazy]),
     * this is one upload instead of an empty one and then the real one; on the
     * others it closes the window where the new file sits empty.
     */
    suspend fun createWithContent(name: String, content: String): StorageResult<Unit> {
        val created = create(name)
        if (created is StorageError) return created
        return when (val written = write(name, content)) {
            is StorageResult.Ok -> StorageResult.Ok(Unit)
            is StorageError -> written
        }
    }

    /**
     * Rename or move a file. [newName] may be in a different directory, in which
     * case intermediate directories are created. [StorageError.NameTaken] if the
     * target already exists, [StorageError.NotFound] if the source is missing.
     */
    suspend fun rename(oldName: String, newName: String): StorageResult<Unit>

    suspend fun delete(name: String): StorageResult<Unit>

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
     * [StorageError.NameTaken] if anything already exists there.
     */
    suspend fun createBinary(name: String): StorageResult<Unit> = StorageError.Unsupported(StorageOp.Create)

    /**
     * True when a new file only exists once something is written to it, so
     * nobody can see a half-written one: callers may write it under its final
     * name instead of staging through a `.part` file and a rename (which such
     * a provider may not support).
     */
    val createsLazily: Boolean get() = capabilities.value.profile.createIsLazy

    /** Opens the existing file [name] for writing, truncating it (mode "wt"). */
    suspend fun openOutput(name: String): OutputStream = unsupported()

    /** Metadata for the file [name], or null when there is no file there. */
    suspend fun statRaw(name: String): FileEntry? = unsupported()

    /**
     * Renames the file [name] within its directory to [newLeaf].
     * [StorageError.NotFound] if the source is missing, [StorageError.NameTaken]
     * if [newLeaf] is taken.
     */
    suspend fun renameRaw(name: String, newLeaf: String): StorageResult<Unit> =
        StorageError.Unsupported(StorageOp.Rename)

    /** Deletes the file [name]. [StorageError.NotFound] if it wasn't there. */
    suspend fun deleteRaw(name: String): StorageResult<Unit> = StorageError.Unsupported(StorageOp.Delete)

    /** True when [dir] is an existing directory ("" is the vault root). */
    suspend fun dirExists(dir: String): Boolean = unsupported()

    /** Files directly inside [dir], every name included. Empty when [dir] is missing. */
    suspend fun listDirRaw(dir: String): List<FileEntry> = unsupported()

    /** Names (leaves, not paths) of the directories directly inside [dir]. */
    suspend fun listSubdirs(dir: String): List<String> = unsupported()

    /**
     * True when [dir] is known to hold nothing. Callers delete a directory on
     * this answer, so a store whose listing can be unfinished (a provider still
     * loading) overrides it to say false then, where an empty [listDirRaw] would
     * lie. The default suits stores that always list completely.
     */
    suspend fun isDirEmptyRaw(dir: String): Boolean = listDirRaw(dir).isEmpty() && listSubdirs(dir).isEmpty()

    /** Deletes [dir] and everything in it. Never the root. [StorageError.NotFound] if missing. */
    suspend fun deleteDir(dir: String): StorageResult<Unit> = StorageError.Unsupported(StorageOp.Delete)

    /**
     * Moves [src] (keeping its leaf name) into [dstParent], creating
     * [dstParent] when missing. [StorageError.NotFound] when the source is
     * missing, [StorageError.NameTaken] when the destination already exists
     * (merging is [com.rrajath.grove.attach.AttachmentFiles]' job). Uses a
     * provider move when possible, else copy then delete.
     */
    suspend fun moveDir(src: String, dstParent: String): StorageResult<Unit> =
        StorageError.Unsupported(StorageOp.Move)

    /**
     * A content URI for the file [name] to hand to intents and image loaders;
     * null when there's none. The URI is one another app can read, and may be
     * slow to produce (a network backend downloads first): suspend, and never
     * call it on the main thread or in a tight loop.
     */
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
 * and org-attach shards inside an attach root ([skipRoots]; null skips no
 * shards), so sync polls, the notebook tree and the index never walk
 * attachment folders. [parentPath] is the vault-relative path of the
 * directory holding [name] ("" for the root).
 */
internal fun isSkippedVaultDir(
    name: String,
    parentPath: String,
    skipRoots: AttachSkipRoots?,
): Boolean =
    name.startsWith(".") ||
        (skipRoots != null && isAttachShard(name) && skipRoots.isAttachRoot(parentPath))

/**
 * A first-level org-attach folder under the attach root: a 2-char uuid shard
 * (`3F/`), a 6-digit ts shard (`202611/`), or the fallback root (`__/`).
 * A user folder named like one (`data/ab/`) is hidden too; accepted trade-off.
 */
internal fun isAttachShard(name: String): Boolean =
    name.length == 2 ||
        (name.length == 6 && name.all { it in '0'..'9' }) ||
        name == "__"

/** [FileStore.capabilities] for stores that allow everything. */
private val ALL_CAPABILITIES: StateFlow<VaultCapabilities> = MutableStateFlow(VaultCapabilities.UNKNOWN).asStateFlow()
