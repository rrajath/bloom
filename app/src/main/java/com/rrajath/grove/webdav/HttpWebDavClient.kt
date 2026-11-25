// i18n: WebDAV protocol methods and internal error text, never shown as UI text
package com.rrajath.grove.webdav

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * WebDAV over HTTPS with HTTP Basic auth (Nextcloud, ownCloud, nginx/apache
 * mod_dav, and most hosted WebDAV services).
 *
 * [list] walks the collection with `Depth: 1` PROPFINDs instead of
 * `Depth: infinity`: many servers disable infinite depth, and a per-directory
 * walk works everywhere. PUT is guarded by `If-Match` when the server supplies
 * an ETag, so an edit made on another device between PROPFIND and PUT becomes a
 * conflict instead of an overwrite.
 */
class HttpWebDavClient(
    config: WebDavConfig,
    proxyOverride: Proxy? = null,
    private val http: OkHttpClient = defaultWebDavHttpClient(proxyOverride),
) : WebDavClient {

    private val root: HttpUrl = WebDavUrl.parse(config.url)
        ?.let { it.newBuilder().query(null).fragment(null).build() }
        ?: throw WebDavException("A valid HTTPS WebDAV URL is required")

    private val authorization = Credentials.basic(config.username, config.password, StandardCharsets.UTF_8)

    /** Collections already MKCOL'd during this client's lifetime (one sync pass). */
    private val ensuredDirs = HashSet<String>()

    override suspend fun list(): Map<String, WebDavEntry> {
        val files = LinkedHashMap<String, WebDavEntry>()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.add("")
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (!visited.add(dir)) continue
            for (entry in propfind(dir, 1)) {
                if (entry.path.isEmpty() || entry.path == dir) continue
                if (!WebDavUrl.isSafeRelative(entry.path)) continue
                if (entry.isDir) queue.add(entry.path) else files[entry.path] = entry
            }
        }
        return files
    }

    /** Depth:1 on one collection — used by the connection test, no tree walk. */
    override suspend fun listShallow(path: String): List<WebDavEntry> = propfind(path, 1)

    override suspend fun stat(path: String): WebDavEntry? =
        propfind(path, 0)
            .firstOrNull { it.path == path || it.path.isEmpty() }
            ?.let { if (it.path.isEmpty()) it.copy(path = path) else it }

    override suspend fun get(path: String): ByteArray? =
        execute(url = url(path), method = "GET", allowed = setOf(404)) { response ->
            if (response.code == 404) null else response.body?.bytes()
        }

    override suspend fun put(path: String, bytes: ByteArray, ifMatch: String?): WebDavEntry {
        ensureDirs(path)
        val returnedEtag = execute(
            url = url(path),
            method = "PUT",
            body = bytes.toRequestBody(OCTET_STREAM),
            headers = ifMatchHeaders(ifMatch),
            allowed = setOf(412),
        ) { response ->
            if (response.code == 412) throw WebDavPreconditionFailed(path)
            response.header("ETag")
        }
        // A PUT response's ETag is optional and some servers report the *old* one;
        // a Depth:0 PROPFIND gives the authoritative post-write revision.
        return stat(path) ?: WebDavEntry(
            path = path,
            isDir = false,
            etag = returnedEtag,
            lastModified = System.currentTimeMillis(),
            size = bytes.size.toLong(),
        )
    }

    override suspend fun delete(path: String, ifMatch: String?) {
        execute(
            url = url(path),
            method = "DELETE",
            headers = ifMatchHeaders(ifMatch),
            allowed = setOf(404, 412),
        ) { response ->
            if (response.code == 412) throw WebDavPreconditionFailed(path)
            Unit
        }
    }

    private fun ifMatchHeaders(ifMatch: String?): List<Pair<String, String>> =
        ifMatch?.let { listOf("If-Match" to it) } ?: emptyList()

    private fun propfind(path: String, depth: Int): List<WebDavEntry> =
        execute(
            url = if (depth > 0) WebDavUrl.buildCollection(root, path) else url(path),
            method = "PROPFIND",
            body = WebDavPropfindParser.propfindBody.toRequestBody(XML),
            headers = listOf("Depth" to depth.toString()),
            allowed = setOf(404),
        ) { response ->
            if (response.code == 404) emptyList()
            else WebDavPropfindParser.parse(response.body?.string().orEmpty(), root, path)
        }

    /** Creates missing ancestor collections, shallowest first. 405 means "already there". */
    private fun ensureDirs(path: String) {
        for (dir in WebDavUrl.parentDirs(path)) {
            if (!ensuredDirs.add(dir)) continue
            runCatching {
                execute(
                    url = WebDavUrl.buildCollection(root, dir),
                    method = "MKCOL",
                    allowed = setOf(301, 405, 409),
                ) { Unit }
            }
        }
    }

    private fun url(path: String): HttpUrl = WebDavUrl.build(root, path)

    /**
     * Every non-2xx status is a hard failure unless the caller explicitly opts
     * in via [allowed]. Silently treating a 301/409/500 as success is dangerous
     * here: `list()` would parse an HTML body as an empty Multi-Status, and the
     * sync would then delete local files it thinks vanished from the server.
     * OkHttp already follows the redirects it can; one that reaches this point
     * cannot be followed and must abort the pass (the caller logs it and keeps
     * indexing the offline mirror).
     */
    private fun <T> execute(
        url: HttpUrl,
        method: String,
        body: RequestBody? = null,
        headers: List<Pair<String, String>> = emptyList(),
        allowed: Set<Int> = emptySet(),
        block: (Response) -> T,
    ): T {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
        headers.forEach { builder.header(it.first, it.second) }
        val request = builder.method(method, body).build()
        return http.newCall(request).execute().use { response ->
            val code = response.code
            if (code == 401 || code == 403) throw WebDavAuthException(code)
            if (!response.isSuccessful && code !in allowed) {
                throw WebDavHttpException(method, url.encodedPath, code, response.message)
            }
            block(response)
        }
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
    }
}

private fun defaultWebDavHttpClient(proxyOverride: Proxy?): OkHttpClient =
    OkHttpClient.Builder()
        // Short per-address timeout: hosts with two A records (Nutstore has
        // 58.215.175.52 and .53) must not spend 15s each on a blocked address
        // before the UI-level deadline gives up. OkHttp retries the next IP
        // automatically; the coroutine timeout in test() leaves room for both.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Hard ceiling on the whole call (connect + all redirects + body). Without
        // it a server that dribbles bytes can keep a request alive far longer than
        // the per-read timeout, and the UI stays "busy" with no explanation.
        .callTimeout(40, TimeUnit.SECONDS)
        .apply { if (proxyOverride != null) proxy(proxyOverride) }
        .build()
