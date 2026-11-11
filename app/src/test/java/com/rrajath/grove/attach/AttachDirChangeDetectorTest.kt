package com.rrajath.grove.attach

import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachDirChangeDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { JvmFileStore(tmp.root, skipAttachShards = true) }
    private var indexed = emptyList<IndexedAttachOwner>()
    private val detector by lazy { AttachDirChangeDetector(AttachmentFiles(store), AttachConfig.Default, indexedOwners = { indexed }) }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun exists(path: String) = File(tmp.root, path).exists()

    private fun heading(title: String, vararg props: Pair<String, String>): String {
        val drawer = if (props.isEmpty()) "" else
            ":PROPERTIES:\n" + props.joinToString("") { ":${it.first}: ${it.second}\n" } + ":END:\n"
        return "* $title\n$drawer[[attachment:a.jpg]]\n"
    }

    private suspend fun detect(before: String, after: String, orgFile: String = "Notes/n.org") =
        detector.detect(orgFile, OrgParser.parse(before).attachOwners(), OrgParser.parse(after))

    @Test
    fun `adding DIR to a heading with ID files moves them to the DIR`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        val changes = detect(heading("Trip", "ID" to "AA11"), heading("Trip", "ID" to "AA11", "DIR" to "photos"))
        val move = changes.single() as AttachDirChange.Move
        assertEquals(AttachDirChange.Kind.DIR_ADDED, move.kind)
        assertEquals("Notes/data/AA/11", move.from)
        assertEquals("Notes/photos", move.to)
        assertEquals("photos", move.shownTarget())
        assertFalse(move.copy)
    }

    @Test
    fun `removing DIR moves the files back to the ID dir`() = runTest {
        put("Notes/photos/a.jpg")
        val move = detect(heading("Trip", "ID" to "AA11", "DIR" to "photos"), heading("Trip", "ID" to "AA11"))
            .single() as AttachDirChange.Move
        assertEquals(AttachDirChange.Kind.DIR_REMOVED, move.kind)
        assertEquals("Notes/photos", move.from)
        assertEquals("Notes/data/AA/11", move.to)
        assertEquals("data/AA/11", move.shownTarget())
    }

    @Test
    fun `changing DIR moves to the new DIR`() = runTest {
        put("Notes/photos/a.jpg")
        val move = detect(heading("Trip", "DIR" to "photos"), heading("Trip", "DIR" to "kyoto"))
            .single() as AttachDirChange.Move
        assertEquals(AttachDirChange.Kind.DIR_CHANGED, move.kind)
        assertEquals("Notes/kyoto", move.to)
    }

    @Test
    fun `removing DIR with no ID left is a broken link`() = runTest {
        put("Notes/photos/a.jpg")
        val change = detect(heading("Trip", "DIR" to "photos"), heading("Trip")).single()
        assertTrue(change is AttachDirChange.Broken)
        assertEquals("Notes/photos", change.from)
    }

    @Test
    fun `removing the ID of an ID-only owner is a broken link`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        val change = detect(heading("Trip", "ID" to "AA11"), heading("Trip")).single()
        assertTrue(change is AttachDirChange.Broken)
    }

    @Test
    fun `removing ID while DIR stays changes nothing`() = runTest {
        put("Notes/photos/a.jpg")
        assertTrue(detect(heading("Trip", "ID" to "AA11", "DIR" to "photos"), heading("Trip", "DIR" to "photos")).isEmpty())
    }

    @Test
    fun `no files in the old dir changes nothing`() = runTest {
        assertTrue(detect(heading("Trip", "ID" to "AA11"), heading("Trip", "ID" to "AA11", "DIR" to "photos")).isEmpty())
        assertTrue(detect(heading("Trip", "ID" to "AA11"), heading("Trip")).isEmpty())
    }

    @Test
    fun `file-level DIR added moves the file's ID dir`() = runTest {
        put("Notes/data/FF/22/a.jpg")
        val before = ":PROPERTIES:\n:ID: FF22\n:END:\nIntro\n"
        val after = ":PROPERTIES:\n:ID: FF22\n:DIR: files\n:END:\nIntro\n"
        val move = detect(before, after).single() as AttachDirChange.Move
        assertTrue(move.owner is AttachOwner.File)
        assertEquals("Notes/data/FF/22", move.from)
        assertEquals("Notes/files", move.to)
    }

    @Test
    fun `file-level DIR removed without ID is a broken link`() = runTest {
        put("Notes/files/a.jpg")
        val change = detect(":PROPERTIES:\n:DIR: files\n:END:\nIntro\n", "Intro\n").single()
        assertTrue(change is AttachDirChange.Broken)
        assertTrue(change.owner is AttachOwner.File)
    }

    @Test
    fun `a DIR another heading still uses is copied, not moved`() = runTest {
        put("Notes/photos/a.jpg")
        val other = heading("Other", "DIR" to "photos")
        val move = detect(heading("Trip", "DIR" to "photos") + other, heading("Trip", "DIR" to "kyoto") + other)
            .single() as AttachDirChange.Move
        assertTrue(move.copy)
    }

    @Test
    fun `a DIR an owner in another file uses is copied`() = runTest {
        put("Notes/photos/a.jpg")
        indexed = listOf(IndexedAttachOwner("Notes/other.org", "Elsewhere", orgId = null, dirProperty = "photos"))
        val move = detect(heading("Trip", "DIR" to "photos"), heading("Trip", "DIR" to "kyoto"))
            .single() as AttachDirChange.Move
        assertTrue(move.copy)
    }

    @Test
    fun `a DIR of dot (the notes folder) moves only the linked files`() = runTest {
        put("Notes/n.org")
        put("Notes/a.jpg", "jpg")
        put("Notes/other.png")
        val move = detect(heading("Trip", "DIR" to "."), heading("Trip", "DIR" to "photos")).single() as AttachDirChange.Move
        assertFalse(move.wholeDir)
        assertEquals(listOf("a.jpg"), move.linkedNames)
        val outcome = AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(listOf(move))
        assertEquals(0, outcome.failed)
        assertEquals("jpg", File(tmp.root, "Notes/photos/a.jpg").readText())
        assertFalse(exists("Notes/a.jpg"))
        assertTrue("the note and unlinked files stay", exists("Notes/n.org") && exists("Notes/other.png"))
    }

    @Test
    fun `moving into the notes folder itself works`() = runTest {
        put("Notes/data/AA/11/a.jpg", "jpg")
        val move = detect(heading("Trip", "ID" to "AA11"), heading("Trip", "ID" to "AA11", "DIR" to "."))
            .single() as AttachDirChange.Move
        assertEquals("Notes", move.to)
        AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(listOf(move))
        assertEquals("jpg", File(tmp.root, "Notes/a.jpg").readText())
        assertFalse(exists("Notes/data"))
    }

    @Test
    fun `a DIR moved into its own subfolder moves the linked files`() = runTest {
        put("Notes/photos/a.jpg", "jpg")
        val move = detect(heading("Trip", "DIR" to "photos"), heading("Trip", "DIR" to "photos/2026")).single() as AttachDirChange.Move
        assertFalse(move.wholeDir)
        AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(listOf(move))
        assertEquals("jpg", File(tmp.root, "Notes/photos/2026/a.jpg").readText())
        assertFalse(exists("Notes/photos/a.jpg"))
    }

    @Test
    fun `a clash with an identical file keeps it and needs no rename`() = runTest {
        put("Notes/data/AA/11/a.jpg", "same")
        put("Notes/photos/a.jpg", "same")
        val changes = detect(heading("Trip", "ID" to "AA11"), heading("Trip", "ID" to "AA11", "DIR" to "photos"))
        val outcome = AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(changes)
        assertTrue(outcome.renames.isEmpty())
        assertEquals("same", File(tmp.root, "Notes/photos/a.jpg").readText())
        assertFalse(exists("Notes/data/AA/11/a.jpg"))
    }

    @Test
    fun `a clash with a different file renames it and the link follows`() = runTest {
        put("Notes/data/AA/11/a.jpg", "mine")
        put("Notes/photos/a.jpg", "theirs")
        val after = heading("Trip", "ID" to "AA11", "DIR" to "photos")
        val changes = detect(heading("Trip", "ID" to "AA11"), after)
        val outcome = AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(changes)
        val (move, map) = outcome.renames.single()
        assertEquals(mapOf("a.jpg" to "a-1.jpg"), map)
        assertEquals("mine", File(tmp.root, "Notes/photos/a-1.jpg").readText())
        assertEquals("theirs", File(tmp.root, "Notes/photos/a.jpg").readText())

        val fixed = renameAttachmentLinks(OrgParser.parse(after), listOf(move.owner to map))
        assertTrue(fixed, fixed.contains("[[attachment:a-1.jpg]]"))
        assertFalse(fixed.contains("[[attachment:a.jpg]]"))
    }

    @Test
    fun `renaming links leaves other owners and descriptions intact`() {
        val text = "* Trip\n:PROPERTIES:\n:DIR: photos\n:END:\n[[attachment:a.jpg][A photo]] [[attachment:b.jpg]]\n" +
            "* Other\n:PROPERTIES:\n:DIR: x\n:END:\n[[attachment:a.jpg]]\n"
        val doc = OrgParser.parse(text)
        val trip = doc.ownerOf(doc.headlines.first())
        val fixed = renameAttachmentLinks(doc, listOf(trip to mapOf("a.jpg" to "a-1.jpg")))
        assertTrue(fixed.contains("[[attachment:a-1.jpg][A photo]] [[attachment:b.jpg]]"))
        assertTrue(fixed.endsWith("* Other\n:PROPERTIES:\n:DIR: x\n:END:\n[[attachment:a.jpg]]\n"))
    }

    @Test
    fun `an ID the attach just added counts`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        val baseline = withAttachedOwner(
            OrgParser.parse(heading("Trip")).attachOwners(),
            AttachOwner.Heading(0, listOf("Trip"), "AA11", null),
        )
        val changes = detector.detect("Notes/n.org", baseline, OrgParser.parse(heading("Trip", "ID" to "AA11", "DIR" to "photos")))
        assertEquals("Notes/photos", (changes.single() as AttachDirChange.Move).to)
    }

    @Test
    fun `applying a move carries the files over and prunes the old ID dir`() = runTest {
        put("Notes/n.org")
        put("Notes/data/AA/11/a.jpg", "jpg")
        val changes = detect(heading("Trip", "ID" to "AA11"), heading("Trip", "ID" to "AA11", "DIR" to "photos"))
        val result = AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(changes)
        assertEquals(0, result.failed)
        assertEquals("jpg", File(tmp.root, "Notes/photos/a.jpg").readText())
        assertFalse(exists("Notes/data"))
    }

    @Test
    fun `applying a copy leaves the shared dir in place`() = runTest {
        put("Notes/photos/a.jpg")
        val other = heading("Other", "DIR" to "photos")
        val changes = detect(heading("Trip", "DIR" to "photos") + other, heading("Trip", "DIR" to "kyoto") + other)
        val result = AttachmentLifecycle(store, IdHolders.None, AttachConfig.Default).applyDirChanges(changes)
        assertEquals(0, result.failed)
        assertTrue(exists("Notes/photos/a.jpg"))
        assertTrue(exists("Notes/kyoto/a.jpg"))
    }
}
