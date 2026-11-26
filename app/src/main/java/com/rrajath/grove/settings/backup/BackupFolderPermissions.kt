package com.rrajath.grove.settings.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rrajath.grove.settings.GroveSettings
import java.net.URLDecoder
import java.util.Locale

/**
 * Releases the automatic-backup folder's persisted SAF grant once it's no longer used.
 *
 * A persisted grant is keyed by URI, so when the backup and the vault point at the
 * same folder they share one grant; releasing it would lock the user out of their
 * vault. This is the only place in the app that calls
 * [ContentResolver.releasePersistableUriPermission], and it never releases the vault.
 */
object BackupFolderPermissions {

    /**
     * Release [old]'s grant unless [settings] (read at release time, not when the folder
     * was picked) shows it's still the vault or the backup folder. Unknown settings release nothing.
     */
    fun releaseIfUnused(resolver: ContentResolver, old: String, settings: GroveSettings?) {
        if (!shouldRelease(old, settings)) return
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "release failed for $old", it) }
    }

    internal fun shouldRelease(old: String, settings: GroveSettings?): Boolean {
        if (settings == null) return false
        val target = normalize(old)
        return listOfNotNull(settings.vaultTreeUri, settings.autoBackupTreeUri).none { normalize(it) == target }
    }

    /** Percent-decoded, with the scheme and authority lowercased, so encoding differences compare equal. */
    private fun normalize(uri: String): String {
        // Keep a literal '+' (URLDecoder would turn it into a space).
        val decoded = runCatching { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri)
        val schemeEnd = decoded.indexOf("://")
        if (schemeEnd < 0) return decoded
        val authorityEnd = decoded.indexOf('/', schemeEnd + 3).let { if (it < 0) decoded.length else it }
        return decoded.substring(0, authorityEnd).lowercase(Locale.ROOT) + decoded.substring(authorityEnd)
    }

    private const val TAG = "AutoBackup" // i18n: log tag
}
