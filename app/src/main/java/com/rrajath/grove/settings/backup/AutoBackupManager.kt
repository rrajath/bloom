package com.rrajath.grove.settings.backup

import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSerialization
import com.rrajath.grove.settings.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Keeps `<app name>-settings.json` in the user's chosen folder in step with settings,
 * capture templates, favorites and saved searches.
 *
 * Change-driven, never polled: each emission of the four sources is serialized
 * exactly as the manual Export writes it; identical JSON stops at
 * [distinctUntilChanged] (so writes of device-local fields, including this
 * manager's own status, never loop back), and a real change is written
 * [DEBOUNCE_MS] after the last one. The persisted SHA-256 of the last write
 * skips content already on disk, which covers a cold start. [flush] writes at
 * once for app-background and folder pick.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AutoBackupManager(
    private val scope: CoroutineScope,
    private val settings: SettingsSource,
    private val templates: Flow<List<CaptureTemplate>>,
    private val favorites: Flow<List<FavoriteNote>>,
    private val savedSearches: Flow<List<SavedSearch>>,
    private val writer: BackupWriter,
    private val status: AutoBackupStatusSink,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What would be written, and where. */
    private data class Snapshot(val treeUri: String, val text: String)

    /** Serializes writes so a [flush] and a debounced write never interleave. */
    private val writeMutex = Mutex()

    fun start(): Job = scope.launch {
        launch { repairIfMissing() }
        settings.settings
            .map { it.autoBackupEnabled && it.autoBackupTreeUri != null }
            .distinctUntilChanged()
            // Off subscribes to nothing but settings: templates, favorites and searches
            // are only watched while a backup is being kept.
            .flatMapLatest { on ->
                if (on) combine(settings.settings, templates, favorites, savedSearches, ::snapshotOf) else flowOf(null)
            }
            .flowOn(dispatchers.default)
            .distinctUntilChanged()
            // Off (null) passes straight through, replacing and so cancelling a pending write.
            .debounce { if (it == null) 0L else DEBOUNCE_MS }
            .filterNotNull()
            .collect { writeIfChanged(it) }
    }

    /**
     * Write the current state now if it differs from the last write ([force]: regardless,
     * e.g. replacing a file another device wrote). No-op while off or without a folder.
     */
    suspend fun flush(force: Boolean = false) {
        writeIfChanged(currentSnapshot() ?: return, force)
    }

    /**
     * The last write is on record but the file is gone (deleted in a file manager):
     * write it again. The hash skip would otherwise hold off until the next change.
     * Runs at start and when Settings > Backup finds the file missing.
     */
    suspend fun repairIfMissing() {
        val s = settings.current()
        val treeUri = s.autoBackupTreeUri?.takeIf { s.autoBackupEnabled && s.autoBackupLastHash != null } ?: return
        val exists = withContext(dispatchers.io) { runCatching { writer.exists(treeUri) }.getOrDefault(true) }
        if (!exists) writeIfChanged(currentSnapshot() ?: return, force = true)
    }

    private suspend fun currentSnapshot(): Snapshot? = withContext(dispatchers.default) {
        snapshotOf(settings.current(), templates.first(), favorites.first(), savedSearches.first())
    }

    private fun snapshotOf(
        s: GroveSettings,
        templates: List<CaptureTemplate>,
        favorites: List<FavoriteNote>,
        searches: List<SavedSearch>,
    ): Snapshot? {
        val treeUri = s.autoBackupTreeUri?.takeIf { s.autoBackupEnabled } ?: return null
        return Snapshot(treeUri, SettingsSerialization.export(s, templates, favorites, searches))
    }

    private suspend fun writeIfChanged(snapshot: Snapshot, force: Boolean = false) = writeMutex.withLock {
        // Re-read from storage: the toggle, folder or last hash may have moved while this waited.
        val current = settings.current()
        if (!current.autoBackupEnabled || current.autoBackupTreeUri != snapshot.treeUri) return@withLock
        val hash = sha256(snapshot.text)
        if (!force && hash == current.autoBackupLastHash) return@withLock
        val result = withContext(dispatchers.io) {
            runCatching { writer.write(snapshot.treeUri, snapshot.text).getOrThrow() }
        }
        if (result.isSuccess) status.recordAutoBackupSuccess(snapshot.treeUri, clock(), hash)
        else status.recordAutoBackupFailure(snapshot.treeUri)
    }

    companion object {
        const val DEBOUNCE_MS = 2_000L

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
