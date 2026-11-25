// i18n: file provider path constants, never shown as UI text
package com.rrajath.grove.webdav

import android.content.Context
import android.net.Uri
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adds attachment support to the plain-directory mirror store.
 *
 * [JvmFileStore][com.rrajath.grove.vault.JvmFileStore] has no content URIs of
 * its own, but read mode, the image viewer and "Open with"/Share all ask
 * [FileStore.contentUri]. A FileProvider over the mirror directory supplies
 * those URIs without exposing app-private files to other apps globally: the
 * grant comes from the intent that carries the URI.
 */
class WebDavMirrorFileStore(
    private val delegate: FileStore,
    private val context: Context,
    private val mirrorRoot: File,
) : FileStore by delegate {

    override suspend fun contentUri(name: String): Uri? = withContext(Dispatchers.IO) {
        val file = File(mirrorRoot, name.trim('/'))
        if (!file.isFile) return@withContext null
        val root = mirrorRoot.canonicalPath
        if (!file.canonicalPath.startsWith("$root${File.separator}")) return@withContext null
        runCatching { WebDavFileProvider.uriFor(context, file) }.getOrNull()
    }
}
