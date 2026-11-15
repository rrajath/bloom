package com.rrajath.grove.vault

import android.app.Application
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.BackendFileStore
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.SafBackend
import com.rrajath.grove.vault.storage.StorageCapabilities
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.StorageResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BackendFileStore] reads each document's `COLUMN_FLAGS`: it publishes them
 * as [FileStore.capabilities] and refuses what they rule out before touching
 * the provider. The fake provider doesn't enforce flags itself, so a file that
 * survives a refused operation proves no provider call was made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackendFileStoreCapabilitiesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeDocumentsProvider

    private fun store(authority: String = FakeDocumentsProvider.AUTHORITY): BackendFileStore {
        provider = FakeDocumentsProvider.install(tmp.root, authority)
        provider.reportedFlags = FULL
        return BackendFileStore(SafBackend(context, FakeDocumentsProvider.treeUri(authority), listingDeadlineMs = 200))
    }

    private fun seed(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun text(path: String) = File(tmp.root, path).takeIf { it.isFile }?.readText()

    private fun fileCount() = tmp.root.walkTopDown().count { it.isFile }

    /** What Seafile gives a file: writable, nothing else. */
    private fun seafileFile(path: String) {
        provider.flagsByPath[path] = Document.FLAG_SUPPORTS_WRITE
    }

    @Test
    fun `capabilities are published per file and folder after a listing`() = runTest {
        seed("a.org")
        seed("work/b.org")
        val saf = store()
        seafileFile("work/b.org")
        provider.flagsByPath[""] = Document.FLAG_DIR_SUPPORTS_CREATE

        saf.list()

        val caps = saf.capabilities.value
        assertEquals(ProviderProfiles.LOCAL, caps.profile)
        assertTrue(caps.ofFile("a.org").delete)
        assertFalse(caps.ofFile("work/b.org").delete)
        assertFalse(caps.ofFile("work/b.org").rename)
        assertTrue(caps.ofDir("work").rename)
        assertFalse(caps.root.delete)
        assertTrue(caps.root.createChildren)
        assertFalse(caps.ofFilesUnder("work").delete)
        assertTrue(caps.ofFilesUnder("").write)
    }

    @Test
    fun `an unknown authority gets the default profile`() = runTest {
        seed("a.org")
        val saf = store(FakeDocumentsProvider.OPAQUE_AUTHORITY)
        saf.list()
        assertEquals(ProviderProfiles.DEFAULT, saf.capabilities.value.profile)
    }

    @Test
    fun `a delete the flags rule out is Unsupported with no provider call`() = runTest {
        seed("a.org", "body")
        val saf = store()
        seafileFile("a.org")
        saf.list()

        assertEquals(StorageError.Unsupported(StorageOp.Delete), saf.delete("a.org"))
        assertEquals("body", text("a.org"))
    }

    @Test
    fun `a same-folder rename the flags rule out is Unsupported and nothing is copied`() = runTest {
        seed("a.org", "body")
        val saf = store()
        seafileFile("a.org")
        saf.list()

        assertEquals(StorageError.Unsupported(StorageOp.Rename), saf.rename("a.org", "b.org"))
        assertEquals("body", text("a.org"))
        assertEquals(null, text("b.org"))
        assertEquals(StorageError.Unsupported(StorageOp.Rename), saf.rename("a.org", "A.org"))
    }

    @Test
    fun `a move with neither move nor delete refuses before copying or creating folders`() = runTest {
        seed("a.org", "body")
        val saf = store()
        seafileFile("a.org")
        saf.list()
        val before = fileCount()

        assertEquals(StorageError.Unsupported(StorageOp.Move), saf.rename("a.org", "sub/a.org"))
        assertEquals(before, fileCount())
        assertEquals("body", text("a.org"))
        assertFalse(File(tmp.root, "sub").exists())
    }

    @Test
    fun `a move without the move flag still works as copy and delete`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.flagsByPath["a.org"] = FULL and Document.FLAG_SUPPORTS_MOVE.inv()
        saf.list()

        assertTrue(saf.rename("a.org", "sub/a.org") is StorageResult.Ok)
        assertEquals("body", text("sub/a.org"))
        assertEquals(null, text("a.org"))
    }

    @Test
    fun `a native move that fails on an undeletable file leaves no copy`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.flagsByPath["a.org"] = Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_WRITE
        provider.supportsMove = false
        saf.list()

        val result = saf.rename("a.org", "sub/a.org")

        assertTrue("got $result", result is StorageError)
        assertEquals("body", text("a.org"))
        assertEquals(null, text("sub/a.org"))
    }

    @Test
    fun `a rename moves the file's capabilities to its new path`() = runTest {
        seed("a.org")
        val saf = store()
        provider.flagsByPath["a.org"] = FULL and Document.FLAG_SUPPORTS_DELETE.inv()
        saf.list()

        assertTrue(saf.rename("a.org", "b.org") is StorageResult.Ok)

        assertFalse(saf.capabilities.value.ofFile("b.org").delete)
        assertFalse("a.org" in saf.capabilities.value.files)
    }

    @Test
    fun `raw operations honour the attachment's flags`() = runTest {
        seed("data/ab/cd/img.png", "png")
        seed("data/ab/cd/other.png", "png")
        val saf = store()
        seafileFile("data/ab/cd/img.png")
        provider.flagsByPath["data/ab"] = Document.FLAG_DIR_SUPPORTS_CREATE

        assertEquals(StorageError.Unsupported(StorageOp.Delete), saf.deleteRaw("data/ab/cd/img.png"))
        assertEquals(StorageError.Unsupported(StorageOp.Rename), saf.renameRaw("data/ab/cd/img.png", "new.png"))
        assertEquals(StorageError.Unsupported(StorageOp.Delete), saf.deleteDir("data/ab"))
        assertEquals(StorageError.Unsupported(StorageOp.Move), saf.moveDir("data/ab", "elsewhere"))
        assertEquals("png", text("data/ab/cd/img.png"))
        assertFalse(File(tmp.root, "elsewhere").exists())
        assertTrue(saf.deleteRaw("data/ab/cd/other.png") is StorageResult.Ok)
    }

    @Test
    fun `unreported flags change nothing`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.reportedFlags = null
        saf.list()

        assertEquals(StorageCapabilities.ALL, saf.capabilities.value.ofFile("a.org"))
        assertTrue(saf.rename("a.org", "b.org") is StorageResult.Ok)
        saf.list()
        assertTrue(saf.delete("b.org") is StorageResult.Ok)
    }

    private companion object {
        val FULL = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY or
            Document.FLAG_DIR_SUPPORTS_CREATE
    }
}
