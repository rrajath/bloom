package com.rrajath.grove.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.rawQuery
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.support.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wired [SyncManager.requestSync] path: a [FakeFileStore] on "disk" and an
 * in-memory Room index, exercised through the real [SyncEngine] +
 * [com.rrajath.grove.data.RoomNoteIndex]. Complements [SyncEngineTest], which
 * tests the diff algorithm in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SyncManagerRoundTripTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = FakeFileStore(OrgFixtures.all)
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)

    @Volatile
    private var keywords = OrgKeywords.DEFAULT

    /** Every file (re)indexed during a sync, in order — reset between phases. */
    private val indexed = mutableListOf<String>()

    /** [SyncManager]'s home scope: shares the test scheduler (so `advanceUntilIdle`
     *  drives its coalescer + engine) but is not a child of the `runTest` job, so
     *  its infinite coalescer consumer does not keep `runTest` from completing. */
    private val syncScope = CoroutineScope(mainDispatcherRule.dispatcher)

    private fun manager() = SyncManager(
        context = app,
        scope = syncScope,
        database = db,
        keywords = { keywords },
        onNotebookIndexed = { fileName, _ -> indexed += fileName },
    )

    @After
    fun tearDown() {
        syncScope.cancel()
        db.close()
    }

    private suspend fun noteFileNames(): Set<String> =
        db.indexDao().notesMatching(rawQuery("SELECT * FROM notes")).map { it.fileName }.toSet()

    @Test
    fun `first sync indexes every file on disk into Room`() = runTest {
        val mgr = manager()
        mgr.attach(store)
        advanceUntilIdle()

        assertEquals(OrgFixtures.all.keys, db.indexDao().notebooks().map { it.fileName }.toSet())
        assertTrue(noteFileNames().contains("reading-list.org"))
        assertTrue(indexed.contains("reading-list.org"))
    }

    @Test
    fun `deleting a file on disk removes its rows on the next sync`() = runTest {
        val mgr = manager()
        mgr.attach(store)
        advanceUntilIdle()
        assertTrue(noteFileNames().contains("reading-list.org"))

        store.delete("reading-list.org")
        mgr.requestSync("file removed")
        advanceUntilIdle()

        assertFalse(noteFileNames().contains("reading-list.org"))
        assertFalse(db.indexDao().notebooks().map { it.fileName }.contains("reading-list.org"))
    }

    @Test
    fun `a sync with nothing changed on disk reindexes nothing`() = runTest {
        val mgr = manager()
        mgr.attach(store)
        advanceUntilIdle()
        assertTrue(indexed.isNotEmpty())

        indexed.clear()
        mgr.requestSync("no-op pass")
        advanceUntilIdle()

        assertTrue("unchanged revisions must not be re-indexed", indexed.isEmpty())
    }

    @Test
    fun `clearAndResync rebuilds the whole index from disk`() = runTest {
        val mgr = manager()
        mgr.attach(store)
        advanceUntilIdle()

        indexed.clear()
        keywords = OrgKeywords.parse("TODO NEXT | DONE")
        mgr.clearAndResync("todo keywords applied")
        advanceUntilIdle()

        // Every file is parsed again under the new keyword config.
        assertEquals(OrgFixtures.all.keys, indexed.toSet())
        assertEquals(OrgFixtures.all.keys, db.indexDao().notebooks().map { it.fileName }.toSet())
    }
}
