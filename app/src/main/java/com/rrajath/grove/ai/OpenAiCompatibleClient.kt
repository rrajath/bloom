// i18n: wire-protocol field names and internal error text, never shown as UI text
package com.rrajath.grove.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatTurn>,
    val temperature: Double = 0.4,
    val stream: Boolean = false,
)

@Serializable
private data class ChatTurn(val role: String = "", val content: String)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val error: ChatError? = null,
)

@Serializable
private data class ChatChoice(val message: ChatTurn? = null)

@Serializable
private data class ChatError(val message: String? = null)

/**
 * Minimal OpenAI-compatible client: one POST to `/chat/completions`, no SDK.
 * OkHttp and kotlinx.serialization are already app dependencies (WebDAV and
 * settings backups), so no new library is introduced for the AI work.
 *
 * The client is pure JVM: [http] can be swapped for an interceptor-backed fake
 * in unit tests, and nothing here touches Android APIs.
 */
class OpenAiCompatibleClient(
    private val config: AiConfig,
    private val http: OkHttpClient = defaultClient(),
) : AiClient {

    // encodeDefaults keeps `stream:false` on the wire: some compatible servers
    // otherwise stream SSE chunks when the field is absent.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val endpoint = AiEndpoint.chatCompletionsUrl(
        AiEndpoint.normalize(config.baseUrl)
            ?: throw AiException("Invalid AI endpoint"),
    )

    override suspend fun complete(messages: List<AiMessage>): String {
        val payload = json.encodeToString(
            ChatRequest(
                model = config.model.trim(),
                messages = messages.map { ChatTurn(it.role, it.content) },
            ),
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiException(errorMessage(raw, response.code), response.code)
                }
                val decoded = runCatching { json.decodeFromString<ChatResponse>(raw) }
                    .getOrElse { throw AiException("Could not read the model response", response.code, it) }
                decoded.error?.message?.takeIf { it.isNotBlank() }?.let {
                    throw AiException(it, response.code)
                }
                decoded.choices.firstOrNull()?.message?.content
                    ?: throw AiException("The model returned an empty response", response.code)
            }
        }
    }

    override suspend fun testConnection(): String =
        complete(listOf(AiMessage.user("Reply with OK."))).trim().take(80)

    private fun errorMessage(raw: String, code: Int): String {
        val serverMessage = runCatching {
            json.decodeFromString<ChatResponse>(raw).error?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return serverMessage
            ?: raw.take(200).takeIf { it.isNotBlank() }
            ?: "HTTP $code"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
