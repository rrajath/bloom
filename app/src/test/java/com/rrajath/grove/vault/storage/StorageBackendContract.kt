package com.rrajath.grove.vault.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every [StorageBackend] must do, whatever it talks to. A backend's test
 * class extends this and supplies the fixture hooks; a future network backend
 * has to pass the same cases. M3 adds capability cases.
 */
abstract class StorageBackendContract {

    /** A backend over the fixture, giving up on a loading directory after [listingDeadlineMs]. */
    protected abstract fun newBackend(listingDeadlineMs: Long = 5_000): StorageBackend

    protected abstract fun seedFile(path: String, text: String = "x")

    protected abstract fun seedDir(path: String)

    /** The backend's handle for the directory at [path] ("" is the root). */
    protected abstract fun dirId(path: String): String

    /** The directory at [path] reports "still loading" until [finishLoadingSoon] (or forever). */
    protected abstract fun markLoading(path: String)

    protected abstract fun finishLoadingSoon(path: String, afterMs: Long)

    protected abstract fun markError(path: String)

    /** The text of the file at [path], or null when there is none. */
    protected abstract fun fileText(path: String): String?

    protected abstract fun dirExists(path: String): Boolean

    /** Makes the provider report [caps] for the document at [path] ("" is the root). */
    protected abstract fun restrict(path: String, caps: StorageCapabilities)

    /** The backend's handle for [name] inside the directory [dir], from a listing. */
    private suspend fun idOf(backend: StorageBackend, dir: String, name: String): String =
        backend.listChildren(dirId(dir)).entries.first { it.name == name }.docId

    @Test
    fun `a settled directory lists as Complete with files and folders`() = runTest {
        seedFile("a.org", "hello")
        seedFile("sub/b.org")
        val backend = newBackend()

        val listing = backend.listChildren(dirId(""))

        assertTrue(listing is Listing.Complete)
        assertEquals(listOf("a.org", "sub"), listing.entries.map { it.name }.sorted())
        assertTrue(listing.entries.single { it.name == "sub" }.isDir)
    }

    @Test
    fun `an empty directory is Complete and empty`() = runTest {
        seedFile("a.org")
        seedDir("empty")
        val backend = newBackend()

        val listing = backend.listChildren(dirId("empty"))

        assertTrue("got $listing", listing is Listing.Complete)
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `files carry a revision and directories do not`() = runTest {
        seedFile("a.org", "hello")
        seedFile("sub/b.org")
        val backend = newBackend()

        val entries = backend.listChildren(dirId("")).entries

        val file = entries.single { it.name == "a.org" }
        assertEquals(5L, file.size)
        assertNotNull(file.revision)
        assertEquals("${file.lastModified}:${file.size}", file.revision)
        assertNull(entries.single { it.name == "sub" }.revision)
    }

    @Test
    fun `a loading directory is waited for and then Complete`() = runTest {
        seedFile("a.org")
        markLoading("")
        finishLoadingSoon("", afterMs = 100)
        val backend = newBackend()

        val listing = backend.listChildren(dirId(""))

        assertTrue("got $listing", listing is Listing.Complete)
        assertEquals(listOf("a.org"), listing.entries.map { it.name })
    }

    @Test
    fun `a directory that never finishes loading is Partial after the deadline`() = runTest {
        seedFile("a.org")
        markLoading("")
        val backend = newBackend(listingDeadlineMs = 200)

        val listing = backend.listChildren(dirId(""))

        assertTrue("got $listing", listing is Listing.Partial)
        // The still-loading cursor held no rows: partial must not read as "empty and done".
        assertFalse(listing is Listing.Complete)
    }

    @Test
    fun `a provider error is Failed`() = runTest {
        seedFile("a.org")
        markError("")
        val backend = newBackend()

        val listing = backend.listChildren(dirId(""))

        assertTrue("got $listing", listing is Listing.Failed)
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `an unknown directory is Failed`() = runTest {
        seedFile("a.org")
        val backend = newBackend()

        assertTrue(backend.listChildren(dirId("missing")) is Listing.Failed)
    }

    // --- mutations ---

    @Test
    fun `createFile makes an empty file and returns its id`() = runTest {
        seedDir("sub")
        val backend = newBackend()

        val created = backend.createFile(dirId("sub"), "new.org")

        assertTrue("got $created", created is StorageResult.Ok)
        assertEquals("", fileText("sub/new.org"))
        assertEquals(created.getOrNull(), idOf(backend, "sub", "new.org"))
    }

    @Test
    fun `createFile on a taken name is NameTaken and leaves the original and no stray`() = runTest {
        seedFile("a.org", "keep")
        val backend = newBackend()

        val created = backend.createFile(dirId(""), "a.org")

        assertEquals(StorageError.NameTaken, created)
        assertEquals("keep", fileText("a.org"))
        assertEquals(listOf("a.org"), backend.listChildren(dirId("")).entries.map { it.name })
    }

    @Test
    fun `createDir makes a folder`() = runTest {
        val backend = newBackend()
        seedFile("a.org")

        val created = backend.createDir(dirId(""), "folder")

        assertTrue("got $created", created is StorageResult.Ok)
        assertTrue(dirExists("folder"))
    }

    @Test
    fun `rename keeps the content under the new name`() = runTest {
        seedFile("a.org", "body")
        val backend = newBackend()

        val renamed = backend.rename(idOf(backend, "", "a.org"), "b.org")

        assertTrue("got $renamed", renamed is StorageResult.Ok)
        assertEquals("body", fileText("b.org"))
        assertNull(fileText("a.org"))
    }

    @Test
    fun `delete removes the file and an unknown id is an error`() = runTest {
        seedFile("a.org")
        val backend = newBackend()
        val id = idOf(backend, "", "a.org")

        assertTrue(backend.delete(id) is StorageResult.Ok)
        assertNull(fileText("a.org"))
        assertTrue(backend.delete(id) is StorageError)
    }

    @Test
    fun `move relocates the file or says it is unsupported`() = runTest {
        seedFile("a.org", "body")
        seedDir("sub")
        val backend = newBackend()

        val moved = backend.move(idOf(backend, "", "a.org"), dirId(""), dirId("sub"))

        when (moved) {
            is StorageResult.Ok -> {
                assertEquals("body", fileText("sub/a.org"))
                assertNull(fileText("a.org"))
            }
            is StorageError.Unsupported -> assertEquals("body", fileText("a.org"))
            else -> org.junit.Assert.fail("a move is done or unsupported, not $moved")
        }
    }

    @Test
    fun `stat reports an entry and NotFound for an unknown id`() = runTest {
        seedFile("a.org", "hello")
        val backend = newBackend()

        val entry = backend.stat(idOf(backend, "", "a.org")).getOrNull()

        assertNotNull(entry)
        assertEquals(5L, entry!!.size)
        assertEquals(StorageError.NotFound, backend.stat(dirId("missing.org")))
    }

    @Test
    fun `openRead streams the content and an unknown id is NotFound`() = runTest {
        seedFile("a.org", "hello")
        val backend = newBackend()

        val text = backend.openRead(idOf(backend, "", "a.org")).getOrNull()!!.bufferedReader().use { it.readText() }

        assertEquals("hello", text)
        assertEquals(StorageError.NotFound, backend.openRead(dirId("missing.org")))
    }

    @Test
    fun `write replaces the content and returns the new revision`() = runTest {
        seedFile("a.org", "a much longer first version")
        val backend = newBackend()
        val id = idOf(backend, "", "a.org")

        val written = backend.write(id, "short".toByteArray())

        assertEquals("short", fileText("a.org"))
        assertEquals(backend.stat(id).getOrNull()!!.revision, written.getOrNull())
    }

    @Test
    fun `write with the current revision succeeds`() = runTest {
        seedFile("a.org", "one")
        val backend = newBackend()
        val id = idOf(backend, "", "a.org")
        val revision = backend.stat(id).getOrNull()!!.revision

        val written = backend.write(id, "two".toByteArray(), ifMatch = revision)

        assertTrue("got $written", written is StorageResult.Ok)
        assertEquals("two", fileText("a.org"))
    }

    @Test
    fun `write with a stale revision is a Conflict and leaves the file unchanged`() = runTest {
        seedFile("a.org", "one")
        val backend = newBackend()
        val id = idOf(backend, "", "a.org")
        val current = backend.stat(id).getOrNull()!!.revision

        val written = backend.write(id, "two".toByteArray(), ifMatch = "0:0")

        assertEquals(StorageError.Conflict(current), written)
        assertEquals("one", fileText("a.org"))
    }

    @Test
    fun `each listed document carries its own capabilities`() = runTest {
        seedFile("locked.org")
        seedFile("free.org")
        seedDir("repo")
        val noRenameMoveDelete = StorageCapabilities(delete = false, rename = false, move = false, copy = false)
        restrict("locked.org", noRenameMoveDelete)
        restrict("repo", StorageCapabilities(rename = false, move = false, copy = false))
        val backend = newBackend()

        val entries = backend.listChildren(dirId("")).entries.associateBy { it.name }

        val lockedFile = noRenameMoveDelete.copy(createChildren = false)
        assertEquals(lockedFile, entries.getValue("locked.org").capabilities)
        assertEquals(StorageCapabilities(createChildren = false), entries.getValue("free.org").capabilities)
        val repo = entries.getValue("repo").capabilities
        assertTrue(repo.delete && repo.createChildren && !repo.rename && !repo.move)
        assertEquals(lockedFile, (backend.stat(entries.getValue("locked.org").docId) as StorageResult.Ok).value.capabilities)
    }
}
