package com.rrajath.grove.ai

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiCompatibleClientTest {

    private val config = AiConfig("https://api.openai.com/v1", "gpt-4o-mini", "sk-test")

    private fun fakeHttp(code: Int, body: String, capture: MutableList<Request>? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                capture?.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun `posts a non-streaming chat completion with the API key`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OpenAiCompatibleClient(
            config,
            fakeHttp(200, """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""", requests),
        )
        val reply = client.complete(listOf(AiMessage.system("rules"), AiMessage.user("hello")))
        assertEquals("OK", reply)
        assertEquals("https://api.openai.com/v1/chat/completions", requests.single().url.toString())
        assertEquals("Bearer sk-test", requests.single().header("Authorization"))

        val payload = Buffer().apply { requests.single().body!!.writeTo(this) }.readUtf8()
        assertTrue(payload.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(payload.contains("\"stream\":false"))
        assertTrue(payload.contains("\"role\":\"user\""))
    }

    @Test
    fun `maps provider errors to AiException with status code`() = runBlocking {
        val client = OpenAiCompatibleClient(
            config,
            fakeHttp(401, """{"error":{"message":"invalid api key"}}"""),
        )
        try {
            client.complete(listOf(AiMessage.user("hello")))
            fail("expected AiException")
        } catch (error: AiException) {
            assertEquals(401, error.statusCode)
            assertTrue(error.message!!.contains("invalid api key"))
        }
    }

    @Test
    fun `testConnection returns the short model reply`() = runBlocking {
        val client = OpenAiCompatibleClient(
            config,
            fakeHttp(200, """{"choices":[{"message":{"content":"OK"}}],"usage":{"total_tokens":3}}"""),
        )
        assertEquals("OK", client.testConnection())
    }
}
