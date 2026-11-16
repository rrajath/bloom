package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDirectionTest {

    private val persian = "سلام"

    private fun tokens(text: String) = InlineTokenizer.tokenize(text)

    @Test
    fun `a formula's latex letters do not make a persian line left to right`() {
        val line = "${'$'}x_t${'$'} $persian"
        assertTrue(InlineDirection.startsRtl(tokens(line)))
        assertEquals(true, InlineDirection.uniformRtl(tokens(line)))
    }

    @Test
    fun `display style formulas are ignored too`() {
        assertTrue(InlineDirection.startsRtl(tokens("\\(\\alpha\\) $persian")))
    }

    @Test
    fun `plain english stays left to right`() {
        assertFalse(InlineDirection.startsRtl(tokens("hello ${'$'}x${'$'}")))
        assertEquals(false, InlineDirection.uniformRtl(tokens("hello ${'$'}x${'$'}")))
    }

    @Test
    fun `a line that is only a formula has no direction`() {
        assertFalse(InlineDirection.startsRtl(tokens("${'$'}x^2${'$'}")))
        assertNull(InlineDirection.uniformRtl(tokens("${'$'}x^2${'$'}")))
    }

    @Test
    fun `lines that disagree have no uniform direction`() {
        assertNull(InlineDirection.uniformRtl(tokens("hello\n$persian")))
    }

    @Test
    fun `lines that agree do`() {
        assertEquals(true, InlineDirection.uniformRtl(tokens("$persian\n${'$'}x${'$'} $persian")))
    }

    @Test
    fun `a line without letters is skipped when the others agree`() {
        assertEquals(true, InlineDirection.uniformRtl(tokens("$persian\n${'$'}x${'$'}\n$persian")))
    }

    @Test
    fun `link labels count and targets do not`() {
        assertTrue(InlineDirection.startsRtl(tokens("[[https://example.com][$persian]]")))
    }
}
