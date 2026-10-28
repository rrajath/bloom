package com.rrajath.grove

import android.app.Application
import android.text.format.DateFormat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.rrajath.grove.capture.SharedPayload
import com.rrajath.grove.capture.ShortcutSyncer
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.RoomNoteIndex
import com.rrajath.grove.icon.AppIconManager
import com.rrajath.grove.icon.NotificationAppearance
import com.rrajath.grove.org.LocalDates
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.reminders.AlarmScheduler
import com.rrajath.grove.reminders.ExactAlarmAccessWatcher
import com.rrajath.grove.reminders.ReminderDigestScheduler
import com.rrajath.grove.reminders.ReminderReconciler
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.sync.SyncManager
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.IgnorePatterns
import com.rrajath.grove.widget.CaptureNotification
import com.rrajath.grove.vault.JvmFileStore
import com.rrajath.grove.vault.SafFileStore
import com.rrajath.grove.vault.TestVaultHook
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.widget.LedgerWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Composition root for app-wide singletons (manual DI; the app is small).
 *
 * `open` only so a Robolectric unit test can subclass it with a no-op
 * [onCreate] (`testing.TestGroveApplication`); production has exactly one
 * instance, created by the framework.
 */
/**
 * Historical ignore-file name from the now-superseded `.orgzlyignore`
 * mechanism (see [com.rrajath.grove.vault.IgnorePatterns] and
 * internal/ignore-list-feature/00-overview.md). Hardcoded as a literal rather
 * than a shared constant, since the class that used to own it was deleted
 * once every caller of it was migrated.
 */
private const val LEGACY_IGNORE_FILE_NAME = ".orgzlyignore"

open class GroveApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Real dispatchers, injected into ViewModels so tests can swap in a test one. */
    val dispatchers = AppDispatchers()

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this, appScope) }

    val templatesRepository: TemplatesRepository by lazy { TemplatesRepository(this) }

    val searchRepository: SearchRepository by lazy { SearchRepository(this) }

    val favoritesRepository: FavoritesRepository by lazy { FavoritesRepository(this) }

    /**
     * Content shared into the app (PRD §10). Observed by the UI so it can be
     * routed into the configured file even when the app was already running.
     */
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<SharedPayload?>(null)

    val database: GroveDatabase by lazy { GroveDatabase.build(this) }

    /** Current TODO keyword config; parser consumers read this. */
    val keywords: StateFlow<OrgKeywords> by lazy {
        settingsRepository.settings
            .map { it.todoKeywords }
            .distinctUntilChanged()
            .map { OrgKeywords.parse(it) }
            .stateIn(appScope, SharingStarted.Eagerly, OrgKeywords.DEFAULT)
    }

    /**
     * Tint for notification small icons, following "Sync App Icon with Theme".
     * Eagerly shared so notification builders running in a receiver can read
     * `.value` without suspending (see `icon/NotificationAppearance.kt`).
     *
     * [onCreate] touches this so the DataStore read starts at process start
     * rather than on the first notification: `stateIn` hands out its seed until
     * the upstream first emits, so a lazily-created flow would tint the very
     * first notification with the default mark regardless of the setting.
     */
    val notificationMarkColor: StateFlow<Int> by lazy {
        settingsRepository.settings
            .map { AppIconManager.markColor(it.syncAppIconWithTheme, it.theme) }
            .distinctUntilChanged()
            .stateIn(
                appScope,
                SharingStarted.Eagerly,
                AppIconManager.markColor(enabled = false, theme = ThemePreference.LIGHT),
            )
    }

    val reminderReconciler: ReminderReconciler by lazy {
        ReminderReconciler(this, database.reminderDao())
    }

    val exactAlarmAccessWatcher: ExactAlarmAccessWatcher by lazy {
        ExactAlarmAccessWatcher(
            canScheduleExact = { AlarmScheduler.canScheduleExactAlarms(this) },
            lastKnown = { settingsRepository.settings.first().exactAlarmAccessLastKnown },
            storeLastKnown = { settingsRepository.setExactAlarmAccessLastKnown(it) },
            rearm = {
                reminderReconciler.rearmFuture()
                val settings = settingsRepository.settings.first()
                if (settings.remindersEnabled && settings.morningBriefEnabled) {
                    ReminderDigestScheduler.scheduleNext(this, settings.defaultReminderTime)
                }
            },
        )
    }

    // Coalesces bursts of onSyncCompleted calls (e.g. a run of outline swipes,
    // each now a single-file reindex per PERFORMANCE_AUDIT_2026-09-16 D2) into
    // at most one widget IPC push per second (D3), instead of one per edit.
    private val widgetRefreshRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val syncManager: SyncManager by lazy {
        SyncManager(
            this, appScope, database,
            keywords = { keywords.value },
            onNotebookIndexed = ::reconcileFileReminders,
            onSyncCompleted = { result ->
                // Only prune on a real directory-diffed pass that actually saw a
                // removal: a null result means the sync failed (or was a targeted
                // single-file reindex), and an empty/stale notebook list there
                // would wrongly cancel live reminders.
                if (result != null && result.removed.isNotEmpty()) {
                    reminderReconciler.pruneRemovedFiles(
                        database.indexDao().notebooks().mapTo(HashSet()) { it.fileName },
                    )
                }
                // catchUpOverdue reconciles day-boundary rollover, not per-edit
                // state — reconcileFileReminders (onNotebookIndexed above)
                // already re-derives the touched file's own reminders on every
                // pass, reindex included. Only worth paying for on a full sync
                // that actually pulled something new.
                if (result != null && result.pulled.isNotEmpty()) {
                    reminderReconciler.catchUpOverdue()
                }
                widgetRefreshRequests.tryEmit(Unit)
            },
        )
    }

    /**
     * The active vault file store, swapping whenever the configured tree URI
     * changes. [TestVaultHook.root] is a test-only override (null in any
     * `release` build — the path is R8-stripped) that forces a plain-directory
     * store for Maestro / the macrobenchmark module; see `debug.DebugTestVault`.
     */
    val fileStore: StateFlow<FileStore?> by lazy {
        combine(
            settingsRepository.settings.map { it.vaultTreeUri }.distinctUntilChanged(),
            settingsRepository.settings.map { it.ignoreList }.distinctUntilChanged(),
            TestVaultHook.root,
        ) { uriString, ignoreListText, testRoot ->
            val ignore = IgnorePatterns(ignoreListText)
            when {
                testRoot != null -> JvmFileStore(testRoot, ignore)
                uriString != null -> SafFileStore(this, uriString.toUri(), ignore)
                else -> null
            }
        }.stateIn(appScope, SharingStarted.Eagerly, null)
    }

    val vault: StateFlow<Vault?> by lazy {
        combine(fileStore, keywords) { store, kw -> store?.let { Vault(it, kw) } }
            .stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /** Today's daily note, warmed when the drawer opens so Dailies renders at once. */
    val dailyNoteLookup: com.rrajath.grove.dailies.DailyNoteLookup by lazy {
        com.rrajath.grove.dailies.DailyNoteLookup(vault, settingsRepository, appScope, dispatchers.io)
    }

    private suspend fun reconcileFileReminders(fileName: String, doc: com.rrajath.grove.org.OrgDocument) {
        val settings = settingsRepository.settings.first()
        reminderReconciler.reconcileFile(
            fileName, doc, settings.defaultReminderTime, settings.remindersEnabled,
            settings.reminderLeadTime, settings.notifyUntimedTasks,
        )
    }

    /**
     * Indexes [fileName] into Room right now from [text] the caller just wrote to
     * the vault, instead of waiting on [syncManager]'s async, revision-diffed
     * sync pass. That pass is fire-and-forget ([SyncManager.requestSync] returns
     * before it finishes) and, worse, a complete no-op if it runs before
     * [syncManager] has been `attach`ed yet - which a widget action can easily
     * hit, since it may be reviving a process Android had killed in the
     * background, racing [fileStore]'s async DataStore read. The ledger widget's
     * mark-done/quick-add actions call this right after [Vault.save] so their
     * own immediate `LedgerWidget().updateAll()` is guaranteed to see the edit,
     * rather than depending on a sync pass that might not land for a while, or
     * at all.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun collectWidgetRefreshRequests() {
        widgetRefreshRequests.debounce(1_000).collect {
            LedgerWidget().updateAll(this@GroveApplication)
        }
    }

    suspend fun reindexNow(fileName: String, text: String) {
        // Non-.org files (an `agenda.org_archive` archive target) stay out of the index, like a full sync.
        if (!fileName.endsWith(".org")) return
        val store = fileStore.value
            ?: return run { android.util.Log.w("GroveWidget", "reindexNow: fileStore null for $fileName") }
        val stat = store.stat(fileName)
            ?: return run { android.util.Log.w("GroveWidget", "reindexNow: stat null for $fileName") }
        val conflictFileName = database.indexDao().conflictFileNameFor(fileName)
        RoomNoteIndex(database, keywords = { keywords.value }, onIndexed = ::reconcileFileReminders).indexNotebook(
            fileName = fileName,
            revision = "${stat.lastModified}:${stat.size}",
            text = text,
            lastModified = stat.lastModified,
            conflictFileName = conflictFileName,
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Warm the notification tint before anything can post a notification.
        notificationMarkColor

        // Reader-facing dates follow the device locale's word order.
        LocalDates.bestPattern = { locale, skeleton -> DateFormat.getBestDateTimePattern(locale, skeleton) }

        appScope.launch { collectWidgetRefreshRequests() }

        appScope.launch {
            // .orgzlyignore is superseded by the ignoreList setting (see
            // internal/ignore-list-feature/00-overview.md). Import its lines into
            // ignoreList exactly once, then never read the file again — the file
            // itself is left on disk, untouched.
            val store = fileStore.filterNotNull().first()
            val settings = settingsRepository.settings.first()
            if (!settings.ignoreListImportCheckDone) {
                val legacyText = runCatching { store.read(LEGACY_IGNORE_FILE_NAME) }.getOrNull()
                val imported = legacyText
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                    ?.toList()
                    ?: emptyList()
                // Always write both flags explicitly (not just on the true
                // branch) so this corrects any stale value left over from a
                // prior buggy run — e.g. an older build that unconditionally
                // marked ignoreListImportedFromFile true regardless of outcome.
                // This block only ever runs once per install (guarded by
                // ignoreListImportCheckDone above), so it's the one chance to
                // make the persisted flags authoritative.
                settingsRepository.setIgnoreListImportedFromFile(imported.isNotEmpty())
                settingsRepository.setIgnoreListLegacyFileEmpty(imported.isEmpty() && legacyText != null)
                if (imported.isNotEmpty()) {
                    val merged = (settings.ignoreList.lineSequence().filter { it.isNotBlank() } + imported)
                        .distinct()
                        .joinToString("\n")
                    settingsRepository.setIgnoreList(merged)
                }
                settingsRepository.markIgnoreListImportCheckDone()
            }
        }

        appScope.launch {
            // Notifications bake in their color at post time, so a theme switch
            // would otherwise leave everything already in the shade wearing the
            // previous theme's mark while the launcher icon had moved on.
            // Read off the settings flow directly (not [notificationMarkColor]):
            // that flow is eagerly seeded with a hardcoded light/disabled color
            // before settings ever load, so for anyone whose actual settings
            // differ from that seed, its first *real* emission is a second
            // distinct value that drop(1) wouldn't catch, re-tinting every
            // notification-eligible asset on every cold start. The settings
            // flow's first emission is genuinely the persisted startup value,
            // which drop(1) here correctly skips.
            settingsRepository.settings
                .map { AppIconManager.markColor(it.syncAppIconWithTheme, it.theme) }
                .distinctUntilChanged()
                .drop(1)
                .collect { NotificationAppearance.retintActive(this@GroveApplication) }
        }

        appScope.launch {
            // Wait for a real settings read before wiring up sync triggers.
            // [fileStore] and [keywords] are independent `stateIn` flows each
            // doing their own async DataStore read; without this wait,
            // [syncManager.attach] can fire (and index every notebook) while
            // [keywords] is still on its `OrgKeywords.DEFAULT` seed, baking a
            // wrong `keyword`/`isDone` into Room for any custom TODO keyword
            // until the next full reindex.
            //
            // Awaiting `settings.first()` alone is NOT enough (this was the gap
            // in the previous fix, commits 343f65b/fd1dc85): it only guarantees
            // DataStore's Preferences are cached in memory. [keywords] is a
            // separate `by lazy` `stateIn(..., Eagerly, OrgKeywords.DEFAULT)`
            // that nothing here has touched yet - [syncManager]'s constructor
            // only stores the `{ keywords.value }` closure, it doesn't call it.
            // That closure's first-ever invocation happens synchronously deep
            // inside `RoomNoteIndex.indexNotebook`, during the very sync this
            // `attach` triggers, with no suspension point in between. `stateIn`
            // hands out its `DEFAULT` seed the instant the flow is *created*,
            // regardless of how much wall-clock time has passed - so a purely
            // time-based "it'll probably have caught up by then" assumption
            // (which is all the previous fix provided) is still a race. Block
            // explicitly on [keywords] itself until it has actually emitted the
            // value derived from the settings just read, so every reader from
            // this point on (including this sync's indexing pass) sees the real
            // config instead of the seed.
            val settings = settingsRepository.settings.first()
            // Timeout is a belt-and-suspenders fallback only (e.g. settings
            // changing again in the split second between the two reads above
            // would otherwise wait for an [OrgKeywords] value that never
            // arrives); it should resolve almost immediately since DataStore's
            // Preferences are already cached by the read above.
            withTimeoutOrNull(5_000) {
                keywords.first { it == OrgKeywords.parse(settings.todoKeywords) }
            }
            fileStore.collect { syncManager.attach(it) }
        }
        appScope.launch {
            // Keyword config changes how files parse; rebuild the index.
            // Read off the settings flow directly (not [keywords]): that flow
            // is eagerly seeded with OrgKeywords.DEFAULT before settings ever
            // load, so for anyone whose todoKeywords differs from the compiled
            // default, its first *real* emission is a second distinct value
            // that drop(1) wouldn't catch, wiping and rebuilding the whole
            // index on every cold start. The settings flow itself has no seed
            // to trip over: its first emission is genuinely the persisted
            // startup value, which drop(1) here correctly skips.
            settingsRepository.settings
                .map { it.todoKeywords }
                .distinctUntilChanged()
                .drop(1)
                .collect { syncManager.clearAndResync("keyword config changed") }
        }
        appScope.launch {
            settingsRepository.settings
                .map { it.syncMode to it.periodicSyncMinutes }
                .distinctUntilChanged()
                .collect { (mode, minutes) -> syncManager.schedulePeriodic(mode, minutes) }
        }

        appScope.launch {
            settingsRepository.settings
                .map { it.captureNotification }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) CaptureNotification.show(this@GroveApplication)
                    else CaptureNotification.hide(this@GroveApplication)
                }
        }

        // App-start safety net: fire anything overdue that was missed while the
        // process was dead, and pick up reminders that were only waiting on a
        // permission grant. DB-only: doesn't need the vault to be ready.
        appScope.launch {
            reminderReconciler.pruneRemovedFiles(
                database.indexDao().notebooks().mapTo(HashSet()) { it.fileName },
            )
            reminderReconciler.catchUpOverdue()
            reminderReconciler.reconcilePending()
            exactAlarmAccessWatcher.check()
        }

        appScope.launch {
            // "Enable reminders" toggled off cancels everything immediately rather
            // than waiting for the next per-file reconcile; toggled back on (or the
            // default reminder time / lead time changing, both of which affect
            // trigger times, or the "notify for tasks without a time" toggle) re-scans
            // every already-indexed notebook.
            settingsRepository.settings
                .map {
                    ReminderReconcileKeys(
                        it.remindersEnabled, it.defaultReminderTime, it.reminderLeadTime, it.notifyUntimedTasks,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { (enabled, defaultTime, leadTime, notifyUntimed) ->
                    if (!enabled) {
                        reminderReconciler.disableAll()
                    } else {
                        val vault = vault.value ?: return@collect
                        val documents = database.indexDao().notebooks()
                            .mapNotNull { nb -> vault.open(nb.fileName)?.let { nb.fileName to it } }
                            .toMap()
                        reminderReconciler.reconcileAll(documents, defaultTime, enabled, leadTime, notifyUntimed)
                    }
                }
        }

        appScope.launch {
            // The Agenda ledger widget only redraws when explicitly told to (see
            // LedgerWidget's provideGlance doc); transparency/days-ahead are purely
            // cosmetic to it, so nudge it here instead of waiting for the next sync.
            settingsRepository.settings
                .map { it.agendaWidgetTransparency to it.agendaWidgetDaysAhead }
                .distinctUntilChanged()
                .drop(1)
                .collect { LedgerWidget().updateAll(this@GroveApplication) }
        }

        appScope.launch {
            // The daily digest ("You have X tasks due today") is a single alarm
            // independent of the per-heading ones above, no drop(1): the very
            // first emission (current settings on cold start) must (re)arm it too,
            // since AlarmManager alarms don't survive a process being killed.
            settingsRepository.settings
                .map { Triple(it.remindersEnabled, it.morningBriefEnabled, it.defaultReminderTime) }
                .distinctUntilChanged()
                .collect { (remindersEnabled, morningBriefEnabled, defaultTime) ->
                    if (remindersEnabled && morningBriefEnabled) {
                        ReminderDigestScheduler.scheduleNext(this@GroveApplication, defaultTime)
                    } else {
                        ReminderDigestScheduler.cancel(this@GroveApplication)
                    }
                }
        }

        appScope.launch {
            // Long-press launcher shortcuts mirror the configured capture
            // templates, so add/edit/delete/reorder in Settings is reflected
            // immediately (including DataStore's initial emission on cold start).
            // Shortcut icon colors follow the same theme + sync-with-icon gate
            // as the launcher icon itself (AppIconManager), so both stay in sync.
            combine(
                templatesRepository.templates,
                settingsRepository.settings
                    .map { it.theme to it.syncAppIconWithTheme }
                    .distinctUntilChanged(),
            ) { templates, (theme, iconThemed) -> Triple(templates, theme, iconThemed) }
                .distinctUntilChanged()
                .collect { (templates, theme, iconThemed) ->
                    ShortcutSyncer.sync(this@GroveApplication, templates, theme, iconThemed)
                }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch {
                    syncManager.onAppForeground(settingsRepository.settings.first().syncMode)
                }
                // Back from system Settings (or anywhere) with exact-alarm access
                // changed: re-arm so existing alarms switch exact/inexact.
                appScope.launch { exactAlarmAccessWatcher.check() }
            }

            override fun onStop(owner: LifecycleOwner) {
                appScope.launch {
                    syncManager.onAppBackground(settingsRepository.settings.first().syncMode)
                }
            }
        })
    }
}

/** The settings whose change forces a full re-scan of every notebook's reminders. */
private data class ReminderReconcileKeys(
    val enabled: Boolean,
    val defaultTime: java.time.LocalTime,
    val leadTime: com.rrajath.grove.settings.ReminderLeadTime,
    val notifyUntimed: Boolean,
)
