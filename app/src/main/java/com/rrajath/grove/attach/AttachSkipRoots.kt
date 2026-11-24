package com.rrajath.grove.attach

/**
 * Which vault directories are attach roots, for the vault-traversal shard
 * skip: org-attach shards (`3F/`, `202611/`, `__/`) directly inside one are
 * never walked by sync, the index or the notebook tree.
 *
 * A `data` folder always counts: reads fall back to `data/` next to each note
 * (as `org-attach-dir-from-id` does), so attachments made before the folder
 * changed stay hidden. The configured folder ([AttachConfig.idDir]) counts
 * wherever a folder's path ends with it when it's relative to each note's
 * folder, and only at the vault root when it's [AttachConfig.isVaultRoot].
 */
class AttachSkipRoots private constructor(
    /** The configured folder as a vault-relative path (`attach`, `media/files`); null when it's `data`. */
    private val folder: String?,
    /** True when [folder] only counts at the vault root, not as a trailing path. */
    private val vaultRoot: Boolean,
) {
    /** Whether [parentPath] (vault-relative, "" for the root) is an attach root. */
    fun isAttachRoot(parentPath: String): Boolean {
        val p = parentPath.trim('/')
        if (endsWith(p, DATA)) return true
        if (folder == null) return false
        return if (vaultRoot) p == folder else endsWith(p, folder)
    }

    private fun endsWith(path: String, tail: String) = path == tail || path.endsWith("/$tail")

    companion object {
        private const val DATA = AttachDirResolver.DEFAULT_ID_DIR

        /** Only `data` folders: Emacs's default, and the store default in tests. */
        val DataOnly = AttachSkipRoots(null, vaultRoot = false)

        fun of(config: AttachConfig): AttachSkipRoots {
            val tail = normalizeVaultPath(config.idDir.trim())
            return AttachSkipRoots(tail?.takeIf { it.isNotEmpty() && it != DATA }, config.isVaultRoot)
        }
    }
}
