package com.rrajath.grove.vault

import android.app.Application
import com.rrajath.grove.attach.ownerOf
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SafFileStore]'s attachment support against a java.io-backed documents
 * provider: the `data/` shard skip in traversal (and that it keeps the
 * number of SAF queries per sync flat), and the raw binary/directory APIs on
 * both a path-id provider (local storage) and an opaque-id one (walks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafFileStoreAttachmentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun store(
        authority: String = FakeDocumentsProvider.AUTHORITY,
        skipShards: Boolean = true,
    ): Pair<SafFileStore, FakeDocumentsProvider> {
        val provider = FakeDocumentsProvider.install(tmp.root, authority)
        return SafFileStore(context, FakeDocumentsProvider.treeUri(authority), skipAttachShards = skipShards) to provider
    }

    private fun write(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    @Test
    fun `list skips attachment shards but keeps org files elsewhere under data`() = runTest {
        write("inbox.org")
        write("data/loose.org")
        write("data/notes/kept.org")
        write("data/3F/2504E0-4F89/photo.jpg")
        write("data/202611/02T125201/clip.m4a")
        write("data/__/a/ab/scan.pdf")
        write("projects/data/AB/CDEF/x.png")

        val (saf, _) = store()
        assertEquals(
            listOf("data/loose.org", "data/notes/kept.org", "inbox.org"),
            saf.list().map { it.name },
        )
    }

    @Test
    fun `with the skip off every folder is listed as before`() = runTest {
        write("inbox.org")
        write("data/3F/2504E0-4F89/photo.jpg")

        val (saf, _) = store(skipShards = false)
        assertEquals(listOf("data/3F/2504E0-4F89/photo.jpg", "inbox.org"), saf.list().map { it.name })
    }

    @Test
    fun `a sync listing costs the same number of queries with 500 attachment dirs`() = runTest {
        write("inbox.org")
        write("projects/acme.org")
        write("projects/data/notes.org")
        write("data/readme.org")

        val (before, provider) = store()
        provider.queries.set(0)
        val baseline = before.list()
        val baselineQueries = provider.queries.get()

        repeat(500) { i ->
            val id = "%08X-1111-2222-3333-444444444444".format(i * 7919)
            write("data/${id.take(2)}/${id.drop(2)}/img-$i.jpg")
            if (i % 5 == 0) write("projects/data/${id.take(2)}/${id.drop(2)}/doc-$i.pdf")
        }

        val (after, provider2) = store()
        provider2.queries.set(0)
        assertEquals(baseline.map { it.name }, after.list().map { it.name })
        assertEquals(baselineQueries, provider2.queries.get())
    }

    @Test
    fun `normal lookups still treat shard files as missing`() = runTest {
        write("data/3F/2504E0/photo.jpg")
        val (saf, _) = store()
        assertNull(saf.stat("data/3F/2504E0/photo.jpg"))
        assertFalse(saf.exists("data/3F/2504E0/photo.jpg"))
        assertTrue(saf.listDir("data/3F/2504E0").isEmpty())
    }

    @Test
    fun `raw file round trip on a path-id provider`() = runTest { rawRoundTrip(FakeDocumentsProvider.AUTHORITY) }

    @Test
    fun `raw file round trip on an opaque-id provider`() = runTest { rawRoundTrip(FakeDocumentsProvider.OPAQUE_AUTHORITY) }

    private suspend fun rawRoundTrip(authority: String) {
        write("inbox.org")
        val (saf, _) = store(authority)
        saf.list() // warm the listing caches, as a sync would

        val part = "data/3F/2504E0-4F89/photo.jpg.part"
        assertFalse(saf.dirExists("data/3F"))
        assertTrue(saf.createBinary(part))
        assertFalse("already exists", saf.createBinary(part))
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        saf.openOutput(part).use { it.write(bytes) }

        assertTrue(saf.dirExists("data/3F/2504E0-4F89"))
        assertEquals(listOf("3F"), saf.listSubdirs("data"))
        assertEquals(200_000L, saf.statRaw(part)?.size)

        assertTrue(saf.renameRaw(part, "photo.jpg"))
        assertNull(saf.statRaw(part))
        val final = "data/3F/2504E0-4F89/photo.jpg"
        assertArrayEquals(bytes, saf.openInput(final).use { it.readBytes() })
        assertEquals(listOf(final), saf.listDirRaw("data/3F/2504E0-4F89").map { it.name })
        assertNotNull(saf.contentUri(final))
        assertNull(saf.contentUri("data/3F/2504E0-4F89/nope.jpg"))
        assertArrayEquals(bytes, File(tmp.root, final).readBytes())

        // Traversal is unaffected by what the raw APIs touched.
        assertEquals(listOf("inbox.org"), saf.list().map { it.name })

        assertTrue(saf.deleteRaw(final))
        assertFalse(File(tmp.root, final).exists())
        assertFalse(saf.deleteRaw(final))
    }

    @Test
    fun `raw lookups see a file added behind the store's back`() = runTest {
        val (saf, _) = store(FakeDocumentsProvider.OPAQUE_AUTHORITY)
        saf.list()
        assertFalse(saf.dirExists("data/AB/CD"))
        write("data/AB/CD/late.png")
        assertTrue(saf.dirExists("data/AB/CD"))
        assertEquals(1L, saf.statRaw("data/AB/CD/late.png")?.size)
    }

    @Test
    fun `deleteDir removes a whole attach dir`() = runTest {
        write("data/3F/2504E0/a.jpg")
        write("data/3F/2504E0/sub/b.pdf")
        val (saf, _) = store()
        assertTrue(saf.deleteDir("data/3F/2504E0"))
        assertFalse(File(tmp.root, "data/3F/2504E0").exists())
        assertTrue(File(tmp.root, "data/3F").isDirectory)
        assertFalse(saf.deleteDir("data/3F/2504E0"))
    }

    @Test
    fun `moveDir uses the provider move`() = runTest { moveDir(supportsMove = true) }

    @Test
    fun `moveDir falls back to copy and delete`() = runTest { moveDir(supportsMove = false) }

    private suspend fun moveDir(supportsMove: Boolean) {
        write("Notes/data/3F/2504E0/a.jpg", "aaa")
        write("Notes/data/3F/2504E0/sub/b.pdf", "bb")
        val (saf, provider) = store()
        provider.supportsMove = supportsMove

        assertTrue(saf.moveDir("Notes/data/3F/2504E0", "Archive/data/3F"))
        assertFalse(File(tmp.root, "Notes/data/3F/2504E0").exists())
        assertEquals("aaa", File(tmp.root, "Archive/data/3F/2504E0/a.jpg").readText())
        assertEquals("bb", File(tmp.root, "Archive/data/3F/2504E0/sub/b.pdf").readText())
        assertEquals(3L, saf.statRaw("Archive/data/3F/2504E0/a.jpg")?.size)
        assertNull(saf.statRaw("Notes/data/3F/2504E0/a.jpg"))
    }

    @Test
    fun `root notebook move carries file-level and heading attach dirs on a path-id provider`() = runTest {
        rootNotebookMove(FakeDocumentsProvider.AUTHORITY)
    }

    @Test
    fun `root notebook move carries file-level and heading attach dirs on an opaque-id provider`() = runTest {
        rootNotebookMove(FakeDocumentsProvider.OPAQUE_AUTHORITY)
    }

    /**
     * The on-device shape: a root notebook whose file-level `:ID:` was added by
     * the editor's attach sheet (drawer on line 0, above `#+TITLE:`) plus a
     * heading owner, moved into a folder that already holds the file's attach
     * dir with a clashing name (merged, the newcomer renamed).
     */
    private suspend fun rootNotebookMove(authority: String) {
        val fileId = "C482DAE9-87F2-4045-BE53-8582798C5D58"
        val headingId = "A0050AF0-FB23-4124-9E18-7C506F6E3949"
        val base = "#+TITLE: Image test\n\nintro\n* Images\nbody\n"
        val withFile = com.rrajath.grove.attach.insertAttachmentLinks(
            base, 0, com.rrajath.grove.attach.AttachOwner.File(null, null), fileId, listOf("a.jpg"),
        ).text
        val parsed = com.rrajath.grove.org.OrgParser.parse(withFile)
        val heading = parsed.ownerOf(parsed.headlines.single())
        val text = com.rrajath.grove.attach.insertAttachmentLinks(withFile, withFile.length, heading, headingId, listOf("b.jpg")).text
        write("image-test.org", text)
        write("Folder/inbox.org", "")
        write("data/C4/82DAE9-87F2-4045-BE53-8582798C5D58/a.jpg", "new")
        write("data/A0/050AF0-FB23-4124-9E18-7C506F6E3949/b.jpg", "bb")
        write("Folder/data/C4/82DAE9-87F2-4045-BE53-8582798C5D58/a.jpg", "old")

        val (saf, _) = store(authority)
        saf.list()
        val lifecycle = com.rrajath.grove.attach.AttachmentLifecycle(saf, com.rrajath.grove.attach.IdHolders.None)
        val inv = lifecycle.inventoryForFile("image-test.org", com.rrajath.grove.org.OrgParser.parse(saf.read("image-test.org")))
        assertEquals(setOf(fileId, headingId), inv.dirs.map { it.id }.toSet())

        assertTrue(saf.rename("image-test.org", "Folder/image-test.org"))
        val result = lifecycle.moveNotebookAttachments("image-test.org", "Folder/image-test.org", inv)

        assertEquals(2, result.moved)
        assertEquals(0, result.failed)
        val fileDir = "Folder/data/C4/82DAE9-87F2-4045-BE53-8582798C5D58"
        assertEquals("old", File(tmp.root, "$fileDir/a.jpg").readText())
        assertEquals("new", File(tmp.root, "$fileDir/a-1.jpg").readText())
        assertEquals("bb", File(tmp.root, "Folder/data/A0/050AF0-FB23-4124-9E18-7C506F6E3949/b.jpg").readText())
        assertFalse("root data/ pruned", File(tmp.root, "data").exists())
    }

    @Test
    fun `moveDir refuses when the destination exists`() = runTest {
        write("Notes/data/3F/2504E0/a.jpg")
        write("Archive/data/3F/2504E0/other.jpg")
        val (saf, _) = store()
        assertFalse(saf.moveDir("Notes/data/3F/2504E0", "Archive/data/3F"))
        assertTrue(File(tmp.root, "Notes/data/3F/2504E0/a.jpg").exists())
    }
}
