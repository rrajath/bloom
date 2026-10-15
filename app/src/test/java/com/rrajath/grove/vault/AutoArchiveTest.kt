package com.rrajath.grove.vault

import com.rrajath.grove.org.ArchiveTarget
import com.rrajath.grove.settings.GroveSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class AutoArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vault(): Vault = Vault(JvmFileStore(tmp.root))

    private val now = LocalDateTime.of(2026, 8, 1, 9, 0)

    @Test
    fun `plain when auto-archive is off`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO Task\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first()
        val settings = GroveSettings(autoArchiveDoneItems = false)

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "DONE", now)

        assertTrue(result is StateChangeResult.Plain)
        assertTrue((result as StateChangeResult.Plain).text.contains("DONE Task"))
    }

    @Test
    fun `plain when the new keyword is not a done-type keyword`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO Task\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first()
        val settings = GroveSettings(autoArchiveDoneItems = true, autoArchiveFile = "archive.org")

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "IN-PROGRESS", now)

        assertTrue(result is StateChangeResult.Plain)
    }

    @Test
    fun `plain when auto-archive is on but nothing resolves a destination`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO Task\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first()
        val settings = GroveSettings(autoArchiveDoneItems = true) // no autoArchiveFile set

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "DONE", now)

        assertTrue(result is StateChangeResult.Plain)
    }

    @Test
    fun `archives same-file to the heading's own ARCHIVE property`() = runTest {
        tmp.newFile("todo.org").writeText(
            """
            * TODO Task
            :PROPERTIES:
            :ARCHIVE: ./todo.org::* Archive
            :END:
            * Archive
            """.trimIndent() + "\n"
        )
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first { it.title == "Task" }
        val settings = GroveSettings(autoArchiveDoneItems = true)

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "DONE", now)

        assertTrue(result is StateChangeResult.Archived)
        result as StateChangeResult.Archived
        assertEquals("todo.org", result.sourceFile)
        assertEquals("todo.org", result.destFile)
        assertEquals("todo › Archive", result.label)
        assertTrue(result.sourceDoc.headlines.none { it.title == "Task" && it.level == 1 })
        val archived = result.sourceDoc.headlines.first { it.title == "Task" }
        assertEquals("DONE", archived.keyword)
        assertEquals(2, archived.level)
    }

    @Test
    fun `settings fallback is null when auto-archive is off`() {
        val on = GroveSettings(autoArchiveDoneItems = true, autoArchiveFile = "archive.org", autoArchiveHeadingPath = "Done")
        assertEquals(com.rrajath.grove.org.ArchiveTarget("archive.org", listOf("Done")), AutoArchive.settingsFallback(on))
        org.junit.Assert.assertNull(AutoArchive.settingsFallback(on.copy(autoArchiveDoneItems = false)))
    }

    @Test
    fun `file-level drawer ARCHIVE beats the Settings fallback and creates the file without adding org`() = runTest {
        tmp.newFile("agenda.org").writeText(
            """
            :PROPERTIES:
            :ARCHIVE: %s_archive::* Done
            :END:
            * TODO Task
            """.trimIndent() + "\n"
        )
        val v = vault()
        val doc = v.open("agenda.org")!!
        val headline = doc.headlines.first { it.title == "Task" }
        val settings = GroveSettings(
            autoArchiveDoneItems = true,
            autoArchiveFile = "archive.org",
            autoArchiveHeadingPath = "Settings",
        )

        val result = AutoArchive.apply(v, settings, doc, "agenda.org", headline, "DONE", now)

        assertTrue(result is StateChangeResult.Archived)
        result as StateChangeResult.Archived
        assertEquals("agenda.org_archive", result.destFile)
        assertTrue(java.io.File(tmp.root, "agenda.org_archive").exists())
        assertTrue(!java.io.File(tmp.root, "agenda.org_archive.org").exists())
        assertTrue(result.destText.contains("* Done"))
    }

    @Test
    fun `archives cross-file using the Settings fallback, creating the destination file`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO Task\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first()
        val settings = GroveSettings(
            autoArchiveDoneItems = true,
            autoArchiveFile = "archive.org",
            autoArchiveHeadingPath = "Done",
        )

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "DONE", now)

        assertTrue(result is StateChangeResult.Archived)
        result as StateChangeResult.Archived
        assertEquals("todo.org", result.sourceFile)
        assertEquals("archive.org", result.destFile)
        assertEquals("archive › Done", result.label)
        assertEquals("", result.destTextBefore.trim())
        assertTrue(result.destText.contains("Done"))
        assertTrue(result.destText.contains("DONE Task"))
        assertTrue(result.sourceDoc.headlines.isEmpty())
    }

    @Test
    fun `auto-archive stamps Emacs ARCHIVE properties on the moved heading`() = runTest {
        tmp.newFile("todo.org").writeText("* Projects\n** TODO Task\n:PROPERTIES:\n:ID: abc\n:END:\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first { it.title == "Task" }
        val settings = GroveSettings(autoArchiveDoneItems = true, autoArchiveFile = "archive.org")

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "DONE", now)

        result as StateChangeResult.Archived
        val drawer = result.destText.substringAfter(":PROPERTIES:\n").substringBefore(":END:")
        assertEquals(
            ":ID: abc\n" +
                ":ARCHIVE_TIME: 2026-08-01 Sat 09:00\n" +
                ":ARCHIVE_FILE: ${File(tmp.root, "todo.org").absolutePath}\n" +
                ":ARCHIVE_OLPATH: Projects\n" +
                ":ARCHIVE_CATEGORY: todo\n" +
                ":ARCHIVE_TODO: DONE\n",
            drawer,
        )
    }

    @Test
    fun `a plain refile adds no ARCHIVE properties`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO Task\n")
        val v = vault()
        val doc = v.open("todo.org")!!

        val write = AutoArchive.refileSubtree(v, doc, "todo.org", doc.headlines.first(), ArchiveTarget("other.org", emptyList()))!!

        assertFalse(write.destText.contains("ARCHIVE_"))
    }

    @Test
    fun `a heading's own ARCHIVE property wins over the Settings fallback`() = runTest {
        tmp.newFile("todo.org").writeText(
            """
            * TODO Task
            :PROPERTIES:
            :ARCHIVE: ./own.org::* Own
            :END:
            """.trimIndent() + "\n"
        )
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first()
        val settings = GroveSettings(
            autoArchiveDoneItems = true,
            autoArchiveFile = "fallback.org",
        )

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "DONE", now)

        assertTrue(result is StateChangeResult.Archived)
        assertEquals("own.org", (result as StateChangeResult.Archived).destFile)
    }

    @Test
    fun `any done-type keyword triggers archiving, not just literal DONE`() = runTest {
        // OrgKeywords.DEFAULT ("TODO IN-PROGRESS | DONE CANCELLED") treats CANCELLED as done-type too.
        tmp.newFile("todo.org").writeText("* TODO Task\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val headline = doc.headlines.first()
        val settings = GroveSettings(autoArchiveDoneItems = true, autoArchiveFile = "archive.org")

        val result = AutoArchive.apply(v, settings, doc, "todo.org", headline, "CANCELLED", now)

        assertTrue(result is StateChangeResult.Archived)
    }

    // --- applyToEach (bulk state change) ---

    @Test
    fun `applyToEach sets the keyword on each heading and skips ones already there`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO One\n* DONE Two\n* Three\n* TODO Untouched\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val lines = doc.headlines.filter { it.title != "Untouched" }.map { it.lineIndex }.toSet()

        val result = AutoArchive.applyToEach(
            v, GroveSettings(autoArchiveDoneItems = false), doc, "todo.org", lines, "DONE", now,
        )!!

        assertEquals(2, result.changed)
        assertEquals(
            listOf("DONE", "DONE", "DONE", "TODO"),
            result.sourceDoc.headlines.map { it.keyword },
        )
        assertTrue(result.destWrites.isEmpty())
        // The CLOSED lines shift the headings below; the remap follows them.
        val three = doc.headlines.first { it.title == "Three" }.lineIndex
        assertEquals(result.sourceDoc.headlines.first { it.title == "Three" }.lineIndex, result.remap(three))
    }

    @Test
    fun `applyToEach archives every heading that ends up done, in their original order`() = runTest {
        tmp.newFile("todo.org").writeText("* TODO One\n** TODO Child\n* TODO Keep\n* TODO Two\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val lines = doc.headlines.filter { it.title in setOf("One", "Two") }.map { it.lineIndex }.toSet()
        val settings = GroveSettings(autoArchiveDoneItems = true, autoArchiveFile = "archive.org")

        val result = AutoArchive.applyToEach(v, settings, doc, "todo.org", lines, "DONE", now)!!

        assertEquals(listOf("Keep"), result.sourceDoc.headlines.map { it.title })
        assertEquals(listOf("archive", "archive"), result.archivedTo)
        val (file, before, after) = result.destWrites.single()
        assertEquals("archive.org", file)
        assertEquals("", before)
        assertTrue("subtrees keep their order", after.indexOf("DONE One") < after.indexOf("DONE Two"))
        assertTrue("children travel along", after.indexOf("DONE One") < after.indexOf("TODO Child"))
        // Nothing is written by applyToEach itself.
        assertEquals("* TODO One\n** TODO Child\n* TODO Keep\n* TODO Two\n", File(tmp.root, "todo.org").readText())
    }

    @Test
    fun `applyToEach archives same-file without losing headings in between`() = runTest {
        tmp.newFile("todo.org").writeText(
            """
            #+ARCHIVE: ::* Archive
            * TODO One
            * TODO Keep
            * TODO Two
            """.trimIndent() + "\n"
        )
        val v = vault()
        val doc = v.open("todo.org")!!
        val lines = doc.headlines.filter { it.title in setOf("One", "Two") }.map { it.lineIndex }.toSet()
        val settings = GroveSettings(autoArchiveDoneItems = true)

        val result = AutoArchive.applyToEach(v, settings, doc, "todo.org", lines, "DONE", now)!!

        assertEquals(
            listOf("* Keep", "* Archive", "** One", "** Two"),
            result.sourceDoc.headlines.map { "*".repeat(it.level) + " " + it.title },
        )
        assertTrue(result.destWrites.isEmpty())
        assertEquals(listOf("todo › Archive", "todo › Archive"), result.archivedTo)
    }

    @Test
    fun `applyToEach returns null when every heading already has the keyword`() = runTest {
        tmp.newFile("todo.org").writeText("* DONE One\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val result = AutoArchive.applyToEach(
            v, GroveSettings(), doc, "todo.org", setOf(doc.headlines.first().lineIndex), "DONE", now,
        )
        assertEquals(null, result)
    }

    @Test
    fun `refileEach leaves headings whose destination file doesn't exist when not creating it`() = runTest {
        tmp.newFile("todo.org").writeText("* One\n* Two\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val target = ArchiveTarget("missing.org", emptyList())

        val result = AutoArchive.refileEach(
            v, doc, "todo.org", doc.headlines.map { it.lineIndex to target }, createFileIfMissing = false,
        )

        assertTrue(result.labels.isEmpty())
        assertEquals(doc.text, result.sourceText)
        assertFalse(File(tmp.root, "missing.org").exists())
    }

    @Test
    fun `refileEach archives several headings to one file in order, stamping each`() = runTest {
        tmp.newFile("todo.org").writeText("* One\n* Keep\n* Two\n")
        val v = vault()
        val doc = v.open("todo.org")!!
        val target = ArchiveTarget("archive.org", listOf("Done"))
        val sources = doc.headlines.filter { it.title != "Keep" }.map { it.lineIndex to target }

        val result = AutoArchive.refileEach(v, doc, "todo.org", sources, createFileIfMissing = true, archivedAt = now)

        assertEquals(listOf("Keep"), result.sourceDoc.headlines.map { it.title })
        assertEquals(listOf("archive › Done", "archive › Done"), result.labels)
        val after = result.destWrites.single().third
        assertTrue(after.indexOf("** One") < after.indexOf("** Two"))
        assertEquals(2, Regex(":ARCHIVE_TIME:").findAll(after).count())
    }
}
