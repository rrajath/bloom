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
 * has to pass the same cases. M2 and M3 add mutation and capability cases.
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
}
