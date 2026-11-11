package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.vault.FileEntry

/**
 * Finds the vault file a tapped local-file link points at, for read mode.
 *
 * A tap only carries the link's raw target, not where it sits, so an
 * `attachment:` link's owner is recovered from [OrgDocument] text: every line
 * holding the target gives an owner candidate (via [resolveOwnerAtLine]), and
 * the first whose attach dir ([AttachDirResolver.readDir]) holds the file wins.
 * `file:` and bare relative links resolve against the org file's directory.
 */
class AttachmentLookup(
    private val files: AttachmentFiles,
    private val config: AttachConfig,
) {

    /**
     * Vault path of the existing file [link] (classified from [rawTarget]) points
     * at, or null when it isn't there. [orgFile] is the file holding the link and
     * [doc] its parsed content. Occurrences inside [preferLines] are tried first,
     * so a note view picks the copy of a repeated link that it shows.
     */
    suspend fun find(
        link: LocalAttachment,
        rawTarget: String,
        orgFile: String,
        doc: OrgDocument?,
        preferLines: IntRange? = null,
    ): String? = findEntry(link, rawTarget, orgFile, doc, preferLines)?.name

    /**
     * Like [find], returning the file's [FileEntry] (its [FileEntry.name] is the
     * vault path). [atLine], when it's a line of [doc], pins the owner to the
     * heading at that line instead of searching the text for the link.
     */
    suspend fun findEntry(
        link: LocalAttachment,
        rawTarget: String,
        orgFile: String,
        doc: OrgDocument?,
        preferLines: IntRange? = null,
        atLine: Int? = null,
    ): FileEntry? = when (link.base) {
        AttachmentBase.ORG_DIR -> orgDirPath(orgFile, link.path)?.let { statAt(it) }
        AttachmentBase.ATTACH_DIR -> {
            val resolver = AttachDirResolver(files, config)
            val owners = if (doc != null && atLine != null && atLine in doc.lines.indices) {
                listOf(resolveOwnerAtLine(doc, atLine, config))
            } else {
                linkOwners(doc, rawTarget, preferLines, config)
            }
            owners.firstNotNullOfOrNull { owner ->
                val dir = resolver.readDir(orgFile, owner) ?: return@firstNotNullOfOrNull null
                normalizeVaultPath(joinPath(dir, link.path))?.takeIf { it.isNotEmpty() }?.let { statAt(it) }
            }
        }
    }

    /** [AttachmentFiles.stat], with the entry's name set to [path] whatever the store reports. */
    private suspend fun statAt(path: String): FileEntry? = files.stat(path)?.copy(name = path)

    private fun orgDirPath(orgFile: String, path: String): String? {
        // Absolute and home paths point outside the vault; nothing to open.
        if (path.startsWith("/") || path.startsWith("~")) return null
        return normalizeVaultPath(joinPath(orgFile.substringBeforeLast('/', ""), path))?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Owner candidates for a link to [rawTarget] in [doc]: the owner of each line
 * containing it, lines in [preferLines] first, deduplicated. The file-level
 * owner when the text doesn't contain the link (e.g. a stale document).
 */
internal fun linkOwners(
    doc: OrgDocument?,
    rawTarget: String,
    preferLines: IntRange?,
    config: AttachConfig,
): List<AttachOwner> {
    doc ?: return emptyList()
    val hits = if (rawTarget.isBlank()) emptyList() else doc.lines.indices.filter { doc.lines[it].contains(rawTarget) }
    val ordered = if (preferLines == null) hits else hits.sortedBy { if (it in preferLines) 0 else 1 }
    return ordered.map { resolveOwnerAtLine(doc, it, config) }.ifEmpty { listOf(doc.fileOwner()) }.distinct()
}
