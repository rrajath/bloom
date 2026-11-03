package com.rrajath.grove.attach

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves camera temp files (`cacheDir/attach-camera/`) to the camera app.
 * Its own subclass so the manifest entry can't clash with a library's
 * `androidx.core.content.FileProvider` declaration.
 */
class AttachmentFileProvider : FileProvider() {
    companion object {
        /** Folder under `cacheDir` that `res/xml/attachment_paths.xml` exposes. */
        const val CAMERA_DIR = "attach-camera"

        fun authority(context: Context): String = "${context.packageName}.attachments"

        /** The camera temp folder, created when missing. */
        fun cameraDir(context: Context): File = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }

        /** A content URI for [file] inside [cameraDir]. */
        fun uriFor(context: Context, file: File): Uri = getUriForFile(context, authority(context), file)
    }
}
