package com.rrajath.grove.vault

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JvmFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = JvmFileStore(tmp.root)

    @Test
    fun `list returns vault-relative paths for nested files`() = runTest {
        tmp.newFile("root.org").writeText("* R")
        tmp.newFolder("projects", "clients")
        tmp.root.resolve("projects/notes.org").writeText("* N")
        tmp.root.resolve("projects/clients/acme.org").writeText("* A")

        assertEquals(
            listOf("projects/clients/acme.org", "projects/notes.org", "root.org"),
            store().list().map { it.name },
        )
    }

    @Test
    fun `list skips dot-directories`() = runTest {
        tmp.newFile("keep.org").writeText("* K")
        tmp.newFolder(".stversions")
        tmp.root.resolve(".stversions/keep~1.org").writeText("* old copy")
        tmp.newFolder(".git")
        tmp.root.resolve(".git/config").writeText("[core]")

        assertEquals(listOf("keep.org"), store().list().map { it.name })
    }

    @Test
    fun `create makes intermediate directories`() = runTest {
        assertTrue(store().create("a/b/c.org"))
        assertTrue(tmp.root.resolve("a/b/c.org").exists())
        assertFalse(store().create("a/b/c.org"))
    }

    @Test
    fun `rename moves a file across directories`() = runTest {
        val s = store()
        s.create("inbox.org")
        s.write("inbox.org", "* Task")

        assertTrue(s.rename("inbox.org", "archive/2026/inbox.org"))
        assertFalse(tmp.root.resolve("inbox.org").exists())
        assertEquals("* Task", tmp.root.resolve("archive/2026/inbox.org").readText())
    }

    @Test
    fun `rename refuses to overwrite an existing target`() = runTest {
        val s = store()
        s.create("a/one.org")
        s.create("b/two.org")
        assertFalse(s.rename("a/one.org", "b/two.org"))
        assertTrue(tmp.root.resolve("a/one.org").exists())
    }

    @Test
    fun `read write stat exists delete resolve by path`() = runTest {
        val s = store()
        s.create("dir/note.org")
        s.write("dir/note.org", "hello")

        assertEquals("hello", s.read("dir/note.org"))
        assertEquals("hello".length.toLong(), s.stat("dir/note.org")!!.size)
        assertTrue(s.exists("dir/note.org"))
        assertNull(s.stat("dir/missing.org"))

        assertTrue(s.delete("dir/note.org"))
        assertFalse(s.exists("dir/note.org"))
    }

    @Test
    fun `pruneEmptyDirs removes empty ancestors but stops at the first non-empty one`() = runTest {
        val s = store()
        s.create("a/b/c/note.org")
        s.create("a/keep.org")

        assertTrue(s.delete("a/b/c/note.org"))
        s.pruneEmptyDirs("a/b/c")

        assertFalse(tmp.root.resolve("a/b").exists()) // b and c collapsed
        assertTrue(tmp.root.resolve("a").exists())    // a still has keep.org
    }

    @Test
    fun `pruneEmptyDirs collapses a parent whose only child was an empty subdir`() = runTest {
        val s = store()
        s.create("proj/sub/x.org")

        assertTrue(s.delete("proj/sub/x.org"))
        s.pruneEmptyDirs("proj")

        assertFalse(tmp.root.resolve("proj").exists())
    }

    @Test
    fun `resolve rejects parent traversal`() = runTest {
        val ex = runCatching { store().read("../escape.org") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `list skips a directory matched by a bare ignore pattern`() = runTest {
        tmp.newFile("keep.org")
        File(tmp.root, "archive").mkdirs()
        File(tmp.root, "archive/x.org").writeText("* X")

        val s = JvmFileStore(tmp.root, IgnorePatterns("archive"))
        assertEquals(listOf("keep.org"), s.list().map { it.name })
    }

    @Test
    fun `list skips a file matched by a glob pattern`() = runTest {
        tmp.newFile("notes.bak")
        tmp.newFile("notes.org")

        val s = JvmFileStore(tmp.root, IgnorePatterns("*.bak"))
        assertEquals(listOf("notes.org"), s.list().map { it.name })
    }

    @Test
    fun `list still applies isSkippedVaultDir alongside ignore patterns`() = runTest {
        tmp.newFile("keep.org")
        tmp.newFolder(".git")
        tmp.root.resolve(".git/config").writeText("[core]")

        val s = JvmFileStore(tmp.root, IgnorePatterns(""))
        assertEquals(listOf("keep.org"), s.list().map { it.name })
    }

    @Test
    fun `list skips attachment shards under data when enabled`() = runTest {
        listOf(
            "inbox.org", "data/loose.org", "data/notes/kept.org",
            "data/3F/2504E0/photo.jpg", "data/202611/02T1252/a.m4a", "data/__/a/ab/b.pdf",
            "Work/data/AB/CD/c.png", "Work/plan.org", "ab/top-level-two-char.org",
        ).forEach { tmp.root.resolve(it).apply { parentFile.mkdirs() }.writeText("x") }

        assertEquals(
            listOf("Work/plan.org", "ab/top-level-two-char.org", "data/loose.org", "data/notes/kept.org", "inbox.org"),
            JvmFileStore(tmp.root, skipAttachShards = true).list().map { it.name },
        )
        assertEquals(9, JvmFileStore(tmp.root, skipAttachShards = false).list().size)
    }

    @Test
    fun `raw apis reach into skipped shards`() = runTest {
        val s = JvmFileStore(tmp.root, skipAttachShards = true)
        val part = "data/3F/2504E0/photo.jpg.part"
        assertFalse(s.dirExists("data/3F"))
        assertTrue(s.createBinary(part))
        assertFalse(s.createBinary(part))
        s.openOutput(part).use { it.write(byteArrayOf(1, 2, 3)) }
        assertTrue(s.renameRaw(part, "photo.jpg"))
        assertFalse(s.renameRaw("data/3F/2504E0/missing.jpg", "x.jpg"))

        val final = "data/3F/2504E0/photo.jpg"
        assertEquals(3L, s.statRaw(final)?.size)
        assertNull(s.statRaw("data/3F/2504E0"))
        assertEquals(listOf(1, 2, 3), s.openInput(final).use { it.readBytes() }.map { it.toInt() })
        assertTrue(s.dirExists("data/3F/2504E0"))
        assertEquals(listOf("3F"), s.listSubdirs("data"))
        assertEquals(listOf(final), s.listDirRaw("data/3F/2504E0").map { it.name })
        assertNull(s.contentUri(final))
        assertTrue(s.list().isEmpty())

        assertTrue(s.moveDir("data/3F/2504E0", "Archive/data/3F"))
        assertFalse(s.dirExists("data/3F/2504E0"))
        assertEquals(3L, s.statRaw("Archive/data/3F/2504E0/photo.jpg")?.size)

        assertTrue(s.deleteRaw("Archive/data/3F/2504E0/photo.jpg"))
        assertFalse(s.deleteRaw("Archive/data/3F/2504E0/photo.jpg"))
        assertTrue(s.deleteDir("Archive/data"))
        assertFalse(s.dirExists("Archive/data"))
    }

    @Test
    fun `moveDir refuses an existing destination`() = runTest {
        val s = JvmFileStore(tmp.root)
        s.createBinary("a/data/3F/X/1.jpg")
        s.createBinary("b/data/3F/X/2.jpg")
        assertFalse(s.moveDir("a/data/3F/X", "b/data/3F"))
        assertTrue(s.dirExists("a/data/3F/X"))
    }
}
