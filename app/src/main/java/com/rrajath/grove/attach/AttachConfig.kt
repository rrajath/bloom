package com.rrajath.grove.attach

/**
 * How Bloom maps an org entry to its attachment directory. Mirrors the
 * org-attach variables Emacs reads; the defaults match Emacs's. The app's
 * current instance comes from [AttachConfigSource] (Settings § Attachments);
 * every resolver, lookup and lifecycle takes one explicitly
 * (`NoDefaultAttachConfigTest` keeps [Default] out of app code), so a settings
 * change reaches all of them.
 *
 * `:DIR:` / `:ATTACH_DIR:` are always honored and always inherited, and `:ID:`
 * never is (see [resolveOwnerAtLine]); neither is configurable.
 */
data class AttachConfig(
    /** `org-attach-id-dir`: relative to the org file's directory, with one trailing `/`. */
    val idDir: String = "data/",
    /** `org-attach-id-to-path-function-list`, in order. */
    val idPathFormats: List<IdPathFormat> = listOf(IdPathFormat.Uuid, IdPathFormat.Ts, IdPathFormat.Fallback),
) {
    /**
     * The vault-relative attach root for [orgFile]'s `:ID:` dirs (`Notes/data`
     * for `Notes/a.org` under the default; `data` at the vault root).
     */
    fun rootFor(orgFile: String): String =
        normalizeVaultPath(joinPath(dirOf(orgFile), idDir)) ?: idDir.trim('/')

    companion object {
        val Default = AttachConfig()

        /** The config for Settings § Attachments' attachment folder (already validated). */
        fun forFolder(folder: String): AttachConfig = AttachConfig(idDir = folder)
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
