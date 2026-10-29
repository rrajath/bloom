package com.rrajath.grove.ui.newbadge

/**
 * One newly-shipped part of the app that should carry a "NEW" badge on every
 * navigational element leading to it, until the user reaches it.
 *
 * The badge shows only for installs that were already on an *older* build than
 * [since] — i.e. app updates, never fresh installs (see [NewBadgeState]).
 *
 * @param id stable identifier; also the token stored in
 *   [com.rrajath.grove.settings.GroveSettings.seenNewFeatures].
 * @param since the `versionCode` this feature shipped in (the numeric form of
 *   `versionName`, `MAJOR*10000 + MINOR*100 + PATCH` — see CHANGELOG.md).
 * @param anchors every anchor key that should show the badge (drawer item,
 *   settings row, the destination itself, …).
 * @param destination the anchor whose appearance on screen retires the feature:
 *   once the user reaches it, the badge clears from every anchor at once.
 */
data class NewFeature(
    val id: String,
    val since: Int,
    val anchors: Set<String>,
    val destination: String,
)

/**
 * Anchor keys a [NewDot] can attach to. Plain strings so any composable — even
 * a deep one that knows nothing about the feature registry — can render a dot
 * for one.
 */
object NewAnchors {
    const val DRAWER_SETTINGS = "drawer.settings"
    const val SETTINGS_TIPS = "settings.tips"

    /** The Settings hub row leading to the Look and Feel page. */
    const val SETTINGS_APPEARANCE = "settings.appearance"

    /** The "Theme" picker block inside Settings § Look and Feel. */
    const val SETTINGS_APPEARANCE_THEME = "settings.appearance.theme"

    /** The app-wide "Text size" block inside Settings § Look and Feel. */
    const val SETTINGS_APPEARANCE_TEXT_SIZE = "settings.appearance.textSize"

    /** The Settings hub row leading to the Notes page. */
    const val SETTINGS_NOTES = "settings.notes"

    /** The "New note cursor" row inside Settings § Notes. */
    const val SETTINGS_NOTES_NEW_NOTE_CURSOR = "settings.notes.newNoteCursor"

    /** The "Auto-save notes" toggle inside Settings § Notes. */
    const val SETTINGS_NOTES_AUTO_SAVE = "settings.notes.autoSave"

    /** The Settings hub row leading to the Capture Templates page. */
    const val SETTINGS_CAPTURE_TEMPLATES = "settings.captureTemplates"

    /** The "Capture link" section on a template's editor (bloom:// link + Add to Homescreen). */
    const val SETTINGS_CAPTURE_TEMPLATES_LINK = "settings.captureTemplates.captureLink"

    /** The Settings hub row leading to the Reminders page. */
    const val SETTINGS_REMINDERS = "settings.reminders"

    /** The "Notify for tasks without a time" toggle inside Settings § Reminders. */
    const val SETTINGS_REMINDERS_UNTIMED = "settings.reminders.untimed"

    /** The Settings hub row leading to the Sharing page. */
    const val SETTINGS_SHARING = "settings.sharing"

    /** The "Fetch link titles" toggle inside Settings § Sharing. */
    const val SETTINGS_SHARING_FETCH_TITLES = "settings.sharing.fetchTitles"

    /** The Settings hub row leading to the Widget page. */
    const val SETTINGS_WIDGET = "settings.widget"

    /** The Widget settings page itself (home-screen Agenda ledger widget). */
    const val SETTINGS_WIDGET_PAGE = "settings.widget.page"

    /** The "Quick add target" row inside Settings § Widget. */
    const val SETTINGS_WIDGET_QUICK_ADD_TARGET = "settings.widget.quickAddTarget"

    /** The note editor's metadata FAB (the "☰" button, bottom-right of the Read
     *  and Edit note screens). A feature reached through it lists this so the
     *  button carries a corner dot until the feature is seen. */
    const val TOPBAR_MENU = "topbar.menu"

    /** The ACTIVE tab in the tabbed planning-dates editor (bare active timestamps / events). */
    const val PLANNING_DATES_ACTIVE = "planningDates.active"

    /** The Outline's `⋮` overflow menu glyph. */
    const val OUTLINE_MENU = "outline.menu"

    /** The Outline overflow menu's "View file" item; retired on reaching the whole-file view. */
    const val OUTLINE_VIEW_FILE = "outline.viewFile"

    /** The Settings hub row leading to the Roam Features page. */
    const val SETTINGS_ROAM = "settings.roam"

    /** The "Open small files as one note" toggle inside Settings § Roam Features. */
    const val SETTINGS_ROAM_WHOLE_FILE = "settings.roam.wholeFile"

    /** The Settings hub row leading to the Notebooks page. */
    const val SETTINGS_NOTEBOOKS = "settings.notebooks"

    /** The "Ignore list" block inside Settings § Notebooks. */
    const val SETTINGS_NOTEBOOKS_IGNORE_LIST = "settings.notebooks.ignoreList"

    /** A Tips & Tricks section, keyed by its [TipGroup] id. */
    fun tipsGroup(id: String) = "tips.group.$id"

    /** A single Tips & Tricks row, keyed by its [TipGroup] id and the tip's own id. */
    fun tipsItem(groupId: String, tipId: String) = "tips.item.$groupId.$tipId"
}

/**
 * The registry of features that currently carry a NEW badge. Add an entry when a
 * release ships something worth pointing at; drop it a release or two later, once
 * every updating user has had a chance to see it.
 */
val NEW_FEATURES: List<NewFeature> = listOf(
    NewFeature(
        // The "Links" group is new to Tips & Tricks; point updating users at it.
        // `since` is the versionCode of the release that ships this framework —
        // bump it to match `gradle.properties` versionName when cutting the release.
        id = "tips-links-group",
        since = 10300,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("links"),
        ),
        destination = NewAnchors.tipsGroup("links"),
    ),
    NewFeature(
        // Settings § Notes gained a "New note cursor" lever (heading vs body).
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "notes-new-note-cursor",
        since = 10400,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_NOTES,
            NewAnchors.SETTINGS_NOTES_NEW_NOTE_CURSOR,
        ),
        destination = NewAnchors.SETTINGS_NOTES_NEW_NOTE_CURSOR,
    ),
    NewFeature(
        // Settings § Look and Feel gained an app-wide "Text size" lever
        // (Small/Medium/Large) that scales every text in the app.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "app-text-size",
        since = 10400,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_APPEARANCE,
            NewAnchors.SETTINGS_APPEARANCE_TEXT_SIZE,
        ),
        destination = NewAnchors.SETTINGS_APPEARANCE_TEXT_SIZE,
    ),
    NewFeature(
        // Settings § Notes gained an "Auto-save notes" switch. Off means an editor
        // never writes on its own: no 5s idle save, and switching to Read no longer
        // saves either (Read renders the unsaved buffer instead).
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "notes-auto-save",
        since = 10500,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_NOTES,
            NewAnchors.SETTINGS_NOTES_AUTO_SAVE,
        ),
        destination = NewAnchors.SETTINGS_NOTES_AUTO_SAVE,
    ),
    NewFeature(
        // Read-mode checkboxes now toggle done on tap and in-progress on
        // long-press (the "Checklist states" setting is gone). Point updating
        // users at the Tips & Tricks entry that spells the gestures out.
        // `since` is the versionCode of the release that ships it.
        id = "checklist-gestures",
        since = 10400,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("checklists"),
        ),
        destination = NewAnchors.tipsGroup("checklists"),
    ),
    NewFeature(
        // A capture template's editor gained a "Capture link" section: a live
        // bloom://capture/<slug> deep link (tap to copy) plus an "Add to
        // Homescreen" button that pins a shortcut straight to that template.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "template-capture-link",
        since = 10500,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_CAPTURE_TEMPLATES,
            NewAnchors.SETTINGS_CAPTURE_TEMPLATES_LINK,
        ),
        destination = NewAnchors.SETTINGS_CAPTURE_TEMPLATES_LINK,
    ),
    NewFeature(
        // Settings gained a dedicated Widget page: Filename/Tags/Priority toggles,
        // a Font size lever, and a live preview for the home-screen Agenda ledger
        // widget.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "agenda-widget-settings",
        since = 10500,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_WIDGET,
            NewAnchors.SETTINGS_WIDGET_PAGE,
        ),
        destination = NewAnchors.SETTINGS_WIDGET_PAGE,
    ),
    NewFeature(
        // Bare active timestamps: a heading can now carry plain `<date>` event
        // stamps (single or a range), managed on the new ACTIVE tab of the
        // planning-dates editor. They show on the agenda, in read mode / outline
        // as a violet dot chip, are searchable with `a.` filters, and fire
        // reminders like SCHEDULED/DEADLINE.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "bare-active-timestamps",
        since = 10600,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.PLANNING_DATES_ACTIVE,
        ),
        destination = NewAnchors.PLANNING_DATES_ACTIVE,
    ),
    NewFeature(
        // Settings § Reminders gained a "Notify for tasks without a time" toggle:
        // a separate notification for every SCHEDULED/DEADLINE/active timestamp
        // that lands on a day with no time of day, fired at the reminder time.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "reminders-notify-untimed",
        since = 10600,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_REMINDERS,
            NewAnchors.SETTINGS_REMINDERS_UNTIMED,
        ),
        destination = NewAnchors.SETTINGS_REMINDERS_UNTIMED,
    ),
    NewFeature(
        // Long-pressing the toolbar's clock button now opens the full date/time
        // picker (prefilled with right now) instead of just adding a time to
        // today's date.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "timestamp-longpress-picker",
        since = 10601,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("writing"),
            NewAnchors.tipsItem("writing", "date"),
        ),
        destination = NewAnchors.tipsItem("writing", "date"),
    ),
    NewFeature(
        // Long-pressing the toolbar's link button now opens a full-height picker
        // with live search across the whole vault (files + headings), instead of
        // the old drill-down-only browser starting from the current file.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "link-picker-live-search",
        since = 10700,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("links"),
            NewAnchors.tipsItem("links", "link-picker"),
        ),
        destination = NewAnchors.tipsItem("links", "link-picker"),
    ),
    NewFeature(
        // The Outline overflow menu gained "View file": the whole file (preface,
        // intro, every heading) as one Read/Edit note, for files under the line limit.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "outline-view-file",
        since = 10700,
        anchors = setOf(
            NewAnchors.OUTLINE_MENU,
            NewAnchors.OUTLINE_VIEW_FILE,
        ),
        destination = NewAnchors.OUTLINE_VIEW_FILE,
    ),
    NewFeature(
        // Settings § Notebooks gained an Ignore list: files, folders and patterns
        // to skip entirely, never scanned or indexed.
        // `since` is the versionCode of the release that ships it — bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "notebooks-ignore-list",
        since = 10700,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_NOTEBOOKS,
            NewAnchors.SETTINGS_NOTEBOOKS_IGNORE_LIST,
        ),
        destination = NewAnchors.SETTINGS_NOTEBOOKS_IGNORE_LIST,
    ),
    NewFeature(
        // Editors offer a chip for `<q` / `<e` / `<s` (any case) that swaps the
        // shorthand for a quote/example/src block. Points at its Tips & Tricks entry.
        // `since` is the versionCode of the release that ships it: bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "block-shorthand-chips",
        since = 10800,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("writing"),
            NewAnchors.tipsItem("writing", "block-shorthand"),
        ),
        destination = NewAnchors.tipsItem("writing", "block-shorthand"),
    ),
    NewFeature(
        // Editors offer a chip for `:PRO` / `:LOG` (any case) that swaps the
        // shorthand for a :PROPERTIES: / :LOGBOOK: drawer. Points at its Tips & Tricks entry.
        // `since` is the versionCode of the release that ships it: bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "drawer-shorthand-chips",
        since = 10800,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("writing"),
            NewAnchors.tipsItem("writing", "drawer-shorthand"),
        ),
        destination = NewAnchors.tipsItem("writing", "drawer-shorthand"),
    ),
    NewFeature(
        // Archive locations accept `%s` for the current file name, as in Emacs.
        // Points at the Tips & Tricks entry that explains ARCHIVE / #+ARCHIVE:.
        // `since` is the versionCode of the release that ships it: bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "archive-location-tip",
        since = 10800,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_TIPS,
            NewAnchors.tipsGroup("capture-automation"),
            NewAnchors.tipsItem("capture-automation", "archive-location"),
        ),
        destination = NewAnchors.tipsItem("capture-automation", "archive-location"),
    ),
    NewFeature(
        // Settings § Sharing gained a "Fetch link titles" switch (on by default):
        // off means a shared link is saved without any network request.
        // `since` is the versionCode of the release that ships it: bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "sharing-fetch-link-titles",
        since = 10801,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_SHARING,
            NewAnchors.SETTINGS_SHARING_FETCH_TITLES,
        ),
        destination = NewAnchors.SETTINGS_SHARING_FETCH_TITLES,
    ),
    NewFeature(
        // Settings § Widget gained a "Quick add target" row: the widget's + now
        // saves to its own file or heading instead of the share target.
        // `since` is the versionCode of the release that ships it: bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "widget-quick-add-target",
        since = 11000,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_WIDGET,
            NewAnchors.SETTINGS_WIDGET_QUICK_ADD_TARGET,
        ),
        destination = NewAnchors.SETTINGS_WIDGET_QUICK_ADD_TARGET,
    ),
    NewFeature(
        // Settings § Look and Feel's theme picker gained Bloom Light and Bloom Dark.
        // `since` is the versionCode of the release that ships it: bump it to
        // match `gradle.properties` versionName when cutting the release.
        id = "bloom-themes",
        since = 11100,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_APPEARANCE,
            NewAnchors.SETTINGS_APPEARANCE_THEME,
        ),
        destination = NewAnchors.SETTINGS_APPEARANCE_THEME,
    ),
    NewFeature(
        // Settings § Look and Feel gained a Light/Dark/System theme mode with a
        // light and a dark theme slot. `since` is the versionCode of the release
        // that ships it: bump it to match `gradle.properties` versionName when
        // cutting the release.
        id = "theme-mode",
        since = 11200,
        anchors = setOf(
            NewAnchors.TOPBAR_MENU,
            NewAnchors.DRAWER_SETTINGS,
            NewAnchors.SETTINGS_APPEARANCE,
            NewAnchors.SETTINGS_APPEARANCE_THEME,
        ),
        destination = NewAnchors.SETTINGS_APPEARANCE_THEME,
    ),
)
