package com.rrajath.grove.ui.vault

import com.rrajath.grove.data.IndexDao
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.ui.editor.LinkFileHit
import com.rrajath.grove.ui.editor.LinkHeadingHit
import com.rrajath.grove.ui.editor.LinkSearchItem
import com.rrajath.grove.ui.editor.buildLinkSearchIndex
import com.rrajath.grove.ui.editor.filterLinkSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

// Live search for RefileSheet. It reuses the insert-link picker's vault-wide
// index and filter (ui/editor/LinkPickerSearch.kt); the only refile-specific
// rule is that the subtree(s) being moved can never be a destination.

/**
 * The vault-wide file + heading index the refile picker searches, built from
 * the Room index alone (no `.org` file is opened), same as the link picker's.
 */
suspend fun loadRefileSearchIndex(dao: IndexDao): ImmutableList<LinkSearchItem> =
    buildLinkSearchIndex(dao.notebooks(), dao.allHeadingOutlines())

/**
 * Line ranges of every refiled subtree in [currentDoc] (each source headline
 * through its last descendant). Empty when there's no active note, as for
 * Settings' location pickers.
 */
fun refileExcludedRanges(currentDoc: OrgDocument?, sourceLines: List<Int>): List<IntRange> {
    if (currentDoc == null) return emptyList()
    return sourceLines.mapNotNull { line ->
        currentDoc.headlineAtLine(line)?.let { it.lineIndex until currentDoc.subtreeEndLine(it) }
    }
}

/**
 * True for a heading hit inside one of the [excluded] subtrees of
 * [currentFileName]. File hits are never excluded: refiling to the source
 * file's own top level is allowed, as in the drill-down.
 */
fun isExcludedRefileHit(item: LinkSearchItem, currentFileName: String?, excluded: List<IntRange>): Boolean =
    item is LinkHeadingHit && currentFileName != null && item.fileName == currentFileName &&
        excluded.any { item.lineIndex in it }

/** [filterLinkSearch] minus the subtrees being refiled (see [isExcludedRefileHit]). */
fun filterRefileSearch(
    index: List<LinkSearchItem>,
    query: String,
    currentFileName: String?,
    excluded: List<IntRange>,
): List<LinkSearchItem> =
    filterLinkSearch(index, query) { isExcludedRefileHit(it, currentFileName, excluded) }

/**
 * The picker state after tapping search hit [item], whose file is [doc]: the
 * same state picking it in the drill-down would leave. A file hit sits at that
 * file's top level; a heading hit drills the full ancestor path down to (and
 * including) the heading, so "Refile here" targets it.
 *
 * [excluded] are the source-subtree ranges that apply to [doc] (empty unless
 * [doc] is the source file). Returns null when the heading is gone, its title
 * no longer matches the (possibly stale) Room index, or it lies inside a
 * subtree being refiled, so a stale hit can never pick the wrong target.
 */
fun RefileUiState.withSearchHit(item: LinkSearchItem, doc: OrgDocument, excluded: List<IntRange>): RefileUiState? =
    when (item) {
        is LinkFileHit -> copy(pickedFile = item.fileName, pickedDoc = doc, path = persistentListOf())
        is LinkHeadingHit -> {
            val heading = doc.headlineAtLine(item.lineIndex)
            if (heading == null || heading.title != item.title || excluded.any { heading.lineIndex in it }) null
            else copy(
                pickedFile = item.fileName,
                pickedDoc = doc,
                path = generateSequence(heading, doc::parent).toList().asReversed()
                    .map { it.lineIndex }
                    .toImmutableList(),
            )
        }
    }
