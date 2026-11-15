package com.rrajath.grove.ui.vault

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.SyncLogEntity
import com.rrajath.grove.sync.ConflictResolution
import com.rrajath.grove.sync.ConflictResolveResult
import com.rrajath.grove.sync.conflicts.ConflictSchemes
import com.rrajath.grove.sync.SyncTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ConflictUiState {
    data object Loading : ConflictUiState()
    data object NoConflict : ConflictUiState()
    data class Loaded(
        val fileName: String,
        val currentText: String,
        val copyText: String,
        val copyLabel: String,
        /** The tool that made the copy ("Seafile"), or null for a name no scheme claims. */
        val schemeName: String? = null,
        /** The provider's name when it won't let Bloom delete the copy; null when it can. */
        @StringRes val copyKeptBy: Int? = null,
    ) : ConflictUiState()

    /**
     * The base file took the resolution, but [provider] wouldn't delete
     * [copyName]: the user removes it there. Until then the next sync flags the
     * conflict again.
     */
    data class CleanupNeeded(val copyName: String, @StringRes val provider: Int) : ConflictUiState()

    data object Resolved : ConflictUiState()
}

class ConflictViewModel(
    private val database: GroveDatabase,
    private val sync: SyncTrigger,
) : ViewModel() {

    private val _state = MutableStateFlow<ConflictUiState>(ConflictUiState.Loading)
    val state: StateFlow<ConflictUiState> = _state

    fun load(fileName: String) {
        viewModelScope.launch {
            val copyName = database.indexDao().conflictFileNameFor(fileName)
            val texts = sync.conflictTexts(fileName)
            _state.value = if (texts == null || copyName == null) {
                ConflictUiState.NoConflict
            } else {
                val copy = ConflictSchemes.parse(copyName)
                ConflictUiState.Loaded(
                    fileName = fileName,
                    currentText = texts.first,
                    copyText = texts.second,
                    copyLabel = copy?.label ?: copyName,
                    schemeName = copy?.scheme?.displayName,
                    copyKeptBy = sync.conflictCopyKeptBy(copyName),
                )
            }
        }
    }

    fun resolve(fileName: String, resolution: ConflictResolution) {
        viewModelScope.launch {
            when (val applied = sync.resolveConflict(fileName, resolution)) {
                ConflictResolveResult.Resolved -> _state.value = ConflictUiState.Resolved
                is ConflictResolveResult.CopyRemains ->
                    _state.value = ConflictUiState.CleanupNeeded(applied.copyName, applied.provider)
                ConflictResolveResult.NotApplied -> {
                    // The conflict copy vanished from under us (e.g. a background
                    // sync or the sync tool itself cleared it while the picker was
                    // open), or the write was refused: nothing was written. Reload
                    // instead of reporting success, so the user sees the real state
                    // (no conflict, or a fresh copy to resolve) rather than a silent
                    // no-op that looks like "kept current".
                    load(fileName)
                }
            }
        }
    }

    companion object {
        val Factory = factory { ConflictViewModel(it.database, it.syncManager) }
    }
}

class SyncLogViewModel(database: GroveDatabase) : ViewModel() {
    private val limit = MutableStateFlow(PAGE_SIZE)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<SyncLogEntity>> = limit
        .flatMapLatest { database.syncLogDao().recent(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<Int> = database.syncLogDao().count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun loadMore() {
        limit.value += PAGE_SIZE
    }

    companion object {
        const val PAGE_SIZE = 50
        val Factory = factory { SyncLogViewModel(it.database) }
    }
}
