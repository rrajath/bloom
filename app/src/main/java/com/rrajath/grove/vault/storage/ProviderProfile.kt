package com.rrajath.grove.vault.storage

import androidx.annotation.StringRes
import com.rrajath.grove.R

/**
 * What Bloom has to be told about a storage provider because no SAF signal
 * shows it. Anything a document's `COLUMN_FLAGS` can say (rename, delete,
 * move, create) is read from the flags instead, never declared here
 * (`ProviderProfileTest` enforces it).
 */
data class ProviderProfile(
    /** Stable id: "local", "seafile", "nextcloud", "default". */
    val id: String,
    /** The documents-provider authorities this profile covers (release and debug builds). */
    val authorities: Set<String>,
    /** The provider's name in messages ("Seafile doesn't let other apps rename files"). */
    @StringRes val displayName: Int,
    /** Document ids are `<root id>/<path>`, so a file is found with one query instead of a listing. */
    val pathDocIds: Boolean = false,
    /** Names match in any case (`inbox.org` is an existing `Inbox.org`). */
    val caseInsensitiveNames: Boolean = false,
    /** A create only reserves the name; the file exists once something is written to it. */
    val createIsLazy: Boolean = false,
    /** The provider keeps both sides of a conflicting edit as a conflict copy. */
    val makesConflictCopies: Boolean = true,
    /**
     * A file created with a slash-separated name under an existing folder
     * (`ab/cdef/photo.jpg`) lands at that path, and its first write makes the
     * missing folders. Files can then reach folders the provider can't create.
     */
    val writeCreatesFolders: Boolean = false,
    /**
     * Creating a folder fails although the folder advertises creates (a provider
     * bug), so a failed folder create is reported as unsupported, not as a
     * one-off error. Bloom still tries first, in case the provider was fixed.
     */
    val folderCreateBroken: Boolean = false,
)

/** The known providers, looked up by a vault tree URI's authority. */
object ProviderProfiles {
    /** The platform's local-storage provider: what a Syncthing folder is. */
    val LOCAL = ProviderProfile(
        id = "local",
        authorities = setOf(EXTERNAL_STORAGE_AUTHORITY),
        displayName = R.string.storage_provider_local,
        pathDocIds = true,
        // Shared storage and SD cards (FAT/exFAT) are case-insensitive.
        caseInsensitiveNames = true,
    )

    /** Seadroid's documents provider. */
    val SEAFILE = ProviderProfile(
        id = "seafile",
        authorities = setOf("com.seafile.seadroid2.documents", "com.seafile.seadroid2.debug.documents"),
        displayName = R.string.storage_provider_seafile,
        createIsLazy = true,
        // The desktop client makes SFConflict copies; the Android app doesn't.
        makesConflictCopies = false,
        // Its upload sends the folder as `relative_path`, which the server creates.
        writeCreatesFolders = true,
        // Seadroid 4.0.15/4.0.16 declare `String createDirSync(...)` without a
        // Call<> wrapper, so every SAF folder create throws in Retrofit.
        folderCreateBroken = true,
    )

    /**
     * Nextcloud's Android app. Quirks are the conservative defaults until the
     * provider is checked on the test rig; flags drive rename/move/delete.
     */
    val NEXTCLOUD = ProviderProfile(
        id = "nextcloud",
        authorities = setOf("org.nextcloud.documents"),
        displayName = R.string.storage_provider_nextcloud,
    )

    /** Any provider not listed: no assumed quirks, and a generic name. */
    val DEFAULT = ProviderProfile(
        id = "default",
        authorities = emptySet(),
        displayName = R.string.storage_provider_generic,
    )

    val all: List<ProviderProfile> = listOf(LOCAL, SEAFILE, NEXTCLOUD)

    /** The profile for [authority]; [DEFAULT] for an unknown or null one. */
    fun forAuthority(authority: String?): ProviderProfile =
        authority?.let { a -> all.firstOrNull { a in it.authorities } } ?: DEFAULT
}
