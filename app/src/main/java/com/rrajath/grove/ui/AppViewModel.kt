package com.rrajath.grove.ui

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.capture.ShareIntake
import com.rrajath.grove.capture.SharedPayload
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.reminders.AlarmScheduler
import com.rrajath.grove.reminders.ExactAlarmPrompt
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.settings.AgendaSwipeAction
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.NoteOpenMode
import com.rrajath.grove.settings.NotebookDisplayNameMode
import com.rrajath.grove.settings.OutlineToggle
import com.rrajath.grove.settings.ReminderLeadTime
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.settings.SettingsSerialization
import com.rrajath.grove.settings.SyncMode
import com.rrajath.grove.settings.ThemeMode
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.ui.newbadge.NewBadgeState
import com.rrajath.grove.ui.editor.LinkSearchItem
import com.rrajath.grove.ui.vault.NotebookItem
import com.rrajath.grove.ui.vault.RefileNotebook
import com.rrajath.grove.ui.vault.RefileUiState
import com.rrajath.grove.ui.vault.allFolderDirs
import com.rrajath.grove.ui.vault.loadRefileSearchIndex
import com.rrajath.grove.ui.vault.withSearchHit
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.vault.Vault
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import com.rrajath.grove.whatsnew.ChangelogParser
import com.rrajath.grove.whatsnew.ChangelogVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A just-saved Roam capture file, offering a "Visit file" snackbar action (~4.2s, like [com.rrajath.grove.ui.vault.OutlineSnack]). */
data class CaptureSavedSnack(val filePath: String, val id: Long)

class AppViewModel(
    private val settingsRepository: SettingsRepository,
    private val searchRepository: SearchRepository,
    private val favoritesRepository: FavoritesRepository,
    private val database: GroveDatabase,
    private val sync: SyncTrigger,
    private val vaultFlow: StateFlow<Vault?>,
    private val pendingShare: MutableStateFlow<SharedPayload?>,
    private val dispatchers: AppDispatchers,
    // Kept for the handful of Android Context APIs with no JVM stub: Toast,
    // asset streams, contentResolver, getString, and ShareIntake.consumeShare.
    private val app: GroveApplication,
) : ViewModel() {

    /** Null until the DataStore emits, so the UI can gate on first load. */
    val settings: StateFlow<GroveSettings?> = settingsRepository.settings
        .map { it as GroveSettings? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Existing vault directories, for the Dailies location field. */
    val directories: StateFlow<List<String>> = database.indexDao().notebooksFlow()
        .map { list -> allFolderDirs(list.map { NotebookItem(it.fileName, 0, 0, false) }).sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedSearches: StateFlow<List<SavedSearch>> = searchRepository.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteSavedSearch(id: String) =
        viewModelScope.launch { searchRepository.deleteSearch(id) }

    fun renameSavedSearch(id: String, name: String) =
        viewModelScope.launch { searchRepository.renameSearch(id, name) }

    fun moveSavedSearch(id: String, delta: Int) =
        viewModelScope.launch { searchRepository.moveSearch(id, delta) }

    val favorites: StateFlow<List<FavoriteNote>> = favoritesRepository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var captureSavedSnackId = 0L
    private val _captureSavedSnack = MutableStateFlow<CaptureSavedSnack?>(null)

    /**
     * Held here (not in [com.rrajath.grove.ui.capture.CaptureViewModel]) because
     * it must outlive the capture screen: the capture route pops off the back
     * stack the moment it fires, so a screen-scoped ViewModel would already be
     * cleared by the time the snackbar needs to render over whatever's
     * revealed underneath.
     */
    val captureSavedSnack: StateFlow<CaptureSavedSnack?> = _captureSavedSnack

    fun showCaptureSavedSnack(filePath: String) {
        val snack = CaptureSavedSnack(filePath, ++captureSavedSnackId)
        _captureSavedSnack.value = snack
        viewModelScope.launch {
            delay(4200)
            if (_captureSavedSnack.value?.id == snack.id) _captureSavedSnack.value = null
        }
    }

    fun dismissCaptureSavedSnack() {
        _captureSavedSnack.value = null
    }

    /**
     * @param customId the heading's existing or newly-written stable id — see
     *   [com.rrajath.grove.ui.vault.DocumentViewModel.ensureStableId], which the caller
     *   (the currently-open [com.rrajath.grove.ui.vault.DocumentViewModel]) must resolve
     *   first so this favorite can be found again by id instead of by raw line number
     *   (line numbers drift under external edits, which is this app's primary edit path).
     */
    fun addFavorite(fileName: String, lineIndex: Int, title: String, customId: String?) =
        viewModelScope.launch {
            favoritesRepository.addFavorite(FavoriteNote(fileName, lineIndex, title, customId))
        }

    fun removeFavorite(fileName: String, lineIndex: Int, customId: String? = null) =
        viewModelScope.launch { favoritesRepository.removeFavorite(fileName, lineIndex, customId) }

    fun renameFavorite(fileName: String, lineIndex: Int, title: String, customId: String? = null) =
        viewModelScope.launch { favoritesRepository.renameFavorite(fileName, lineIndex, title, customId) }

    fun moveFavorite(fileName: String, lineIndex: Int, delta: Int, customId: String? = null) =
        viewModelScope.launch { favoritesRepository.moveFavorite(fileName, lineIndex, delta, customId) }

    fun setThemeMode(mode: ThemeMode) =
        viewModelScope.launch { settingsRepository.updateThemeSelection { it.copy(mode = mode) } }

    fun setLightTheme(theme: ThemePreference) =
        viewModelScope.launch { settingsRepository.updateThemeSelection { it.copy(light = theme) } }

    fun setDarkTheme(theme: ThemePreference) =
        viewModelScope.launch { settingsRepository.updateThemeSelection { it.copy(dark = theme) } }

    fun setSyncAppIconWithTheme(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setSyncAppIconWithTheme(enabled)
        if (enabled) toast("Restart the app for the icon change to take effect")
    }

    fun setAppFontSize(fontSize: FontSizePreference) =
        viewModelScope.launch { settingsRepository.setAppFontSize(fontSize) }

    fun setReadModeFontSize(fontSize: FontSizePreference) =
        viewModelScope.launch { settingsRepository.setReadModeFontSize(fontSize) }

    fun setEditModeFontSize(fontSize: FontSizePreference) =
        viewModelScope.launch { settingsRepository.setEditModeFontSize(fontSize) }

    fun setDefaultNoteOpenMode(mode: NoteOpenMode) =
        viewModelScope.launch { settingsRepository.setDefaultNoteOpenMode(mode) }

    fun setNewNoteCursor(cursor: com.rrajath.grove.settings.NewNoteCursor) =
        viewModelScope.launch { settingsRepository.setNewNoteCursor(cursor) }

    fun setAutoSaveNotes(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoSaveNotes(enabled) }

    fun setShowNotebookFileIcons(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setShowNotebookFileIcons(enabled) }

    fun setFlattenNotebookFolders(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setFlattenNotebookFolders(enabled) }

    fun setNotebookSortKey(key: com.rrajath.grove.settings.NotebookSortKey) =
        viewModelScope.launch { settingsRepository.setNotebookSortKey(key) }

    fun setNotebookSortAscending(ascending: Boolean) =
        viewModelScope.launch { settingsRepository.setNotebookSortAscending(ascending) }

    fun completeOnboarding() = viewModelScope.launch {
        // A brand-new install has nothing "new" to report: stamp the current build as
        // already seen, in the same write that flips onboardingDone, so the What's New
        // modal never fires for someone who just onboarded (see setOnboardingDone).
        settingsRepository.setOnboardingDone(true, com.rrajath.grove.BuildConfig.VERSION_CODE)
    }

    private val _whatsNew = MutableStateFlow<List<ChangelogVersion>>(emptyList())
    val whatsNew: StateFlow<List<ChangelogVersion>> = _whatsNew

    /**
     * Loads CHANGELOG.md's bundled asset and shows whatever's new since the version last
     * recorded as seen. Call once onboarding is confirmed done (see [completeOnboarding]).
     */
    fun checkWhatsNew() = viewModelScope.launch(dispatchers.io) {
        val current = com.rrajath.grove.BuildConfig.VERSION_CODE
        // Read straight from the store rather than settings.value: this runs off a
        // recomposition triggered by onboardingDone flipping, and the cached
        // StateFlow value can still be a step behind the transaction that also
        // stamped lastSeenChangelogBuild on a fresh install.
        val lastSeen = settingsRepository.settings.first().lastSeenChangelogBuild
        if (lastSeen == current) return@launch
        val text = runCatching {
            app.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@launch
        val entries = if (lastSeen == null) {
            // Existing install seeing this feature for the first time: no recorded baseline to
            // diff against, so show just the newest shipped version instead of the full history.
            ChangelogParser.parse(text)
                .filter { it.subsections.any { s -> s.items.isNotEmpty() } }
                .firstOrNull { it.versionCode != null }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            ChangelogParser.entriesSince(text, lastSeen)
        }
        if (entries.isNotEmpty()) _whatsNew.value = entries
    }

    fun dismissWhatsNew() = markWhatsNewSeen()

    private val _exactAlarmPrompt = MutableStateFlow(false)
    /** The one-time "Get reminders on time" dialog; see [ExactAlarmPrompt] for when. */
    val exactAlarmPrompt: StateFlow<Boolean> = _exactAlarmPrompt

    /** Called on every ON_RESUME (and when What's New closes, so the two never stack). */
    fun checkExactAlarmPrompt() = viewModelScope.launch(dispatchers.io) {
        val s = settingsRepository.settings.first()
        _exactAlarmPrompt.value = ExactAlarmPrompt.shouldShow(
            onboardingDone = s.onboardingDone,
            promptHandled = s.exactAlarmPromptHandled,
            remindersEnabled = s.remindersEnabled,
            morningBriefEnabled = s.morningBriefEnabled,
            hasFutureReminder = database.reminderDao().futureCount(System.currentTimeMillis()) > 0,
            notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
            canScheduleExact = AlarmScheduler.canScheduleExactAlarms(app),
        )
    }

    /** "Allow": never ask again; the caller opens the system page (needs an Activity context). */
    fun onExactAlarmPromptAllow() {
        _exactAlarmPrompt.value = false
        viewModelScope.launch { settingsRepository.markExactAlarmPromptHandled() }
    }

    /** "Not now" (or back): never ask again, but say where to turn it on later. */
    fun onExactAlarmPromptNotNow() {
        _exactAlarmPrompt.value = false
        viewModelScope.launch {
            settingsRepository.markExactAlarmPromptHandled()
            toast(
                "You can turn this on later in Settings › Reminders › Exact timing.",
                android.widget.Toast.LENGTH_LONG,
            )
        }
    }

    /**
     * Full shipped-release history for Settings › About › What's New, loaded once from the
     * bundled CHANGELOG.md asset. Empty until [loadWhatsNewHistory] has run.
     */
    private val _whatsNewHistory = MutableStateFlow<List<ChangelogVersion>>(emptyList())
    val whatsNewHistory: StateFlow<List<ChangelogVersion>> = _whatsNewHistory

    fun loadWhatsNewHistory() = viewModelScope.launch(dispatchers.io) {
        if (_whatsNewHistory.value.isNotEmpty()) return@launch
        val text = runCatching {
            app.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@launch
        _whatsNewHistory.value = ChangelogParser.shippedReleases(text)
    }

    /**
     * Records the current build as the last one whose changes the user has seen and clears any
     * pending launch-time modal. Called both when that modal is dismissed and when the What's
     * New screen is opened, so seeing it in one place suppresses it in the other.
     */
    fun markWhatsNewSeen() {
        _whatsNew.value = emptyList()
        viewModelScope.launch { settingsRepository.setLastSeenChangelogBuild(com.rrajath.grove.BuildConfig.VERSION_CODE) }
    }

    /** Which "NEW" feature badges are live (see `ui/newbadge`). */
    val newBadgeState: StateFlow<NewBadgeState> = settingsRepository.settings
        .map { NewBadgeState.from(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewBadgeState.EMPTY)

    /** Record the "was I here before this feature?" baseline on first run; a no-op after that. */
    fun ensureNewBadgeBaseline() = viewModelScope.launch {
        settingsRepository.ensureNewBadgeBaseline(com.rrajath.grove.BuildConfig.VERSION_CODE)
    }

    fun markNewFeaturesSeen(ids: List<String>) =
        viewModelScope.launch { settingsRepository.markNewFeaturesSeen(ids) }

    /** Debug menu (Settings › Developer): re-arm every NEW badge for testing. */
    fun resetNewBadges() = viewModelScope.launch { settingsRepository.resetNewBadges() }

    fun setVaultTreeUri(uri: String) =
        viewModelScope.launch { settingsRepository.setVaultTreeUri(uri) }

    fun setSyncMode(mode: SyncMode) =
        viewModelScope.launch { settingsRepository.setSyncMode(mode) }

    fun setPeriodicSyncMinutes(minutes: Int) =
        viewModelScope.launch { settingsRepository.setPeriodicSyncMinutes(minutes) }

    /**
     * Also the target of Settings > Notes' always-visible "Apply (re-indexes all
     * notebooks)" action, so it forces a rebuild even when [config] is unchanged
     * from the persisted value: the DataStore-diff collector in
     * [GroveApplication] that reindexes on an actual keyword-config change
     * wouldn't otherwise fire for a no-op write.
     */
    fun setTodoKeywords(config: String) = viewModelScope.launch {
        settingsRepository.setTodoKeywords(config)
        sync.clearAndResync("todo keywords applied")
    }

    fun setIgnoreList(value: String) = viewModelScope.launch {
        settingsRepository.setIgnoreList(value)
        sync.clearAndResync("ignore list applied")
    }

    fun setDefaultPriority(priority: Char?) =
        viewModelScope.launch { settingsRepository.setDefaultPriority(priority) }

    fun setAddIdToNewNotes(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAddIdToNewNotes(enabled) }

    fun setAddCreatedToNewNotes(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAddCreatedToNewNotes(enabled) }

    fun setCaptureNotification(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setCaptureNotification(enabled) }

    fun setShareTargetFile(fileName: String) =
        viewModelScope.launch { settingsRepository.setShareTargetFile(fileName.trim()) }

    fun setFetchSharedLinkTitles(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setFetchSharedLinkTitles(enabled) }

    /**
     * Route content shared into Grove (PRD §10) straight to the configured file:
     * a URL becomes a heading linking the fetched page title; long text becomes
     * an empty heading with the text as the body; short text becomes a heading.
     * Appended to the bottom of the target file (created if missing).
     */
    fun consumeSharedContent() {
        val payload = pendingShare.value ?: return
        pendingShare.value = null
        viewModelScope.launch { ShareIntake.consumeShare(app, payload) }
    }

    private suspend fun toast(
        message: String,
        duration: Int = android.widget.Toast.LENGTH_SHORT,
    ) = withContext(dispatchers.main) {
        android.widget.Toast.makeText(app, message, duration).show()
    }

    fun setOutlineToggle(toggle: OutlineToggle, enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setOutlineToggle(toggle, enabled) }

    fun setShowPreface(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setShowPreface(enabled) }

    fun setShowPropertyDrawers(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setShowPropertyDrawers(enabled) }

    fun setNotebookDisplayNameMode(mode: NotebookDisplayNameMode) =
        viewModelScope.launch { settingsRepository.setNotebookDisplayNameMode(mode) }

    fun setAutoArchiveDoneItems(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoArchiveDoneItems(enabled) }

    fun setRoamFeaturesEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setRoamFeaturesEnabled(enabled) }

    fun setRoamShowBacklinks(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setRoamShowBacklinks(enabled) }

    fun setRoamShowSuggestions(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setRoamShowSuggestions(enabled) }

    fun setRoamOpenWholeFile(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setRoamOpenWholeFile(enabled) }

    fun setDailiesDirectory(dir: String) =
        viewModelScope.launch { settingsRepository.setDailiesDirectory(dir) }

    fun setDailiesFilenamePattern(pattern: String) =
        viewModelScope.launch { settingsRepository.setDailiesFilenamePattern(pattern) }

    fun setDailiesHeaderTemplate(template: String) =
        viewModelScope.launch { settingsRepository.setDailiesHeaderTemplate(template) }

    // --- location picker (Settings § Notes' archive location, Settings § Widget's
    // quick-add target): same drill-down flow as RefileSheet, just picking a
    // default destination instead of moving an actual note. ---

    /** Which setting a confirmed [locationPicker] pick is saved to. */
    enum class LocationPickerPurpose { ARCHIVE, QUICK_ADD }

    private val _locationPicker = MutableStateFlow<RefileUiState?>(null)
    val locationPicker: StateFlow<RefileUiState?> = _locationPicker
    private var locationPickerPurpose = LocationPickerPurpose.ARCHIVE

    fun startArchiveLocationPick() = startLocationPick(LocationPickerPurpose.ARCHIVE)

    fun startQuickAddTargetPick() = startLocationPick(LocationPickerPurpose.QUICK_ADD)

    private fun startLocationPick(purpose: LocationPickerPurpose) {
        locationPickerPurpose = purpose
        _locationPicker.value = RefileUiState(sourceLine = -1)
        viewModelScope.launch {
            // From the Room index, not vault.notebooks() (which re-parses every
            // .org file); fileName + noteCount are columns on NotebookEntity.
            val notebooks = database.indexDao().notebooks()
                .sortedBy { it.fileName }
                .map { RefileNotebook(it.fileName, it.noteCount) }
                .toImmutableList()
            _locationPicker.value = _locationPicker.value?.copy(notebooks = notebooks)
        }
        viewModelScope.launch {
            val searchIndex = loadRefileSearchIndex(database.headingOutlines)
            _locationPicker.value = _locationPicker.value?.copy(searchIndex = searchIndex)
        }
    }

    /** A tap on a live-search result: same picker state as picking it in the drill-down (no exclusions here). */
    suspend fun locationSelectSearchResult(item: LinkSearchItem): Boolean {
        if (_locationPicker.value == null) return false
        val doc = vaultFlow.value?.open(item.fileName)
        if (doc == null) {
            toast("Couldn't open ${item.fileName.removeSuffix(".org")}")
            return false
        }
        val picker = _locationPicker.value ?: return false
        val next = picker.withSearchHit(item, doc, excluded = emptyList())
        if (next == null) {
            toast(app.getString(R.string.refile_search_stale_heading))
            return false
        }
        _locationPicker.value = next
        return true
    }

    fun locationPickNotebook(fileName: String) {
        viewModelScope.launch {
            val doc = vaultFlow.value?.open(fileName)
            if (doc == null) {
                toast("Couldn't open ${fileName.removeSuffix(".org")}")
                return@launch
            }
            _locationPicker.value =
                _locationPicker.value?.copy(pickedFile = fileName, pickedDoc = doc, path = persistentListOf())
        }
    }

    fun locationDrillInto(line: Int) {
        _locationPicker.value =
            _locationPicker.value?.let { it.copy(path = (it.path + line).toImmutableList()) }
    }

    fun locationBack() {
        _locationPicker.value = _locationPicker.value?.let {
            if (it.path.isNotEmpty()) it.copy(path = it.path.dropLast(1).toImmutableList())
            else it.copy(pickedFile = null, pickedDoc = null)
        }
    }

    fun locationCancel() {
        _locationPicker.value = null
    }

    fun locationConfirm() {
        val picker = _locationPicker.value ?: return
        val fileName = picker.pickedFile ?: return
        val headingPath = picker.path.mapNotNull { picker.pickedDoc?.headlineAtLine(it)?.title }
        _locationPicker.value = null
        viewModelScope.launch {
            when (locationPickerPurpose) {
                LocationPickerPurpose.ARCHIVE -> settingsRepository.setAutoArchiveLocation(fileName, headingPath)
                LocationPickerPurpose.QUICK_ADD -> settingsRepository.setQuickAddTarget(fileName, headingPath)
            }
        }
    }

    fun setRemindersEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setRemindersEnabled(enabled) }

    fun setMorningBriefEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setMorningBriefEnabled(enabled) }

    fun setNotifyUntimedTasks(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setNotifyUntimedTasks(enabled) }

    fun setDefaultReminderTime(time: java.time.LocalTime) =
        viewModelScope.launch { settingsRepository.setDefaultReminderTime(time) }

    fun setReminderLeadTime(leadTime: ReminderLeadTime) =
        viewModelScope.launch { settingsRepository.setReminderLeadTime(leadTime) }

    fun setAgendaSwipeLeftAction(action: AgendaSwipeAction) =
        viewModelScope.launch { settingsRepository.setAgendaSwipeLeftAction(action) }

    fun setAgendaSwipeRightAction(action: AgendaSwipeAction) =
        viewModelScope.launch { settingsRepository.setAgendaSwipeRightAction(action) }

    fun setAgendaWidgetTransparency(transparency: Float) =
        viewModelScope.launch { settingsRepository.setAgendaWidgetTransparency(transparency) }

    fun setAgendaWidgetDaysAhead(days: Int) =
        viewModelScope.launch { settingsRepository.setAgendaWidgetDaysAhead(days) }

    fun setAgendaWidgetShowFileName(show: Boolean) =
        viewModelScope.launch { settingsRepository.setAgendaWidgetShowFileName(show) }

    fun setAgendaWidgetShowTags(show: Boolean) =
        viewModelScope.launch { settingsRepository.setAgendaWidgetShowTags(show) }

    fun setAgendaWidgetShowPriority(show: Boolean) =
        viewModelScope.launch { settingsRepository.setAgendaWidgetShowPriority(show) }

    fun setAgendaWidgetFontSize(fontSize: FontSizePreference) =
        viewModelScope.launch { settingsRepository.setAgendaWidgetFontSize(fontSize) }

    /** Write the current preferences as a JSON document to the user-picked [uri]. */
    fun exportSettings(uri: android.net.Uri) = viewModelScope.launch {
        val current = settingsRepository.settings.first()
        val text = SettingsSerialization.export(current)
        val ok = withContext(dispatchers.io) {
            runCatching {
                app.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("no output stream")
            }.isSuccess
        }
        toast(if (ok) "Settings exported" else "Couldn't write settings file")
    }

    /** Read a JSON document from [uri] and apply the portable preferences within. */
    fun importSettings(uri: android.net.Uri) = viewModelScope.launch {
        val text = withContext(dispatchers.io) {
            runCatching {
                app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
        if (text == null) {
            toast("Couldn't read settings file")
            return@launch
        }
        val current = settingsRepository.settings.first()
        val imported = runCatching { SettingsSerialization.import(text, current) }.getOrNull()
        if (imported == null) {
            toast("Not a valid ${app.getString(R.string.app_name)} settings file")
            return@launch
        }
        settingsRepository.applyImported(imported)
        toast("Settings imported")
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as GroveApplication
                return AppViewModel(
                    app.settingsRepository,
                    app.searchRepository,
                    app.favoritesRepository,
                    app.database,
                    app.syncManager,
                    app.vault,
                    app.pendingShare,
                    app.dispatchers,
                    app,
                ) as T
            }
        }
    }
}
