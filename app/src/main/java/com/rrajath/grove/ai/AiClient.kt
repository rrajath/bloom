// i18n: wire-protocol roles and internal error text, never shown as UI text
package com.rrajath.grove.ai

/** One turn of a chat-completions conversation. */
data class AiMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = AiMessage("system", text)
        fun user(text: String) = AiMessage("user", text)
        fun assistant(text: String) = AiMessage("assistant", text)
    }
}

/**
 * The single boundary between AI features and the network. Every future AI
 * capability talks through this interface; unit tests replace it with a fake.
 */
interface AiClient {
    /** Full assistant reply for [messages]. Throws [AiException] on any failure. */
    suspend fun complete(messages: List<AiMessage>): String

    /** A tiny round-trip used by the settings screen's connection check. */
    suspend fun testConnection(): String
}

class AiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
