package com.rrajath.grove.org

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** A parsed `:ARCHIVE:`/`#+ARCHIVE:` value: destination file plus an optional nested heading path. */
data class ArchiveTarget(val fileName: String, val headingPath: ImmutableList<String>) {
    /** Copies a plain list, so a caller can never mutate a value Compose treats as stable. */
    constructor(fileName: String, headingPath: List<String>) : this(fileName, headingPath.toImmutableList())
}

/**
 * Parses and resolves org's `ARCHIVE` property/keyword, and locates (or
 * creates) the heading path it names inside a destination document.
 */
object ArchiveLocation {

    /**
     * Parses `<relative-path>[::* <heading>[/<heading>...]]` (a `./` prefix on
     * the path is stripped; nested heading segments are `/`-separated). Returns
     * null for a blank value.
     *
     * With [sourceFile] (the vault-relative path of the file being archived
     * from), `%s` in the path expands to it, as in Emacs (`%s_archive` →
     * `agenda.org_archive`), and an empty path (`::* Heading`) means that same
     * file. A path that already has an extension (`agenda.org_archive`) is used
     * exactly as written; only a bare name (`archive`) gets `.org` appended.
     */
    fun parse(raw: String, sourceFile: String? = null): ArchiveTarget? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split("::", limit = 2)
        var filePart = parts[0].trim().removePrefix("./")
        if (sourceFile != null) filePart = filePart.replace("%s", sourceFile)
        if (filePart.isEmpty()) filePart = sourceFile ?: return null
        val hasExtension = '.' in filePart.substringAfterLast('/').drop(1)
        val fileName = if (hasExtension) filePart else "$filePart.org"
        val headingPath = if (parts.size > 1) {
            parts[1].trim().removePrefix("*").trim()
                .split("/").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return ArchiveTarget(fileName, headingPath)
    }

    /**
     * Nearest-ancestor-wins resolution, org-property-inheritance style: a
     * heading's own `:ARCHIVE:` wins, else the closest ancestor's, else the
     * file-level `:PROPERTIES:` drawer's `:ARCHIVE:`, else the file-level
     * `#+ARCHIVE:` keyword, else (only when the file names nothing)
     * [settingsFallback], the app-wide default archive location. Keys match
     * case-insensitively, like org; a blank value doesn't stop the search.
     * [sourceFile] feeds [parse]'s `%s` / same-file handling.
     */
    fun resolve(
        doc: OrgDocument,
        headline: OrgHeadline,
        settingsFallback: ArchiveTarget? = null,
        sourceFile: String? = null,
    ): ArchiveTarget? {
        var current: OrgHeadline? = headline
        while (current != null) {
            current.properties.entries.firstOrNull { it.key.equals("ARCHIVE", ignoreCase = true) }
                ?.let { parse(it.value, sourceFile) }
                ?.let { return it }
            current = doc.parent(current)
        }
        doc.filePropertyDrawer.firstOrNull { it.first.equals(":ARCHIVE:", ignoreCase = true) }
            ?.let { parse(it.second, sourceFile) }
            ?.let { return it }
        doc.preambleKeywords.firstOrNull { it.first.equals("#+ARCHIVE:", ignoreCase = true) }
            ?.let { parse(it.second, sourceFile) }
            ?.let { return it }
        return settingsFallback
    }

    /**
     * Finds each heading in [path] in order (top level for the first segment,
     * a direct child of the previous match thereafter), creating any missing
     * one as an empty heading. Returns the possibly-mutated document plus the
     * final heading, or a null heading when [path] is empty (i.e. top level).
     */
    fun findOrCreateHeadingPath(doc: OrgDocument, path: List<String>): Pair<OrgDocument, OrgHeadline?> {
        var currentDoc = doc
        var parent: OrgHeadline? = null
        for (title in path) {
            val siblings = parent?.let { currentDoc.directChildren(it) }
                ?: currentDoc.headlines.filter { currentDoc.parent(it) == null }
            val existing = siblings.firstOrNull { it.title == title }
            parent = if (existing != null) {
                existing
            } else {
                val (newText, newLine) = if (parent != null) {
                    OrgMutations.newChild(currentDoc, parent, title)
                } else {
                    OrgMutations.newTopLevel(currentDoc, title)
                }
                currentDoc = OrgParser.parse(newText, currentDoc.keywords)
                currentDoc.headlines.firstOrNull { it.lineIndex == newLine }
            }
        }
        return currentDoc to parent
    }
}
