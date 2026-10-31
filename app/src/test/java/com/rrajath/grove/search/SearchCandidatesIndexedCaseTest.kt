package com.rrajath.grove.search

import android.app.Application
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [loadSearchCandidates] against the real indexed `notes` table, because
 * the bug it guards only shows up there: `keyword = ? COLLATE NOCASE` OR-ed
 * together was answered from the column's BINARY index and matched
 * case-sensitively, so the Agenda widget's "Open tasks" (`i.in-progress OR
 * i.todo`) loaded no candidates at all. [NoteCandidateQueryTest] only checks
 * the SQL text and can't see that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SearchCandidatesIndexedCaseTest {

    private val store = FakeFileStore(
        mapOf(
            "Work.org" to """
                * TODO [#A] Write report
                * IN-PROGRESS [#B] Review draft
                * DONE Ship it
                * Plain heading
            """.trimIndent() + "\n",
        ),
    )

    private suspend fun titles(raw: String, db: GroveDatabase): List<String> =
        loadSearchCandidates(db, QueryParser.parse(raw)).map { it.title }.sorted()

    @Test
    fun `lowercase state, priority and notebook terms match case-insensitively through the index`() = runTest {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()

            assertEquals(listOf("Review draft", "Write report"), titles("i.in-progress OR i.todo", db))
            assertEquals(listOf("Write report"), titles("i.todo", db))
            assertEquals(listOf("Review draft", "Write report"), titles("ps.a OR ps.b", db))
            assertEquals(4, titles("b.work", db).size)
            assertEquals(4, titles("b.work.org OR b.WORK", db).size)
        } finally {
            db.close()
        }
    }
}
