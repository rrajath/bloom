package com.rrajath.grove.vault.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the vault lives. Only [Saf] exists today; a built-in network client
 * would add its own variant (and a backend for it) without another settings
 * migration. Persisted with [encode] as a tagged value, e.g.
 * `{"type":"saf","treeUri":"content://..."}`.
 */
@Serializable
sealed interface VaultLocation {
    /** A folder some documents provider exposes, behind a persisted tree URI. */
    @Serializable
    @SerialName("saf")
    data class Saf(val treeUri: String) : VaultLocation

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

        /** The location [encoded] holds; null for null, blank, malformed or unknown-type input. */
        fun decode(encoded: String?): VaultLocation? {
            if (encoded.isNullOrBlank()) return null
            return runCatching { JSON.decodeFromString(serializer(), encoded) }.getOrNull()
        }
    }
}
