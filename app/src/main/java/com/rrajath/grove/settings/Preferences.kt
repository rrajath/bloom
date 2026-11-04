package com.rrajath.grove.settings

// Pure-Kotlin preference enums (no android imports) so mapping logic is JVM-testable.

enum class ThemePreference(val storageKey: String) {
    LIGHT("light"),
    DARK("dark"),
    TOKYONIGHT("tokyonight"),
    TOKYODAY("tokyoday"),
    CATPPUCCIN("catppuccin"),
    CATPPUCCINLATTE("catppuccinlatte"),
    ROSEPINEDAWN("rosepinedawn"),
    ROSEPINEMOON("rosepinemoon"),
    CHALK("chalk"),
    COBALT("cobalt"),
    DELFT("delft"),
    SAGE("sage"),
    ASHROSE("ashrose"),
    OBSIDIAN("obsidian"),
    LANTERN("lantern"),
    LIGHTHOUSE("lighthouse"),
    MOSS("moss"),
    WISTERIA("wisteria"),
    BLOOMLIGHT("bloomlight"),
    BLOOMDARK("bloomdark");

    companion object {
        fun fromStorage(value: String?): ThemePreference =
            entries.firstOrNull { it.storageKey == value } ?: LIGHT

        /**
         * Theme for an install that never stored one. Fresh installs start on
         * Bloom Light; installs that finished onboarding before Bloom Light
         * existed keep the Grove Light they have always seen.
         */
        fun defaultFor(onboardingDone: Boolean): ThemePreference =
            if (onboardingDone) LIGHT else BLOOMLIGHT
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
    MARK_DONE("mark_done", "Mark as Done"),

    /** Clock in on the row's heading, or out when its clock runs (the Emacs agenda's `I` / `O`). */
    CLOCK("clock", "Clock In / Out");

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
