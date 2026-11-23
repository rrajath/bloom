package com.rrajath.grove.ui.vault

import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.IndexDao
import com.rrajath.grove.data.NoteEntity
import com.rrajath.grove.data.NotesFts
import com.rrajath.grove.data.rawQuery
import com.rrajath.grove.search.FtsQuery
import com.rrajath.grove.settings.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SQLite's host-parameter limit is 999 on older Android; stay well under it. */
private const val OUTLINE_QUERY_CHUNK = 500

/**
 * Candidate notes whose body contains [text] (case-insensitive), for the
 * Linked (`id:…`) and Unlinked (title) halves of Backlinks. A superset only:
 * [computeLinkedReferences] decides every hit.
 *
 * Probes the [NotesFts] trigram index, so a note open costs about a
 * millisecond instead of a `LIKE` scan of every body in the vault. The index
 * also folds non-ASCII case (`Über`/`über`), which `LIKE` doesn't, so it
 * returns at least every row the scan did. Falls back to the scan when [text]
 * is under three characters (no trigram to look up) or FTS5 is unavailable.
 */
internal suspend fun IndexDao.notesWithBodyText(text: String): List<NoteEntity> {
    val match = if (ftsAvailable) FtsQuery.bodyContains(text) else null
    return if (match != null) {
        notesMatching(
            rawQuery(
                "SELECT * FROM notes WHERE (fileName, lineIndex) IN " +
                    "(SELECT fileName, lineIndex FROM ${NotesFts.TABLE} WHERE ${NotesFts.TABLE} MATCH ?)", // i18n: SQL
                listOf(match),
            ),
        )
    } else {
        notesWithBodyContaining(escapeLikeNeedle(text))
    }
}

/**
 * Backlinks/mentions for the Backlinks bar+sheet, shared by
 * [DocumentViewModel] and `EditorViewModel`. Each [load] cancels the one before
 * it, so a slow scan for an old target can't land after (and overwrite) a newer one.
 */
class LinkedReferencesLoader(
    private val scope: CoroutineScope,
    private val database: GroveDatabase,
    private val settings: SettingsSource,
    private val dispatchers: AppDispatchers,
) {
    private val _result = MutableStateFlow(LinkedReferencesResult.EMPTY)
    val result: StateFlow<LinkedReferencesResult> = _result

    private var job: Job? = null

    /**
     * Loads what elsewhere in the vault links to (or plainly mentions) this
     * note: [targetId] is the heading's own `:ID:` (or the file's, for the
     * intro), null when it has none yet -- the linked half is then always
     * empty, but the unlinked-mentions scan by [title] still runs.
     */
    fun load(fileName: String, lineIndex: Int, targetId: String?, title: String) {
        job?.cancel()
        job = scope.launch {
            // Vault-wide body lookups that only the Backlinks bar uses: skip
            // them entirely while that bar can't show.
            if (!settings.settings.first().roamBacklinksActive) {
                _result.value = LinkedReferencesResult.EMPTY
                return@launch
            }
            _result.value = withContext(dispatchers.default) {
                val dao = database.indexDao()
                val linkCandidates = targetId
                    ?.let { dao.notesWithBodyText("id:$it") }
                    .orEmpty()
                val mentionCandidates = title
                    .takeIf { it.isNotBlank() }
                    ?.let { dao.notesWithBodyText(it) }
                    .orEmpty()
                val bare = computeLinkedReferences(
                    targetId, title, fileName to lineIndex, linkCandidates, mentionCandidates, emptyMap(),
                )
                // Crumbs only for the files that actually produced hits, not every heading in the vault.
                val files = hitFiles(bare)
                if (files.isEmpty()) {
                    bare
                } else {
                    val outlines = files.chunked(OUTLINE_QUERY_CHUNK).flatMap { dao.headingOutlinesFor(it) }
                    withCrumbs(bare, buildOwnPathCrumbs(outlines))
                }
            }
        }
    }

    /** Cancels any in-flight [load] and empties the result, for a target whose bar can't show. */
    fun clear() {
        job?.cancel()
        job = null
        _result.value = LinkedReferencesResult.EMPTY
    }
}
