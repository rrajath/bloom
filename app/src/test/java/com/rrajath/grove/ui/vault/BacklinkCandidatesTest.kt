package com.rrajath.grove.ui.vault

import android.app.Application
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [notesWithBodyText] (the Backlinks candidate lookup) through the FTS trigram
 * index must return at least every row the old `LIKE` scan did, and fall back
 * to that scan when the index can't answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BacklinkCandidatesTest {

    private val store = FakeFileStore(
        mapOf(
            "a.org" to """
                * Target
                :PROPERTIES:
                :ID: 21F14F07-DF4A-4468-8197-CF8CEA5BFDF2
                :END:
                * Links it
                See [[id:21F14F07-DF4A-4468-8197-CF8CEA5BFDF2][the target]].
                * Lowercase link
                See [[id:21f14f07-df4a-4468-8197-cf8cea5bfdf2]].
                * Mentions project
                Notes about Project Phoenix today.
                * Mentions project lowercase
                the project phoenix roadmap
                * German mention
                Ein Gedanke über das Thema.
                * Odd characters
                Progress: 50% "done"_now and AI work.
                * Title only Project Phoenix
                Nothing in the body.
            """.trimIndent() + "\n",
        ),
    )

    private suspend fun TestScope.db(): GroveDatabase {
        val db = InMemoryGroveDatabase.create(queryCoroutineContext = StandardTestDispatcher(testScheduler))
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        assertTrue(db.indexDao().ftsAvailable)
        return db
    }

    private suspend fun GroveDatabase.viaIndex(text: String): Set<String> =
        indexDao().notesWithBodyText(text).map { it.title }.toSet()

    private suspend fun GroveDatabase.viaScan(text: String): Set<String> =
        indexDao().notesWithBodyContaining(escapeLikeNeedle(text)).map { it.title }.toSet()

    @Test
    fun `ascii needles return exactly what the scan did`() = runTest {
        val db = db()
        try {
            for (needle in listOf(
                "id:21F14F07-DF4A-4468-8197-CF8CEA5BFDF2",
                "Project Phoenix",
                "PROJECT phoenix",
                "50% \"done\"_now",
                "\"done\"",
                "missing entirely",
            )) {
                assertEquals(needle, db.viaScan(needle), db.viaIndex(needle))
            }
            assertEquals(
                setOf("Links it", "Lowercase link"),
                db.viaIndex("id:21F14F07-DF4A-4468-8197-CF8CEA5BFDF2"),
            )
            // Body only: a heading whose title (not body) says it isn't a candidate.
            assertEquals(setOf("Mentions project", "Mentions project lowercase"), db.viaIndex("Project Phoenix"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `non-ascii case is folded, which the scan missed`() = runTest {
        val db = db()
        try {
            // The body only says "über": LIKE folds ASCII case only, so "Über" missed it.
            assertEquals(emptySet<String>(), db.viaScan("Über"))
            assertEquals(setOf("German mention"), db.viaIndex("Über"))
            assertEquals(setOf("German mention"), db.viaIndex("ÜBER das"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `needles under three characters fall back to the scan`() = runTest {
        val db = db()
        try {
            assertEquals(setOf("Odd characters"), db.viaIndex("AI"))
            assertEquals(db.viaScan("ai"), db.viaIndex("ai"))
            assertEquals(db.viaScan("%"), db.viaIndex("%"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `without FTS5 every lookup is the scan`() = runTest {
        val db = db()
        try {
            db.indexDao().ftsAvailable = false
            for (needle in listOf("Project Phoenix", "id:21F14F07-DF4A-4468-8197-CF8CEA5BFDF2", "über")) {
                assertEquals(needle, db.viaScan(needle), db.viaIndex(needle))
            }
        } finally {
            db.close()
        }
    }
}
