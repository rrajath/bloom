package com.rrajath.grove.settings.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.OutputStream

class SafBackupWriterTest {

    private val tree = "content://tree/backup"
    private val docs = FakeDocs()
    private val writer = SafBackupWriter(docs, NAME)

    @Test
    fun `the file is named after the app, lowercased with dashes for spaces`() {
        assertEquals("bloom-settings.json", SafBackupWriter.fileNameFor("Bloom"))
        assertEquals("bloom-debug-settings.json", SafBackupWriter.fileNameFor("Bloom Debug"))
    }

    @Test
    fun `creates the file when missing and writes with truncate`() = runTest {
        assertTrue(writer.write(tree, "{}").isSuccess)
        assertEquals(listOf(NAME), docs.names.values.toList())
        assertEquals("{}", docs.content.values.single())
        assertEquals(listOf("wt"), docs.modes)
    }

    @Test
    fun `an existing file is overwritten in place`() = runTest {
        docs.add("doc1", NAME, "old content that is long")
        assertTrue(writer.write(tree, "new").isSuccess)
        assertEquals(mapOf("doc1" to "new"), docs.content)
        assertEquals(0, docs.creates)
    }

    @Test
    fun `the resolved document is cached between writes`() = runTest {
        writer.write(tree, "a")
        writer.write(tree, "b")
        assertEquals(1, docs.finds)
        assertEquals("b", docs.content.values.single())
    }

    @Test
    fun `a write to a vanished cached file re-resolves and recreates it`() = runTest {
        writer.write(tree, "a")
        docs.vanishAll()
        assertTrue(writer.write(tree, "b").isSuccess)
        assertEquals("b", docs.content.values.single())
        assertEquals(2, docs.creates)
    }

    @Test
    fun `a renamed document from the provider is used as returned`() = runTest {
        docs.createName = "bloom-debug-settings (1).json"
        assertTrue(writer.write(tree, "x").isSuccess)
        assertEquals("bloom-debug-settings (1).json", docs.names.values.single())
        writer.write(tree, "y")
        assertEquals("y", docs.content.values.single())
    }

    @Test
    fun `a provider rejecting truncate falls back to recreate and plain write`() = runTest {
        docs.add("doc1", NAME, "old content that is long")
        docs.rejectTruncate = true
        assertTrue(writer.write(tree, "new").isSuccess)
        assertFalse("old doc gone", "doc1" in docs.content)
        assertEquals("new", docs.content.values.single())
        assertEquals("w", docs.modes.last())
    }

    @Test
    fun `no truncate and no removal fails instead of writing a corrupt tail`() = runTest {
        docs.add("doc1", NAME, "old content that is long")
        docs.rejectTruncate = true
        docs.removeSupported = false
        assertTrue(writer.write(tree, "new").isFailure)
        assertEquals("old content that is long", docs.content["doc1"])
        assertFalse("never a bare w on an existing file", "w" in docs.modes)
    }

    @Test
    fun `an unwritable folder fails`() = runTest {
        docs.createSupported = false
        assertTrue(writer.write(tree, "x").isFailure)
    }

    @Test
    fun `find returns the backup file or null`() = runTest {
        assertNull(writer.find(tree))
        docs.add("doc1", NAME, "x")
        assertEquals("doc1", writer.find(tree))
    }

    private companion object {
        const val NAME = "bloom-debug-settings.json"
    }

    @Test
    fun `bestMatch prefers the newest of the file and its renamed copies`() {
        val e = { id: String, name: String, t: Long -> SafBackupWriter.Companion.Entry(id, name, t) }
        val entries = listOf(
            e("1", NAME, 100),
            e("2", NAME.removeSuffix(".json") + " (1).json", 300),
            e("3", "notes.org", 900),
            e("4", "other-settings.json", 800),
        )
        assertEquals("2", SafBackupWriter.bestMatch(NAME, entries)?.id)
    }

    @Test
    fun `bestMatch takes the exact name on a tie and ignores look-alikes`() {
        val e = { id: String, name: String, t: Long -> SafBackupWriter.Companion.Entry(id, name, t) }
        val stem = NAME.removeSuffix(".json")
        assertEquals("1", SafBackupWriter.bestMatch(NAME, listOf(e("2", "$stem (2).json", 0), e("1", NAME, 0)))?.id)
        assertEquals(null, SafBackupWriter.bestMatch(NAME, listOf(e("5", "$stem (x).json", 9), e("6", "$stem.json.bak", 9))))
    }

    private class FakeDocs : BackupDocuments {
        val names = linkedMapOf<String, String>()
        val content = linkedMapOf<String, String>()
        val modes = mutableListOf<String>()
        var finds = 0
        var creates = 0
        var next = 0
        var createName = NAME
        var rejectTruncate = false
        var removeSupported = true
        var createSupported = true

        fun add(id: String, name: String, text: String) {
            names[id] = name
            content[id] = text
        }

        fun vanishAll() {
            names.keys.toList().forEach(::forget)
        }

        private fun forget(id: String) {
            names -= id
            content -= id
        }

        override suspend fun find(treeUri: String, name: String): String? {
            finds++
            return names.entries.firstOrNull { it.value == name }?.key
        }

        override suspend fun create(treeUri: String, name: String): String? {
            if (!createSupported) return null
            creates++
            val id = "new${next++}"
            add(id, createName, "")
            return id
        }

        override suspend fun open(docUri: String, mode: String): OutputStream {
            modes += mode
            if (docUri !in names) throw FileNotFoundException(docUri)
            if (mode == "wt" && rejectTruncate) throw IllegalArgumentException("unsupported mode")
            return object : ByteArrayOutputStream() {
                override fun close() {
                    val written = toString(Charsets.UTF_8.name())
                    // "w" without truncate keeps the old tail, like the providers the plan warns about.
                    content[docUri] = if (mode == "w") written + content.getValue(docUri).drop(size()) else written
                }
            }
        }

        override suspend fun remove(docUri: String): Boolean {
            if (!removeSupported) return false
            forget(docUri)
            return true
        }
    }
}
