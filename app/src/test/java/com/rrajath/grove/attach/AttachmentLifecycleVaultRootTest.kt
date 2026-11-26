package com.rrajath.grove.attach

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

/** [AttachmentLifecycle] with a vault-root attachment folder (`/attach/`). */
class AttachmentLifecycleVaultRootTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Index stand-in: id -> files holding it. */
    private val index = mutableMapOf<String, Set<String>>()

    private fun lifecycle(idDir: String = "/attach/") = AttachmentLifecycle(
        JvmFileStore(tmp.root, skipAttachShards = true),
        IdHolders { ids -> index.filterKeys { it in ids } },
        AttachConfig(idDir = idDir),
    )

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun exists(path: String) = File(tmp.root, path).exists()

    private fun heading(title: String, id: String) = "* $title\n:PROPERTIES:\n:ID: $id\n:END:\nbody\n"

    private val a = "AA11"
    private val b = "BB22"

    @Test
    fun `inventory marks central dirs, shared by any file in the vault`() = runTest {
        put("attach/AA/11/a.jpg")
        put("attach/BB/22/b.jpg")
        put("Notes/data/CC/33/c.jpg")
        index[a] = setOf("Notes/travel.org", "Archive/elsewhere.org")
        index[b] = setOf("Notes/travel.org")
        val doc = OrgParser.parse(heading("A", a) + heading("B", b) + heading("C", "CC33"))
        val inv = lifecycle().inventoryForFile("Notes/travel.org", doc)

        assertEquals(
            mapOf("attach/AA/11" to true, "attach/BB/22" to true, "Notes/data/CC/33" to false),
            inv.dirs.associate { it.dir to it.central },
        )
        assertEquals(mapOf(a to true, b to false, "CC33" to false), inv.dirs.associate { it.id to it.shared })
        assertEquals(listOf("Notes/data/CC/33"), inv.movable().dirs.map { it.dir })
    }

    @Test
    fun `moving a notebook out of the vault root leaves the central folder alone`() = runTest {
        put("attach/AA/11/a.jpg")
        val doc = OrgParser.parse(heading("A", a))
        val lc = lifecycle()
        val inv = lc.inventoryForFile("travel.org", doc)
        put("Trips/travel.org", doc.text)

        // Even when handed the full inventory, central dirs are skipped.
        val r = lc.moveNotebookAttachments("travel.org", "Trips/travel.org", inv)

        assertEquals(LifecycleResult.None, r)
        assertTrue(exists("attach/AA/11/a.jpg"))
        assertFalse(exists("Trips/attach"))
    }

    @Test
    fun `a legacy data dir next to the note still moves with the notebook`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        val lc = lifecycle()
        val inv = lc.inventoryForFile("Notes/travel.org", OrgParser.parse(heading("A", a))).movable()
        val r = lc.moveNotebookAttachments("Notes/travel.org", "Archive/travel.org", inv)
        assertEquals(1, r.moved)
        assertTrue(exists("Archive/data/AA/11/a.jpg"))
    }

    @Test
    fun `refiling from a root file to another folder leaves the central folder alone`() = runTest {
        put("attach/AA/11/a.jpg")
        val r = lifecycle().followMovedOwners(
            listOf(FileRewrite("inbox.org", heading("M", a), ""), FileRewrite("Archive/b.org", "", heading("M", a))),
        )
        assertEquals(LifecycleResult.None, r)
        assertTrue(exists("attach/AA/11/a.jpg"))
        assertFalse(exists("Archive/attach"))
    }

    @Test
    fun `deleting keeps a central dir another file holds and prunes up to the central folder`() = runTest {
        put("attach/AA/11/a.jpg")
        put("attach/BB/22/b.jpg")
        put("attach/CC/33/keep.jpg")
        val lc = lifecycle()
        val inv = lc.inventoryForFile("Notes/travel.org", OrgParser.parse(heading("A", a) + heading("B", b)))
        // Another file picked up A's ID after the inventory ran.
        index[a] = setOf("Archive/copy.org")

        val r = lc.deleteDirs("Notes/travel.org", inv) { false }

        assertEquals(LifecycleResult(deleted = 1, kept = 1), r)
        assertTrue(exists("attach/AA/11/a.jpg"))
        assertFalse(exists("attach/BB"))
        assertTrue(exists("attach/CC/33/keep.jpg"))
    }

    @Test
    fun `the central folder survives when its last dir is deleted`() = runTest {
        put("attach/BB/22/b.jpg")
        val lc = lifecycle()
        val inv = lc.inventoryForFile("travel.org", OrgParser.parse(heading("B", b)))
        lc.deleteDirs("travel.org", inv) { false }
        assertFalse(exists("attach/BB"))
        assertTrue(exists("attach"))
    }

    @Test
    fun `folder ops skip a central folder inside the folder but handle its data dirs`() = runTest {
        put("Work/attach/AA/11/a.jpg")
        put("Work/data/BB/22/b.jpg")
        val lc = lifecycle("/Work/attach/")
        val r = lc.deleteFolderAttachments("Work")
        assertEquals(1, r.deleted)
        assertTrue(exists("Work/attach/AA/11/a.jpg"))
        assertFalse(exists("Work/data"))
    }

    @Test
    fun `a same-named folder away from the vault root is not an attach root`() = runTest {
        put("Work/attach/AA/11/a.jpg")
        assertFalse(lifecycle().folderHasAttachments("Work"))
        put("Work/data/BB/22/b.jpg")
        assertTrue(lifecycle().folderHasAttachments("Work"))
    }

    // --- switching to a vault-root folder: find and merge per-note folders ---

    private fun read(path: String) = File(tmp.root, path).readText()

    @Test
    fun `perNoteRoots finds the old folder next to notes, not the new central one`() = runTest {
        put("attach/AA/11/root.jpg", "12")
        put("projects/attach/BB/22/b.jpg", "123")
        put("deep/x/attach/__/C/CC33/c.txt", "1")
        put("projects/attachments/DD/44/d.jpg")
        put("notes/attach/readme.org", "* not an attach root")
        val found = lifecycle("attach/").perNoteRoots(exclude = "attach")
        assertEquals(setOf("projects/attach", "deep/x/attach"), found.roots.toSet())
        assertEquals(2, found.files)
        assertEquals(4L, found.bytes)
    }

    @Test
    fun `perNoteRoots matches a nested folder as a trailing path`() = runTest {
        put("Work/media/files/AA/11/a.jpg")
        put("Work/files/BB/22/b.jpg")
        assertEquals(listOf("Work/media/files"), lifecycle("media/files/").perNoteRoots(exclude = "media/files").roots)
    }

    @Test
    fun `perNoteRoots is empty when the folder is already at the vault root`() = runTest {
        put("projects/attach/BB/22/b.jpg")
        assertTrue(lifecycle("/attach/").perNoteRoots(exclude = "attach").isEmpty)
    }

    @Test
    fun `mergeRootsInto moves shards, dedupes identical files and leaves conflicting ones`() = runTest {
        put("projects/attach/AA/11/new.jpg", "new")
        put("projects/attach/BB/22/same.jpg", "same")
        put("projects/attach/BB/22/clash.jpg", "mine")
        put("projects/attach/BB/22/extra.jpg", "extra")
        put("projects/attach/notes/keep.org", "* user folder")
        put("deep/attach/CC/33/c.jpg", "c")
        put("attach/BB/22/same.jpg", "same")
        put("attach/BB/22/clash.jpg", "theirs")

        val r = lifecycle().mergeRootsInto(listOf("projects/attach", "deep/attach"), "attach")

        assertEquals(RootMergeResult(left = 1), r)
        assertEquals("new", read("attach/AA/11/new.jpg"))
        assertEquals("same", read("attach/BB/22/same.jpg"))
        assertEquals("theirs", read("attach/BB/22/clash.jpg"))
        assertEquals("extra", read("attach/BB/22/extra.jpg"))
        assertEquals("c", read("attach/CC/33/c.jpg"))
        // The conflicting file stays where it was, with its folders.
        assertEquals("mine", read("projects/attach/BB/22/clash.jpg"))
        assertFalse(exists("projects/attach/BB/22/same.jpg"))
        assertFalse(exists("projects/attach/AA"))
        assertTrue(exists("projects/attach/notes/keep.org"))
        // An emptied root goes away.
        assertFalse(exists("deep/attach"))
    }
}
