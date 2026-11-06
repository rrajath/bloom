package com.rrajath.grove.data

import android.app.Application
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [IndexDao.attachOwners] lists every indexed `:ID:` holder, for the attach sheet's Vault scope. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IndexDaoAttachOwnersTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val db = InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)

    private val vault = """
        :PROPERTIES:
        :ID: F1
        :END:
        #+TITLE: A

        * One
        :PROPERTIES:
        :ID: H1
        :END:
        * Two
        no id here
    """.trimIndent() + "\n"

    private val store = FakeFileStore(mapOf("a.org" to vault))

    @After
    fun tearDown() = db.close()

    @Test
    fun `heading and file-level IDs are both owners`() = runTest {
        TestVaultSeeder.index(db, store)

        assertEquals(
            setOf(IndexedAttachOwner("a.org", null, "F1"), IndexedAttachOwner("a.org", "One", "H1")),
            db.indexDao().attachOwners().toSet(),
        )
    }
}
