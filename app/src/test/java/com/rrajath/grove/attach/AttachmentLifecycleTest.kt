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

class AttachmentLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Index stand-in: id -> files holding it. */
    private val index = mutableMapOf<String, Set<String>>()

    private val lifecycle by lazy {
        AttachmentLifecycle(
            JvmFileStore(tmp.root, skipAttachShards = true),
            IdHolders { ids -> index.filterKeys { it in ids } },
        )
    }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun exists(path: String) = File(tmp.root, path).exists()
    private fun read(path: String) = File(tmp.root, path).readText()

    private fun heading(title: String, id: String) = "* $title\n:PROPERTIES:\n:ID: $id\n:END:\nbody\n"

    // IDs used throughout: uuid-style, so the dir is <2 chars>/<rest>.
    private val a = "AA11"
    private val b = "BB22"
    private val c = "CC33"

    // --- inventory ---

    @Test
    fun `file inventory lists existing ID dirs with sizes and DIR owners`() = runTest {
        put("Notes/data/AA/11/a.jpg", "12345")
        put("Notes/data/AA/11/b.pdf", "123")
        put("Notes/data/BB/22/c.txt", "1")
        val text = heading("A", a) + heading("B", b) + heading("C", c) +
            "* D\n:PROPERTIES:\n:DIR: ../Photos/kyoto\n:END:\n"
        val inv = lifecycle.inventoryForFile("Notes/travel.org", OrgParser.parse(text))
        assertEquals(listOf("Notes/data/AA/11", "Notes/data/BB/22"), inv.dirs.map { it.dir })
        assertEquals(3, inv.files)
        assertEquals(9L, inv.bytes)
        assertEquals(listOf("../Photos/kyoto"), inv.dirProperties)
        assertEquals(0, inv.sharedFolders)
    }

    @Test
    fun `an ID held by another file in the same directory is shared, one elsewhere is not`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        put("Notes/data/BB/22/b.jpg")
        index[a] = setOf("Notes/travel.org", "Notes/other.org")
        index[b] = setOf("Notes/travel.org", "Archive/other.org")
        val inv = lifecycle.inventoryForFile("Notes/travel.org", OrgParser.parse(heading("A", a) + heading("B", b)))
        assertEquals(mapOf(a to true, b to false), inv.dirs.associate { it.id to it.shared })
    }

    @Test
    fun `subtree inventory covers children and marks IDs also held outside the subtree`() = runTest {
        put("data/AA/11/a.jpg")
        put("data/BB/22/b.jpg")
        put("data/CC/33/c.jpg")
        val text = heading("Parent", a) +
            "** Child\n:PROPERTIES:\n:ID: $b\n:END:\n" +
            heading("Other", c) +
            "* Copy\n:PROPERTIES:\n:ID: $b\n:END:\n"
        val doc = OrgParser.parse(text)
        val inv = lifecycle.inventoryForSubtrees("n.org", doc, setOf(0))
        assertEquals(mapOf(a to false, b to true), inv.dirs.associate { it.id to it.shared })
    }

    // --- notebook move ---

    @Test
    fun `notebook move relocates dirs, merges with collisions, copies shared ones, prunes`() = runTest {
        put("Notes/data/AA/11/a.jpg", "mine")
        put("Notes/data/BB/22/b.jpg", "shared")
        put("Archive/data/AA/11/a.jpg", "theirs")
        index[b] = setOf("Notes/travel.org", "Notes/other.org")
        val doc = OrgParser.parse(heading("A", a) + heading("B", b))
        val inv = lifecycle.inventoryForFile("Notes/travel.org", doc)
        put("Archive/travel.org", doc.text) // the .org itself already moved

        val r = lifecycle.moveNotebookAttachments("Notes/travel.org", "Archive/travel.org", inv)

        assertEquals(LifecycleResult(moved = 1, copied = 1), r)
        assertEquals("theirs", read("Archive/data/AA/11/a.jpg"))
        assertEquals("mine", read("Archive/data/AA/11/a-1.jpg"))
        assertEquals("shared", read("Archive/data/BB/22/b.jpg"))
        assertEquals("shared", read("Notes/data/BB/22/b.jpg"))
        assertFalse(exists("Notes/data/AA"))
    }

    @Test
    fun `notebook move from the root prunes the emptied data dir`() = runTest {
        put("data/AA/11/a.jpg")
        val doc = OrgParser.parse(heading("A", a))
        val inv = lifecycle.inventoryForFile("travel.org", doc)
        val r = lifecycle.moveNotebookAttachments("travel.org", "Trips/travel.org", inv)
        assertEquals(1, r.moved)
        assertTrue(exists("Trips/data/AA/11/a.jpg"))
        assertFalse(exists("data"))
    }

    @Test
    fun `a dir that can't be moved stays in place and is counted`() = runTest {
        put("Notes/data/AA/11/a.jpg", "keep")
        put("Archive/data/AA", "a file where the shard dir should go")
        val doc = OrgParser.parse(heading("A", a))
        val inv = lifecycle.inventoryForFile("Notes/travel.org", doc)
        val r = lifecycle.moveNotebookAttachments("Notes/travel.org", "Archive/travel.org", inv)
        assertEquals(1, r.failed)
        assertEquals("keep", read("Notes/data/AA/11/a.jpg"))
    }

    // --- deletes ---

    @Test
    fun `deleteDirs removes unshared dirs, keeps shared and re-held ones, prunes`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        put("Notes/data/BB/22/b.jpg")
        put("Notes/data/CC/33/c.jpg")
        put("Notes/keep.org", "")
        index[b] = setOf("Notes/travel.org", "Notes/other.org")
        val doc = OrgParser.parse(heading("A", a) + heading("B", b) + heading("C", c))
        val inv = lifecycle.inventoryForFile("Notes/travel.org", doc)
        val r = lifecycle.deleteDirs("Notes/travel.org", inv) { id -> id == c }
        assertEquals(LifecycleResult(deleted = 1, kept = 2), r)
        assertFalse(exists("Notes/data/AA"))
        assertTrue(exists("Notes/data/BB/22/b.jpg"))
        assertTrue(exists("Notes/data/CC/33/c.jpg"))
        assertTrue(exists("Notes/keep.org"))
    }

    @Test
    fun `heldInDir ignores the file itself and files in other directories`() = runTest {
        index[a] = setOf("Notes/travel.org", "Archive/x.org")
        assertFalse(lifecycle.heldInDir("Notes/travel.org", a))
        index[a] = setOf("Notes/travel.org", "Notes/x.org")
        assertTrue(lifecycle.heldInDir("Notes/travel.org", a))
    }

    // --- refile / archive / undo ---

    @Test
    fun `a subtree refiled into another directory takes its dir along, and UNDO brings it back`() = runTest {
        put("Notes/data/AA/11/a.jpg", "img")
        val src = heading("Keep", b) + heading("Move", a)
        val srcAfter = heading("Keep", b)
        val dst = "* Inbox\n"
        val dstAfter = dst + heading("Move", a)

        val forward = lifecycle.followMovedOwners(
            listOf(FileRewrite("Notes/a.org", src, srcAfter), FileRewrite("Archive/b.org", dst, dstAfter)),
        )
        assertEquals(1, forward.moved)
        assertEquals("img", read("Archive/data/AA/11/a.jpg"))
        assertFalse(exists("Notes/data"))

        val back = lifecycle.followMovedOwners(
            listOf(FileRewrite("Notes/a.org", srcAfter, src), FileRewrite("Archive/b.org", dstAfter, dst)),
        )
        assertEquals(1, back.moved)
        assertEquals("img", read("Notes/data/AA/11/a.jpg"))
        assertFalse(exists("Archive/data"))
    }

    @Test
    fun `refile within one directory leaves dirs alone`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        val r = lifecycle.followMovedOwners(
            listOf(FileRewrite("Notes/a.org", heading("M", a), ""), FileRewrite("Notes/b.org", "", heading("M", a))),
        )
        assertEquals(LifecycleResult.None, r)
        assertTrue(exists("Notes/data/AA/11/a.jpg"))
    }

    @Test
    fun `an ID still in the source, or in a sibling file, is copied instead of moved`() = runTest {
        put("Notes/data/AA/11/a.jpg")
        val stillThere = lifecycle.followMovedOwners(
            listOf(
                FileRewrite("Notes/a.org", heading("M", a) + heading("Dup", a), heading("Dup", a)),
                FileRewrite("Archive/b.org", "", heading("M", a)),
            ),
        )
        assertEquals(1, stillThere.copied)
        assertTrue(exists("Notes/data/AA/11/a.jpg"))
        assertTrue(exists("Archive/data/AA/11/a.jpg"))

        put("Notes/data/BB/22/b.jpg")
        index[b] = setOf("Notes/a.org", "Notes/sibling.org")
        val sibling = lifecycle.followMovedOwners(
            listOf(FileRewrite("Notes/a.org", heading("M", b), ""), FileRewrite("Archive/b.org", "", heading("M", b))),
        )
        assertEquals(1, sibling.copied)
        assertTrue(exists("Notes/data/BB/22/b.jpg"))
    }

    @Test
    fun `an ID with no dir on disk is skipped`() = runTest {
        val r = lifecycle.followMovedOwners(
            listOf(FileRewrite("Notes/a.org", heading("M", a), ""), FileRewrite("Archive/b.org", "", heading("M", a))),
        )
        assertEquals(LifecycleResult.None, r)
    }

    // --- folders ---

    @Test
    fun `folder rename moves every data shard below it and keeps user folders in data`() = runTest {
        put("Work/data/AA/11/a.jpg", "a")
        put("Work/sub/data/__/B/BB22/b.jpg", "b")
        put("Work/data/notes/n.org", "* note")
        // Vault.renameFolder has already moved the .org files.
        put("Jobs/data/notes/n.org", "* note")
        File(tmp.root, "Work/data/notes/n.org").delete()

        assertTrue(lifecycle.folderHasAttachments("Work"))
        val r = lifecycle.moveFolderAttachments("Work", "Jobs")
        assertEquals(2, r.moved)
        assertEquals("a", read("Jobs/data/AA/11/a.jpg"))
        assertEquals("b", read("Jobs/sub/data/__/B/BB22/b.jpg"))
        assertFalse(exists("Work"))
    }

    @Test
    fun `folder delete removes the shards and prunes`() = runTest {
        put("Work/data/AA/11/a.jpg")
        put("Work/x/data/202611/02T1/b.jpg")
        put("Other/data/AA/11/keep.jpg")
        val r = lifecycle.deleteFolderAttachments("Work")
        assertEquals(2, r.deleted)
        assertFalse(exists("Work"))
        assertTrue(exists("Other/data/AA/11/keep.jpg"))
        assertFalse(lifecycle.folderHasAttachments("Other/missing"))
    }

    @Test
    fun `folderHasAttachments stops walking at the first attach root`() = runTest {
        put("Work/a/data/AA/11/a.jpg")
        put("Work/b/data/BB/22/b.jpg")
        put("Work/c/deep/x.org")
        val listed = mutableListOf<String>()
        val counting = object : com.rrajath.grove.vault.FileStore by JvmFileStore(tmp.root, skipAttachShards = true) {
            override suspend fun listSubdirs(dir: String): List<String> {
                listed += dir
                return JvmFileStore(tmp.root, skipAttachShards = true).listSubdirs(dir)
            }
        }
        val walker = AttachmentLifecycle(counting, IdHolders.None)
        assertTrue(walker.folderHasAttachments("Work"))
        // Sorted walk: Work, Work/a, Work/a/data; nothing under b or c.
        assertEquals(listOf("Work", "Work/a", "Work/a/data"), listed)
    }

    // --- helpers ---

    @Test
    fun `orgIdsIn reads heading and file-level IDs`() {
        val text = ":PROPERTIES:\n:ID: FILE1\n:END:\n#+TITLE: t\n* H\n  :properties:\n  :id:   H1  \n  :END:\n:IDX: no\n"
        assertEquals(setOf("FILE1", "H1"), orgIdsIn(text))
    }

    @Test
    fun `sheet and toast copy`() {
        assertEquals("Notes/data/", dataDirLabel("Notes/travel.org"))
        assertEquals("data/", dataDirLabel("travel.org"))
        assertEquals("41 MB", formatTotalSize(41L * 1024 * 1024 + 1000))
        assertEquals("2.4 MB", formatTotalSize(2_500_000))
        val inv = AttachmentInventory(
            listOf(
                AttachDirEntry(a, "d/a", 20, 40L * 1024 * 1024, shared = false),
                AttachDirEntry(b, "d/b", 3, 1L * 1024 * 1024, shared = true),
            ),
        )
        assertEquals("2 folders · 23 files · 41 MB", inventoryDetail(inv))
        assertEquals("travel.org → Archive/ · 2 folders · 41 MB", moveSubline("travel.org", "Archive", inv))
        assertEquals(
            "Moved travel.org and 6 attachment folders to Archive/",
            notebookMoveMessage("travel.org", "Archive", LifecycleResult(moved = 4, copied = 2)),
        )
        assertEquals(
            "Moved travel.org; 1 attachment folder couldn't be moved",
            notebookMoveMessage("travel.org", "Archive", LifecycleResult(moved = 4, failed = 1)),
        )
    }
}
