package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser

/**
 * Where a part-of-file editor buffer sits in its org file. By default
 * [fileText] is the file on disk and [span] the lines the buffer replaces when
 * it's saved (the single-heading editor's owned span). With [placed],
 * [fileText] already holds the buffer starting at `span.first`, the way
 * capture will insert it (possibly re-leveled), so nothing is spliced.
 */
data class AttachFileContext(val fileText: String, val span: IntRange, val placed: Boolean = false)

/**
 * An editor buffer placed back into its org file, so attach owners resolve
 * against the whole file. A single-heading editor's buffer is only that
 * heading's subtree: on its own, ancestors' and the file drawer's `:DIR:` are
 * invisible. The buffer is spliced into the file text exactly as the save path
 * does ([OrgMutations.replaceSpan]), so unsaved edits count, and buffer line `i`
 * is file line `span.first + i`.
 *
 * Owners come back in buffer coordinates ([AttachOwner.Heading.lineIndex] and
 * `titlePath` as the buffer sees them), so link insertion keeps working on the
 * buffer; only `dirProperty` and `dirFrom` come from the whole file (`dirFrom`
 * in file coordinates).
 */
class BufferInFile private constructor(
    val buffer: OrgDocument,
    /** The whole file with the buffer in it; null when resolving against the buffer alone. */
    val file: OrgDocument?,
    private val offset: Int,
    /** Capture re-levels its entry, so a placed buffer's headings match by title only. */
    private val matchLevel: Boolean,
) {

    /** The owner at buffer line [line]; see [resolveOwnerAtLine]. */
    fun ownerAtLine(line: Int): AttachOwner {
        val local = resolveOwnerAtLine(buffer, line)
        val file = file ?: return local
        val h = buffer.headingAtLine(line) ?: return local
        val inFile = file.headlines.firstOrNull { it.lineIndex == h.lineIndex + offset }
            ?.takeIf { it.title == h.title && (!matchLevel || it.level == h.level) }
            ?: return local
        val whole = resolveOwnerAtLine(file, inFile.lineIndex) as AttachOwner.Heading
        return buffer.ownerOf(h).copy(dirProperty = whole.dirProperty, dirFrom = whole.dirFrom)
    }

    /**
     * The file's owner for buffer line [line] when the buffer has no heading of
     * its own there but sits inside the file: capture text that goes straight
     * into a target heading's body belongs to that heading. In file
     * coordinates; null when the buffer has a heading at [line] or no file.
     */
    fun ownerAround(line: Int): AttachOwner? {
        val file = file ?: return null
        if (buffer.headingAtLine(line) != null) return null
        return resolveOwnerAtLine(file, line + offset)
    }

    /** The level of [owner]'s `dirFrom` heading, looked up where it lives (the file, else the buffer). */
    fun dirFromLevel(owner: AttachOwner.Heading): Int? {
        val from = owner.dirFrom as? AttachOwner.Heading ?: return null
        return (file ?: buffer).headlines.firstOrNull { it.lineIndex == from.lineIndex }?.level
    }

    /** The owner at character [offset] of the buffer. */
    fun ownerAtOffset(offset: Int): AttachOwner = ownerAtLine(lineOfOffset(buffer.text, offset))

    companion object {
        /**
         * [bufferText] placed in [context]'s file. A null [context] (the buffer is
         * the whole file) or a span that no longer fits the file resolves against
         * the buffer alone.
         */
        fun of(bufferText: String, context: AttachFileContext?): BufferInFile {
            val buffer = OrgParser.parse(bufferText)
            val alone = BufferInFile(buffer, null, 0, matchLevel = true)
            context ?: return alone
            val fileDoc = OrgParser.parse(context.fileText)
            val span = context.span
            if (span.first < 0 || span.last >= fileDoc.lines.size || span.isEmpty()) return alone
            if (context.placed) return BufferInFile(buffer, fileDoc, span.first, matchLevel = false)
            val (spliced, _) = OrgMutations.replaceSpan(fileDoc, span.first, span.last + 1, bufferText)
            return BufferInFile(buffer, OrgParser.parse(spliced), span.first, matchLevel = true)
        }
    }
}
