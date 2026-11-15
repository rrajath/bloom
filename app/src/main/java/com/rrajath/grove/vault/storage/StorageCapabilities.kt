package com.rrajath.grove.vault.storage

import android.provider.DocumentsContract.Document

/**
 * What a provider lets other apps do to one document, from its
 * `COLUMN_FLAGS`. A provider that doesn't report flags gets [ALL]: Bloom then
 * tries the operation and maps a refusal to [StorageError.Unsupported].
 *
 * Flags are per document: Seafile, for one, gives its repos different flags
 * than the files in them. The vault root's set decides UI defaults; the target
 * item's set decides an operation.
 */
data class StorageCapabilities(
    val delete: Boolean = true,
    val rename: Boolean = true,
    val move: Boolean = true,
    val copy: Boolean = true,
    /** The file's content can be replaced (files only; true for folders). */
    val write: Boolean = true,
    /** Files and folders can be created inside it (folders only; false for files). */
    val createChildren: Boolean = true,
) {
    /** Whether [op] is allowed on this document. */
    fun supports(op: StorageOp): Boolean = when (op) {
        StorageOp.Create, StorageOp.CreateFolder -> createChildren
        StorageOp.Rename -> rename
        StorageOp.Move -> move
        StorageOp.Delete -> delete
        StorageOp.Write -> write
        StorageOp.Read -> true
    }

    /**
     * Whether the document can end up in another folder: natively, or as a
     * copy followed by deleting the original. Without either, a move would
     * leave the file in two places.
     */
    val canRelocate: Boolean get() = move || delete

    /** Both sets allow it. */
    infix fun and(other: StorageCapabilities) = StorageCapabilities(
        delete = delete && other.delete,
        rename = rename && other.rename,
        move = move && other.move,
        copy = copy && other.copy,
        write = write && other.write,
        createChildren = createChildren && other.createChildren,
    )

    companion object {
        /** Everything allowed: what a document with no reported flags is assumed to support. */
        val ALL = StorageCapabilities()

        /** Decodes a document's `COLUMN_FLAGS`; null (not reported) is [ALL]. */
        fun fromFlags(flags: Int?, isDir: Boolean): StorageCapabilities {
            if (flags == null) return ALL
            fun has(flag: Int) = flags and flag != 0
            return StorageCapabilities(
                delete = has(Document.FLAG_SUPPORTS_DELETE),
                rename = has(Document.FLAG_SUPPORTS_RENAME),
                move = has(Document.FLAG_SUPPORTS_MOVE),
                copy = has(Document.FLAG_SUPPORTS_COPY),
                write = isDir || has(Document.FLAG_SUPPORTS_WRITE),
                createChildren = isDir && has(Document.FLAG_DIR_SUPPORTS_CREATE),
            )
        }
    }
}

/**
 * What the vault's provider allows, as last seen: the root's flags, every
 * listed file's and folder's own (vault-relative paths), and the provider's
 * display name for messages. Published by [com.rrajath.grove.vault.FileStore.capabilities].
 */
data class VaultCapabilities(
    /** The vault's provider; [ProviderProfiles.DEFAULT] until a store reports one. */
    val profile: ProviderProfile = ProviderProfiles.DEFAULT,
    val root: StorageCapabilities = StorageCapabilities.ALL,
    val files: Map<String, StorageCapabilities> = emptyMap(),
    val dirs: Map<String, StorageCapabilities> = emptyMap(),
) {
    /** The file at [path]'s capabilities; [StorageCapabilities.ALL] when it hasn't been listed. */
    fun ofFile(path: String): StorageCapabilities = files[path.trim('/')] ?: StorageCapabilities.ALL

    /** The folder at [path]'s own capabilities (the root for ""); ALL when unlisted. */
    fun ofDir(path: String): StorageCapabilities {
        val p = path.trim('/')
        return if (p.isEmpty()) root else dirs[p] ?: StorageCapabilities.ALL
    }

    /** What every file under [dir] (recursively) allows: a folder action is only as capable as its least capable file. */
    fun ofFilesUnder(dir: String): StorageCapabilities {
        val p = dir.trim('/')
        val prefix = if (p.isEmpty()) "" else "$p/"
        return files.asSequence()
            .filter { it.key.startsWith(prefix) }
            .fold(StorageCapabilities.ALL) { acc, e -> acc and e.value }
    }

    /** What every listed file allows, for a one-line summary; the root's set when no file is listed. */
    val allFiles: StorageCapabilities
        get() = if (files.isEmpty()) root else files.values.fold(StorageCapabilities.ALL) { acc, c -> acc and c }

    companion object {
        val UNKNOWN = VaultCapabilities()
    }
}
