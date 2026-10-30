package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineDiffTest {

    private val before = OrgParser.parse(
        """
        * TODO A
        - [ ] one
        - [ ] two
        * B
        * C
        """.trimIndent() + "\n"
    )

    private fun diff(after: String) = LineDiff(before, OrgParser.parse(after))

    private fun line(title: String) = before.headlines.first { it.title == title }.lineIndex

    @Test
    fun `lines before and after an insertion carry over, the later ones shifted`() {
        val d = diff(before.text.replace("* TODO A\n", "* TODO A\nCLOSED: [2026-10-30 Fri]\n"))
        assertEquals(line("A"), d.heading(line("A")))
        assertEquals(line("B") + 1, d.heading(line("B")))
        assertEquals(2, d.bodyLine(1))
    }

    @Test
    fun `a heading whose own line changed is followed by its title`() {
        val d = diff(before.text.replace("* TODO A\n", "* DONE A\nCLOSED: [2026-10-30 Fri]\n"))
        assertEquals(line("A"), d.heading(line("A")))
        assertEquals(line("C") + 1, d.heading(line("C")))
    }

    @Test
    fun `reordered headings inside the changed run are not followed`() {
        val d = diff(before.text.replace("* B\n* C\n", "* C\n* B\n"))
        assertNull(d.heading(line("B")))
        assertNull(d.heading(line("C")))
        assertEquals(line("A"), d.heading(line("A")))
    }

    @Test
    fun `a toggled checklist item is still followed, a rewritten line is not`() {
        assertEquals(1, diff(before.text.replace("- [ ] one", "- [X] one")).bodyLine(1))
        assertNull(diff(before.text.replace("- [ ] one", "- [ ] uno")).bodyLine(1))
    }
}
