package com.rrajath.grove.sync

import com.rrajath.grove.sync.conflicts.ConflictSchemes
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageResult
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.IgnorePatterns
import com.rrajath.grove.vault.JvmFileStore
import com.rrajath.grove.vault.TreeListing
import com.rrajath.grove.vault.UnverifiedReadException
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FileStore] that counts whole-directory [list] calls vs. targeted [stat]
 * calls, and (like `BackendFileStore`) serves [stat] as a single-file lookup rather
 * than falling through to [list]. Used to prove the single-file reindex path
 * never enumerates the vault.
 */
private class CountingStore(private val root: File) : FileStore {
    var listCalls = 0
    var statCalls = 0
    private val inner = JvmFileStore(root)

    override suspend fun list(): List<FileEntry> {
        listCalls++
        return inner.list()
    }

    override suspend fun stat(name: String): FileEntry? {
        statCalls++
        val f = File(root, name)
        return if (f.isFile) FileEntry(name, f.lastModified(), f.length()) else null
    }

    override suspend fun read(name: String) = inner.read(name)
    override suspend fun write(name: String, content: String, ifMatch: String?) = inner.write(name, content, ifMatch)
    override suspend fun create(name: String) = inner.create(name)
    override suspend fun rename(oldName: String, newName: String) = inner.rename(oldName, newName)
    override suspend fun delete(name: String) = inner.delete(name)
    override suspend fun exists(name: String) = inner.exists(name)
}

/** In-memory NoteIndex fake recording engine interactions. */
private class FakeIndex : NoteIndex {
    val revisions = mutableMapOf<String, String>()
    val texts = mutableMapOf<String, String>()
    val conflicts = mutableMapOf<String, String?>()
    val indexedOrder = mutableListOf<String>()
    val stubbedBatches = mutableListOf<List<String>>()

    /** Ordered log of stub/index calls, to assert the discovery/parse split. */
    val events = mutableListOf<String>()

    override suspend fun knownNotebooks(): Map<String, KnownNotebook> =
        revisions.mapValues { (name, rev) -> KnownNotebook(rev, conflicts[name]) }

    override suspend fun stubNotebooks(stubs: List<NotebookStub>) {
        stubbedBatches.add(stubs.map { it.fileName })
        stubs.forEach { events.add("stub:${it.fileName}") }
    }

    override suspend fun indexNotebook(
        fileName: String,
        revision: String,
        text: String,
        lastModified: Long,
        conflictFileName: String?,
    ) {
        revisions[fileName] = revision
        texts[fileName] = text
        conflicts[fileName] = conflictFileName
        indexedOrder.add(fileName)
        events.add("index:$fileName")
    }

    override suspend fun setConflict(fileName: String, conflictFileName: String?) {
        conflicts[fileName] = conflictFileName
    }

    override suspend fun removeNotebook(fileName: String) {
        revisions.remove(fileName)
        texts.remove(fileName)
        conflicts.remove(fileName)
    }
}

class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val index = FakeIndex()
    private var now = 1000L

    private fun engine(ignore: IgnorePatterns = IgnorePatterns("")) =
        SyncEngine(JvmFileStore(tmp.root, ignore), index) { now }

    @Test
    fun `first sync pulls everything`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        tmp.newFile("b.org").writeText("* B")
        tmp.newFile("notes.txt").writeText("not org")

        val result = engine().sync()!!
        assertEquals(listOf("a.org", "b.org"), result.pulled.sorted())
        assertEquals("* A", index.texts["a.org"])
        assertNull(index.texts["notes.txt"])
        assertEquals(1000L, result.completedAt)
    }

    @Test
    fun `unchanged files are not re-pulled`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        val e = engine()
        e.sync()
        index.indexedOrder.clear()

        val result = e.sync()!!
        assertEquals(emptyList<String>(), result.pulled)
        assertTrue(index.indexedOrder.isEmpty())
    }

    @Test
    fun `external change is detected by revision`() = runTest {
        val file = tmp.newFile("a.org").apply { writeText("* A") }
        val e = engine()
        e.sync()

        file.writeText("* A changed externally")
        file.setLastModified(file.lastModified() + 5000)
        val result = e.sync()!!
        assertEquals(listOf("a.org"), result.pulled)
        assertEquals("* A changed externally", index.texts["a.org"])
    }

    @Test
    fun `deleted files are removed from index`() = runTest {
        val file = tmp.newFile("a.org").apply { writeText("* A") }
        val e = engine()
        e.sync()

        file.delete()
        val result = e.sync()!!
        assertEquals(listOf("a.org"), result.removed)
        assertNull(index.texts["a.org"])
    }

    @Test
    fun `sync conflict copies are reported not indexed as notebooks`() = runTest {
        tmp.newFile("a.org").writeText("* current")
        tmp.newFile("a.sync-conflict-20250611-143200-DEVICE.org").writeText("* other")

        val result = engine().sync()!!
        assertEquals(mapOf("a.org" to "a.sync-conflict-20250611-143200-DEVICE.org"), result.conflicts)
        assertEquals(listOf("a.org"), result.pulled)
        assertEquals("a.sync-conflict-20250611-143200-DEVICE.org", index.conflicts["a.org"])
    }

    @Test
    fun `conflict marker clears once the copy disappears`() = runTest {
        tmp.newFile("a.org").writeText("* current")
        val copy = tmp.newFile("a.sync-conflict-20250611-143200-DEVICE.org").apply { writeText("* other") }
        val e = engine()
        e.sync()
        assertNotNull(index.conflicts["a.org"])

        copy.delete()
        e.sync()
        assertNull(index.conflicts["a.org"])
    }

    @Test
    fun `ignored files never reach the sync result`() = runTest {
        tmp.newFile("keep.org").writeText("* K")
        tmp.newFile("archive.org").writeText("* A")

        val result = engine(IgnorePatterns("archive*")).sync()!!
        assertEquals(listOf("keep.org"), result.pulled)
    }

    @Test
    fun `discovers nested files and indexes them by vault-relative path`() = runTest {
        tmp.newFile("root.org").writeText("* R")
        File(tmp.root, "projects/clients").mkdirs()
        File(tmp.root, "projects/notes.org").writeText("* N")
        File(tmp.root, "projects/clients/acme.org").writeText("* A")

        val result = engine().sync()!!
        assertEquals(
            listOf("projects/clients/acme.org", "projects/notes.org", "root.org"),
            result.pulled.sorted(),
        )
        assertEquals("* A", index.texts["projects/clients/acme.org"])
    }

    @Test
    fun `a file moved between directories is one removed plus one added`() = runTest {
        val file = File(tmp.root, "inbox.org").apply { writeText("* Task") }
        val e = engine()
        e.sync()
        index.indexedOrder.clear()

        File(tmp.root, "archive/2026").mkdirs()
        file.renameTo(File(tmp.root, "archive/2026/inbox.org"))

        val result = e.sync()!!
        assertEquals(listOf("inbox.org"), result.removed)
        assertEquals(listOf("archive/2026/inbox.org"), result.pulled)
        assertNull(index.texts["inbox.org"])
        assertEquals("* Task", index.texts["archive/2026/inbox.org"])
    }

    @Test
    fun `conflict copy in a subdirectory maps to its base in that directory`() = runTest {
        File(tmp.root, "projects/clients").mkdirs()
        File(tmp.root, "projects/clients/acme.org").writeText("* current")
        File(tmp.root, "projects/clients/acme.sync-conflict-20250611-143200-DEVICE.org")
            .writeText("* other")

        val result = engine().sync()!!
        assertEquals(
            mapOf(
                "projects/clients/acme.org" to
                    "projects/clients/acme.sync-conflict-20250611-143200-DEVICE.org",
            ),
            result.conflicts,
        )
        assertEquals(listOf("projects/clients/acme.org"), result.pulled)
    }

    @Test
    fun `a file whose download can't be verified is skipped and pulled next time`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        tmp.newFile("b.org").writeText("* B")
        val inner = JvmFileStore(tmp.root)
        var failA = true
        val store = object : FileStore by inner {
            override suspend fun read(name: String): String {
                if (name == "a.org" && failA) throw UnverifiedReadException(name)
                return inner.read(name)
            }
        }
        val e = SyncEngine(store, index) { now }

        val first = e.sync()!!
        assertEquals(listOf("b.org"), first.pulled)
        assertEquals(listOf("a.org"), first.unread)
        assertNull(index.texts["a.org"])

        failA = false
        assertEquals(listOf("a.org"), e.sync()!!.pulled)
        assertEquals("* A", index.texts["a.org"])
    }

    @Test
    fun `engine reports error state on store failure`() = runTest {
        val broken = object : com.rrajath.grove.vault.FileStore {
            override suspend fun list() = throw IllegalStateException("disk gone")
            override suspend fun read(name: String) = ""
            override suspend fun write(name: String, content: String, ifMatch: String?): StorageResult<String> = StorageResult.Ok("")
            override suspend fun create(name: String): StorageResult<Unit> = StorageError.NameTaken
            override suspend fun rename(oldName: String, newName: String): StorageResult<Unit> = StorageError.NotFound
            override suspend fun delete(name: String): StorageResult<Unit> = StorageError.NotFound
            override suspend fun exists(name: String) = false
        }
        val e = SyncEngine(broken, index) { now }
        assertNull(e.sync())
        assertTrue(e.state.value is SyncState.Error)
    }

    @Test
    fun `newly discovered files are stubbed once as a batch before any parse`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        tmp.newFile("b.org").writeText("* B")

        engine().sync()!!

        // One batch emission carrying every new file (instant full list), not
        // one stub per file interleaved with parsing.
        assertEquals(listOf(listOf("a.org", "b.org")), index.stubbedBatches.map { it.sorted() })
        // Every stub is written before the first file is parsed.
        val lastStub = index.events.indexOfLast { it.startsWith("stub:") }
        val firstIndex = index.events.indexOfFirst { it.startsWith("index:") }
        assertTrue(lastStub in 0 until firstIndex)
    }

    @Test
    fun `content change to an already-indexed notebook is not re-stubbed`() = runTest {
        val file = tmp.newFile("a.org").apply { writeText("* A") }
        val e = engine()
        e.sync()
        index.stubbedBatches.clear()

        file.writeText("* A changed")
        file.setLastModified(file.lastModified() + 5000)
        val result = e.sync()!!

        assertEquals(listOf("a.org"), result.pulled)
        assertTrue(index.stubbedBatches.isEmpty())
    }

    @Test
    fun `state ends in Done with result`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        val e = engine()
        val result = e.sync()
        assertEquals(SyncState.Done(result!!), e.state.value)
    }

    // --- single-file reindex (PERFORMANCE_AUDIT_2026-08-27 #1) ---

    @Test
    fun `reindexOne indexes only the named file and never lists the directory`() = runTest {
        val a = tmp.newFile("a.org").apply { writeText("* A") }
        tmp.newFile("b.org").writeText("* B")
        val store = CountingStore(tmp.root)
        val e = SyncEngine(store, index) { now }
        e.sync()
        index.indexedOrder.clear()
        val listCallsBefore = store.listCalls

        a.writeText("* A edited")
        a.setLastModified(a.lastModified() + 5000)
        e.reindexOne("a.org", "* A edited", conflictFileName = null)

        // Only a.org is touched; b.org (and the rest of a growing vault) is not.
        assertEquals(listOf("a.org"), index.indexedOrder)
        assertEquals("* A edited", index.texts["a.org"])
        // The whole point of #1: no full directory list, just this file's stat.
        assertEquals(listCallsBefore, store.listCalls)
        assertEquals(1, store.statCalls)
    }

    @Test
    fun `reindexOne records the current on-disk revision`() = runTest {
        val a = tmp.newFile("a.org").apply { writeText("* A") }
        val store = CountingStore(tmp.root)
        val e = SyncEngine(store, index) { now }

        a.writeText("* A v2")
        a.setLastModified(123_000)
        e.reindexOne("a.org", "* A v2", conflictFileName = null)

        assertEquals("123000:${"* A v2".toByteArray().size}", index.revisions["a.org"])
    }

    @Test
    fun `reindexOne carries the conflict marker through`() = runTest {
        tmp.newFile("a.org").writeText("* A")
        val store = CountingStore(tmp.root)
        val e = SyncEngine(store, index) { now }

        e.reindexOne("a.org", "* A", conflictFileName = "a.sync-conflict-x.org")

        assertEquals("a.sync-conflict-x.org", index.conflicts["a.org"])
    }

    @Test
    fun `reindexOne on a vanished file is a no-op`() = runTest {
        val store = CountingStore(tmp.root)
        val e = SyncEngine(store, index) { now }

        e.reindexOne("ghost.org", "* nope", conflictFileName = null)

        assertTrue(index.indexedOrder.isEmpty())
    }

    /** Hides everything under [incomplete] and reports those folders as unfinished, as a loading provider does. */
    private fun storeWith(incomplete: Set<String>, rootProblem: String? = null): FileStore {
        val inner = JvmFileStore(tmp.root)
        return object : FileStore by inner {
            override suspend fun listTree(): TreeListing {
                val tree = TreeListing(inner.list(), incomplete, rootProblem)
                return tree.copy(entries = tree.entries.filterNot { tree.isUnderIncompleteDir(it.name) })
            }
        }
    }

    @Test
    fun `a folder the provider did not finish listing keeps its notebooks indexed`() = runTest {
        File(tmp.root, "sub").mkdirs()
        File(tmp.root, "a.org").writeText("* A")
        File(tmp.root, "sub/b.org").writeText("* B")
        File(tmp.root, "sub/deep").mkdirs()
        File(tmp.root, "sub/deep/c.org").writeText("* C")
        engine().sync()

        val result = SyncEngine(storeWith(setOf("sub")), index) { now }.sync()!!

        assertEquals(emptyList<String>(), result.removed)
        assertEquals(setOf("sub"), result.incompleteDirs)
        assertEquals(setOf("a.org", "sub/b.org", "sub/deep/c.org"), index.revisions.keys)
    }

    @Test
    fun `a complete listing still removes a deleted notebook`() = runTest {
        File(tmp.root, "sub").mkdirs()
        File(tmp.root, "sub/b.org").writeText("* B")
        engine().sync()
        File(tmp.root, "sub/b.org").delete()

        val result = SyncEngine(storeWith(emptySet()), index) { now }.sync()!!

        assertEquals(listOf("sub/b.org"), result.removed)
    }

    @Test
    fun `an unfinished root leaves the index untouched and reports Offline`() = runTest {
        File(tmp.root, "a.org").writeText("* A")
        engine().sync()
        val before = index.revisions.toMap()
        File(tmp.root, "a.org").delete()
        val e = SyncEngine(storeWith(setOf(""), "Server unreachable"), index) { now }

        val result = e.sync()

        assertNull(result)
        assertEquals(SyncState.Offline("Server unreachable"), e.state.value)
        assertEquals(before, index.revisions)
    }

    @Test
    fun `a conflict marker survives when the folder holding the copy was not fully listed`() = runTest {
        File(tmp.root, "sub").mkdirs()
        File(tmp.root, "sub/b.org").writeText("* B")
        File(tmp.root, "sub/b.sync-conflict-20250611-143200-DEVICE.org").writeText("* other")
        engine().sync()
        val marker = index.conflicts["sub/b.org"]
        assertNotNull(marker)

        // The folder is now only partly visible: the notebook shows, its copy doesn't.
        val inner = JvmFileStore(tmp.root)
        val partial = object : FileStore by inner {
            override suspend fun listTree() = TreeListing(
                inner.list().filterNot { ConflictSchemes.isConflictFile(it.name) },
                incompleteDirs = setOf("sub"),
            )
        }
        SyncEngine(partial, index) { now }.sync()

        assertEquals(marker, index.conflicts["sub/b.org"])
    }

    @Test
    fun `a refused save marks the engine Offline until the next sync`() = runTest {
        File(tmp.root, "a.org").writeText("* One\n")
        val e = engine()
        e.sync()

        e.markOffline("The provider didn't answer before a save")
        assertEquals(SyncState.Offline("The provider didn't answer before a save"), e.state.value)

        e.sync()
        assertTrue(e.state.value is SyncState.Done)
    }
}
