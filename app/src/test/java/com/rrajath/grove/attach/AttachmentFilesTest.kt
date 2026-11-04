package com.rrajath.grove.attach

import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { AttachmentFiles(JvmFileStore(tmp.root, skipAttachShards = true)) }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun read(path: String) = File(tmp.root, path).readText()

    @Test
    fun `exists answers for attach dirs, as the resolver's DirExists`() = runTest {
        put("data/3F/2504E0/a.jpg")
        assertTrue(files.exists("data/3F/2504E0"))
        assertFalse(files.exists("data/3F/nope"))
        assertEquals("data/3F/2504E0", AttachDirResolver(files).readDir("n.org", AttachOwner.File("3F2504E0", null)))
    }

    @Test
    fun `list hides partial files`() = runTest {
        put("data/3F/X/a.jpg")
        put("data/3F/X/b.jpg.part")
        assertEquals(listOf("data/3F/X/a.jpg"), files.list("data/3F/X").map { it.name })
    }

    @Test
    fun `takenNames lists once and reserves every name it claims`() = runTest {
        put("data/3F/X/a.jpg")
        val taken = files.takenNames("data/3F/X")
        put("data/3F/X/late.jpg") // Not re-listed: the batch is the only writer.
        assertEquals("a-1.jpg", taken.claim("a.jpg"))
        assertEquals("A-2.jpg", taken.claim("A.jpg"))
        assertEquals("late.jpg", taken.claim("late.jpg"))
    }

    @Test
    fun `uniqueName is case-insensitive, sanitizes, and counts partials as taken`() = runTest {
        put("data/3F/X/Photo.JPG")
        put("data/3F/X/scan.pdf.part")
        assertEquals("photo-1.jpg", files.uniqueName("data/3F/X", "photo.jpg"))
        assertEquals("scan-1.pdf", files.uniqueName("data/3F/X", "scan.pdf"))
        assertEquals("ab.png", files.uniqueName("data/3F/X", "[a/b].png"))
        assertEquals("new.txt", files.uniqueName("data/missing", "new.txt"))
    }

    @Test
    fun `copyFile streams bytes and refuses to clobber`() = runTest {
        put("data/3F/X/a.bin", "hello")
        assertTrue(files.copyFile("data/3F/X/a.bin", "data/AB/Y/a.bin"))
        assertEquals("hello", read("data/AB/Y/a.bin"))
        assertFalse(files.copyFile("data/3F/X/a.bin", "data/AB/Y/a.bin"))
    }

    @Test
    fun `copyFile cleans up when the source is missing`() = runTest {
        assertFalse(files.copyFile("data/3F/X/none.bin", "data/AB/Y/none.bin"))
        assertFalse(File(tmp.root, "data/AB/Y/none.bin").exists())
    }

    @Test
    fun `copyDir copies a tree and renames collisions`() = runTest {
        put("a/data/3F/X/1.jpg", "one")
        put("a/data/3F/X/sub/2.pdf", "two")
        put("b/data/3F/X/1.jpg", "existing")
        assertTrue(files.copyDir("a/data/3F/X", "b/data/3F/X"))
        assertEquals("existing", read("b/data/3F/X/1.jpg"))
        assertEquals("one", read("b/data/3F/X/1-1.jpg"))
        assertEquals("two", read("b/data/3F/X/sub/2.pdf"))
        assertEquals("one", read("a/data/3F/X/1.jpg"))
    }

    @Test
    fun `mergeDir moves the whole dir when the destination is free`() = runTest {
        put("a/data/3F/X/1.jpg", "one")
        assertTrue(files.mergeDir("a/data/3F/X", "b/data/3F/X"))
        assertEquals("one", read("b/data/3F/X/1.jpg"))
        assertFalse(File(tmp.root, "a/data/3F/X").exists())
    }

    @Test
    fun `mergeDir merges file by file into an existing dir`() = runTest {
        put("a/data/3F/X/1.jpg", "one")
        put("a/data/3F/X/sub/3.txt", "three")
        put("b/data/3F/X/1.jpg", "existing")
        put("b/data/3F/X/2.jpg", "two")
        assertTrue(files.mergeDir("a/data/3F/X", "b/data/3F/X"))
        assertEquals(
            listOf("1-1.jpg", "1.jpg", "2.jpg"),
            files.list("b/data/3F/X").map { it.name.substringAfterLast('/') },
        )
        assertEquals("one", read("b/data/3F/X/1-1.jpg"))
        assertEquals("three", read("b/data/3F/X/sub/3.txt"))
        assertFalse(File(tmp.root, "a/data/3F/X").exists())
    }

    @Test
    fun `sizeOf sums files recursively`() = runTest {
        put("data/3F/X/a", "12345")
        put("data/3F/X/sub/b", "123")
        assertEquals(AttachmentFiles.DirSize(8, 2), files.sizeOf("data/3F/X"))
        assertEquals(AttachmentFiles.DirSize(0, 0), files.sizeOf("data/none"))
    }

    @Test
    fun `pruneEmpty removes emptied shards and data up to the stop dir`() = runTest {
        File(tmp.root, "Notes/data/3F/X").mkdirs()
        put("Notes/data/AB/Y/keep.jpg")
        put("Notes/n.org")
        files.pruneEmpty("Notes/data/3F/X", stopAt = "Notes")
        assertFalse(File(tmp.root, "Notes/data/3F").exists())
        assertTrue(File(tmp.root, "Notes/data/AB/Y/keep.jpg").exists())

        files.delete("Notes/data/AB/Y/keep.jpg")
        files.pruneEmpty("Notes/data/AB/Y", stopAt = "Notes")
        assertFalse(File(tmp.root, "Notes/data").exists())
        assertTrue(File(tmp.root, "Notes/n.org").exists())
    }
}
