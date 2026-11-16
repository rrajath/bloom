package com.rrajath.grove.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.InlineType

/**
 * [orgInlineLinks] must report link ranges in the *rendered* string (labels
 * shown, not raw `[[target][label]]` syntax) since that's the coordinate
 * space callers hit-test against ([TextLayoutResult.getOffsetForPosition]).
 */
class OrgInlineLinksTest {

    @Test
    fun `no links in plain text`() {
        assertTrue(orgInlineLinks("just some text").isEmpty())
    }

    @Test
    fun `link range covers the rendered label, not the raw markup`() {
        val text = "see [[https://example.com][docs]] for more"
        val rendered = "see docs for more" // what annotateOrgInline actually renders
        val link = orgInlineLinks(text).single()
        assertEquals("https://example.com", link.target)
        assertEquals("docs", rendered.substring(link.range.first, link.range.last + 1))
    }

    @Test
    fun `link without a label uses the target as both text and range content`() {
        val links = orgInlineLinks("[[https://example.com]]")
        assertEquals(1, links.size)
        assertEquals("https://example.com", links.first().target)
        assertEquals(0.."https://example.com".length - 1, links.first().range)
    }

    @Test
    fun `bare url is a link spanning its own text`() {
        val text = "go to https://example.com now"
        val links = orgInlineLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://example.com", links.first().target)
        assertEquals("go to ".length, links.first().range.first)
    }

    @Test
    fun `multiple links get non-overlapping ranges in encounter order`() {
        val text = "[[a][one]] and [[b][two]]"
        val links = orgInlineLinks(text)
        assertEquals(2, links.size)
        assertEquals("a", links[0].target)
        assertEquals("b", links[1].target)
        assertTrue(links[0].range.last < links[1].range.first)
    }

    @Test
    fun `offset within a link range resolves to that link`() {
        val text = "prefix [[https://x.io][click here]] suffix"
        val links = orgInlineLinks(text)
        val link = links.single()
        val rendered = "prefix click here suffix"
        val midOffset = rendered.indexOf("here")
        assertTrue(midOffset in link.range)
    }

    @Test
    fun `plain text passes through orgInlinePlainText unchanged`() {
        assertEquals("How to Tie a Knot", orgInlinePlainText("How to Tie a Knot"))
    }

    @Test
    fun `whole-heading link renders as its label in orgInlinePlainText`() {
        assertEquals(
            "How to Tie a Knot",
            orgInlinePlainText("[[https://example.com/knots][How to Tie a Knot]]"),
        )
    }

    @Test
    fun `partial link renders as its label inline in orgInlinePlainText`() {
        assertEquals(
            "How to Tie a Knot",
            orgInlinePlainText("How to [[https://example.com/knots][Tie]] a Knot"),
        )
    }

    @Test
    fun `a formula before a link keeps the link range aligned with the rendered text`() {
        // A formula's rendered text is its LaTeX source (the placeholder's alternate text),
        // so everything after it stays at the same offset whether it is typeset or not.
        val text = "so ${'$'}x^2${'$'} then [[https://example.com][docs]]"
        val rendered = "so ${'$'}x^2${'$'} then docs"
        val link = orgInlineLinks(text).single()
        assertEquals("docs", rendered.substring(link.range.first, link.range.last + 1))
    }

    @Test
    fun `placeholder index counts links and formulas drawn inline, in text order`() {
        val text = "[[a][one]] ${'$'}x${'$'} [[b][two]]"
        val drawn = InlineTokenizer.tokenize(text)
            .filter { it.type == InlineType.LINK || it.type == InlineType.MATH }
            .associate { it.range to "id-${'$'}{it.range.first}" }
        assertEquals(listOf(0, 2), orgInlineLinks(text, drawn).map { it.placeholder })
    }

    @Test
    fun `a formula that is not drawn takes no placeholder index`() {
        val text = "[[a][one]] ${'$'}x${'$'} [[b][two]]"
        val links = InlineTokenizer.tokenize(text).filter { it.type == InlineType.LINK }
        val drawn = links.associate { it.range to "id-${'$'}{it.range.first}" }
        assertEquals(listOf(0, 1), orgInlineLinks(text, drawn).map { it.placeholder })
    }

    @Test
    fun `links not drawn inline have no placeholder`() {
        assertEquals(listOf(-1, -1), orgInlineLinks("[[a][one]] ${'$'}x${'$'} [[b][two]]").map { it.placeholder })
    }
}
