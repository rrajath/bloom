package com.rrajath.grove.ui.editor

import androidx.annotation.StringRes
import androidx.compose.ui.text.TextRange
import com.rrajath.grove.R

/**
 * Org snippets offered as a suggestion chip when their shorthand is typed (all
 * case-insensitive): the blocks `<q` quote, `<e` example, `<s` src (after Emacs
 * org-tempo), and the drawers `:PRO…` properties and `:LOG…` logbook.
 */
enum class BlockTemplate(val keyword: String, @StringRes val insertPrompt: Int) {
    QUOTE("QUOTE", R.string.editor_block_insert_quote),
    EXAMPLE("EXAMPLE", R.string.editor_block_insert_example),
    SRC("SRC", R.string.editor_block_insert_src),
    PROPERTIES("PROPERTIES", R.string.editor_block_insert_properties),
    LOGBOOK("LOGBOOK", R.string.editor_block_insert_logbook);

    val isDrawer: Boolean get() = this == PROPERTIES || this == LOGBOOK

    /** Opening line, without indentation. Src keeps a trailing space for the language. */
    val beginLine: String get() = when {
        isDrawer -> ":$keyword:"
        this == SRC -> "#+BEGIN_$keyword "
        else -> "#+BEGIN_$keyword"
    }

    val endLine: String get() = if (isDrawer) ":END:" else "#+END_$keyword"
}

/** A typed shorthand and the range it occupies in the buffer. */
data class BlockTrigger(val template: BlockTemplate, val range: TextRange)

/** A [BlockTrigger] expanded: replace [start]..[end] with [replacement], caret at [cursor]. */
data class BlockInsertion(val start: Int, val end: Int, val replacement: String, val cursor: Int)

/** Longest shorthand worth scanning back for: `:PROPERTIES:`. */
private const val MAX_TRIGGER_LENGTH = 12

private val BLOCK_SHORTHANDS = mapOf(
    'q' to BlockTemplate.QUOTE,
    'e' to BlockTemplate.EXAMPLE,
    's' to BlockTemplate.SRC,
)

/**
 * The shorthand just typed at a collapsed cursor, or null. The shorthand must start
 * the line or follow whitespace, and nothing but whitespace (or the end of the buffer)
 * may follow the cursor, so `a<q`, `<qu`, `<q|x` and `id:pro` don't trigger.
 *
 * - Blocks: exactly `<q` / `<e` / `<s`. Never in the preface ([isInPreface]), whose
 *   `#+KEY:` lines aren't body text.
 * - Drawers: `:` plus at least `PRO` / `LOG`, up to the full `:PROPERTIES:` /
 *   `:LOGBOOK:`, so the chip survives typing a few more letters. Allowed anywhere,
 *   the preface included.
 */
fun blockTemplateTriggerAt(text: CharSequence, selection: TextRange): BlockTrigger? {
    if (!selection.collapsed) return null
    val cursor = selection.start
    if (cursor < 2 || cursor > text.length) return null
    if (cursor < text.length && !text[cursor].isWhitespace()) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) {
        start--
        if (cursor - start > MAX_TRIGGER_LENGTH) return null
    }
    val token = text.substring(start, cursor)
    val range = TextRange(start, cursor)
    if (token.length == 2 && token[0] == '<') {
        val template = BLOCK_SHORTHANDS[token[1].lowercaseChar()] ?: return null
        if (isInPreface(text, cursor)) return null
        return BlockTrigger(template, range)
    }
    if (token.length >= 4) {
        val template = BlockTemplate.entries.firstOrNull { it.isDrawer && it.beginLine.startsWith(token, ignoreCase = true) }
        if (template != null) return BlockTrigger(template, range)
    }
    return null
}

/**
 * Expands [trigger] into its block or drawer, indented like the line it was typed on
 * (so one typed in a list item stays in that item). The shorthand is removed; if that
 * leaves the line blank the snippet takes its place, otherwise the rest of the line
 * is kept as is and the snippet starts on the next line. The caret lands on the empty
 * body line, except for src, where it lands after `#+BEGIN_SRC ` so the language can
 * be typed first.
 */
fun expandBlockTemplate(text: String, trigger: BlockTrigger): BlockInsertion {
    val start = trigger.range.min
    val end = trigger.range.max
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
    val indent = text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    // The line minus the shorthand and the whitespace around it: `a <q b` -> `a b`.
    val before = text.substring(lineStart, start).trimEnd()
    val after = text.substring(end, lineEnd).trim()
    val rest = when {
        after.isEmpty() -> before
        before.isBlank() -> indent + after
        else -> "$before $after"
    }
    val template = trigger.template
    val begin = indent + template.beginLine
    val block = "$begin\n$indent\n$indent${template.endLine}"
    val prefix = if (rest.isBlank()) "" else "$rest\n"
    val caretInBlock = if (template == BlockTemplate.SRC) begin.length else begin.length + 1 + indent.length
    return BlockInsertion(lineStart, lineEnd, prefix + block, lineStart + prefix.length + caretInBlock)
}
