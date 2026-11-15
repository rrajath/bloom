package com.rrajath.grove.vault

import com.rrajath.grove.vault.storage.getOrNull
import com.rrajath.grove.vault.storage.isOk
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vault(ignore: IgnorePatterns = IgnorePatterns("")): Vault =
        Vault(JvmFileStore(tmp.root, ignore))

    @Test
    fun `lists only org files`() = runTest {
        tmp.newFile("a.org").writeText("* One\n** Sub\n*** Deeper\n* Two")
        tmp.newFile("b.org").writeText("* Single\n** Child")
        tmp.newFile("readme.txt").writeText("not org")

        val notebooks = vault().notebooks()
        assertEquals(listOf("a.org", "b.org"), notebooks.map { it.fileName })
        // Only top-level headings count as notes; subheadings belong to them.
        assertEquals(listOf(2, 1), notebooks.map { it.noteCount })
    }

    @Test
    fun `deleteNotebook removes the file for good`() = runTest {
        tmp.newFile("n.org").writeText("* Note")

        val v = vault()
        assertTrue(v.deleteNotebook("n.org").isOk)
        assertEquals(emptyList<String>(), v.notebooks().map { it.fileName })
        assertFalse(tmp.root.resolve("n.org").exists())
        // No `.trash` copy is left behind.
        assertTrue(tmp.root.list()!!.none { it.endsWith(".trash") })
    }

    @Test
    fun `deleteNotebook prunes the directory it empties`() = runTest {
        tmp.newFolder("sub", "dir")
        tmp.root.resolve("sub/dir/foo.org").writeText("* First")
        tmp.newFile("sub/keep.org").writeText("* Keep")
        val v = vault()

        assertTrue(v.deleteNotebook("sub/dir/foo.org").isOk)
        // `sub/dir` is gone; `sub` survives because `keep.org` still lives there.
        assertFalse(tmp.root.resolve("sub/dir").exists())
        assertTrue(tmp.root.resolve("sub/keep.org").exists())
    }

    @Test
    fun `only lists files the FileStore surfaces`() = runTest {
        tmp.newFile("keep.org").writeText("* K")
        tmp.newFile("archive.org").writeText("* A")

        val notebooks = vault(IgnorePatterns("archive*")).notebooks()
        assertEquals(listOf("keep.org"), notebooks.map { it.fileName })
    }

    @Test
    fun `open parses the document`() = runTest {
        tmp.newFile("n.org").writeText("* Heading :tag:\nBody")
        val doc = vault().open("n.org")
        assertNotNull(doc)
        assertEquals("Heading", doc!!.headlines[0].title)
        assertNull(vault().open("missing.org"))
    }

    @Test
    fun `createNotebook appends org extension and rejects duplicates`() = runTest {
        val v = vault()
        assertTrue(v.createNotebook("inbox").isOk)
        assertTrue(tmp.root.resolve("inbox.org").exists())
        assertFalse(v.createNotebook("inbox.org").isOk)
    }

    @Test
    fun `save invalidates cache`() = runTest {
        tmp.newFile("n.org").writeText("* Old")
        val v = vault()
        assertEquals("Old", v.open("n.org")!!.headlines[0].title)
        v.save("n.org", "* New")
        assertEquals("New", v.open("n.org")!!.headlines[0].title)
    }

    @Test
    fun `display name strips extension`() {
        assertEquals("journal", Notebook("journal.org", 0, 0L).displayName)
    }

    @Test
    fun `display name and dir are derived from a nested path`() {
        val nb = Notebook("projects/clients/acme.org", 0, 0L)
        assertEquals("acme", nb.displayName)
        assertEquals("projects/clients", nb.dir)
        assertEquals("", Notebook("root.org", 0, 0L).dir)
    }

    @Test
    fun `notebooks lists files from subdirectories by path`() = runTest {
        tmp.newFile("root.org").writeText("* R")
        tmp.newFolder("projects", "clients")
        tmp.root.resolve("projects/notes.org").writeText("* N\n* M")
        tmp.root.resolve("projects/clients/acme.org").writeText("* A")

        val notebooks = vault().notebooks()
        assertEquals(
            listOf("projects/clients/acme.org", "projects/notes.org", "root.org"),
            notebooks.map { it.fileName },
        )
        assertEquals(listOf(1, 2, 1), notebooks.map { it.noteCount })
    }

    @Test
    fun `createNotebook into a directory creates missing folders`() = runTest {
        val v = vault()
        assertTrue(v.createNotebook("tasks", dir = "work/2026").isOk)
        assertTrue(tmp.root.resolve("work/2026/tasks.org").exists())
        assertFalse(v.createNotebook("tasks.org", dir = "work/2026").isOk)
    }

    @Test
    fun `moveNotebook relocates the file keeping its name`() = runTest {
        tmp.newFile("acme.org").writeText("* A")
        val v = vault()

        assertEquals("clients/acme.org", v.moveNotebook("acme.org", "clients").getOrNull())
        assertFalse(tmp.root.resolve("acme.org").exists())
        assertEquals("* A", tmp.root.resolve("clients/acme.org").readText())
        assertEquals(StorageError.NameTaken, v.moveNotebook("clients/acme.org", "clients"))
    }

    @Test
    fun `moveNotebook into a not-yet-existing nested folder creates the path`() = runTest {
        tmp.newFile("acme.org").writeText("* A")
        val v = vault()

        assertEquals("clients/2026/acme.org", v.moveNotebook("acme.org", "clients/2026").getOrNull())
        assertTrue(tmp.root.resolve("clients/2026/acme.org").exists())
    }

    @Test
    fun `createNotebook honours a typed nested name under a directory`() = runTest {
        val v = vault()
        assertTrue(v.createNotebook("clients/acme.org", dir = "projects").isOk)
        assertTrue(tmp.root.resolve("projects/clients/acme.org").exists())
    }

    @Test
    fun `renameFolder moves every descendant and creates the new directory`() = runTest {
        tmp.newFolder("projects", "clients")
        tmp.root.resolve("projects/grove.org").writeText("* G")
        tmp.root.resolve("projects/clients/acme.org").writeText("* A")
        val v = vault()

        assertEquals("work", v.renameFolder("projects", "work").getOrNull())
        assertFalse(tmp.root.resolve("projects/grove.org").exists())
        assertEquals("* G", tmp.root.resolve("work/grove.org").readText())
        assertEquals("* A", tmp.root.resolve("work/clients/acme.org").readText())
        assertEquals(
            listOf("work/clients/acme.org", "work/grove.org"),
            v.notebooks().map { it.fileName },
        )
    }

    @Test
    fun `renameFolder keeps the parent and renames only the leaf`() = runTest {
        tmp.newFolder("area", "old")
        tmp.root.resolve("area/old/note.org").writeText("* N")
        val v = vault()

        assertEquals("area/new", v.renameFolder("area/old", "new").getOrNull())
        assertEquals("* N", tmp.root.resolve("area/new/note.org").readText())
        // The vacated source directory is not left on disk.
        assertFalse(tmp.root.resolve("area/old").exists())
    }

    @Test
    fun `renameFolder is a no-op when the name is unchanged or the target exists`() = runTest {
        tmp.newFolder("a")
        tmp.root.resolve("a/one.org").writeText("* 1")
        tmp.newFolder("b")
        tmp.root.resolve("b/one.org").writeText("* 2")
        val v = vault()

        assertEquals(StorageError.NameTaken, v.renameFolder("a", "a"))
        assertEquals(StorageError.NameTaken, v.renameFolder("a", "b")) // b/one.org already exists
        assertTrue(tmp.root.resolve("a/one.org").exists())
    }

    @Test
    fun `deleteFolder removes every file and prunes the folder tree`() = runTest {
        tmp.newFolder("projects", "clients")
        tmp.root.resolve("projects/grove.org").writeText("* G")
        tmp.root.resolve("projects/clients/acme.org").writeText("* A")
        tmp.newFile("root.org").writeText("* R")
        val v = vault()

        assertEquals(2, v.deleteFolder("projects").getOrNull())
        assertEquals(listOf("root.org"), v.notebooks().map { it.fileName })
        // Both the nested subfolder and the folder itself are gone from disk.
        assertFalse(tmp.root.resolve("projects").exists())
    }

    @Test
    fun `deleting a folder frees its name for a sibling folder rename`() = runTest {
        tmp.newFolder("foo")
        tmp.root.resolve("foo/a.org").writeText("* A")
        tmp.newFolder("bar")
        tmp.root.resolve("bar/b.org").writeText("* B")
        val v = vault()

        assertEquals(1, v.deleteFolder("foo").getOrNull())
        // The reported bug: this used to fail because `foo/a.org.trash` lingered.
        assertEquals("foo", v.renameFolder("bar", "foo").getOrNull())
        assertEquals("* B", tmp.root.resolve("foo/b.org").readText())
    }

    // --- case-insensitive name-collision guard (SAF/FAT sync targets) ---
    //
    // JvmFileStore inherits the host filesystem's casing behaviour (case-
    // insensitive on the default macOS/APFS setup, case-sensitive on Linux CI),
    // so these use a strictly case-sensitive in-memory store to prove Vault's
    // own guard rather than the OS's.

    /** In-memory, strictly case-sensitive [FileStore]. */
    private class CaseSensitiveStore : FileStore {
        private val files = linkedMapOf<String, String>()
        override suspend fun list(): List<FileEntry> =
            files.map { (name, body) -> FileEntry(name, 0L, body.length.toLong()) }
        override suspend fun read(name: String): String = files.getValue(name)
        override suspend fun write(name: String, content: String, ifMatch: String?): StorageResult<String> {
            files[name] = content
            return StorageResult.Ok("")
        }
        override suspend fun create(name: String): StorageResult<Unit> {
            if (name in files) return StorageError.NameTaken
            files[name] = ""
            return StorageResult.Ok(Unit)
        }
        override suspend fun rename(oldName: String, newName: String): StorageResult<Unit> {
            if (oldName == newName || newName in files) return StorageError.NameTaken
            if (oldName !in files) return StorageError.NotFound
            files[newName] = files.remove(oldName)!!
            return StorageResult.Ok(Unit)
        }
        override suspend fun delete(name: String): StorageResult<Unit> =
            if (files.remove(name) != null) StorageResult.Ok(Unit) else StorageError.NotFound
        override suspend fun exists(name: String): Boolean = name in files
    }

    @Test
    fun `createNotebook rejects a case-only duplicate`() = runTest {
        val v = Vault(CaseSensitiveStore())
        assertTrue(v.createNotebook("Work").isOk)
        assertFalse(v.createNotebook("work").isOk)
        assertFalse(v.createNotebook("WORK.org").isOk)
    }

    @Test
    fun `renameNotebook rejects a rename onto an existing name case-insensitively`() = runTest {
        val v = Vault(CaseSensitiveStore())
        assertTrue(v.createNotebook("inbox").isOk)
        assertTrue(v.createNotebook("archive").isOk)
        assertFalse(v.renameNotebook("inbox.org", "Archive").isOk)
        // A case-only self-rename is still allowed.
        assertTrue(v.renameNotebook("inbox.org", "Inbox").isOk)
    }

    @Test
    fun `renameFolder rejects merging into an existing folder case-insensitively`() = runTest {
        val v = Vault(CaseSensitiveStore())
        assertTrue(v.createNotebook("one", dir = "Alpha").isOk)
        assertTrue(v.createNotebook("two", dir = "Beta").isOk)
        assertEquals(StorageError.NameTaken, v.renameFolder("Alpha", "Beta"))
        assertEquals(StorageError.NameTaken, v.renameFolder("Alpha", "beta"))
    }

    @Test
    fun `save creates a file that does not exist yet`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(strictWrite = true)
        val v = Vault(store)

        v.save("roam/daily/2026-09-23.org", "* 19:20 Long run\n")

        assertEquals("* 19:20 Long run\n", store.read("roam/daily/2026-09-23.org"))
    }

    // --- StorageResult propagation ---

    @Test
    fun `createNotebook onto an existing name is NameTaken`() = runTest {
        val v = vault()
        assertTrue(v.createNotebook("Work").isOk)
        assertEquals(StorageError.NameTaken, v.createNotebook("Work"))
    }

    @Test
    fun `renameNotebook reports NameTaken for a taken target and NotFound for a missing source`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        tmp.newFile("b.org").writeText("* B")
        val v = vault()

        assertEquals(StorageError.NameTaken, v.renameNotebook("a.org", "b"))
        assertEquals(StorageError.NotFound, v.renameNotebook("nope.org", "c"))
    }

    @Test
    fun `deleteNotebook of a missing file is NotFound`() = runTest {
        assertEquals(StorageError.NotFound, vault().deleteNotebook("nope.org"))
    }

    @Test
    fun `every store error reaches the caller unchanged`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* A"))
        val v = Vault(store)
        val errors = listOf(
            StorageError.Unsupported(com.rrajath.grove.vault.storage.StorageOp.Delete),
            StorageError.ProviderError("boom"),
            StorageError.Offline,
            StorageError.AuthFailed,
        )
        for (error in errors) {
            store.failWith(com.rrajath.grove.vault.storage.StorageOp.Delete, error)
            assertEquals(error, v.deleteNotebook("a.org"))
        }
        assertEquals("a failed delete must leave the notebook", listOf("a.org"), v.notebooks().map { it.fileName })
    }

    @Test
    fun `trySave returns the error and leaves the file and cache alone`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* A"))
        val v = Vault(store)
        v.open("a.org")
        store.failWith(com.rrajath.grove.vault.storage.StorageOp.Write, StorageError.Offline)

        assertEquals(StorageError.Offline, v.trySave("a.org", "* changed"))
        assertEquals("* A", store.read("a.org"))
    }

    @Test
    fun `save throws a StorageException carrying the error`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* A"))
        store.failWith(com.rrajath.grove.vault.storage.StorageOp.Write, StorageError.AuthFailed)

        val thrown = runCatching { Vault(store).save("a.org", "* changed") }.exceptionOrNull()

        assertTrue(thrown is StorageException)
        assertEquals(StorageError.AuthFailed, (thrown as StorageException).error)
    }

    @Test
    fun `trySave of a new notebook whose create is refused returns that error`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore()
        store.failWith(com.rrajath.grove.vault.storage.StorageOp.Create, StorageError.ProviderError("no"))

        assertEquals(StorageError.ProviderError("no"), Vault(store).trySave("new.org", "* x"))
    }

    @Test
    fun `renameFolder stops at the first refused move and returns its error`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("old/a.org" to "* A", "old/b.org" to "* B"))
        store.failWith(com.rrajath.grove.vault.storage.StorageOp.Rename, StorageError.Unsupported(com.rrajath.grove.vault.storage.StorageOp.Rename))

        val result = Vault(store).renameFolder("old", "new")

        assertEquals(StorageError.Unsupported(com.rrajath.grove.vault.storage.StorageOp.Rename), result)
        assertEquals(listOf("old/a.org", "old/b.org"), store.snapshot().keys.toList())
    }

    @Test
    fun `deleteFolder stops at the first refused delete and returns its error`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("old/a.org" to "* A"))
        store.failWith(com.rrajath.grove.vault.storage.StorageOp.Delete, StorageError.ProviderError(null))

        assertEquals(StorageError.ProviderError(null), Vault(store).deleteFolder("old"))
        assertEquals(listOf("old/a.org"), store.snapshot().keys.toList())
    }

    @Test
    fun `trySave with ifMatch writes only over the revision the caller read`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* One\n"))
        val v = Vault(store)
        val read = v.revision("a.org")!!

        store.write("a.org", "* Changed elsewhere\n")
        val refused = v.trySave("a.org", "* Mine\n", ifMatch = read)

        assertTrue(refused is StorageError.Conflict)
        assertEquals("* Changed elsewhere\n", store.read("a.org"))

        val now = v.revision("a.org")!!
        assertTrue(v.trySave("a.org", "* Mine\n", ifMatch = now).isOk)
        assertEquals("* Mine\n", store.read("a.org"))
    }

    @Test
    fun `trySave with ifMatch on a file deleted meanwhile is a conflict, not a create`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* One\n"))
        val v = Vault(store)
        val read = v.revision("a.org")!!
        store.delete("a.org")

        assertEquals(StorageError.Conflict(null), v.trySave("a.org", "* Mine\n", ifMatch = read))
        assertFalse(store.exists("a.org"))
    }

    @Test
    fun `a save based on a document read before an external edit is refused`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* One\n"))
        val v = Vault(store)
        val doc = v.open("a.org")!!

        store.write("a.org", "* Changed elsewhere\n")
        val result = v.trySave("a.org", doc.text + "* Mine\n", base = doc)

        assertTrue(result is StorageError.Conflict)
        assertEquals("* Changed elsewhere\n", store.read("a.org"))
        assertTrue(runCatching { v.save("a.org", "x", base = doc) }.exceptionOrNull() is StorageException)
    }

    @Test
    fun `a base whose marker moved without its text changing still saves`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* One\n"))
        val v = Vault(store)
        val doc = v.open("a.org")!!
        store.touch("a.org")

        assertTrue(v.trySave("a.org", "* One\n* Two\n", base = doc).isOk)
        assertEquals("* One\n* Two\n", store.read("a.org"))
    }

    @Test
    fun `the parse handed to a save is the base of the next one`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* One\n"))
        val v = Vault(store)
        val first = v.open("a.org")!!
        val second = com.rrajath.grove.org.OrgParser.parse("* One\n* Two\n")
        assertTrue(v.trySave("a.org", second.text, parsed = second, base = first).isOk)
        assertEquals(v.revision("a.org"), v.revisionOf(second))

        store.write("a.org", "* Changed elsewhere\n")
        assertTrue(v.trySave("a.org", "* Three\n", base = second) is StorageError.Conflict)
    }

    @Test
    fun `a document the vault didn't hand out saves unconditionally`() = runTest {
        val store = com.rrajath.grove.testing.FakeFileStore(mapOf("a.org" to "* One\n"))
        val v = Vault(store)
        val foreign = com.rrajath.grove.org.OrgParser.parse("* One\n")
        assertNull(v.revisionOf(foreign))
        assertTrue(v.trySave("a.org", "* Two\n", base = foreign).isOk)
    }
}
