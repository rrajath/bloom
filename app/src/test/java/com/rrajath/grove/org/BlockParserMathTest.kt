package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockParserMathTest {

    private fun parse(body: String) = BlockParser.parse(body.lines())

    private fun env(vararg lines: String) = OrgBlock.MathEnvironment(lines.toList(), 0)

    @Test
    fun `an align environment is one block`() {
        val blocks = parse("\\begin{align*}\n  x &= 1 \\\\\n  y &= 2\n\\end{align*}")
        assertEquals(listOf(env("\\begin{align*}", "  x &= 1 \\\\", "  y &= 2", "\\end{align*}")), blocks)
    }

    @Test
    fun `align and align star both count`() {
        assertTrue(parse("\\begin{align}\nx\n\\end{align}").single() is OrgBlock.MathEnvironment)
        assertTrue(parse("\\begin{align*}\nx\n\\end{align*}").single() is OrgBlock.MathEnvironment)
    }

    @Test
    fun `a blank line inside does not split it`() {
        val blocks = parse("\\begin{align*}\nx &= 1 \\\\\n\ny &= 2\n\\end{align*}")
        assertEquals(1, blocks.size)
        assertEquals(5, (blocks.single() as OrgBlock.MathEnvironment).lines.size)
    }

    @Test
    fun `lines that look like list items tables or numbering stay inside`() {
        for (inner in listOf("- y &= 2", "+ y &= 2", "|y| &= 2", "2) y &= 2", "1. y &= 2")) {
            val blocks = parse("\\begin{align}\nx &= 1 \\\\\n$inner\n\\end{align}")
            assertEquals("for '$inner'", 1, blocks.size)
            assertTrue("for '$inner'", blocks.single() is OrgBlock.MathEnvironment)
        }
    }

    @Test
    fun `text around an environment stays as paragraphs with correct start lines`() {
        val blocks = parse("before\n\\begin{align}\nx\n\\end{align}\nafter")
        assertEquals(3, blocks.size)
        assertEquals(OrgBlock.Paragraph(listOf("before"), 0), blocks[0])
        assertEquals(OrgBlock.MathEnvironment(listOf("\\begin{align}", "x", "\\end{align}"), 1), blocks[1])
        assertEquals(OrgBlock.Paragraph(listOf("after"), 4), blocks[2])
    }

    @Test
    fun `an indented environment is recognised`() {
        assertTrue(parse("  \\begin{align}\n  x\n  \\end{align}").single() is OrgBlock.MathEnvironment)
    }

    @Test
    fun `every math environment kind is recognised`() {
        for (name in listOf("equation", "equation*", "gather", "multline", "cases", "pmatrix", "bmatrix", "array")) {
            assertTrue(name, parse("\\begin{$name}\nx\n\\end{$name}").single() is OrgBlock.MathEnvironment)
        }
    }

    @Test
    fun `an unclosed environment is left as ordinary text`() {
        val blocks = parse("\\begin{align}\nx &= 1")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is OrgBlock.Paragraph)
    }

    @Test
    fun `text after the closing end leaves it to the inline scanner`() {
        assertTrue(parse("\\begin{align}\nx\n\\end{align} trailing").single() is OrgBlock.Paragraph)
    }

    @Test
    fun `an unknown environment is not math`() {
        assertTrue(parse("\\begin{itemize}\nx\n\\end{itemize}").single() is OrgBlock.Paragraph)
    }

    @Test
    fun `same name environments nest`() {
        val src = listOf("\\begin{matrix}", "\\begin{matrix} a \\end{matrix} & b", "\\end{matrix}")
        val block = parse(src.joinToString("\n")).single() as OrgBlock.MathEnvironment
        assertEquals(src, block.lines)
    }

    @Test
    fun `two environments in a row are two blocks`() {
        val blocks = parse("\\begin{align}\na\n\\end{align}\n\\begin{align}\nb\n\\end{align}")
        assertEquals(listOf(true, true), blocks.map { it is OrgBlock.MathEnvironment })
    }

    @Test
    fun `an environment in a list item stays with the item and keeps its line breaks`() {
        val blocks = parse("- step one:\n  \\begin{align*}\n  x &= 1 \\\\\n  - y &= 2\n\n  |z| &= 3\n  \\end{align*}\n- step two")
        val items = (blocks.single() as OrgBlock.ListBlock).items
        assertEquals(2, items.size)
        assertEquals("step one: \\begin{align*}\nx &= 1 \\\\\n- y &= 2\n\n|z| &= 3\n\\end{align*}", items[0].text)
        assertEquals("step two", items[1].text)
    }

    @Test
    fun `plain list continuation is unchanged`() {
        val items = (parse("- one\n  more words").single() as OrgBlock.ListBlock).items
        assertEquals("one more words", items.single().text)
    }

    @Test
    fun `random environment-like text never crashes the parser and keeps every line`() {
        val pieces = listOf(
            "\\begin{align}", "\\end{align}", "\\begin{align*}", "\\end{align*}", "\\begin{matrix}", "\\end{matrix}",
            "\\begin{itemize}", "- item", "+ item", "|a|b|", "", "   ", "text", "  indented", "* heading", "#+begin_src", "#+end_src",
            "x &= 1 \\\\", "\\begin{", "\\end{", "$$", "\\[", "1. one", "  \\begin{align}", "  \\end{align}",
        )
        val random = kotlin.random.Random(85)
        repeat(3000) {
            val lines = List(random.nextInt(1, 14)) { pieces[random.nextInt(pieces.size)] }
            val blocks = BlockParser.parse(lines)
            for (b in blocks.filterIsInstance<OrgBlock.MathEnvironment>()) {
                assertTrue(b.lines.first().trimStart().startsWith("\\begin{"))
                assertTrue(b.lines.last().contains("\\end{"))
            }
        }
    }
}
