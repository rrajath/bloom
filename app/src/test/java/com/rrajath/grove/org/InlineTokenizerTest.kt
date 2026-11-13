package com.rrajath.grove.org

import com.rrajath.grove.org.math.MathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTokenizerTest {

    private fun types(line: String) = InlineTokenizer.tokenize(line).map { it.type }

    @Test
    fun `plain text is one token`() {
        val tokens = InlineTokenizer.tokenize("just some text")
        assertEquals(listOf(InlineType.TEXT), tokens.map { it.type })
        assertEquals("just some text", tokens[0].text)
    }

    @Test
    fun `tokens cover the whole line`() {
        val line = "a *b* c [[https://x.io][link]] d <2025-01-01 Wed> e"
        val tokens = InlineTokenizer.tokenize(line)
        assertEquals(line.length, tokens.sumOf { it.range.last - it.range.first + 1 })
        assertEquals(0, tokens.first().range.first)
        assertEquals(line.length - 1, tokens.last().range.last)
    }

    @Test
    fun `emphasis variants`() {
        assertEquals(listOf(InlineType.BOLD), types("*bold*"))
        assertEquals(listOf(InlineType.ITALIC), types("/italic/"))
        assertEquals(listOf(InlineType.UNDERLINE), types("_under_"))
        assertEquals(listOf(InlineType.STRIKETHROUGH), types("+strike+"))
        assertEquals(listOf(InlineType.CODE), types("~code~"))
        assertEquals(listOf(InlineType.CODE), types("`code`"))
        assertEquals(listOf(InlineType.VERBATIM), types("=verb="))
    }

    @Test
    fun `emphasis strips markers from display text`() {
        val token = InlineTokenizer.tokenize("say *hello* now")[1]
        assertEquals(InlineType.BOLD, token.type)
        assertEquals("hello", token.text)
    }

    @Test
    fun `mid-word asterisks are not emphasis`() {
        assertEquals(listOf(InlineType.TEXT), types("2*3*4 equals 24"))
        assertEquals(listOf(InlineType.TEXT), types("a*b"))
    }

    @Test
    fun `bracket link with label`() {
        val token = InlineTokenizer.tokenize("see [[https://example.com][the site]] ok")[1]
        assertEquals(InlineType.LINK, token.type)
        assertEquals("the site", token.text)
        assertEquals("https://example.com", token.target)
    }

    @Test
    fun `bracket link without label shows target`() {
        val token = InlineTokenizer.tokenize("[[id:abc-123]]")[0]
        assertEquals(InlineType.LINK, token.type)
        assertEquals("id:abc-123", token.text)
        assertEquals("id:abc-123", token.target)
    }

    @Test
    fun `custom id link`() {
        val token = InlineTokenizer.tokenize("see [[#kyoto-day-2]]")[1]
        assertEquals("#kyoto-day-2", token.target)
    }

    @Test
    fun `bare url`() {
        val tokens = InlineTokenizer.tokenize("go to https://example.com/x?y=1 now")
        val link = tokens.first { it.type == InlineType.LINK }
        assertEquals("https://example.com/x?y=1", link.target)
    }

    @Test
    fun `scheme links`() {
        for (target in listOf("mailto:a@b.c", "tel:+15551234", "geo:35.0,135.7")) {
            val tokens = InlineTokenizer.tokenize("contact $target here")
            assertEquals(target, tokens.first { it.type == InlineType.LINK }.target)
        }
    }

    @Test
    fun `phone numbers become tappable and formatted`() {
        for (raw in listOf("833-806-1627", "(833) 806-1627", "833.806.1627", "+1 833-806-1627", "1-833-806-1627")) {
            val tokens = InlineTokenizer.tokenize("call $raw now")
            val link = tokens.first { it.type == InlineType.LINK }
            assertEquals("(833) 806-1627", link.text)
            assertEquals("tel:+18338061627", link.target)
        }
    }

    @Test
    fun `bare digit runs are not phone numbers`() {
        assertEquals(listOf(InlineType.TEXT), types("order id 8338061627"))
        assertEquals(listOf(InlineType.TEXT), types("version 1.2.3.4567"))
    }

    @Test
    fun `explicit tel scheme link is untouched by phone formatting`() {
        val tokens = InlineTokenizer.tokenize("contact tel:+15551234 here")
        val link = tokens.first { it.type == InlineType.LINK }
        assertEquals("tel:+15551234", link.target)
        assertEquals("tel:+15551234", link.text)
    }

    @Test
    fun `timestamps tokenize`() {
        val tokens = InlineTokenizer.tokenize("due <2025-06-27 Fri> and noted [2025-01-04 Sat]")
        assertEquals(2, tokens.count { it.type == InlineType.TIMESTAMP })
    }

    @Test
    fun `emphasis inside link is not split`() {
        val tokens = InlineTokenizer.tokenize("[[https://x.io/a*b*c][label]]")
        assertEquals(1, tokens.size)
        assertEquals(InlineType.LINK, tokens[0].type)
    }

    @Test
    fun `italic does not trigger inside urls`() {
        val tokens = InlineTokenizer.tokenize("https://example.com/path/to/thing and more")
        val link = tokens.first { it.type == InlineType.LINK }
        assertEquals("https://example.com/path/to/thing", link.target)
        assertTrue(tokens.none { it.type == InlineType.ITALIC })
    }

    @Test
    fun `inline math is one token that keeps its raw source`() {
        val tokens = InlineTokenizer.tokenize("let ${'$'}x^2${'$'} be")
        assertEquals(listOf(InlineType.TEXT, InlineType.MATH, InlineType.TEXT), tokens.map { it.type })
        val math = tokens[1]
        assertEquals("${'$'}x^2${'$'}", math.text)
        assertEquals("x^2", math.math!!.body)
        assertEquals(MathKind.INLINE, math.math!!.kind)
        assertNull(tokens[0].math)
    }

    @Test
    fun `math tokens keep offsets aligned with the rendered text`() {
        val line = "a ${'$'}\\alpha${'$'} b \\(c\\) d ${'$'}${'$'}e${'$'}${'$'}"
        val tokens = InlineTokenizer.tokenize(line)
        assertEquals(line.length, tokens.sumOf { it.range.last - it.range.first + 1 })
        assertEquals(line, tokens.joinToString("") { it.text })
        assertEquals(listOf(MathKind.INLINE, MathKind.INLINE, MathKind.DISPLAY), tokens.mapNotNull { it.math?.kind })
    }

    @Test
    fun `markup characters inside math are not emphasis`() {
        assertEquals(listOf(InlineType.MATH), types("${'$'}a +b+ c${'$'}"))
        assertEquals(listOf(InlineType.MATH), types("${'$'}x_i * y_i${'$'}"))
        assertEquals(listOf(InlineType.MATH), types("${'$'}a /b/ c${'$'}"))
    }

    @Test
    fun `emphasis outside math still works`() {
        assertEquals(
            listOf(InlineType.BOLD, InlineType.TEXT, InlineType.MATH),
            types("*bold* and ${'$'}x${'$'}"),
        )
    }

    @Test
    fun `prices stay plain text`() {
        assertEquals(listOf(InlineType.TEXT), types("costs ${'$'}5 and ${'$'}10"))
    }

    @Test
    fun `a link keeps priority over math that overlaps it`() {
        val tokens = InlineTokenizer.tokenize("[[https://x.io/${'$'}a${'$'}][label]]")
        assertEquals(listOf(InlineType.LINK), tokens.map { it.type })
    }
}
