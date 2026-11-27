package com.rrajath.grove.ai

/**
 * Storage for the AI provider API key. Deliberately separate from the settings
 * repository: the key is never in DataStore, never exported in a settings
 * backup and never restored onto another device.
 */
interface AiSecretStore {
    fun save(secret: String)
    fun load(): String?
    fun clear()
}
