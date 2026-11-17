package com.rrajath.grove.vault

import com.rrajath.grove.settings.GroveSettings

/**
 * Which app serves the vault folder, judged from its SAF tree URI. Only the device's own
 * storage (`com.android.externalstorage.documents`, which is where a Syncthing folder
 * lives) is fully supported; folders served by another app's DocumentsProvider (Seafile,
 * Nextcloud and other cloud apps) get the "isn't fully supported" warning, because their
 * listings, reads and writes can behave in ways that lose edits.
 *
 * Pure string parsing, so it runs in JVM tests without `android.net.Uri`.
 */
object VaultProvider {
    const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** The authority of a `content://authority/tree/...` URI, or null if it isn't one. */
    fun authorityOf(treeUri: String): String? {
        if (!treeUri.startsWith("content://")) return null
        return treeUri.removePrefix("content://").substringBefore('/')
            .takeIf { it.isNotEmpty() }
    }

    /** True when [treeUri] is served by another app rather than the device's own storage. */
    fun isNonLocal(treeUri: String?): Boolean =
        treeUri != null && authorityOf(treeUri) != LOCAL_STORAGE_AUTHORITY

    /** The one-time dialog: after onboarding, for a non-local vault not yet answered. */
    fun shouldShowWarningDialog(settings: GroveSettings): Boolean {
        val uri = settings.vaultTreeUri ?: return false
        return settings.onboardingDone && isNonLocal(uri) && settings.providerWarningAckedUri != uri
    }

    /** The Notebooks banner: a non-local vault whose banner wasn't dismissed. */
    fun shouldShowBanner(settings: GroveSettings): Boolean {
        val uri = settings.vaultTreeUri ?: return false
        return isNonLocal(uri) && settings.providerBannerDismissedUri != uri
    }
}
