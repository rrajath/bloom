package com.rrajath.grove.attach

/**
 * Which vault directories are attach roots, for the vault-traversal shard
 * skip: org-attach shards (`3F/`, `202611/`, `__/`) directly inside one are
 * never walked by sync, the index or the notebook tree.
 *
 * A `data` folder always counts: reads fall back to `data/` (as
 * `org-attach-dir-from-id` does), so attachments made before the folder
 * changed stay hidden. The configured folder ([AttachConfig.idDir], relative
 * to each note's folder) counts wherever a folder's path ends with it.
 */
class AttachSkipRoots private constructor(
    /** The configured folder as a trailing path (`attach`, `media/files`); null when it's `data`. */
    private val suffix: String?,
) {
    /** Whether [parentPath] (vault-relative, "" for the root) is an attach root. */
    fun isAttachRoot(parentPath: String): Boolean {
        val p = parentPath.trim('/')
        return endsWith(p, DATA) || (suffix != null && endsWith(p, suffix))
    }

    private fun endsWith(path: String, tail: String) = path == tail || path.endsWith("/$tail")

    companion object {
        private const val DATA = AttachDirResolver.DEFAULT_ID_DIR

        /** Only `data` folders: Emacs's default, and the store default in tests. */
        val DataOnly = AttachSkipRoots(null)

        fun of(config: AttachConfig): AttachSkipRoots {
            val tail = normalizeVaultPath(config.idDir.trim())
            return AttachSkipRoots(tail?.takeIf { it.isNotEmpty() && it != DATA })
        }
    }
}
