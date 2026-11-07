package com.rrajath.grove.org

/** A text buffer plus cursor offset, the result of an editing helper. */
data class TextEdit(val text: String, val cursor: Int)

/**
 * Pure line-editing behaviors shared by the note editor and capture editor.
 * Operates on (text, cursor) so it stays JVM-testable without Compose types.
 */
object LineEditing {

    // Groups: 1 indent, 2 bullet, 3 spaces after bullet, 4 checkbox marker
    // ("[ ]"/"[x]"/"[X]"/"[-]", or absent), 5 spaces after the checkbox, 6 content.
    private val LIST_ITEM = Regex("""^(\s*)([-+]|\d+[.)])( +)(\[[ Xx-]\])?( *)(.*)$""")
    private val NUMBERED = Regex("""^(\d+)([.)])$""")
    private val EMPTY_HEADING = Regex("""^\*+ ?$""")
    private val TITLED_HEADING = Regex("""^(\*+) +\S""")

    /**
     * Org/markdown style list continuation. The caller (see `OrgInputTransformation`)
     * has already established, from the field's actual change list rather than a
     * whole-text diff, that this edit's only newline-adjacent change is a solitary
     * `"\n"` inserted right before [cursor] — so it doesn't matter whether an IME
     * also finalized/autocorrected a composing word earlier on the same line in the
     * same batch (a real Enter press right after a mid-line edit used to look, to a
     * naive whole-text length diff, like "more than one character changed" and get
     * silently dropped). Only the resulting line, read from [text], decides whether
     * to continue the list (`- `, `+ `, `3. `→`4. `, `- [ ] `→`- [ ] `) or, when the
     * item was empty, remove the dangling bullet instead. A continued checklist item
     * always restarts unchecked, regardless of the previous item's state. Returns
     * null when the line before the cursor isn't a list item.
     */
    fun continueListOnEnter(text: String, cursor: Int): TextEdit? {
        if (cursor < 1 || cursor > text.length || text[cursor - 1] != '\n') return null

        val lineStart = text.lastIndexOf('\n', cursor - 2) + 1
        val prevLine = text.substring(lineStart, cursor - 1)
        val item = LIST_ITEM.matchEntire(prevLine) ?: return null
        val (indent, bullet, _, checkbox, _, content) = item.destructured

        return if (content.isBlank()) {
            // Enter on an empty item ends the list: drop the bullet, no new line.
            TextEdit(text.substring(0, lineStart) + text.substring(cursor), lineStart)
        } else {
            val nextBullet = NUMBERED.matchEntire(bullet)
                ?.destructured
                ?.let { (n, suffix) -> "${n.toLong() + 1}$suffix" }
                ?: bullet
            val checkboxPart = if (checkbox.isNotEmpty()) "[ ] " else ""
            val insert = "$indent$nextBullet $checkboxPart"
            TextEdit(
                text.substring(0, cursor) + insert + text.substring(cursor),
                cursor + insert.length,
            )
        }
    }

    private const val INDENT_STEP = "  "

    /**
     * Toolbar indent buttons: shift the list item under the cursor one level
     * deeper ([delta] > 0) or shallower by [INDENT_STEP], turning it into a
     * sub-list item or promoting it back. Returns null when the cursor line
     * isn't a list item, or when outdenting an item already at column zero.
     */
    fun changeListIndent(text: String, cursor: Int, delta: Int): TextEdit? {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val item = LIST_ITEM.matchEntire(line) ?: return null
        val (indent, bullet, spaces, checkbox, checkboxSpace, content) = item.destructured
        val newLine = if (delta > 0) {
            // A fresh sub-list restarts numbering at 1 (unordered bullets unchanged).
            val newBullet = NUMBERED.matchEntire(bullet)
                ?.destructured?.let { (_, suffix) -> "1$suffix" }
                ?: bullet
            INDENT_STEP + indent + newBullet + spaces + checkbox + checkboxSpace + content
        } else {
            if (indent.isEmpty()) return null
            line.substring(minOf(INDENT_STEP.length, indent.length))
        }
        return TextEdit(
            text.substring(0, lineStart) + newLine + text.substring(lineEnd),
            (at + newLine.length - line.length).coerceAtLeast(lineStart),
        )
    }

    /**
     * Whether [line] is a heading line with no title typed yet: just stars,
     * e.g. `"* "`, `"** "`, or stars followed by one of [keywords]' TODO
     * states, e.g. `"* TODO "`. Either way the very next character typed is
     * the first letter of the heading title.
     */
    private fun isEmptyHeadingPrefix(line: String, keywords: OrgKeywords): Boolean {
        var stars = 0
        while (stars < line.length && line[stars] == '*') stars++
        if (stars == 0 || stars >= line.length || line[stars] != ' ') return false
        val rest = line.substring(stars + 1)
        if (rest.isEmpty()) return true
        return keywords.all.any { rest == "$it " }
    }

    /**
     * Auto-capitalize the first letter typed into an org heading (including
     * right after a TODO keyword, e.g. `"* TODO "`). Returns null when no
     * capitalization is needed (not a heading line, char already uppercase,
     * or the edit isn't an insertion landing right at the first content character).
     *
     * Diffs [oldText]/[newText] by common prefix/suffix rather than assuming a
     * single-character change, so this also fires for swipe-typed or
     * autocorrect-committed words, which an IME delivers as a multi-character
     * insertion in one batch rather than one key event per letter.
     *
     * Call this after [continueListOnEnter] in the text-change handler, passing
     * the old text (before the edit) and the new text + cursor from the IME.
     */
    fun capitalizeHeadingOnType(
        oldText: String,
        newText: String,
        cursor: Int,
        keywords: OrgKeywords = OrgKeywords.DEFAULT,
    ): TextEdit? = capitalizeFirstCharOnType(oldText, newText, cursor) { isEmptyHeadingPrefix(it, keywords) }

    /**
     * Auto-capitalize the first letter typed as the content of a numbered
     * list item (`"1. "`, `"2) "`, …) — the list-item counterpart of
     * [capitalizeHeadingOnType]. Returns null when no capitalization is
     * needed (not an empty numbered-item prefix, char already uppercase, or
     * the edit isn't an insertion landing right at the first content
     * character).
     */
    fun capitalizeListItemOnType(oldText: String, newText: String, cursor: Int): TextEdit? =
        capitalizeFirstCharOnType(oldText, newText, cursor, ::isEmptyNumberedListItemPrefix)

    private fun isEmptyNumberedListItemPrefix(line: String): Boolean {
        val item = LIST_ITEM.matchEntire(line) ?: return false
        val (_, bullet, _, _, _, content) = item.destructured
        return content.isEmpty() && NUMBERED.matches(bullet)
    }

    /**
     * Diffs [oldText]/[newText] by common prefix/suffix (rather than assuming
     * a single-character change, so this also fires for swipe-typed or
     * autocorrect-committed words) and, when the insertion is a single
     * lowercase letter landing right where [isEmptyPrefix] says the line so
     * far is an "empty" prefix (a bare heading/list-bullet with no title or
     * content yet), uppercases it.
     */
    private fun capitalizeFirstCharOnType(
        oldText: String,
        newText: String,
        cursor: Int,
        isEmptyPrefix: (String) -> Boolean,
    ): TextEdit? {
        if (newText.length <= oldText.length) return null
        val maxPrefix = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        val maxSuffix = maxPrefix - prefix
        var suffix = 0
        while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        val insertEnd = newText.length - suffix
        if (insertEnd <= prefix) return null
        if (cursor != insertEnd) return null
        val newChar = newText[prefix]
        if (!newChar.isLetter() || newChar.isUpperCase()) return null
        val lineStart = newText.lastIndexOf('\n', prefix - 1) + 1
        val lineBeforeInsertion = newText.substring(lineStart, prefix)
        if (!isEmptyPrefix(lineBeforeInsertion)) return null
        val capitalized = newText.substring(0, prefix) + newChar.uppercaseChar() + newText.substring(prefix + 1)
        return TextEdit(capitalized, cursor)
    }

    /**
     * Caret placement for a note created for immediate editing (outline "+" FAB).
     * When [heading] is true the caret parks just past the "* " that begins the
     * first line, inserting the single space when the stars have none so the line
     * stays a valid heading the moment the user types; when false it parks at the
     * very end (the blank body line), the historical default. Returns [text]
     * unchanged in the space-already-present and body cases.
     */
    fun newNoteCaret(text: String, heading: Boolean): TextEdit {
        if (!heading) return TextEdit(text, text.length)
        val lineEnd = text.indexOf('\n').let { if (it == -1) text.length else it }
        var stars = 0
        while (stars < lineEnd && text[stars] == '*') stars++
        if (stars == 0) return TextEdit(text, text.length)
        val hasSpace = stars < text.length && text[stars] == ' '
        return if (hasSpace) {
            TextEdit(text, stars + 1)
        } else {
            TextEdit(text.substring(0, stars) + " " + text.substring(stars), stars + 1)
        }
    }

    /**
     * Toolbar `*` button, contextual to the nearest titled heading at or above
     * the cursor (level L, or 0 when there is none).
     *
     * From any line that isn't an empty heading it starts a subheading (L+1
     * stars) on the next line. Tapping again on that still-empty heading line
     * steps it up one level at a time (L, L-1, ... 1) and then wraps back to
     * L+1, so for L = 3 the sequence is `****`, `***`, `**`, `*`, `****`.
     */
    fun insertHeadingStar(text: String, cursor: Int): TextEdit {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        if (EMPTY_HEADING.matches(line)) {
            val stars = line.trimEnd().length
            val next = if (stars > 1) stars - 1 else contextHeadingLevel(text, lineStart) + 1
            val newLine = "*".repeat(next) + " "
            return TextEdit(
                text.substring(0, lineStart) + newLine + text.substring(lineEnd),
                lineStart + newLine.length,
            )
        }
        val level = contextHeadingLevel(text, lineEnd)
        val snippet = "\n" + "*".repeat(level + 1) + " "
        return TextEdit(
            text.substring(0, at) + snippet + text.substring(at),
            at + snippet.length,
        )
    }

    /**
     * Level of the nearest titled heading on a line ending at or before [end]
     * (empty `** ` placeholders are skipped), or 0 when there is none.
     */
    private fun contextHeadingLevel(text: String, end: Int): Int {
        var lineEnd = end
        while (true) {
            val lineStart = text.lastIndexOf('\n', lineEnd - 1) + 1
            TITLED_HEADING.find(text.substring(lineStart, lineEnd))?.let { return it.groupValues[1].length }
            if (lineStart == 0) return 0
            lineEnd = lineStart - 1
        }
    }
}
