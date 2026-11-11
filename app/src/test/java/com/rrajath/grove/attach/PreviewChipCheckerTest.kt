package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Outline and search chips check existence themselves, through the shared cache, a few at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewChipCheckerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cache = AttachmentStatusCache()
    private val store by lazy { JvmFileStore(tmp.root, skipAttachShards = true) }

    private fun put(path: String) = File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText("x")

    private val text = """
        * Lunch
        :PROPERTIES:
        :ID: abcdef
        :END:
        [[attachment:a.jpg]]
        * Dinner
        :PROPERTIES:
        :ID: 123456
        :END:
        [[attachment:a.jpg]]
    """.trimIndent() + "\n"
    private val doc = OrgParser.parse(text)
    private val raw = "attachment:a.jpg"
    private val link = AttachmentLink.classify(raw, null)!!

    private fun checker(
        store: FileStore? = this.store,
        loadDoc: suspend (String) -> OrgDocument? = { null },
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        parallelism: Int = 4,
    ) = PreviewChipChecker({ store }, { config }, cache, loadDoc, dispatcher, parallelism)

    private var config = AttachConfig.Default

    @Test
    fun `a config change rebuilds the resolver under the new folder`() = runTest {
        put("attach/ab/cdef/a.jpg")
        val c = checker(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(AttachmentStatus.Missing, c.status(link, raw, "n.org", doc, ownerLine = 0))
        config = AttachConfig(idDir = "attach/")
        cache.clear() // as the app does on a config change
        assertTrue(c.status(link, raw, "n.org", doc, ownerLine = 0) is AttachmentStatus.Present)
    }

    @Test
    fun `the heading line picks the owner, so each heading's copy of a link is checked on its own`() = runTest {
        put("data/ab/cdef/a.jpg")
        val c = checker(dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(c.status(link, raw, "n.org", doc, ownerLine = 0) is AttachmentStatus.Present)
        assertEquals(AttachmentStatus.Missing, c.status(link, raw, "n.org", doc, ownerLine = 5))
    }

    @Test
    fun `a missing result is shared with read mode's knownMissing`() = runTest {
        val c = checker(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(AttachmentStatus.Missing, c.status(link, raw, "moved/n.org", doc, ownerLine = 0))
        assertTrue(cache.knownMissing("moved/n.org", raw))
    }

    @Test
    fun `without a parsed doc an attachment link loads it to find the owner`() = runTest {
        put("data/12/3456/a.jpg")
        var loads = 0
        val c = checker(loadDoc = { loads++; doc }, dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(c.status(link, raw, "n.org", null, ownerLine = 5) is AttachmentStatus.Present)
        assertEquals(1, loads)
    }

    @Test
    fun `file links never load the doc`() = runTest {
        put("trips/img/a.png")
        val l = AttachmentLink.classify("file:img/a.png", null)!!
        val c = checker(loadDoc = { error("not needed") }, dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(c.status(l, "file:img/a.png", "trips/kyoto.org", null, ownerLine = 0) is AttachmentStatus.Present)
    }

    @Test
    fun `no vault means no answer, so the chip keeps its default look`() = runTest {
        val c = checker(store = null, dispatcher = StandardTestDispatcher(testScheduler))
        assertNull(c.status(link, raw, "n.org", doc, ownerLine = 0))
    }

    @Test
    fun `a cached answer skips the store`() = runTest {
        val c = checker(dispatcher = StandardTestDispatcher(testScheduler))
        c.status(link, raw, "n.org", doc, ownerLine = 0)
        val counting = CountingStore(store)
        val again = checker(store = counting.proxy, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(AttachmentStatus.Missing, again.status(link, raw, "n.org", doc, ownerLine = 0))
        assertEquals(0, counting.calls.get())
    }

    @Test
    fun `at most parallelism checks touch the store at once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocking = BlockingStore(store, gate)
        val c = checker(store = blocking.proxy, dispatcher = StandardTestDispatcher(testScheduler), parallelism = 2)
        val jobs = (0 until 6).map { i ->
            async { c.status(link, raw, "f$i.org", doc, ownerLine = 0) }
        }
        advanceUntilIdle()
        assertEquals(2, blocking.active.get())
        gate.complete(Unit)
        jobs.awaitAll()
        assertEquals(2, blocking.peak.get())
    }

    /** Counts the existence checks a lookup makes. */
    private class CountingStore(private val delegate: FileStore) : FileStore by delegate {
        val calls = AtomicInteger()
        val proxy: FileStore get() = this

        override suspend fun statRaw(name: String): FileEntry? = delegate.statRaw(name).also { calls.incrementAndGet() }
        override suspend fun dirExists(dir: String): Boolean = delegate.dirExists(dir).also { calls.incrementAndGet() }
    }

    /** Holds every existence check at [gate], tracking how many wait there at once. */
    private class BlockingStore(private val delegate: FileStore, private val gate: CompletableDeferred<Unit>) : FileStore by delegate {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val proxy: FileStore get() = this

        private suspend fun <T> held(block: suspend () -> T): T {
            peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
            gate.await()
            return try { block() } finally { active.decrementAndGet() }
        }

        override suspend fun statRaw(name: String): FileEntry? = held { delegate.statRaw(name) }
        override suspend fun dirExists(dir: String): Boolean = held { delegate.dirExists(dir) }
    }
}
