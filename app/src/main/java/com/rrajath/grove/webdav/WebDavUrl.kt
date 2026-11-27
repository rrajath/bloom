// i18n: URL and path handling constants, never shown as UI text
package com.rrajath.grove.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder

/**
 * URL handling for one WebDAV collection. Only HTTPS is accepted: Basic auth
 * credentials must never travel over plaintext HTTP.
 *
 * Pure JVM (OkHttp's HttpUrl has no Android dependency), so path handling is
 * covered by unit tests.
 */
object WebDavUrl {

    /** Validated collection URL with a trailing slash, or null for an unusable URL. */
    fun parse(raw: String): HttpUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val url = withSlash.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        if (url.query != null || url.fragment != null) return null
        // A password embedded in the URL would land in DataStore as plaintext,
        // bypassing the Keystore-backed secret store entirely.
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url
    }

    /** [parse] as a string, for storing in preferences. */
    fun normalize(raw: String): String? = parse(raw)?.toString()

    /** URL of [relativePath] under the vault collection [root]. */
    fun build(root: HttpUrl, relativePath: String): HttpUrl =
        (root.toString() + encodeRelative(relativePath)).toHttpUrl()

    /**
     * [build] with a trailing slash, for a collection. Many WebDAV servers
     * (Apache mod_dav, nginx, SabreDAV) respond to a slash-less collection with
     * a 301 and an HTML body — which would otherwise be parsed as an empty
     * PROPFIND result and make the sync believe every remote file was deleted.
     */
    fun buildCollection(root: HttpUrl, relativePath: String): HttpUrl {
        val encoded = encodeRelative(relativePath)
        val base = root.toString() // parse() guarantees a trailing slash
        return (if (encoded.isEmpty()) base else "$base$encoded/").toHttpUrl()
    }

    private fun encodeRelative(relativePath: String): String =
        relativePath.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /**
     * Vault-relative path from a PROPFIND `<href>`.
     *
     * Servers return either an absolute path (`/remote.php/dav/files/u/Notes/a.org`),
     * an absolute URL, or a path relative to the collection that was queried
     * ([basePath]). Percent-encoding is decoded segment by segment so a file
     * named `week 2.org` maps back to the same name on every server.
     */
    fun relativeFromHref(root: HttpUrl, href: String, basePath: String): String? {
        val text = href.trim()
        val raw = runCatching { URI(text).rawPath }.getOrNull()
            ?: text.substringBefore('?').substringBefore('#')
        val parts = raw.trim('/').split('/')
            .map { decodePercent(it) }
            .filter { it.isNotEmpty() }
        if (raw.startsWith("/")) {
            // HttpUrl.pathSegments are still percent-encoded, while `parts` were
            // decoded above. Decode both sides or a vault root containing a space
            // ("My%20Notes") never matches its own hrefs.
            val rootParts = root.pathSegments
                .filter { it.isNotEmpty() }
                .map { decodePercent(it) }
            if (parts.size < rootParts.size || parts.take(rootParts.size) != rootParts) return null
            return parts.drop(rootParts.size).joinToString("/")
        }
        val prefix = basePath.trim('/').split('/').filter { it.isNotEmpty() }
        // Relative hrefs are normally relative to the collection just queried
        // ("a.org" for a PROPFIND on projects/). A few servers instead return
        // them relative to the vault root ("projects/a.org"); if the response
        // already starts with the collection prefix, don't double it.
        val resolved = if (prefix.isNotEmpty() &&
            parts.size >= prefix.size && parts.take(prefix.size) == prefix
        ) parts else prefix + parts
        return resolved.joinToString("/")
    }

    /** Percent-decode without URLDecoder's `+` -> space behaviour (a literal plus is a valid filename char). */
    fun decodePercent(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Rejects traversal and hidden paths coming from a server (or a stale state file). */
    fun isSafeRelative(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || '\\' in path) return false
        return path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".")
        }
    }

    /** Ancestor collections of [path], shallowest first ("" is the vault root). */
    fun parentDirs(path: String): List<String> {
        val dirs = mutableListOf<String>()
        var current = ""
        for (segment in path.split('/').dropLast(1)) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            dirs.add(current)
        }
        return dirs
    }
}
