package com.rrajath.grove.settings

// Pure-Kotlin preference enums (no android imports) so mapping logic is JVM-testable.

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

enum class FontSizePreference(val storageKey: String, val scale: Float) {
    SMALL("small", 0.9f),
    MEDIUM("medium", 1.0f),
    LARGE("large", 1.12f);

    companion object {
        fun fromStorage(value: String?): FontSizePreference =
            entries.firstOrNull { it.storageKey == value } ?: MEDIUM
    }
}

enum class SyncMode(val storageKey: String, val label: String) {
    MANUAL("manual", "Manual only"),
    ON_OPEN_CLOSE("on_open_close", "On open / close"),
    PERIODIC("periodic", "Periodic"),
    CONTINUOUS("continuous", "Continuous");

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
enum class NotebookSortKey(val storageKey: String, val label: String) {
    ALPHABETICAL("alphabetical", "Alphabetical"),
    LAST_MODIFIED("last_modified", "Last modified");

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
enum class ReminderLeadTime(val storageKey: String, val label: String, val offsetMinutes: Long, val dueMessage: String) {
    AT_TIME("at_time", "At the time of event", 0L, "Your task is due now"),
    MIN_5("min_5", "5 minutes before the event", 5L, "Your task is due in 5 minutes"),
    MIN_10("min_10", "10 minutes before the event", 10L, "Your task is due in 10 minutes"),
    MIN_15("min_15", "15 minutes before the event", 15L, "Your task is due in 15 minutes"),
    MIN_30("min_30", "30 minutes before the event", 30L, "Your task is due in 30 minutes"),
    HOUR_1("hour_1", "1 hour before the event", 60L, "Your task is due in 1 hour"),
    DAY_1("day_1", "1 day before the event", 1440L, "Your task is due in a day");

    companion object {
        fun fromStorage(value: String?): ReminderLeadTime =
            entries.firstOrNull { it.storageKey == value } ?: AT_TIME
    }
}

/** Agenda row swipe gesture (either direction), Settings § Agenda. */
enum class AgendaSwipeAction(val storageKey: String, val label: String) {
    SET_SCHEDULED("set_scheduled", "Schedule Task"),
    SET_DEADLINE("set_deadline", "Set Deadline"),
    MARK_DONE("mark_done", "Mark as Done");

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
enum class AgendaGrouping(val storageKey: String, val label: String) {
    DATE("date", "Date"),
    PRIORITY("priority", "Priority"),
    TAG("tag", "Tag"),
    FILE("file", "File");

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
