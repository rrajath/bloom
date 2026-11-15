package com.rrajath.grove.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.SafBackend
import com.rrajath.grove.vault.storage.SafFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
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
 * [SafFileStore] over providers that answer a children query with an empty
 * "still loading" cursor first (Seafile, Nextcloud): nothing may read that as
 * an empty folder, and what the store already knew must survive an unfinished
 * listing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafFileStoreLoadingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val root = FakeDocumentsProvider.ROOT_ID

    private fun write(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    /** A store that gives up on a loading folder after [deadlineMs]. */
    private fun store(deadlineMs: Long = 150): Pair<SafFileStore, FakeDocumentsProvider> {
        val provider = FakeDocumentsProvider.install(tmp.root)
        val tree = FakeDocumentsProvider.treeUri()
        return SafFileStore(context, tree, backend = SafBackend(context, tree, deadlineMs)) to provider
    }

    @Test
    fun `a vault where every folder loads first lists completely`() = runTest {
        write("a.org"); write("x/b.org"); write("x/y/c.org"); write("z/d.org")
        val (saf, provider) = store(deadlineMs = 5_000)
        provider.everyDirLoadsFirst = true
        provider.autoFinishLoadingMs = 30

        val tree = saf.listTree()

        assertEquals(listOf("a.org", "x/b.org", "x/y/c.org", "z/d.org"), tree.entries.map { it.name })
        assertTrue(tree.isComplete)
        assertNull(tree.rootProblem)
    }

    @Test
    fun `single lookups and folder listings work on a cold store whose folders load first`() = runTest {
        write("x/y/c.org")
        val (saf, provider) = store(deadlineMs = 5_000)
        provider.everyDirLoadsFirst = true
        provider.autoFinishLoadingMs = 30

        assertEquals(listOf("x/y/c.org"), saf.listDir("x/y").map { it.name })
        assertNotNull(saf.stat("x/y/c.org"))
        assertEquals("x", saf.listSubdirs("").single())
    }

    @Test
    fun `a folder still loading is incomplete and its cached ids survive`() = runTest {
        write("a.org"); write("x/b.org")
        val (saf, provider) = store()
        assertTrue(saf.listTree().isComplete)
        provider.loadingDirs.add("$root/x")

        val tree = saf.listTree()

        assertEquals(setOf("x"), tree.incompleteDirs)
        assertEquals(listOf("a.org"), tree.entries.map { it.name })
        assertTrue(tree.isUnderIncompleteDir("x/anything.org"))
        assertFalse(tree.isUnderIncompleteDir("a.org"))
        // What we knew is still resolvable: the cache merged instead of being replaced.
        assertNotNull(saf.stat("x/b.org"))
        assertTrue(saf.exists("x/b.org"))
    }

    @Test
    fun `a failed root is reported with its reason and keeps the caches`() = runTest {
        write("a.org")
        val (saf, provider) = store()
        saf.list()
        provider.errorDirs.add(root)

        val tree = saf.listTree()

        assertEquals(setOf(""), tree.incompleteDirs)
        assertNotNull(tree.rootProblem)
        assertTrue(tree.entries.isEmpty())
        assertTrue(saf.exists("a.org"))
    }

    @Test
    fun `an unfinished listing does not make absence authoritative`() = runTest {
        write("a.org")
        val (saf, provider) = store()
        provider.loadingDirs.add(root) // never finishes: the first walk is partial

        assertFalse(saf.listTree().isComplete)
        // A file added meanwhile is found by asking again, not denied from a stale "complete" flag.
        provider.loadingDirs.remove(root)
        write("b.org")
        assertTrue(saf.exists("b.org"))
    }

    @Test
    fun `listDir on a partial listing forgets nothing`() = runTest {
        write("x/b.org")
        val (saf, provider) = store()
        saf.list()
        provider.loadingDirs.add("$root/x")

        assertTrue(saf.listDir("x").isEmpty())

        assertNotNull(saf.stat("x/b.org"))
    }

    @Test
    fun `pruneEmptyDirs leaves a folder the provider is still loading`() = runTest {
        File(tmp.root, "old").mkdirs()
        val (saf, provider) = store()
        saf.list()
        provider.loadingDirs.add("$root/old")

        saf.pruneEmptyDirs("old")
        assertTrue("a loading folder is not an empty one", File(tmp.root, "old").isDirectory)

        provider.loadingDirs.remove("$root/old")
        saf.pruneEmptyDirs("old")
        assertFalse("once listed as empty it goes", File(tmp.root, "old").exists())
    }

    @Test
    fun `an emptied child goes but its loading parent stays`() = runTest {
        File(tmp.root, "p/q").mkdirs()
        val (saf, provider) = store()
        saf.list()
        provider.loadingDirs.add("$root/p")

        saf.pruneEmptyDirs("p/q")

        assertFalse(File(tmp.root, "p/q").exists())
        assertTrue(File(tmp.root, "p").isDirectory)
    }

    @Test
    fun `isDirEmptyRaw is false while loading and true once settled`() = runTest {
        File(tmp.root, "d").mkdirs()
        val (saf, provider) = store()
        provider.loadingDirs.add("$root/d")

        assertFalse(saf.isDirEmptyRaw("d"))
        // The raw listing alone would have said "empty": that is the bug this guards.
        assertTrue(saf.listDirRaw("d").isEmpty())

        provider.loadingDirs.remove("$root/d")
        assertTrue(saf.isDirEmptyRaw("d"))
        write("d/f.txt")
        assertFalse(saf.isDirEmptyRaw("d"))
    }

    @Test
    fun `a decorated store still reports incomplete folders`() = runTest {
        write("x/b.org")
        val (saf, provider) = store()
        saf.list()
        provider.loadingDirs.add("$root/x")

        val tree = LineEndingFileStore(saf).listTree()

        assertEquals(setOf("x"), tree.incompleteDirs)
    }
}
