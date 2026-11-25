// i18n: file provider path constants, never shown as UI text
package com.rrajath.grove.webdav

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves files from the WebDAV mirror (attachments) to image loaders and
 * "Open with"/Share intents. Its own subclass so the manifest entry can't clash
 * with a library's `androidx.core.content.FileProvider` declaration.
 */
class WebDavFileProvider : FileProvider() {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.webdav.files"

        fun uriFor(context: Context, file: File): Uri = getUriForFile(context, authority(context), file)
    }
}
