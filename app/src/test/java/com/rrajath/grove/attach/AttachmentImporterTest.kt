package com.rrajath.grove.attach

import com.rrajath.grove.vault.JvmFileStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { JvmFileStore(tmp.root, skipAttachShards = true) }
    private val sources = mutableMapOf<String, () -> InputStream?>()
    private val importer by lazy { AttachmentImporter(store, { uri -> sources[uri]?.invoke() }) }

    private fun source(uri: String, name: String, bytes: ByteArray = ByteArray(10) { it.toByte() }): AttachSource {
        sources[uri] = { ByteArrayInputStream(bytes) }
        return AttachSource(uri, name, bytes.size.toLong())
    }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun exists(path: String) = File(tmp.root, path).exists()

    @Test
    fun `copies every item into the dir under its picked name`() = runTest {
        val result = importer.import(
            "notes/data/8B/41D2F0",
            listOf(source("a", "receipt.pdf"), source("b", "photo.jpg")),
            pruneStop = "notes",
        )
        assertEquals(ImportResult.Success(listOf("receipt.pdf", "photo.jpg"), emptySet()), result)
        assertEquals(10L, File(tmp.root, "notes/data/8B/41D2F0/receipt.pdf").length())
        assertTrue(exists("notes/data/8B/41D2F0/photo.jpg"))
        assertFalse(File(tmp.root, "notes/data/8B/41D2F0").list()!!.any { it.endsWith(".part") })
    }

    @Test
    fun `a taken name gets a numbered suffix and is reported as renamed`() = runTest {
        put("data/AB/CD/receipt.pdf")
        val result = importer.import("data/AB/CD", listOf(source("a", "Receipt.PDF")), pruneStop = "")
        assertEquals(ImportResult.Success(listOf("Receipt-1.PDF"), setOf("Receipt-1.PDF")), result)
    }

    @Test
    fun `two picks with the same name in one import don't collide`() = runTest {
        val result = importer.import("data/AB/CD", listOf(source("a", "x.jpg"), source("b", "x.jpg")), pruneStop = "")
        assertEquals(listOf("x.jpg", "x-1.jpg"), (result as ImportResult.Success).names)
    }

    @Test
    fun `names are sanitized before writing`() = runTest {
        val result = importer.import("data/AB/CD", listOf(source("a", "a[1]/b.txt")), pruneStop = "")
        assertEquals(listOf("a1b.txt"), (result as ImportResult.Success).names)
    }

    @Test
    fun `an unavailable source fails and removes items that already landed`() = runTest {
        val ok = source("a", "first.jpg")
        val gone = AttachSource("missing", "second.jpg", 5)
        val result = importer.import("notes/data/8B/41D2F0", listOf(ok, gone), pruneStop = "notes")
        assertEquals(ImportResult.Failed("second.jpg", ImportFailure.SourceUnavailable), result)
        // The import created the dir, so it and the emptied shard/data dirs are pruned.
        assertFalse(exists("notes/data"))
        assertTrue(exists("notes"))
    }

    @Test
    fun `a failure keeps a dir that existed before, with its other files`() = runTest {
        put("data/AB/CD/old.pdf")
        val gone = AttachSource("missing", "new.pdf", 5)
        val result = importer.import("data/AB/CD", listOf(gone), pruneStop = "")
        assertEquals(ImportFailure.SourceUnavailable, (result as ImportResult.Failed).failure)
        assertTrue(exists("data/AB/CD/old.pdf"))
        assertFalse(exists("data/AB/CD/new.pdf.part"))
    }

    @Test
    fun `a read error mid-stream is a source failure and leaves no partial`() = runTest {
        sources["boom"] = {
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 3) 1 else throw IOException("gone")
                override fun read(b: ByteArray, off: Int, len: Int): Int = if (n++ < 1) 3 else throw IOException("gone")
            }
        }
        val result = importer.import("data/AB/CD", listOf(AttachSource("boom", "a.bin", null)), pruneStop = "")
        assertEquals(ImportResult.Failed("a.bin", ImportFailure.SourceUnavailable), result)
        assertFalse(exists("data"))
    }

    @Test
    fun `cancelling removes partials and finished items, then rethrows`() = runTest {
        val gate = CompletableDeferred<Unit>()
        sources["slow"] = {
            object : InputStream() {
                override fun read(): Int = 1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    gate.complete(Unit)
                    return len
                }
            }
        }
        val first = source("a", "done.jpg")
        var thrown: Throwable? = null
        // A real thread: the endless stream never suspends, so the test thread must stay free.
        val job = launch(Dispatchers.IO) {
            try {
                importer.import("data/AB/CD", listOf(first, AttachSource("slow", "endless.bin", null)), pruneStop = "")
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        gate.await()
        job.cancelAndJoin()
        assertTrue(thrown is CancellationException)
        assertFalse(exists("data"))
    }

    @Test
    fun `progress reports item index and bytes`() = runTest {
        val seen = mutableListOf<ImportProgress>()
        importer.import("d", listOf(source("a", "a.bin", ByteArray(300_000)), source("b", "b.bin")), "") { seen += it }
        assertEquals(ImportProgress(1, 2, "a.bin", 0, 300_000), seen.first())
        assertEquals(300_000L, seen.last { it.index == 1 }.bytesDone)
        assertEquals(ImportProgress(2, 2, "b.bin", 10, 10), seen.last())
    }

    @Test
    fun `sizes format as in the 1c list`() {
        assertEquals("0 B", formatAttachmentSize(0))
        assertEquals("182 KB", formatAttachmentSize(182 * 1024))
        assertEquals("2.4 MB", formatAttachmentSize((2.4 * 1024 * 1024).toLong()))
        assertEquals("2.1 of 4.1 MB", formatCopyBytes((2.1 * 1024 * 1024).toLong(), (4.1 * 1024 * 1024).toLong()))
        assertEquals("100 of 200 KB", formatCopyBytes(100 * 1024, 200 * 1024))
        assertEquals("1.5 MB", formatCopyBytes((1.5 * 1024 * 1024).toLong(), null))
    }

    // Photo processing (M5): a fake processor stands in for ImageTranscoder.

    private val processedBytes = "JPEGDATA".toByteArray()
    private val processorCalls = mutableListOf<Pair<String, Int?>>()
    private fun processor(result: Boolean = true) = PhotoProcessor { src, open, longEdge ->
        processorCalls += src.displayName to longEdge
        open()?.close()
        if (!result) null else tmp.newFile().apply { writeBytes(processedBytes) }
    }

    @Test
    fun `a processed photo is written from the processor output under a jpg name`() = runTest {
        val imp = AttachmentImporter(store, { uri -> sources[uri]?.invoke() }, processor())
        val result = imp.import(
            "data/AB/CD", listOf(source("a", "IMG_1.HEIC")), pruneStop = "",
            processPhotos = true, photoLongEdge = 1920,
        )
        assertEquals(ImportResult.Success(listOf("IMG_1.jpg"), emptySet()), result)
        assertEquals("JPEGDATA", File(tmp.root, "data/AB/CD/IMG_1.jpg").readText())
        assertEquals(listOf("IMG_1.HEIC" to 1920), processorCalls)
    }

    @Test
    fun `the jpg name from a conversion still gets a unique suffix`() = runTest {
        put("data/AB/CD/IMG_1.jpg")
        val imp = AttachmentImporter(store, { uri -> sources[uri]?.invoke() }, processor())
        val result = imp.import("data/AB/CD", listOf(source("a", "IMG_1.heic")), pruneStop = "", processPhotos = true)
        assertEquals(ImportResult.Success(listOf("IMG_1-1.jpg"), setOf("IMG_1-1.jpg")), result)
    }

    @Test
    fun `files source and non-photo formats skip the processor`() = runTest {
        val imp = AttachmentImporter(store, { uri -> sources[uri]?.invoke() }, processor())
        imp.import("data/AB/CD", listOf(source("a", "big.jpg")), pruneStop = "", processPhotos = false, photoLongEdge = 1280)
        imp.import("data/AB/CD", listOf(source("b", "shot.png")), pruneStop = "", processPhotos = true, photoLongEdge = 1280)
        assertTrue(processorCalls.isEmpty())
        assertEquals(10L, File(tmp.root, "data/AB/CD/big.jpg").length())
        assertEquals(10L, File(tmp.root, "data/AB/CD/shot.png").length())
    }

    @Test
    fun `a processor that declines falls back to a byte copy under the original name`() = runTest {
        val imp = AttachmentImporter(store, { uri -> sources[uri]?.invoke() }, processor(result = false))
        val result = imp.import("data/AB/CD", listOf(source("a", "IMG_2.HEIC")), pruneStop = "", processPhotos = true)
        assertEquals(ImportResult.Success(listOf("IMG_2.HEIC"), emptySet()), result)
        assertEquals(10L, File(tmp.root, "data/AB/CD/IMG_2.HEIC").length())
    }
}
