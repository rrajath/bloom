package com.rrajath.grove.ui.screens.settings

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.rrajath.grove.BuildConfig
import com.rrajath.grove.R
import com.rrajath.grove.ui.nav.Routes
import com.rrajath.grove.ui.vault.WHOLE_FILE_LINE_LIMIT

/** A Settings sub-page a search result can open. [visible] mirrors the hub's own build gate. */
enum class SettingsPageId(val route: String, @StringRes val title: Int, val visible: Boolean = true) {
    APPEARANCE(Routes.SETTINGS_APPEARANCE, R.string.settings_page_appearance_title),
    NOTEBOOKS(Routes.SETTINGS_NOTEBOOKS, R.string.settings_page_notebooks_title),
    CAPTURE_TEMPLATES(Routes.SETTINGS_CAPTURE_TEMPLATES, R.string.settings_page_capture_templates_title),
    SYNC(Routes.SETTINGS_SYNC, R.string.settings_page_sync_title),
    NOTES(Routes.SETTINGS_NOTES, R.string.settings_page_notes_title),
    AGENDA(Routes.SETTINGS_AGENDA, R.string.settings_page_agenda_title),
    WIDGET(Routes.SETTINGS_WIDGET, R.string.settings_page_widget_title),
    REMINDERS(Routes.SETTINGS_REMINDERS, R.string.settings_page_reminders_title),
    ROAM(Routes.SETTINGS_ROAM, R.string.settings_page_roam_title, visible = BuildConfig.ROAM_FEATURES),
    ATTACHMENTS(Routes.SETTINGS_ATTACHMENTS, R.string.settings_page_attachments_title),
    SHARING(Routes.SETTINGS_SHARING, R.string.settings_page_sharing_title),
    BACKUP(Routes.SETTINGS_BACKUP, R.string.settings_page_backup_title),
    BUG_REPORT(Routes.SETTINGS_BUG_REPORT, R.string.settings_page_bug_report_title),
    TIPS(Routes.SETTINGS_TIPS, R.string.settings_page_tips_title),
    WHATS_NEW(Routes.SETTINGS_WHATS_NEW, R.string.settings_page_whats_new_title),
    DEVELOPER(Routes.SETTINGS_DEVELOPER, R.string.settings_page_developer_title, visible = BuildConfig.DEBUG),
}

enum class SettingKind {
    TOGGLE,
    /** A segmented control (or another pick-one control). */
    CHOICE,
    /** A text or number field, slider, picker or action. */
    OTHER,
    /** A whole page with no single row to reveal (Help and About entries). */
    PAGE,
}

/** Stands in for the app name in [SettingSpec.descriptionArgs]. */
object AppNameArg

/**
 * One searchable setting: the single source of its label and description for both its
 * settings page and Settings search, and the id its row is tagged with for the reveal.
 *
 * [inline] settings render their real control in a search result, bound in
 * [INLINE_BINDINGS] to the same ViewModel setter the page uses. Leave it false for
 * anything whose page does extra work (permission prompts, deferred commits, pickers).
 *
 * [description] is what search matches on. Pages whose hint depends on the current value
 * (or carries styling) keep their own hint and leave this as a representative one, or null.
 *
 * [revealFallback] is revealed instead when this row is hidden behind another setting
 * (e.g. Archive location only shows while Auto-archive is on).
 */
class SettingSpec(
    val id: String,
    val page: SettingsPageId,
    @StringRes val label: Int,
    @StringRes val description: Int? = null,
    val kind: SettingKind = SettingKind.OTHER,
    val inline: Boolean = false,
    val descriptionArgs: List<Any> = emptyList(),
    val revealFallback: String? = null,
) {
    val testTag: String get() = "setting:$id"
}

fun SettingSpec.labelString(res: Resources): String = res.getString(label)

fun SettingSpec.descriptionString(res: Resources): String? = description?.let { d ->
    val args = descriptionArgs.map { if (it === AppNameArg) res.getString(R.string.app_name) else it }
    if (args.isEmpty()) res.getString(d) else res.getString(d, *args.toTypedArray())
}

@Composable
@ReadOnlyComposable
fun SettingSpec.labelText(): String {
    LocalConfiguration.current
    return labelString(LocalContext.current.resources)
}

@Composable
@ReadOnlyComposable
fun SettingSpec.descriptionText(): String? {
    LocalConfiguration.current
    return descriptionString(LocalContext.current.resources)
}

/**
 * Settings whose label or description contains [query] (case-insensitive), in [specs] order.
 * [resolve] turns a spec into its (label, description) text.
 */
fun matchSettingSpecs(
    query: String,
    specs: List<SettingSpec>,
    resolve: (SettingSpec) -> Pair<String, String?>,
): List<SettingSpec> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return specs.filter { spec ->
        val (label, description) = resolve(spec)
        label.contains(q, ignoreCase = true) || description?.contains(q, ignoreCase = true) == true
    }
}

/** Every searchable setting, in hub and page order. Add a spec with every new settings row. */
object SettingSpecs {
    private fun s(
        id: String,
        page: SettingsPageId,
        @StringRes label: Int,
        @StringRes description: Int? = null,
        kind: SettingKind = SettingKind.OTHER,
        inline: Boolean = false,
        args: List<Any> = emptyList(),
        fallback: String? = null,
    ) = SettingSpec(id, page, label, description, kind, inline, args, fallback)

    private val TOGGLE = SettingKind.TOGGLE
    private val CHOICE = SettingKind.CHOICE

    // Appearance
    val AppearanceTheme = s("appearance.theme", SettingsPageId.APPEARANCE, R.string.settings_appearance_theme, kind = CHOICE)
    val AppearanceSyncIcon = s("appearance.syncIcon", SettingsPageId.APPEARANCE, R.string.settings_appearance_sync_icon, kind = TOGGLE)
    val AppearanceTextSize = s(
        "appearance.textSize", SettingsPageId.APPEARANCE,
        R.string.settings_appearance_text_size, R.string.settings_appearance_text_size_hint, CHOICE, inline = true,
    )

    // Notebooks
    val NotebooksFileIcons = s(
        "notebooks.fileIcons", SettingsPageId.NOTEBOOKS,
        R.string.settings_notebooks_file_icons, R.string.settings_notebooks_file_icons_hint, TOGGLE, inline = true,
    )
    val NotebooksFlatten = s(
        "notebooks.flatten", SettingsPageId.NOTEBOOKS,
        R.string.settings_notebooks_flatten, R.string.settings_notebooks_flatten_hint, TOGGLE, inline = true,
    )
    val NotebooksDisplayName = s(
        "notebooks.displayName", SettingsPageId.NOTEBOOKS, R.string.settings_notebooks_display_name, kind = CHOICE, inline = true,
    )
    val NotebooksSort = s(
        "notebooks.sort", SettingsPageId.NOTEBOOKS,
        R.string.settings_notebooks_sort, R.string.settings_notebooks_sort_hint, CHOICE, inline = true,
    )
    val NotebooksOrder = s("notebooks.order", SettingsPageId.NOTEBOOKS, R.string.settings_notebooks_order, kind = CHOICE, inline = true)
    val NotebooksIgnoreList = s(
        "notebooks.ignoreList", SettingsPageId.NOTEBOOKS,
        R.string.settings_notebooks_ignore_list, R.string.settings_notebooks_ignore_list_hint,
    )

    // Capture templates
    val TemplatesNew = s("templates.new", SettingsPageId.CAPTURE_TEMPLATES, R.string.settings_templates_new)
    val TemplatesNotification = s(
        "templates.notification", SettingsPageId.CAPTURE_TEMPLATES, R.string.settings_templates_notification, kind = TOGGLE,
    )

    // Sync
    val SyncFolder = s("sync.folder", SettingsPageId.SYNC, R.string.settings_sync_folder)
    val SyncAuto = s("sync.auto", SettingsPageId.SYNC, R.string.settings_sync_auto, kind = CHOICE)
    val SyncInterval = s("sync.interval", SettingsPageId.SYNC, R.string.settings_sync_interval, kind = CHOICE, fallback = "sync.auto")
    val SyncLog = s("sync.log", SettingsPageId.SYNC, R.string.settings_sync_view_log)

    // Notes
    val NotesTodoKeywords = s(
        "notes.todoKeywords", SettingsPageId.NOTES, R.string.settings_notes_todo_keywords, R.string.settings_notes_todo_keywords_hint,
    )
    val NotesDefaultPriority = s(
        "notes.defaultPriority", SettingsPageId.NOTES,
        R.string.settings_notes_default_priority, R.string.settings_notes_default_priority_hint, CHOICE, inline = true,
    )
    val NotesDefaultMode = s("notes.defaultMode", SettingsPageId.NOTES, R.string.settings_notes_default_mode, kind = CHOICE, inline = true)
    val NotesFontSize = s("notes.fontSize", SettingsPageId.NOTES, R.string.settings_notes_font_size, R.string.settings_notes_font_size_hint, CHOICE)
    val NotesShowPreface = s(
        "notes.showPreface", SettingsPageId.NOTES,
        R.string.settings_notes_show_preface, R.string.settings_notes_show_preface_hint, TOGGLE, inline = true,
    )
    val NotesShowDrawers = s(
        "notes.showDrawers", SettingsPageId.NOTES,
        R.string.settings_notes_show_drawers, R.string.settings_notes_show_drawers_hint, TOGGLE, inline = true,
    )
    val NotesAddId = s(
        "notes.addId", SettingsPageId.NOTES, R.string.settings_notes_add_id, R.string.settings_notes_add_id_hint, TOGGLE, inline = true,
    )
    val NotesAddCreated = s(
        "notes.addCreated", SettingsPageId.NOTES,
        R.string.settings_notes_add_created, R.string.settings_notes_add_created_hint, TOGGLE, inline = true,
    )
    val NotesNewNoteCursor = s(
        "notes.newNoteCursor", SettingsPageId.NOTES,
        R.string.settings_notes_new_note_cursor, R.string.settings_notes_new_note_cursor_hint, CHOICE, inline = true,
    )
    val NotesAutoSave = s(
        "notes.autoSave", SettingsPageId.NOTES, R.string.settings_notes_auto_save, R.string.settings_notes_auto_save_hint, TOGGLE, inline = true,
    )
    val NotesCapitalize = s(
        "notes.capitalize", SettingsPageId.NOTES,
        R.string.settings_notes_capitalize, R.string.settings_notes_capitalize_hint, TOGGLE, inline = true,
    )
    val NotesHighlightLine = s(
        "notes.highlightLine", SettingsPageId.NOTES,
        R.string.settings_notes_highlight_line, R.string.settings_notes_highlight_line_hint, TOGGLE, inline = true,
    )
    val NotesLineNumbers = s(
        "notes.lineNumbers", SettingsPageId.NOTES,
        R.string.settings_notes_line_numbers, R.string.settings_notes_line_numbers_hint, TOGGLE, inline = true,
    )
    val NotesAutoArchive = s(
        "notes.autoArchive", SettingsPageId.NOTES,
        R.string.settings_notes_auto_archive, R.string.settings_notes_auto_archive_hint, TOGGLE, inline = true,
    )
    val NotesArchiveLocation = s(
        "notes.archiveLocation", SettingsPageId.NOTES,
        R.string.settings_notes_archive_location, R.string.settings_notes_archive_location_hint, fallback = "notes.autoArchive",
    )

    // Agenda
    val AgendaSwipeLeft = s("agenda.swipeLeft", SettingsPageId.AGENDA, R.string.settings_agenda_swipe_left, kind = CHOICE)
    val AgendaSwipeRight = s("agenda.swipeRight", SettingsPageId.AGENDA, R.string.settings_agenda_swipe_right, kind = CHOICE)
    val AgendaDeadlineWarning = s(
        "agenda.deadlineWarning", SettingsPageId.AGENDA,
        R.string.settings_agenda_deadline_warning, R.string.settings_agenda_deadline_warning_hint,
    )
    val AgendaWeekStart = s(
        "agenda.weekStart", SettingsPageId.AGENDA, R.string.settings_agenda_week_start, R.string.settings_agenda_week_start_hint, CHOICE,
    )

    // Widget
    val WidgetTransparency = s(
        "widget.transparency", SettingsPageId.WIDGET, R.string.settings_widget_transparency, R.string.settings_widget_transparency_hint,
    )
    val WidgetDaysAhead = s(
        "widget.daysAhead", SettingsPageId.WIDGET, R.string.settings_widget_days_ahead, R.string.settings_widget_days_ahead_hint,
    )
    val WidgetShowFileName = s(
        "widget.showFileName", SettingsPageId.WIDGET,
        R.string.settings_widget_show_filename, R.string.settings_widget_show_filename_hint, TOGGLE, inline = true,
    )
    val WidgetShowTags = s(
        "widget.showTags", SettingsPageId.WIDGET,
        R.string.settings_widget_show_tags, R.string.settings_widget_show_tags_hint, TOGGLE, inline = true,
    )
    val WidgetShowPriority = s(
        "widget.showPriority", SettingsPageId.WIDGET,
        R.string.settings_widget_show_priority, R.string.settings_widget_show_priority_hint, TOGGLE, inline = true,
    )
    val WidgetQuickAddTarget = s(
        "widget.quickAddTarget", SettingsPageId.WIDGET,
        R.string.settings_widget_quick_add_target, R.string.settings_widget_quick_add_target_default_hint,
    )
    val WidgetFontSize = s("widget.fontSize", SettingsPageId.WIDGET, R.string.settings_widget_font_size, kind = CHOICE, inline = true)

    // Reminders: all navigate-only, the page owns the permission rows.
    val RemindersEnable = s(
        "reminders.enable", SettingsPageId.REMINDERS, R.string.settings_reminders_enable, R.string.settings_reminders_enable_hint,
        TOGGLE, args = listOf("<2026-10-30 Thu 09:00>"), // i18n: org timestamp syntax
    )
    val RemindersLeadTime = s(
        "reminders.leadTime", SettingsPageId.REMINDERS,
        R.string.settings_reminders_notify_me, R.string.settings_reminders_notify_me_hint, CHOICE, fallback = "reminders.enable",
    )
    val RemindersUntimed = s(
        "reminders.untimed", SettingsPageId.REMINDERS,
        R.string.settings_reminders_untimed, R.string.settings_reminders_untimed_hint, TOGGLE, fallback = "reminders.enable",
    )
    val RemindersMorningBrief = s(
        "reminders.morningBrief", SettingsPageId.REMINDERS,
        R.string.settings_reminders_morning_brief, R.string.settings_reminders_morning_brief_hint, TOGGLE,
    )
    val RemindersSendAt = s(
        "reminders.sendAt", SettingsPageId.REMINDERS,
        R.string.settings_reminders_send_at, R.string.settings_reminders_send_at_hint, fallback = "reminders.morningBrief",
    )

    // Roam (debug and perf builds only)
    val RoamEnable = s(
        "roam.enable", SettingsPageId.ROAM, R.string.settings_page_roam_title, R.string.settings_roam_enable_hint, TOGGLE, inline = true,
    )
    val RoamBacklinks = s(
        "roam.backlinks", SettingsPageId.ROAM,
        R.string.settings_roam_backlinks, R.string.settings_roam_backlinks_hint, TOGGLE, inline = true, fallback = "roam.enable",
    )
    val RoamSuggestions = s(
        "roam.suggestions", SettingsPageId.ROAM,
        R.string.settings_roam_suggestions, R.string.settings_roam_suggestions_hint, TOGGLE, inline = true, fallback = "roam.enable",
    )
    val RoamWholeFile = s(
        "roam.wholeFile", SettingsPageId.ROAM, R.string.settings_roam_whole_file, R.string.settings_roam_whole_file_hint,
        TOGGLE, inline = true, args = listOf(WHOLE_FILE_LINE_LIMIT), fallback = "roam.enable",
    )
    val RoamDailiesLocation = s(
        "roam.dailiesLocation", SettingsPageId.ROAM,
        R.string.settings_roam_dailies_location, R.string.settings_roam_dailies_location_hint, fallback = "roam.enable",
    )
    val RoamDailiesPattern = s(
        "roam.dailiesPattern", SettingsPageId.ROAM, R.string.settings_roam_dailies_pattern, R.string.settings_roam_dailies_pattern_hint,
        args = listOf("%<%Y-%m-%d>.org"), fallback = "roam.enable", // i18n: org-roam filename pattern
    )
    val RoamDailiesHeader = s(
        "roam.dailiesHeader", SettingsPageId.ROAM, R.string.settings_roam_dailies_header, R.string.settings_roam_dailies_header_hint,
        args = listOf("%?"), fallback = "roam.enable", // i18n: template placeholder
    )

    // Attachments
    val AttachPhotoSize = s(
        "attachments.photoSize", SettingsPageId.ATTACHMENTS,
        R.string.settings_attach_photo_size, R.string.settings_attach_photo_size_hint, CHOICE, inline = true,
    )
    val AttachImageWidth = s(
        "attachments.imageWidth", SettingsPageId.ATTACHMENTS, R.string.settings_attach_image_width, R.string.settings_attach_image_width_hint,
        CHOICE, inline = true, args = listOf("#+ATTR_ORG: :width"), // i18n: org syntax
    )
    val AttachFolder = s(
        "attachments.folder", SettingsPageId.ATTACHMENTS, R.string.settings_attach_folder, R.string.settings_attach_folder_hint,
        args = listOf("org-attach-id-dir"), // i18n: Emacs variable name
    )
    val AttachTag = s(
        "attachments.tag", SettingsPageId.ATTACHMENTS, R.string.settings_attach_tag, R.string.settings_attach_tag_none_hint,
        args = listOf("org-attach-auto-tag"), // i18n: Emacs variable name
    )

    // Sharing
    val SharingTarget = s(
        "sharing.target", SettingsPageId.SHARING, R.string.settings_share_target, R.string.settings_share_target_hint,
        args = listOf(AppNameArg),
    )
    val SharingPlacement = s("sharing.placement", SettingsPageId.SHARING, R.string.settings_share_placement, kind = CHOICE, inline = true)
    val SharingFetchTitles = s(
        "sharing.fetchTitles", SettingsPageId.SHARING,
        R.string.settings_share_fetch_titles, R.string.settings_share_fetch_titles_hint, TOGGLE, inline = true,
    )

    // Backup: the page has no row label, so the result names both buttons.
    val BackupFile = s("backup.file", SettingsPageId.BACKUP, R.string.settings_search_backup_label, R.string.settings_backup_intro)

    // Not inline: turning it on may need the folder picker first.
    val BackupAuto = s("backup.auto", SettingsPageId.BACKUP, R.string.settings_auto_backup_toggle, R.string.settings_auto_backup_toggle_hint, TOGGLE)
    val BackupFolder = s("backup.folder", SettingsPageId.BACKUP, R.string.settings_auto_backup_folder)
    val BackupRestore = s("backup.restore", SettingsPageId.BACKUP, R.string.settings_auto_backup_restore)

    // Help and About: page entries, nothing on the page is revealed.
    val BugReportPage = s(
        "page.bugReport", SettingsPageId.BUG_REPORT,
        R.string.settings_page_bug_report_title, R.string.settings_page_bug_report_summary, SettingKind.PAGE,
    )
    val TipsPage = s("page.tips", SettingsPageId.TIPS, R.string.settings_page_tips_title, R.string.settings_page_tips_summary, SettingKind.PAGE)
    val WhatsNewPage = s(
        "page.whatsNew", SettingsPageId.WHATS_NEW,
        R.string.settings_page_whats_new_title, R.string.settings_page_whats_new_summary, SettingKind.PAGE,
    )
    val DeveloperPage = s(
        "page.developer", SettingsPageId.DEVELOPER,
        R.string.settings_page_developer_title, R.string.settings_page_developer_summary, SettingKind.PAGE,
    )

    val all: List<SettingSpec> = listOf(
        AppearanceTheme, AppearanceSyncIcon, AppearanceTextSize,
        NotebooksFileIcons, NotebooksFlatten, NotebooksDisplayName, NotebooksSort, NotebooksOrder, NotebooksIgnoreList,
        TemplatesNew, TemplatesNotification,
        SyncFolder, SyncAuto, SyncInterval, SyncLog,
        NotesTodoKeywords, NotesDefaultPriority, NotesDefaultMode, NotesFontSize, NotesShowPreface, NotesShowDrawers,
        NotesAddId, NotesAddCreated, NotesNewNoteCursor, NotesAutoSave, NotesCapitalize, NotesHighlightLine,
        NotesLineNumbers, NotesAutoArchive, NotesArchiveLocation,
        AgendaSwipeLeft, AgendaSwipeRight, AgendaDeadlineWarning, AgendaWeekStart,
        WidgetTransparency, WidgetDaysAhead, WidgetShowFileName, WidgetShowTags, WidgetShowPriority,
        WidgetQuickAddTarget, WidgetFontSize,
        RemindersEnable, RemindersLeadTime, RemindersUntimed, RemindersMorningBrief, RemindersSendAt,
        RoamEnable, RoamBacklinks, RoamSuggestions, RoamWholeFile, RoamDailiesLocation, RoamDailiesPattern, RoamDailiesHeader,
        AttachPhotoSize, AttachImageWidth, AttachFolder, AttachTag,
        SharingTarget, SharingPlacement, SharingFetchTitles,
        BackupAuto, BackupFolder, BackupRestore, BackupFile,
        BugReportPage, TipsPage, WhatsNewPage, DeveloperPage,
    )

    /** What search offers in this build (debug-only pages stay hidden, as on the hub). */
    val searchable: List<SettingSpec> get() = all.filter { it.page.visible }

    fun byId(id: String): SettingSpec? = all.firstOrNull { it.id == id }
}
