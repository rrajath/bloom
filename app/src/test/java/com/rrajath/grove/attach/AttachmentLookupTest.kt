package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Read-mode lookup of the file behind a tapped `attachment:` / `file:` link. */
class AttachmentLookupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lookup by lazy { AttachmentLookup(AttachmentFiles(JvmFileStore(tmp.root, skipAttachShards = true))) }

    private fun put(path: String) = File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText("x")

    private suspend fun find(target: String, text: String, orgFile: String = "notes.org", prefer: IntRange? = null): String? {
        val link = AttachmentLink.classify(target, null)!!
        return lookup.find(link, target, orgFile, OrgParser.parse(text), prefer)
    }

    private val twoHeadings = """
        * Lunch
        :PROPERTIES:
        :ID: abcdef
        :END:
        [[attachment:IMG_1.jpg]]
        * Dinner
        :PROPERTIES:
        :ID: 123456
        :END:
        [[attachment:IMG_1.jpg]]
    """.trimIndent() + "\n"

    @Test
    fun `attachment link resolves through the owning heading's ID dir`() = runTest {
        put("data/ab/cdef/IMG_1.jpg")
        assertEquals("data/ab/cdef/IMG_1.jpg", find("attachment:IMG_1.jpg", twoHeadings))
    }

    @Test
    fun `attachment link in a subfolder notebook resolves next to it`() = runTest {
        put("work/data/ab/cdef/IMG_1.jpg")
        assertEquals("work/data/ab/cdef/IMG_1.jpg", find("attachment:IMG_1.jpg", twoHeadings, orgFile = "work/n.org"))
    }

    @Test
    fun `a repeated link prefers the occurrence inside the note on screen`() = runTest {
        put("data/ab/cdef/IMG_1.jpg")
        put("data/12/3456/IMG_1.jpg")
        assertEquals("data/ab/cdef/IMG_1.jpg", find("attachment:IMG_1.jpg", twoHeadings))
        assertEquals("data/12/3456/IMG_1.jpg", find("attachment:IMG_1.jpg", twoHeadings, prefer = 5 until 10))
    }

    @Test
    fun `a repeated link falls back to the other owner when only that one has the file`() = runTest {
        put("data/12/3456/IMG_1.jpg")
        assertEquals("data/12/3456/IMG_1.jpg", find("attachment:IMG_1.jpg", twoHeadings))
    }

    @Test
    fun `DIR property wins and resolves against the org file's directory`() = runTest {
        put("work/scans/receipt.pdf")
        val text = "* Taxes\n:PROPERTIES:\n:DIR: scans\n:END:\n[[attachment:receipt.pdf][Receipt]]\n"
        assertEquals("work/scans/receipt.pdf", find("attachment:receipt.pdf", text, orgFile = "work/money.org"))
    }

    @Test
    fun `a link above the first heading uses the file-level ID`() = runTest {
        put("data/fi/le01/a.pdf")
        val text = ":PROPERTIES:\n:ID: file01\n:END:\n#+TITLE: T\n[[attachment:a.pdf]]\n* H\n"
        assertEquals("data/fi/le01/a.pdf", find("attachment:a.pdf", text))
    }

    @Test
    fun `missing file, missing dir and ID-less owner all resolve to null`() = runTest {
        put("data/ab/cdef/other.jpg")
        assertNull(find("attachment:IMG_1.jpg", twoHeadings))
        assertNull(find("attachment:IMG_1.jpg", "* No id\n[[attachment:IMG_1.jpg]]\n"))
    }

    @Test
    fun `attachment path cannot climb out of the vault`() = runTest {
        put("secret.txt")
        assertNull(find("attachment:../../../../secret.txt", twoHeadings.replace("IMG_1.jpg", "../../../../secret.txt")))
    }

    @Test
    fun `file link into an attach dir resolves against the org file's directory`() = runTest {
        put("work/data/ab/cdef/x.pdf")
        assertEquals("work/data/ab/cdef/x.pdf", find("file:data/ab/cdef/x.pdf", "", orgFile = "work/n.org"))
        assertEquals("work/data/ab/cdef/x.pdf", find("./data/ab/cdef/x.pdf::3", "", orgFile = "work/n.org"))
        assertNull(find("file:data/ab/cdef/gone.pdf", "", orgFile = "work/n.org"))
    }

    @Test
    fun `absolute and home file links are outside the vault`() = runTest {
        put("x.pdf")
        assertNull(find("file:/x.pdf", ""))
        assertNull(find("file:~/x.pdf", ""))
    }
}
