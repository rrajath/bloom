package com.rrajath.grove.attach

import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.JvmFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentScopeScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultDir by lazy { tmp.newFolder("vault") }
    private val files by lazy { AttachmentFiles(JvmFileStore(vaultDir, skipAttachShards = true)) }
    private val resolver by lazy { AttachDirResolver(files) }
    private val scanner by lazy { AttachmentScopeScanner(files) }

    private fun put(dir: String, name: String, modified: Long) {
        val f = File(vaultDir, "$dir/$name")
        f.parentFile!!.mkdirs()
        f.writeText(name)
        f.setLastModified(modified)
    }

    private fun drawer(vararg props: String) =
        ":PROPERTIES:\n" + props.joinToString("") { "$it\n" } + ":END:\n"

    @Test fun `file scope lists file-level and heading dirs newest first`() = runTest {
        val doc = OrgParser.parse(drawer(":ID: F1") + "* A\n" + drawer(":ID: H1") + "* B\nbody\n")
        val fileDir = resolver.writeDir("x.org", doc.fileOwner())!!
        val aDir = resolver.writeDir("x.org", doc.ownerOf(doc.headlines[0]))!!
        put(fileDir, "f.pdf", 1_000_000)
        put(aDir, "a.jpg", 2_000_000)

        val rows = scanner.scanFile("x.org", doc)

        assertEquals(listOf("a.jpg", "f.pdf"), rows.map { it.file.name.substringAfterLast('/') })
        assertEquals(listOf("A", "x.org"), rows.map { it.source })
        assertEquals(listOf("A", null), rows.map { it.ownerTitle })
    }

    @Test fun `same file name under two headings lists both with their own dir`() = runTest {
        val doc = OrgParser.parse("* A\n" + drawer(":ID: H1") + "* B\n" + drawer(":ID: H2"))
        put(resolver.writeDir("x.org", doc.ownerOf(doc.headlines[0]))!!, "photo.jpg", 1_000_000)
        put(resolver.writeDir("x.org", doc.ownerOf(doc.headlines[1]))!!, "photo.jpg", 2_000_000)

        val rows = scanner.scanFile("x.org", doc)

        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.dir }.toSet().size)
        assertEquals(2, rows.map { it.file.name }.toSet().size)
    }

    @Test fun `owners sharing a dir are listed once`() = runTest {
        val doc = OrgParser.parse(drawer(":DIR: shared") + "* A\n" + drawer(":DIR: shared"))
        put("shared", "one.pdf", 1_000_000)
        put("shared", "two.pdf", 2_000_000)

        val rows = scanner.scanFile("x.org", doc)

        assertEquals(listOf("shared/two.pdf", "shared/one.pdf"), rows.map { it.file.name })
    }

    @Test fun `vault scope uses the buffer for the current file and the index for others`() = runTest {
        val doc = OrgParser.parse("* A\n" + drawer(":ID: H1"))
        put(resolver.writeDir("x.org", doc.ownerOf(doc.headlines[0]))!!, "a.jpg", 2_000_000)
        val tripDir = resolver.writeDir("y/j.org", AttachOwner.File("J1", null))!!
        assertTrue(tripDir.startsWith("y/data/"))
        put(tripDir, "p.jpg", 1_000_000)
        val stale = resolver.writeDir("x.org", AttachOwner.File("H9", null))!!
        put(stale, "old.jpg", 3_000_000)

        val rows = scanner.scanVault(
            "x.org", doc,
            listOf(IndexedAttachOwner("x.org", "Stale", "H9"), IndexedAttachOwner("y/j.org", "Trip notes", "J1")),
        )

        assertEquals(listOf("x.org › A", "j.org › Trip notes"), rows.map { it.source })
        assertEquals(listOf("x.org", "y/j.org"), rows.map { it.ownerOrgFile })
    }
}
