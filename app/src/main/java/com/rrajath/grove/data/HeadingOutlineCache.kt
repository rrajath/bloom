package com.rrajath.grove.data

import androidx.room.InvalidationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide cache of every notebook row plus every heading outline row:
 * what the link picker, the refile/location pickers and the auto-link
 * suggester all build their vault-wide indexes from. Without it each picker
 * open re-ran [IndexDao.notebooks] + [IndexDao.allHeadingOutlines] and rebuilt
 * the same index, though the rows change only when the index does.
 *
 * Kept current by a Room [InvalidationTracker] observer on `notes` and
 * `notebooks` ([observer], registered in [GroveDatabase]'s builder). Room
 * delivers that notification asynchronously after a write commits, so a read
 * racing a write can briefly see the previous snapshot, the same staleness a
 * picker opened just before a reindex always had. Dropping the snapshot is
 * O(1), so a full reindex's per-file invalidations need no debounce: the next
 * read after the burst reloads once.
 */
class HeadingOutlineCache(private val dao: IndexDao) {

    /**
     * One consistent read of both tables. [derived] memoizes indexes built from
     * it, so a picker reopened with no index change reuses the built list too.
     */
    class Snapshot(
        val notebooks: List<NotebookEntity>,
        /** Ordered by (fileName, lineIndex), as [IndexDao.allHeadingOutlines] returns them. */
        val headings: List<NoteOutlineRow>,
    ) {
        private val memo = ConcurrentHashMap<Any, Any>()

        /** [build] once per [key] for this snapshot (two racing first calls may both build; one result wins). */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> derived(key: Any, build: (Snapshot) -> T): T =
            memo[key] as T? ?: build(this).let { memo.putIfAbsent(key, it) as T? ?: it }
    }

    @Volatile
    private var cached: Snapshot? = null

    // Bumped on every invalidation, so a load that overlapped one isn't cached.
    private val generation = AtomicLong()
    private val loadMutex = Mutex()

    val observer: InvalidationTracker.Observer = object : InvalidationTracker.Observer(TABLES) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }

    suspend fun snapshot(): Snapshot {
        cached?.let { return it }
        // Concurrent cold reads (e.g. two pickers' loaders) share one query.
        return loadMutex.withLock {
            cached ?: run {
                val gen = generation.get()
                val loaded = Snapshot(dao.notebooks(), dao.allHeadingOutlines())
                if (generation.get() == gen) cached = loaded
                loaded
            }
        }
    }

    private companion object {
        val TABLES = arrayOf("notes", "notebooks")
    }
}
