package com.rrajath.grove.webdav

import com.rrajath.grove.sync.SyncConflicts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebDavVaultSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var mirror: File
    private lateinit var stateDir: File
    private lateinit var client: FakeWebDavClient
    private lateinit var scope: CoroutineScope
    private lateinit var syncer: WebDavVaultSync

    @Before
    fun setUp() {
        mirror = tmp.newFolder("mirror")
        stateDir = tmp.newFolder("state")
        client = FakeWebDavClient()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        syncer = WebDavVaultSync(
            scope = scope,
            mirrorRoot = mirror,
            stateDir = stateDir,
            configProvider = { WebDavConfig("https://cloud.example.com/dav/", "alice", "secret") },
            clientFactory = { client },
            clock = { 1_730_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `remote files are pulled into the mirror`() = runTest {
        client.remoteEdit("inbox.org", "* remote")
        client.remoteEdit("projects/week 2.org", "* project")

        val first = syncer.sync()
        assertEquals(2, first.pulled)
        assertEquals("* remote", readLocal("inbox.org"))
        assertEquals("* project", readLocal("projects/week 2.org"))
        assertEquals(0, syncer.sync().changed)
    }

    @Test
    fun `local edits are pushed`() = runTest {
        client.remoteEdit("inbox.org", "* v1")
        syncer.sync()
        writeLocal("inbox.org", "* local edit")

        val result = syncer.sync()
        assertEquals(1, result.pushed)
        assertEquals("* local edit", String(client.files.getValue("inbox.org")))
        assertEquals(0, syncer.sync().changed)
    }

    @Test
    fun `remote edits are pulled`() = runTest {
        client.remoteEdit("inbox.org", "* v1")
        syncer.sync()
        client.remoteEdit("inbox.org", "* v2 from emacs")

        val result = syncer.sync()
        assertEquals(1, result.pulled)
        assertEquals("* v2 from emacs", readLocal("inbox.org"))
        assertEquals(0, syncer.sync().changed)
    }

    @Test
    fun `edits on both sides keep a sync conflict copy`() = runTest {
        client.remoteEdit("inbox.org", "* base")
        syncer.sync()
        writeLocal("inbox.org", "* mine")
        client.remoteEdit("inbox.org", "* theirs")

        val result = syncer.sync()
        assertEquals(1, result.conflicts)
        assertEquals("* theirs", readLocal("inbox.org"))

        val names = mirror.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(mirror).invariantSeparatorsPath }.toList()
        val conflicts = SyncConflicts.detect(names)
        val copy = conflicts["inbox.org"]
        assertNotNull(copy)
        assertEquals("* mine", readLocal(copy!!))
        assertTrue(SyncConflicts.isConflictFile(copy))
        assertTrue(client.files.containsKey(copy))
        assertEquals(0, syncer.sync().changed)
    }

    @Test
    fun `identical files on a first run do not conflict`() = runTest {
        client.remoteEdit("inbox.org", "* same")
        writeLocal("inbox.org", "* same")

        val result = syncer.sync()
        assertEquals(0, result.conflicts)
        assertEquals(0, result.changed)
        assertEquals("* same", readLocal("inbox.org"))
    }

    @Test
    fun `deletions propagate in both directions`() = runTest {
        client.remoteEdit("gone.org", "* bye")
        client.remoteEdit("kept.org", "* hi")
        syncer.sync()

        File(mirror, "gone.org").delete()
        assertEquals(1, syncer.sync().deletedRemote)
        assertFalse(client.files.containsKey("gone.org"))

        client.files.remove("kept.org")
        client.revisions.remove("kept.org")
        assertEquals(1, syncer.sync().deletedLocal)
        assertFalse(File(mirror, "kept.org").exists())
    }

    @Test
    fun `a locally edited file survives a remote deletion`() = runTest {
        client.remoteEdit("draft.org", "* base")
        syncer.sync()
        writeLocal("draft.org", "* still mine")
        client.files.remove("draft.org")
        client.revisions.remove("draft.org")

        val result = syncer.sync()
        assertEquals(1, result.pushed)
        assertEquals("* still mine", String(client.files.getValue("draft.org")))
    }

    @Test
    fun `a remote edit revived after a local deletion`() = runTest {
        client.remoteEdit("draft.org", "* base")
        syncer.sync()
        File(mirror, "draft.org").delete()
        client.remoteEdit("draft.org", "* changed elsewhere")

        val result = syncer.sync()
        assertEquals(1, result.pulled)
        assertEquals("* changed elsewhere", readLocal("draft.org"))
    }

    @Test
    fun `connection test counts files without touching the mirror`() = runTest {
        client.remoteEdit("inbox.org", "* a")
        client.remoteEdit("projects/week 2.org", "* b")
        assertEquals(2, syncer.test())
        assertFalse(File(mirror, "inbox.org").exists())
    }

    @Test
    fun `connection test on an empty collection returns zero`() = runTest {
        assertEquals(0, syncer.test())
    }

    @Test
    fun `switching collection does not upload the previous mirror`() = runTest {
        val rootConfig = WebDavConfig("https://cloud.example.com/dav/", "alice", "secret")
        val todoConfig = WebDavConfig("https://cloud.example.com/dav/ToDo/", "alice", "secret")
        val rootClient = FakeWebDavClient().apply { remoteEdit("PureWriter/a.org", "* old") }
        val rootSync = WebDavVaultSync(
            scope = scope,
            mirrorRoot = mirror,
            mirrorRootForConfig = { WebDavPaths.mirrorRoot(mirror, it) },
            stateDir = stateDir,
            configProvider = { rootConfig },
            clientFactory = { rootClient },
        )
        assertEquals(1, rootSync.sync().pulled)

        val todoClient = FakeWebDavClient().apply { remoteEdit("todo.org", "* todo") }
        val todoSync = WebDavVaultSync(
            scope = scope,
            mirrorRoot = mirror,
            mirrorRootForConfig = { WebDavPaths.mirrorRoot(mirror, it) },
            stateDir = stateDir,
            configProvider = { todoConfig },
            clientFactory = { todoClient },
        )
        assertEquals(1, todoSync.sync().pulled)
        assertFalse(todoClient.files.containsKey("PureWriter/a.org"))
        assertEquals("* todo", File(WebDavPaths.mirrorRoot(mirror, todoConfig), "todo.org").readText())
        assertEquals("* old", File(WebDavPaths.mirrorRoot(mirror, rootConfig), "PureWriter/a.org").readText())
    }

    @Test
    fun `a 503 response pauses subsequent WebDAV requests`() = runTest {
        client.httpFailure = WebDavHttpException("PROPFIND", "/dav/", 503, "Service Unavailable")

        val first = runCatching { syncer.sync() }.exceptionOrNull()
        assertTrue(first is WebDavRateLimitedException)
        assertEquals(1, client.listCalls)

        val second = runCatching { syncer.sync() }.exceptionOrNull()
        assertTrue(second is WebDavRateLimitedException)
        val probe = runCatching { syncer.test() }.exceptionOrNull()
        assertTrue(probe is WebDavRateLimitedException)
        assertEquals(1, client.listCalls)
    }

    @Test
    fun `no configuration means a no-op`() = runTest {
        val unconfigured = WebDavVaultSync(
            scope = scope,
            mirrorRoot = mirror,
            stateDir = stateDir,
            configProvider = { null },
            clientFactory = { client },
        )
        assertEquals(0, unconfigured.sync().changed)
    }

    @Test
    fun `servers without etag or timestamps still detect remote edits`() = runTest {
        client.omitMetadata = true
        client.remoteEdit("plain.org", "* v1")
        syncer.sync()
        client.remoteEdit("plain.org", "* v2")

        val result = syncer.sync()
        assertEquals(1, result.pulled)
        assertEquals("* v2", readLocal("plain.org"))
        assertEquals(0, syncer.sync().changed)
    }

    @Test
    fun `a file that vanishes between propfind and get is retried next pass`() = runTest {
        client.remoteEdit("inbox.org", "* remote")
        client.vanishOnGet = "inbox.org"

        assertEquals(0, syncer.sync().changed)
        assertEquals(1, syncer.sync().pulled)
        assertEquals("* remote", readLocal("inbox.org"))
    }

    @Test
    fun `a failed remote delete keeps the baseline for a retry`() = runTest {
        client.remoteEdit("gone.org", "* bye")
        syncer.sync()
        File(mirror, "gone.org").delete()

        client.failDeleteOnce = true
        assertEquals(0, syncer.sync().changed)
        assertEquals(1, syncer.sync().deletedRemote)
        assertFalse(client.files.containsKey("gone.org"))
    }

    private fun writeLocal(path: String, text: String) {
        val file = File(mirror, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun readLocal(path: String): String = File(mirror, path).readText()

    /** In-memory WebDAV server with ETag-ish revisions and If-Match checks. */
    private class FakeWebDavClient : WebDavClient {
        val files = LinkedHashMap<String, ByteArray>()
        val revisions = HashMap<String, String>()
        /** Simulate a server whose PROPFIND returns no usable revision metadata. */
        var omitMetadata = false
        /** Simulate a resource disappearing between the PROPFIND and the GET. */
        var vanishOnGet: String? = null
        /** Simulate a 412 on the next DELETE (another device changed the file). */
        var failDeleteOnce = false
        /** Simulate a server-busy response from PROPFIND. */
        var httpFailure: WebDavHttpException? = null
        /** Count PROPFIND walks, to prove the cooldown stops further requests. */
        var listCalls = 0
        private var next = 1

        fun remoteEdit(path: String, text: String) {
            files[path] = text.toByteArray()
            revisions[path] = "\"r${next++}\""
        }

        private fun entry(path: String): WebDavEntry = if (omitMetadata) {
            WebDavEntry(path, false, null, 0L, 0L)
        } else {
            WebDavEntry(
                path = path,
                isDir = false,
                etag = revisions[path],
                lastModified = 1_730_000_000_000L,
                size = files.getValue(path).size.toLong(),
            )
        }

        override suspend fun list(): Map<String, WebDavEntry> {
            listCalls++
            httpFailure?.let { throw it }
            return files.keys.associateWith { entry(it) }
        }

        override suspend fun stat(path: String): WebDavEntry? =
            files[path]?.let { entry(path) }

        override suspend fun get(path: String): ByteArray? {
            if (vanishOnGet == path) {
                vanishOnGet = null
                return null
            }
            return files[path]
        }

        override suspend fun put(path: String, bytes: ByteArray, ifMatch: String?): WebDavEntry {
            val current = revisions[path]
            if (ifMatch != null && current != null && current != ifMatch) {
                throw WebDavPreconditionFailed(path)
            }
            files[path] = bytes
            revisions[path] = "\"r${next++}\""
            return entry(path)
        }

        override suspend fun delete(path: String, ifMatch: String?) {
            if (failDeleteOnce) {
                failDeleteOnce = false
                throw WebDavPreconditionFailed(path)
            }
            val current = revisions[path]
            if (ifMatch != null && current != null && current != ifMatch) {
                throw WebDavPreconditionFailed(path)
            }
            files.remove(path)
            revisions.remove(path)
        }
    }
}
