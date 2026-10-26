package com.rrajath.grove.search

import android.database.SQLException
import android.util.Log
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.NoteEntity
import com.rrajath.grove.data.rawQuery

private const val TAG = "SearchCandidates"

/**
 * Loads only the rows a search could possibly match: the FTS5 index supplies
 * the text candidates and [narrowing] / the query's structured tokens become
 * SQL predicates. Both narrowings are supersets by construction (see
 * [NoteCandidateQuery]), so [QueryMatcher] still decides every result and the
 * visible behaviour is identical to scanning the whole vault.
 *
 * Shared by the Search screen and the Agenda widget's saved-search view so
 * both start from the same candidate set.
 */
suspend fun loadSearchCandidates(
    database: GroveDatabase,
    textQuery: SearchQuery?,
    narrowing: FacetNarrowing = FacetNarrowing.NONE,
): List<NoteEntity> {
    val dao = database.indexDao()
    val match = textQuery
        ?.takeIf { database.ftsAvailable }
        ?.let { FtsQuery.matchExpression(it) }
    val candidate = NoteCandidateQuery.build(match, textQuery, narrowing)
    return try {
        dao.notesMatching(rawQuery(candidate.sql, candidate.args))
    } catch (e: SQLException) {
        // A search must never fail because of the index: fall back to the
        // whole table and let the Kotlin matcher do all the work, exactly as
        // it did before the candidate query existed.
        Log.w(TAG, "candidate query failed, falling back to a full scan: ${candidate.sql}", e)
        dao.notesMatching(rawQuery("SELECT * FROM notes ORDER BY fileName, lineIndex"))
    }
}
