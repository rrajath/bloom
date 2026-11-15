package com.rrajath.grove.sync

import com.rrajath.grove.vault.JvmFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Minimal in-memory [NoteIndex], enough to drive [SyncEngine] in a JVM test. */
private class RecordingIndex : NoteIndex {
    val revisions = mutableMapOf<String, String>()
    val texts = mutableMapOf<String, String>()
    val conflicts = mutableMapOf<String, String?>()

    override suspend fun knownNotebooks(): Map<String, KnownNotebook> =
        revisions.mapValues { (name, rev) -> KnownNotebook(rev, conflicts[name]) }

    override suspend fun stubNotebooks(stubs: List<NotebookStub>) {
        stubs.forEach { conflicts[it.fileName] = it.conflictFileName }
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

class SyncConflictsTest {

    private val copy = "journal.sync-conflict-20250611-143200-ABCDEF7.org"

    @Test
    fun `recognizes syncthing conflict names`() {
        assertTrue(SyncConflicts.isConflictFile(copy))
        assertFalse(SyncConflicts.isConflictFile("journal.org"))
        assertFalse(SyncConflicts.isConflictFile("sync-conflict-notes.org"))
    }

    @Test
    fun `base name strips the conflict infix`() {
        assertEquals("journal.org", SyncConflicts.baseName(copy))
        assertEquals(null, SyncConflicts.baseName("journal.org"))
    }

    @Test
    fun `conflict names keep their vault-relative directory prefix`() {
        val nested = "projects/clients/acme.sync-conflict-20250611-143200-ABCDEF7.org"
        assertTrue(SyncConflicts.isConflictFile(nested))
        assertEquals("projects/clients/acme.org", SyncConflicts.baseName(nested))
        assertEquals(
            mapOf("projects/clients/acme.org" to nested),
            SyncConflicts.detect(listOf("projects/clients/acme.org", nested, "root.org")),
        )
    }

    @Test
    fun `label formats the timestamp`() {
        assertEquals("2025-06-11 14:32", SyncConflicts.label(copy))
    }

    @Test
    fun `detect maps base to newest copy`() {
        val names = listOf(
            "journal.org",
            "journal.sync-conflict-20250610-090000-AAA.org",
            copy,
            "other.org",
        )
        assertEquals(mapOf("journal.org" to copy), SyncConflicts.detect(names))
    }

    @Test
    fun `keepBoth interleaves both versions of a diverging heading in place`() {
        val main = "* Heading\nbody\n"
        val conflict = "* Heading\nolder body\n** Child\n"
        val merged = ConflictResolver.keepBoth(main, conflict)
        // "* Heading" matches on both sides and is kept once; "body" (main) and
        // "older body\n** Child" (conflict) diverge at the same spot, so both stay,
        // main's version first.
        val expected = """
            * Heading
            body
            older body
            ** Child
        """.trimIndent() + "\n"
        assertEquals(expected, merged)
    }

    @Test
    fun `keepBoth handles missing trailing newline`() {
        val merged = ConflictResolver.keepBoth("* A", "* B")
        assertEquals("* A\n* B", merged)
    }

    @Test
    fun `keepBoth reproduces main text byte-for-byte when the copy is identical`() {
        val text = "* Heading\nbody\n"
        assertEquals(text, ConflictResolver.keepBoth(text, text))
    }

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Full "Keep both" round trip: detect the conflict, resolve it (mirroring
     * exactly what [SyncManager.resolveConflict]'s KEEP_BOTH branch does: read
     * both files, merge, overwrite the base file, delete the copy), then re-sync.
     * Regression coverage for the bug where "Keep both" silently degraded to
     * "keep current" (nothing written) whenever the copy was already gone by
     * resolution time; asserts the merged file actually carries BOTH the
     * original and the copy's content into the index, and the conflict marker
     * clears once the copy is gone.
     */
    @Test
    fun `keep both resolution round-trips through a resync`() = runTest {
        val copyName = "journal.sync-conflict-20250611-143200-DEVICE.org"
        tmp.newFile("journal.org").writeText("* Buy groceries\nMilk, eggs\n")
        tmp.newFile(copyName).writeText("* Buy groceries\nMilk, eggs, bread\n")

        val store = JvmFileStore(tmp.root)
        val index = RecordingIndex()
        val engine = SyncEngine(store, index) { 1000L }

        // Conflict is detected and indexed first, same as a normal sync would.
        val firstSync = engine.sync()!!
        assertEquals(mapOf("journal.org" to copyName), firstSync.conflicts)
        assertEquals(copyName, index.conflicts["journal.org"])

        // Exactly SyncManager.resolveConflict's KEEP_BOTH sequence.
        val merged = ConflictResolver.keepBoth(
            mainText = store.read("journal.org"),
            conflictText = store.read(copyName),
        )
        store.write("journal.org", merged)
        store.delete(copyName)

        // Re-sync, as resolveConflict does immediately after resolving.
        engine.sync()

        val finalText = index.texts.getValue("journal.org")
        assertTrue("main content missing", finalText.contains("Milk, eggs\n"))
        assertTrue("conflict copy content missing", finalText.contains("Milk, eggs, bread"))
        assertNull("conflict marker should clear once the copy is gone", index.conflicts["journal.org"])
        assertFalse(store.exists(copyName))
    }

    @Test
    fun `apply keeps the conflict copy when the base write is refused`() = runTest {
        val copy = "journal.sync-conflict-20250611-143200-DEVICE.org"
        val store = com.rrajath.grove.testing.FakeFileStore(
            mapOf("journal.org" to "* Mine\n", copy to "* Theirs\n"),
        )
        store.failWith(
            com.rrajath.grove.vault.storage.StorageOp.Write,
            com.rrajath.grove.vault.storage.StorageError.Offline,
        )

        for (resolution in listOf(ConflictResolution.KEEP_CONFLICT_COPY, ConflictResolution.KEEP_BOTH)) {
            val result = ConflictResolver.apply(store, "journal.org", copy, resolution)

            assertEquals(com.rrajath.grove.vault.storage.StorageError.Offline, result)
            assertEquals("* Theirs\n", store.read(copy))
            assertEquals("* Mine\n", store.read("journal.org"))
        }
    }

    @Test
    fun `apply writes the base then deletes the copy`() = runTest {
        val copy = "journal.sync-conflict-20250611-143200-DEVICE.org"
        val store = com.rrajath.grove.testing.FakeFileStore(
            mapOf("journal.org" to "* Mine\n", copy to "* Theirs\n"),
        )

        val result = ConflictResolver.apply(store, "journal.org", copy, ConflictResolution.KEEP_CONFLICT_COPY)

        assertTrue(result is com.rrajath.grove.vault.storage.StorageResult.Ok)
        assertEquals("* Theirs\n", store.read("journal.org"))
        assertFalse(store.exists(copy))
    }

    @Test
    fun `apply KEEP_CURRENT only removes the copy`() = runTest {
        val copy = "journal.sync-conflict-20250611-143200-DEVICE.org"
        val store = com.rrajath.grove.testing.FakeFileStore(
            mapOf("journal.org" to "* Mine\n", copy to "* Theirs\n"),
        )

        ConflictResolver.apply(store, "journal.org", copy, ConflictResolution.KEEP_CURRENT)

        assertEquals("* Mine\n", store.read("journal.org"))
        assertFalse(store.exists(copy))
    }
}
