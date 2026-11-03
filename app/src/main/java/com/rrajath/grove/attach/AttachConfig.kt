package com.rrajath.grove.attach

/**
 * How Bloom maps an org entry to its attachment directory. Mirrors the
 * org-attach variables Emacs reads, at their defaults. Every call site takes an
 * instance (defaulting to [Default]) so a future settings screen only has to
 * supply a different one.
 */
data class AttachConfig(
    /**
     * `org-attach-id-dir`: relative to the org file's directory, or vault-rooted
     * when it starts with `/`.
     */
    val idDir: String = "data/",
    /** `org-attach-id-to-path-function-list`, in order. */
    val idPathFormats: List<IdPathFormat> = listOf(IdPathFormat.Uuid, IdPathFormat.Ts, IdPathFormat.Fallback),
    /** Honor `:DIR:` (and the legacy `:ATTACH_DIR:`) properties. */
    val honorDirProperty: Boolean = true,
    /** `org-attach-use-inheritance`: look up `:DIR:` / `:ID:` on ancestors too. */
    val useInheritance: Boolean = false,
) {
    /** Leaf name of [idDir] (`data` for the default), used by the vault-traversal shard skip. */
    val idDirLeaf: String get() = idDir.trimEnd('/').substringAfterLast('/')

    companion object {
        val Default = AttachConfig()
    }
}

/** One of org-attach's ID-to-path functions (`org-attach-id-*-folder-format`). */
sealed interface IdPathFormat {
    /** The folder path for [id], or null when this format doesn't apply to it. */
    fun path(id: String): String?

    /** `org-attach-id-uuid-folder-format`: `id[0..2]/id[2..]` when longer than 2. */
    data object Uuid : IdPathFormat {
        override fun path(id: String) = if (id.length > 2) "${id.take(2)}/${id.drop(2)}" else null
    }

    /** `org-attach-id-ts-folder-format`: `id[0..6]/id[6..]` when longer than 6. */
    data object Ts : IdPathFormat {
        override fun path(id: String) = if (id.length > 6) "${id.take(6)}/${id.drop(6)}" else null
    }

    /** `org-attach-id-fallback-folder-format`: `__/<first char>/<id>`, always applies. */
    data object Fallback : IdPathFormat {
        override fun path(id: String) = if (id.isEmpty()) null else "__/${id.take(1)}/$id"
    }
}
