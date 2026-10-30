package com.rrajath.grove.vault

import com.rrajath.grove.org.OrgParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LineEndingFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = LineEndingFileStore(JvmFileStore(tmp.root))

    private fun onDisk(name: String) = tmp.root.resolve(name).readText()

    private val crlfNote = listOf(
        ":PROPERTIES:",
        ":ID:       441d116d-8731-4338-adab-9b6a5e77c6e7",
        ":END:",
        "#+title: Push ups",
        "",
        "* Progression",
        "** Wall push ups",
        "#+attr_org: :width 300",
        "- Be a straight line",
        "",
    ).joinToString("\r\n")

    @Test
    fun `read strips carriage returns from a CRLF file`() = runTest {
        tmp.newFile("a.org").writeText("* One\r\n** Two\r\n")
        assertEquals("* One\n** Two\n", store().read("a.org"))
    }

    @Test
    fun `a CRLF file parses like its LF twin`() = runTest {
        tmp.newFile("roam.org").writeText(crlfNote)
        val doc = OrgParser.parse(store().read("roam.org"))

        assertEquals(listOf("Progression", "Wall push ups"), doc.headlines.map { it.title })
        assertEquals("441d116d-8731-4338-adab-9b6a5e77c6e7", doc.fileId)
        assertEquals(listOf("#+title:" to "Push ups"), doc.preambleKeywords)
    }

    @Test
    fun `writing a file that was read as CRLF keeps CRLF`() = runTest {
        tmp.newFile("a.org").writeText("* One\r\n")
        val s = store()
        s.write("a.org", s.read("a.org") + "* Two\n")
        assertEquals("* One\r\n* Two\r\n", onDisk("a.org"))
    }

    @Test
    fun `writing an LF file normalizes stray CRLF in the new content`() = runTest {
        tmp.newFile("a.org").writeText("* One\n")
        val s = store()
        s.write("a.org", s.read("a.org") + "* Pasted\r\nbody\r\n")
        assertEquals("* One\n* Pasted\nbody\n", onDisk("a.org"))
    }

    @Test
    fun `a new file is written with LF`() = runTest {
        val s = store()
        s.create("new.org")
        s.write("new.org", "* Hi\r\n")
        assertEquals("* Hi\n", onDisk("new.org"))
    }

    @Test
    fun `rename carries the CRLF flag to the new name`() = runTest {
        tmp.newFile("old.org").writeText("* One\r\n")
        val s = store()
        s.read("old.org")
        s.rename("old.org", "moved/new.org")
        s.write("moved/new.org", "* One\n* Two\n")
        assertEquals("* One\r\n* Two\r\n", onDisk("moved/new.org"))
    }

    @Test
    fun `a file converted to LF elsewhere is written back as LF`() = runTest {
        tmp.newFile("a.org").writeText("* One\r\n")
        val s = store()
        s.read("a.org")
        tmp.root.resolve("a.org").writeText("* One\n")
        s.write("a.org", s.read("a.org") + "* Two\n")
        assertFalse('\r' in onDisk("a.org"))
    }
}
