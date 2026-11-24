package com.rrajath.grove.data

import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The FTS5 mirror of the searchable text in the `notes` table.
 *
 * Room's annotations only cover FTS3/FTS4, and FTS4 has no `trigram` tokenizer,
 * which Grove needs, because its documented text semantics are arbitrary
 * case-insensitive *substring* matching (`meet` matches `committee`), not token
 * or prefix matching. So the virtual table is created by hand here and read via
 * `@RawQuery`; its writes live in [IndexDao] behind `@SkipQueryVerification` and
 * are co-transacted with the `notes` writes by [GroveDatabase] so the two tables
 * can never drift.
 *
 * The index is a candidate *narrower* only: `QueryMatcher` still decides every
 * final match, so an FTS hit that the Kotlin matcher rejects costs nothing but a
 * row. That also means the table is entirely optional (see [create]).
 *
 * Per-file deletes go through the Room-owned `notes_fts_map` side table
 * ([com.rrajath.grove.data.FtsMapEntity]), which records the rowid SQLite
 * assigned each mirror row, so a delete targets rowids instead of scanning the
 * UNINDEXED `fileName` column. A schema bump rebuilds both this table and the
 * map destructively, keeping them in step.
 */
object NotesFts {

    const val TABLE = "notes_fts"

    /**
     * `fileName`/`lineIndex` are UNINDEXED: they are carried so a match maps back
     * to its `notes` primary key, and must not themselves be searchable text.
     */
    private const val CREATE_TABLE = """
        CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(
            fileName UNINDEXED,
            lineIndex UNINDEXED,
            title,
            body,
            tokenize = 'trigram case_sensitive 0'
        )
    """

    /**
     * Creates the table if it does not exist, returning whether it is usable.
     *
     * minSdk 34 bundles a SQLite far newer than the 3.34 that introduced the
     * trigram tokenizer, so this should always succeed. It is still guarded:
     * a device whose SQLite was built without FTS5 would otherwise throw on
     * every sync, and search degrading to the (correct, just slower) in-memory
     * scan is much better than an app that cannot index at all.
     */
    fun create(connection: SQLiteConnection): Boolean = try {
        connection.execSQL(CREATE_TABLE)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "FTS5 trigram index unavailable; search falls back to the full scan", e)
        false
    }

    private const val TAG = "NotesFts" // i18n: log tag
}
