package com.rrajath.grove.settings

import androidx.annotation.StringRes
import com.rrajath.grove.R
import com.rrajath.grove.ui.util.UiText
import com.rrajath.grove.ui.util.text

// Pure-Kotlin preference enums (no android framework imports) so mapping logic is JVM-testable.
// User-visible labels are string resource ids (`R` and `@StringRes` are plain JVM types).

/**
 * A concrete color theme. [isDark] sorts it into the light or dark family: the
 * Light/Dark/System mode ([ThemeMode]) offers only same-family themes per slot.
 * Kept in sync with `GroveColors.isDark` (`ThemeTest` checks it).
 */
enum class ThemePreference(val storageKey: String, val isDark: Boolean) {
    LIGHT("light", false),
    DARK("dark", true),
    TOKYONIGHT("tokyonight", true),
    TOKYODAY("tokyoday", false),
    CATPPUCCIN("catppuccin", true),
    CATPPUCCINLATTE("catppuccinlatte", false),
    ROSEPINEDAWN("rosepinedawn", false),
    ROSEPINEMOON("rosepinemoon", true),
    CHALK("chalk", false),
    COBALT("cobalt", false),
    DELFT("delft", false),
    SAGE("sage", false),
    ASHROSE("ashrose", false),
    OBSIDIAN("obsidian", true),
    LANTERN("lantern", true),
    LIGHTHOUSE("lighthouse", true),
    MOSS("moss", true),
    WISTERIA("wisteria", true),
    BLOOMLIGHT("bloomlight", false),
    BLOOMDARK("bloomdark", true);

    companion object {
        fun fromStorage(value: String?): ThemePreference =
            fromStorageOrNull(value) ?: LIGHT

        fun fromStorageOrNull(value: String?): ThemePreference? =
            entries.firstOrNull { it.storageKey == value }
    }
}

/** Settings § Look and Feel: which theme slot applies. */
enum class ThemeMode(val storageKey: String) {
    LIGHT("light"),
    DARK("dark"),

    /** Follow the phone's dark theme setting (Android 10+ only, see [ThemeSelection.effectiveMode]). */
    SYSTEM("system");

    companion object {
        fun fromStorageOrNull(value: String?): ThemeMode? =
            entries.firstOrNull { it.storageKey == value }
    }
}

/**
 * The user's theme choice: a [mode] plus one theme per family. [light] must be a
 * light theme and [dark] a dark one ([sanitized] enforces it for stored/imported
 * values). Which theme is on screen comes from [resolve], never from a field.
 */
data class ThemeSelection(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val light: ThemePreference = ThemePreference.BLOOMLIGHT,
    val dark: ThemePreference = ThemePreference.BLOOMDARK,
) {
    /**
     * Before Android 10 there is no system-wide dark theme, so the System option
     * is hidden there and a stored SYSTEM (e.g. imported from a newer phone)
     * behaves as LIGHT. It stays SYSTEM in storage so it round-trips.
     */
    fun effectiveMode(systemSupported: Boolean): ThemeMode =
        if (mode == ThemeMode.SYSTEM && !systemSupported) ThemeMode.LIGHT else mode

    fun resolve(systemIsDark: Boolean, systemSupported: Boolean): ThemePreference =
        when (effectiveMode(systemSupported)) {
            ThemeMode.LIGHT -> light
            ThemeMode.DARK -> dark
            ThemeMode.SYSTEM -> if (systemIsDark) dark else light
        }

    /** Replace a slot holding the wrong family with that family's default. */
    fun sanitized(): ThemeSelection = copy(
        light = if (light.isDark) ThemePreference.BLOOMLIGHT else light,
        dark = if (dark.isDark) dark else ThemePreference.BLOOMDARK,
    )

    companion object {
        /** Stored form; null when [mode] is missing (nothing stored, or a pre-mode install). */
        fun fromStorage(mode: String?, light: String?, dark: String?): ThemeSelection? {
            val m = ThemeMode.fromStorageOrNull(mode) ?: return null
            val defaults = ThemeSelection()
            return ThemeSelection(
                mode = m,
                light = ThemePreference.fromStorageOrNull(light) ?: defaults.light,
                dark = ThemePreference.fromStorageOrNull(dark) ?: defaults.dark,
            ).sanitized()
        }

        /**
         * What an install's stored keys read as: the mode keys when present,
         * else the retired single [legacyTheme] migrated in place, else the
         * unset default ([defaultFor]).
         */
        fun fromStorage(
            mode: String?,
            light: String?,
            dark: String?,
            legacyTheme: String?,
            onboardingDone: Boolean,
        ): ThemeSelection =
            fromStorage(mode, light, dark)
                ?: legacyTheme?.let { fromLegacy(ThemePreference.fromStorage(it)) }
                ?: defaultFor(onboardingDone)

        /**
         * Map a pre-mode single theme onto a selection that looks the same: the
         * mode matching its family, the theme in that slot, the Bloom default in
         * the other.
         */
        fun fromLegacy(theme: ThemePreference): ThemeSelection =
            if (theme.isDark) ThemeSelection(ThemeMode.DARK, dark = theme)
            else ThemeSelection(ThemeMode.LIGHT, light = theme)

        /**
         * Theme for an install that stored nothing yet. Fresh installs follow the
         * phone with the Bloom pair; installs that finished onboarding before
         * Bloom Light existed keep the Grove Light they have always seen.
         */
        fun defaultFor(onboardingDone: Boolean): ThemeSelection =
            if (onboardingDone) fromLegacy(ThemePreference.LIGHT) else ThemeSelection()
    }
}

enum class FontSizePreference(val storageKey: String, val scale: Float, @StringRes val labelRes: Int) {
    SMALL("small", 0.9f, R.string.settings_font_size_small),
    MEDIUM("medium", 1.0f, R.string.settings_font_size_medium),
    LARGE("large", 1.12f, R.string.settings_font_size_large);

    companion object {
        fun fromStorage(value: String?): FontSizePreference =
            entries.firstOrNull { it.storageKey == value } ?: MEDIUM
    }
}

enum class SyncMode(val storageKey: String, @StringRes val labelRes: Int) {
    MANUAL("manual", R.string.settings_sync_mode_manual),
    ON_OPEN_CLOSE("on_open_close", R.string.settings_sync_mode_on_open_close),
    PERIODIC("periodic", R.string.settings_sync_mode_periodic),
    CONTINUOUS("continuous", R.string.settings_sync_mode_continuous);

    companion object {
        fun fromStorage(value: String?): SyncMode =
            entries.firstOrNull { it.storageKey == value } ?: ON_OPEN_CLOSE
    }
}

enum class OutlineToggle { TAGS, TIMESTAMPS, KEYWORDS }

enum class NoteOpenMode(val storageKey: String) {
    READ("read"),
    EDIT("edit");

    companion object {
        fun fromStorage(value: String?): NoteOpenMode =
            entries.firstOrNull { it.storageKey == value } ?: READ
    }
}

/**
 * Settings § Notes: where the caret lands when a note is created for immediate
 * editing (the outline "+" FAB). [HEADING] parks it just after the "* " ready to
 * type a title; [BODY] parks it on the blank body line below, which is the
 * historical behavior and the default.
 */
enum class NewNoteCursor(val storageKey: String) {
    HEADING("heading"),
    BODY("body");

    companion object {
        fun fromStorage(value: String?): NewNoteCursor =
            entries.firstOrNull { it.storageKey == value } ?: BODY
    }
}

enum class NotebookDisplayNameMode(val storageKey: String) {
    FILENAME("filename"),
    TITLE("title");

    companion object {
        fun fromStorage(value: String?): NotebookDisplayNameMode =
            entries.firstOrNull { it.storageKey == value } ?: FILENAME
    }
}

/**
 * Settings § Notebooks: what the notebooks list is sorted by. Pinned notebooks
 * and folders always keep their pin order and are unaffected. A folder sorts by
 * its most recently modified descendant file when [LAST_MODIFIED] is picked.
 */
enum class NotebookSortKey(val storageKey: String, @StringRes val labelRes: Int) {
    ALPHABETICAL("alphabetical", R.string.settings_notebooks_sort_alphabetical),
    LAST_MODIFIED("last_modified", R.string.settings_notebooks_sort_last_modified);

    companion object {
        fun fromStorage(value: String?): NotebookSortKey =
            entries.firstOrNull { it.storageKey == value } ?: ALPHABETICAL
    }
}

/**
 * Settings § Reminders: how far ahead of a SCHEDULED/DEADLINE's own time-of-day
 * the "due" notification actually fires. Only applies to timestamps that carry
 * an explicit time-of-day; a date-only stamp fires at the "Send reminder at"
 * time exactly (see `ReminderEntity.firesOwnNotification`), so a lead time has
 * nothing to shift for those.
 */
enum class ReminderLeadTime(
    val storageKey: String,
    @StringRes val labelRes: Int,
    val offsetMinutes: Long,
    /** Notification text for a task (a heading with a todo keyword). */
    @StringRes val taskMessageRes: Int,
    /** Notification text for an event (no todo keyword). */
    @StringRes val eventMessageRes: Int,
) {
    AT_TIME("at_time", R.string.reminders_lead_at_time, 0L, R.string.reminders_task_due_now, R.string.reminders_event_starting_now),
    MIN_5("min_5", R.string.reminders_lead_min_5, 5L, R.string.reminders_task_due_min_5, R.string.reminders_event_starts_min_5),
    MIN_10("min_10", R.string.reminders_lead_min_10, 10L, R.string.reminders_task_due_min_10, R.string.reminders_event_starts_min_10),
    MIN_15("min_15", R.string.reminders_lead_min_15, 15L, R.string.reminders_task_due_min_15, R.string.reminders_event_starts_min_15),
    MIN_30("min_30", R.string.reminders_lead_min_30, 30L, R.string.reminders_task_due_min_30, R.string.reminders_event_starts_min_30),
    HOUR_1("hour_1", R.string.reminders_lead_hour_1, 60L, R.string.reminders_task_due_hour_1, R.string.reminders_event_starts_hour_1),
    DAY_1("day_1", R.string.reminders_lead_day_1, 1440L, R.string.reminders_task_due_day_1, R.string.reminders_event_starts_day_1);

    companion object {
        fun fromStorage(value: String?): ReminderLeadTime =
            entries.firstOrNull { it.storageKey == value } ?: AT_TIME
    }
}

/**
 * Settings § Attachments: how Camera and Photos images are stored. [longEdge] is
 * the target long edge in px, or null to keep full resolution (HEIC is still
 * converted to JPEG). Files-picker attachments are always copied as-is.
 */
enum class AttachPhotoSize(val storageKey: String, @StringRes val labelRes: Int, val longEdge: Int?) {
    ORIGINAL("ORIGINAL", R.string.settings_attach_photo_size_original, null),
    LARGE("LARGE", R.string.settings_attach_photo_size_large, 2560),
    MEDIUM("MEDIUM", R.string.settings_attach_photo_size_medium, 1920),
    SMALL("SMALL", R.string.settings_attach_photo_size_small, 1280);

    companion object {
        fun fromStorage(value: String?): AttachPhotoSize = entries.firstOrNull { it.storageKey == value } ?: ORIGINAL
    }
}

/** Settings § Attachments: default read-mode width for inline images. */
enum class AttachImageWidth(val storageKey: String, @StringRes val labelRes: Int) {
    FIT("FIT", R.string.settings_attach_image_width_fit),
    FIXED("FIXED", R.string.settings_attach_image_width_fixed);

    companion object {
        const val DEFAULT_PX = 320
        const val MIN_PX = 120
        const val MAX_PX = 1200
        const val STEP_PX = 40

        fun fromStorage(value: String?): AttachImageWidth = entries.firstOrNull { it.storageKey == value } ?: FIT

        fun clampPx(px: Int): Int = px.coerceIn(MIN_PX, MAX_PX)
    }
}

/**
 * Settings § Attachments: the attachment folder (`org-attach-id-dir`), always
 * relative to each note's folder. One or more plain folder names (`attach/`,
 * `.attach/`, `media/files/`); no vault-rooted or climbing paths.
 */
object AttachFolder {
    const val DEFAULT = "data/"

    /** Why [raw] can't be used, or null when it can. */
    fun error(raw: String): UiText? {
        val v = raw.trim()
        val segments = v.split('/').filter { it.isNotEmpty() }
        val id = when {
            v.isEmpty() -> R.string.settings_attach_folder_error_empty
            v.startsWith("/") -> R.string.settings_attach_folder_error_leading_slash
            '~' in v -> R.string.settings_attach_folder_error_tilde
            '\\' in v -> R.string.settings_attach_folder_error_backslash
            segments.isEmpty() -> R.string.settings_attach_folder_error_empty
            segments.any { it.trim() == ".." } -> R.string.settings_attach_folder_error_parent
            segments.any { it.trim() == "." } -> R.string.settings_attach_folder_error_dot
            segments.any { it != it.trim() } -> R.string.settings_attach_folder_error_space
            else -> return null
        }
        return UiText.res(id)
    }

    /** [raw] trimmed, repeated `/` collapsed, one trailing `/`; null when [error] rejects it. */
    fun normalize(raw: String): String? =
        if (error(raw) != null) null
        else raw.trim().split('/').filter { it.isNotEmpty() }.joinToString("/") + "/"

    /** A stored or imported value, falling back to [DEFAULT] when missing or invalid. */
    fun sanitize(raw: String?): String = raw?.let(::normalize) ?: DEFAULT
}

/**
 * Settings § Attachments: the tag a heading gets when files are attached to it
 * (`org-attach-auto-tag`), stored without colons. Empty means no tag.
 */
object AttachAutoTag {
    const val DEFAULT = "ATTACH"

    /** Why [raw] can't be used, or null when it can: blank (no tag) or a valid heading tag. */
    fun error(raw: String): UiText? = if (raw.isBlank()) null else com.rrajath.grove.org.OrgTags.error(raw)?.text()

    /** [raw] trimmed (empty for no tag); null when [error] rejects it. */
    fun normalize(raw: String): String? = if (error(raw) != null) null else raw.trim()

    /** A stored or imported value: missing or invalid falls back to [DEFAULT]; empty stays empty. */
    fun sanitize(raw: String?): String = raw?.let(::normalize) ?: DEFAULT
}

/**
 * First column of every month calendar (Settings § Agenda "Start of week").
 * [SYSTEM] follows the device region, so it can resolve to a day other than
 * Sunday or Monday (e.g. Saturday). Display only: org repeaters, ISO week
 * datetrees and the "next week" shorthand never read it.
 */
enum class WeekStart(val storageKey: String, @StringRes val labelRes: Int) {
    SYSTEM("system", R.string.settings_week_start_system),
    SUNDAY("sunday", R.string.settings_week_start_sunday),
    MONDAY("monday", R.string.settings_week_start_monday);

    /** The concrete first day, given the device region's own first day for [SYSTEM]. */
    fun resolve(systemFirstDay: java.time.DayOfWeek): java.time.DayOfWeek = when (this) {
        SYSTEM -> systemFirstDay
        SUNDAY -> java.time.DayOfWeek.SUNDAY
        MONDAY -> java.time.DayOfWeek.MONDAY
    }

    companion object {
        fun fromStorage(value: String?): WeekStart =
            entries.firstOrNull { it.storageKey == value } ?: SYSTEM

        /** Blank cells before day 1 of a month whose 1st falls on [firstOfMonth]. */
        fun leadingBlanks(firstOfMonth: java.time.DayOfWeek, firstDay: java.time.DayOfWeek): Int =
            (firstOfMonth.value - firstDay.value + 7) % 7

        /** The seven weekdays in column order, starting at [firstDay]. */
        fun weekdays(firstDay: java.time.DayOfWeek): List<java.time.DayOfWeek> =
            List(7) { firstDay.plus(it.toLong()) }
    }
}

/** Agenda row swipe gesture (either direction), Settings § Agenda. */
enum class AgendaSwipeAction(val storageKey: String, @StringRes val labelRes: Int) {
    SET_SCHEDULED("set_scheduled", R.string.agenda_swipe_action_schedule),
    SET_DEADLINE("set_deadline", R.string.agenda_swipe_action_deadline),
    MARK_DONE("mark_done", R.string.agenda_swipe_action_done),

    /** Clock in on the row's heading, or out when its clock runs (the Emacs agenda's `I` / `O`). */
    CLOCK("clock", R.string.agenda_swipe_action_clock);

    companion object {
        fun fromStorage(value: String?, default: AgendaSwipeAction): AgendaSwipeAction =
            entries.firstOrNull { it.storageKey == value } ?: default
    }
}

/**
 * Agenda levers § "Group by": how the day's items are bucketed into sections.
 * Lives in settings rather than the ViewModel because a user who always works
 * by priority shouldn't re-pick it on every visit to the screen.
 */
enum class AgendaGrouping(val storageKey: String, @StringRes val labelRes: Int) {
    DATE("date", R.string.agenda_group_by_date),
    PRIORITY("priority", R.string.agenda_group_by_priority),
    TAG("tag", R.string.agenda_group_by_tag),
    FILE("file", R.string.agenda_group_by_file);

    companion object {
        fun fromStorage(value: String?): AgendaGrouping =
            entries.firstOrNull { it.storageKey == value } ?: DATE
    }
}

/**
 * Agenda levers § "Show": which TODO states reach the list.
 *
 * The middle chips are the vault's own active (todo-type) keywords rather than
 * a fixed `NEXT`/`WAITING` pair, so the filter follows whatever `todoKeywords`
 * is configured. [Open] is state-agnostic: anything not done-type, including
 * headings with no keyword at all.
 */
sealed interface AgendaStateFilter {

    val storageKey: String

    /** Everything that is not a done-type keyword. */
    data object Open : AgendaStateFilter {
        override val storageKey: String get() = "open"
    }

    /** Every planned heading, completed ones included. */
    data object All : AgendaStateFilter {
        override val storageKey: String get() = "all"
    }

    /** Exactly one active keyword, e.g. `NEXT`. */
    data class Keyword(val name: String) : AgendaStateFilter {
        override val storageKey: String get() = "$KEYWORD_PREFIX$name"
    }

    companion object {
        private const val KEYWORD_PREFIX = "kw:"

        fun fromStorage(value: String?): AgendaStateFilter = when {
            value == null -> Open
            value == All.storageKey -> All
            value.startsWith(KEYWORD_PREFIX) ->
                value.removePrefix(KEYWORD_PREFIX).takeIf { it.isNotEmpty() }?.let(::Keyword) ?: Open
            else -> Open
        }
    }
}
