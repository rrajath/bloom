// i18n: HTTP headers and interstitial-title patterns, not UI text
package com.rrajath.grove.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Best-effort fetch of a web page's <title>, so a shared URL reads as a real heading. */
object PageTitleFetcher {
    private val TITLE = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // Bot-check interstitials (Cloudflare, etc.) serve one of these instead of the real title
    // while a JS challenge resolves. A plain HTTP GET can't get past that, so a title matching
    // this pattern triggers the WebView fallback (which can run the challenge's JS) and, once
    // there, keeps that fallback polling rather than accepting the interstitial title as final.
    private val INTERSTITIAL_TITLE = Regex(
        "please wait|just a moment|attention required|checking your browser|" +
            "verify(?:ing)? you(?:'| a)re? (?:a )?human|human verification|" +
            "enable javascript|are you a robot|ddos protection by|access denied",
        RegexOption.IGNORE_CASE,
    )

    // Second-level labels that are themselves generic (co.uk, org.in, ...) so the real brand
    // sits one label further left, e.g. "bbc.co.uk" -> "bbc", not "co".
    private val GENERIC_DOMAIN_LABELS = setOf("co", "com", "org", "net", "gov", "edu", "ac", "mil", "info")

    private val TITLE_SEGMENT_SPLIT = Regex("[-|:·•/»—–]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    private const val WEBVIEW_TIMEOUT_MS = 12_000L
    private const val WEBVIEW_POLL_INTERVAL_MS = 400L

    private const val MAX_REDIRECTS = 8

    private data class HttpTitleResult(val title: String, val finalUrl: String)

    /** Returns the page title, or null on any failure. Runs its I/O on [Dispatchers.IO]. */
    suspend fun fetch(url: String, context: Context): String? {
        val httpResult = fetchViaHttp(url)
        // Check the placeholder heuristic against finalUrl, not the shared url: a short link
        // like youtu.be redirects to youtube.com before any title is served, so the brand the
        // title is compared against must be the redirected site's, not the shortener's.
        return if (httpResult != null && !isPlaceholderTitle(httpResult.title, httpResult.finalUrl)) {
            httpResult.title
        } else {
            fetchViaWebView(url, context)
        }
    }

    // internal (rather than private): exercised directly by PageTitleFetcherTest since the
    // network/WebView-dependent callers around it can't run in a local JVM unit test.
    internal fun isPlaceholderTitle(title: String, url: String): Boolean =
        INTERSTITIAL_TITLE.containsMatchIn(title) || isBrandShellTitle(title, url)

    // Many sites (Reddit, YouTube, Instagram, ...) serve a generic app-shell <title> —
    // just the site's own brand name, or that name plus a marketing tagline — to every URL,
    // and only swap in the real, content-specific title once client-side JS hydrates. Rather
    // than enumerate those sites' known shell titles one by one, this derives the site's own
    // brand token from the shared URL's host and checks whether the fetched title is nothing
    // more than that brand: either the whole title, or a title that *opens* with the brand as
    // a short lead-in (like a tagline) without also *closing* with it — real content titles
    // conventionally put the brand at the end instead ("Video Title - YouTube", "repo · GitHub").
    internal fun isBrandShellTitle(title: String, url: String): Boolean {
        val brand = brandTokenFromUrl(url) ?: return false
        val normalized = normalizeForBrandCompare(title)
        if (normalized == brand) return true

        val segments = title.split(TITLE_SEGMENT_SPLIT)
            .map(::normalizeForBrandCompare)
            .filter { it.isNotEmpty() }
        if (segments.size < 2) return false
        if (segments.first() != brand) return false
        if (segments.last() == brand) return false

        val remainderWordCount = segments.drop(1).sumOf { it.split(' ').size }
        return remainderWordCount <= 6
    }

    private fun normalizeForBrandCompare(s: String): String =
        s.lowercase().replace(NON_ALNUM, " ").trim()

    internal fun brandTokenFromUrl(url: String): String? {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase() ?: return null
        val labels = host.removePrefix("www.").split(".").filter { it.isNotEmpty() }
        if (labels.size < 2) return labels.firstOrNull()
        var idx = labels.size - 2
        if (labels[idx] in GENERIC_DOMAIN_LABELS && idx - 1 >= 0) idx -= 1
        return labels[idx]
    }

    // Follows redirects manually (rather than instanceFollowRedirects = true) so the final,
    // post-redirect URL is known: a URL shortener like youtu.be redirects to the real site
    // before serving anything, and the placeholder-brand check needs the real site's host,
    // not the shortener's, to know what a "just the brand" title would look like there.
    private suspend fun fetchViaHttp(url: String): HttpTitleResult? = withContext(Dispatchers.IO) {
        runCatching {
            var currentUrl = url
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36",
                    )
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                }
                val code = conn.responseCode
                if (code in 300..399 && hops < MAX_REDIRECTS) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location == null) return@runCatching null
                    currentUrl = URL(URL(currentUrl), location).toString()
                    hops++
                    continue
                }
                break
            }
            try {
                // Read only as far as </title> rather than the whole page.
                val head = conn.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    var line = reader.readLine()
                    while (line != null && sb.length < 200_000) {
                        sb.append(line).append('\n')
                        if (sb.contains("</title>", ignoreCase = true)) break
                        line = reader.readLine()
                    }
                    sb.toString()
                }
                TITLE.find(head)?.groupValues?.get(1)?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
                    ?.let { HttpTitleResult(it, currentUrl) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    // Runs the page in a hidden WebView so its JS challenge/redirect (or, for an SPA, its
    // client-side data fetch) can resolve, then reads the real document title. Polls
    // webView.title after onPageFinished rather than reading it once after a fixed delay: how
    // long an SPA takes to swap its shell title for the real one varies with network speed, so
    // this keeps checking — at [WEBVIEW_POLL_INTERVAL_MS] intervals — until the title escapes
    // [isPlaceholderTitle], or the outer timeout gives up and this returns null rather than
    // surface a shell/interstitial title as if it were real.
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchViaWebView(url: String, context: Context): String? =
        withContext(Dispatchers.Main) {
            val webView = WebView(context.applicationContext)
            try {
                withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val handler = Handler(Looper.getMainLooper())
                        lateinit var poll: Runnable
                        poll = Runnable {
                            val title = webView.title?.let(::cleanTitle)
                            // webView.url reflects wherever a redirect (e.g. a youtu.be short
                            // link) actually landed, not the URL that was originally loaded.
                            val currentUrl = webView.url ?: url
                            if (title != null && title.isNotBlank() && !isPlaceholderTitle(title, currentUrl)) {
                                if (cont.isActive) cont.resume(title)
                            } else {
                                handler.postDelayed(poll, WEBVIEW_POLL_INTERVAL_MS)
                            }
                        }
                        webView.settings.javaScriptEnabled = true
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                handler.removeCallbacks(poll)
                                handler.postDelayed(poll, WEBVIEW_POLL_INTERVAL_MS)
                            }
                        }
                        cont.invokeOnCancellation { handler.removeCallbacks(poll) }
                        webView.loadUrl(url)
                    }
                }
            } finally {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }

    private fun cleanTitle(raw: String): String {
        val unescaped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION") Html.fromHtml(raw)
        }
        return unescaped.toString().replace(Regex("\\s+"), " ").trim()
    }
}
