package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N5: a `:DIR:` edit carries the files of descendants that inherit it. */
class AttachDirInheritedMovesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { JvmFileStore(tmp.root, skipAttachShards = true) }
    private val detector by lazy {
        AttachDirChangeDetector(AttachmentFiles(store), AttachConfig.Default, newId = { "CC33" })
    }
    private val lifecycle by lazy { AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default) }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun read(path: String) = File(tmp.root, path).readText()
    private fun exists(path: String) = File(tmp.root, path).exists()

    private fun heading(stars: String, title: String, link: String?, vararg props: Pair<String, String>): String {
        val drawer = if (props.isEmpty()) "" else
            ":PROPERTIES:\n" + props.joinToString("") { ":${it.first}: ${it.second}\n" } + ":END:\n"
        return "$stars $title\n$drawer" + (link?.let { "[[attachment:$it]]\n" } ?: "")
    }

    private suspend fun detect(before: String, after: String) =
        detector.detect("Notes/n.org", OrgParser.parse(before).attachOwners(), OrgParser.parse(after))

    @Test
    fun `changing a parent DIR carries a child's linked files, even when the dir is shared`() = runTest {
        put("Notes/photos/a.jpg", "a")
        put("Notes/photos/b.jpg", "b")
        // Other keeps using photos/, so only linked files go (as copies).
        val other = heading("*", "Other", null, "DIR" to "photos")
        val before = heading("*", "Trip", "a.jpg", "DIR" to "photos") + heading("**", "Day", "b.jpg") + other
        val after = heading("*", "Trip", "a.jpg", "DIR" to "kyoto") + heading("**", "Day", "b.jpg") + other
        val move = detect(before, after).single() as AttachDirChange.Move
        assertEquals("Trip", (move.owner as AttachOwner.Heading).title)
        assertEquals(listOf("Day"), move.carriedFor.map { (it as AttachOwner.Heading).title })
        assertEquals(listOf("a.jpg", "b.jpg"), move.linkedNames)
        assertTrue(move.copy)
        assertFalse(move.wholeDir)

        lifecycle.applyDirChanges(listOf(move))
        assertEquals("a", read("Notes/kyoto/a.jpg"))
        assertEquals("b", read("Notes/kyoto/b.jpg"))
    }

    @Test
    fun `a child with its own DIR is left alone`() = runTest {
        put("Notes/photos/a.jpg")
        put("Notes/mine/b.jpg")
        val child = heading("**", "Day", "b.jpg", "DIR" to "mine")
        val changes = detect(
            heading("*", "Trip", "a.jpg", "DIR" to "photos") + child,
            heading("*", "Trip", "a.jpg", "DIR" to "kyoto") + child,
        )
        val move = changes.single() as AttachDirChange.Move
        assertTrue(move.carriedFor.isEmpty())
        assertEquals(listOf("a.jpg"), move.linkedNames)
        assertEquals("Notes/photos", move.from)
    }

    @Test
    fun `adding a parent DIR moves a child's ID files there, folded into the parent's entry`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        put("Notes/data/BB/22/b.jpg", "b")
        val changes = detect(
            heading("*", "Trip", "a.jpg", "ID" to "AA11") + heading("**", "Day", "b.jpg", "ID" to "BB22"),
            heading("*", "Trip", "a.jpg", "ID" to "AA11", "DIR" to "photos") + heading("**", "Day", "b.jpg", "ID" to "BB22"),
        ).map { it as AttachDirChange.Move }
        assertEquals(listOf("Notes/data/AA/11", "Notes/data/BB/22"), changes.map { it.from })
        assertTrue(changes.all { it.to == "Notes/photos" })
        assertFalse(changes[0].folded)
        assertTrue(changes[1].folded)
        assertEquals(AttachDirChange.Kind.DIR_ADDED, changes[1].kind)
        assertEquals(listOf(changes[0]), changes.shownInNotice())

        lifecycle.applyDirChanges(changes)
        assertEquals("b", read("Notes/photos/b.jpg"))
        assertFalse(exists("Notes/data/BB/22/b.jpg"))
    }

    @Test
    fun `adding a parent DIR moves a child's files out of the file-level DIR`() = runTest {
        put("Notes/files/b.jpg", "b")
        put("Notes/files/intro.png")
        val file = ":PROPERTIES:\n:DIR: files\n:END:\n[[attachment:intro.png]]\n"
        val changes = detect(
            file + heading("*", "Trip", null, "ID" to "AA11") + heading("**", "Day", "b.jpg"),
            file + heading("*", "Trip", null, "ID" to "AA11", "DIR" to "photos") + heading("**", "Day", "b.jpg"),
        )
        val move = changes.single() as AttachDirChange.Move
        // Trip's own :ID: lost to the file's :DIR: before, so it was in files/ too and leads.
        assertEquals("Trip", (move.owner as AttachOwner.Heading).title)
        assertEquals(listOf("Day"), move.carriedFor.map { (it as AttachOwner.Heading).title })
        assertEquals(listOf("b.jpg"), move.linkedNames)
        assertEquals("Notes/files", move.from)
        assertEquals("Notes/photos", move.to)
        // The file itself still uses files/ (its intro links there).
        assertTrue(move.copy)
        assertFalse(move.folded)
    }

    @Test
    fun `removing a parent DIR sends each child to the folder it resolves to now`() = runTest {
        put("Notes/photos/a.jpg", "a")
        put("Notes/photos/b.jpg", "b")
        val changes = detect(
            heading("*", "Trip", "a.jpg", "ID" to "AA11", "DIR" to "photos") + heading("**", "Day", "b.jpg", "ID" to "BB22"),
            heading("*", "Trip", "a.jpg", "ID" to "AA11") + heading("**", "Day", "b.jpg", "ID" to "BB22"),
        ).map { it as AttachDirChange.Move }
        assertEquals(listOf("Notes/data/AA/11", "Notes/data/BB/22"), changes.map { it.to })
        assertTrue(changes.none { it.wholeDir || it.copy || it.folded })
        assertEquals(listOf("Day"), changes.shownInNotice().drop(1).map { (it.owner as AttachOwner.Heading).title })

        lifecycle.applyDirChanges(changes)
        assertEquals("a", read("Notes/data/AA/11/a.jpg"))
        assertEquals("b", read("Notes/data/BB/22/b.jpg"))
    }

    @Test
    fun `a child left with no folder gets a new ID and its files follow`() = runTest {
        put("Notes/photos/a.jpg", "a")
        put("Notes/photos/b.jpg", "b")
        val after = heading("*", "Trip", "a.jpg", "ID" to "AA11") + heading("**", "Day", "b.jpg")
        val changes = detect(
            heading("*", "Trip", "a.jpg", "ID" to "AA11", "DIR" to "photos") + heading("**", "Day", "b.jpg"),
            after,
        ).map { it as AttachDirChange.Move }
        val child = changes.single { it.newId != null }
        assertEquals("CC33", child.newId)
        assertEquals("Notes/data/CC/33", child.to)
        assertFalse(child.folded)

        val outcome = lifecycle.applyDirChanges(changes)
        assertEquals(listOf(child), outcome.newIds)
        assertEquals("b", read("Notes/data/CC/33/b.jpg"))

        val edit = applyMoveOutcomeText(OrgParser.parse(after), outcome)
        val doc = OrgParser.parse(edit.text)
        assertEquals("CC33", doc.ownerOf(doc.headlines[1]).id)
        assertEquals(listOf(5 to 3), edit.insertions)
    }

    @Test
    fun `a parent losing both DIR and ID is broken, but its child still gets an ID`() = runTest {
        put("Notes/photos/a.jpg")
        put("Notes/photos/b.jpg", "b")
        val changes = detect(
            heading("*", "Trip", "a.jpg", "DIR" to "photos") + heading("**", "Day", "b.jpg"),
            heading("*", "Trip", "a.jpg") + heading("**", "Day", "b.jpg"),
        )
        assertTrue(changes[0] is AttachDirChange.Broken)
        val move = changes[1] as AttachDirChange.Move
        assertEquals("CC33", move.newId)
        assertFalse("the broken parent's files stay put", move.wholeDir)
        lifecycle.applyDirChanges(changes)
        assertTrue(exists("Notes/photos/a.jpg"))
        assertEquals("b", read("Notes/data/CC/33/b.jpg"))
    }

    @Test
    fun `a child with no linked files gets no ID`() = runTest {
        put("Notes/photos/a.jpg")
        val changes = detect(
            heading("*", "Trip", "a.jpg", "ID" to "AA11", "DIR" to "photos") + heading("**", "Day", null),
            heading("*", "Trip", "a.jpg", "ID" to "AA11") + heading("**", "Day", null),
        ).map { it as AttachDirChange.Move }
        assertNull(changes.single().newId)
    }

    @Test
    fun `a clash rename also fixes a carried child's link`() = runTest {
        put("Notes/photos/a.jpg", "a")
        put("Notes/photos/b.jpg", "mine")
        put("Notes/kyoto/b.jpg", "theirs")
        val after = heading("*", "Trip", "a.jpg", "DIR" to "kyoto") + heading("**", "Day", "b.jpg")
        val changes = detect(heading("*", "Trip", "a.jpg", "DIR" to "photos") + heading("**", "Day", "b.jpg"), after)
        val outcome = lifecycle.applyDirChanges(changes)
        assertEquals(mapOf("b.jpg" to "b-1.jpg"), outcome.renames.single().second)
        assertEquals("mine", read("Notes/kyoto/b-1.jpg"))
        val text = applyMoveOutcomeText(OrgParser.parse(after), outcome).text
        assertTrue(text, text.contains("** Day\n[[attachment:b-1.jpg]]"))
        assertTrue(text.contains("[[attachment:a.jpg]]"))
    }
}
