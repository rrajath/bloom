package com.rrajath.grove.vault

import android.app.Application
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.attach.AttachSource
import com.rrajath.grove.attach.AttachmentImporter
import com.rrajath.grove.attach.ImportResult
import com.rrajath.grove.vault.storage.BackendFileStore
import com.rrajath.grove.vault.storage.LAZY_CREATE_GRACE_MS
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.RAW_FRESH_MS
import com.rrajath.grove.vault.storage.SafBackend
import com.rrajath.grove.vault.storage.isOk
import java.io.ByteArrayInputStream
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
 * [BackendFileStore] on a lazy-create provider (Seafile): a create only
 * reserves an id and the file exists once written, and listings may lag behind
 * a create. Driven against [FakeDocumentsProvider.lazyCreate].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackendFileStoreLazyCreateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeDocumentsProvider
    private var clock = 1_000_000L

    private fun store(lazy: Boolean = true): BackendFileStore {
        provider = FakeDocumentsProvider.install(tmp.root, FakeDocumentsProvider.OPAQUE_AUTHORITY)
        provider.lazyCreate = lazy
        provider.writeMakesFolders = lazy
        val backend = SafBackend(
            context,
            FakeDocumentsProvider.treeUri(FakeDocumentsProvider.OPAQUE_AUTHORITY),
            listingDeadlineMs = 200,
            profile = if (lazy) ProviderProfiles.SEAFILE else ProviderProfiles.DEFAULT,
        )
        return BackendFileStore(backend, now = { clock })
    }

    private fun file(path: String) = File(tmp.root, path)

    @Test
    fun `creating a notebook makes the file exist and lists it`() = runTest {
        val store = store()
        assertTrue(store.create("inbox.org").isOk)

        assertTrue(file("inbox.org").isFile)
        assertEquals(listOf("inbox.org"), store.list().map { it.name })
    }

    @Test
    fun `a notebook in a new folder is created with the folder`() = runTest {
        val store = store()
        assertTrue(store.createWithContent("work/acme.org", "* TODO Call\n").isOk)

        assertEquals("* TODO Call\n", file("work/acme.org").readText())
    }

    @Test
    fun `createWithContent writes the content in the create`() = runTest {
        val store = store()
        assertTrue(store.createWithContent("a.org", "* Heading\n").isOk)

        assertEquals("* Heading\n", file("a.org").readText())
        assertEquals("* Heading\n", store.read("a.org"))
    }

    @Test
    fun `a listing that lags behind the create keeps the new file`() = runTest {
        val store = store()
        store.list()
        provider.hideCreatedFromListings = true
        assertTrue(store.create("new.org").isOk)

        assertEquals(listOf("new.org"), store.list().map { it.name })
        assertTrue(store.write("new.org", "text").isOk)
        assertEquals("text", file("new.org").readText())
    }

    @Test
    fun `a file still missing from listings after the grace window is dropped`() = runTest {
        val store = store()
        store.list()
        provider.hideCreatedFromListings = true
        assertTrue(store.create("new.org").isOk)

        clock += LAZY_CREATE_GRACE_MS + 1
        assertEquals(emptyList<String>(), store.list().map { it.name })
    }

    @Test
    fun `once listed the file no longer needs the grace window`() = runTest {
        val store = store()
        store.list()
        provider.hideCreatedFromListings = true
        assertTrue(store.create("new.org").isOk)
        provider.revealCreated()
        store.list()

        clock += LAZY_CREATE_GRACE_MS + 1
        assertEquals(listOf("new.org"), store.list().map { it.name })
    }

    @Test
    fun `a deleted new file isn't brought back by the grace window`() = runTest {
        val store = store()
        store.list()
        provider.hideCreatedFromListings = true
        assertTrue(store.create("new.org").isOk)
        assertTrue(store.delete("new.org").isOk)

        assertEquals(emptyList<String>(), store.list().map { it.name })
    }

    @Test
    fun `an attachment created then written exists with its bytes, however long the copy took`() = runTest {
        val store = store()
        assertTrue(store.createBinary("data/AB/CD/photo.jpg").isOk)
        assertFalse(file("data/AB/CD/photo.jpg").exists())

        clock += RAW_FRESH_MS + 1
        store.openOutput("data/AB/CD/photo.jpg").use { it.write(byteArrayOf(1, 2, 3)) }

        assertEquals(3L, file("data/AB/CD/photo.jpg").length())
        assertEquals(listOf<Byte>(1, 2, 3), store.openInput("data/AB/CD/photo.jpg").use { it.readBytes() }.toList())
    }

    @Test
    fun `an attachment import on a provider that can't rename writes the final name directly`() = runTest {
        val store = store()
        // Seafile: writable, folders accept children, but nothing renames, moves or deletes.
        provider.reportedFlags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE
        val bytes = ByteArray(10) { it.toByte() }
        val importer = AttachmentImporter(store, { ByteArrayInputStream(bytes) })

        val result = importer.import("data/8B/41D2F0", listOf(AttachSource("u", "photo.jpg", 10)), pruneStop = "")

        assertEquals(ImportResult.Success(listOf("photo.jpg"), emptySet()), result)
        assertEquals(10L, file("data/8B/41D2F0/photo.jpg").length())
        assertFalse(file("data/8B/41D2F0").list()!!.any { it.endsWith(".part") })
    }

    @Test
    fun `an eager provider still creates an empty file and stages imports`() = runTest {
        val store = store(lazy = false)
        assertTrue(store.create("a.org").isOk)
        assertEquals(0L, file("a.org").length())
        assertFalse(store.createsLazily)

        val importer = AttachmentImporter(store, { ByteArrayInputStream(ByteArray(4)) })
        val result = importer.import("data/AB/CD", listOf(AttachSource("u", "x.bin", 4)), pruneStop = "")
        assertEquals(ImportResult.Success(listOf("x.bin"), emptySet()), result)
        assertEquals(4L, file("data/AB/CD/x.bin").length())
    }

    @Test
    fun `an attachment reaches a new folder when the provider can't create folders`() = runTest {
        val store = store()
        provider.failDirCreate = true
        file("data").mkdirs()
        val importer = AttachmentImporter(store, { ByteArrayInputStream(ByteArray(6)) })

        val result = importer.import("data/8B/41D2F0", listOf(AttachSource("u", "photo.jpg", 6)), pruneStop = "")

        assertEquals(ImportResult.Success(listOf("photo.jpg"), emptySet()), result)
        assertEquals(6L, file("data/8B/41D2F0/photo.jpg").length())
    }

    @Test
    fun `a second attachment into the same new folder lands next to the first`() = runTest {
        val store = store()
        provider.failDirCreate = true
        val importer = AttachmentImporter(store, { ByteArrayInputStream(ByteArray(2)) })
        val sources = listOf(AttachSource("u1", "a.jpg", 2), AttachSource("u2", "b.jpg", 2))

        val result = importer.import("data/AB/CD", sources, pruneStop = "")

        assertEquals(ImportResult.Success(listOf("a.jpg", "b.jpg"), emptySet()), result)
        assertEquals(setOf("a.jpg", "b.jpg"), file("data/AB/CD").list()!!.toSet())
    }

    @Test
    fun `a failed folder create on Seafile is reported as unsupported`() = runTest {
        store()
        provider.failDirCreate = true
        val backend = SafBackend(
            context,
            FakeDocumentsProvider.treeUri(FakeDocumentsProvider.OPAQUE_AUTHORITY),
            listingDeadlineMs = 200,
            profile = ProviderProfiles.SEAFILE,
        )

        assertEquals(
            com.rrajath.grove.vault.storage.StorageError.Unsupported(com.rrajath.grove.vault.storage.StorageOp.CreateFolder),
            backend.createDir(backend.rootId, "projects"),
        )
    }

    @Test
    fun `a notebook reaches new folders when the provider can't create folders`() = runTest {
        val store = store()
        provider.failDirCreate = true
        file("work").mkdirs()

        assertTrue(store.createWithContent("work/acme/q4/plan.org", "* Plan\n").isOk)
        assertTrue(store.create("work/acme/q4/notes.org").isOk)

        assertEquals("* Plan\n", file("work/acme/q4/plan.org").readText())
        assertTrue(file("work/acme/q4/notes.org").isFile)
        assertEquals(listOf("work/acme/q4/notes.org", "work/acme/q4/plan.org"), store.list().map { it.name })
    }

    @Test
    fun `a notebook in a new folder survives a listing that doesn't show the folder yet`() = runTest {
        val store = store()
        provider.failDirCreate = true
        provider.hideCreatedFromListings = true
        store.list()

        assertTrue(store.createWithContent("inbox/today.org", "x").isOk)
        assertTrue(file("inbox/today.org").isFile)

        // Neither the folder nor the file is listed yet, but the file stays.
        assertEquals(listOf("inbox/today.org"), store.list().map { it.name })
        clock += LAZY_CREATE_GRACE_MS + 1
        provider.revealCreated()
        assertEquals(listOf("inbox/today.org"), store.list().map { it.name })
    }

    @Test
    fun `an attachment that can't get its folder names the provider's reason`() = runTest {
        val store = store(lazy = false)
        provider.failDirCreate = true
        val importer = AttachmentImporter(store, { ByteArrayInputStream(ByteArray(2)) })

        val result = importer.import("data/AB/CD", listOf(AttachSource("u", "a.jpg", 2)), pruneStop = "")

        result as ImportResult.Failed
        assertTrue(result.error is com.rrajath.grove.vault.storage.StorageError.ProviderError)
    }

    @Test
    fun `the store reports lazy create from its provider profile`() = runTest {
        assertTrue(store().createsLazily)
    }
}
