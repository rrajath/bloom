package com.rrajath.grove.org.math

import org.junit.Assert.assertEquals
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
}
