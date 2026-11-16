package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileStore

/**
 * Hands out the [AttachmentLifecycle] for whichever vault store and
 * [AttachConfig] are current: built on first use, then reused while the store
 * stays the same instance and the config is unchanged, so every operation on
 * one store shares one lifecycle (and its mutex). A new store (another vault
 * picked) or a new config gets a new lifecycle.
 *
 * Synchronous on purpose. The lifecycle used to be a lazily created
 * `stateIn(appScope, Eagerly, null)` over the store flow; its first `.value`
 * read returned the initial null before the collector on the background
 * dispatcher had run. Each lifecycle entry point reads it once, so the first
 * notebook move or delete (or folder change, heading delete, refile) after
 * launch saw "no attachments" and silently took the plain path.
 */
class AttachmentLifecycleSource(
    private val enabled: Boolean,
    private val store: () -> FileStore?,
    private val config: () -> AttachConfig,
    private val create: (FileStore, AttachConfig) -> AttachmentLifecycle,
) {
    private var cached: Pair<FileStore, AttachmentLifecycle>? = null

    /** The lifecycle for the current store; null when attachments are off or no vault is set. */
    fun current(): AttachmentLifecycle? {
        if (!enabled) return null
        val s = store() ?: return null
        val c = config()
        synchronized(this) {
            cached?.let { (cachedStore, lifecycle) -> if (cachedStore === s && lifecycle.config == c) return lifecycle }
            return create(s, c).also { cached = s to it }
        }
    }
}
