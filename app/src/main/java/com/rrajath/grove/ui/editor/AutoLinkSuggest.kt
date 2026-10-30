package com.rrajath.grove.ui.editor

import androidx.compose.ui.text.TextRange
import com.rrajath.grove.data.HeadingOutlineCache
import com.rrajath.grove.data.NoteOutlineRow
import com.rrajath.grove.data.NotebookEntity
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Prototype: an inline "as you type" auto-suggest for `[[id:…]]` links. Unlike
 * the toolbar's [LinkPickerSheet] (which browses/searches every file and
 * heading, and lets a target without an `:ID:` fall back to a plain link),
 * this only ever offers targets that already carry a file-level or heading
 * `:ID:` -- picking one always inserts an `id:` link, no fallback dialog.
 */
sealed interface AutoLinkSuggestion {
    val fileName: String
    val title: String
    val titleLower: String
    val id: String

    /** Unique across a whole suggestion list, for chip expand-state and Compose keys. */
    val key: String
}

data class AutoLinkFileSuggestion(
    override val fileName: String,
    override val title: String,
    override val id: String,
) : AutoLinkSuggestion {
    override val titleLower: String = title.lowercase()
    override val key: String = "file:$fileName"
}

data class AutoLinkHeadingSuggestion(
    override val fileName: String,
    val lineIndex: Int,
    override val title: String,
    override val id: String,
) : AutoLinkSuggestion {
    override val titleLower: String = title.lowercase()
    override val key: String = "heading:$fileName:$lineIndex"
}

/**
 * Vault-wide index the auto-suggester filters live as a 3+ character word is
 * typed. Built once (like [buildLinkSearchIndex]) from the same Room-cached
 * notebook/heading rows, but keeps only entries with an `:ID:` -- everything
 * in this list can become an `[[id:…]]` link without touching the file system.
 */
fun buildAutoLinkIndex(
    notebooks: List<NotebookEntity>,
    headings: List<NoteOutlineRow>,
): ImmutableList<AutoLinkSuggestion> {
    val items = ArrayList<AutoLinkSuggestion>(notebooks.size + headings.size)
    for (nb in notebooks) {
        val id = nb.orgId ?: continue
        val label = nb.title?.takeIf { it.isNotBlank() } ?: nb.fileName.removeSuffix(".org")
        items.add(AutoLinkFileSuggestion(nb.fileName, label, id))
    }
    for (row in headings) {
        val id = row.orgId ?: continue
        items.add(AutoLinkHeadingSuggestion(row.fileName, row.lineIndex, row.title, id))
    }
    return items.toImmutableList()
}

private object AutoLinkIndexKey

/** [buildAutoLinkIndex] over this snapshot, built once until the Room index changes. */
fun HeadingOutlineCache.Snapshot.autoLinkIndex(): ImmutableList<AutoLinkSuggestion> =
    derived(AutoLinkIndexKey) { buildAutoLinkIndex(it.notebooks, it.headings) }

/**
 * Case-insensitive substring match of [word] against [index], same semantics
 * as [filterLinkSearch]. Callers only invoke this once [word] has reached the
 * 3-character trigger threshold.
 */
fun filterAutoLinkSuggestions(
    index: List<AutoLinkSuggestion>,
    word: String,
    limit: Int = 30,
): List<AutoLinkSuggestion> {
    if (word.length < 3) return emptyList()
    val q = word.lowercase()
    val out = ArrayList<AutoLinkSuggestion>(limit.coerceAtMost(index.size))
    for (item in index) {
        if (item.titleLower.contains(q)) {
            out.add(item)
            if (out.size >= limit) break
        }
    }
    return out
}

/** The finished `[[id:…][Title]]` link a tap on [suggestion] splices in. */
fun formatAutoLinkInsertion(suggestion: AutoLinkSuggestion): String =
    "[[id:${suggestion.id}][${suggestion.title}]]"

/** A word actively being typed at the cursor, and the range it occupies in the buffer. */
data class WordAtCursor(val range: TextRange, val text: String)

private fun isWordChar(c: Char) = c.isLetterOrDigit()

/**
 * The word ending exactly at a collapsed cursor, or null when the selection
 * isn't collapsed, the cursor sits at the very start of a word (nothing typed
 * yet), or the cursor is inside an unclosed `[[…` on the current line (typing
 * inside a link's own target/description shouldn't trigger a second,
 * nonsensical suggestion list).
 */
fun wordAtCursor(text: CharSequence, selection: TextRange): WordAtCursor? {
    if (!selection.collapsed) return null
    val cursor = selection.start
    if (cursor <= 0 || cursor > text.length) return null
    var start = cursor
    while (start > 0 && isWordChar(text[start - 1])) start--
    if (start == cursor) return null
    if (isInsideOrgLink(text, cursor)) return null
    return WordAtCursor(TextRange(start, cursor), text.substring(start, cursor))
}

/** True when [pos] falls after an unmatched `[[` earlier on its own line. */
private fun isInsideOrgLink(text: CharSequence, pos: Int): Boolean {
    var lineStart = pos
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
    var i = lineStart
    var open = false
    while (i < pos - 1) {
        if (text[i] == '[' && text[i + 1] == '[') {
            open = true
            i += 2
        } else if (text[i] == ']' && i + 1 < text.length && text[i + 1] == ']') {
            open = false
            i += 2
        } else {
            i++
        }
    }
    return open
}

/**
 * True when [offset] sits on a line of the buffer's preface ([OrgMutations.prefaceRange]):
 * roam link and roam-node suggestions stay off there, since `#+KEY:` values aren't
 * body text. Only the lines before the first headline are parsed (the preface can't
 * reach past them), so this stays cheap enough to run on every keystroke.
 */
fun isInPreface(text: CharSequence, offset: Int): Boolean {
    val cursorLine = (0 until offset.coerceIn(0, text.length)).count { text[it] == '\n' }
    val preamble = text.lineSequence().takeWhile { !isHeadlineLine(it) }.toList()
    if (cursorLine >= preamble.size) return false
    val range = OrgMutations.prefaceRange(OrgParser.parse(preamble.joinToString("\n"))) ?: return false
    return cursorLine in range
}

/** Mirrors OrgParser's headline rule: one or more `*` followed by whitespace. */
private fun isHeadlineLine(line: String): Boolean =
    line.startsWith('*') && line.trimStart('*').firstOrNull()?.isWhitespace() == true

/**
 * Chip label for the suggestion strip: titles under 10 characters show in
 * full, longer ones truncate to 9 characters + an ellipsis until the chip is
 * tapped once to reveal the full title (a second tap then inserts the link).
 */
fun ellipsizeChipLabel(title: String, expanded: Boolean): String =
    if (expanded || title.length < 10) title else title.take(9) + "…"
