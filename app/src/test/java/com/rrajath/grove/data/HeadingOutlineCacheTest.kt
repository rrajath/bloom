package com.rrajath.grove.data

import android.app.Application
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.ui.editor.linkSearchIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HeadingOutlineCache] serves repeat picker opens from one snapshot, and the
 * Room invalidation observer drops it once `notes`/`notebooks` change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeadingOutlineCacheTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val db = InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)

    private val store = FakeFileStore(mapOf("work.org" to "* Projects\n** Acme\n"))

    @After
    fun tearDown() = db.close()

    private fun HeadingOutlineCache.Snapshot.titles() = headings.map { it.title }

    @Test
    fun `repeat reads reuse the snapshot and its built index`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()

        val first = db.headingOutlines.snapshot()
        val second = db.headingOutlines.snapshot()

        assertSame(first, second)
        assertSame(first.linkSearchIndex(), second.linkSearchIndex())
        assertEquals(listOf("Projects", "Acme"), first.titles())
    }

    @Test
    fun `an index write invalidates the snapshot`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val before = db.headingOutlines.snapshot()

        store.write("work.org", "* Projects\n** Acme\n** Globex\n")
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val after = db.headingOutlines.snapshot()

        assertNotSame(before, after)
        assertEquals(listOf("Projects", "Acme", "Globex"), after.titles())
        assertEquals(4, after.linkSearchIndex().size) // one file hit + three heading hits
    }

    @Test
    fun `explicit invalidate forces a reload`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val before = db.headingOutlines.snapshot()

        db.headingOutlines.invalidate()

        assertNotSame(before, db.headingOutlines.snapshot())
    }
}
