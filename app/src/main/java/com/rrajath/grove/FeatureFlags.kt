package com.rrajath.grove

/** Compile-time switches for features still being built. */
object FeatureFlags {
    /**
     * Image and file attachments (org-attach compatible). Debug builds only
     * until the feature graduates; release builds keep today's behavior,
     * including vault traversal (no `data/` shard skip).
     */
    val ATTACHMENTS: Boolean = BuildConfig.DEBUG
}
