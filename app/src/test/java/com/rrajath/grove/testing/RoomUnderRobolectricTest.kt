package com.rrajath.grove.testing

import android.app.Application
import com.rrajath.grove.data.GroveDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the M3 DB unblock: `BundledSQLiteDriver` (via the desktop
 * `sqlite-bundled-jvm` natives on the test runtime classpath) loads under
 * Robolectric, FTS5 + the trigram tokenizer are compiled in, and DAO queries
 * run in the test's virtual time when [GroveDatabase.inMemory] is handed a
 * `TestDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RoomUnderRobolectricTest {

    @Test
    fun `in-memory GroveDatabase reports FTS5 available on the bundled JVM SQLite`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = dispatcher)
        try {
            db.indexDao().notebooks() // forces the connection open + FTS bootstrap
            advanceUntilIdle()
            assertTrue("FTS5 trigram index should be usable", db.ftsAvailable)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the seeder indexes fixture files into the notes table`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeFileStore(OrgFixtures.all)
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = dispatcher)
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()

            val notebooks = db.indexDao().notebooks()
            assertEquals(OrgFixtures.all.keys.sorted(), notebooks.map { it.fileName }.sorted())
            // projects.org has two level-1 headings (Ship v2 release, Backlog).
            assertEquals(2, notebooks.single { it.fileName == "projects.org" }.noteCount)
        } finally {
            db.close()
        }
    }
}
