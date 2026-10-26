package com.rrajath.grove.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext

/**
 * Rebuildable index over the vault (PRD §13): never the source of truth;
 * always derivable by re-parsing the .org files.
 */
@Entity(
    tableName = "notebooks",
    // Backs the file-level `[[id:…]]` lookup in `resolveOrgLink`: an `:ID:` in a
    // file's leading property drawer resolves to that file's outline, indexed so
    // the lookup stays O(log n) as the vault grows to thousands of files.
    indices = [Index("orgId")],
)
data class NotebookEntity(
    @PrimaryKey val fileName: String,
    /** Last indexed revision ("mtime:size"). */
    val revision: String,
    val noteCount: Int,
    val lastModified: Long,
    /** Name of a Syncthing .sync-conflict file shadowing this notebook, if any. */
    val conflictFileName: String?,
    /** Cached `#+TITLE:` preamble value, so the list doesn't re-parse files just to display it. */
    val title: String? = null,
    /**
     * The `:ID:` from the file's leading `:PROPERTIES:` drawer (before the first
     * headline), if any. Lets `[[id:…]]` links that target a whole file resolve
     * to its outline. Null for a stub row and for files with no file-level ID.
     */
    val orgId: String? = null,
    /**
     * False for a lightweight stub row inserted at discovery time (file listed
     * but not yet parsed): note count / title are placeholders until the
     * background parse pass fills them in and flips this to true. Lets the
     * notebook list appear in full immediately instead of growing row-by-row.
     */
    val isIndexed: Boolean = true,
)

/**
 * Indices back the facet pushdown in [IndexDao.notesMatching]: the chip filters
 * and `i.`/`p.` query tokens become SQL `WHERE` predicates instead of a scan
 * over every row. `scheduled`/`deadline` are indexed for their `IS NOT NULL`
 * probes (the agenda screen, and any date facet), not for date comparison:
 * they hold raw org timestamp strings.
 */
@Entity(
    tableName = "notes",
    primaryKeys = ["fileName", "lineIndex"],
    indices = [
        Index("keyword"),
        Index("isDone"),
        Index("priority"),
        Index("scheduled"),
        Index("deadline"),
        Index("activeTimestamps"),
        // Back the vault-wide `[[id:…]]` / `[[#custom-id]]` link lookups so they
        // probe an index instead of scanning every note row.
        Index("orgId"),
        Index("customId"),
    ],
)
data class NoteEntity(
    val fileName: String,
    val lineIndex: Int,
    val level: Int,
    val title: String,
    val keyword: String?,
    val priority: String?,
    /** Own tags, ":"-joined. */
    val tags: String,
    /** Inherited tags incl. own and file tags, ":"-joined (for t. searches). */
    val inheritedTags: String,
    val scheduled: String?,
    val deadline: String?,
    val closed: String?,
    /**
     * Space-joined `format()` forms of every bare active timestamp in the
     * heading's own body (the dedicated line plus any inline in prose), or null
     * when there are none. What the agenda and search read to place an event on
     * its day(s); never becomes overdue.
     */
    val activeTimestamps: String?,
    val orgId: String?,
    val customId: String?,
    val createdAt: String?,
    /** Own body text, in full, for full-text search and snippets. */
    val body: String,
    /** Done-type keyword flag resolved at index time. */
    val isDone: Boolean,
    /** Mirror of the notebook's lastModified for recency ranking. */
    val lastModified: Long,
)

/**
 * Maps a `notes` row (fileName + lineIndex) to the rowid SQLite assigned its
 * mirror row in the [NotesFts] virtual table. `notes_fts` declares `fileName`
 * UNINDEXED, so `DELETE FROM notes_fts WHERE fileName = ?` is a full scan of the
 * FTS content table — run once per changed file on every sync, and once per file
 * on a full resync (O(files x notes)). With this side table the per-file delete
 * targets specific rowids, which FTS5 removes in roughly O(1). Room-owned (a
 * plain B-tree table, no FTS), so a schema bump rebuilds it destructively
 * alongside `notes`.
 */
@Entity(
    tableName = "notes_fts_map",
    primaryKeys = ["fileName", "lineIndex"],
    indices = [Index("fileName")],
)
data class FtsMapEntity(
    val fileName: String,
    val lineIndex: Int,
    val ftsRowid: Long,
)

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val message: String,
)

/**
 * A scheduled SCHEDULED/DEADLINE reminder (see `reminders` package). Rebuildable
 * from the .org files like the rest of the index, but also carries live
 * scheduling state (the AlarmManager alarm, whether it already fired, whether
 * it's waiting on a permission) that isn't derivable from disk alone.
 */
@Entity(
    tableName = "reminders",
    // ReminderDao.forFile does `WHERE fileName = ?` once per file on every sync
    // (ReminderReconciler.reconcileFile, driven by RoomNoteIndex.onIndexed);
    // without this index that is a full table scan per file.
    indices = [Index("fileName")],
)
data class ReminderEntity(
    /** Composite: fileName + ancestor-title-path + own title + level + planning type. */
    @PrimaryKey val key: String,
    val fileName: String,
    /** Ancestor titles + own title, "/"-joined: how the heading is re-located on disk. */
    val headingPath: String,
    /** Own title only, used as the notification's title. */
    val headingTitle: String,
    val headingLevel: Int,
    /** "SCHEDULED" or "DEADLINE". */
    val planningType: String,
    val triggerAtMillis: Long,
    /** Stable per-[key] id for both the shown notification and its AlarmManager PendingIntent. */
    val notificationId: Int,
    /** True when scheduling was skipped for lack of POST_NOTIFICATIONS/exact-alarm access. */
    val pendingPermission: Boolean = false,
    /** Set once the "due now" notification has been shown for this trigger time, so
     *  catch-up passes don't re-fire it. Cleared whenever [triggerAtMillis] changes. */
    val firedAt: Long? = null,
    /** Whether this reminder shows its own "due now" notification when it fires.
     *  True whenever the timestamp carries its own time-of-day; for a date-only
     *  timestamp it is true only when Settings › Reminders › "Notify for tasks
     *  without a time" is on, and false otherwise (the row still feeds the daily
     *  digest count either way). */
    val firesOwnNotification: Boolean = true,
    /** [com.rrajath.grove.settings.ReminderLeadTime.storageKey] this row's [triggerAtMillis]
     *  was computed with, baked in at scheduling time so the "due in N minutes" notification
     *  text can't drift out of sync with a lead-time setting change made after this was armed. */
    val leadTime: String = "at_time",
    /** True when the heading carries a live (non-DONE) TODO keyword -- the sole
     *  signal for task-vs-event wording ("due now" vs "starting now") and for
     *  whether the notification always gets a Complete action, independent of
     *  which planning field (SCHEDULED/DEADLINE/bare active) this row tracks. */
    val isTask: Boolean = false,
    /** True when the timestamp this row tracks carries a repeater cookie (`+1w`
     *  etc.). Doesn't affect wording -- that's [isTask] alone -- but on an event
     *  row (`isTask == false`) it's what additionally earns a Complete action
     *  alongside Reschedule (a task row always gets Complete regardless of this
     *  flag). Always false for a bare active timestamp that only lives inline in
     *  prose, not on the heading's managed timestamp line -- that line is all
     *  [com.rrajath.grove.org.OrgMutations.setActiveTimestamps] knows how to
     *  rewrite, and Complete needs to rewrite it to honor the action. */
    val hasRepeater: Boolean = false,
    /** ISO date of the specific active timestamp this row tracks, set only for
     *  [com.rrajath.grove.reminders.PlanningType.ACTIVE] rows (a heading can
     *  carry several). Complete uses it to re-locate that one stamp among the
     *  heading's active timestamps and advance just it, leaving any others on
     *  the same line untouched. */
    val activeTimestampDate: String? = null,
)

/** Projection of the notebook columns the sync engine diffs against disk. */
data class NotebookSyncState(
    val fileName: String,
    val revision: String,
    val conflictFileName: String?,
    val isIndexed: Boolean,
)

/** Primary key of a `notes` row: what an FTS lookup hands back. */
data class NoteKey(val fileName: String, val lineIndex: Int)

/** One notebook's most recent note mtime, for recency-ranking filename matches. */
data class FileRecency(val fileName: String, val lastModified: Long)

/**
 * One heading's outline position + title, for the editor's link-picker search
 * index (see `buildLinkSearchIndex`). No body/tags/planning columns: the index
 * only ever matches and displays a title. [orgId] is the heading's `:ID:` (null
 * for headings without one), used by the inline auto-link suggester
 * (`buildAutoLinkIndex`) to filter down to headings an `[[id:…]]` link can target.
 */
data class NoteOutlineRow(
    val fileName: String,
    val lineIndex: Int,
    val level: Int,
    val title: String,
    val orgId: String? = null,
)

/**
 * Binds a statement built by `NoteCandidateQuery` for [IndexDao.notesMatching].
 * Every parameter that builder emits is text (match expressions, keywords,
 * tags, file names), so a single bind kind covers all of them.
 */
fun rawQuery(sql: String, args: List<String> = emptyList()): RoomRawQuery =
    RoomRawQuery(sql) { statement ->
        args.forEachIndexed { i, arg -> statement.bindText(i + 1, arg) }
    }

/**
 * Every column the filter catalog and the blank-state quick counts need, and
 * nothing else. Deliberately excludes `title`/`body`: this projection is held
 * in memory for the whole vault, so carrying note bodies in it would defeat the
 * point of moving search itself to a query-scoped load.
 */
data class NoteFacetRow(
    val fileName: String,
    val keyword: String?,
    val isDone: Boolean,
    val inheritedTags: String,
    val scheduled: String?,
    val deadline: String?,
    val activeTimestamps: String?,
    /** Mirror of the notebook's mtime, for recency-ranking filename matches. */
    val lastModified: Long,
)

/**
 * Every column the agenda / ledger widget read off a planned note, minus `body`.
 * [IndexDao.plannedNotes] backs a `Flow` that is held for the whole vault's
 * planned set (and inside the Glance widget process, where memory is tightest),
 * so carrying the full note text — which no agenda consumer matches on — is pure
 * overhead. Maps to `NoteMeta` via `toNoteMeta`, same as [NoteEntity].
 */
data class PlannedNoteRow(
    val fileName: String,
    val lineIndex: Int,
    val title: String,
    val keyword: String?,
    val priority: String?,
    val tags: String,
    val inheritedTags: String,
    val scheduled: String?,
    val deadline: String?,
    val closed: String?,
    val activeTimestamps: String?,
    val createdAt: String?,
    val isDone: Boolean,
    val lastModified: Long,
)

/**
 * An abstract class rather than an interface so it can carry [ftsAvailable]:
 * the FTS statements below live inside the same `@Transaction` methods as the
 * `notes` writes (that is what keeps the two tables from ever drifting), so the
 * "is there an FTS table at all" decision has to be readable from in here.
 */
@Dao
abstract class IndexDao {

    /**
     * Whether [NotesFts] exists and can be written to. Set once by
     * [GroveDatabase]'s bootstrap callback; false only where SQLite was built
     * without FTS5, in which case search falls back to the full scan.
     */
    @Volatile
    var ftsAvailable: Boolean = false

    @Query("SELECT * FROM notebooks")
    abstract suspend fun notebooks(): List<NotebookEntity>

    @Query("SELECT * FROM notebooks")
    abstract fun notebooksFlow(): Flow<List<NotebookEntity>>

    @Query("SELECT fileName, revision, conflictFileName, isIndexed FROM notebooks")
    abstract suspend fun notebookSyncStates(): List<NotebookSyncState>

    @Query("SELECT conflictFileName FROM notebooks WHERE fileName = :fileName")
    abstract suspend fun conflictFileNameFor(fileName: String): String?

    @Query("SELECT DISTINCT tags FROM notes WHERE tags != ''")
    abstract suspend fun allTagStrings(): List<String>

    /**
     * Every heading's outline position + title across the whole vault, ordered by
     * file then position -- exactly what `buildLinkSearchIndex` needs to build
     * every file's breadcrumb trail in a single linear pass. Excludes the file
     * intro row (`lineIndex < 0`): it isn't a heading and has no `[[*title]]`
     * form to link to.
     */
    @Query("SELECT fileName, lineIndex, level, title, orgId FROM notes WHERE lineIndex >= 0 ORDER BY fileName, lineIndex")
    abstract suspend fun allHeadingOutlines(): List<NoteOutlineRow>

    /** [allHeadingOutlines] narrowed to [files], for breadcrumbs over a handful of hit files. */
    @Query("SELECT fileName, lineIndex, level, title, orgId FROM notes WHERE fileName IN (:files) AND lineIndex >= 0 ORDER BY fileName, lineIndex")
    abstract suspend fun headingOutlinesFor(files: List<String>): List<NoteOutlineRow>

    /** Where a heading with this `:ID:` lives, for resolving `[[id:…]]` links vault-wide. */
    @Query("SELECT fileName, lineIndex FROM notes WHERE orgId = :id LIMIT 1")
    abstract suspend fun noteLocationByOrgId(id: String): NoteKey?

    /**
     * The file whose leading property drawer carries this `:ID:`, for resolving a
     * `[[id:…]]` link that targets a whole file (→ its outline). Indexed lookup.
     */
    @Query("SELECT fileName FROM notebooks WHERE orgId = :id LIMIT 1")
    abstract suspend fun notebookByOrgId(id: String): String?

    /** Where a heading with this `:CUSTOM_ID:` lives, for resolving `[[#id]]` links vault-wide. */
    @Query("SELECT fileName, lineIndex FROM notes WHERE customId = :customId LIMIT 1")
    abstract suspend fun noteLocationByCustomId(customId: String): NoteKey?

    /**
     * Every note whose own body text contains [needle] as a literal substring
     * (case-insensitive) -- the candidate set the Backlinks sheet
     * scans for `[[id:…]]` backlinks and plain-text title mentions. LIKE
     * rather than an FTS `MATCH`: a needle like `id:AB12` or a short title
     * isn't a safe FTS token, and the `notes` table is small enough (a
     * personal vault, not a corpus) for the scan to be cheap. [needle] must
     * already have `%`/`_`/`\` escaped by the caller (see `escapeLikeNeedle`).
     */
    @Query("SELECT * FROM notes WHERE body LIKE '%' || :needle || '%' ESCAPE '\\'")
    abstract suspend fun notesWithBodyContaining(needle: String): List<NoteEntity>

    @Query(
        "SELECT fileName, keyword, isDone, inheritedTags, scheduled, deadline, " +
            "activeTimestamps, lastModified FROM notes"
    )
    abstract fun noteFacets(): Flow<List<NoteFacetRow>>

    /**
     * A change signal for the `notes` table and nothing more: Room re-runs this
     * (and emits, even when the count is unchanged) on every write to the
     * table, so a consumer that only needs "the index changed, re-run my
     * query" can key off it without holding any row data. The Agenda widget's
     * saved-search view uses it.
     */
    @Query("SELECT COUNT(*) FROM notes")
    abstract fun notesChanged(): Flow<Int>

    /** Every notebook with indexed notes, plus its latest note mtime (see [FileRecency]). */
    @Query("SELECT fileName, MAX(lastModified) AS lastModified FROM notes GROUP BY fileName")
    abstract suspend fun fileRecency(): List<FileRecency>

    /**
     * Rows the agenda can possibly show. `QueryMatcher.agenda` buckets notes by
     * their SCHEDULED/DEADLINE date or a bare active timestamp, so a note with
     * none of the three can never appear: excluding those in SQL is an exact
     * narrowing, not an approximation.
     */
    @Query(
        "SELECT fileName, lineIndex, title, keyword, priority, tags, inheritedTags, " +
            "scheduled, deadline, closed, activeTimestamps, createdAt, isDone, lastModified " +
            "FROM notes WHERE scheduled IS NOT NULL OR deadline IS NOT NULL " +
            "OR activeTimestamps IS NOT NULL"
    )
    abstract fun plannedNotes(): Flow<List<PlannedNoteRow>>

    /**
     * Distinct SCHEDULED/DEADLINE timestamp strings across the vault — all the
     * "other days that already have something on them" dots on the planning
     * calendar need. No note identity, no body.
     */
    @Query(
        "SELECT scheduled AS ts FROM notes WHERE scheduled IS NOT NULL " +
            "UNION SELECT deadline AS ts FROM notes WHERE deadline IS NOT NULL"
    )
    abstract fun plannedTimestamps(): Flow<List<String>>

    /**
     * Every note's space-joined active-timestamp string (see
     * [NoteEntity.activeTimestamps]). Each row may hold several stamps / a
     * range, so the one consumer splits and `OrgTimestamp.parseAll`s them.
     */
    @Query("SELECT activeTimestamps FROM notes WHERE activeTimestamps IS NOT NULL")
    abstract fun plannedActiveTimestamps(): Flow<List<String>>

    /**
     * Candidate rows for one search, built by `NoteCandidateQuery`. Raw because
     * the query joins [NotesFts], which Room does not own as an entity.
     */
    @SkipQueryVerification
    @RawQuery
    abstract suspend fun notesMatching(query: RoomRawQuery): List<NoteEntity>

    /** Keys of the FTS rows matching a MATCH expression (see `FtsQuery`). */
    @SkipQueryVerification
    @RawQuery
    abstract suspend fun noteKeysMatching(query: RoomRawQuery): List<NoteKey>

    @Insert
    abstract suspend fun insertNotes(notes: List<NoteEntity>)

    @Insert
    abstract suspend fun insertNotebook(notebook: NotebookEntity)

    /**
     * Bulk-insert stub rows for newly-discovered files in one transaction (a
     * single [notebooksFlow] emission). IGNORE so files that already have a
     * row (indexed or stub) keep their real data instead of being blanked.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertNotebookStubs(notebooks: List<NotebookEntity>)

    @Query("UPDATE notebooks SET conflictFileName = :conflictFileName WHERE fileName = :fileName")
    abstract suspend fun setConflict(fileName: String, conflictFileName: String?)

    @Query("DELETE FROM notes WHERE fileName = :fileName")
    abstract suspend fun deleteNotes(fileName: String)

    @Query("DELETE FROM notebooks WHERE fileName = :fileName")
    abstract suspend fun deleteNotebook(fileName: String)

    /**
     * Rewrites one file's rows in `notes` and in the FTS mirror. Both happen in
     * the same transaction, which is what makes it impossible for the mirror to
     * drift from the table it indexes.
     */
    @Transaction
    open suspend fun replaceNotebook(notebook: NotebookEntity, notes: List<NoteEntity>) {
        deleteNotebook(notebook.fileName)
        deleteNotes(notebook.fileName)
        insertNotebook(notebook)
        insertNotes(notes)
        if (ftsAvailable) {
            deleteFtsRows(notebook.fileName)
            if (notes.isNotEmpty()) {
                val map = ArrayList<FtsMapEntity>(notes.size)
                for (n in notes) {
                    val rowid = insertFtsRow(n.fileName, n.lineIndex, n.title, n.body)
                    map.add(FtsMapEntity(n.fileName, n.lineIndex, rowid))
                }
                insertFtsMap(map)
            }
        }
    }

    /** Drops a deleted or renamed notebook from every table. */
    @Transaction
    open suspend fun removeNotebook(fileName: String) {
        deleteNotebook(fileName)
        deleteNotes(fileName)
        if (ftsAvailable) deleteFtsRows(fileName)
    }

    @Query("DELETE FROM notebooks")
    abstract suspend fun clearNotebooks()

    @Query("DELETE FROM notes")
    abstract suspend fun clearNotes()

    /** Wipes the whole index (rebuilt on next sync; it's only a cache). */
    @Transaction
    open suspend fun clearAll() {
        clearNotebooks()
        clearNotes()
        clearFtsMap()
        if (ftsAvailable) clearFts()
    }

    // --- FTS mirror ---
    //
    // Room cannot validate statements against a virtual table it does not own as
    // an entity, hence @SkipQueryVerification. The table is guaranteed to exist
    // at runtime by GroveDatabase's bootstrap callback; when creating it failed,
    // GroveDatabase.ftsAvailable is false and none of these run.

    /**
     * Deletes one file's FTS mirror rows by rowid (via [FtsMapEntity]) instead of
     * `WHERE fileName = ?`, which is a full scan because `notes_fts.fileName` is
     * UNINDEXED. Also clears the file's map rows.
     */
    @Transaction
    open suspend fun deleteFtsRows(fileName: String) {
        deleteFtsRowsByMap(fileName)
        deleteFtsMapRows(fileName)
    }

    @SkipQueryVerification
    @Query(
        "DELETE FROM notes_fts WHERE rowid IN " +
            "(SELECT ftsRowid FROM notes_fts_map WHERE fileName = :fileName)"
    )
    abstract suspend fun deleteFtsRowsByMap(fileName: String)

    @Query("DELETE FROM notes_fts_map WHERE fileName = :fileName")
    abstract suspend fun deleteFtsMapRows(fileName: String)

    @Insert
    abstract suspend fun insertFtsMap(rows: List<FtsMapEntity>)

    @Query("DELETE FROM notes_fts_map")
    abstract suspend fun clearFtsMap()

    /** Returns the rowid SQLite assigned the inserted FTS row. */
    @SkipQueryVerification
    @Query(
        "INSERT INTO notes_fts(fileName, lineIndex, title, body) " +
            "VALUES (:fileName, :lineIndex, :title, :body)"
    )
    abstract suspend fun insertFtsRow(fileName: String, lineIndex: Int, title: String, body: String): Long

    @SkipQueryVerification
    @Query("DELETE FROM notes_fts")
    abstract suspend fun clearFts()

    @SkipQueryVerification
    @Query("SELECT COUNT(*) FROM notes_fts")
    abstract suspend fun ftsRowCount(): Int

    @Query("SELECT COUNT(*) FROM notes_fts_map")
    abstract suspend fun ftsMapRowCount(): Int
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entry: SyncLogEntity)

    /** One transaction for a whole pass's worth of log lines (PERFORMANCE_AUDIT_2026-09-16 F4). */
    @Insert
    suspend fun insertAll(entries: List<SyncLogEntity>)

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SyncLogEntity>>

    @Query("SELECT COUNT(*) FROM sync_log")
    fun count(): Flow<Int>

    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT 500)")
    suspend fun trim()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE fileName = :fileName")
    suspend fun forFile(fileName: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE key = :key")
    suspend fun get(key: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE pendingPermission = 1")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE pendingPermission = 0 AND firedAt IS NULL AND triggerAtMillis <= :now")
    suspend fun overdueUnfired(now: Long): List<ReminderEntity>

    // Overdue rows are excluded: reconcilePending() settles those silently rather
    // than firing them, so they shouldn't be counted as "need permission" either.
    @Query("SELECT COUNT(*) FROM reminders WHERE pendingPermission = 1 AND triggerAtMillis > :now")
    fun pendingCountFlow(now: Long): Flow<Int>

    /** Upcoming reminders of any kind; gates the one-time exact-alarm prompt. */
    @Query("SELECT COUNT(*) FROM reminders WHERE triggerAtMillis > :now")
    suspend fun futureCount(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("UPDATE reminders SET firedAt = :firedAt WHERE key = :key")
    suspend fun markFired(key: String, firedAt: Long)

    @Query("DELETE FROM reminders WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM reminders")
    suspend fun clearAll()
}

@Database(
    entities = [
        NotebookEntity::class, NoteEntity::class, FtsMapEntity::class,
        SyncLogEntity::class, ReminderEntity::class,
    ],
    // v17: added ReminderEntity.isTask (task vs event is now decided by whether
    // the heading carries a live TODO keyword, not by planning type) and started
    // computing hasRepeater for SCHEDULED/DEADLINE rows too, not just ACTIVE.
    // Destructive migration drops the rebuildable reminders table; the next
    // reconcile repopulates it from disk.
    // v16: added ReminderEntity.hasRepeater/activeTimestampDate (an ACTIVE-type
    // reminder whose bare timestamp carries a repeater cookie is now a task, not
    // an event: its notification gets Complete/Reschedule actions, and Complete
    // needs the stamp's own date to re-locate and advance it). Destructive
    // migration drops the rebuildable reminders table; the next reconcile
    // repopulates it from disk.
    // v15: added notes_fts_map (fileName+lineIndex -> notes_fts rowid) so the
    // per-file FTS delete targets rowids instead of scanning the UNINDEXED
    // fileName column. Destructive migration drops every table (dropAllTables),
    // the FTS bootstrap recreates notes_fts empty, and the next sync repopulates
    // both from the .org files — no file data touched.
    // v14: added a secondary index on reminders.fileName (ReminderDao.forFile
    // runs `WHERE fileName = ?` once per file on every sync). Destructive
    // migration drops the rebuildable reminders table; the next reconcile
    // repopulates it from disk.
    // v13: renamed ReminderEntity.hasExplicitTime → firesOwnNotification (a
    // date-only reminder now fires its own notification when Settings › Reminders
    // › "Notify for tasks without a time" is on). Destructive migration drops the
    // rebuildable reminders table; the next reconcile repopulates it from disk.
    // v12: added NoteEntity.activeTimestamps (space-joined bare active timestamps
    // in a heading's own body) + a secondary index on it for the `IS NOT NULL`
    // probe. Destructive migration drops the rebuildable index; the next sync
    // repopulates it from the .org files, no file data touched.
    // v11: added NotebookEntity.orgId (file-level `:ID:` from the leading property
    // drawer, so `[[id:…]]` links to a whole file resolve to its outline) and
    // secondary indices on notebooks.orgId / notes.orgId / notes.customId so the
    // link lookups probe an index instead of scanning. Destructive migration
    // drops the (rebuildable) index; the next sync repopulates it from the .org
    // files, no file data touched.
    // v10: NotebookEntity.fileName / NoteEntity.fileName / notes_fts.fileName are
    // now vault-relative paths ("projects/acme.org"), not bare names, since the
    // vault can contain subfolders. No column change; destructive migration just
    // re-syncs the (rebuildable) index from the .org files under the new keys.
    // v9: added ReminderEntity.leadTime (Settings › Reminders › "Notify me" lead
    // time, baked into each row at scheduling time);
    // v8: added ReminderEntity.hasExplicitTime (date-only reminders now bundle into
    // the daily digest notification instead of firing individually);
    // v7: added the notes_fts FTS5 mirror, secondary indices on notes, and full
    // (no longer 4000-char-capped) note bodies;
    // v6: added ReminderEntity (SCHEDULED/DEADLINE notification scheduling state);
    // v5: added NotebookEntity.isIndexed (stub vs fully-parsed notebook rows);
    // v4: added NotebookEntity.title (cached #+TITLE: preamble value). Destructive
    // migration drops the index so the next sync rebuilds it from the .org files.
    version = 17,
    exportSchema = false,
)
abstract class GroveDatabase : RoomDatabase() {
    abstract fun indexDao(): IndexDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun reminderDao(): ReminderDao

    /**
     * Whether the [NotesFts] virtual table exists and can be used. False only on
     * a device whose SQLite lacks FTS5, where everything falls back to the full
     * in-memory scan: slower, but identical results.
     */
    val ftsAvailable: Boolean get() = indexDao().ftsAvailable

    companion object {
        fun build(context: Context): GroveDatabase =
            create(Room.databaseBuilder(context, GroveDatabase::class.java, "grove-index.db"))

        /**
         * Throwaway in-memory instance wired through the same [create] path, so
         * the instrumented tests exercise the real FTS bootstrap rather than a
         * hand-rolled copy of it that could drift.
         *
         * [queryCoroutineContext] defaults to [Dispatchers.IO] as in production;
         * a JVM (Robolectric) test passes its `TestDispatcher` so DAO queries
         * run in the test's virtual time and `advanceUntilIdle()` is reliable.
         */
        fun inMemory(
            context: Context,
            queryCoroutineContext: CoroutineContext = Dispatchers.IO,
        ): GroveDatabase =
            create(Room.inMemoryDatabaseBuilder(context, GroveDatabase::class.java), queryCoroutineContext)

        private fun create(
            builder: RoomDatabase.Builder<GroveDatabase>,
            queryCoroutineContext: CoroutineContext = Dispatchers.IO,
        ): GroveDatabase {
            // The callback only fires on first database access, which is
            // necessarily after build() returns, so `database` is always
            // assigned by the time either override runs.
            lateinit var database: GroveDatabase
            val ftsBootstrap = object : RoomDatabase.Callback() {
                override fun onCreate(connection: SQLiteConnection) {
                    database.indexDao().ftsAvailable = NotesFts.create(connection)
                }

                // Also on open: the table lives outside Room's schema, so nothing
                // else would recreate it if a destructive migration or a manual
                // wipe removed it out from under us.
                override fun onOpen(connection: SQLiteConnection) {
                    database.indexDao().ftsAvailable = NotesFts.create(connection)
                }
            }
            database = builder
                // Android's platform SQLite is built without the FTS5 module, so
                // the bundled SQLite ships one that has it (plus the trigram
                // tokenizer the substring semantics depend on).
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(queryCoroutineContext)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(ftsBootstrap)
                .build()
            return database
        }
    }
}
