package com.rrajath.grove.org

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * A raw identifier for an org heading's `:ID:` (or the `:CUSTOM_ID:` Grove
 * auto-injects when favoriting). Upper-cased so the UUID matches the form Emacs
 * `org-id` writes into `:PROPERTIES:` drawers. Not checked against the vault:
 * anything that writes an ID into a file goes through [OrgIdAllocator] instead.
 */
fun newOrgId(): String = UUID.randomUUID().toString().uppercase()

/**
 * Hands out org IDs that no `:ID:` or `:CUSTOM_ID:` in the vault already uses.
 *
 * [taken] asks the index (case-insensitively, like org's own ID lookups) whether
 * a candidate is in use; a hit is discarded and a new candidate drawn. IDs issued
 * by this allocator are also remembered for the process lifetime, so two IDs
 * handed out before the index catches up with the files they land in (a bulk
 * "generate ID", two captures in a row) can never collide with each other.
 */
class OrgIdAllocator(
    private val taken: suspend (String) -> Boolean,
    private val generate: () -> String = ::newOrgId,
) {
    private val mutex = Mutex()
    private val issued = HashSet<String>()

    /** One fresh, vault-unique ID. */
    suspend fun next(): String = allocate(1).single()

    /** [count] fresh IDs, unique in the vault and among themselves. */
    suspend fun allocate(count: Int): List<String> {
        if (count <= 0) return emptyList()
        return mutex.withLock {
            List(count) {
                var attempts = 0
                var candidate: String
                do {
                    check(attempts++ < MAX_ATTEMPTS) { "No unused org ID after $MAX_ATTEMPTS attempts" } // i18n: log
                    candidate = generate()
                } while (candidate.uppercase() in issued || taken(candidate))
                issued += candidate.uppercase()
                candidate
            }
        }
    }

    private companion object {
        // A random UUID colliding even once is astronomically unlikely; this
        // only stops a broken generator (or index) from spinning forever.
        const val MAX_ATTEMPTS = 64
    }
}
