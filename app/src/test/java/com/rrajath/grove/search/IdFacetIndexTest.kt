package com.rrajath.grove.search

import android.app.Application
import androidx.room.useReaderConnection
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `notes.orgId` / `notes.customId` are NOCASE so the `id.` facet's
 * case-insensitive `LIKE 'prefix%'` probes their indexes instead of scanning
 * every note (10k+ notes in real vaults). Checked against the real bundled
 * SQLite: the plan, the case-insensitive results through the multi-index OR
 * (see [SearchCandidatesIndexedCaseTest] for why results must be checked
 * too), and that exact `[[id:…]]` / `[[#…]]` lookups stay case-sensitive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IdFacetIndexTest {

    private val store = FakeFileStore(
        mapOf(
            "Work.org" to """
                * Upper id
                :PROPERTIES:
                :ID: AB12-UPPER
                :END:
                * Lower id
                :PROPERTIES:
                :ID: ab34-lower
                :END:
                * Intro capital
                :PROPERTIES:
                :CUSTOM_ID: Intro
                :END:
                * Intro small
                :PROPERTIES:
                :CUSTOM_ID: intro
                :END:
                * No ids
            """.trimIndent() + "\n",
        ),
    )

    private suspend fun titles(db: GroveDatabase, raw: String): List<String> =
        loadSearchCandidates(db, QueryParser.parse(raw)).map { it.title }.sorted()

    private suspend fun plan(db: GroveDatabase, sql: String, args: List<String>): String =
        db.useReaderConnection { conn ->
            conn.usePrepared("EXPLAIN QUERY PLAN $sql") { stmt ->
                args.forEachIndexed { i, a -> stmt.bindText(i + 1, a) }
                buildString { while (stmt.step()) appendLine(stmt.getText(3)) }
            }
        }

    @Test
    fun `id prefix search uses the indexes`() = runTest {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()
            val q = NoteCandidateQuery.build(match = null, query = QueryParser.parse("id.ab"))
            val p = plan(db, q.sql, q.args)
            assertTrue(p, "orgId>?" in p && "customId>?" in p)
            assertTrue(p, "SCAN notes" !in p)
        } finally {
            db.close()
        }
    }

    @Test
    fun `id prefix search matches either case`() = runTest {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()
            assertEquals(listOf("Lower id", "Upper id"), titles(db, "id.ab"))
            assertEquals(listOf("Lower id", "Upper id"), titles(db, "id.AB"))
            assertEquals(listOf("Intro capital", "Intro small"), titles(db, "id.INTRO"))
            assertEquals(listOf("Upper id"), titles(db, "id.ab12"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `exact id lookups stay case-sensitive and indexed`() = runTest {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        try {
            TestVaultSeeder.index(db, store)
            advanceUntilIdle()
            val dao = db.indexDao()
            val small = dao.noteLocationByCustomId("intro")
            val capital = dao.noteLocationByCustomId("Intro")
            assertTrue(small != null && capital != null && small != capital)
            assertNull(dao.noteLocationByCustomId("INTRO"))
            assertEquals(dao.noteLocationByOrgId("AB12-UPPER")?.fileName, "Work.org")
            assertNull(dao.noteLocationByOrgId("ab12-upper"))
            assertEquals(listOf("ab34-lower"), dao.filesHoldingOrgIds(listOf("ab34-lower", "AB34-LOWER")).map { it.orgId })

            val p = plan(db, "SELECT fileName, lineIndex FROM notes WHERE customId = ? AND customId = ? COLLATE BINARY LIMIT 1", listOf("x", "x"))
            assertTrue(p, "USING INDEX" in p && "customId=?" in p)
        } finally {
            db.close()
        }
    }
}
