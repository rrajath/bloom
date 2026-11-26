package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderValidationTest {

    private fun invalidTokens(template: String) =
        PlaceholderExpander.findInvalid(template).map { it.token }

    @Test
    fun `every supported placeholder is not flagged`() {
        val supported = listOf(
            "%U", "%u", "%T", "%t", "%date", "%time", "%day", "%month", "%year",
            "%clipboard", "%shared_text", "%shared_url", "%cursor", "%?",
        )
        supported.forEach { assertTrue("$it should be valid", invalidTokens(it).isEmpty()) }
    }

    @Test
    fun `well-formed prompt is not flagged`() {
        assertTrue(invalidTokens("* %^{Title}").isEmpty())
    }

    @Test
    fun `unknown word placeholder is flagged`() {
        assertEquals(listOf("%foo"), invalidTokens("%foo"))
    }

    @Test
    fun `literal percent in prose is not flagged`() {
        assertTrue(invalidTokens("100% sure").isEmpty())
    }

    @Test
    fun `malformed prompt missing braces is flagged`() {
        assertEquals(listOf("%^Title"), invalidTokens("%^Title"))
    }

    @Test
    fun `bare caret with no name is flagged`() {
        assertEquals(listOf("%^"), invalidTokens("%^"))
    }

    @Test
    fun `multiple invalid tokens are all reported`() {
        assertEquals(listOf("%foo", "%bar"), invalidTokens("%foo and %bar"))
    }

    @Test
    fun `mixture of valid and invalid only flags the invalid ones`() {
        assertEquals(listOf("%bogus"), invalidTokens("%U %bogus %cursor"))
    }

    @Test
    fun `longest known key is not misread as invalid`() {
        // %shared_text must not register %s as a stray invalid token.
        assertTrue(invalidTokens("%shared_text").isEmpty())
    }

    @Test
    fun `both id spellings are valid`() {
        assertTrue(invalidTokens(":ID: %id\n:ID: %(id)").isEmpty())
    }
}
