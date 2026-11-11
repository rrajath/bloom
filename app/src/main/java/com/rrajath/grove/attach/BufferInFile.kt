package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser

/**
 * Where a part-of-file editor buffer sits in its org file: the file's text on
 * disk and the file lines the buffer replaces when it's saved (the editor's
 * owned span).
 */
data class AttachFileContext(val fileText: String, val span: IntRange)

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
    private val file: OrgDocument?,
    private val offset: Int,
) {

    /** The owner at buffer line [line]; see [resolveOwnerAtLine]. */
    fun ownerAtLine(line: Int): AttachOwner {
        val local = resolveOwnerAtLine(buffer, line)
        val file = file ?: return local
        val h = buffer.headingAtLine(line) ?: return local
        val inFile = file.headlines.firstOrNull { it.lineIndex == h.lineIndex + offset }
            ?.takeIf { it.level == h.level && it.title == h.title }
            ?: return local
        val whole = resolveOwnerAtLine(file, inFile.lineIndex) as AttachOwner.Heading
        return buffer.ownerOf(h).copy(dirProperty = whole.dirProperty, dirFrom = whole.dirFrom)
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
            context ?: return BufferInFile(buffer, null, 0)
            val fileDoc = OrgParser.parse(context.fileText)
            val span = context.span
            if (span.first < 0 || span.last >= fileDoc.lines.size || span.isEmpty()) {
                return BufferInFile(buffer, null, 0)
            }
            val (spliced, _) = OrgMutations.replaceSpan(fileDoc, span.first, span.last + 1, bufferText)
            return BufferInFile(buffer, OrgParser.parse(spliced), span.first)
        }
    }
}
