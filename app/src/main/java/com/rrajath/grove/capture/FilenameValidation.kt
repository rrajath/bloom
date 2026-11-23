package com.rrajath.grove.capture

import com.rrajath.grove.R
import com.rrajath.grove.ui.util.UiText

// Characters FAT/exFAT (the common case for the SAF tree providers this app
// targets) reject outright, plus the Windows path separator and C0 control
// characters. Forward slash is allowed as a vault-relative path separator and
// is validated segment-by-segment below, not by this set.
private val ILLEGAL_SEGMENT_CHARS = Regex("""[\\:*?"<>|\p{Cntrl}]""")

/** Validates a capture template's target-file path (PRD §7.4). Errors are user-facing [UiText]. */
object FilenameValidation {

    internal val ENTER_FILENAME = UiText.res(R.string.filename_error_enter_filename)
    internal val ENTER_NAME = UiText.res(R.string.filename_error_enter_name)
    internal val INVALID_PATH = UiText.res(R.string.filename_error_invalid_path)
    internal val ILLEGAL_CHARS = UiText.res(R.string.filename_error_illegal_chars)
    internal val MUST_END_ORG = UiText.res(R.string.filename_error_must_end_org)
    internal val RENAME_NO_SLASH = UiText.res(R.string.filename_error_rename_no_slash)

    /**
     * `null` when [name] is a valid `.org` target — a bare filename or a
     * vault-relative path with `/`-separated segments (the notebook picker
     * offers notebooks in subfolders); otherwise a user-facing reason.
     */
    fun errorFor(name: String): UiText? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ENTER_FILENAME
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return INVALID_PATH

        val segments = trimmed.split("/")
        segments.forEachIndexed { i, segment ->
            when {
                segment.isEmpty() -> return INVALID_PATH
                segment == "." || segment == ".." -> return INVALID_PATH
                ILLEGAL_SEGMENT_CHARS.containsMatchIn(segment) ->
                    return ILLEGAL_CHARS
                i == segments.lastIndex && segment == ".org" -> return ENTER_FILENAME
            }
        }

        if (!trimmed.endsWith(".org")) return MUST_END_ORG
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
    fun errorForNewNotebook(name: String): UiText? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ENTER_NAME
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return INVALID_PATH

        val stem = trimmed.removeSuffix(".org")
        if (stem.isEmpty()) return ENTER_NAME

        stem.split("/").forEach { segment ->
            when {
                segment.isEmpty() -> return INVALID_PATH
                segment == "." || segment == ".." -> return INVALID_PATH
                ILLEGAL_SEGMENT_CHARS.containsMatchIn(segment) ->
                    return ILLEGAL_CHARS
            }
        }
        return null
    }

    /**
     * Validates a name typed into the "Rename notebook" dialog. Rename keeps the
     * notebook in its folder, so any `/` is rejected (folders change via Move);
     * everything else follows [errorForNewNotebook].
     */
    fun errorForRenameNotebook(name: String): UiText? {
        if (name.contains('/')) return RENAME_NO_SLASH
        return errorForNewNotebook(name)
    }

    /**
     * Validates a Roam template's target directory. `null` when [path] is a valid
     * vault-relative directory; a blank [path] means the vault root and is valid.
     * Same segment rules as [errorForNewNotebook] minus `.org` handling — a
     * directory has no file extension to worry about.
     */
    fun errorForDirectory(path: String): UiText? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return INVALID_PATH

        trimmed.split("/").forEach { segment ->
            when {
                segment.isEmpty() -> return INVALID_PATH
                segment == "." || segment == ".." -> return INVALID_PATH
                ILLEGAL_SEGMENT_CHARS.containsMatchIn(segment) ->
                    return ILLEGAL_CHARS
            }
        }
        return null
    }
}
