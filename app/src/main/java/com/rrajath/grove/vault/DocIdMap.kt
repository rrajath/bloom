package com.rrajath.grove.vault

import java.util.concurrent.ConcurrentHashMap

/**
 * Vault-relative path -> document id, safe for concurrent use. On a
 * case-insensitive provider it also keeps an index by case-folded path, so
 * [keyFor] finds a path cached in another case in O(1) instead of scanning
 * every key with `equals(ignoreCase = true)`, which made each miss (exists()
 * on an absent name, create() pre-checks, Dailies day walks) O(files).
 */
internal class DocIdMap(
    private val caseInsensitive: Boolean,
    initial: Map<String, String> = emptyMap(),
) {
    private val ids = ConcurrentHashMap<String, String>(initial)

    /** Folded path -> every cached spelling of it (normally one). */
    private val folded = ConcurrentHashMap<String, Set<String>>()

    init {
        if (caseInsensitive) initial.keys.forEach(::index)
    }

    operator fun get(key: String): String? = ids[key]

    operator fun contains(key: String): Boolean = ids.containsKey(key)

    operator fun set(key: String, docId: String) {
        ids[key] = docId
        if (caseInsensitive) index(key)
    }

    fun remove(key: String) {
        ids.remove(key)
        if (caseInsensitive) folded.computeIfPresent(fold(key)) { _, s -> (s - key).ifEmpty { null } }
    }

    /** Removes every key matching [predicate]. */
    fun removeKeysIf(predicate: (String) -> Boolean) {
        for (key in ids.keys) if (predicate(key)) remove(key)
    }

    /** A live view of the cached paths. */
    val keys: Set<String> get() = ids.keys

    /** [key] itself when cached, else (case-insensitive provider only) its cached spelling in another case. */
    fun keyFor(key: String): String? {
        if (ids.containsKey(key)) return key
        if (!caseInsensitive) return null
        return folded[fold(key)]?.firstOrNull { ids.containsKey(it) }
    }

    private fun index(key: String) {
        folded.compute(fold(key)) { _, s -> if (s == null) setOf(key) else s + key }
    }

    companion object {
        /**
         * Folds [s] so two strings fold equal exactly when
         * `a.equals(b, ignoreCase = true)`: Kotlin compares chars by their
         * uppercase, then by the lowercase of that.
         */
        fun fold(s: String): String = buildString(s.length) {
            for (c in s) append(c.uppercaseChar().lowercaseChar())
        }
    }
}
