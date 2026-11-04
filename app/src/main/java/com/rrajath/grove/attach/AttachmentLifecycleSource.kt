package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileStore

/**
 * Hands out the [AttachmentLifecycle] for whichever vault store is current:
 * built on first use, then reused while the store stays the same instance, so
 * every operation on one store shares one lifecycle (and its mutex). A new
 * store (another vault picked) gets a new lifecycle.
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
    private val create: (FileStore) -> AttachmentLifecycle,
) {
    private var cached: Pair<FileStore, AttachmentLifecycle>? = null

    /** The lifecycle for the current store; null when attachments are off or no vault is set. */
    fun current(): AttachmentLifecycle? {
        if (!enabled) return null
        val s = store() ?: return null
        synchronized(this) {
            cached?.let { (cachedStore, lifecycle) -> if (cachedStore === s) return lifecycle }
            return create(s).also { cached = s to it }
        }
    }
}
