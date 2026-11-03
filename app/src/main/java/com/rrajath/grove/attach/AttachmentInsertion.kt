package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser

/** Result of [insertAttachmentLinks]: the new buffer text and where the cursor goes. */
data class AttachInsertResult(
    val text: String,
    /** Character offset just after the last inserted link. */
    val cursor: Int,
    /** Lines of the `:PROPERTIES:` drawer that received the new `:ID:`, for the editor flash. Null when no ID was added. */
    val idDrawerLines: IntRange?,
)

/** `[[attachment:<name>]]`. */
fun attachmentLinkText(name: String): String = "[[attachment:$name]]"

/**
 * Adds `:ID: [newId]` to [owner] (when [newId] is non-null), then inserts one
 * `[[attachment:<name>]]` per line at [cursor], adding line breaks so each link
 * sits on its own line. Pure; the editor applies the result as one undo step.
 *
 * A cursor inside the owner's metadata (headline, planning line, drawers; or the
 * file-level drawer and `#+KEY:` lines for a file owner) is moved to the start of
 * the owner's body so the links never break that metadata.
 */
fun insertAttachmentLinks(
    text: String,
    cursor: Int,
    owner: AttachOwner,
    newId: String?,
    names: List<String>,
): AttachInsertResult {
    require(names.isNotEmpty()) { "names must not be empty" }
    val doc = OrgParser.parse(text)
    var at = insertionOffset(doc, cursor.coerceIn(0, text.length), owner)

    var working = text
    var drawerLines: IntRange? = null
    if (newId != null) {
        val edited = when (owner) {
            is AttachOwner.File -> OrgMutations.upsertFileProperty(doc, "ID", newId)
            is AttachOwner.Heading -> {
                val h = doc.headlines.firstOrNull { it.lineIndex == owner.lineIndex }
                    ?: error("owner heading not found at line ${owner.lineIndex}")
                OrgMutations.upsertProperty(doc, h, "ID", newId)
            }
        }
        // The ID edit only touches lines above the owner's body, so everything at
        // and after the insertion point shifts by the length change.
        at += edited.length - working.length
        working = edited
        drawerLines = idDrawerRange(OrgParser.parse(working), owner)
    }

    val before = working.substring(0, at)
    val after = working.substring(at)
    val prefix = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
    val block = names.joinToString("\n") { attachmentLinkText(it) }
    val suffix = if (after.isEmpty() || after.startsWith("\n")) "" else "\n"
    val newText = before + prefix + block + suffix + after
    return AttachInsertResult(newText, at + prefix.length + block.length, drawerLines)
}

/** Clamps [cursor] to the owner's body so links never land inside its metadata. */
private fun insertionOffset(doc: OrgDocument, cursor: Int, owner: AttachOwner): Int {
    val cursorLine = lineOfOffset(doc.text, cursor)
    val bodyLine = when (owner) {
        is AttachOwner.File -> doc.introStart
        is AttachOwner.Heading -> doc.headlines.firstOrNull { it.lineIndex == owner.lineIndex }?.bodyStart
            ?: return cursor
    }
    return if (cursorLine < bodyLine) lineStartOffset(doc, bodyLine) else cursor
}

/** Offset of the first character of [line]; text length when [line] is past the end. */
private fun lineStartOffset(doc: OrgDocument, line: Int): Int {
    if (line >= doc.lines.size) return doc.text.length
    var offset = 0
    for (i in 0 until line) offset += doc.lines[i].length + 1
    return offset
}

private fun idDrawerRange(doc: OrgDocument, owner: AttachOwner): IntRange? = when (owner) {
    is AttachOwner.File -> OrgMutations.fileDrawerRange(doc)
    is AttachOwner.Heading -> doc.headlines.firstOrNull { it.lineIndex == owner.lineIndex }
        ?.let { OrgMutations.headingDrawerRange(doc, it, ":PROPERTIES:") }
}
