package com.rrajath.grove.org.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathFragmentsTest {

    private fun find(text: String) = MathFragments.find(text)

    private fun bodies(text: String) = find(text).map { it.body }

    @Test
    fun `plain text has no fragments`() {
        assertTrue(find("just some words").isEmpty())
        assertTrue(find("").isEmpty())
    }

    @Test
    fun `single dollar is inline math`() {
        val f = find("let ${'$'}x^2 - 2x + 1 = 0${'$'} hold").single()
        assertEquals(MathKind.INLINE, f.kind)
        assertEquals("x^2 - 2x + 1 = 0", f.body)
        assertEquals(4..21, f.range)
    }

    @Test
    fun `escaped parens are inline and escaped brackets are display`() {
        val inline = find("so \\(a+b\\) done").single()
        assertEquals(MathKind.INLINE, inline.kind)
        assertEquals("a+b", inline.body)
        val display = find("\\[a+b\\]").single()
        assertEquals(MathKind.DISPLAY, display.kind)
        assertEquals("a+b", display.body)
    }

    @Test
    fun `double dollar is display math`() {
        val f = find("${'$'}${'$'}x = 1${'$'}${'$'}").single()
        assertEquals(MathKind.DISPLAY, f.kind)
        assertEquals("x = 1", f.body)
    }

    @Test
    fun `display math may span lines`() {
        val f = find("${'$'}${'$'}\nx = 1\n${'$'}${'$'}").single()
        assertEquals(MathKind.DISPLAY, f.kind)
        assertEquals("\nx = 1\n", f.body)
    }

    @Test
    fun `prices are not math`() {
        assertTrue(find("it costs ${'$'}5 and ${'$'}10 total").isEmpty())
        assertTrue(find("between ${'$'}5 and ${'$'}10.").isEmpty())
        assertTrue(find("${'$'}HOME/bin:${'$'}PATH").isEmpty())
        assertTrue(find("a lone ${'$'} sign").isEmpty())
    }

    @Test
    fun `dollar borders follow org rules`() {
        assertTrue(find("${'$'} x${'$'}").isEmpty()) // space after opener
        assertTrue(find("${'$'}x ${'$'}").isEmpty()) // space before closer
        assertTrue(find("${'$'}.5${'$'}").isEmpty()) // opener followed by a period
        assertEquals(listOf("x"), bodies("${'$'}x${'$'}")) // one-character body
        assertEquals(listOf("x"), bodies("see ${'$'}x${'$'}, then"))
        assertEquals(listOf("x"), bodies("${'$'}x${'$'}-axis"))
    }

    @Test
    fun `closing dollar followed by a letter is not math`() {
        assertTrue(find("${'$'}x${'$'}y").isEmpty())
        assertTrue(find("${'$'}5-${'$'}10").isEmpty())
    }

    @Test
    fun `closing dollar may be followed by Persian punctuation`() {
        assertEquals(listOf("x"), bodies("مقدار ${'$'}x${'$'}، است"))
        assertEquals(listOf("x"), bodies("چیست ${'$'}x${'$'}؟"))
    }

    @Test
    fun `escaped dollar is literal`() {
        assertTrue(find("pay \\${'$'}5 and \\${'$'}6").isEmpty())
        assertEquals(listOf("a \\${'$'} b"), bodies("${'$'}a \\${'$'} b${'$'}"))
    }

    @Test
    fun `double backslash does not open a paren fragment`() {
        assertTrue(find("line\\\\(not math)").isEmpty())
    }

    @Test
    fun `single dollar content spans at most three lines`() {
        assertEquals(1, find("${'$'}a\nb\nc${'$'}").size)
        assertTrue(find("${'$'}a\nb\nc\nd${'$'}").isEmpty())
    }

    @Test
    fun `empty bodies are ignored`() {
        assertTrue(find("\\(\\)").isEmpty())
        assertTrue(find("\\[  \\]").isEmpty())
        assertTrue(find("${'$'}${'$'}${'$'}${'$'}").isEmpty())
    }

    @Test
    fun `unterminated openers are ignored`() {
        assertTrue(find("\\(a+b").isEmpty())
        assertTrue(find("\\[a+b").isEmpty())
        assertTrue(find("${'$'}${'$'}a+b").isEmpty())
        assertTrue(find("${'$'}a+b").isEmpty())
    }

    @Test
    fun `math environments are display fragments with the whole environment as body`() {
        val src = "\\begin{align}\na &= b \\\\\nc &= d\n\\end{align}"
        val f = find("before\n$src\nafter").single()
        assertEquals(MathKind.DISPLAY, f.kind)
        assertEquals(src, f.body)
        assertEquals(src, ("before\n$src\nafter").substring(f.range))
    }

    @Test
    fun `non math environments are left alone`() {
        assertTrue(find("\\begin{itemize} x \\end{itemize}").isEmpty())
        assertTrue(find("\\begin{align} no end").isEmpty())
    }

    @Test
    fun `nested environments of the same name close at the matching end`() {
        val src = "\\begin{matrix} \\begin{matrix} a \\end{matrix} & b \\end{matrix}"
        assertEquals(listOf(src), bodies("x $src y"))
    }

    @Test
    fun `several fragments in one string are found in order`() {
        val f = find("${'$'}a${'$'} and \\(b\\) and ${'$'}${'$'}c${'$'}${'$'}")
        assertEquals(listOf("a", "b", "c"), f.map { it.body })
        assertEquals(listOf(MathKind.INLINE, MathKind.INLINE, MathKind.DISPLAY), f.map { it.kind })
    }

    // ---- environments by line --------------------------------------------------------

    @Test
    fun `environment opening line`() {
        assertEquals("align*", MathFragments.environmentOpeningLine("\\begin{align*}"))
        assertEquals("align", MathFragments.environmentOpeningLine("   \\begin{align} x &= 1 \\\\"))
        assertEquals(null, MathFragments.environmentOpeningLine("text \\begin{align}"))
        assertEquals(null, MathFragments.environmentOpeningLine("\\begin{itemize}"))
        assertEquals(null, MathFragments.environmentOpeningLine("plain"))
    }

    @Test
    fun `environment end line`() {
        val lines = listOf("\\begin{align}", "a &= b", "", "- c", "\\end{align}", "tail")
        assertEquals(4, MathFragments.environmentEndLine(lines, 0))
        assertEquals(null, MathFragments.environmentEndLine(lines, 1))
        assertEquals(null, MathFragments.environmentEndLine(listOf("\\begin{align}", "x"), 0))
        assertEquals(0, MathFragments.environmentEndLine(listOf("\\begin{align} x \\\\end{align}"), 0).let { if (it == null) null else 0 })
    }

    // ---- fragment under the cursor ---------------------------------------------------

    private fun at(text: String, marker: Char = '|'): MathFragment? {
        val offset = text.indexOf(marker)
        return MathFragments.at(text.replace(marker.toString(), ""), offset)
    }

    @Test
    fun `cursor inside inline math finds it`() {
        val f = at("let ${'$'}x^|2${'$'} hold")!!
        assertEquals("x^2", f.body)
        assertEquals(MathKind.INLINE, f.kind)
    }

    @Test
    fun `cursor touching either delimiter still counts`() {
        assertEquals("x", at("a |${'$'}x${'$'} b")!!.body)
        assertEquals("x", at("a ${'$'}x${'$'}| b")!!.body)
    }

    @Test
    fun `cursor outside any formula finds nothing`() {
        assertNull(at("a ${'$'}x${'$'} b |c"))
        assertNull(at("| plain text"))
        assertNull(at("price ${'$'}5 and |${'$'}10"))
    }

    @Test
    fun `cursor in display math and in an environment`() {
        assertEquals(MathKind.DISPLAY, at("${'$'}${'$'} a |= b ${'$'}${'$'}")!!.kind)
        val env = "\\begin{align}\na &= |b\n\\end{align}"
        val f = at(env)!!
        assertEquals(MathKind.DISPLAY, f.kind)
        assertEquals("\\begin{align}\na &= b\n\\end{align}", f.body)
    }

    @Test
    fun `an environment with blank lines is found`() {
        val f = at("\\begin{align}\na &= b\n\n|c &= d\n\\end{align}")!!
        assertTrue(f.body.endsWith("\\end{align}"))
    }

    @Test
    fun `an environment that is not closed yet is found while typing`() {
        val f = at("intro\n\n\\begin{align}\na &= b \\\\\nc &= |")!!
        assertEquals(MathKind.DISPLAY, f.kind)
        assertEquals("\\begin{align}\na &= b \\\\\nc &= ", f.body)
    }

    @Test
    fun `an unclosed environment stops at the next blank line`() {
        val f = at("\\begin{align}\na &= |b\n\nnext paragraph")!!
        assertEquals("\\begin{align}\na &= b", f.body)
    }

    @Test
    fun `an unclosed environment is not found after a blank line far from it`() {
        assertNull(at("\\begin{align}\na\n\nsome other text |here"))
    }

    @Test
    fun `an unclosed unknown environment is not math`() {
        assertNull(at("\\begin{itemize}\n|x"))
    }

    @Test
    fun `range is relative to the whole text`() {
        val text = "0123456789 ${'$'}ab${'$'} end"
        val f = MathFragments.at(text, 13)!!
        assertEquals("ab", text.substring(f.range.first + 1, f.range.last))
    }

    @Test
    fun `huge text is scanned around the cursor only`() {
        val filler = "word ".repeat(100_000)
        val text = filler + "see ${'$'}x^2${'$'} now" + filler
        val offset = filler.length + 8
        val f = MathFragments.at(text, offset)!!
        assertEquals("x^2", f.body)
        assertEquals("${'$'}x^2${'$'}", text.substring(f.range))
    }
}
