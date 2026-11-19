package com.rrajath.grove.data

import android.app.Application
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.support.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A performance audit finding: the per-file FTS delete
 * now targets rowids via `notes_fts_map` instead of scanning the UNINDEXED
 * `fileName` column. These tests pin the invariant that has to hold for that to
 * be safe — the map stays exactly in step with `notes_fts`, and deleting one
 * file's rows never touches another's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotesFtsMapTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val db = InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val dao get() = db.indexDao()

    private val alpha = """
        * Alpha one
        the quick brown fox

        * Alpha two
        jumps over the lazy dog
    """.trimIndent() + "\n"

    private val beta = """
        * Beta one
        pack my box with five dozen liquor jugs
    """.trimIndent() + "\n"

    @After
    fun tearDown() = db.close()

    private suspend fun index(fileName: String, text: String) {
        RoomNoteIndex(db).indexNotebook(
            fileName = fileName,
            revision = "r-${text.hashCode()}",
            text = text,
            lastModified = 1L,
            conflictFileName = null,
        )
    }

    private suspend fun matchCount(term: String): Int =
        dao.noteKeysMatching(
            rawQuery(
                "SELECT fileName, lineIndex FROM notes_fts WHERE notes_fts MATCH ?",
                listOf("\"$term\""),
            )
        ).size

    @Test
    fun `map row count tracks the fts row count on first index`() = runTest {
        index("alpha.org", alpha)

        assertTrue("FTS should be available in tests", dao.ftsAvailable)
        assertEquals(dao.ftsRowCount(), dao.ftsMapRowCount())
        assertEquals(2, dao.ftsRowCount())
    }

    @Test
    fun `re-indexing a file leaves no orphan fts or map rows`() = runTest {
        index("alpha.org", alpha)
        index("alpha.org", "* Alpha only\nnow just one heading\n")

        assertEquals(1, dao.ftsRowCount())
        assertEquals(1, dao.ftsMapRowCount())
        // The replaced content is gone from the index, the new content is in.
        assertEquals(0, matchCount("brown"))
        assertEquals(1, matchCount("heading"))
    }

    @Test
    fun `deleting one file's rows does not touch another file`() = runTest {
        index("alpha.org", alpha)
        index("beta.org", beta)
        assertEquals(3, dao.ftsRowCount())
        assertEquals(3, dao.ftsMapRowCount())

        dao.removeNotebook("alpha.org")

        assertEquals(1, dao.ftsRowCount())
        assertEquals(1, dao.ftsMapRowCount())
        assertEquals(0, matchCount("fox"))
        assertEquals(1, matchCount("liquor"))
    }

    @Test
    fun `clearAll empties both the mirror and the map`() = runTest {
        index("alpha.org", alpha)
        index("beta.org", beta)

        dao.clearAll()

        assertEquals(0, dao.ftsRowCount())
        assertEquals(0, dao.ftsMapRowCount())
    }
}
