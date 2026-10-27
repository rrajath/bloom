package com.rrajath.grove.org

/**
 * A parsed org link target: the raw string between `[[ ]]` (or a bare URL),
 * classified into what the app should do with it. Parsing is pure and
 * JVM-testable; resolving a target against the vault/index lives in the
 * DocumentViewModel.
 */
sealed interface OrgLinkTarget {
    /** `[[id:UUID]]` — a heading anywhere in the vault, keyed by its `:ID:`. */
    data class Id(val id: String) : OrgLinkTarget

    /** `[[#custom-id]]` — a heading in the current file, keyed by `:CUSTOM_ID:`. */
    data class CustomId(val customId: String) : OrgLinkTarget

    /** `[[*Heading Title]]` — a headline in the current file, matched by title. */
    data class Heading(val text: String) : OrgLinkTarget

    /** `[[file:foo.org]]` / `[[./foo.org]]` / `[[foo.org]]` — a whole file (→ its outline). */
    data class File(val path: String) : OrgLinkTarget

    /** `[[file:foo.org::*Heading]]` — a headline in another file, matched by title. */
    data class FileHeading(val path: String, val text: String) : OrgLinkTarget

    /** `[[file:foo.org::#custom-id]]` — a `:CUSTOM_ID:` in another file. */
    data class FileCustomId(val path: String, val customId: String) : OrgLinkTarget

    /**
     * `[[denote:20240101T120000]]` — a Denote note anywhere in the vault, matched
     * by the identifier in its file name (→ its outline).
     */
    data class Denote(val identifier: String) : OrgLinkTarget

    /** `[[denote:20240101T120000::*Heading]]` — a headline in a Denote note, matched by title. */
    data class DenoteHeading(val identifier: String, val text: String) : OrgLinkTarget

    /** `[[denote:20240101T120000::#custom-id]]` — a `:CUSTOM_ID:` in a Denote note. */
    data class DenoteCustomId(val identifier: String, val customId: String) : OrgLinkTarget

    /** `http(s)://…`, `mailto:`, `tel:`, … — hand off to the OS. */
    data class External(val uri: String) : OrgLinkTarget

    /** `[[Some text]]` with no scheme and not path-shaped — org "fuzzy" search. */
    data class Fuzzy(val text: String) : OrgLinkTarget
}

object OrgLinkParser {

    /** Schemes org (and this app) resolve by handing the URI to the OS. */
    private val EXTERNAL_SCHEME =
        Regex("""^(https?|mailto|tel|sms|geo|news|ftp|ftps|doi|magnet|irc):""", RegexOption.IGNORE_CASE)

    fun parse(rawTarget: String, currentFile: String): OrgLinkTarget {
        val target = rawTarget.trim()

        when {
            target.startsWith("id:") ->
                return OrgLinkTarget.Id(target.removePrefix("id:").trim())
            target.startsWith("denote:") ->
                return parseDenote(target.removePrefix("denote:"))
            EXTERNAL_SCHEME.containsMatchIn(target) ->
                return OrgLinkTarget.External(target)
            target.startsWith("#") ->
                return OrgLinkTarget.CustomId(target.removePrefix("#").trim())
            target.startsWith("*") ->
                return OrgLinkTarget.Heading(target.trimStart('*').trim())
        }

        // Split an optional file part from an optional `::` search option.
        val filePart: String?
        val search: String?
        if (target.startsWith("file:")) {
            val rest = target.removePrefix("file:")
            val sep = rest.indexOf("::")
            filePart = if (sep >= 0) rest.substring(0, sep) else rest
            search = if (sep >= 0) rest.substring(sep + 2) else null
        } else {
            val sep = target.indexOf("::")
            val head = if (sep >= 0) target.substring(0, sep) else target
            if (looksLikePath(head)) {
                filePart = head
                search = if (sep >= 0) target.substring(sep + 2) else null
            } else {
                filePart = null
                search = null
            }
        }

        if (filePart != null) {
            val path = resolvePath(filePart.trim(), currentFile)
            val s = search?.trim()
            return when {
                s.isNullOrEmpty() -> OrgLinkTarget.File(path)
                s.startsWith("#") -> OrgLinkTarget.FileCustomId(path, s.removePrefix("#").trim())
                s.startsWith("*") -> OrgLinkTarget.FileHeading(path, s.trimStart('*').trim())
                else -> OrgLinkTarget.FileHeading(path, s)
            }
        }

        return OrgLinkTarget.Fuzzy(target)
    }

    /**
     * The part after `denote:` is the note's identifier, optionally followed by
     * a `::` search option, classified the same way as for a `file:` link.
     */
    private fun parseDenote(rest: String): OrgLinkTarget {
        val sep = rest.indexOf("::")
        val identifier = (if (sep >= 0) rest.substring(0, sep) else rest).trim()
        val s = if (sep >= 0) rest.substring(sep + 2).trim() else null
        return when {
            s.isNullOrEmpty() -> OrgLinkTarget.Denote(identifier)
            s.startsWith("#") -> OrgLinkTarget.DenoteCustomId(identifier, s.removePrefix("#").trim())
            s.startsWith("*") -> OrgLinkTarget.DenoteHeading(identifier, s.trimStart('*').trim())
            else -> OrgLinkTarget.DenoteHeading(identifier, s)
        }
    }

    private fun looksLikePath(head: String): Boolean =
        head.endsWith(".org", ignoreCase = true) ||
            head.startsWith("./") ||
            head.startsWith("../") ||
            head.startsWith("/") ||
            head.contains('/')

    /**
     * Normalize a link's file part to a vault-relative path (`.org` suffixed).
     * Relative paths are resolved against the linking file's directory, the way
     * Emacs org resolves `[[file:…]]`; a leading `/` is treated as vault-root.
     */
    private fun resolvePath(path: String, currentFile: String): String {
        val rooted = path.startsWith("/")
        val segments = ArrayDeque<String>()
        if (!rooted) {
            currentFile.substringBeforeLast('/', "")
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach { segments.addLast(it) }
        }
        for (seg in path.removePrefix("/").split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(seg)
            }
        }
        val joined = segments.joinToString("/")
        return if (joined.endsWith(".org", ignoreCase = true)) joined else "$joined.org"
    }
}
