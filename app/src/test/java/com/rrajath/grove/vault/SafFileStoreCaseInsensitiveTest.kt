package com.rrajath.grove.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
 * [SafFileStore] on shared storage, where `inbox.org` and `Inbox.org` are one
 * file. Asking for the wrong case used to miss the cache, create the file, and
 * leave the provider's `inbox (2).org` behind on every capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafFileStoreCaseInsensitiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun store(): SafFileStore {
        FakeDocumentsProvider.install(tmp.root).caseInsensitive = true
        return SafFileStore(context, FakeDocumentsProvider.treeUri())
    }

    private fun write(path: String, text: String) =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun names() = tmp.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(tmp.root).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `saving under another case writes the existing file`() = runTest {
        write("Inbox.org", "#+title: Inbox\n")
        val saf = store()
        saf.list()
        val vault = Vault(saf)

        repeat(3) { i -> vault.save("inbox.org", "#+title: Inbox\n* Capture $i\n") }

        assertEquals(listOf("Inbox.org"), names())
        assertEquals("#+title: Inbox\n* Capture 2\n", File(tmp.root, "Inbox.org").readText())
        assertEquals("Inbox.org", saf.stat("inbox.org")?.name)
        assertEquals("Inbox.org", saf.canonicalName("inbox.org"))
        assertFalse(saf.create("inbox.org"))
    }

    @Test
    fun `a cold store resolves the real spelling too`() = runTest {
        write("notes/Inbox.org", "x")
        val saf = store()

        assertEquals("notes/Inbox.org", saf.stat("notes/inbox.org")?.name)
        assertEquals("x", saf.read("notes/INBOX.org"))
    }

    @Test
    fun `a file the cache missed is written instead of duplicated`() = runTest {
        val saf = store()
        saf.list()
        // Appears after the listing (Syncthing), so the cache doesn't know it.
        write("Inbox.org", "old")

        Vault(saf).save("inbox.org", "new")

        assertEquals(listOf("Inbox.org"), names())
        assertEquals("new", File(tmp.root, "Inbox.org").readText())
    }

    @Test
    fun `a case-only rename keeps one file with the new spelling`() = runTest {
        write("Work.org", "w")
        val saf = store()
        saf.list()

        assertTrue(saf.rename("Work.org", "work.org"))

        assertEquals(listOf("work.org"), names())
        assertEquals("w", File(tmp.root, "work.org").readText())
    }

    @Test
    fun `renaming onto another file in another case is refused`() = runTest {
        write("a.org", "a")
        write("B.org", "b")
        val saf = store()
        saf.list()

        assertFalse(saf.rename("a.org", "b.org"))
        assertEquals(listOf("B.org", "a.org"), names())
    }

    @Test
    fun `a new file under another case of a folder goes into that folder`() = runTest {
        write("Projects/acme.org", "a")
        val saf = store()
        saf.list()

        assertTrue(saf.create("projects/new.org"))

        assertEquals(listOf("Projects/acme.org", "Projects/new.org"), names())
        assertEquals("Projects/new.org", saf.stat("projects/new.org")?.name)
    }

    @Test
    fun `saving and listing under another case of a folder use the real one`() = runTest {
        write("Projects/Inbox.org", "old")
        val saf = store()

        Vault(saf).save("projects/inbox.org", "new")

        assertEquals(listOf("Projects/Inbox.org"), names())
        assertEquals("new", File(tmp.root, "Projects/Inbox.org").readText())
        assertEquals(listOf("Projects/Inbox.org"), saf.listDir("PROJECTS").map { it.name })
    }

    @Test
    fun `moving into another case of a folder uses that folder`() = runTest {
        write("a.org", "a")
        write("Projects/keep.org", "k")
        val saf = store()
        saf.list()

        assertTrue(saf.rename("a.org", "projects/a.org"))

        assertEquals(listOf("Projects/a.org", "Projects/keep.org"), names())
    }

    @Test
    fun `pruning another case of an emptied folder removes it`() = runTest {
        write("Projects/only.org", "x")
        val saf = store()
        saf.list()

        assertTrue(saf.delete("projects/only.org"))
        saf.pruneEmptyDirs("projects")

        assertFalse(File(tmp.root, "Projects").exists())
    }
}
