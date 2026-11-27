// i18n: WebDAV XML element names and protocol parsing, never shown as UI text
package com.rrajath.grove.webdav

import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a 207 Multi-Status PROPFIND response into [WebDavEntry] values.
 *
 * Uses DOM (JAXP) rather than Android's XmlPullParser so the same code runs in
 * JVM unit tests. Namespace prefixes differ between servers (`D:`, `d:`, or a
 * default namespace), so elements are matched by local name. External entities
 * and DOCTYPE declarations are disabled: the response comes from a remote host.
 */
object WebDavPropfindParser {

    val propfindBody: String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:resourcetype/><d:getetag/><d:getlastmodified/><d:getcontentlength/>""" +
            """</d:prop></d:propfind>"""

    fun parse(xml: String, root: HttpUrl, basePath: String): List<WebDavEntry> {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw WebDavException("Invalid PROPFIND response: ${e.message}", e)
        }
        return descendants(document.documentElement, "response")
            .mapNotNull { parseResponse(it, root, basePath) }
    }

    private fun parseResponse(response: Element, root: HttpUrl, basePath: String): WebDavEntry? {
        val href = directChildren(response, "href").firstOrNull()?.textContent?.trim()
            ?: return null
        val path = WebDavUrl.relativeFromHref(root, href, basePath) ?: return null
        val prop = usableProp(response) ?: return null
        val isDir = descendants(prop, "collection").isNotEmpty()
        val etag = directChildren(prop, "getetag").firstOrNull()?.textContent?.trim()
            ?.takeIf { it.isNotEmpty() }
        val modified = directChildren(prop, "getlastmodified").firstOrNull()?.textContent?.trim()
        val size = directChildren(prop, "getcontentlength").firstOrNull()?.textContent?.trim()
            ?.toLongOrNull() ?: 0L
        return WebDavEntry(path, isDir, etag, parseDate(modified), size)
    }

    /** The `<prop>` from a propstat whose status is 200; 404 propstats are ignored. */
    private fun usableProp(response: Element): Element? {
        val propstats = descendants(response, "propstat")
        if (propstats.isEmpty()) return descendants(response, "prop").firstOrNull()
        for (propstat in propstats) {
            val status = directChildren(propstat, "status").firstOrNull()?.textContent.orEmpty()
            if (status.isNotBlank() && !status.contains("200")) continue
            return directChildren(propstat, "prop").firstOrNull()
                ?: descendants(propstat, "prop").firstOrNull()
        }
        return null
    }

    /** RFC 1123 is the spec'd form, but several servers emit ISO-8601 instead. */
    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: 0L
    }

    private fun descendants(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { localName(it) == name }
    }

    private fun directChildren(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { localName(it) == name }
    }

    private fun localName(element: Element): String =
        element.localName?.takeIf { it.isNotEmpty() } ?: element.nodeName.substringAfterLast(':')
}
