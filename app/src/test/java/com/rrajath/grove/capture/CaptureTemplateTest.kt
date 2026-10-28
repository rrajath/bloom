package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTemplateTest {

    @Test
    fun `templates round-trip through json`() {
        val templates = DefaultTemplates.all +
                CaptureTemplate(
                    id = "custom",
                    name = "Meeting",
                    color = "blue",
                    targetFile = "work.org",
                    location = TargetLocation.UnderHeading(customId = "meetings", appendLast = true),
                    template = "* %^{Topic}\n%U\n%cursor",
                )
        val decoded = TemplateSerializer.decode(TemplateSerializer.encode(templates))
        assertEquals(templates, decoded)
    }

    @Test
    fun `legacy date-only datetree templates migrate to Datetree with date-only stamps`() {
        // Stored by builds that had separate Datetree (date) / (datetime) locations.
        val stored = """{"templates":[""" +
            """{"id":"d","name":"Daily","targetFile":"log.org","location":{"type":"datetree_date"},""" +
            """"template":"%U %^{Mood}\n%T %cursor"},""" +
            """{"id":"j","name":"Journal","targetFile":"journal.org","location":{"type":"datetree_datetime"},""" +
            """"template":"%U %cursor"}]}"""
        val decoded = TemplateSerializer.decode(stored)
        assertEquals(2, decoded.size)
        assertEquals(TargetLocation.Datetree(), decoded[0].location)
        assertEquals("%u %^{Mood}\n%t %cursor", decoded[0].template)
        // Datetime templates are untouched: same location, same text.
        assertEquals(TargetLocation.Datetree(), decoded[1].location)
        assertEquals("%U %cursor", decoded[1].template)
    }

    @Test
    fun `migrated date-only template expands exactly as it did before`() {
        val stored = """{"templates":[{"id":"d","name":"Daily","targetFile":"log.org",""" +
            """"location":{"type":"datetree_date"},"template":"%U %T %cursor"}]}"""
        val template = TemplateSerializer.decode(stored).single()
        val now = java.time.LocalDateTime.of(2025, 6, 11, 14, 32)
        // What Datetree (date) produced: %U/%T with the time stripped.
        assertEquals(
            "[2025-06-11 Wed] <2025-06-11 Wed> ",
            PlaceholderExpander.expand(template.template, CaptureContext(now = now)).text,
        )
    }

    @Test
    fun `merged Datetree keeps the datetree_datetime serial name`() {
        val encoded = TemplateSerializer.encode(DefaultTemplates.all.filter { it.location.isDatetree })
        assertTrue(encoded, encoded.contains("\"datetree_datetime\""))
    }

    @Test
    fun `roam node templates round-trip through json`() {
        val templates = listOf(
            CaptureTemplate(
                id = "roam1",
                name = "Roam Node",
                targetFile = "unused.org",
                location = TargetLocation.BottomOfFile,
                template = "unused",
                kind = TemplateKind.ROAM_NODE,
                roamDirectory = "notes/roam",
                filenamePattern = "%<%Y%m%d%H%M%S>-%(slug)",
                newFileTemplate = ":PROPERTIES:\n:ID:       %(id)\n:END:\n#+title: %?",
            ),
        )
        val decoded = TemplateSerializer.decode(TemplateSerializer.encode(templates))
        assertEquals(templates, decoded)
    }

    @Test
    fun `default templates decode as PLAIN kind`() {
        assertTrue(DefaultTemplates.all.all { it.kind == TemplateKind.PLAIN })
    }

    @Test
    fun `corrupt json decodes to empty list`() {
        assertEquals(emptyList<CaptureTemplate>(), TemplateSerializer.decode("not json"))
        assertEquals(emptyList<CaptureTemplate>(), TemplateSerializer.decode(""))
    }

    @Test
    fun `default templates match the PRD`() {
        val byName = DefaultTemplates.all.associateBy { it.name }
        assertEquals(3, byName.size)
        assertEquals("journal.org", byName["Journal Entry"]!!.targetFile)
        assertTrue(byName["Journal Entry"]!!.location.isDatetree)
        assertEquals("inbox.org", byName["Quick Note"]!!.targetFile)
        assertEquals(TargetLocation.BottomOfFile, byName["Quick Note"]!!.location)
        assertEquals("* %cursor", byName["Quick Note"]!!.template)
        assertEquals("tasks.org", byName["TODO"]!!.targetFile)
        assertTrue(byName["TODO"]!!.template.startsWith("* TODO %^{Title}"))
        assertTrue(!byName["TODO"]!!.template.contains("SCHEDULED"))
    }

    @Test
    fun `a plain Datetree encodes exactly as before, new fields round-trip`() {
        val plain = CaptureTemplate(id = "j", name = "J", targetFile = "j.org", location = TargetLocation.Datetree(), template = "x")
        assertTrue(
            TemplateSerializer.encode(listOf(plain)),
            TemplateSerializer.encode(listOf(plain)).contains("""{"type":"datetree_datetime"}"""),
        )
        val nested = listOf(
            plain.copy(location = TargetLocation.Datetree(id = "A", path = listOf("Work", "Meetings"), treeType = DatetreeType.WEEK)),
            plain.copy(id = "u", location = TargetLocation.UnderHeading(id = "B", path = listOf("Tasks"))),
        )
        assertEquals(nested, TemplateSerializer.decode(TemplateSerializer.encode(nested)))
    }

    @Test
    fun `built-in ids are stable for launcher shortcuts`() {
        // ShortcutSyncer keys each dynamic launcher shortcut by template id and
        // deep-links to bloom://capture/<id>. Renaming a built-in id would silently
        // break the launcher long-press shortcuts, so pin them here.
        val ids = DefaultTemplates.all.map { it.id }.toSet()
        assertTrue("Journal shortcut target missing", "builtin-journal" in ids)
        assertTrue("Quick Note shortcut target missing", "builtin-quick-note" in ids)
    }

    @Test
    fun `location descriptions for the picker`() {
        assertEquals("bottom of file", TargetLocation.BottomOfFile.describe())
        assertEquals("datetree", TargetLocation.Datetree().describe())
        assertEquals("under #x", TargetLocation.UnderHeading(customId = "x").describe())
        assertEquals("under #y", TargetLocation.UnderHeading(customId = "x", id = "y").describe())
        assertEquals("under Projects", TargetLocation.UnderHeading(title = "Projects").describe())
        assertEquals("under Tasks", TargetLocation.UnderHeading(id = "x", path = listOf("Work", "Tasks")).describe())
        assertEquals("datetree in Meetings", TargetLocation.Datetree(path = listOf("Work", "Meetings")).describe())
    }
}
