# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

**Versioning:** `versionName` is the single source of truth. It's a
manually-controlled SemVer string (`major.minor.patch`) set in the
`versionName` key in `gradle.properties`: major = breaking change, minor =
new feature, patch = fix/tweak. `versionCode`, stored next to it so F-Droid
can read it, must equal `MAJOR*10000 + MINOR*100 + PATCH` (so `1.2.3` →
`10203`; keep minor and patch each within 0-99), and the build fails if it
doesn't. `scripts/release.sh` bumps both. CI reads `versionName` via
`./gradlew -q printVersionName` and never bumps it.

Two eras of historical entries below use different schemes: entries titled
`1.0.<N>` below the `1.0.0` line predate mid-2026, when `versionName` was an
auto `1.0.<commit-count>` string; entries between `1.0.0` and `1.0.2` carry a
`(build N)` suffix from a later era when `versionCode` was still the git
commit count. Both are frozen as they shipped. Current entries are just
`## [<version>] - <date>`.

Every entry below still corresponds 1:1 to a real GitHub Release.

The three entries marked *(local build, no GitHub Release)* predate commit 54,
which is when the release workflow was first added; those changes shipped
locally but nothing was ever tagged or published for them.

**Cutting a release is manual.** Add your changes under `## [Unreleased]` as
you go (that part still takes a human; nobody else knows what the change was
for). Every push (any branch) and PR runs the unit tests and builds the debug
APK, uploaded as a CI artifact; a push to `main` additionally builds the
release APK (running R8 and the signing config, so release-only breakage is
caught at merge time). Nothing is tagged, bundled, or published to Releases
from an ordinary push by itself, so several pushes with unrelated fixes can
land before you choose to ship one.

To cut a release, run `scripts/release.sh 1.2.0` locally. It bumps
`versionName` in `gradle.properties`, archives `## [Unreleased]` as
`## [1.2.0] - <date>` under a fresh empty `## [Unreleased]`, commits both as
`Release v1.2.0`, tags that commit `v1.2.0`, and moves `main` to it. Then push
`main` and the tag (the script prints both commands). The tag push builds and
publishes a GitHub Release with both APKs and the AAB, using the
`## [1.2.0]` section as the release notes.

CI never edits this file: it's bundled into the APK for the What's New modal,
so the tagged commit has to already contain the release heading. F-Droid
rebuilds from the tag and compares its APK with the published one, and a
heading added only on the CI runner breaks that comparison. A tag whose
commit lacks a matching `versionName` and `## [<version>]` heading fails the
run. Manually running the "Build & Release" workflow against `main` publishes
`v<versionName>` only if that heading exists (e.g. to retry a failed tag
build); otherwise it just builds and tests.

Re-running against the same commit/tag re-uses the same tag and just
re-uploads the APKs to the existing release instead of failing.

## [Unreleased]

### Added
- Outline's Move & indent bar can act on several headings at once: tap rows to add them to the selection.
- Its ☰ button opens the metadata sheet to change state, priority, tags, dates, notes, or org-ids on every selected heading, or refile them all at once.

### Changed
- When a heading can't be demoted in Outline, the message now explains why: there's no heading above to go under.

### Fixed
- Moving, promoting, or demoting a heading in Outline no longer unfolds collapsed headings.

## [1.11.0] - 2026-10-19

### Added
- `bloom://` deep links; existing `grove://` links and pinned shortcuts keep working.
- Bloom Light and Bloom Dark themes, each with a matching launcher icon. New installs start on Bloom Light.

### Changed
- Grove is now called Bloom. Same app, same notes, same settings; nothing to migrate.
- The default app icon, Agenda widget icon and Capture widget now use Bloom Light colors.

## [1.10.0] - 2026-10-15

### Added
- Pinned notebooks and folders can be reordered with Move up and Move down in their long-press menu.
- Agenda: swipe left or right on empty space to switch between Today and Upcoming.
- Settings › Widget: pick the file or heading where the Agenda widget's + saves quick tasks.

### Changed
- The Agenda widget's quick-add shows its target as a label instead of a file menu.
- Marking a task done now logs the state change at the top of its LOGBOOK.
- Read mode shows a heading's CLOSED date as a muted check chip.

## [1.9.0] - 2026-10-04

### Added
- Marking a recurring task done now shows its next occurrence date in the snackbar.
- Search supports nested brackets, `.( … )` negation, `AND`, `it.` keyword types, date comparisons, and Orgzly sort keys.
- Search accepts lowercase `and`/`or`, quoted values, `e.` events, `ps.` set priority, and Orgzly day aliases.
- Search supports `now` and hour offsets with time-of-day matching, plus `Ny` year offsets.
- `ad.N` searches now show results grouped by day, including events, like Orgzly's agenda.
- Search results: long swipe right marks a task done; a Reveal cell shows it in its notebook.
- Settings › Reminders now flags missing exact-alarm or notification access, with an Allow button to fix it.
- A one-time prompt asks for Alarms & reminders access so reminders arrive on time.

### Changed
- README now shows build, release, F-Droid, downloads, license, platform, stack, activity, and docs badges.
- Refreshed README and store screenshots; README now states the correct minimum Android version (6.0).
- Date filters follow Orgzly defaults: `s.today` now includes overdue. Saved searches are migrated to keep their meaning.
- Plain search text also matches a heading's own tags; filter-only searches sort by notebook, priority, then date.
- Editor suggestion strip restyled with outlined chips; Capture's Save now fits inside it, aligned with the Read/Edit toggle.
- While typing a note, the menu button smoothly shrinks into the suggestion strip, like Capture's Save.
- Agenda widget: tapping the "Agenda" title opens the Agenda screen; the app icon opens the app.
- Swipe cells: Schedule now sits at the edge with Reveal beside it; Agenda's Schedule swipe gains Reveal.
- Long swipes on Search and Agenda rows fade the other cell and flood the chosen action's color.
- Search result swipe rows now have rounded corners, matching Agenda.
- Outline swipe rows now have rounded corners, matching Search results.

### Fixed
- Flinging up in the capture or note editor no longer snaps back to the cursor near the top.
- Editors keep the line you're typing two lines above the suggestion strip, which now hides text scrolling under it.
- The daily note editor also keeps the line you're typing two lines above the suggestion strip.
- On Android 6 to 8.1, the dates picker and the note metadata sheet no longer opens with the keyboard covering its tabs.
- The note metadata sheet now opens fully, so its action row is never hidden behind the navigation bar.
- Search result date pills now show the scheduled or deadline time, e.g. "today 23:00".
- The note and Capture editors no longer leave a blank gap above the suggestion strip.
- Search's Operators card now has matching top and bottom padding.
- Reminders switch to exact timing once Alarms & reminders access is granted, instead of staying up to an hour late.

## [1.8.1] - 2026-09-29

### Added
- Settings → Sharing gained a "Fetch link titles" switch; off makes no network request when sharing links.
- Archiving adds Emacs's ARCHIVE_TIME, ARCHIVE_FILE, ARCHIVE_OLPATH, ARCHIVE_CATEGORY and ARCHIVE_TODO properties.
- Every capture template now shows where the note lands: file, heading path, and placement.

### Changed
- Favoriting a heading now adds an ID property instead of a CUSTOM_ID.
- Capture templates target a heading by ID; existing CUSTOM_ID targets keep working.
- Under-heading templates without a leading heading add content to that heading.

### Fixed
- The capture template name field now capitalizes the first letter of sentences.
- Headings with both ID and CUSTOM_ID now show as favorited and aren't favorited twice.
- Saving an edited note no longer removes the blank line before the next heading.
- Setting dates, marking done, and adding properties or logbook entries keep the file's existing indentation.
- Maestro flow 11 now types a heading for each new note, since blank headings can't be saved.
- Maestro flow 10 now expands the LOGBOOK drawer and checks the added note's text.
- Hourly repeaters: `.+Nh` restarts from completion time, `++Nh` catches up past now, and times roll past midnight.

## [1.8.0] - 2026-09-25

### Added
- A smart suggestions strip that helps inserts some drawers and blocks while typing
- Archive locations support `%s` for the current file name, as in Emacs; see Tips & Tricks.
- Typing `<q`, `<e`, `<s`, `:PRO` or `:LOG` (any case) offers a chip inserting that block or drawer.
- Tips & Tricks explains the block and drawer shorthand chips.
- A NEW dot marks the "Choose where a file archives to" tip.

### Changed
- The editor suggestion strip has a hairline top border, separating it from the text above.
- Read/Edit mode toggles now show eye and pencil icons instead of text labels, and shrink to fit them.
- Read/Edit toggle is one shared component; tapping anywhere on it switches mode.
- Whole-file Edit-to-Read no longer saves; Read shows unsaved text and leaving asks to save.
- Search text matches show the matched line with ten words of context; only heading results swipe.
- Capture editor parses the draft once per edit instead of up to three times.

### Fixed
- Edit screens no longer leave a blank strip under the top bar; scrolled text reaches it like Read mode.
- Search file headers show folder and match count on a second line; long file names no longer break layout.
- Vault.save() now creates missing files before writing (required by SAF backend).
- Refile archive location now honors the file-level ARCHIVE property before Settings; archive files keep their exact name.
- The Settings archive location is ignored while auto-archive is off.

## [1.7.1] - 2026-09-22

### Fixed
- Remove stale new feature dot

## [1.7.0] - 2026-09-22

### Added
- Add a Notebooks settings Ignore list to exclude files, folders, and patterns from indexing and sync.
- Added "View file" to the Outline menu: read or edit the whole file as one note (files up to 1,000 lines).

### Changed
- The link picker now supports live search, opens full height, and links leaf headings on tap.
- Tips & Tricks now describes the link picker's live search instead of the old drill-down browser.
- Whole-file Read/Edit header now scrolls long file names on one line and compacts folders to their first letter. 
- Lowered minSdk to Android 6.0 (API 23), widening device support; added API-level guards where needed.

### Fixed
- Fixed missing scroll-to-top/bottom buttons in the Notebooks folder drill-down view.
- Fixed the formatting toolbar staying visible with keyboard closed, and gapped above it when open, in the whole-file editor.
- Fixed done items in Search results not showing faded, crossed-out titles like Outline mode.
- Fixed a numbered sub-list nested under a bullet not restarting its numbering at 1 in Read mode.
- Fixed `+strikethrough+` text not rendering with a line-through style in Read mode.
- Fixed a stale "Imported from .orgzlyignore" status persisting even with no such file in the vault.
- Fixed the keyboard covering the Ignore list field; it now auto-scrolls into view when focused.
- Fixed reminders stuck behind a stale notification-permission banner on Android 12 and below.

## [1.6.3] - 2026-09-17

### Added
- Added a "Generate org-id" action to the metadata sheet, writing a UUID into the heading's :ID: property.

### Changed
- Notebook and note opens now parse off the main thread; Outline/Read start pre-collapsed instead of expanding then collapsing.
- Outline swipes, checkboxes, and other single-file edits now sync via a targeted reindex instead of a full vault scan.
- Folder expand/collapse in Notebooks now responds instantly instead of waiting on a settings write.
- Reduced scroll-time recomposition in Outline, Agenda, Notebooks, Read, and org table rows (swipe panels, favorite lookups, date formatting, row dividers).
- Reduced settings-write and keystroke fan-out in navigation, note editing, theming, Agenda, and the widget.
- Sync passes now index files concurrently, batch their log writes, and skip redundant parses on rename, delete, and reindex.

### Fixed
- Fixed the metadata sheet showing "Favorited" instead of "Unfavorite" as the toggle-off label.
- Sync-conflict notifications now fire only when conflicts first appear, not on every subsequent sync.
- Fixed a changelog unit test that failed on every version bump, blocking CI's release build.

## [1.6.2] - 2026-09-13

### Added
- Added an agenda lever (off by default) showing icon-styled scheduled/active-timestamp chips next to deadline chips on rows.
- Agenda and the widget now show a done button on keyword-less repeating headings, advancing the date like notifications already did.

### Changed
- Long-pressing the editor toolbar's clock icon now opens a date/time picker instead of inserting immediately.
- Bare active timestamps are now written right below the heading, above its
  properties drawer, instead of below it; older notes still parse either way.

### Fixed
- Fixed reminders for repeating bare timestamps missing Complete/Reschedule
  actions; Complete now advances the date instead of no-opping.
- Fixed `ad.N` search filter not excluding undated tasks (e.g. `ad.7 i.todo`).
- Fixed reminder task/event wording and buttons to follow the heading's TODO
  keyword instead of which timestamp field (SCHEDULED/DEADLINE/active) it used.
- Fixed a crash and duplicate row when a heading was both scheduled/deadlined
  and carried a bare active timestamp for the same day in the agenda.
- Fixed sync conflict notifications covering non-.org files and re-firing
  every sync pass; now scoped to .org and updated in place.
- Fixed keyword-less repeating reminders leaving a spurious DONE logbook entry and LAST_REPEAT property when completed.

## [1.6.1] - 2026-09-11

### Fixed
- Fixed the "Notify for tasks without a time" NEW badge never clearing when
  reminders were disabled; visiting Settings › Reminders now always retires it.
- Fixed the v1.6.0 release shipping without its own What's New entry: the
  version was tagged before its CHANGELOG.md entry was committed.

## [1.6.0] - 2026-09-10

### Added
- Added a What's New screen in Settings › About: recent releases as cards, with
  an unseen-release dot on the row.
- Added a "Notify for tasks without a time" toggle to Settings › Reminders (off
  by default). When on, every SCHEDULED, DEADLINE, or active timestamp that
  falls on a day with no time of day fires its own notification at the reminder
  time, instead of only being counted in the daily digest. The digest keeps
  working alongside it, and a date that has already passed stays silent.
- Added support for bare active timestamps: a heading can now carry plain
  `<date>` event stamps (single or a `<a>--<b>` range) alongside SCHEDULED and
  DEADLINE, edited on a new ACTIVE tab in the tabbed planning-dates editor with
  long-press-drag for ranges. Events show on the Agenda on their exact day
  (never overdue), render as a violet dot chip in read mode and the outline,
  are searchable with `a.` date filters and an `o.active` sort, and fire
  reminders like the planning dates do (a timed event pings, a date-only one
  joins the daily digest).

### Changed
- Performance improvements.
- Cleared all Android lint warnings: KTX URI/bitmap helpers, a non-restricted
  back-stack check, capture-shortcut usage reporting, and other small fixes.
- In the planning-dates editor, tapping a start or end time field now opens the
  clock dial instead of a text field; the end field's dialog gains a "Clear"
  button. Free-text time entry stays available in the shorthand box.
- Removed the CI `macrobenchmark` job. Benchmark numbers off a shared-runner
  emulator are noise, and keeping the suite green there was a recurring
  maintenance cost (cold-start re-seed/re-index flakiness, the androidx EMULATOR
  guard). The `:macrobenchmark` module stays; run it locally on a real device
  (`./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest`), as the
  README now documents. The debug/benchmark test-vault seed is also now
  idempotent (a `.grove-seed` marker file) so repeated cold launches don't
  re-index ~560 files each time, and the benchmarks' "wait for the list" step is
  a hard assertion with a longer timeout. No release impact.

### Fixed
- Headings with no TODO keyword lost their mark-done button in the Agenda and
  widget, and now also appear in the ledger widget.
- Event rows under a day heading no longer repeat that day (e.g. "Today") in
  their metadata line.
- Fixed deleted notebooks still firing reminder notifications; their alarms are
  now cancelled on delete, sync, and app start.
- The navigation drawer's item list now scrolls when it overflows; the header
  stays pinned.
- Planning Dates: the Today/Tomorrow/weekend/next-week presets now show on
  every tab before a date is set, not only after.
- Planning Dates: evened out the SCHEDULED tab glyph size and removed the
  redundant "Not set" line.
- Fixed the What's New popup showing nothing for releases whose changelog
  bullets have no `### Added`/`### Fixed` heading.
- Settings toggles now animate smoothly. The switch thumb follows the tap
  immediately instead of snapping a few frames later once the setting had
  round-tripped through disk, and a group of settings rows now animates its
  height when a toggle reveals or hides a follow-up row.
- The Notebooks list lines its trailing icons up on one right edge: the top
  bar's sync-status icon and each folder row's expand or collapse chevron now
  sit in line with the notebook rows' pin icon instead of a few dp outside it.
- The time picker's clock dial now renders in the app's sans font instead of
  the read-mode serif, matching the rest of the app chrome.
- Fixed the tabbed planning-dates editor collapsing this heading's own dates
  for the tabs you are not on into the same small dot it uses for other notes'
  planned days. SCHEDULED, DEADLINE and ACTIVE dates you set this session now
  stay on the calendar as an outlined cell in their own colour when you switch
  to another tab.
- Fixed a bare `<date>` event stamp showing twice in read mode and the outline
  — once as plain text, once as the violet chip. The dedicated event line after
  a heading now renders only as the chip. Stamps written inline in a sentence
  are unchanged.
- Fixed the note editor's floating menu button covering the last lines of text.
  The editor now keeps a clear band at the bottom so text always scrolls above
  the button.
- Fixed a search result showing only the earliest bare active timestamp. Every
  active stamp on a heading now gets its own violet pill, each carrying its
  time of day (and range) when it has one, instead of just the first all-day
  one.
- Fixed the planning-dates editor crashing when you paged the calendar to
  another month and back. The day grid now renders as a fixed six-row shape, so
  changing months no longer reshapes the view tree in a way the UI framework
  could not apply.

## [1.5.0] - 2026-09-07

### Added
- Debug builds: Settings › Developer has a **Drill-down file count** field that
  overrides the folder drill-down threshold (default 20) for the session, so the
  large-folder drill-down view can be tested without creating 20+ files. Not
  saved; resets on restart.
- Settings § Notes has an **Auto-save notes** toggle (on by default). Turn it off
  and an editor never writes on its own: no 5-second idle save, and the file
  changes only when you tap the save icon or pick Save on the way out.
- A capture template's editor has a **Capture link** section: a live
  `grove://capture/<slug>` deep link (updates as you rename the template) you
  can tap to copy, plus an **Add to Homescreen** button that pins a shortcut
  straight into that template's capture editor.
- Settings has a new **Widget** page for the home-screen Agenda widget, holding
  everything that shapes it: background transparency, days ahead, show/hide
  Filename (nested folders contract to initials, e.g. `W/P/notes.org`), Tags,
  and Priority, and a Small/Medium/Large Font size. A live preview below the
  settings updates as you change them. Settings § Agenda now covers only the
  agenda-row swipe actions.

### Changed
- Settings § Notes is now split into labelled sections (Tasks, Display, New
  notes & editing, Archiving) instead of one long undivided list.
- The Notebooks home screen's floating Capture button is now a docked bottom
  bar: Search, an inline "+ Capture" pill, and New notebook, all within thumb
  reach. The top bar drops its "+" and search icons, and the bar stays put
  while you browse inside a folder. The bar is now a transparent floating
  capsule with no drop shadow, and the notebook list scrolls full-height
  behind it instead of stopping short above a reserved strip.
- The note metadata menu (state, priority, tags, planning, refile) moved from the
  top-bar hamburger to a floating button at the bottom-right of the Read and Edit
  note screens. The capture editor keeps its top-bar menu.
- Switching between Read and Edit no longer saves the note. Read mode renders
  your unsaved edits instead, folds its own changes (a checkbox tap, a metadata
  edit) back into them, and asks to save or discard when you leave the note.

### Fixed
- Read mode: checkbox/bullet markers in a list sat slightly below the center
  of their item text. The marker box is now sized to the exact first-line box
  and the item text uses a centered, untrimmed line height, so the glyph and
  the first line of text line up.
- Capture: saving a note while no sync folder is configured used to briefly
  report success and fire a spurious sync request; it now fails cleanly with
  "No sync folder configured" and touches nothing. Only reachable through code
  paths (a share-sheet capture on a fresh install); no user-visible change in
  normal use.
- The Read/Edit toggle in the note top bar and the metadata button at the
  bottom-right now sit on the same 24dp right margin as the back arrow and the
  note body, instead of pressing against the screen edge.
- `grove://capture/<id>` deep links now also resolve by the template's display
  name (case-insensitively) or its dash-separated slug, not just its internal
  id, and show a toast and close instead of a blank screen when nothing
  matches.
- Saving or dismissing a capture opened via a bare `grove://capture` link (no
  template id) now closes the app instead of revealing the Notebooks screen
  behind it, matching the fix already in place for `grove://capture/<id>`
  links.
- Read mode no longer splits a wrapped list item (e.g. after Emacs
  `fill-paragraph`/`M-q`) into a bullet plus a stray paragraph; its
  continuation lines now render as part of the same bullet.
- Completing a reminder for a repeating SCHEDULED/DEADLINE heading now
  advances its date and keeps it TODO, instead of marking it DONE outright.
- Read mode's own-heading tag row now shows the heading's own tags, matching
  what the metadata sheet edits. It previously showed inherited tags too, so
  removing a tag that a parent heading (or `#+FILETAGS:`) also carried looked
  like it hadn't worked even though it had.
- Tapping a task's radio circle in the home-screen Agenda widget now shows a
  circular tap ripple clipped to the ring, instead of a square highlight
  around it.

## [1.4.0] - 2026-09-04

### Added
- Settings § Look and Feel has a **Text size** lever (Small / Medium / Large)
  that scales all text in the app. The read and edit mode font-size levers stack
  on top of it for note content.
- Search now matches notebook **file names**, not just note text. A name match is
  listed first, above the content results, as a tappable row that opens its
  outline.
- The read-mode metadata sheet has a **★ Favorite** action that pins the note to
  the drawer's Favorites; tap again to unpin. Works on heading-less notes too.
- Standalone `#+` keyword lines in a body (`#+CAPTION:`, `#+NAME:`, and the like)
  now render as their own collapsible block in Read mode. Consecutive lines fold
  into one "KEYWORDS" block; a lone line is named after its keyword.
  Double-tapping opens the scoped editor.
- Org tables render as a real grid in Read mode: header row bolded and pinned
  while the body scrolls, wide tables scrolling sideways. The editor still shows
  the raw `| a | b |` text.
- Settings § Notes has a **New note cursor** lever: place the cursor on the body
  line (default) or in the heading after the `* `, so a new note opens ready for
  its title.

### Changed
- Checklist boxes in Read mode now use two gestures instead of a tap cycle. Tap
  toggles done (`[ ]`/`[-]` ↔ `[X]`); tap and hold marks in-progress `[-]` and
  holding again clears it. The **Checklist states** setting is gone; both marks
  are always available.
- The formatting toolbar's link button now uses a URL you already copied. Select
  text, tap **[[]]**, and a link on the clipboard becomes the target with your
  text as the label. Otherwise it drops the `https://` scaffold as before. The
  clipboard is only read when text is selected.
- The metadata sheet's tag list shows a "tap an existing tag to remove it" hint
  whenever at least one tag is set.
- The Notebooks list stays responsive during a sync. The folder tree is now
  regrouped and re-sorted off the main thread, and only when the vault or your
  settings change. Folder rows also skip redrawing when unchanged. More
  noticeable in larger vaults.
- Settings are now parsed once and shared across the app instead of being rebuilt
  separately for every screen and background job that observes them (~40). Most
  noticeable when expanding and collapsing folders in the Notebooks tree.
- The Agenda no longer re-buckets its whole list when an unrelated preference
  changes. It now reacts only to the eight settings it actually shows, not to
  things like folder expansion or notebook colour.
- Large org tables in Read mode open without a stall. The body is now a lazy list
  (only visible rows are built), and column widths are measured from one cell per
  column instead of every cell.
- The Agenda, the ledger widget, and the planning-dates calendar now read a
  trimmed set of columns instead of the full text of every scheduled note.
  Matters most inside the widget, where memory is tightest.

### Fixed
- The search results list now snaps back to the top on every keystroke, instead
  of keeping the previous scroll offset over a new, shorter result set.
- Read mode now keeps every line break inside a paragraph. A single newline used
  to collapse into a space; now each line stays on its own line. A blank line
  still starts a new paragraph.
- The metadata sheet's tag suggestion list now opens above the text field, so it
  no longer sits under the keyboard. It flips back downward only when the field
  is too close to the top of the screen.
- Capture-template monogram tiles now carry a dashed outline and a small "+"
  badge, matching the design prototype. Applies in the quick-capture sheet, the
  Capture Templates settings list, the template editor, and the icon-colour
  dialog.
- The home-screen Capture widget now draws the Grove asterisk from a vector
  instead of a "✱" text character, so the mark shows on devices whose system font
  lacks that glyph.
- Fixed a blank white screen when opening the Capture widget and pressing back
  twice quickly. The second press no longer pops past the start of the back
  stack.
- Updated Jetpack Glance to 1.2.0, which fixes the Capture widget redrawing
  itself as the Agenda widget (or vice versa) after either had been used.

## [1.3.0] - 2026-09-03

### Added
- Tapping an Org link in read mode now resolves it and navigates in-app.
  `[[*Heading]]` and `[[Heading]]` (no scheme) jump to the read view of a
  matching heading in the same file. `[[#custom-id]]` and `[[id:UUID]]` jump to
  the heading with that `CUSTOM_ID` / `ID` anywhere in the vault; an `[[id:UUID]]`
  whose id is a file's file-level `:ID:` (in the property drawer above the first
  heading) opens that file's outline. These lookups are index-backed, so they
  stay fast in a vault with thousands of files.
  `[[file:name.org::#custom-id]]` opens that heading in the named file (falling
  back to the file's outline if only the heading is missing), and a bare
  `[[file:name.org]]` opens the file's outline. `http`/`mailto`/`tel` and other
  external schemes are handed to the OS. A link that resolves to nothing shows a
  "not found" toast.
- **The link button** in the note editor's formatting toolbar now does more.
  Tapping it with **text selected** inserts `[[https://][<selection>]]` with the
  cursor on the scheme, and folds the scheme if you then paste a URL that already
  has one; tapping it with nothing selected still inserts the
  `[[link][description]]` template. **Long-pressing** it opens a drill-down
  picker (the refile sheet, minus refile/archive). At a file's top level, **Link
  to this file** writes `[[file:path.org]]`; drill into a heading and **Link to
  this heading** writes `[[*Heading]]` (same file) or
  `[[file:path.org::*Heading]]` (another file). With text selected it becomes the
  link description; with nothing selected the link is named after its target (the
  heading title, or the file's `#+TITLE:` / file name). If the picked file or
  heading carries an `:ID:` (or a heading `:CUSTOM_ID:`), a prompt offers to use
  that instead, since an id link survives a rename or move.
- `#+BEGIN_x … #+END_x` blocks in a note body now render in read mode as
  collapsible sections styled like the `:PROPERTIES:` / `:LOGBOOK:` drawers,
  headed by the block type (`QUOTE`, `SRC`, `EXAMPLE`, `VERSE`, `CENTER`, a
  custom name, …). `#+BEGIN_SRC` shows its language next to the `SRC` heading.
  Any leading affiliated-keyword lines (`#+ATTR_LATEX:`, `#+CAPTION:`, `#+NAME:`,
  `#+HEADER:`) directly above a block are folded into its section. Blocks open
  expanded (they are note content, not metadata). Double-tapping a block's body
  opens an editor scoped to just that block, markers included; saving splices
  only that block back into the file. `#+BEGIN_EXAMPLE` blocks, previously drawn
  as a plain code box, now use this style too.
- **Settings › Notebooks › Notebooks sort order** sets how the notebooks list is
  ordered: **Alphabetical** or **Last modified**, with a separate
  **Ascending / Descending** toggle. It applies at every level — top-level
  notebooks, folders (a folder sorts by its most recently edited note), notes
  inside folders, the drill-down view and flat mode. Pinned notebooks and
  folders always keep their pinned order. Default is Alphabetical, ascending.
- **Settings › Notes › Font size** now has separate **Read mode** and **Edit
  mode** controls (Small / Medium / Large each). The read-mode size scales the
  rendered note, the edit-mode size scales the editor text, and they are
  independent of each other. Both default to Medium.
- The **New notebook** dialog now validates the name as you type: the **Create**
  button stays disabled for a name that ends in a slash or contains a reserved
  filesystem character (`\ : * ? " < > |`). A helper line spells out that a slash
  nests the notebook in folders, with the example `work/ideas`. The
  **Rename notebook** dialog validates the same way.

### Changed
- The **New notebook** and **Rename notebook** dialogs now show a fixed `.org`
  next to the name you are typing, so it is clear the extension is added for you.
  The field holds just the stem; nested paths like `work/ideas` still work.
- The Outline row swipe panel's **Fav** action now reflects state: on a heading
  that is already a favorite it shows a filled star and reads **Unfav**.
- IDs that Grove writes into a note (`:ID:` on a new or shared note, and the
  `:CUSTOM_ID:` added when you favorite a heading) are now upper-case, matching
  the form Emacs `org-id` uses. Existing IDs in the vault are left as they are.
- Deleting a notebook or a folder is now permanent. Android's Storage Access
  Framework has no system trash for files in a folder you granted, so Grove no
  longer renames the file to `<name>.org.trash` (those copies just piled up and
  Grove could not read them back). Both actions now show a confirmation dialog
  first, spelling out how many notes go and that it "cannot be undone" (recovery,
  if the vault syncs with Syncthing, is via its file versioning). Any folder left
  empty by a delete or a rename is removed from disk.
- Settings has a new **Notebooks** page, directly under **Look and Feel**. The
  "Show file icons in notebooks", "Flatten folders" and "Notebook display name"
  settings moved there from Look and Feel; their values are unchanged.
- The single app-wide **Font size** setting moved off **Look and Feel** and was
  replaced by the per-mode read/edit controls on the **Notes** page. It used to
  scale the whole Material typography (in practice, mostly screen titles); the
  app's own chrome now stays at a fixed size and only the note / editor scale.
  An existing font-size choice carries over to both new controls on upgrade.
- Flatten-folders mode now orders its single list purely by the Notebooks sort
  order rather than always grouping files by folder path first.
- A small **NEW** badge now appears next to a navigational element that leads to
  something added in a recent update, following the trail all the way down (for
  example the nav-drawer **Settings** item, the **Settings › Tips & Tricks** row,
  and the new Tips section itself). Reaching the new thing clears the badge from
  every spot at once. Badges only show after an app update, never on a fresh
  install. This release badges the new **Links** section in Tips & Tricks. Debug
  builds get a **Settings › Developer › Reset New badges** action to replay them.

### Fixed
- Status bar icons keep the right colour through a rotation. On a light theme
  they stayed dark on portrait but flipped to white when turned landscape (and
  stayed white on the way back); the bar appearance is now re-asserted on every
  configuration change, not just on a theme switch.
- An Outline heading with more than two lines of content now ends its preview
  with an ellipsis, so it is clear there is more text than the two lines shown.
- Rotating the screen no longer discards in-progress work. `MainActivity` now
  declares `android:configChanges` for orientation and every other runtime
  config, so a rotation (or a dark-mode / font-scale change) re-lays-out the UI
  in place instead of destroying and recreating the Activity. Unsaved note
  edits, a half-filled capture, an in-progress date edit, a template being
  written and scroll positions all survive the turn.
- Opening the app from a `grove://capture` link (capture shortcut, widget,
  notification), cancelling the capture, then rotating no longer re-opens the
  same capture sheet. The launch Intent is now treated as a one-shot: it is
  cleared once navigated and is not re-read after a process-death recreation.
- Creating a notebook with a name like `foo/` no longer makes a folder `foo`
  containing a stray file named `.org`. Typing a real nested path such as
  `foo/bar.org` or `foo/bar/baz.org` still creates the missing folders and the
  file inside them.
- Renaming a folder to the name of a folder you deleted earlier no longer fails
  with "a folder with that name already exists". The delete now removes the old
  folder from disk instead of leaving hidden `.org.trash` files behind in it.

## [1.2.0] - 2026-09-01

### Added
- Content that sits before a file's first heading (or a file with no headings
  at all, like an org-roam note) is no longer hidden. Grove calls it the file's
  **intro** ("preface" stays reserved for the `#+` keyword lines above it). The
  outline shows the intro in full above the headings as flowing body text with
  inline markup, inset so its left edge lines up with the outline's leaf bullets;
  tapping it opens it in read mode showing just that content, with a Read/Edit
  toggle. Edit opens an editor scoped to the intro alone: the `#+` keyword lines
  and the file's `:PROPERTIES:` drawer are not in the buffer, since each of those
  has its own double-tap editor. The intro is counted in the notebook's note total
  and is full-text searchable.
- In the intro's read-mode metadata sheet, picking a state, priority, tag,
  schedule/deadline or note first inserts a blank top-level heading above the
  content (so the content becomes its body), shows a toast, then applies the
  change and re-opens it as an ordinary note. Refile is hidden until the
  content has a heading.
- Settings › Help now has a **Tips & Tricks** page: a grouped, tap-to-expand
  list of shortcuts and gestures that the interface doesn't otherwise announce
  (viewing last sync/save times, autosave, inserting dates, cycling heading
  levels, double-tap-to-edit, in-place drawer editing, following and building
  links, linking by ID, swiping search results, changing saved queries,
  capturing from the home-screen icon, auto-archiving). Where a tip points at a
  specific control its description shows that control's actual icon inline,
  including the toolbar's `[[]]` link button; the "follow a link" tip shows a
  sample styled the way read mode paints a link (display only, tapping it goes
  nowhere), and bare link syntax in the copy (`https://`, `[[link]]`) is set in
  the editor's monospace link style. The top bar carries the outline view's
  fold/unfold icon to expand or collapse every tip at once.
- A file-level `:PROPERTIES:` drawer at the very top of an `.org` file (before
  the first heading) is now shown in the outline as a collapsed-by-default
  key/value section above the PREFACE box, styled the same as the `:PROPERTIES:`
  drawer on individual headings in read mode. It is gated by the same "Show
  property drawers" setting. Previously this drawer was dropped from the outline
  entirely.
- Double-tapping a `:PROPERTIES:` or `:LOGBOOK:` drawer now opens an editor
  scoped to just that drawer's raw text, the same way double-tapping the PREFACE
  section opens the preface editor. Works on the outline's file-level
  `:PROPERTIES:` drawer and on every heading's `:PROPERTIES:` / `:LOGBOOK:`
  drawer in read mode. Saving splices only that drawer back into the file.

### Changed
- Double-tapping a PREFACE or `:PROPERTIES:` key/value row now registers anywhere
  on the row, not only when the tap lands exactly on the key or value text.
- The PREFACE editor is now scoped to the file's `#+KEY:` lines instead of the
  whole preamble. It no longer opens with the file-level `:PROPERTIES:` drawer
  and the intro text in the buffer, both of which now have editors of their own,
  so the three regions can no longer overwrite each other. Saving splices only
  the keyword lines back, leaving the blank line before the intro or the first
  heading intact.
- Settings › Help › Report a bug now offers two actions: "Send report via email"
  (unchanged: copies the report to the clipboard and opens a mail-app chooser)
  and "Open an issue on GitHub" (copies the same report and opens the Grove
  issue tracker in the browser). The old "Copy as text instead" link is gone,
  replaced by a note that both buttons copy the report to the clipboard.

### Fixed
- A capture template's **Target file** (and the Settings › Sharing target file)
  can now point at a notebook in a subfolder. The field already suggested nested
  notebooks in its autocomplete, but picking one was rejected as containing an
  invalid `/`, leaving Save disabled. Forward-slash paths are now accepted (each
  segment validated, no leading/trailing slash, still must end in `.org`); a
  missing target file and its parent folders are created on first capture.
- Deleting a notebook whose trashed copy already exists no longer produces an
  awkward `name.org.trash-2` file. The collision counter now goes before the
  extension (`name-1.org.trash`, `name-2.org.trash`), keeping a clean
  `.org.trash` suffix on every trashed file.
- Creating or renaming a notebook or folder to a name that already exists is no
  longer a silent no-op. The dialog now stays open with your typed text and a
  toast explains the clash ("A notebook with that name already exists" / "A
  folder with that name already exists"). The duplicate check is
  case-insensitive, matching how the sync folder's filesystem treats names.

## [1.1.1] - 2026-08-30
- Agenda widget: a crowded meta line (date + inherited tags + filename) no longer wraps the trailing filename mid-word across several lines. When a row carries tags, the filename now moves to its own line below the rest of the strip; otherwise it stays inline. Individual chips truncate rather than wrap.

## [1.1.0] - 2026-08-29

### Added
- The vault can now contain subfolders. Grove reads `.org` files from nested
  directories, not just the top level, and skips dot-directories like
  Syncthing's `.stversions`.
- The Notebooks screen shows nested folders as an inline tree. Folder rows
  carry a colour-tinted icon tile, a recursive file/sub-folder count, and a
  warning glyph when a file inside has a sync conflict; tapping one expands it
  in place, with its contents drawn under an indent rail. A new top-bar button
  expands or collapses every folder at once, expansion is remembered per
  folder, and on a vault's first open any folder holding a scheduled or
  deadline task starts expanded. Pinned notebooks move to their own strip
  above the tree.
- A folder with more than 20 files opens as its own screen instead of
  expanding in place: a back arrow, a tappable breadcrumb back to the vault
  root, and a flat file list. Sub-folders inside drill deeper; the system back
  button steps back up one level at a time.
- Notebooks can be created into and moved between folders. The `＋` button in a
  drilled-in folder creates the new notebook there (and a typed
  `sub/dir/name.org` still makes the folders it needs); a new "Move to folder…"
  action on a notebook's long-press menu opens a picker that walks the folder
  tree, with a "New folder here" option. Moving a notebook keeps its icon
  colour and pinned position.
- Long-pressing a folder row opens a menu to pin it to the top, rename it,
  change its icon colour, or delete it. Pinning a folder moves it into the
  Pinned strip; renaming moves every file inside and carries their icon colours
  and pins across; deleting moves every file inside to the trash, recoverable
  from the synced folder.
- Settings → Look and Feel gains a "Flatten folders" toggle. With it on, the
  Notebooks screen hides folder rows and lists every note in one flat list,
  each showing its folder path as a subtitle; top-level notes are unchanged and
  the monogram letter still comes from the filename. The drill-down view and
  expand/collapse-all are hidden while flat, but a note can still be moved
  between folders from its long-press menu.

### Changed
- Expanding or collapsing a folder on the Notebooks screen now animates
  smoothly: the whole subtree grows or shrinks its height as one block and the
  rows below glide with it, instead of the rows popping in individually while
  the list jumps. The chevron flip is timed to match the same motion.
- The "Pinned" label above the pinned strip on the Notebooks screen is gone.
  The pin icon on each row already says what the strip is.
- Settings reshuffle: "Notebook display name" moved from Notes to Look and Feel
  (next to the other notebook-list options); "Default note mode", "Show
  preface", and "Show property drawers" moved from Look and Feel to Notes
  (with the other read-mode content settings). Export/import of settings is
  unaffected.
- The Pinned strip on the Notebooks screen now lists notebooks and folders in
  one chronological order — the order you pinned them — instead of always
  grouping folders above files. Pin a folder after a file and it stays below
  that file.
- Folder rows on the Notebooks screen now line their icon tile up with the file
  rows in the same column instead of sitting a caret-width to the right. The
  expand/collapse chevron moved to the right end of the row: a downward `▾` that
  flips when the folder is open, or a static `›` for a folder big enough to open
  as its own screen.
- The Read-mode breadcrumb now shows a nested note's full folder path
  (`projects/clients/acme.org › Heading`) instead of just the bare file name,
  and keeps the `.org` suffix to match the notebook list and the outline title.
- The "This notebook is empty" placeholder now shows the Grove asterisk mark
  (the five-spoke app icon, drawn in the same muted colour, no tile behind it)
  in place of the generic Unicode star.

### Fixed
- Pinning a folder no longer shows it twice on the Notebooks screen (once in
  the Pinned strip and again as a normal folder row). A pinned folder now
  appears only in the strip; its whole subtree is lifted out of the tree, the
  same way a pinned file already was.
- Tapping a pinned folder in the Pinned strip now expands it in place to show
  its subfolders and notes, instead of only flipping the chevron with nothing
  underneath. A pinned folder large enough to open as its own screen still
  drills in on tap, as before.
- With "Flatten folders" on, a note that is pinned only because its folder is
  pinned now shows the pin icon on its row. Previously those rows looked
  unpinned even though they were held at the top of the list.
- Notebook rows with a single note now read "1 note" instead of "1 notes".
  Same fix applied to the outline note count and the refile sheet's heading
  counts; the folder file/sub-folder counts already handled the singular.
- Pinning a notebook that lives inside a folder no longer pushes the
  vault-root notebook rows to the right. Top-level files now sit flush with the
  folder tiles; only files nested inside an expanded folder keep the indent.
- The debug build now shows "Grove Debug" everywhere the app name appears
  in-app (onboarding title, navigation drawer header, Settings about line, bug
  report email subject, and the share/file-open/settings-import toasts), not
  just on the launcher icon. These spots previously hardcoded "Grove" instead
  of reading the build's app name resource.

## [1.0.5] - 2026-08-28

### Fixed
- Read mode no longer hangs for a few seconds when opening a heading with a
  large or deeply-nested subtree, and scrolling such a note is smooth again.
  The note's sub-headings now render lazily instead of all at once, and a note
  with a very large subtree opens with its inner sections folded (tap a
  section's caret to expand it).

### Changed
- In Read mode, text selection now stays within a single section (heading +
  its body) rather than spanning the whole note.

## [1.0.4] - 2026-08-28

### Changed
- `versionCode` is now the numeric form of `versionName` (`MAJOR*10000 + MINOR*100 + PATCH`, so `1.2.3` becomes `10203`) instead of the git commit count. Keep `versionName`'s minor and patch segments within 0-99.
- GitHub Release titles are now just `v<version>` (e.g. `v1.0.4`); the `(build N)` suffix is gone, and newly archived CHANGELOG.md headings drop it too. The in-app What's New modal now derives each release's ordering key from its version heading.
- Settings' footer now reads just `Grove v<version>`; the parenthetical build number is dropped since it's now the same information as the version.
- The manual "Run workflow" release path tags `v<version>` instead of `v<version>-<commitcount>`. Re-dispatching a release without first bumping `versionName` now re-uploads assets to the existing release rather than creating a new tag.

## [1.0.3] - 2026-08-28 (build 316)

### Fixed
- Performance: state holders backing scroll offsets, swipe accumulators and "load more" counters on the auto-scroll helper, the planning-dates calendar, the search filter sheet and the capture-template editor now use the primitive `mutableIntStateOf`/`mutableFloatStateOf` instead of `mutableStateOf`, dropping an int/float autoboxing allocation on every update (Android Lint: `AutoboxingStateCreation`).
- Performance: the Agenda screen's lever-toggle knob animates with the lambda `Modifier.offset { }` overload, so its position updates run in the placement phase instead of triggering a full recomposition and relayout on every animation frame (Android Lint: `UseOfNonLambdaOffsetOverload`).
- Performance: the scroll-to-top/bottom jump buttons no longer recompose their host screen on every scrolled pixel. Edge state (`atTop`/`atBottom`) is now derived through `derivedStateOf`, and the scroll-activity visibility trackers watch position via `snapshotFlow` inside their effect rather than reading it in composition (Android Lint: `FrequentlyChangingValue`).
- Performance: backgrounding the app no longer runs 19 synchronous `PackageManager` IPC calls on the main thread to re-apply the launcher icon. The work is skipped entirely when the currently-enabled icon alias already matches the theme (the usual case), and when it does run it now runs off the main thread.
- Performance: saving a note from the editor reindexes just that one file instead of running a full-vault directory listing and revision diff. The cost of a save no longer grows with the number of files in the vault. The full sync still runs on its own triggers (foreground/background, periodic, continuous polling, manual refresh).
- Performance: overlapping sync triggers now coalesce. Grove's independent triggers (the continuous-mode poll, the periodic worker, foreground/background lifecycle, a manual refresh, a resolved conflict) used to each run a full sync pass back to back when they fired close together; they now funnel through one queue that collapses a burst into a single pass, with a short debounce so near-simultaneous triggers settle before the pass starts.
- Performance: Continuous sync mode's 10-second poll no longer runs a full vault re-index every tick. Each tick now takes a cheap directory listing and only starts a sync when a file was actually added, removed, renamed, or changed; an unchanged vault costs one listing and nothing else.
- Performance: the Search, Agenda and editor screens' state now uses immutable list types (`kotlinx.collections.immutable`) and stability annotations, so the Compose compiler marks `SearchUiState`, `AgendaUiState` and `EditorUiState` as stable and those screens can skip recomposition when their state hasn't changed.

## [1.0.2] - 2026-08-26 (build 304)

### Added
- Settings → Look and Feel has a new **Show file icons in notebooks** toggle (on by default). Turned off, the per-file monogram tile is dropped from every row of the notebooks list, leaving just the name and metadata line. The setting travels with Import/Export Settings.

### Changed
- Deleting a capture template (Settings → Capture Templates, the `✕` on a row) now asks for confirmation first, matching how deleting a note works. Previously the tap deleted the template immediately with no undo.
- docs-site: the buffer-bar wordmark next to `grove.org` is now the Grove app icon (an 18px squircle tile carrying the org asterisk, with separate light and dark variants) instead of a plain green `✳` glyph.
- docs-site: the Starlight docs-section header now shows the Grove app icon beside the "Grove" wordmark (it was plain text before), matching the landing page's buffer bar. Light and dark variants swap with the theme.
- docs-site: recaptured the Capture-picker and Notebooks-list screenshots (landing page and feature docs, both themes) so they show the new monogram tiles instead of the retired glyph set.
- Notebook and capture-template icons are now **monograms**: a colored tile showing the first letter of the name. For a notebook that's its `#+TITLE:` when the display name is set to "Title" (falling back to the file name), otherwise the file name; for a template it's the template name, updated live as you type. The old curated glyph set (`✦ ✶ ✸ …`) and its picker are gone; a notebook's long-press menu now offers **Change icon color**, a color-only picker with the six theme swatches, and long-pressing the monogram tile on the template edit screen opens the same picker. Any per-notebook glyph you had picked before is dropped the next time that notebook's color is changed or it's renamed.
- The built-in **Quick Note** capture template is now `* %cursor` (a bare heading marker with the cursor right after it) instead of prompting for a title first.
- The built-in **TODO** capture template now files into `tasks.org` instead of `inbox.org`.
- "Report a bug"'s Send Report now copies the formatted report to the clipboard immediately, so it's never lost if no mail app is available or the chooser gets dismissed, and builds the email with `ACTION_SEND` + `message/rfc822` instead of `ACTION_SENDTO` + a `mailto:` URI, an intent shape more mail apps besides Gmail register a compose-email handler for.
- The "Build & Release" workflow can now also be triggered by pushing a version tag matching `v*.*.*` (e.g. `v1.2.0`), publishing a GitHub Release tagged and titled from that exact tag. The manual "Run workflow" dispatch against `main` still works as before.
- The "Build & Release" workflow no longer builds the release APK or AAB on every push. An ordinary push (any branch) and PRs now run the unit tests and build only the debug APK; a push to `main` also builds the release APK (so R8 and signing-config breakage is still caught at merge time); the release AAB and the GitHub Release are built only on the release path (a pushed `v*.*.*` tag or a manual dispatch against `main`). This drops the expensive R8/bundle work from routine feature-branch pushes.

### Removed
- docs-site: deleted the `docs-site/resources/` folder (a second copy of the feature `.mdx` docs plus unused screenshot originals). It had diverged from the built copy under `src/content/docs/`, was referenced by no build step, and only `src/content/docs/features/` is ever shipped. That directory is now the single source for the docs.
- docs-site: pruned unreferenced screenshot files — `src/assets/landing/old/` and `src/assets/landing/dark-mode/`, every loose top-level PNG under `src/assets/landing/` and the dead ones under `src/content/docs/assets/`, plus the empty `internal-docs/` directory. Only images actually imported by a page remain.

### Fixed
- Several dialogs and text fields rendered their placeholder or typed text in the platform default font (Roboto, or a serif) instead of IBM Plex: the Search screen's "Name this search" box, the rename-saved-search and rename-notebook dialogs, the quick "Add note" dialog, the capture-template prompt dialog, the notebook-name field, and Read mode's error / "Note not found" messages. All now use the app's own type.
- docs-site: the landing page and privacy page were serving a generic green-asterisk favicon (an inline SVG placeholder) rather than the Grove app icon that the docs section already used. Both now point at `favicon.svg` / `favicon.ico`.
- The What's New modal no longer flashes on a fresh install. Completing onboarding now stamps the current build as already-seen in the *same* write that marks onboarding done, closing a race where the check could see onboarding finished before that stamp landed and pop the modal once.
- Cleared every warning `kotlinc` printed for `./gradlew testDebugUnitTest`: migrated the bug report screen, Quick Capture's clipboard placeholder, and read mode's Copy Link off the deprecated `LocalClipboardManager` Compose API onto `LocalClipboard`; swapped the outline screen's non-mirrored promote/demote icons for their `AutoMirrored` equivalents; removed a dead null-check in `EditorViewModel.deleteSubtree` that could never be false; and renamed the placeholder-expansion unit tests' `%`-prefixed display names, which were flagged as unsafe on Windows file paths.

## [1.0.1] - 2026-08-25 (build 294)

### Added
- Favorited notes: star/unstar from the outline, sidebar, and read view now resolves through the heading's stable `:CUSTOM_ID:`/`:ID:` instead of a raw line number, so favorites survive edits elsewhere in the file.
- Quick Capture's top bar now has a hamburger menu opening the same metadata sheet as the note editor (state, priority, tags, scheduled/deadline dates) and a Read/Edit toggle that previews the draft's org-formatted rendering in place, with no save required to preview.

### Changed
- Overwriting one of Search's four Quick Start cards (Overdue, Today, Open tasks, Unscheduled) from the star button's save dropdown now updates that card's own query in place instead of creating a separate saved search.
- "Report a bug" now sends the report as an email to the developer instead of filing a public GitHub issue through a relay service: tapping Send Report opens your mail app with the form's details filled in, and nothing is sent until you send that email yourself.
- The "Build & Release" GitHub Actions workflow no longer publishes a GitHub Release automatically on every push to `main`. Every push still builds, tests, and uploads APKs as CI artifacts; a release (tag + APKs + AAB) is now only cut by manually running the workflow against `main`.

### Fixed
- Fixed favorited notes losing their star when a note above them was refiled or deleted: the cleanup that drops favorites inside a removed subtree was comparing a favorite's stale stored line index against the removed range instead of resolving its current position first.
- The default "TODO" quick capture template no longer adds a SCHEDULED date; captured TODOs now start undated.
- Fixed a Quick Start card's result count staying frozen on its old value after overwriting the card's query (even across an app restart): the count now recomputes from the overridden query instead of always using the card's built-in preset.

## [1.0.0] - 2026-08-15 (build 290)

### Fixed
- Disabled AGP's "Dependency metadata" APK Signing Block entry (`dependenciesInfo.includeInApk`/`includeInBundle = false`), which F-Droid's build scanner rejects as a non-standard signing block, failing the reproducible-build check.

## [1.0.0] - 2026-08-15 (build 287)

### Fixed
- Pinned `android.ndkVersion` (29.0.14206865) so release builds strip bundled native libraries (from dependencies, not Grove's own source) with a consistent NDK across environments. An unpinned NDK let CI and F-Droid's build server pick different versions, producing byte-different `.so` files and failing F-Droid's reproducible-build verification against the CI-published APK.

## [1.0.0] - 2026-08-15 (build 282)

### Fixed
- CI stopped publishing new GitHub Releases (breaking Obtainium and anyone else watching Releases) once `versionName` became manually-bumped and started repeating across pushes: the release tag was `v$VERSION` alone, so every push after the first collided with the existing tag and silently clobbered its assets instead of creating a new release. The tag now includes the auto-incrementing `versionCode` (`v$VERSION-$VERSION_CODE`), guaranteeing a fresh tag — and a genuinely new release — on every push with content under `## [Unreleased]`. Release titles stay human-friendly (`v1.0.0 (build 277)`), independent of the tag string.

## [1.0.0] - 2026-08-15 (build 279)

### Changed
- Settings now shows the build number in brackets next to the version name (e.g. "Grove v1.0.0 (277)"), making it possible to tell builds with the same `versionName` apart without pulling `adb shell dumpsys package`.

## [1.0.0] - 2026-08-15 (build 277)

### Fixed
- Ledger widget: an unbounded section (e.g. an already-corrupted index misclassifying hundreds of notes, as with the prior KILL-keyword cold-start race) could push Glance's `LazyColumn` past Android's ~1MB RemoteViews transaction limit, which surfaced as the host's generic "Can't show content" placeholder with nothing logged. Each section is now capped at 20 rows with a "+N more · view all" row (deep-links to the in-app Agenda screen) when truncated; the section header's count stays the true, untruncated total.

## [1.0.0] - 2026-08-15 (build 274)

### Added
- `.github/workflows/deploy-docs-site.yml`: deploys `docs-site` to Cloudflare on push to `main`, scoped to `docs-site/**` changes, mirroring the `paths-ignore` on the app's build workflow so a push never triggers both. Requires `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` repo secrets.

### Fixed
- `docs-site`: screenshots and other optimized images rendered as alt text instead of the actual image once deployed to Cloudflare. The `@astrojs/cloudflare` adapter defaults to transforming images at runtime via Cloudflare's Images product, which isn't provisioned on the account (and isn't emulated by `wrangler dev`), so every image request 404'd. Fixed by setting `imageService: 'compile'` so images are optimized at build time instead, matching the site's fully static output.

## [1.0.0] - 2026-08-15 (build 271)

### Changed
- Moved the docs/landing site (`docs-site/`, Astro + Starlight) into this repo instead of a separate one, so docs can be updated alongside code. The app CI workflow now ignores `docs-site/**`; it deploys separately as a Cloudflare Worker with static assets (`docs-site`, `npm run deploy`).

## [1.0.0] - 2026-08-14 (build 266)

### Added
- Added a Screenshots section to `README.md` and the F-Droid/Play store listing assets
  (`fastlane/metadata/android/en-US/images/`): six phone screenshots and a 512x512 `icon.png`
  rendered from the default adaptive-icon layers, since the app ships no raster launcher icon.

### Fixed
- Fixed the agenda ledger widget permanently showing the system's "Can't show content"
  fallback for vaults containing a SCHEDULED/DEADLINE timestamp with an out-of-range hour or
  minute (e.g. a hand-typo'd `25:00`, or a >24h CLOCK duration cookie that reads like a clock
  time). `OrgTimestamp.parse` already guarded an invalid *date* against throwing, but not an
  invalid *time*: `LocalTime.of` threw an uncaught `DateTimeException` straight out of the
  widget's `provideGlance`, which Glance has no crash UI for, so the AppWidgetHost fell back to
  its generic placeholder on every redraw — surviving delete/re-add because the bad timestamp
  lives in the vault, not the widget instance. An out-of-range time now rejects the whole
  timestamp the same way an out-of-range date already did. The widget's section-building is
  also now wrapped defensively, so a future data problem degrades to an empty ledger instead of
  taking the whole widget down again.
- Fixed the daily digest notification again reporting hundreds of tasks as overdue with a custom
  done-keyword (e.g. `KILL`) left unrecognized, on some cold starts. This is a second, narrower
  gap in the same area as the earlier "Fix cold-start race that could index notebooks with stale
  TODO keywords" fix (commits 343f65b/fd1dc85), not a full regression of it: that fix made
  `GroveApplication.onCreate` await `settingsRepository.settings.first()` before wiring up
  `syncManager.attach`, which does stop DataStore's *disk* read from racing the first sync. But
  `keywords` (the parsed `OrgKeywords` used to classify every heading during indexing) is a
  *separate* `by lazy` `StateFlow`, eagerly seeded with the default keyword set until its own
  independent subscription delivers a real value — and nothing forced that subscription to
  start early. Its very first read happened synchronously, with no suspension point, inside the
  very first sync's indexing pass, so it could still return the default seed regardless of how
  much wall-clock time had passed since the settings read. `onCreate` now explicitly blocks on
  `keywords` itself until it has emitted the value derived from the settings just read, before
  letting that first sync fire, closing the gap the settings-only wait left open.
- Fixed favorites in the sidebar drawer becoming "Note not found" after the underlying `.org`
  file was edited outside the app (Grove's whole model is that files are edited in Emacs/synced
  via Syncthing too, not just in-app). `FavoriteNote` was keyed purely by a raw line number,
  which drifts the moment any line above it is added or removed externally. Favoriting a note
  already wrote a stable `:CUSTOM_ID:` onto its heading, but discarded the id afterward instead
  of storing it; favorites, `NoteRef` navigation, and the read/edit-mode note loaders now resolve
  by that id first, falling back to the line number only when there's no id (older favorites, or
  a note that never got one).

## [1.0.259] - 2026-08-13

### Fixed
- The in-app "What's New" modal now tracks the last-seen release by `versionCode` instead of
  `versionName`. It previously compared `BuildConfig.VERSION_NAME` directly, which worked only
  because that string used to embed the ever-increasing commit count; now that `versionName` is a
  manually-bumped SemVer string that can repeat across releases (e.g. several pushes all still
  `1.0.0`), that comparison would have silently stopped surfacing new changelog entries after the
  first one. CHANGELOG.md's archived release headers now carry a `(build N)` suffix — the
  `versionCode` at cut time — and `ChangelogParser`/`SettingsRepository` key off that instead.
- Shared-link title fetching (PageTitleFetcher) no longer accepts a generic app-shell
  `<title>` — like YouTube's bare "YouTube" — as a shared link's title. The previous fix for
  this only special-cased Reddit's known shell titles; the check is now site-agnostic: it
  derives the shared URL's own brand from its host and rejects a fetched title that reduces to
  nothing but that brand (alone, or as a short lead-in tagline), falling back to the WebView
  fetch either way so the real, client-rendered title is used instead.
- Fixed that brand check still missing YouTube links shared via the native share sheet, which
  hand over a `youtu.be/<id>` short link: `PageTitleFetcher` was comparing the fetched title
  against the shortener's own host ("youtu") rather than the real site it redirects to
  ("youtube"), so the brand never matched and the "YouTube" placeholder slipped through. HTTP
  fetches now follow redirects manually so the placeholder check runs against the final,
  post-redirect URL; the WebView fallback's poll loop does the same using `webView.url`.
  Verified on-device: a shared `youtu.be` link now resolves to the real video title.

## [1.0.255] - 2026-08-12

### Fixed
- Quick capture's save icon now matches the note/preface editor's UX exactly: green while
  dirty (tap saves immediately), grey once saved (tap shows a "last saved at" toast), no
  blink animation. It previously started hidden, blinked twice on every auto-save, and used
  the two colors with inverted meaning.
- Auto-capitalizing the first letter of a heading now also fires right after a TODO keyword
  (e.g. `* TODO buy milk` → `* TODO Buy milk`), including for swipe-typed words, matching the
  existing behavior on plain headings. Also now respects a custom `todoKeywords` configuration
  instead of assuming the default set.
- The Quick Add widget's text field now auto-capitalizes like a sentence, matching every other
  text entry point in the app.
- The ten newest themes (Chalk, Cobalt, Delft, Sage, Ash Rose, Obsidian, Lantern, Lighthouse,
  Moss, Wisteria) now use a hue-distinct color for property/drawer keys instead of a shade of
  the same neutral used for values, so preface, `:PROPERTIES:`, and `:LOGBOOK:` drawer keys
  are visually distinct from their values, matching Grove Dark/Light and the other community
  themes.
- Double-tapping a subheading while reading now opens the editor with the cursor and scroll
  position landing on that subheading instead of always at the top of the note. Previously,
  double-tapping anywhere jumped into the editor at the very first line, forcing a manual
  scroll through the whole buffer to reach a subheading buried further down. Double-tapping
  the note's own heading/body is unchanged.
- Report a bug screen: the steps-to-reproduce placeholder now shows each numbered step on its
  own line instead of run together on one line; hitting Enter on a numbered item now continues
  the list (or removes the item if it was left empty), auto-capitalizing the first letter typed
  into a new item, matching the note editor's list behavior; removed the "Privacy policy" link
  (no such page exists) and replaced em dashes with plain punctuation throughout the screen's
  copy; the "Report sent" checkmark now uses the same check icon as the Notebooks screen's sync
  indicator instead of a plain "✓" glyph; a successful send now shows a popup with a link to the
  filed GitHub issue and Dismiss/Go to Issue buttons instead of a toast; "Copy as text" is now
  styled as a visible link instead of unstyled plain text. The "What went wrong" and "Steps to
  reproduce" fields now auto-capitalize like a sentence as you type (via the keyboard's own
  sentence-cap input mode, same as every other text field in the app), rather than never
  capitalizing at all.
- Sharing a Reddit link into Grove no longer saves the note titled just "Reddit" (or "Reddit -
  The heart of the internet"). Reddit serves that generic placeholder to every URL over plain
  HTTP and only swaps in the real post/subreddit title once its page finishes loading
  client-side; the title fetcher now recognizes those placeholders and waits for the real title
  to appear instead of accepting the placeholder as final.

## [1.0.251] - 2026-08-12

### Added
- Ten new themes in Settings § Look and Feel: Chalk, Cobalt, Delft, Sage, Ash Rose (light-family)
  and Obsidian, Lantern, Lighthouse, Moss, Wisteria (dark-family), each with a matching
  "Sync App Icon with Theme" launcher icon variant
- Settings § Help § Report a bug screen: description (required) and steps-to-reproduce
  (optional) fields, toggles for including device/app info and the recent error log, an
  expandable "show exactly what gets sent" payload preview, and Send/Copy actions. Send has
  no crash server to submit to yet, so it surfaces the entered report as a toast instead;
  Copy places a plain-text version on the clipboard for pasting into a GitHub issue.

## [1.0.249] - 2026-08-11

### Changed
- The planning calendar's "has items" marker (days some other note has a SCHEDULED/DEADLINE on)
  is now a bold, violet-tinted day number instead of a dot, matching how Emacs org-mode marks its
  scheduling calendar; the "has items" legend swatch is gone since the coloring speaks for itself
- The planning calendar no longer marks today with its own dot unless today itself is scheduled
  or deadlined

### Fixed
- The planning calendar dialog's status and navigation bar icons no longer render light-on-light
  in light themes; the dialog opens its own platform window that wasn't picking up the app's
  theme-driven icon appearance
- The Ledger widget's mark-done tap target is back to the visible ring's actual width instead of
  the wider region that was compensating for an unrelated stale-render bug already fixed elsewhere

## [1.0.245] - 2026-08-10

### Fixed
- Fixed a cold-start race where the very first sync after launching the app (most likely right
  after an update) could index some notebooks before the persisted TODO keyword config had
  finished loading, silently falling back to the default keywords and leaving custom keywords
  (e.g. a user-defined `KILL` state) unrecognized and unhighlighted in the agenda until the next
  full reindex

## [1.0.242] - 2026-08-10

### Added
- Metadata bottom sheet: a "Refile" option next to "Add note". In Read mode it opens the Refile
  picker directly; in the Editor it first confirms ("this note will be saved and refiled to the
  location you choose"), saves the buffer, then opens the picker against the saved file
- A completed refile from the metadata sheet now shows the same "Refiled to X" snackbar (with
  Undo) as refiling from the Outline view

### Changed
- Agenda's swipe-left/swipe-right dropdowns now only offer Mark as Done and Schedule Task; Set
  Deadline is no longer selectable there (still supported if set via an older settings export)
- Settings › Sharing's target-file field is now the same notebook-autocomplete field (with
  inline filename validation) used by the capture-template editor's Target file field, instead of
  a plain, unvalidated text field
- Search's "Browse tags" quick-start card is now "Unscheduled": it filters to open TODOs with
  neither a scheduled date nor a deadline, using a live count like the other quick-start cards
- Search's quick-start cards now use real Material icons at a consistent size instead of
  text-glyph icons that rendered at mismatched sizes
- Search's saved-search star now aligns with the title line instead of centering against both
  lines

### Fixed
- The time picker's digits, the capture template editor's Name field, and its Heading name field
  (including their placeholder text) now use the app's UI font instead of silently falling back
  to the read-mode serif font
- The text-selection teardrop handle no longer hangs over the previous screen, still blinking,
  when navigating back out of a settings page (or the capture template editor) while a text field
  is focused
- Refiling a note out from under the Read-mode or Editor screen now waits for the move to
  actually land (and its undo window to close) before leaving, instead of navigating away the
  instant the picker closed — which risked cancelling the refile's file write mid-flight

## [1.0.240] - 2026-08-10

### Changed
- Removed the Synthwave, Nord, and Dracula themes (and their app-icon variants)
- The theme dropdown's active-theme checkmark and the outline screen's overflow-menu toggles now
  use real Material check/checkbox icons instead of raw "✓" text glyphs, matching the checkmark
  style used elsewhere in the app (e.g. the notebooks list top bar's synced indicator)
- Removed the Sentry crash-reporting integration entirely (Gradle plugin, manifest config, CI
  upload step)

### Fixed
- Preface, properties-drawer, and logbook keys are now visibly colored in Grove Dark, Tokyo Day,
  Catppuccin Latte, Rosé Pine Dawn, and Rosé Pine Moon — their key color had been left equal to
  the body-text color, making keys blend into values

## [1.0.236] - 2026-08-08

### Fixed
- The metadata sheet's and the state-change sheet's (swipe reveal row "state" action) keyword
  chips now always tint by done/active state, not just the currently-selected chip, matching the
  keyword badges shown everywhere else in the app (Outline rows, Search results, Agenda rows)
- The Agenda screen's Today/Upcoming tabs now have proper spacing below them; the overdue card and
  list no longer sit flush against the tab bar
- The Agenda screen's Group by and Show (state) levers are now tracked separately for the Today and
  Upcoming tabs; changing one no longer silently re-buckets or re-filters the other tab, and
  Upcoming still defaults to grouping by date

### Changed
- The Agenda screen's selected Today/Upcoming tab is now marked with a plain darker fill instead of
  an accent-colored outline
- The Agenda screen's ⇅ levers button now highlights with an accent tint while its panel is open

## [1.0.234] - 2026-08-07

### Added
- Capture template editor's Target file field now has an inline expanding suggestion list (▾ to
  toggle, filters live as you type) below the field, listing the vault's existing notebooks, styled
  like the settings screen's other dropdown pickers
- That Target file field is validated as a filesystem-safe `.org` filename (checked live and on
  focus-out); Save is disabled until it's valid
- The Template field is validated for unsupported `%...` placeholders and shows the offending tokens
  inline; both the Capture Templates settings list and the "Capture to…" picker sheet now show a
  small `⚠ N` badge on any template with filename or placeholder errors
- Left/right swipes on the planning-dates calendar now step to the previous/next month, same
  direction as the existing ‹/› arrows, with the outgoing month sliding away as the new one slides in

### Fixed
- Auto-capitalizing the first letter of a heading only worked for individual keystrokes; swipe-typed
  or autocorrect-committed words (delivered as one multi-character insertion, not one key event per
  letter) skipped it entirely. Both paths now capitalize consistently
- The template editor's Template field could be hidden behind the keyboard when focused, since the
  screen never reserved space for the keyboard; it now scrolls the focused field above it

## [1.0.219] - 2026-08-07

### Added
- Phone numbers written in prose (e.g. `833-806-1627`, `(833) 806-1627`, `+1 833-806-1627`) are
  now detected in read mode, shown reformatted as `(833) 806-1627`, and tappable to place a call —
  no `[[tel:...]]` link syntax required

## [1.0.217] - 2026-08-07

### Changed
- Metadata sheet's Tags field is now a search/create dropdown instead of a free-text
  space-separated field: typing filters the vault's tag pool into a tappable list, with a
  "Create tag "…"" option when there's no exact match. Applied tags render below the field as one
  `:tag:tag:` colon string; tapping a segment removes that tag
- Widget quick-add's date and notebook chips now open a dropdown listing every option instead of
  cycling one tap at a time. Date adds a "Custom date…" entry that opens the same
  `PlanningDatesScreen` used by Reschedule; presets still resolve at send time so "Today" stays
  correct even if the sheet is left open across midnight
- Updated the design system doc for the tags dropdown and the widget quick-add date/notebook
  pop-out chips described above

### Fixed
- Typing in the metadata sheet's tag field could get silently cleared partway through: the
  autocomplete dropdown's dismiss handler wiped the typed text on any dismissal, including
  transient ones while the keyboard/sheet were still settling into place. Dismissing the dropdown
  no longer touches what's been typed
- The metadata sheet's tag field could be hidden behind the keyboard when focused, and scrolling
  the sheet fully open could push the "+ Add note" button out of view with no way to reach it,
  since the sheet's content never reserved space for the keyboard or allowed scrolling past it
- CI's unit test build was failing: the last backfill introduced a `### Documentation` subsection
  heading, which `ChangelogParserTest` correctly rejects since it isn't one of the Keep-a-Changelog
  categories the parser and widget "what's new" screen support. That bullet now lives under
  `### Changed` instead

## [1.0.208] - 2026-08-06

### Fixed
- Extra TODO keywords squished the metadata sheet's State chips instead of wrapping. The State and
  Priority chip rows were plain `Row`s, so once the configured keywords no longer fit on one line
  Compose shrank each chip to fit, and the last one degraded into a column of single letters
  (`K I L L`). Both rows are now `FlowRow`s that wrap onto additional lines, and a chip's label
  never wraps inside the chip itself the row stayed put, still
  showing its old keyword. The file on disk and the search index were in fact being updated
  correctly every time — only the widget's *display* was stale. It read its rows into local
  variables before handing them to Glance, and while a widget session is live `updateAll()` only
  recomposes that already-captured snapshot instead of re-reading anything, so the ledger kept
  redrawing the same rows until the app was relaunched. The widget now reads the index and settings
  from inside its own composition, so it redraws itself as soon as an edit lands. This also fixes
  the same staleness after widget quick-add, and rows whose keyword pill rendered as plain title
  text
- Marking a task done from the Agenda widget did nothing at all in release builds, while working
  correctly in debug builds of the same commit. Glance starts a widget action by looking up the
  callback class by name and constructing it reflectively; R8 could not see that constructor being
  called from anywhere, so it removed it from the release APK and the tap threw before any of the
  widget's code ran. Release builds now keep the constructor on widget action callbacks

## [1.0.206] - 2026-08-06

### Fixed
- Marking a task done from the Agenda widget could silently do nothing if Android had killed the
  app process in the background: the tap read the vault before its background initialization had
  finished, so the write never happened even though the circle still showed as tapped. It now waits
  for the vault to finish loading before writing
- Settings > Notes > TODO keywords' "Apply" action is now always available, not just after editing
  the field, so it also works as a manual "rebuild index" action (e.g. to recompute a notebook's
  done/open state after it was indexed under an older keyword config)

## [1.0.204] - 2026-08-06

### Fixed
- The Agenda widget's task circle now has a much larger tap target (it absorbed the dead space that
  used to sit between it and the title), and its ring border is thinner
- Tapping anywhere in the Agenda widget's header row (logo, "N today · N in N days") now opens
  Grove, not just the logo icon; the "+" button is unaffected
- Grove now shows up as an app that can open `.org` files from more sources (file managers that tag
  them `application/octet-stream`, and cloud providers whose content URI doesn't end in `.org`) —
  the intent filter previously required both a matching MIME type and a matching path in the same
  rule, which most senders satisfy only one of
- The daily reminder digest ("You have X tasks due today") now counts tasks with a specific
  time-of-day too, so its total always matches what the Agenda screen shows for today (overdue +
  due today); it previously excluded timed tasks since they also get their own "due now"
  notification, which made the digest undercount
- Marking a task done in the Agenda widget now removes it from the widget right away. The
  widget used to depend on the app's background sync to notice the file changed and reindex it,
  which was fire-and-forget and, if the widget action revived a process Android had killed in the
  background, could be a complete no-op — the task would only disappear once some later, unrelated
  sync happened to run. It's now indexed immediately from the text just written, independent of
  background sync

## [1.0.200] - 2026-08-05

### Fixed
- Sync conflicts' "Keep both" now line-diffs the current file against the conflict copy and keeps
  both versions right where they diverge, instead of appending the whole conflict copy under a new
  heading at the end
- Pressing Enter in a bulleted, numbered, or checklist item now always continues the list, even
  right after editing a word in the middle of the line and moving the cursor to the end before
  hitting Enter
- The Agenda widget's section-header divider line now spans the full remaining width of the row
  instead of just its right half
- Favoriting a note now adds a `:CUSTOM_ID:` property to it (only if it doesn't already have an
  `:ID:` or `:CUSTOM_ID:`), so the favorite is stably referenceable from the sidebar
- Sharing text or a link into Grove from another app no longer opens Grove's UI; the note is saved
  to the configured file and a toast confirms it, exactly as before, but silently

## [1.0.198] - 2026-08-05

### Added
- Settings › Reminders now has a "Notify me" dropdown (at the time of event, 5/10/15/30 minutes
  before, 1 hour before, 1 day before) that shifts the "due" notification earlier and reworks its
  message to match ("Your task is due in 15 minutes", etc.); only affects SCHEDULED/DEADLINE
  stamps that carry their own time of day
- Settings › Agenda has a new "Widget" section: a transparency slider for the home-screen Agenda
  ledger widget's background, and a "Days ahead" field controlling how far into the future it shows

## [1.0.196] - 2026-08-05

### Added
- New home-screen widget: an Agenda ledger showing overdue tasks first, then upcoming tasks
  grouped by day (Today, Tomorrow, then by date), sorted by priority and time within each day.
  Tap a task to open it in read mode, tap the circle to mark it done (recurring tasks and
  auto-archive behave the same as on the Agenda screen), tap "+" for a small quick-add composer
  (defaults to the Sharing settings' target notebook), or tap the header icon to open the app

## [1.0.194] - 2026-08-05

### Added
- The star button on the Search page now opens a searchable dropdown of saved searches
  (plus the quick-start cards) instead of a plain name prompt: typing continuously filters
  the list, picking an existing entry asks to overwrite it, and a name not in the list saves
  as a new search
- The Filters bottom sheet's negatable facets (Notebook, Tags, TODO state, Priority) now show
  a "tap again to exclude" hint next to their section label
- The Filters bottom sheet now also has Closed and Created date facets, matching the existing
  Scheduled/Deadline ones (Today/Next 7 days/Overdue/No date/Custom range)
- The Advanced panel's operator chips gained d./c./cr., alongside the existing t./i./s./b./p.

### Fixed
- `s.overdue`/`d.overdue` search expressions returned nothing: the "overdue" period token
  wasn't recognized at all; they now match anything scheduled/due strictly before today
- `s.nodate`/`d.nodate` returned nothing: only `s.none`/`d.none` were recognized; `nodate` is
  now accepted as an alias for `none`
- `s.today`/`d.today` matched anything scheduled/due on or before today (i.e. everything
  already overdue too) instead of only today itself
- The Search page's "Overdue" quick-start card only checked the deadline date by default,
  missing open tasks that were overdue on their scheduled date instead
- The "Open tasks" quick-start card's default search ANDed every active TODO keyword together
  instead of ORing them, which could never match anything once more than one keyword was
  configured; the same AND-instead-of-OR mirroring bug affected any multi-value Filters
  selection (e.g. two tags picked together) whenever the mirrored text field re-narrowed the
  results a second time
- The Advanced panel's operator chips now reflow naturally with the available width instead
  of a fixed two-row split, so short chips no longer wrap to a second line while there's still
  room on the first
- Scheduled/deadline pills no longer render on already-done items in search results, since a
  completed item's dates aren't actionable

## [1.0.190] - 2026-08-05

### Added
- Long-pressing a Saved Search or Favorite in the nav drawer now opens a Move up/Move
  down/Rename/Delete menu instead of just a delete confirmation, so both lists can be
  reordered and renamed in place, not only pruned
- A What's New modal now appears once after an app update, listing everything from
  CHANGELOG.md since the version you last saw, formatted the same way as the changelog
  itself (Added/Fixed/etc. sections with bullet items)

### Fixed
- Killing (marking done) a recurring task with auto-archive enabled no longer archives it: a
  repeating SCHEDULED/DEADLINE keeps the task's keyword active (per org semantics, only the date
  advances), but auto-archive was checking the requested keyword instead of the task's actual
  post-mutation state, so it refiled still-active recurring tasks to the archive location anyway
- Setting a SCHEDULED or DEADLINE to a date/time already in the past no longer fires an
  immediate "task is due" notification; only a genuine catch-up (the device was off or the
  app was closed when an already-armed future reminder came due) still fires one

## [1.0.187] - 2026-08-04

### Added
- Double-tapping Outline's PREFACE section now opens an editor scoped to just the file's
  preamble (everything before the first heading), mirroring double-tap-to-edit elsewhere

### Fixed
- Daily reminder digest no longer overcounts tasks: a heading whose SCHEDULED carries a
  time-of-day (and so fires its own "due now" notification) no longer falls back to a
  date-only DEADLINE on the same heading as a substitute anchor date, which could count it
  as due in the digest even though the Agenda screen would never show it under today or
  overdue
- Agenda rows now show a heading's inherited tags (its own tags plus every ancestor
  heading's tags), matching org-mode tag inheritance, instead of only the heading's
  own tags
- Sync conflict screen's "Keep both" button now actually merges both versions (appending the
  conflicting copy under a `* CONFLICT` heading) instead of silently leaving the file
  unchanged when the conflict marker had already cleared from the index, which previously
  looked identical to "Keep current"
- Read mode now renders a heading's SCHEDULED/DEADLINE planning line before its
  `:PROPERTIES:`/`:LOGBOOK:` drawers, matching the order edit mode (and the underlying org
  file) already uses, instead of showing drawers first
- Tapping a `.org` file in a file manager (or setting Grove as its default handler) now actually
  opens the matching notebook instead of silently doing nothing — the manifest's file-open
  intent-filter was correctly registered, but nothing on the receiving end matched the
  incoming `content://`/`file://` URI against the vault and navigated to it

## [1.0.179] - 2026-08-04

### Fixed
- Shared-link capture now falls back to a hidden WebView to read the real page title when the
  plain HTTP fetch returns a bot-check interstitial (e.g. Reddit's "Please wait for
  verification" page), instead of using the placeholder title verbatim

## [1.0.174] - 2026-08-04

### Changed
- The sidebar's Agenda/saved-search icons and the search icon (sidebar, Notebooks/Outline
  top bars, search field) now use the exact path data from the design prototype instead of
  approximated Material Icon paths
- Refile sheet's notebook rows now use a dedicated notebook icon instead of the "▤" text
  glyph
- Search date filters (`s.none`/`d.none`/`c.none`/`cr.none`) now match notes whose
  corresponding timestamp is absent, instead of being treated like every other "within
  period" filter
- The launcher icon no longer auto-switches to a dark-mode variant; light-theme colors are
  now used by default in both themes

## [1.0.172] - 2026-08-03

### Changed
- Notebooks list now supports pull-to-refresh instead of a dedicated refresh button in the
  top bar
- The search icon (sidebar, Notebooks/Outline top bars, search field) now uses a dedicated
  `ic_search` drawable instead of the "⌕" text glyph / `Icons.Default.Search`
- The sidebar's Settings, Agenda, and saved-search icons now use dedicated `ic_settings`,
  `ic_calendar_view_day`, and `ic_filter_center_focus` drawables instead of text glyphs
- Search's Filters button now uses `Icons.Default.FilterList` instead of the "⚙" text glyph

### Fixed
- Tapping "Manage" from the Capture picker now opens Settings § Capture Templates directly
  instead of the Settings home page

## [1.0.168] - 2026-08-03

### Changed
- Edit mode's formatting toolbar now draws Bold/Italic/Underline/Code/Checklist as custom
  vector icons instead of text glyphs (`B`, `I`, `U`, `</>`, `☑`), matching the icon style
  already used for the rest of the toolbar

## [1.0.164] - 2026-08-02

### Changed
- Favorites now use a dedicated star icon instead of the plain "★" character: filled amber
  in read mode and the outline row indicator, outlined to match the existing color scheme
  in the sidebar and the outline swipe row
- The "Note" swipe action on Outline's swipe-reveal row and Agenda's swipe-to-commit row now
  uses a custom `ic_note` drawable instead of the Material `EditNote` icon

### Fixed
- Back gesture on Settings § Notes and § Sharing now shows the previous screen fading in
  like every other screen instead of fading to blank — a `BackHandler` there was silently
  swallowing the predictive-back gesture before `NavHost` could animate it
- The favorites star in read mode now aligns flush with the property drawer's right edge
  below it
- Read mode's metadata sheet now shows "SCHEDULED"/"DEADLINE" instead of the abbreviated
  "SCHED"/"DUE"
- Fixed a crash when changing the theme in Settings with "Sync app icon with theme" on:
  launcher shortcuts were republished against the icon alias for the *new* theme before
  `AppIconManager` had actually enabled it (that switch is deferred to when the app
  backgrounds), so `ShortcutManagerCompat` rejected the publish with "is not main activity"

## [1.0.162] - 2026-08-02

### Fixed
- Agenda swipe-row checkbox and priority badge now sit 12dp from the row edges instead of 2dp
- Edit mode's metadata sheet: SCHED and DUE now stack on separate lines instead of sharing one row

## [1.0.157] - 2026-08-02

### Added
- Settings § Notes: "Auto-archive done items?" — marking a task with any done-type keyword
  (not just `DONE`) now refiles it automatically, resolved the same way org resolves
  `ARCHIVE`: the heading's own property, its nearest ancestor's, the file's `#+ARCHIVE:`
  keyword, then a new Settings-configured fallback location (picked via the same drill-down
  picker as manual refile). Works identically from the Outline swipe, Agenda, Search, and
  edit mode, with a "Marked done. Refiled to `<location>`" snackbar and Undo
- Edit mode's metadata sheet gained an "+ Add note" action, logging free text into the
  heading's LOGBOOK drawer the same way the Outline's "Note" swipe action does
- Read mode gained the same ☰ metadata sheet as edit mode: state, priority, tags,
  Schedule/Deadline, and Add note are all editable without switching to edit mode first,
  saving straight to disk

### Changed
- Edit mode's Schedule and Deadline are now one combined row instead of two, opening the
  same dual-date picker already used for both
- Switching a note between read and edit mode is now instantaneous; the full-screen
  transition now only plays when actually navigating to a different note

### Fixed
- Settings export/import now carries the last-used refile destination, matching the other
  refile-shaped settings that were already portable
- Cold start no longer wipes and rebuilds the entire note index when your TODO keyword
  setting differs from the default: a startup-vs-change detection bug treated every launch
  as a keyword config change, forcing a full reparse of every notebook instead of trusting
  the existing cache

## [1.0.155] - 2026-08-01

### Fixed
- Reminder digest ("You have X tasks due today") was double-counting tasks
  that carry an explicit time-of-day: those already get their own individual
  notification, so the digest now only bundles date-only reminders, matching
  its intended count
- Digest count no longer disagrees with Agenda by one when a heading carries
  both a SCHEDULED and a DEADLINE date landing on the same side of today: it
  now collapses to a single task the same way Agenda's "a heading belongs to
  exactly one day" rule does, instead of counting the heading's two reminders
  table rows separately

## [1.0.151] - 2026-08-01

### Changed
- Screen transitions now use the fade-through from the Android predictive-back
  guidelines instead of a horizontal slide, which had made moving between
  screens look like swiping through full-screen photos. Dragging back fades the
  previous screen in and scales it down into place, following the gesture and
  rewinding if you let go early; going forward mirrors it as a zoom in

## [1.0.147] - 2026-08-01

### Added
- Predictive back gesture support: navigating between screens now slides the
  previous screen in, partially visible, while dragging back
- Agenda swipe panel: an "Add note" button rides alongside "Mark done"; a
  partial swipe reveals both, a full swipe still marks the task done directly

### Fixed
- Agenda swipe-reveal background now has rounded corners matching the card
  in front of it, instead of showing square corners underneath

### Changed
- Removed em dashes from UI text, code comments, and documentation throughout
  the project, replacing each with punctuation that fit the sentence

## [1.0.144] - 2026-07-31

### Fixed
- Search page UI improvements

## [1.0.143] - 2026-07-31

### Fixed
- Search results swipe gesture bug
- Light theme status bar icons

## [1.0.142] - 2026-07-31

### Added
- Split settings into separate pages for better organization
- Swipeable search results interface

### Changed
- Reordered theme list for better discoverability

## [1.0.141] - 2026-07-30

### Fixed
- Agenda screen UI improvements and other minor enhancements

## [1.0.140] - 2026-07-30

### Added
- Agenda keyword filters

### Changed
- Swipe-panel alignment improvements

### Fixed
- Notification icon display

## [1.0.138] - 2026-07-29

### Added
- Tap-to-save functionality for planning dates
- Outline note capture from agenda

### Changed
- Calendar and flag icons replacing SCHEDULED/DEADLINE text labels

### Fixed
- Logbook note continuation formatting
- Swipe-reveal panel colors
- Repeater sentence alignment

## [1.0.136] - 2026-07-29

### Changed
- Replaced two-step date picker with full-screen SCHEDULED + DEADLINE editor

## [1.0.135] - 2026-07-29

### Added
- Outline search functionality
- State picker in outline view
- Agenda done filter

### Fixed
- Outline autosave issues

## [1.0.133] - 2026-07-28

### Added
- Agenda swipe actions (mark done, reschedule, etc.)
- Outline narrowing feature
- LOGBOOK drawer support in outline view

## [1.0.132] - 2026-07-27

### Changed
- Migrated search to FTS5 trigram index with SQL facet pushdown (performance improvement)

## [1.0.130] - 2026-07-27

### Fixed
- Reminder notification tap handling
- Keyboard gap when editing
- Agenda snippet overflow

## [1.0.128] - 2026-07-26

### Changed
- Extracted Agenda into its own dedicated screen

### Fixed
- Search bugs and visual polish

## [1.0.127] - 2026-07-26

### Added
- Agenda overdue section
- Agenda day pagination
- Per-priority agenda colors

## [1.0.126] - 2026-07-25

### Added
- Auto-scroll during read mode drag-selection

### Fixed
- Selection auto-scroll in org editors

## [1.0.123] - 2026-07-24

### Changed
- Renamed header tags to preface
- Dropped per-row sync checkmark

## [1.0.121] - 2026-07-24

### Added
- Reminders system with alarm scheduling and notifications
- Boot receiver rebinding for alarms

## [1.0.120] - 2026-07-23

### Changed
- Improved outline expand/collapse visual feedback
- Settings UX improvements

### Fixed
- Editor scroll jitter

## [1.0.118] - 2026-07-23

### Changed
- Redrew outline fold caret as canvas glyph
- Enlarged collapsed-chevron visual size
- Conflict diff labeling by date

## [1.0.117] - 2026-07-23

### Added
- Reusable PlanningDatePicker with date+time step

## [1.0.116] - 2026-07-20

### Added
- Fade and checkmark animations for checklist parent items (when all children complete)
- Zero-padded timestamp minutes
- Long-press for date-only timestamp creation
- FAB clearance improvements on lists

## [1.0.114] - 2026-07-20

### Added
- Checklist insert button

### Changed
- Redrew read-mode list markers as canvas glyphs

### Fixed
- Drawer swipe and back gesture handling breaking after leaving home screen

## [1.0.111] - 2026-07-20

### Added
- Checklist list items with configurable read-mode tap-to-cycle states

## [1.0.109] - 2026-07-18

### Fixed
- App shortcut deep links
- Notebook sorting
- FAB scrim rendering
- Autosave check mark visibility
- Link button behavior

## [1.0.107] - 2026-07-17

### Added
- Support for dashed tags in headers
- Last-used refile location memory

### Fixed
- Timestamp and content line break handling

## [1.0.105] - 2026-07-14

### Added
- Dynamic launcher shortcuts per capture template

## [1.0.103] - 2026-07-13

### Added
- Auto-archive feature during refile operations
- Notebook display name customization in settings

### Changed
- Moved header tags to top of file

### Fixed
- Refile-undo tap-through bug
- Sticky header tags visibility

## [1.0.100] - 2026-07-13

### Added
- Outline gestures: swipe-reveal panels
- Move/indent command bar for outline editing
- Refile functionality in outline

### Fixed
- Command bar centering
- Tag and favorite alignment
- FAB cursor focus

## [1.0.98] - 2026-07-12

### Changed
- Converted Settings theme chips to inline dropdown per prototype

## [1.0.96] - 2026-07-12

### Added
- Favorite stars for notes
- Header tag display

### Fixed
- Header-tags visibility issues

## [1.0.95] - 2026-07-11

### Added
- Theme-synced launcher icons
- 5-spoke brand mark in UI

## [1.0.90] - 2026-07-10

### Added
- Debug build type with distinct applicationId for testing alongside release builds

### Changed
- Even out editor toolbar spacing
- Capture auto-save checkmark
- Unified notebook-row checkmark icon

### Fixed
- Toolbar icons wrapping on narrow screens
- Toolbar divider between code and link buttons removed

## [1.0.84] - 2026-07-09

### Added
- Performance optimization: lifecycle-aware flow collection (reduced memory usage)
- O(n) inherited-tags indexing algorithm
- Lazy read view rendering
- Memoized tree traversals
- Memoized editor highlighting per line
- O(1) tokenizer overlap check
- Search index memory caching
- Precomputed timestamp dates
- Theme prototype design files

### Changed
- Hot-path regexes optimization
- Database projections optimization
- Sync state machine improvements

### Fixed
- Dead code removal throughout codebase

## [1.0.82] - 2026-07-08

### Added
- Theme picker with 7 themes for Settings screen

## [1.0.80] - 2026-07-08

### Fixed
- Sentry release version mismatch
- Capture template label handling

## [1.0.77] - 2026-07-08

### Added
- Sentry error tracking and session monitoring
- Sentry release tracking in Android Manifest

### Fixed
- Quick-capture autosave crash
- Editor scroll position persistence
- Autosave toast notifications
- Insert submenu positioning

### Changed
- Removed Sentry wizard test exception from MainActivity

## [1.0.73] - 2026-07-07

### Changed
- Scroll jump buttons extended functionality
- Reverted double-tap cursor positioning

## [1.0.71] - 2026-07-07

### Added
- Auto-save functionality for capture and edit modes
- Scroll to top/bottom buttons

## [1.0.70] - 2026-07-06

### Added
- Insert submenu with formatting options
- Unified capture keyboard

### Fixed
- Selection auto-scroll behavior
- Link menu positioning
- Broken Compose imports in clean builds

## [1.0.67] - 2026-06-28

### Fixed
- Markdown preview indentation
- Heart icon removal from favorite menu

## [1.0.65] - 2026-06-27

### Changed
- App icon background refinement

## [1.0.64] - 2026-06-27

### Changed
- Architecture documentation updates
- Generated design system documentation

## [1.0.63] - 2026-06-27

### Changed
- Minor app icon adjustments

## [1.0.62] - 2026-06-27

### Added
- Empty-heading validation
- Favorites feature for quick access
- Unit tests for heading validation and favorite note serialization

## [1.0.59] - 2026-06-26

### Fixed
- Sync race condition
- Pin icon behavior
- Clipboard toast notifications
- Heading capitalization

## [1.0.58] - 2026-06-19

### Fixed
- Note editor UI issues
- Keyboard gap in editor

## [1.0.56] - 2026-06-18

### Fixed
- Quick capture UI bugs: scroll positioning, heading color, icons, placeholder popup

## [1.0.54] - 2026-06-14

### Added
- Settings: import/export preferences as JSON
- CI/CD: GitHub Release publishing with both APKs

### Fixed
- Settings serializer compile error

## [1.0.50] - 2026-06-13 *(local build, no GitHub Release)*

### Added
- M3: capture templates: org-capture for Android
- M4: sync engine + Room index: laptop edits appear on reopen
- M5: full org editor: syntax highlighting, metadata, structural ops
- M6: full-text + structured search, saved searches, agenda
- M7: zero-friction capture surfaces + polish
- README and docs (architecture, design decisions, search syntax, terminology)
- Icon color picker to notebook long-press menu
- Prefill heading stars in the capture editor
- Baseline profile for performance optimization
- Macrobenchmark module for startup and scroll timing
- Journal and Quick Note launcher shortcuts
- Org file handler registration
- O(1) fold detection in outlines instead of O(n²)
- Outline row title annotation caching

### Changed
- Outline: apply node edits optimistically instead of reloading from disk
- Editor: memoize the buffer parse in the view model
- Editor: cache syntax-highlight result and drop redundant headline spans
- Vault: fetch single-file metadata via stat() instead of listing the tree
- Search: run filtering and snippet building off the main thread
- Release: enable R8 minification and resource shrinking
- Editor: auto-capitalize sentences while typing
- Outline opens fully collapsed by default
- Editor toolbar spacing and icon sizing enlarged/unified
- Render org links in headings instead of raw `[[target][desc]]`
- Editor link button inserts named placeholders

### Fixed
- Top bar overlapping the status bar in edge-to-edge mode
- Capture crash: escape regex braces/brackets for Android's ICU engine
- Notebook creation: SAF providers appended .txt to new .org files
- Empty notebook UX: outline FAB adds a top-level note
- Various outline/editor/capture/home bugs
- Outline swipe-reveal panel colors and re-arm behavior

## [1.0.3] - 2026-06-11 *(local build, no GitHub Release)*

### Added
- Initial Android Studio Compose scaffold
- M1: design system, navigation shell, app identity
- M2: lossless org engine + SAF vault: browse real notes
