package com.rrajath.grove.sync.conflicts

import com.rrajath.grove.sync.KnownNotebook
import com.rrajath.grove.sync.NoteIndex
import com.rrajath.grove.sync.NotebookStub
import com.rrajath.grove.sync.SyncEngine
import com.rrajath.grove.vault.JvmFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConflictSchemeTest {

    @Test
    fun `seafile copy with a modifier`() {
        val copy = ConflictSchemes.parse("inbox (SFConflict alex@example.com 2026-11-08-10-12-33).org")!!
        assertSame(SeafileScheme, copy.scheme)
        assertEquals("inbox.org", copy.baseName)
        assertEquals("2026-11-08 10:12:33", copy.stamp)
        assertEquals("alex@example.com", copy.author)
        assertEquals("2026-11-08 10:12 · alex@example.com", copy.label)
    }

    @Test
    fun `seafile copy without a modifier, extension or at a nested path`() {
        val bare = ConflictSchemes.parse("inbox (SFConflict 2026-11-08-10-12-33).org")!!
        assertEquals("inbox.org", bare.baseName)
        assertNull(bare.author)
        assertEquals("2026-11-08 10:12", bare.label)

        assertEquals("README", ConflictSchemes.baseName("README (SFConflict a@b 2026-11-08-10-12-33)"))
        assertEquals(
            "work/projects/acme.org",
            ConflictSchemes.baseName("work/projects/acme (SFConflict a@b 2026-11-08-10-12-33).org"),
        )
        // A name with spaces and dots of its own keeps them.
        assertEquals(
            "my notes v1.2.org",
            ConflictSchemes.baseName("my notes v1.2 (SFConflict a@b 2026-11-08-10-12-33).org"),
        )
    }

    @Test
    fun `nextcloud copies, current and legacy`() {
        val withUser = ConflictSchemes.parse("inbox (conflicted copy Alex Smith 2026-11-08 101233).org")!!
        assertSame(NextcloudScheme, withUser.scheme)
        assertEquals("inbox.org", withUser.baseName)
        assertEquals("Alex Smith", withUser.author)
        assertEquals("2026-11-08 10:12:33", withUser.stamp)

        val noUser = ConflictSchemes.parse("dir/inbox (conflicted copy 2026-11-08 101233).org")!!
        assertEquals("dir/inbox.org", noUser.baseName)
        assertNull(noUser.author)

        val legacy = ConflictSchemes.parse("inbox_conflict-20261108-101233.org")!!
        assertSame(NextcloudScheme, legacy.scheme)
        assertEquals("inbox.org", legacy.baseName)
        assertEquals("2026-11-08 10:12:33", legacy.stamp)
    }

    @Test
    fun `syncthing copy keeps its old label`() {
        val copy = ConflictSchemes.parse("journal.sync-conflict-20250611-143200-ABCDEF1.org")!!
        assertSame(SyncthingScheme, copy.scheme)
        assertEquals("journal.org", copy.baseName)
        assertEquals("2025-06-11 14:32", copy.label)
    }

    @Test
    fun `ordinary names are not conflict copies`() {
        listOf(
            "inbox.org",
            "notes (draft).org",
            "SFConflict.org",
            "inbox (SFConflict alex).org",
            "inbox (conflicted copy).org",
            "my_conflict-notes.org",
            "sync-conflict-notes.org",
        ).forEach { assertNull(it, ConflictSchemes.parse(it)) }
    }

    @Test
    fun `detect runs every scheme and keeps the newest copy per base`() {
        val names = listOf(
            "a.org",
            "a (SFConflict x@y 2026-11-08-10-00-00).org",
            "a.sync-conflict-20261109-090000-ABCDEF1.org",
            "b.org",
            "b (conflicted copy 2026-11-01 120000).org",
            "c.org",
        )
        assertEquals(
            mapOf(
                "a.org" to "a.sync-conflict-20261109-090000-ABCDEF1.org",
                "b.org" to "b (conflicted copy 2026-11-01 120000).org",
            ),
            ConflictSchemes.detect(names),
        )
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sync flags a seafile copy and never indexes it as a notebook`() = runTest {
        val copy = "inbox (SFConflict alex@example.com 2026-11-08-10-12-33).org"
        tmp.newFile("inbox.org").writeText("* Mine\n")
        tmp.newFile(copy).writeText("* Theirs\n")
        val index = object : NoteIndex {
            val indexed = mutableMapOf<String, String?>()
            override suspend fun knownNotebooks() = indexed.mapValues { KnownNotebook("", it.value) }
            override suspend fun stubNotebooks(stubs: List<NotebookStub>) = Unit
            override suspend fun indexNotebook(
                fileName: String, revision: String, text: String, lastModified: Long, conflictFileName: String?,
            ) {
                indexed[fileName] = conflictFileName
            }
            override suspend fun setConflict(fileName: String, conflictFileName: String?) {
                indexed[fileName] = conflictFileName
            }
            override suspend fun removeNotebook(fileName: String) {
                indexed.remove(fileName)
            }
        }

        val result = SyncEngine(JvmFileStore(tmp.root), index) { 0L }.sync()!!

        assertEquals(mapOf("inbox.org" to copy), result.conflicts)
        assertEquals(mapOf("inbox.org" to copy), index.indexed)
    }
}
