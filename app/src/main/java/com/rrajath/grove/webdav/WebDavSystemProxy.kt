// i18n: internal network helper, no user-visible text
package com.rrajath.grove.webdav

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Android's Wi-Fi proxy (Settings > Wi-Fi > advanced > Proxy) is not picked up
 * by OkHttp's JVM-wide default ProxySelector. Apps that build a bare
 * OkHttpClient therefore try to connect directly even when the browser is
 * going through a proxy — the classic "browser works, app times out" case.
 *
 * Returns the active network's HTTP proxy, or null for a direct connection.
 * minSdk is 23, so ConnectivityManager.getActiveNetwork()/getLinkProperties()
 * and LinkProperties.getHttpProxy() are all available.
 */
object WebDavSystemProxy {

    fun current(context: Context): Proxy? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        val properties: LinkProperties = manager.getLinkProperties(network) ?: return null
        val info = properties.httpProxy ?: return null
        val host = info.host
        if (host.isNullOrBlank() || info.port <= 0) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, info.port))
    }
}
