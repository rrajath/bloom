package com.rrajath.grove.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.time.LocalTime

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Whether a [PinnedItem] pins a notebook file or a folder. */
enum class PinKind(val tag: Char) { FILE('f'), FOLDER('d') }

/**
 * One entry in the unified, chronologically-ordered pin list ([GroveSettings.pinnedItems]).
 *
 * Persisted as a single token `<tag><path>` with no separator: the first char is
 * [PinKind.tag] (`f`/`d`), the rest is the raw vault-relative path. A path can
 * contain `:` but never `;` (the list separator), so a fixed 1-char prefix is
 * unambiguous where a `d:`-style prefix would not be.
 */
data class PinnedItem(val kind: PinKind, val path: String) {
    fun encode(): String = "${kind.tag}$path"

    companion object {
        fun decode(token: String): PinnedItem? {
            if (token.length < 2) return null
            val kind = PinKind.entries.firstOrNull { it.tag == token[0] } ?: return null
            return PinnedItem(kind, token.substring(1))
        }

        /**
         * Fold the two legacy per-type pin lists into one unified list. The old
         * lists carry no cross-order, so this reproduces what the strip showed
         * before: folders first, then files.
         */
        fun fromLegacy(pinnedNotebooks: List<String>, pinnedFolders: List<String>): List<PinnedItem> =
            pinnedFolders.map { PinnedItem(PinKind.FOLDER, it) } +
                pinnedNotebooks.map { PinnedItem(PinKind.FILE, it) }
    }
}

data class GroveSettings(
    /** Light/Dark/System mode plus the chosen light and dark themes; resolve it for the screen. */
    val themeSelection: ThemeSelection = ThemeSelection(),
    /** When true, the launcher icon and drawer logo follow the resolved theme; otherwise they stay the default light mark. */
    val syncAppIconWithTheme: Boolean = false,
    /**
     * App-wide text-size baseline (Settings § Look and Feel). Scales every `sp`-sized
     * text in the app from a single lever. [readModeFontSize] / [editModeFontSize]
     * compound on top of this for note content only, so App=Large + Read=Large ≈
     * 1.12 × 1.12.
     */
    val appFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /** Read mode: scales the rendered note (Settings § Notes). App chrome is unaffected. */
    val readModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /** Edit mode: scales the editor text field (Settings § Notes). App chrome is unaffected. */
    val editModeFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    val defaultNoteOpenMode: NoteOpenMode = NoteOpenMode.READ,
    val onboardingDone: Boolean = false,
    /**
     * `BuildConfig.VERSION_CODE` last shown by the What's New modal; device-specific like
     * [onboardingDone], so deliberately left out of [com.rrajath.grove.settings.SettingsSerialization].
     * Keyed on versionCode (the numeric form of versionName, see CHANGELOG.md's "Versioning"
     * section) rather than the version string so the "seen since" comparison stays plain integer
     * ordering.
     */
    val lastSeenChangelogBuild: Int? = null,
    /**
     * `BuildConfig.VERSION_CODE` the "NEW" feature-badge framework was first seeded at
     * (see [SettingsRepository.ensureNewBadgeBaseline]). A feature badges only for
     * installs already on an *older* build than the feature shipped in — i.e. updates,
     * never fresh installs. Written once and never moved after; device-specific like
     * [lastSeenChangelogBuild], so deliberately left out of [SettingsSerialization].
     */
    val newBadgeBaseline: Int? = null,
    /**
     * Ids of `ui/newbadge` features the user has already reached; their badges no
     * longer show. Device-specific, like [newBadgeBaseline] — not exported.
     */
    val seenNewFeatures: Set<String> = emptySet(),
    /**
     * Exact-alarm access ("Alarms & reminders") as last seen by
     * [com.rrajath.grove.reminders.ExactAlarmAccessWatcher]; null until the first check.
     * A difference from the live value means every future reminder's alarm must be
     * re-armed. Device-specific, like [onboardingDone]: not exported.
     */
    val exactAlarmAccessLastKnown: Boolean? = null,
    /**
     * True once the one-time "Get reminders on time" dialog was answered (Allow or Not now);
     * it never shows again after that. Device-specific, like [onboardingDone]: not exported.
     */
    val exactAlarmPromptHandled: Boolean = false,
    /** Persisted SAF tree URI of the sync folder; null until the user picks one. */
    val vaultTreeUri: String? = null,
    val syncMode: SyncMode = SyncMode.ON_OPEN_CLOSE,
    val periodicSyncMinutes: Int = 30,
    /** Org TODO keyword config, `|` splits done-type ("TODO IN-PROGRESS | DONE CANCELLED"). */
    val todoKeywords: String = DEFAULT_TODO_KEYWORDS,
    /** Settings § Notebooks: newline-separated file/folder/pattern entries, never descended into or indexed. */
    val ignoreList: String = "",
    /**
     * Device-local: true once the one-time `.orgzlyignore` check has run, so it
     * never re-runs — regardless of what it found. Guards against re-importing
     * (or re-flagging empty/missing) after the user clears the list. Not
     * included in SettingsSerialization — same treatment as [onboardingDone].
     */
    val ignoreListImportCheckDone: Boolean = false,
    /**
     * Device-local: true only if the one-time `.orgzlyignore` check actually
     * found the file *and* it had at least one importable pattern, which was
     * then merged into [ignoreList]. False for both "no `.orgzlyignore` file at
     * all" and "found but empty" — see [ignoreListLegacyFileEmpty] for the
     * latter. Not included in SettingsSerialization — same treatment as
     * [onboardingDone].
     */
    val ignoreListImportedFromFile: Boolean = false,
    /**
     * Device-local: true if the one-time `.orgzlyignore` check found the file
     * but it had no importable patterns (blank/comment/negated lines only), so
     * [ignoreList] was left untouched. Distinguishes "found but empty" from "no
     * `.orgzlyignore` file at all" for the Settings § Notebooks messaging. Not
     * included in SettingsSerialization — same treatment as [onboardingDone].
     */
    val ignoreListLegacyFileEmpty: Boolean = false,
    /** Default priority for the metadata sheet; null = none. */
    val defaultPriority: Char? = null,
    val addIdToNewNotes: Boolean = false,
    val addCreatedToNewNotes: Boolean = true,
    /** Where the caret lands in a note created for immediate editing (outline "+" FAB). */
    val newNoteCursor: NewNoteCursor = NewNoteCursor.BODY,
    /**
     * Whether an editor writes the buffer to disk on its own after a 5s pause in
     * typing. Off means the file changes only when the user asks: the floppy icon,
     * or Save in the leave-with-unsaved-changes dialog.
     */
    val autoSaveNotes: Boolean = true,
    /** Per-notebook last-used note mode overrides: "file.org" → "read"/"edit". */
    val notebookModes: Map<String, String> = emptyMap(),
    /** Per-notebook monogram color overrides: "file.org" → palette key ("green"…). */
    val notebookColors: Map<String, String> = emptyMap(),
    /** Per-folder icon color overrides, keyed by vault-relative dir: "projects/clients" → palette key. */
    val folderColors: Map<String, String> = emptyMap(),
    val captureNotification: Boolean = false,
    /** .org file that receives content shared into Grove from other apps. */
    val shareTargetFile: String = DEFAULT_SHARE_TARGET,
    /** Fetch a shared link's page title over the network (plain HTTP, then a hidden-WebView fallback). */
    val fetchSharedLinkTitles: Boolean = true,
    // Outline display toggles (PRD §5.3)
    val showTagsInOutline: Boolean = true,
    val showTimestampsInOutline: Boolean = true,
    val showKeywordsInOutline: Boolean = true,
    /**
     * Single chronologically-ordered pin list across both notebooks and folders;
     * first = topmost. Order in this list is the display order of the Pinned
     * strip (append on pin, remove on unpin).
     */
    val pinnedItems: List<PinnedItem> = emptyList(),
    /** Read mode: show a collapsible section for file-level `#+` keyword lines. */
    val showPreface: Boolean = true,
    /** Read mode: show collapsible sections for `:PROPERTIES:` drawers. */
    val showPropertyDrawers: Boolean = true,
    /** Notebook list label: raw file name, or the `#+TITLE:` cached in the index. */
    val notebookDisplayNameMode: NotebookDisplayNameMode = NotebookDisplayNameMode.FILENAME,
    /** Notebook list: show the per-file icon tile at the start of each row. */
    val showNotebookFileIcons: Boolean = true,
    /**
     * Notebook list: collapse the folder tree to a flat file list. Every `.org`
     * file shows as its own row with its folder path as a subtitle; folder rows,
     * the drill-down view, and expand/collapse all are hidden.
     */
    val flattenNotebookFolders: Boolean = false,
    /** Notebook list sort key; pinned notebooks/folders keep their pin order regardless. */
    val notebookSortKey: NotebookSortKey = NotebookSortKey.ALPHABETICAL,
    /** Notebook list sort direction (pairs with [notebookSortKey]); true = A→Z / oldest→newest. */
    val notebookSortAscending: Boolean = true,
    /** Destination file of the most recent successful refile; null until one has happened. */
    val lastRefileFile: String? = null,
    /** '/'-separated heading path within [lastRefileFile]; empty = top level. */
    val lastRefileHeadingPath: String = "",
    /** Auto-refile a task to [autoArchiveFile] the moment it's marked with a done-type keyword. */
    val autoArchiveDoneItems: Boolean = false,
    /** Fallback archive destination used when a heading has no `ARCHIVE` property/keyword of its own. */
    val autoArchiveFile: String? = null,
    /** '/'-separated heading path within [autoArchiveFile]; empty = top level. */
    val autoArchiveHeadingPath: String = "",
    /** SCHEDULED/DEADLINE reminder notifications (see `reminders` package). */
    val remindersEnabled: Boolean = true,
    /** Daily digest ("You have X tasks due today") opt-in; requires [remindersEnabled] too. */
    val morningBriefEnabled: Boolean = true,
    /**
     * Fire a separate notification for every task/event whose SCHEDULED, DEADLINE
     * or active timestamp lands on a day with no time-of-day, at
     * [defaultReminderTime] on that day. Off by default (those rows only feed the
     * daily digest); requires [remindersEnabled] too.
     */
    val notifyUntimedTasks: Boolean = false,
    /** Time of day used for date-only SCHEDULED/DEADLINE/active stamps (no time-of-day). */
    val defaultReminderTime: LocalTime = LocalTime.of(9, 0),
    /** How far ahead of a timestamp's own time-of-day the "due" notification fires. */
    val reminderLeadTime: ReminderLeadTime = ReminderLeadTime.AT_TIME,
    /** Agenda row swipe-left/swipe-right gestures (Settings § Agenda). */
    val agendaSwipeLeftAction: AgendaSwipeAction = AgendaSwipeAction.MARK_DONE,
    val agendaSwipeRightAction: AgendaSwipeAction = AgendaSwipeAction.SET_SCHEDULED,
    // Agenda levers panel: sticky across visits, unlike the Today/Upcoming tab
    // itself. Tracked separately per tab so picking e.g. "Tag" grouping while on
    // Today doesn't silently re-bucket Upcoming (and vice versa) — each tab
    // remembers its own choice, with Upcoming's own default staying "Date".
    val agendaGroupingToday: AgendaGrouping = AgendaGrouping.DATE,
    val agendaGroupingUpcoming: AgendaGrouping = AgendaGrouping.DATE,
    val agendaStateFilterToday: AgendaStateFilter = AgendaStateFilter.Open,
    val agendaStateFilterUpcoming: AgendaStateFilter = AgendaStateFilter.Open,
    val agendaShowTags: Boolean = true,
    val agendaShowFile: Boolean = false,
    /**
     * Adds the SCHEDULED date chip and the bare-active-timestamp `●` chip
     * alongside whatever DEADLINE indicators a row already always shows.
     */
    val agendaShowTimestamps: Boolean = false,
    /** Agenda ledger home-screen widget background transparency: 0 = opaque, 1 = fully transparent. */
    val agendaWidgetTransparency: Float = 0f,
    /** How many days ahead the Agenda ledger widget shows, beyond Overdue/Today. */
    val agendaWidgetDaysAhead: Int = 14,
    /**
     * Widget-only appearance toggles, deliberately separate from [agendaShowTags] /
     * [agendaShowFile] (the in-app Agenda screen's own levers): changing how the
     * widget looks should never silently change the in-app screen too.
     */
    val agendaWidgetShowFileName: Boolean = false,
    val agendaWidgetShowTags: Boolean = true,
    val agendaWidgetShowPriority: Boolean = true,
    val agendaWidgetFontSize: FontSizePreference = FontSizePreference.MEDIUM,
    /**
     * Where the widget's quick-add composer saves a task. null = follow
     * [shareTargetFile], which is what the composer always used before this
     * lever existed, so an update never moves anyone's tasks on its own.
     */
    val quickAddTargetFile: String? = null,
    /** '/'-separated heading path within [quickAddTargetFile]; empty = bottom of the file. */
    val quickAddHeadingPath: String = "",
    /**
     * Notebooks screen: directory paths whose folder rows are expanded in the
     * inline tree. Device-specific view state, like [onboardingDone] — deliberately
     * left out of [com.rrajath.grove.settings.SettingsSerialization] and
     * [applyImported].
     */
    val expandedFolders: Set<String> = emptySet(),
    /** True once the first-open folder-expansion heuristic has run for this vault. */
    val notebooksTreeDefaultsApplied: Boolean = false,
    /**
     * Settings § Roam Features (experimental, off by default): master switch for
     * [roamShowBacklinks] / [roamShowSuggestions]. Both sub-toggles are inert
     * while this is off, regardless of their own stored value.
     */
    val roamFeaturesEnabled: Boolean = false,
    /** Roam Features sub-toggle: show the Backlinks bar in Outline/Read/Edit. */
    val roamShowBacklinks: Boolean = false,
    /** Roam Features sub-toggle: show file/heading link suggestions while typing in Edit mode. */
    val roamShowSuggestions: Boolean = false,
    /**
     * Roam Features sub-toggle: opening a notebook (Notebooks list, a `[[file:]]`
     * link, a search file match) whose file is under the whole-file line limit
     * skips the outline and opens the file as one note (`Routes.FILE`). On by
     * default (unlike the other Roam Features sub-toggles) once the master
     * switch is on.
     */
    val roamOpenWholeFile: Boolean = true,
    /** Roam Features § Dailies: vault-relative folder daily notes are created in; "" = vault root. */
    val dailiesDirectory: String = "",
    /** Roam Features § Dailies: filename pattern (FilenamePattern tokens), including the `.org` suffix. */
    val dailiesFilenamePattern: String = "%<%Y-%m-%d>.org",
    /** Roam Features § Dailies: expanded (PlaceholderExpander) into a new day file's initial content. */
    val dailiesHeaderTemplate: String = ":PROPERTIES:\n:ID: %(id)\n:END:\n#+title: %date\n%?",
) {
    /** Pinned notebook file names in pin order — derived view of [pinnedItems]. */
    val pinnedNotebooks: List<String>
        get() = pinnedItems.filter { it.kind == PinKind.FILE }.map { it.path }

    /** Pinned folder paths (vault-relative dirs) in pin order — derived view of [pinnedItems]. */
    val pinnedFolders: List<String>
        get() = pinnedItems.filter { it.kind == PinKind.FOLDER }.map { it.path }

    /** Backlinks are both enabled and visible; the backlinks scan runs only then. */
    val roamBacklinksActive: Boolean
        get() = roamFeaturesEnabled && roamShowBacklinks

    /** Typing suggestions are both enabled and visible; the suggestion index loads eagerly only then. */
    val roamSuggestionsActive: Boolean
        get() = roamFeaturesEnabled && roamShowSuggestions

    companion object {
        const val DEFAULT_TODO_KEYWORDS = "TODO IN-PROGRESS | DONE CANCELLED"
        const val DEFAULT_SHARE_TARGET = "inbox.org"
        val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(9, 0)
        const val DEFAULT_AGENDA_WIDGET_DAYS_AHEAD = 14
    }
}

class SettingsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) : SettingsSource {

    private object Keys {
        /** Retired single-theme key: read only to migrate into [themeMode]/[lightTheme]/[darkTheme]. */
        val legacyTheme = stringPreferencesKey("theme")
        val themeMode = stringPreferencesKey("theme_mode")
        val lightTheme = stringPreferencesKey("theme_light")
        val darkTheme = stringPreferencesKey("theme_dark")
        val syncAppIconWithTheme = booleanPreferencesKey("sync_app_icon_with_theme")

        /**
         * Retired: the single app-wide font-size lever (scaled the whole Material
         * typography). Read only to seed [readModeFontSize] / [editModeFontSize] the
         * first time; every write purges it.
         */
        val legacyFontSize = stringPreferencesKey("font_size")
        val appFontSize = stringPreferencesKey("app_font_size")
        val readModeFontSize = stringPreferencesKey("read_mode_font_size")
        val editModeFontSize = stringPreferencesKey("edit_mode_font_size")
        val noteOpenMode = stringPreferencesKey("note_open_mode")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastSeenChangelogBuild = intPreferencesKey("last_seen_changelog_build")
        val newBadgeBaseline = intPreferencesKey("new_badge_baseline")
        val seenNewFeatures = stringPreferencesKey("seen_new_features")
        val exactAlarmAccessLastKnown = booleanPreferencesKey("exact_alarm_access_last_known")
        val exactAlarmPromptHandled = booleanPreferencesKey("exact_alarm_prompt_handled")
        val vaultTreeUri = stringPreferencesKey("vault_tree_uri")
        val syncMode = stringPreferencesKey("sync_mode")
        val periodicSyncMinutes = intPreferencesKey("periodic_sync_minutes")
        val todoKeywords = stringPreferencesKey("todo_keywords")
        val ignoreList = stringPreferencesKey("ignore_list")
        val ignoreListImportCheckDone = booleanPreferencesKey("ignore_list_import_check_done")
        val ignoreListImportedFromFile = booleanPreferencesKey("ignore_list_imported_from_file")
        val ignoreListLegacyFileEmpty = booleanPreferencesKey("ignore_list_legacy_file_empty")
        val defaultPriority = stringPreferencesKey("default_priority")
        val addIdToNewNotes = booleanPreferencesKey("add_id_to_new_notes")
        val addCreatedToNewNotes = booleanPreferencesKey("add_created_to_new_notes")
        val newNoteCursor = stringPreferencesKey("new_note_cursor")
        val autoSaveNotes = booleanPreferencesKey("auto_save_notes")
        val notebookModes = stringPreferencesKey("notebook_modes")

        /**
         * Retired: per-notebook glyph picks from before monogram icons. Kept only so
         * notebook-style writes can purge any value a pre-monogram install left behind.
         */
        val notebookIcons = stringPreferencesKey("notebook_icons")
        val notebookColors = stringPreferencesKey("notebook_colors")
        val folderColors = stringPreferencesKey("folder_colors")
        val captureNotification = booleanPreferencesKey("capture_notification")
        val shareTargetFile = stringPreferencesKey("share_target_file")
        val fetchSharedLinkTitles = booleanPreferencesKey("fetch_shared_link_titles")
        val showTagsInOutline = booleanPreferencesKey("show_tags_in_outline")
        val showTimestampsInOutline = booleanPreferencesKey("show_timestamps_in_outline")
        val showKeywordsInOutline = booleanPreferencesKey("show_keywords_in_outline")
        /** Unified ordered pin list; `;`-joined `<tag><path>` tokens (see [PinnedItem]). */
        val pinnedItems = stringPreferencesKey("pinned_items")

        /**
         * Retired: the two separate per-type pin lists. Read only for the one-time
         * migration into [pinnedItems]; every write purges them.
         */
        val pinnedNotebooks = stringPreferencesKey("pinned_notebooks")
        val pinnedFolders = stringPreferencesKey("pinned_folders")
        val showPreface = booleanPreferencesKey("show_preface")
        val showPropertyDrawers = booleanPreferencesKey("show_property_drawers")
        val notebookDisplayNameMode = stringPreferencesKey("notebook_display_name_mode")
        val showNotebookFileIcons = booleanPreferencesKey("show_notebook_file_icons")
        val flattenNotebookFolders = booleanPreferencesKey("flatten_notebook_folders")
        val notebookSortKey = stringPreferencesKey("notebook_sort_key")
        val notebookSortAscending = booleanPreferencesKey("notebook_sort_ascending")
        val lastRefileFile = stringPreferencesKey("last_refile_file")
        val lastRefileHeadingPath = stringPreferencesKey("last_refile_heading_path")
        val autoArchiveDoneItems = booleanPreferencesKey("auto_archive_done_items")
        val autoArchiveFile = stringPreferencesKey("auto_archive_file")
        val autoArchiveHeadingPath = stringPreferencesKey("auto_archive_heading_path")
        val remindersEnabled = booleanPreferencesKey("reminders_enabled")
        val morningBriefEnabled = booleanPreferencesKey("morning_brief_enabled")
        val notifyUntimedTasks = booleanPreferencesKey("notify_untimed_tasks")
        val defaultReminderTime = stringPreferencesKey("default_reminder_time")
        val reminderLeadTime = stringPreferencesKey("reminder_lead_time")
        val agendaSwipeLeftAction = stringPreferencesKey("agenda_swipe_left_action")
        val agendaSwipeRightAction = stringPreferencesKey("agenda_swipe_right_action")
        val agendaGroupingToday = stringPreferencesKey("agenda_grouping_today")
        val agendaGroupingUpcoming = stringPreferencesKey("agenda_grouping_upcoming")
        val agendaStateFilterToday = stringPreferencesKey("agenda_state_filter_today")
        val agendaStateFilterUpcoming = stringPreferencesKey("agenda_state_filter_upcoming")
        val agendaShowTags = booleanPreferencesKey("agenda_show_tags")
        val agendaShowFile = booleanPreferencesKey("agenda_show_file")
        val agendaShowTimestamps = booleanPreferencesKey("agenda_show_timestamps")
        val agendaWidgetTransparency = floatPreferencesKey("agenda_widget_transparency")
        val agendaWidgetDaysAhead = intPreferencesKey("agenda_widget_days_ahead")
        val agendaWidgetShowFileName = booleanPreferencesKey("agenda_widget_show_file_name")
        val agendaWidgetShowTags = booleanPreferencesKey("agenda_widget_show_tags")
        val agendaWidgetShowPriority = booleanPreferencesKey("agenda_widget_show_priority")
        val agendaWidgetFontSize = stringPreferencesKey("agenda_widget_font_size")
        val quickAddTargetFile = stringPreferencesKey("quick_add_target_file")
        val quickAddHeadingPath = stringPreferencesKey("quick_add_heading_path")
        val expandedFolders = stringPreferencesKey("expanded_folders")
        val notebooksTreeDefaultsApplied = booleanPreferencesKey("notebooks_tree_defaults_applied")
        val roamFeaturesEnabled = booleanPreferencesKey("roam_features_enabled")
        val roamShowBacklinks = booleanPreferencesKey("roam_show_backlinks")
        val roamShowSuggestions = booleanPreferencesKey("roam_show_suggestions")
        val roamOpenWholeFile = booleanPreferencesKey("roam_open_whole_file")
        val dailiesDirectory = stringPreferencesKey("dailies_directory")
        val dailiesFilenamePattern = stringPreferencesKey("dailies_filename_pattern")
        val dailiesHeaderTemplate = stringPreferencesKey("dailies_header_template")
    }

    /**
     * Shared, hot: one `GroveSettings` build per DataStore write, fanned out to all
     * ~40 collectors, instead of each collector re-running the (non-trivial) mapper.
     * `replay = 1` with no seed value preserves the "nothing until the first read"
     * semantics that [com.rrajath.grove.ui.AppViewModel] gates first load on.
     */
    override val settings: Flow<GroveSettings> = context.settingsDataStore.data.map { prefs ->
        GroveSettings(
            themeSelection = readThemeSelection(prefs),
            syncAppIconWithTheme = prefs[Keys.syncAppIconWithTheme] ?: false,
            appFontSize = FontSizePreference.fromStorage(prefs[Keys.appFontSize]),
            readModeFontSize = FontSizePreference.fromStorage(
                prefs[Keys.readModeFontSize] ?: prefs[Keys.legacyFontSize]
            ),
            editModeFontSize = FontSizePreference.fromStorage(
                prefs[Keys.editModeFontSize] ?: prefs[Keys.legacyFontSize]
            ),
            defaultNoteOpenMode = NoteOpenMode.fromStorage(prefs[Keys.noteOpenMode]),
            onboardingDone = prefs[Keys.onboardingDone] ?: false,
            lastSeenChangelogBuild = prefs[Keys.lastSeenChangelogBuild],
            newBadgeBaseline = prefs[Keys.newBadgeBaseline],
            seenNewFeatures = decodeStringSet(prefs[Keys.seenNewFeatures]),
            exactAlarmAccessLastKnown = prefs[Keys.exactAlarmAccessLastKnown],
            exactAlarmPromptHandled = prefs[Keys.exactAlarmPromptHandled] ?: false,
            vaultTreeUri = prefs[Keys.vaultTreeUri],
            syncMode = SyncMode.fromStorage(prefs[Keys.syncMode]),
            periodicSyncMinutes = prefs[Keys.periodicSyncMinutes] ?: 30,
            todoKeywords = prefs[Keys.todoKeywords] ?: GroveSettings.DEFAULT_TODO_KEYWORDS,
            ignoreList = prefs[Keys.ignoreList] ?: "",
            ignoreListImportCheckDone = prefs[Keys.ignoreListImportCheckDone] ?: false,
            ignoreListImportedFromFile = prefs[Keys.ignoreListImportedFromFile] ?: false,
            ignoreListLegacyFileEmpty = prefs[Keys.ignoreListLegacyFileEmpty] ?: false,
            defaultPriority = prefs[Keys.defaultPriority]?.firstOrNull(),
            addIdToNewNotes = prefs[Keys.addIdToNewNotes] ?: false,
            addCreatedToNewNotes = prefs[Keys.addCreatedToNewNotes] ?: true,
            newNoteCursor = NewNoteCursor.fromStorage(prefs[Keys.newNoteCursor]),
            autoSaveNotes = prefs[Keys.autoSaveNotes] ?: true,
            notebookModes = decodeModes(prefs[Keys.notebookModes]),
            notebookColors = decodeModes(prefs[Keys.notebookColors]),
            folderColors = decodeModes(prefs[Keys.folderColors]),
            captureNotification = prefs[Keys.captureNotification] ?: false,
            shareTargetFile = prefs[Keys.shareTargetFile] ?: GroveSettings.DEFAULT_SHARE_TARGET,
            fetchSharedLinkTitles = prefs[Keys.fetchSharedLinkTitles] ?: true,
            showTagsInOutline = prefs[Keys.showTagsInOutline] ?: true,
            showTimestampsInOutline = prefs[Keys.showTimestampsInOutline] ?: true,
            showKeywordsInOutline = prefs[Keys.showKeywordsInOutline] ?: true,
            pinnedItems = readPinnedItems(prefs),
            showPreface = prefs[Keys.showPreface] ?: true,
            showPropertyDrawers = prefs[Keys.showPropertyDrawers] ?: true,
            notebookDisplayNameMode = NotebookDisplayNameMode.fromStorage(prefs[Keys.notebookDisplayNameMode]),
            showNotebookFileIcons = prefs[Keys.showNotebookFileIcons] ?: true,
            flattenNotebookFolders = prefs[Keys.flattenNotebookFolders] ?: false,
            notebookSortKey = NotebookSortKey.fromStorage(prefs[Keys.notebookSortKey]),
            notebookSortAscending = prefs[Keys.notebookSortAscending] ?: true,
            lastRefileFile = prefs[Keys.lastRefileFile],
            lastRefileHeadingPath = prefs[Keys.lastRefileHeadingPath] ?: "",
            autoArchiveDoneItems = prefs[Keys.autoArchiveDoneItems] ?: false,
            autoArchiveFile = prefs[Keys.autoArchiveFile],
            autoArchiveHeadingPath = prefs[Keys.autoArchiveHeadingPath] ?: "",
            remindersEnabled = prefs[Keys.remindersEnabled] ?: true,
            morningBriefEnabled = prefs[Keys.morningBriefEnabled] ?: true,
            notifyUntimedTasks = prefs[Keys.notifyUntimedTasks] ?: false,
            defaultReminderTime = decodeTime(prefs[Keys.defaultReminderTime]),
            reminderLeadTime = ReminderLeadTime.fromStorage(prefs[Keys.reminderLeadTime]),
            agendaSwipeLeftAction = AgendaSwipeAction.fromStorage(
                prefs[Keys.agendaSwipeLeftAction], AgendaSwipeAction.MARK_DONE
            ),
            agendaSwipeRightAction = AgendaSwipeAction.fromStorage(
                prefs[Keys.agendaSwipeRightAction], AgendaSwipeAction.SET_SCHEDULED
            ),
            agendaGroupingToday = AgendaGrouping.fromStorage(prefs[Keys.agendaGroupingToday]),
            agendaGroupingUpcoming = AgendaGrouping.fromStorage(prefs[Keys.agendaGroupingUpcoming]),
            agendaStateFilterToday = AgendaStateFilter.fromStorage(prefs[Keys.agendaStateFilterToday]),
            agendaStateFilterUpcoming = AgendaStateFilter.fromStorage(prefs[Keys.agendaStateFilterUpcoming]),
            agendaShowTags = prefs[Keys.agendaShowTags] ?: true,
            agendaShowFile = prefs[Keys.agendaShowFile] ?: false,
            agendaShowTimestamps = prefs[Keys.agendaShowTimestamps] ?: false,
            agendaWidgetTransparency = prefs[Keys.agendaWidgetTransparency] ?: 0f,
            agendaWidgetDaysAhead = prefs[Keys.agendaWidgetDaysAhead] ?: GroveSettings.DEFAULT_AGENDA_WIDGET_DAYS_AHEAD,
            agendaWidgetShowFileName = prefs[Keys.agendaWidgetShowFileName] ?: false,
            agendaWidgetShowTags = prefs[Keys.agendaWidgetShowTags] ?: true,
            agendaWidgetShowPriority = prefs[Keys.agendaWidgetShowPriority] ?: true,
            agendaWidgetFontSize = FontSizePreference.fromStorage(prefs[Keys.agendaWidgetFontSize]),
            quickAddTargetFile = prefs[Keys.quickAddTargetFile],
            quickAddHeadingPath = prefs[Keys.quickAddHeadingPath] ?: "",
            expandedFolders = decodeFolderSet(prefs[Keys.expandedFolders]),
            notebooksTreeDefaultsApplied = prefs[Keys.notebooksTreeDefaultsApplied] ?: false,
            // Roam Features are debug-only for now: a release build reads the master
            // switch as off (the stored value is left alone), which hides every Roam
            // surface keyed off it -- Dailies, backlinks, suggestions, roam templates.
            roamFeaturesEnabled = com.rrajath.grove.BuildConfig.DEBUG && (prefs[Keys.roamFeaturesEnabled] ?: false),
            roamShowBacklinks = prefs[Keys.roamShowBacklinks] ?: false,
            roamShowSuggestions = prefs[Keys.roamShowSuggestions] ?: false,
            roamOpenWholeFile = prefs[Keys.roamOpenWholeFile] ?: true,
            dailiesDirectory = prefs[Keys.dailiesDirectory] ?: "",
            dailiesFilenamePattern = prefs[Keys.dailiesFilenamePattern] ?: GroveSettings().dailiesFilenamePattern,
            dailiesHeaderTemplate = prefs[Keys.dailiesHeaderTemplate] ?: GroveSettings().dailiesHeaderTemplate,
        )
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** `;`-joined directory paths; `;` can't appear in a path, `/` is the separator. */
    private fun decodeFolderSet(raw: String?): Set<String> =
        raw?.split(';')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** `;`-joined token set (feature ids); tokens never contain `;`. */
    private fun decodeStringSet(raw: String?): Set<String> =
        raw?.split(';')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun decodeTime(raw: String?): LocalTime =
        raw?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: GroveSettings.DEFAULT_REMINDER_TIME

    private fun encodeTime(time: LocalTime): String = time.toString()

    /** Legacy per-type pin list decode; used only by [readPinnedItems]'s migration path. */
    private fun decodePinnedList(raw: String?): List<String> =
        raw?.split(';')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun decodePinnedItems(raw: String?): List<PinnedItem> =
        raw?.split(';')?.mapNotNull { if (it.isEmpty()) null else PinnedItem.decode(it) } ?: emptyList()

    private fun encodePinnedItems(items: List<PinnedItem>): String =
        items.joinToString(";") { it.encode() }

    /**
     * The unified pin list, migrating the two legacy keys on the fly: if
     * [Keys.pinnedItems] has never been written, fold [Keys.pinnedNotebooks] +
     * [Keys.pinnedFolders] into one (folders first, matching the old strip order).
     */
    private fun readPinnedItems(prefs: Preferences): List<PinnedItem> {
        prefs[Keys.pinnedItems]?.let { return decodePinnedItems(it) }
        return PinnedItem.fromLegacy(
            decodePinnedList(prefs[Keys.pinnedNotebooks]),
            decodePinnedList(prefs[Keys.pinnedFolders]),
        )
    }

    /** Persist [items] as the sole pin list, purging the retired per-type keys. */
    private fun writePinnedItems(prefs: MutablePreferences, items: List<PinnedItem>) {
        prefs[Keys.pinnedItems] = encodePinnedItems(items)
        prefs.remove(Keys.pinnedNotebooks)
        prefs.remove(Keys.pinnedFolders)
    }

    private fun decodeModes(raw: String?): Map<String, String> =
        raw?.split(';')
            ?.mapNotNull { entry ->
                val eq = entry.lastIndexOf('=')
                if (eq <= 0) null else entry.substring(0, eq) to entry.substring(eq + 1)
            }
            ?.toMap()
            ?: emptyMap()

    private fun encodeModes(map: Map<String, String>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Bulk-write an imported settings document in one transaction. Leaves the
     * device-specific vault URI, onboarding flag, and Notebooks tree view state
     * ([expandedFolders] / [notebooksTreeDefaultsApplied]) alone: those don't
     * travel with an export (see [SettingsSerialization]).
     */
    suspend fun applyImported(s: GroveSettings) {
        context.settingsDataStore.edit { p ->
            writeThemeSelection(p, s.themeSelection)
            p[Keys.syncAppIconWithTheme] = s.syncAppIconWithTheme
            p[Keys.appFontSize] = s.appFontSize.storageKey
            p[Keys.readModeFontSize] = s.readModeFontSize.storageKey
            p[Keys.editModeFontSize] = s.editModeFontSize.storageKey
            p.remove(Keys.legacyFontSize)
            p[Keys.noteOpenMode] = s.defaultNoteOpenMode.storageKey
            p[Keys.syncMode] = s.syncMode.storageKey
            p[Keys.periodicSyncMinutes] = s.periodicSyncMinutes
            p[Keys.todoKeywords] = s.todoKeywords
            p[Keys.ignoreList] = s.ignoreList
            if (s.defaultPriority == null) p.remove(Keys.defaultPriority)
            else p[Keys.defaultPriority] = s.defaultPriority.toString()
            p[Keys.addIdToNewNotes] = s.addIdToNewNotes
            p[Keys.addCreatedToNewNotes] = s.addCreatedToNewNotes
            p[Keys.newNoteCursor] = s.newNoteCursor.storageKey
            p[Keys.autoSaveNotes] = s.autoSaveNotes
            p[Keys.notebookModes] = encodeModes(s.notebookModes)
            p[Keys.notebookColors] = encodeModes(s.notebookColors)
            p[Keys.folderColors] = encodeModes(s.folderColors)
            p.remove(Keys.notebookIcons)
            p[Keys.captureNotification] = s.captureNotification
            p[Keys.shareTargetFile] = s.shareTargetFile
            p[Keys.fetchSharedLinkTitles] = s.fetchSharedLinkTitles
            p[Keys.showTagsInOutline] = s.showTagsInOutline
            p[Keys.showTimestampsInOutline] = s.showTimestampsInOutline
            p[Keys.showKeywordsInOutline] = s.showKeywordsInOutline
            writePinnedItems(p, s.pinnedItems)
            p[Keys.showPreface] = s.showPreface
            p[Keys.showPropertyDrawers] = s.showPropertyDrawers
            p[Keys.notebookDisplayNameMode] = s.notebookDisplayNameMode.storageKey
            p[Keys.showNotebookFileIcons] = s.showNotebookFileIcons
            p[Keys.flattenNotebookFolders] = s.flattenNotebookFolders
            p[Keys.remindersEnabled] = s.remindersEnabled
            p[Keys.morningBriefEnabled] = s.morningBriefEnabled
            p[Keys.notifyUntimedTasks] = s.notifyUntimedTasks
            p[Keys.defaultReminderTime] = encodeTime(s.defaultReminderTime)
            p[Keys.reminderLeadTime] = s.reminderLeadTime.storageKey
            p[Keys.agendaSwipeLeftAction] = s.agendaSwipeLeftAction.storageKey
            p[Keys.agendaSwipeRightAction] = s.agendaSwipeRightAction.storageKey
            p[Keys.agendaGroupingToday] = s.agendaGroupingToday.storageKey
            p[Keys.agendaGroupingUpcoming] = s.agendaGroupingUpcoming.storageKey
            p[Keys.agendaStateFilterToday] = s.agendaStateFilterToday.storageKey
            p[Keys.agendaStateFilterUpcoming] = s.agendaStateFilterUpcoming.storageKey
            p[Keys.agendaShowTags] = s.agendaShowTags
            p[Keys.agendaShowFile] = s.agendaShowFile
            p[Keys.agendaShowTimestamps] = s.agendaShowTimestamps
            p[Keys.agendaWidgetTransparency] = s.agendaWidgetTransparency
            p[Keys.agendaWidgetDaysAhead] = s.agendaWidgetDaysAhead
            p[Keys.agendaWidgetShowFileName] = s.agendaWidgetShowFileName
            p[Keys.agendaWidgetShowTags] = s.agendaWidgetShowTags
            p[Keys.agendaWidgetShowPriority] = s.agendaWidgetShowPriority
            p[Keys.agendaWidgetFontSize] = s.agendaWidgetFontSize.storageKey
            if (s.quickAddTargetFile == null) p.remove(Keys.quickAddTargetFile)
            else p[Keys.quickAddTargetFile] = s.quickAddTargetFile
            p[Keys.quickAddHeadingPath] = s.quickAddHeadingPath
            p[Keys.autoArchiveDoneItems] = s.autoArchiveDoneItems
            if (s.autoArchiveFile == null) p.remove(Keys.autoArchiveFile)
            else p[Keys.autoArchiveFile] = s.autoArchiveFile
            p[Keys.autoArchiveHeadingPath] = s.autoArchiveHeadingPath
            if (s.lastRefileFile == null) p.remove(Keys.lastRefileFile)
            else p[Keys.lastRefileFile] = s.lastRefileFile
            p[Keys.lastRefileHeadingPath] = s.lastRefileHeadingPath
            p[Keys.roamFeaturesEnabled] = s.roamFeaturesEnabled
            p[Keys.roamShowBacklinks] = s.roamShowBacklinks
            p[Keys.roamShowSuggestions] = s.roamShowSuggestions
            p[Keys.roamOpenWholeFile] = s.roamOpenWholeFile
            p[Keys.dailiesDirectory] = s.dailiesDirectory
            p[Keys.dailiesFilenamePattern] = s.dailiesFilenamePattern
            p[Keys.dailiesHeaderTemplate] = s.dailiesHeaderTemplate
        }
    }

    /**
     * Always writes all three theme keys: a migrated install has none of them
     * yet, and writing one slot alone would leave the legacy key in charge.
     */
    suspend fun updateThemeSelection(transform: (ThemeSelection) -> ThemeSelection) {
        context.settingsDataStore.edit { writeThemeSelection(it, transform(readThemeSelection(it))) }
    }

    private fun readThemeSelection(prefs: Preferences): ThemeSelection =
        ThemeSelection.fromStorage(
            mode = prefs[Keys.themeMode],
            light = prefs[Keys.lightTheme],
            dark = prefs[Keys.darkTheme],
            legacyTheme = prefs[Keys.legacyTheme],
            onboardingDone = prefs[Keys.onboardingDone] ?: false,
        )

    private fun writeThemeSelection(p: MutablePreferences, selection: ThemeSelection) {
        val s = selection.sanitized()
        p[Keys.themeMode] = s.mode.storageKey
        p[Keys.lightTheme] = s.light.storageKey
        p[Keys.darkTheme] = s.dark.storageKey
        // Keys.legacyTheme is left as-is: themeMode wins once present, and a
        // downgraded install still finds the theme it last had.
    }

    suspend fun setSyncAppIconWithTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.syncAppIconWithTheme] = enabled }
    }

    suspend fun setAppFontSize(fontSize: FontSizePreference) {
        context.settingsDataStore.edit { it[Keys.appFontSize] = fontSize.storageKey }
    }

    suspend fun setReadModeFontSize(fontSize: FontSizePreference) {
        context.settingsDataStore.edit { it[Keys.readModeFontSize] = fontSize.storageKey }
    }

    suspend fun setEditModeFontSize(fontSize: FontSizePreference) {
        context.settingsDataStore.edit { it[Keys.editModeFontSize] = fontSize.storageKey }
    }

    suspend fun setDefaultNoteOpenMode(mode: NoteOpenMode) {
        context.settingsDataStore.edit { it[Keys.noteOpenMode] = mode.storageKey }
    }

    suspend fun setNewNoteCursor(cursor: NewNoteCursor) {
        context.settingsDataStore.edit { it[Keys.newNoteCursor] = cursor.storageKey }
    }

    suspend fun setAutoSaveNotes(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.autoSaveNotes] = enabled }
    }

    /**
     * [seenChangelogBuild], when given, is stamped in the *same* transaction as
     * [onboardingDone]. Onboarding completion is the one moment we want both flags
     * to flip together: the What's New check keys off `onboardingDone` becoming
     * true, and a fresh install has nothing "new" to report, so writing them
     * separately leaves a window where the check sees `onboardingDone == true` but
     * `lastSeenChangelogBuild == null` and wrongly pops the modal.
     */
    suspend fun setOnboardingDone(done: Boolean, seenChangelogBuild: Int? = null) {
        context.settingsDataStore.edit {
            // Pin the fresh-install default before flipping the flag: an unset
            // theme on an onboarded install reads as the legacy default instead.
            if (done && it[Keys.themeMode] == null && it[Keys.legacyTheme] == null) {
                writeThemeSelection(it, ThemeSelection.defaultFor(onboardingDone = false))
            }
            it[Keys.onboardingDone] = done
            if (seenChangelogBuild != null) it[Keys.lastSeenChangelogBuild] = seenChangelogBuild
        }
    }

    suspend fun setLastSeenChangelogBuild(build: Int) {
        context.settingsDataStore.edit { it[Keys.lastSeenChangelogBuild] = build }
    }

    suspend fun setExactAlarmAccessLastKnown(granted: Boolean) {
        context.settingsDataStore.edit { it[Keys.exactAlarmAccessLastKnown] = granted }
    }

    suspend fun markExactAlarmPromptHandled() {
        context.settingsDataStore.edit { it[Keys.exactAlarmPromptHandled] = true }
    }

    /**
     * Seed [GroveSettings.newBadgeBaseline] the first time the "NEW" badge framework
     * runs, then never touch it again. A fresh install has already stamped
     * [Keys.lastSeenChangelogBuild] with [currentBuild] during onboarding, so the
     * baseline lands on the current build and nothing is badged; an updating install
     * carries its older last-seen build, so features newer than that show a badge.
     * (A rare pre-[Keys.lastSeenChangelogBuild] install seeds to [currentBuild] and
     * misses this one release's badges — the same tradeoff the What's New check makes.)
     */
    suspend fun ensureNewBadgeBaseline(currentBuild: Int) {
        context.settingsDataStore.edit { prefs ->
            if (prefs[Keys.newBadgeBaseline] == null) {
                prefs[Keys.newBadgeBaseline] = prefs[Keys.lastSeenChangelogBuild] ?: currentBuild
            }
        }
    }

    /** Retire the badges for [ids] — the user has reached those features. */
    suspend fun markNewFeaturesSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val merged = decodeStringSet(prefs[Keys.seenNewFeatures]) + ids
            prefs[Keys.seenNewFeatures] = merged.joinToString(";")
        }
    }

    /** Debug only: re-arm every NEW badge (clear the seen set, drop the baseline to 0). */
    suspend fun resetNewBadges() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.seenNewFeatures)
            prefs[Keys.newBadgeBaseline] = 0
        }
    }

    suspend fun setVaultTreeUri(uri: String) {
        context.settingsDataStore.edit { it[Keys.vaultTreeUri] = uri }
    }

    suspend fun setSyncMode(mode: SyncMode) {
        context.settingsDataStore.edit { it[Keys.syncMode] = mode.storageKey }
    }

    suspend fun setPeriodicSyncMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.periodicSyncMinutes] = minutes }
    }

    suspend fun setTodoKeywords(config: String) {
        context.settingsDataStore.edit { it[Keys.todoKeywords] = config }
    }

    suspend fun setIgnoreList(value: String) {
        context.settingsDataStore.edit { it[Keys.ignoreList] = value }
    }

    suspend fun markIgnoreListImportCheckDone() {
        context.settingsDataStore.edit { it[Keys.ignoreListImportCheckDone] = true }
    }

    /** Debug only (Settings › Developer): flip the one-time import-check flag back to re-trigger it. */
    suspend fun setIgnoreListImportCheckDone(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.ignoreListImportCheckDone] = value }
    }

    suspend fun setIgnoreListImportedFromFile(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.ignoreListImportedFromFile] = value }
    }

    suspend fun setIgnoreListLegacyFileEmpty(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.ignoreListLegacyFileEmpty] = value }
    }

    suspend fun setDefaultPriority(priority: Char?) {
        context.settingsDataStore.edit {
            if (priority == null) it.remove(Keys.defaultPriority)
            else it[Keys.defaultPriority] = priority.toString()
        }
    }

    suspend fun setAddIdToNewNotes(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.addIdToNewNotes] = enabled }
    }

    suspend fun setAddCreatedToNewNotes(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.addCreatedToNewNotes] = enabled }
    }

    suspend fun setCaptureNotification(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.captureNotification] = enabled }
    }

    suspend fun setShareTargetFile(fileName: String) {
        context.settingsDataStore.edit { it[Keys.shareTargetFile] = fileName }
    }

    suspend fun setFetchSharedLinkTitles(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.fetchSharedLinkTitles] = enabled }
    }

    suspend fun setOutlineToggle(key: OutlineToggle, enabled: Boolean) {
        context.settingsDataStore.edit {
            it[
                when (key) {
                    OutlineToggle.TAGS -> Keys.showTagsInOutline
                    OutlineToggle.TIMESTAMPS -> Keys.showTimestampsInOutline
                    OutlineToggle.KEYWORDS -> Keys.showKeywordsInOutline
                }
            ] = enabled
        }
    }

    suspend fun setShowPreface(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showPreface] = enabled }
    }

    suspend fun setShowPropertyDrawers(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showPropertyDrawers] = enabled }
    }

    suspend fun setNotebookDisplayNameMode(mode: NotebookDisplayNameMode) {
        context.settingsDataStore.edit { it[Keys.notebookDisplayNameMode] = mode.storageKey }
    }

    suspend fun setShowNotebookFileIcons(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showNotebookFileIcons] = enabled }
    }

    suspend fun setFlattenNotebookFolders(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.flattenNotebookFolders] = enabled }
    }

    suspend fun setNotebookSortKey(key: NotebookSortKey) {
        context.settingsDataStore.edit { it[Keys.notebookSortKey] = key.storageKey }
    }

    suspend fun setNotebookSortAscending(ascending: Boolean) {
        context.settingsDataStore.edit { it[Keys.notebookSortAscending] = ascending }
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.remindersEnabled] = enabled }
    }

    suspend fun setMorningBriefEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.morningBriefEnabled] = enabled }
    }

    suspend fun setNotifyUntimedTasks(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.notifyUntimedTasks] = enabled }
    }

    suspend fun setDefaultReminderTime(time: LocalTime) {
        context.settingsDataStore.edit { it[Keys.defaultReminderTime] = encodeTime(time) }
    }

    suspend fun setReminderLeadTime(leadTime: ReminderLeadTime) {
        context.settingsDataStore.edit { it[Keys.reminderLeadTime] = leadTime.storageKey }
    }

    suspend fun setAgendaSwipeLeftAction(action: AgendaSwipeAction) {
        context.settingsDataStore.edit { it[Keys.agendaSwipeLeftAction] = action.storageKey }
    }

    suspend fun setAgendaSwipeRightAction(action: AgendaSwipeAction) {
        context.settingsDataStore.edit { it[Keys.agendaSwipeRightAction] = action.storageKey }
    }

    suspend fun setAgendaGroupingToday(grouping: AgendaGrouping) {
        context.settingsDataStore.edit { it[Keys.agendaGroupingToday] = grouping.storageKey }
    }

    suspend fun setAgendaGroupingUpcoming(grouping: AgendaGrouping) {
        context.settingsDataStore.edit { it[Keys.agendaGroupingUpcoming] = grouping.storageKey }
    }

    suspend fun setAgendaStateFilterToday(filter: AgendaStateFilter) {
        context.settingsDataStore.edit { it[Keys.agendaStateFilterToday] = filter.storageKey }
    }

    suspend fun setAgendaStateFilterUpcoming(filter: AgendaStateFilter) {
        context.settingsDataStore.edit { it[Keys.agendaStateFilterUpcoming] = filter.storageKey }
    }

    suspend fun setAgendaShowTags(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.agendaShowTags] = show }
    }

    suspend fun setAgendaShowFile(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.agendaShowFile] = show }
    }

    suspend fun setAgendaShowTimestamps(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.agendaShowTimestamps] = show }
    }

    suspend fun setAgendaWidgetTransparency(transparency: Float) {
        context.settingsDataStore.edit { it[Keys.agendaWidgetTransparency] = transparency.coerceIn(0f, 1f) }
    }

    suspend fun setAgendaWidgetDaysAhead(days: Int) {
        context.settingsDataStore.edit { it[Keys.agendaWidgetDaysAhead] = days.coerceAtLeast(2) }
    }

    suspend fun setAgendaWidgetShowFileName(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.agendaWidgetShowFileName] = show }
    }

    suspend fun setAgendaWidgetShowTags(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.agendaWidgetShowTags] = show }
    }

    suspend fun setAgendaWidgetShowPriority(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.agendaWidgetShowPriority] = show }
    }

    suspend fun setAgendaWidgetFontSize(fontSize: FontSizePreference) {
        context.settingsDataStore.edit { it[Keys.agendaWidgetFontSize] = fontSize.storageKey }
    }

    suspend fun setQuickAddTarget(fileName: String, headingPath: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.quickAddTargetFile] = fileName
            prefs[Keys.quickAddHeadingPath] = headingPath.joinToString("/")
        }
    }

    /** Replace the whole expanded-folder set (expand-all / collapse-all). */
    suspend fun setExpandedFolders(dirs: Set<String>) {
        context.settingsDataStore.edit { it[Keys.expandedFolders] = dirs.joinToString(";") }
    }

    /** First-open heuristic result: seed the expanded set and mark the pass done, in one write. */
    suspend fun applyNotebooksTreeDefaults(expanded: Set<String>) {
        context.settingsDataStore.edit {
            it[Keys.expandedFolders] = expanded.joinToString(";")
            it[Keys.notebooksTreeDefaultsApplied] = true
        }
    }

    suspend fun setLastRefileTarget(fileName: String, headingPath: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.lastRefileFile] = fileName
            prefs[Keys.lastRefileHeadingPath] = headingPath.joinToString("/")
        }
    }

    suspend fun setRoamFeaturesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.roamFeaturesEnabled] = enabled }
    }

    suspend fun setRoamShowBacklinks(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.roamShowBacklinks] = enabled }
    }

    suspend fun setRoamShowSuggestions(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.roamShowSuggestions] = enabled }
    }

    suspend fun setRoamOpenWholeFile(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.roamOpenWholeFile] = enabled }
    }

    suspend fun setDailiesDirectory(dir: String) {
        context.settingsDataStore.edit { it[Keys.dailiesDirectory] = dir }
    }

    suspend fun setDailiesFilenamePattern(pattern: String) {
        context.settingsDataStore.edit { it[Keys.dailiesFilenamePattern] = pattern }
    }

    suspend fun setDailiesHeaderTemplate(template: String) {
        context.settingsDataStore.edit { it[Keys.dailiesHeaderTemplate] = template }
    }

    suspend fun setAutoArchiveDoneItems(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.autoArchiveDoneItems] = enabled }
    }

    suspend fun setAutoArchiveLocation(fileName: String, headingPath: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.autoArchiveFile] = fileName
            prefs[Keys.autoArchiveHeadingPath] = headingPath.joinToString("/")
        }
    }

    suspend fun setNotebookColor(fileName: String, colorKey: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeModes(prefs[Keys.notebookColors]).toMutableMap()
            current[fileName] = colorKey
            prefs[Keys.notebookColors] = encodeModes(current)
            // Monogram icons replaced the glyph picker; drop any leftover glyph pick.
            prefs.remove(Keys.notebookIcons)
        }
    }

    private suspend fun editPinnedItems(mutate: (MutableList<PinnedItem>) -> Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = readPinnedItems(prefs).toMutableList()
            if (mutate(current)) writePinnedItems(prefs, current)
        }
    }

    suspend fun pinNotebook(fileName: String) = editPinnedItems { items ->
        val entry = PinnedItem(PinKind.FILE, fileName)
        if (entry in items) false else items.add(entry)
    }

    suspend fun unpinNotebook(fileName: String) = editPinnedItems { items ->
        items.remove(PinnedItem(PinKind.FILE, fileName))
    }

    /**
     * Swap two pins' positions in the strip order. Callers pass visible neighbours,
     * so stale pins (to a file or folder that no longer exists) keep their slots
     * and never absorb a Move up/down. No-op if either isn't pinned.
     */
    suspend fun swapPinnedItems(a: PinnedItem, b: PinnedItem) = editPinnedItems { items ->
        val i = items.indexOf(a)
        val j = items.indexOf(b)
        if (i < 0 || j < 0 || i == j) false
        else { items[i] = b; items[j] = a; true }
    }

    /** Keep the chosen monogram color and pin position attached to a notebook across renames. */
    suspend fun moveNotebookStyle(oldFileName: String, newFileName: String) {
        context.settingsDataStore.edit { prefs ->
            val colors = decodeModes(prefs[Keys.notebookColors]).toMutableMap()
            colors.remove(oldFileName)?.let { value ->
                colors[newFileName] = value
                prefs[Keys.notebookColors] = encodeModes(colors)
            }
            // Monogram icons replaced the glyph picker; drop any leftover glyph pick.
            prefs.remove(Keys.notebookIcons)
            val pinned = readPinnedItems(prefs).toMutableList()
            val pinIdx = pinned.indexOf(PinnedItem(PinKind.FILE, oldFileName))
            if (pinIdx >= 0) {
                pinned[pinIdx] = PinnedItem(PinKind.FILE, newFileName)
                writePinnedItems(prefs, pinned)
            }
        }
    }

    suspend fun setFolderColor(dir: String, colorKey: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeModes(prefs[Keys.folderColors]).toMutableMap()
            current[dir] = colorKey
            prefs[Keys.folderColors] = encodeModes(current)
        }
    }

    suspend fun pinFolder(dir: String) = editPinnedItems { items ->
        val entry = PinnedItem(PinKind.FOLDER, dir)
        if (entry in items) false else items.add(entry)
    }

    suspend fun unpinFolder(dir: String) = editPinnedItems { items ->
        items.remove(PinnedItem(PinKind.FOLDER, dir))
    }

    /**
     * Move the icon colour and pin state of a folder (and every descendant file
     * and sub-folder) from [oldDir] to [newDir] when the folder is renamed. Keys
     * are re-based by prefix: `oldDir` itself and anything under `oldDir/`.
     */
    suspend fun renameFolderStyle(oldDir: String, newDir: String) {
        if (oldDir == newDir) return
        context.settingsDataStore.edit { prefs ->
            val oldPrefix = "$oldDir/"
            fun rebase(key: String): String? = when {
                key == oldDir -> newDir
                key.startsWith(oldPrefix) -> newDir + "/" + key.removePrefix(oldPrefix)
                else -> null
            }

            val folderColors = decodeModes(prefs[Keys.folderColors]).toMutableMap()
            reKeyByPrefix(folderColors, ::rebase)
            prefs[Keys.folderColors] = encodeModes(folderColors)

            val notebookColors = decodeModes(prefs[Keys.notebookColors]).toMutableMap()
            reKeyByPrefix(notebookColors, ::rebase)
            prefs[Keys.notebookColors] = encodeModes(notebookColors)

            // One unified list: re-base every pinned entry (file or folder) whose
            // path is oldDir or sits under it, keeping list order untouched.
            writePinnedItems(
                prefs,
                readPinnedItems(prefs).map { item ->
                    rebase(item.path)?.let { item.copy(path = it) } ?: item
                },
            )
        }
    }

    /** Drop the icon colour and pin state of [dir] and every descendant when the folder is deleted. */
    suspend fun deleteFolderStyle(dir: String) {
        context.settingsDataStore.edit { prefs ->
            val prefix = "$dir/"
            fun own(key: String) = key == dir || key.startsWith(prefix)

            val folderColors = decodeModes(prefs[Keys.folderColors]).filterKeys { !own(it) }
            prefs[Keys.folderColors] = encodeModes(folderColors)

            val notebookColors = decodeModes(prefs[Keys.notebookColors]).filterKeys { !own(it) }
            prefs[Keys.notebookColors] = encodeModes(notebookColors)

            writePinnedItems(prefs, readPinnedItems(prefs).filterNot { own(it.path) })
        }
    }

    /** Re-key a map in place: for every key [rebase] resolves, remove the old entry and re-add under the new key. */
    private fun reKeyByPrefix(map: MutableMap<String, String>, rebase: (String) -> String?) {
        val moves = map.keys.mapNotNull { old -> rebase(old)?.let { old to it } }
        moves.forEach { (old, new) -> map.remove(old)?.let { map[new] = it } }
    }
}
