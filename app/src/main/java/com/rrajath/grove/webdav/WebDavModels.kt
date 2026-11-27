// i18n: WebDAV protocol constants and internal error text, never shown as UI text
package com.rrajath.grove.webdav

/**
 * Connection settings for one WebDAV account. [url] is the normalized URL of the
 * vault collection (always https, always with a trailing slash).
 *
 * The password is deliberately not part of [com.rrajath.grove.settings.GroveSettings]:
 * it lives in [WebDavSecretStore] (Android Keystore encrypted, app-private file)
 * and is never exported with a settings backup.
 */
data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
)

/** One resource as reported by PROPFIND. [path] is vault-relative with '/' separators. */
data class WebDavEntry(
    val path: String,
    val isDir: Boolean,
    val etag: String?,
    val lastModified: Long,
    val size: Long,
) {
    /**
     * Opaque revision used for change detection. Servers without ETag support
     * fall back to the same `mtime:size` shape the local index uses.
     */
    val revision: String get() = etag ?: "$lastModified:$size"
}

/**
 * Network boundary for the two-way sync, so [WebDavVaultSync] can be unit-tested
 * with an in-memory server. Paths are vault-relative; implementations create any
 * missing parent collections on [put].
 */
interface WebDavClient {
    /** Every file under the vault (collections omitted, hidden paths skipped). */
    suspend fun list(): Map<String, WebDavEntry>

    /** Shallow listing of one collection (PROPFIND Depth: 1), including directories. */
    suspend fun listShallow(path: String): List<WebDavEntry> = list().values.toList()

    /** Current metadata for one path (PROPFIND Depth: 0), or null when missing. */
    suspend fun stat(path: String): WebDavEntry?

    /** File content, or null when the resource is gone. */
    suspend fun get(path: String): ByteArray?

    /**
     * Upload [bytes], optionally guarded by an [ifMatch] ETag. Returns the
     * resource's post-write metadata, so the sync state never stores a guessed
     * revision. Throws [WebDavPreconditionFailed] on HTTP 412.
     */
    suspend fun put(path: String, bytes: ByteArray, ifMatch: String?): WebDavEntry

    /** Delete a resource; a missing one is a no-op. */
    suspend fun delete(path: String, ifMatch: String?)
}

open class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WebDavAuthException(code: Int) : WebDavException("WebDAV authentication failed (HTTP $code)")

class WebDavHttpException(method: String, path: String, val code: Int, message: String) :
    WebDavException("$method $path failed (HTTP $code): $message")

/**
 * HTTP 503/429: the server is busy or rate-limiting. The app pauses WebDAV
 * requests for a short cooldown instead of retrying immediately and making
 * the limit worse.
 */
class WebDavRateLimitedException(val retryAfterSeconds: Int, val statusCode: Int) :
    WebDavException("WebDAV server is busy (HTTP $statusCode); retry in about $retryAfterSeconds s")

/** HTTP 412: somebody changed the remote file between our PROPFIND and PUT. */
class WebDavPreconditionFailed(path: String) : WebDavException("Remote file changed: $path")

/** Password storage. Android Keystore in the app, in-memory fakes in tests. */
interface WebDavSecretStore {
    fun save(secret: String)
    fun load(): String?
    fun clear()
}
