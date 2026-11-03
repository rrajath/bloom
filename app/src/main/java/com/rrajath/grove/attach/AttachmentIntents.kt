package com.rrajath.grove.attach

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import com.rrajath.grove.R
import com.rrajath.grove.vault.FileStore

/**
 * Hands an attachment to other apps: "Open with" (ACTION_VIEW) and Share
 * (ACTION_SEND), always through a chooser and with a read grant on the SAF
 * document URI. The MIME type comes from the extension, because files are
 * created as `application/octet-stream` to stop providers mangling names.
 */
object AttachmentIntents {

    /** Outcome of [open] / [share], for the caller's toast. */
    enum class Result { Launched, Missing, NoHandler }

    /** Toast for [Result.NoHandler]. */
    const val NO_HANDLER_MESSAGE = "No app can open this file"

    /** MIME type for [name] by extension, `application/octet-stream` when unknown. */
    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return OCTET_STREAM
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: OCTET_STREAM
    }

    fun viewIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData carries the grant through the chooser on every API level.
            .apply { clipData = ClipData.newRawUri(null, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Whether any installed app handles viewing [uri] as [mime] (needs the manifest `<queries>` on API 30+). */
    fun hasViewer(context: Context, uri: Uri, mime: String): Boolean {
        val pm = context.packageManager
        val intent = viewIntent(uri, mime)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return matches.isNotEmpty()
    }

    /** "Open with" for the attachment at vault path [path]. */
    suspend fun open(context: Context, store: FileStore, path: String): Result {
        val uri = store.contentUri(path) ?: return Result.Missing
        val mime = mimeType(path)
        if (!hasViewer(context, uri, mime)) return Result.NoHandler
        launch(context, Intent.createChooser(viewIntent(uri, mime), context.getString(R.string.attach_open_with)))
        return Result.Launched
    }

    /** Share sheet for the attachment at vault path [path]. */
    suspend fun share(context: Context, store: FileStore, path: String): Result {
        val uri = store.contentUri(path) ?: return Result.Missing
        launch(context, Intent.createChooser(shareIntent(uri, mimeType(path)), context.getString(R.string.attach_share)))
        return Result.Launched
    }

    private fun launch(context: Context, chooser: Intent) {
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private const val OCTET_STREAM = "application/octet-stream"
}
