package com.rrajath.grove.settings.backup

import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSerialization
import com.rrajath.grove.settings.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
@OptIn(FlowPreview::class)
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
        combine(settings.settings, templates, favorites, savedSearches, ::snapshotOf)
            .flowOn(dispatchers.default)
            .distinctUntilChanged()
            // Off (null) passes straight through, replacing and so cancelling a pending write.
            .debounce { if (it == null) 0L else DEBOUNCE_MS }
            .filterNotNull()
            .collect { writeIfChanged(it) }
    }

    /** Write the current state now if it differs from the last write. No-op while off or without a folder. */
    suspend fun flush() {
        val snapshot = withContext(dispatchers.default) {
            snapshotOf(settings.settings.first(), templates.first(), favorites.first(), savedSearches.first())
        } ?: return
        writeIfChanged(snapshot)
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

    private suspend fun writeIfChanged(snapshot: Snapshot) = writeMutex.withLock {
        // Re-read: the toggle, folder or last hash may have moved while this waited.
        val current = settings.settings.first()
        if (!current.autoBackupEnabled || current.autoBackupTreeUri != snapshot.treeUri) return@withLock
        val hash = sha256(snapshot.text)
        if (hash == current.autoBackupLastHash) return@withLock
        val result = withContext(dispatchers.io) {
            runCatching { writer.write(snapshot.treeUri, snapshot.text).getOrThrow() }
        }
        if (result.isSuccess) status.recordAutoBackupSuccess(clock(), hash) else status.recordAutoBackupFailure()
    }

    companion object {
        const val DEBOUNCE_MS = 2_000L

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
