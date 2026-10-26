package com.rrajath.grove.ui.editor

import com.rrajath.grove.data.NoteOutlineRow
import com.rrajath.grove.data.NotebookEntity
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * One row of a picker's live search results (the insert-link picker and the
 * refile picker share this index): a whole file or a heading inside one.
 */
sealed interface LinkSearchItem {
    val fileName: String
    val title: String
    val titleLower: String
}

data class LinkFileHit(
    override val fileName: String,
    override val title: String,
    val noteCount: Int,
) : LinkSearchItem {
    override val titleLower: String = title.lowercase()
}

data class LinkHeadingHit(
    override val fileName: String,
    val lineIndex: Int,
    override val title: String,
    /** "notebook › ancestor › ancestor" breadcrumb down to this heading's parent. */
    val crumb: String,
) : LinkSearchItem {
    override val titleLower: String = title.lowercase()
}

/**
 * Builds the vault-wide flat index the link picker searches live as the user
 * types. Built once from the Room index alone -- [notebooks] and [headings] are
 * already-parsed titles pulled straight out of `notes`/`notebooks`, so this never
 * opens or re-parses a single `.org` file.
 *
 * [headings] must be ordered by (fileName, lineIndex), as `IndexDao.allHeadingOutlines`
 * returns them: each file's rows are then walked exactly once with an
 * ancestor-title stack (the same O(n) shape as `OrgDocument.inheritedTagsAll`) to
 * build every heading's breadcrumb without ever re-walking an ancestor chain.
 */
fun buildLinkSearchIndex(
    notebooks: List<NotebookEntity>,
    headings: List<NoteOutlineRow>,
): ImmutableList<LinkSearchItem> {
    val fileLabel = HashMap<String, String>(notebooks.size * 2)
    val items = ArrayList<LinkSearchItem>(notebooks.size + headings.size)
    for (nb in notebooks) {
        val label = nb.title?.takeIf { it.isNotBlank() } ?: nb.fileName.removeSuffix(".org")
        fileLabel[nb.fileName] = label
        items.add(LinkFileHit(nb.fileName, label, nb.noteCount))
    }
    var i = 0
    while (i < headings.size) {
        val file = headings[i].fileName
        val label = fileLabel[file] ?: file.removeSuffix(".org")
        // (level, title) of every ancestor currently open on the path down to
        // whichever row is next, nearest-first popped as levels rise back up.
        val stack = ArrayDeque<Pair<Int, String>>()
        while (i < headings.size && headings[i].fileName == file) {
            val row = headings[i]
            while (stack.isNotEmpty() && stack.last().first >= row.level) stack.removeLast()
            val crumb = (listOf(label) + stack.map { it.second }).joinToString(" › ")
            items.add(LinkHeadingHit(file, row.lineIndex, row.title, crumb))
            stack.addLast(row.level to row.title)
            i++
        }
    }
    return items.toImmutableList()
}

/** What happened after a tap on a live-search result (see `EditorViewModel.linkPickerSelectSearchResult`). */
sealed interface LinkPickerSearchOutcome {
    /** The tapped file/heading drilled the browse position further in; nothing to confirm yet. */
    data object Drilled : LinkPickerSearchOutcome
    /** A leaf heading is ready to link immediately -- no sub-headings to drill into. */
    data class ReadyToConfirm(val doc: OrgDocument, val fileName: String, val heading: OrgHeadline) : LinkPickerSearchOutcome
    /** The result's file or heading no longer exists. */
    data object Failed : LinkPickerSearchOutcome
}

/**
 * Case-insensitive substring match of [query] against [index], stopping the
 * moment [limit] hits are found instead of scanning the rest of the vault.
 * [LinkSearchItem.titleLower] is pre-folded once at index-build time, so this
 * never re-lowercases a title per keystroke.
 *
 * Items for which [exclude] returns true are skipped and never count toward
 * [limit] (the refile picker uses it to drop the subtree being moved; see
 * `filterRefileSearch`).
 */
fun filterLinkSearch(
    index: List<LinkSearchItem>,
    query: String,
    limit: Int = 40,
    exclude: (LinkSearchItem) -> Boolean = { false },
): List<LinkSearchItem> {
    if (query.isBlank()) return emptyList()
    val q = query.trim().lowercase()
    val out = ArrayList<LinkSearchItem>(limit.coerceAtMost(index.size))
    for (item in index) {
        if (item.titleLower.contains(q) && !exclude(item)) {
            out.add(item)
            if (out.size >= limit) break
        }
    }
    return out
}
