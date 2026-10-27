package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SharedPayloadTest {

    @Test
    fun `url is extracted and text keeps the rest`() {
        val p = SharedPayload.from(null, "Check this out https://example.com/a?b=1 amazing")
        assertEquals("https://example.com/a?b=1", p.url)
        assertEquals("Check this out  amazing", p.text)
    }

    @Test
    fun `subject becomes shared text for url-only shares`() {
        val p = SharedPayload.from("Article Title", "https://example.com/article")
        assertEquals("https://example.com/article", p.url)
        assertEquals("Article Title", p.text)
    }

    @Test
    fun `plain text share has no url`() {
        val p = SharedPayload.from(null, "just a thought")
        assertEquals("", p.url)
        assertEquals("just a thought", p.text)
    }

    @Test
    fun `empty share is empty`() {
        assertTrue(SharedPayload.from(null, null).isEmpty)
        assertTrue(SharedPayload.from("", "  ").isEmpty)
    }

    @Test
    fun `shared placeholders expand in a reading-list template`() {
        val payload = SharedPayload.from("Great Post", "https://blog.example/post")
        val result = PlaceholderExpander.expand(
            "* [[%shared_url][%shared_text]]\n%U\n%cursor",
            CaptureContext(
                now = LocalDateTime.of(2025, 6, 11, 8, 0),
                sharedText = payload.text,
                sharedUrl = payload.url,
            ),
        )
        assertEquals(
            "* [[https://blog.example/post][Great Post]]\n[2025-06-11 Wed 08:00]\n",
            result.text,
        )
    }
}
