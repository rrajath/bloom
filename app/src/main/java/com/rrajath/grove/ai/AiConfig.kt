// i18n: internal endpoint rules and persisted keys, never shown as UI text
package com.rrajath.grove.ai

import java.net.URI

/**
 * Connection settings for an OpenAI-compatible chat completions endpoint.
 * The API key is deliberately not part of the persisted settings object; it
 * lives in [AiSecretStore] so a settings backup can never carry it.
 */
data class AiConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

/** Why a config cannot be used; the UI maps each one to a translatable message. */
enum class AiConfigProblem { BASE_URL, INSECURE_URL, MODEL, API_KEY }

/**
 * Endpoint rules shared by the settings form and the HTTP client.
 *
 * A base URL is either `https://host/v1` (a `/chat/completions` suffix is also
 * accepted and stripped) or a loopback HTTP address for a locally hosted model.
 * Plain HTTP to a remote host is rejected so an API key can never travel in
 * clear text. Query strings, fragments and embedded userinfo are rejected too.
 */
object AiEndpoint {

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    /** Canonical base URL, or null when [baseUrl] is malformed or insecure. */
    fun normalize(baseUrl: String): String? {
        var raw = baseUrl.trim().trimEnd('/')
        if (raw.endsWith("/chat/completions")) {
            raw = raw.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (raw.isEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (scheme == "http" && host !in LOOPBACK) return null
        return raw
    }

    fun chatCompletionsUrl(normalizedBaseUrl: String): String =
        if (normalizedBaseUrl.endsWith("/chat/completions")) normalizedBaseUrl
        else "$normalizedBaseUrl/chat/completions"

    fun modelsUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/models"

    fun validate(config: AiConfig): AiConfigProblem? {
        val raw = config.baseUrl.trim()
        if (normalize(raw) == null) {
            return if (raw.startsWith("http://", ignoreCase = true)) AiConfigProblem.INSECURE_URL
            else AiConfigProblem.BASE_URL
        }
        if (config.model.isBlank()) return AiConfigProblem.MODEL
        if (config.apiKey.isBlank()) return AiConfigProblem.API_KEY
        return null
    }
}
