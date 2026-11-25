package com.rrajath.grove.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiEndpointTest {

    @Test
    fun `normalizes trailing slash and full completions URL`() {
        assertEquals("https://api.openai.com/v1", AiEndpoint.normalize("https://api.openai.com/v1/"))
        assertEquals(
            "https://api.openai.com/v1",
            AiEndpoint.normalize("  https://api.openai.com/v1/chat/completions  "),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiEndpoint.chatCompletionsUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            AiEndpoint.modelsUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun `rejects remote plain http and embedded credentials`() {
        assertNull(AiEndpoint.normalize("http://api.example.com/v1"))
        assertNull(AiEndpoint.normalize("https://user:pass@api.example.com/v1"))
        assertNull(AiEndpoint.normalize("https://api.example.com/v1?key=1"))
        assertNull(AiEndpoint.normalize("ftp://api.example.com/v1"))
        assertEquals(
            "http://localhost:11434/v1",
            AiEndpoint.normalize("http://localhost:11434/v1"),
        )
        assertEquals(
            "http://10.0.2.2:8000/v1",
            AiEndpoint.normalize("http://10.0.2.2:8000/v1"),
        )
    }

    @Test
    fun `validate reports the first actionable problem`() {
        val base = AiConfig("https://api.openai.com/v1", "gpt-4o-mini", "sk-test")
        assertNull(AiEndpoint.validate(base))
        assertEquals(
            AiConfigProblem.INSECURE_URL,
            AiEndpoint.validate(base.copy(baseUrl = "http://example.com/v1")),
        )
        assertEquals(
            AiConfigProblem.BASE_URL,
            AiEndpoint.validate(base.copy(baseUrl = "not-a-url")),
        )
        assertEquals(
            AiConfigProblem.MODEL,
            AiEndpoint.validate(base.copy(model = "  ")),
        )
        assertEquals(
            AiConfigProblem.API_KEY,
            AiEndpoint.validate(base.copy(apiKey = "")),
        )
    }
}
