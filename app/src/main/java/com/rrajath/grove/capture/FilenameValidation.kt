package com.rrajath.grove.capture

// Characters FAT/exFAT (the common case for the SAF tree providers this app
// targets) reject outright, plus the Windows path separator and C0 control
// characters. Forward slash is allowed as a vault-relative path separator and
// is validated segment-by-segment below, not by this set.
private val ILLEGAL_SEGMENT_CHARS = Regex("""[\\:*?"<>|\p{Cntrl}]""")

/** Validates a capture template's target-file path (PRD §7.4). */
object FilenameValidation {

    /**
     * `null` when [name] is a valid `.org` target — a bare filename or a
     * vault-relative path with `/`-separated segments (the notebook picker
     * offers notebooks in subfolders); otherwise a user-facing reason.
     */
    fun errorFor(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Enter a filename"
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return "Not a valid path"

        val segments = trimmed.split("/")
        segments.forEachIndexed { i, segment ->
            when {
                segment.isEmpty() -> return "Not a valid path"
                segment == "." || segment == ".." -> return "Not a valid path"
                ILLEGAL_SEGMENT_CHARS.containsMatchIn(segment) ->
                    return "Can't contain \\ : * ? \" < > |"
                i == segments.lastIndex && segment == ".org" -> return "Enter a filename"
            }
        }

        if (!trimmed.endsWith(".org")) return "Filename must end in .org"
        return null
    }

    /**
     * Validates a name typed into the "New notebook" / "Rename notebook"
     * dialogs, where the `.org` extension is optional and appended
     * automatically. `null` when [name] resolves to a valid vault-relative
     * `.org` path (a bare name, or `/`-separated segments to create the
     * notebook inside nested folders); otherwise a user-facing reason.
     *
     * Same segment rules as [errorFor] minus the "must end in .org"
     * requirement: a bare `foo` is fine, `foo/` is not.
     */
    fun errorForNewNotebook(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Enter a name"
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return "Not a valid path"

        val stem = trimmed.removeSuffix(".org")
        if (stem.isEmpty()) return "Enter a name"

        stem.split("/").forEach { segment ->
            when {
                segment.isEmpty() -> return "Not a valid path"
                segment == "." || segment == ".." -> return "Not a valid path"
                ILLEGAL_SEGMENT_CHARS.containsMatchIn(segment) ->
                    return "Can't contain \\ : * ? \" < > |"
            }
        }
        return null
    }

    /**
     * Validates a name typed into the "Rename notebook" dialog. Rename keeps the
     * notebook in its folder, so any `/` is rejected (folders change via Move);
     * everything else follows [errorForNewNotebook].
     */
    fun errorForRenameNotebook(name: String): String? {
        if (name.contains('/')) return "Names can't contain /. Use Move to change folders."
        return errorForNewNotebook(name)
    }

    /**
     * Validates a Roam template's target directory. `null` when [path] is a valid
     * vault-relative directory; a blank [path] means the vault root and is valid.
     * Same segment rules as [errorForNewNotebook] minus `.org` handling — a
     * directory has no file extension to worry about.
     */
    fun errorForDirectory(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return "Not a valid path"

        trimmed.split("/").forEach { segment ->
            when {
                segment.isEmpty() -> return "Not a valid path"
                segment == "." || segment == ".." -> return "Not a valid path"
                ILLEGAL_SEGMENT_CHARS.containsMatchIn(segment) ->
                    return "Can't contain \\ : * ? \" < > |"
            }
        }
        return null
    }
}
