package com.rrajath.grove.testing

import android.annotation.SuppressLint
import com.rrajath.grove.clock.ClockService
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.RoomNoteIndex
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/**
 * A [ClockService] over test fakes: its writes are reindexed straight into
 * [db] from [store], as `GroveApplication.reindexNow` does in the app, so the
 * running-clock flow follows every clock edit.
 */
object TestClockService {
    // Core library desugaring provides java.time in every APK these fixtures go
    // into; lint doesn't apply desugaring to the testFixtures source set.
    @SuppressLint("NewApi")
    fun create(
        vault: StateFlow<Vault?>,
        db: GroveDatabase,
        store: FileStore,
        settings: SettingsSource,
        scope: CoroutineScope,
        keywords: OrgKeywords = OrgKeywords.DEFAULT,
        now: () -> LocalDateTime = { LocalDateTime.now() },
    ): ClockService = ClockService(
        vault = vault,
        runningRows = db.indexDao().runningClocks(),
        settings = settings,
        reindex = reindex@{ fileName, text ->
            val stat = store.stat(fileName) ?: return@reindex
            RoomNoteIndex(db, { keywords }).indexNotebook(
                fileName = fileName,
                revision = "${stat.lastModified}:${stat.size}",
                text = text,
                lastModified = stat.lastModified,
                conflictFileName = null,
            )
        },
        scope = scope,
        now = now,
    )
}
