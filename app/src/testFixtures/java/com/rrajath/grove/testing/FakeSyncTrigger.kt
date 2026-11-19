package com.rrajath.grove.testing

import com.rrajath.grove.sync.ConflictResolution
import com.rrajath.grove.sync.SyncResult
import com.rrajath.grove.sync.SyncState
import com.rrajath.grove.sync.SyncTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recording [SyncTrigger] for ViewModel tests: instead of standing up
 * WorkManager, a `FileObserver` and a foreground `Service`, it just remembers
 * which sync side-effects a VM asked for so a test can assert on them.
 *
 * The conflict helpers ([conflictTexts], [resolveConflict]) delegate to
 * caller-supplied lambdas; the defaults behave as "no conflict".
 */
class FakeSyncTrigger(
    private val onConflictTexts: suspend (String) -> Pair<String, String>? = { null },
    private val onResolveConflict: suspend (String, ConflictResolution) -> Boolean = { _, _ -> false },
) : SyncTrigger {

    data class ReindexCall(val fileName: String, val text: String, val reason: String)

    /** `reason` of every [requestSync] call, in order. */
    val syncRequests = mutableListOf<String>()

    /** Every [requestReindex] call, in order. */
    val reindexCalls = mutableListOf<ReindexCall>()

    /** `reason` of every [clearAndResync] call, in order. */
    val clearAndResyncRequests = mutableListOf<String>()

    /** File names passed to [forceReload], in order. */
    val forceReloadCalls = mutableListOf<String>()

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastResult = MutableStateFlow<SyncResult?>(null)
    override val lastResult: StateFlow<SyncResult?> = _lastResult.asStateFlow()

    override fun requestSync(reason: String) {
        syncRequests += reason
    }

    override fun requestReindex(fileName: String, text: String, reason: String) {
        reindexCalls += ReindexCall(fileName, text, reason)
    }

    override fun clearAndResync(reason: String) {
        clearAndResyncRequests += reason
    }

    override suspend fun forceReload(fileName: String) {
        forceReloadCalls += fileName
    }

    override suspend fun conflictTexts(baseName: String): Pair<String, String>? =
        onConflictTexts(baseName)

    override suspend fun resolveConflict(baseName: String, resolution: ConflictResolution): Boolean =
        onResolveConflict(baseName, resolution)

    // --- test helpers ---

    fun emitState(state: SyncState) {
        _state.value = state
    }

    fun emitResult(result: SyncResult) {
        _lastResult.value = result
    }
}
