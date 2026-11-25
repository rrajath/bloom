// i18n: internal path keys, never shown as UI text
package com.rrajath.grove.webdav

import java.io.File
import java.security.MessageDigest

/**
 * Stable per-vault identity for on-disk state.
 *
 * The URL contains the WebDAV collection path, so `.../dav/` and `.../dav/ToDo/`
 * are different vaults. Keeping the mirror and the sync baseline under the same
 * key prevents files from one collection being treated as new local files when
 * the user switches the URL to another collection.
 */
object WebDavPaths {

    /** Short, filesystem-safe key derived from the collection URL and account. */
    fun vaultKey(config: WebDavConfig): String =
        hash("${config.url}|${config.username}".toByteArray(Charsets.UTF_8)).take(16)

    /** Mirror directory for [config] under a shared base directory. */
    fun mirrorRoot(base: File, config: WebDavConfig): File = File(base, vaultKey(config))

    /** Baseline state file for [config] under a shared state directory. */
    fun stateFile(base: File, config: WebDavConfig): File =
        File(base, "state-${vaultKey(config)}.json")

    fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append(((it.toInt() and 0xff).toString(16)).padStart(2, '0')) }
        }
    }
}
