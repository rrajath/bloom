# Bloom

*Formerly Grove.*

<p align="center">
  <a href="https://github.com/rrajath/bloom/actions/workflows/build.yml?query=branch%3Amain+event%3Apush"><img src="https://img.shields.io/github/actions/workflow/status/rrajath/bloom/build.yml?branch=main&event=push&label=build&style=for-the-badge" alt="Build status" /></a>
  <a href="https://github.com/rrajath/bloom/releases/latest"><img src="https://img.shields.io/github/v/release/rrajath/bloom?style=for-the-badge" alt="Latest release" /></a>
  <a href="https://f-droid.org/packages/com.rrajath.grove/"><img src="https://img.shields.io/f-droid/v/com.rrajath.grove?style=for-the-badge&logo=fdroid&logoColor=white" alt="F-Droid version" /></a>
  <a href="https://github.com/rrajath/bloom/releases"><img src="https://img.shields.io/github/downloads/rrajath/bloom/total?style=for-the-badge" alt="Total downloads" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/rrajath/bloom?style=for-the-badge" alt="License: Apache 2.0" /></a>
  <br />
  <img src="https://img.shields.io/badge/API-23%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Minimum Android API 23" />
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <a href="https://github.com/rrajath/bloom/commits/main"><img src="https://img.shields.io/github/last-commit/rrajath/bloom?style=for-the-badge" alt="Last commit" /></a>
  <a href="https://bloom.rrajath.com"><img src="https://img.shields.io/badge/docs-bloom.rrajath.com-0A7E3E?style=for-the-badge&logo=readthedocs&logoColor=white" alt="Documentation" /></a>
</p>

**A native Android Org-mode note-taking app: a first-class mobile companion for Emacs org-mode users.**

Bloom edits plain `.org` files in a folder you choose. There is no account, no proprietary database, and no export step: the files on disk *are* your notes, byte-for-byte, and they sync to your laptop with whatever tool you already trust (Syncthing is the recommended pairing). If you stop using Bloom tomorrow, your notes are exactly where they always were.

<p align="center">
  <a href="https://f-droid.org/packages/com.rrajath.grove">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
         alt="Get it on F-Droid" height="80" />
  </a>
  <a href="https://play.google.com/store/apps/details?id=com.rrajath.grove">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
         alt="Get it on Google Play" height="80" />
  </a>
  <a href="https://github.com/rrajath/bloom/releases">
    <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png"
         alt="Get it on GitHub" height="80" />
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/rrajath/bloom">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png"
         alt="Get it on Obtainium" height="80" />
  </a>
</p>

## Highlights

- **File-first**: `.org` files in a synced folder are the sole source of truth. Bloom's internal database is only a rebuildable search index.
- **Non-destructive org engine**: a custom parser models documents as a thin view over the raw text. Parse → serialize is byte-identical by construction, so Bloom never reformats a file it didn't deliberately edit.
- **Zero-friction capture**: org-capture style templates with `%U`, `%^{prompt}`, `%cursor`-style placeholders; targets include top/bottom of file, under a heading picked from the target file (found by its `ID` or outline path), and day/week/month date trees, optionally inside a heading. Reachable from a home-screen widget, the share sheet, an optional persistent notification, and `bloom://capture` deep links (the pre-rename `grove://` scheme still works).
- **Home-screen widgets**: a one-tap Capture widget, and an Agenda ledger widget (overdue + day-grouped upcoming tasks, mark-done and a small quick-add composer right from the home screen — no need to open the app).
- **Real editor**: raw org subtree editing with syntax highlighting, a formatting toolbar (the link button inserts a URL link on tap, or long-press to link to any file or heading in the vault, by name or by `ID`/`CUSTOM_ID`), list continuation on Enter, a metadata sheet (TODO state, priority, tags with autocomplete, SCHEDULED/DEADLINE), repeater advancement on DONE, and autosave with a stale-file guard (switchable off in Settings › Notes, in which case read mode renders your unsaved buffer until you save).
- **One canvas for both dates**: scheduling opens a full-screen editor that puts SCHEDULED and DEADLINE on the same calendar (with the lead time between them shaded and a warning if you'd start already late), and commits both in one edit. Each carries presets, a time range and an org repeater, and a shorthand box parses lines like `fri 10-11am ++1w` or `d: aug 5` as you type.
- **Reminders**: a notification fires when a heading's SCHEDULED or DEADLINE time arrives, with Complete (marks the heading done) and Reschedule (reopens the dates screen) actions right on the notification. Enabled by default. Notification access is asked for first; once it's on, a one-time prompt asks for Android's "Alarms & reminders" access so reminders fire on the minute instead of up to an hour late. Settings › Reminders shows whichever access is still missing, with an Allow button.
- **Outline operations**: collapsible heading tree with body previews, expand/collapse all, move/cut/copy/paste subtrees, cycle state by swipe, narrow to a subtree.
- **Heading-less content is first-class**: text before a file's first heading (or a whole org-roam note with no headings) shows as its own row above the outline, opens in a read view, is counted as a note, and is searchable. Adding metadata to it inserts a blank heading above the content first.
- **Nested vault folders**: subdirectories inside the vault are first-class. The Notebooks screen shows the whole vault as an inline tree (a folder with more than 20 files becomes a tappable drill-down instead), a notebook's identity is its vault-relative path, and `＋` / "Move to folder…" create and relocate notebooks across folders.
- **Orgzly-compatible search**: `i.todo s.7d t.work .b.archive OR p.a`, saved searches, and an `ad.N` agenda view. Backed by a SQLite FTS5 trigram index, so substring search stays instant as the vault grows and covers full note bodies. See [docs/search-syntax.md](docs/search-syntax.md).
- **Sync that respects your tools**: change detection by file revision, Syncthing `.sync-conflict-*` detection with a keep-local / keep-remote / keep-both picker, manual through continuous sync modes, `.orgzlyignore` support.
- **A calm, deliberate design**: IBM Plex Sans/Serif/Mono, 20 light and dark themes (Bloom Light by default, plus Bloom Dark, Catppuccin, Rosé Pine, Tokyo Night and more), and org syntax tokens colored the way an Emacs theme would.

## Screenshots

| Notebooks                                                                           | Outline                                                                         | Read mode                                                                          |
|-------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| ![Notebooks](fastlane/metadata/android/en-US/images/phoneScreenshots/1.png) | ![Outline](fastlane/metadata/android/en-US/images/phoneScreenshots/2.png) | ![Read mode](fastlane/metadata/android/en-US/images/phoneScreenshots/3.png) |

| Edit mode                                                                          | Agenda                                                                        | Widget                                                                        |
|------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| ![Edit mode](fastlane/metadata/android/en-US/images/phoneScreenshots/4.png) | ![Agenda](fastlane/metadata/android/en-US/images/phoneScreenshots/5.png) | ![Widget](fastlane/metadata/android/en-US/images/phoneScreenshots/6.png) |

## Getting started

### Requirements

- Android 6.0+ (`minSdk 23`)
- To build: JDK 17+, Android SDK 36. Open in Android Studio, or:

```bash
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint
```

### Testing

```bash
./gradlew testDebugUnitTest        # JVM unit tests (run in CI on every push)
./gradlew connectedAndroidTest     # instrumented + Compose UI tests (needs a device/emulator)
```

CI runs the unit tests on every push and the Compose UI tests nightly. Maestro
end-to-end flows live in `.maestro/`.

#### Performance benchmarks

The Macrobenchmark suite (`:macrobenchmark`) measures cold/warm startup and
scroll jank, and regenerates the Baseline Profile that ships in the APK. It is
**not** run in CI: benchmark numbers off a shared-runner emulator are noise, so
run it locally on a real device instead.

```bash
./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest   # startup + scroll benchmarks
./gradlew :app:generateBaselineProfile                           # writes app/src/release/generated/baselineProfiles/
```

Results land in `macrobenchmark/build/outputs/connected_android_test_additional_output/`.

### First run

1. Launch Bloom and pick your org folder (any folder reachable through Android's file picker).
2. Point Syncthing (or any other sync tool) at the same folder to share it with your other machines.
3. Capture something.

## Roadmap
Following is the list of features/enhancements I'm planning to make to this app:
- [ ] Image support
- [ ] Nested search expressions
- [ ] WebDAV support
- [x] Clickable org links
- [x] Tips & Tricks page
- [x] What's New screen (Settings › About)
- [x] Multi-day events
- [x] Bare active timestamps

## Project layout

```
app/src/main/java/com/rrajath/grove/
├── org/        Org-mode engine: parser, mutations, timestamps, line editing
├── vault/      File access: recursive FileStore abstraction, SAF + JVM impls, Vault facade
├── sync/       Sync engine, state machine, conflict handling, Android triggers
├── data/       Room index (rebuildable cache) over the vault
├── capture/    Capture templates, placeholder expansion, entry insertion
├── search/     Orgzly-style query parser, matcher, snippets, saved searches
├── settings/   Preferences (DataStore) and settings repository
├── widget/     Home-screen widgets (Capture, Agenda ledger) + persistent capture notification
└── ui/         Jetpack Compose screens, view models, theme
```

## Documentation

| Document | What it covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layers, data flow, threading, and the invariants that hold the app together |
| [docs/terminology.md](docs/terminology.md) | Glossary of org-mode and Bloom-specific terms used throughout the code |
| [docs/search-syntax.md](docs/search-syntax.md) | Full reference for the search query language |

## Docs site

`docs-site/` is the public landing page and user docs (Astro + Starlight), deployed as a Cloudflare Worker with static assets (`npm run deploy`). It's a separate npm project — see [docs-site/README.md](docs-site/README.md) for setup and deployment details. Changes there don't trigger the app CI workflow (`.github/workflows/build.yml` ignores `docs-site/**`).

## Tech stack

Kotlin 2.2 · Jetpack Compose (Material 3) · Room (on bundled SQLite, for FTS5) · DataStore · WorkManager · Glance · kotlinx.serialization · JUnit 4

## Fonts

Bloom bundles IBM Plex Sans, Serif, and Mono, licensed under the SIL Open Font License (see `FONT_LICENSE_OFL.txt`).

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](https://github.com/rrajath/bloom/blob/main/LICENSE) file for details.

## AI Transparency

This app was built with help from LLM-based coding tools (Claude Code). All design decisions, architecture, and every piece of code are reviewed and tested by me. LLMs are a tool in the workflow, not an autopilot.
