package com.rrajath.grove.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FavoriteNoteRebaseTest {

    private val inFile = FavoriteNote("work/a.org", 3, "A", customId = "ID-A")
    private val inSub = FavoriteNote("work/sub/b.org", 5, "B", customId = "ID-B")
    private val elsewhere = FavoriteNote("workshop/c.org", 1, "C")
    private val favs = listOf(inFile, inSub, elsewhere)

    @Test
    fun `file rename re-points only that file's favorites`() {
        val out = favs.rebasedFile("work/a.org", "work/renamed.org")
        assertEquals(listOf("work/renamed.org", "work/sub/b.org", "workshop/c.org"), out.map { it.fileName })
        assertEquals(inFile.copy(fileName = "work/renamed.org"), out[0])
    }

    @Test
    fun `file move keeps line index, title and id`() {
        val out = favs.rebasedFile("work/a.org", "a.org")
        assertEquals(FavoriteNote("a.org", 3, "A", "ID-A"), out[0])
    }

    @Test
    fun `unchanged rebase returns the same instance`() {
        assertSame(favs, favs.rebasedFile("nope.org", "other.org"))
        assertSame(favs, favs.rebasedFolder("nope", "other"))
    }

    @Test
    fun `folder rename re-bases descendants but not sibling prefixes`() {
        val out = favs.rebasedFolder("work", "job")
        assertEquals(listOf("job/a.org", "job/sub/b.org", "workshop/c.org"), out.map { it.fileName })
    }

    @Test
    fun `relocate heals a missing file by id and leaves the rest`() = runBlocking {
        val noId = FavoriteNote("gone.org", 2, "No id")
        val lost = FavoriteNote("gone.org", 4, "Lost", customId = "ID-LOST")
        val list = listOf(inFile, noId, lost, inSub.copy(fileName = "old/b.org"))
        val out = list.relocatedMissing(setOf("work/a.org", "new/b.org")) { id ->
            if (id == "ID-B") NoteKey("new/b.org", 9) else null
        }
        assertEquals(inFile, out[0])
        assertEquals(noId, out[1])
        assertEquals(lost, out[2])
        assertEquals(FavoriteNote("new/b.org", 9, "B", "ID-B"), out[3])
    }

    @Test
    fun `relocate with nothing missing returns the same instance`() = runBlocking {
        assertSame(favs, favs.relocatedMissing(favs.map { it.fileName }.toSet()) { error("not called") })
    }
}
