package com.rrajath.grove.vault

import android.app.Application
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.BackendFileStore
import com.rrajath.grove.vault.storage.SafBackend
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.storage.getOrNull
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
 * What [BackendFileStore]'s mutations answer when they don't succeed: each
 * failure has its own [StorageError], not a bare `false`. Driven against
 * [FakeDocumentsProvider] with its refuse-this-operation switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackendFileStoreResultsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeDocumentsProvider

    private fun store(): BackendFileStore {
        provider = FakeDocumentsProvider.install(tmp.root)
        return BackendFileStore(SafBackend(context, FakeDocumentsProvider.treeUri(), listingDeadlineMs = 200))
    }

    private fun seed(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun text(path: String) = File(tmp.root, path).takeIf { it.isFile }?.readText()

    /** What Seafile reports: the file is writable, but not renamable, deletable or movable. */
    private fun seafileFlags() {
        provider.reportedFlags = Document.FLAG_SUPPORTS_WRITE
    }

    @Test
    fun `create on an existing name is NameTaken`() = runTest {
        seed("a.org")
        assertEquals(StorageError.NameTaken, store().create("a.org"))
    }

    @Test
    fun `create the provider refuses is Unsupported when it advertises no create`() = runTest {
        seed("a.org")
        val saf = store()
        provider.failCreate = true
        seafileFlags()

        assertEquals(StorageError.Unsupported(StorageOp.Create), saf.create("b.org"))
        assertEquals(null, text("b.org"))
    }

    @Test
    fun `create the provider refuses without saying why is a ProviderError`() = runTest {
        seed("a.org")
        val saf = store()
        provider.failCreate = true

        assertTrue(saf.create("b.org") is StorageError.ProviderError)
    }

    @Test
    fun `rename of a missing file is NotFound and onto an existing one is NameTaken`() = runTest {
        seed("a.org")
        seed("b.org")
        val saf = store()

        assertEquals(StorageError.NotFound, saf.rename("nope.org", "c.org"))
        assertEquals(StorageError.NameTaken, saf.rename("a.org", "b.org"))
        assertEquals(StorageError.NameTaken, saf.rename("a.org", "a.org"))
    }

    @Test
    fun `a refused rename on Seafile-like flags is Unsupported and the file stays`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.failRename = true
        seafileFlags()

        assertEquals(StorageError.Unsupported(StorageOp.Rename), saf.rename("a.org", "b.org"))
        assertEquals("body", text("a.org"))
        assertTrue(saf.exists("a.org"))
    }

    @Test
    fun `delete of a missing file is NotFound`() = runTest {
        seed("a.org")
        assertEquals(StorageError.NotFound, store().delete("nope.org"))
    }

    @Test
    fun `a refused delete on Seafile-like flags is Unsupported and the file stays`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.failDelete = true
        seafileFlags()

        assertEquals(StorageError.Unsupported(StorageOp.Delete), saf.delete("a.org"))
        assertEquals("body", text("a.org"))
        assertTrue(saf.exists("a.org"))
    }

    @Test
    fun `a move falls back to copy and delete when the provider can't move`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.supportsMove = false

        val result = saf.rename("a.org", "sub/a.org")

        assertTrue("got $result", result is StorageResult.Ok)
        assertEquals("body", text("sub/a.org"))
        assertEquals(null, text("a.org"))
    }

    @Test
    fun `a move whose copy half is refused leaves the original and no copy`() = runTest {
        seed("a.org", "body")
        val saf = store()
        provider.supportsMove = false
        provider.failCreate = true

        val result = saf.rename("a.org", "sub/a.org")

        assertTrue("got $result", result is StorageError)
        assertEquals("body", text("a.org"))
        assertFalse(File(tmp.root, "sub/a.org").exists())
    }

    @Test
    fun `write returns the new revision and rejects a stale one`() = runTest {
        seed("a.org", "one")
        val saf = store()
        val before = saf.stat("a.org")!!

        val written = saf.write("a.org", "two!", ifMatch = "${before.lastModified}:${before.size}")

        assertTrue("got $written", written is StorageResult.Ok)
        val after = saf.stat("a.org")!!
        assertEquals("${after.lastModified}:${after.size}", written.getOrNull())
        assertEquals("two!", text("a.org"))

        val stale = saf.write("a.org", "three", ifMatch = "${before.lastModified}:${before.size}")

        assertEquals(StorageError.Conflict("${after.lastModified}:${after.size}"), stale)
        assertEquals("two!", text("a.org"))
    }

    @Test
    fun `write to a file that isn't there is NotFound`() = runTest {
        seed("a.org")
        assertEquals(StorageError.NotFound, store().write("nope.org", "x"))
    }

    @Test
    fun `with the vault root unreachable a create is Offline, not a silent failure`() = runTest {
        seed("a.org")
        val saf = store()
        provider.errorDirs.add(FakeDocumentsProvider.ROOT_ID)

        assertEquals(StorageError.Offline, saf.create("sub/b.org"))
        assertEquals(StorageError.Offline, saf.rename("a.org", "sub/a.org"))
    }
}
