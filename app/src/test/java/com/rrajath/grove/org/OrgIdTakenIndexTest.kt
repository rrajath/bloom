package com.rrajath.grove.org

import android.app.Application
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [com.rrajath.grove.data.IndexDao.orgIdTaken] against the real bundled SQLite, wired into [OrgIdAllocator]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OrgIdTakenIndexTest {

    private val store = FakeFileStore(
        mapOf(
            "Work.org" to """
                * Heading id
                :PROPERTIES:
                :ID: AB12-HEADING
                :END:
                * Custom id
                :PROPERTIES:
                :CUSTOM_ID: my-anchor
                :END:
            """.trimIndent() + "\n",
            "Node.org" to """
                :PROPERTIES:
                :ID: cd34-file
                :END:
                #+title: Node
            """.trimIndent() + "\n",
        ),
    )

    @Test
    fun `heading, custom and file-level ids are taken in any case`() = runTest {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()
            val dao = db.indexDao()
            assertTrue(dao.orgIdTaken("AB12-HEADING"))
            assertTrue(dao.orgIdTaken("ab12-heading"))
            assertTrue(dao.orgIdTaken("MY-ANCHOR"))
            assertTrue(dao.orgIdTaken("CD34-FILE"))
            assertFalse(dao.orgIdTaken("EF56-UNUSED"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `allocator skips an id that exists only in the vault`() = runTest {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()
            val candidates = ArrayDeque(listOf("CD34-FILE", "AB12-HEADING", "EF56-UNUSED"))
            val allocator = OrgIdAllocator(taken = { db.indexDao().orgIdTaken(it) }, generate = { candidates.removeFirst() })
            assertEquals("EF56-UNUSED", allocator.next())
        } finally {
            db.close()
        }
    }
}
