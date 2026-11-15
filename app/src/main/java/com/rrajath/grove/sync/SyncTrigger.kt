package com.rrajath.grove.sync

import androidx.annotation.StringRes

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow interface over the sync side-effects that ViewModels invoke, so a test
 * can hand a VM a recording fake instead of the real [SyncManager] (which stands
 * up WorkManager, a FileObserver and a foreground Service). [SyncManager]
 * implements it; every member below already exists there.
 *
 * See internal/test-suite-01-integration-robolectric.md § Prerequisites (M2).
 */
interface SyncTrigger {
    /** Current sync state machine position (Idle → Checking → … → Done/Conflict/Error). */
    val state: StateFlow<SyncState>

    /** The most recent completed sync's result, or null before the first pass. */
    val lastResult: StateFlow<SyncResult?>

    /** Coalesced request for a full directory-diff sync pass. */
    fun requestSync(reason: String)

    /** Reindex a single file the caller just wrote, skipping the full directory diff. */
    fun requestReindex(fileName: String, text: String, reason: String)

    /** Wipe the index and rebuild from scratch (todo-keyword config changed). */
    fun clearAndResync(reason: String)

    /** Force Load: drop this notebook's cached index and re-pull it from disk. */
    suspend fun forceReload(fileName: String)

    /** The base file's text paired with its pending conflict copy's text, or null if no conflict. */
    suspend fun conflictTexts(baseName: String): Pair<String, String>?

    /**
     * The provider's display name when it won't let Bloom delete the conflict
     * copy [copyName] (resolving then leaves it for the user to remove there);
     * null when the copy can be deleted. A string resource id (`@StringRes`
     * can't annotate a suspend function's return).
     */
    suspend fun conflictCopyKeptBy(copyName: String): Int?

    /** Apply [resolution] to [baseName]'s conflict copy. */
    suspend fun resolveConflict(baseName: String, resolution: ConflictResolution): ConflictResolveResult
}

/** What [SyncTrigger.resolveConflict] did. */
sealed interface ConflictResolveResult {
    /** The base file took the resolution and the copy is gone. */
    data object Resolved : ConflictResolveResult

    /** The base file took the resolution, but [provider] wouldn't delete [copyName]. */
    data class CopyRemains(val copyName: String, @StringRes val provider: Int) : ConflictResolveResult

    /** Nothing was written: no store, the copy vanished, or the write was refused. */
    data object NotApplied : ConflictResolveResult
}
