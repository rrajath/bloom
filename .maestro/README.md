# Maestro end-to-end flows (test suite Layer 3 / M5)

Design: `internal/test-suite-03-e2e-maestro.md`. Status: **passing** — all
flows verified against a Pixel_9a AVD (API 35, AOSP keyboard) on 2026-09-07.

## What runs

| Flow | Journey |
|---|---|
| `flows/02-capture-to-confirmation.yaml` | Capture a note via the "Quick Note" template, confirm it lands in Search |
| `flows/03-search-open-note.yaml` | Search `photosynthesis`, open the result in Read mode |
| `flows/04-edit-note-save.yaml` | Open a note, edit it, reopen, confirm the edit persisted |
| `flows/05-follow-links.yaml` | Follow every org link form from Read mode (27 cases, ~2.5 min) — see below |
| `scripts/sync-conflict-notifications.sh` | Simulate Syncthing conflict copies and verify notification behavior — see below |
| `flows/07-auto-archive-done.yaml` | Turn on auto-archive in Settings, mark a task DONE, confirm it's refiled to the configured location |
| `flows/08-refile-note.yaml` | Left-swipe "Refile" on a heading with a child, move it (as a subtree) into another notebook |
| `flows/09-favorite-unfavorite.yaml` | Favorite/unfavorite a note from the outline's right-swipe panel and from the Read-mode metadata sheet |
| `flows/10-add-note-to-heading.yaml` | Log a free-text LOGBOOK note against a heading from the outline's right-swipe panel and from the metadata sheet |
| `flows/11-add-note-position.yaml` | Create a new note above, below, and as a child of an existing note via the outline's left-swipe panel |
| `flows/12-pin-unpin-notebook.yaml` | Pin/unpin a notebook from its long-press context menu on the Notebooks screen |
| `flows/attachments/01-dir-inheritance.yaml` | A child heading's image resolves through its parent's `:DIR:`, the attach sheet names the parent as the folder's source, and changing the parent's `:DIR:` moves the child's file too. Seeded by `grove_test_seed_set: attachments` (`debug/DebugTestAttachments`, which generates the image); `grove_test_cleanup` wipes the vault afterwards |
| `scripts/ignore-list-settings.sh` | Settings § Notebooks' "Ignore list" row: the `.orgzlyignore` one-shot import (no file / empty file / imported with dedup) plus adding a pattern by hand and confirming it takes effect — see below |

Journey 01 (onboarding + the SAF system folder picker) is **deferred** — see the
design doc. Flows 02–05 skip onboarding entirely via the debug hook.

### Flow 05: follow-links

Opens the "Link Hub" note (`links-hub.org`, anchor word `linkhub`) whose body
holds one of every org link form, taps each, and asserts the landing. The
resolver (`org/OrgLinkParser` + `DocumentViewModel.resolveOrgLink`) routes every
form to one of four outcomes, one parametrised subflow each:

| Subflow | Outcome | Asserts |
|---|---|---|
| `subflows/follow-link-to-read.yaml` | Read mode of a heading | a `REACHED-*` body marker, then `back` |
| `subflows/follow-link-to-outline.yaml` | a file's Outline | `outline_file_label` text, then `back` |
| `subflows/follow-link-to-external.yaml` | OS hand-off (`https:`) | Read screen gone + URL in the browser, then `back` |
| `subflows/follow-link-to-toast.yaml` | `grove_toast` (`mailto:` with no handler, or unresolved) | toast regex, no navigation |

Covered: `*Heading` / bare-fuzzy / `#custom-id` / `id:` heading (same file);
`id:` **file-level** and `file:this.org` (→ own outline); `id:` heading, and
`file:`/`./`/bare `other.org` with `::*Heading` / `::#custom-id` (other file);
`id:` file-level and three path spellings of a whole other file; `::*Missing` /
`::#missing` (file resolves, heading gone → outline fallback); `https:` labelled
/ bare / in prose, `mailto:`; and five unresolved forms (`id:`, `#`, `*`,
`file:`, cross-file fuzzy).

### Flow 06: sync-conflict notifications

Not a plain Maestro YAML flow — `scripts/sync-conflict-notifications.sh`. It
simulates Syncthing dropping `*.sync-conflict-<ts>-<device>.org` copies
directly into the debug test vault (`adb shell` writes into
`/storage/emulated/0/Android/data/com.rrajath.grove.debug/files/testvault`,
which `adb shell` can read/write without root), triggers a sync pass via a
small Maestro flow (pull-to-refresh on the Notebooks list), and asserts
`SyncManager`'s posted notification via `adb shell dumpsys notification`.
Maestro's YAML has no filesystem-injection or NotificationManager-inspection
primitive, so this can't be a pure `.yaml` flow the way 02-05 are.

Run: `./gradlew :app:installDebug && .maestro/scripts/sync-conflict-notifications.sh`
(needs exactly one connected device/emulator — an AVD, never the physical
device with the real vault).

`SyncManager.notifyConflicts` is edge-triggered: it posts only on the
0 -> 1+ conflict transition, count-only text ("N sync conflict(s) found"), and
stays silent while the conflict set persists or grows/shrinks-but-nonzero —
even across a user dismiss — until it empties and a fresh conflict starts a
new episode.

Covers (verified against Pixel_9a, API 35, 2026-09-12; re-verify after the
edge-trigger rewrite):
- a conflict copy of a non-`.org` file posts no notification and shows no
  in-app indication (`SyncEngine` filters conflicts to `.org` basenames);
- two `.org` conflicts landing in the same sync pass post exactly one
  notification with count-only pluralized text ("2 sync conflicts found");
- a 3rd conflicting file landing while one notification is already posted
  does **not** re-post or update it — text and `when` stay byte-for-byte
  identical;
- an unchanged conflict set across repeated sync passes does not re-post —
  `when` stays byte-for-byte identical, proven via `dumpsys notification`
  rather than the (identical either way) visible text;
- resolving every conflicted notebook through ConflictScreen ("Keep
  current") cancels the notification;
- once all conflicts clear, a fresh conflict re-notifies with a new `when`
  ("1 sync conflict found").

### Ignore-list settings: `scripts/ignore-list-settings.sh`

Also not a plain Maestro YAML flow, for the same class of reason as Flow 06:
the app's `.orgzlyignore` import (`GroveApplication.onCreate`) is a **one-shot
check that runs on the very first process start after install**, before any
UI interaction is possible. To control what it finds, `.orgzlyignore` has to
already exist in the vault directory before that first launch — which needs
`adb shell` writes ahead of a `pm clear`-fresh install, not a Maestro
primitive. Each scenario below is its own `pm clear` + `adb shell` vault
write + `maestro test` run (`subflows/launch-direct-vault.yaml`, which skips
the bundled `.org` fixture seed so the adb-pushed files survive the launch).

Run: `./gradlew :app:installDebug && .maestro/scripts/ignore-list-settings.sh`
(needs exactly one connected device/emulator — an AVD, never the physical
device with the real vault).

Covers (**UNVERIFIED ON-DEVICE** — written from `SettingsNotebooksScreen.kt`,
`GroveApplication.kt` and `SyncEngine.kt` source, not yet run against a real
emulator):
- no `.orgzlyignore` file: "No .orgzlyignore file found." and an empty field;
- an empty `.orgzlyignore` file: "Found a .orgzlyignore file and it's
  empty." and an empty field;
- a `.orgzlyignore` with a few patterns and `#` comment lines: "Imported from
  .orgzlyignore." and the field shows exactly the deduped, comment-stripped
  patterns;
- same import, then typing one more pattern into the field by hand and
  confirming it actually takes effect — a matching notebook drops out of the
  Notebooks list after a few pull-to-refresh passes, while a non-matching one
  stays.

Known fragile points, same spirit as flows 07-12's caveats:
- **No testTag on the Ignore list `OutlinedTextField`.** Selectors fall back
  to position (`above: {text: "Dot-prefixed folders.*"}`, the constant text
  right below the field) rather than an id. Worth a testTag if this flow
  proves worth keeping.
- **Asserting "the field is empty"** uses `text: "^$"` anchored `above` that
  same constant text, since the field has no label/placeholder to check
  instead — unverified that Maestro's accessibility dump exposes an empty
  Compose `OutlinedTextField`'s text as `""` rather than omitting the node.
- **Editing the field's content** clears it (`eraseText: 500`) and retypes
  the full expected content line-by-line with `pressKey: Enter` between
  lines, rather than tapping to place a cursor and appending — a tap's
  landing offset inside a multi-line field isn't reliably "end of text", and
  `adb shell input text` doesn't reliably turn an embedded `\n` into a real
  line break.

## The debug test-vault hook

Flows launch the **debug** build (`com.rrajath.grove.debug`) with two intent
extras:

```
--ez grove_test_direct_vault true    # vault = <app external files>/testvault, no SAF picker
--ez grove_test_seed true            # (re)write the .org fixtures into it on launch
```

`DebugTestVault` (`app/src/main/java/com/rrajath/grove/debug/`) reads them in
`MainActivity.onCreate`, points `GroveApplication.fileStore` at a `JvmFileStore`
via `TestVaultHook`, and marks onboarding skipped. Everything is a no-op unless
`BuildConfig.DEBUG`; the release APK carries none of it and none of the fixture
assets.

### Fixtures — single source of truth

The `.org` bodies live in `app/src/testFixtures/resources/fixtures/`. They are:

- read by `OrgFixtures` on the JVM/UI test classpath, and
- copied into the debug APK's `assets/fixtures/` by the `copyDebugTestFixtures`
  Gradle task, which is what the hook seeds from.

Edit the files in `src/testFixtures/resources/fixtures/` and both layers follow.

## Running locally

```bash
curl -Ls "https://get.maestro.mobile.dev" | bash   # first time; adds ~/.maestro/bin

./gradlew :app:installDebug
maestro test .maestro/flows                          # all
maestro test .maestro/flows/03-search-open-note.yaml # one
maestro test --include-tags e2e .maestro
```

Use an emulator, not the physical device that carries the real vault. Create
the AVD from a plain **AOSP** system image (`system-images;android-36;default;…`),
not a Google APIs / Play Store image. The Play images ship Gboard, whose
first-run "glide typing" popup and floating toolbar break input and back
navigation; the AOSP image (`com.android.inputmethod.latin`) has neither, and
matches what CI runs (`reactivecircus/android-emulator-runner` with no
`target:` defaults to `default`).

The flows call `hideKeyboard` after each `inputText` that is followed by a tap
or a `back`, so a stray IME can't hide the next target or absorb the first
`back`. Keep that pattern when adding flows.

## CI

`e2e-maestro` job in `.github/workflows/build.yml` — manual "Run workflow" only
(`workflow_dispatch`). Boots API 24 and API 36 emulators (a matrix; Maestro's driver needs API 24+), installs the debug APK, runs
`maestro test .maestro/flows`. A Maestro Cloud variant is noted in a comment
there.

## Known fragile points (verify first)

- **Capture template pick** (flow 02): assumes >1 template so the picker sheet
  shows and "Quick Note" is tappable; the tap is marked `optional` for a
  single-template vault.
- **Index latency** (flows 02–04): Search waits use `extendedWaitUntil` with 15 s
  timeouts to absorb the reindex after a file write. May need tuning. (`assertVisible`
  itself has no `timeout` property — only `extendedWaitUntil` does.)
- **Re-open after edit** (flow 04): `edit_note_save` keeps you in the editor, so
  the flow does a single `back` to the Search screen (query + result still
  there), `hideKeyboard` (that screen re-focuses the field), then taps the
  result row again to re-open the note in Read mode. Deliberately not a fixed
  `back` chain to Notebooks — a second `back` on the Search screen clears the
  query instead of leaving, so the count was unstable.
- **Link taps** (flow 05): each link is its own list item so its rendered text
  is the whole node — Maestro's `text:` selector is a full-match regex, so a
  link that shares a line with other prose won't match. Keep one link per line
  in `links-hub.org`. External returns use a single `back` (not `launchApp`,
  which starts MainActivity fresh and drops the Read-mode back stack). The
  `scrollUntilVisible` steps run at the default speed on purpose — `speed: 100`
  overshoots and intermittently misses a mid-list link.
- **Verified** against a Pixel_9a AVD (API 35, AOSP keyboard) on 2026-09-07:
  flows 02-05 pass. Re-check selectors if the Search, editor, or Read screens
  change.
- **Flows 07-12 are unverified on-device.** They're written directly from the
  production source (`SwipeRevealRow.kt`, `OutlineScreen.kt`, `MetadataSheet.kt`,
  `RefileSheet.kt`, `StatePickerSheet.kt`, `AutoArchive.kt`, `NotebooksScreen.kt`)
  but haven't been run yet. Two things worth checking first on a real run:
  - **None of `SwipeRevealRow`, `SwipeAction`, `MetadataSheet`, `RefileSheet`,
    `StatePickerSheet`, or `NoteDialog` carry a `testTag`** — every interaction
    in flows 07-12 is a `text:` selector against the exact label string in the
    source (e.g. `"Fav"`/`"Unfav"`, `"→ Refile"`, `"★ Favorite"`/`"★ Favorited"`,
    `"+ Add note"`, swipe-panel labels `"State"`/`"Schedule"`/`"Note"`/
    `"Above"`/`"Below"`/`"Sub"`). If any of that copy changes, the flow breaks
    silently rather than through a stable id — consider adding testTags to
    those composables if these flows turn out to be worth keeping long-term.
  - **The element-anchored `swipe: {direction, from: {text: ...}}` step has no
    precedent in this repo** (the only existing swipe, in
    `subflows/trigger-sync.yaml`, is a plain pull-to-refresh with no `from`).
    `SwipeRevealRow` swipes **right** to reveal its `leftActions` panel
    (State/Schedule/Note/Fav) and **left** to reveal `rightActions`
    (Above/Below/Sub/Refile) — flows 07/09/10 swipe right, 08/11 swipe left.
    If the `from`-anchored form doesn't fire the gesture, fall back to a
    percent-coordinate `swipe` like `trigger-sync.yaml` uses.
  - Flow 12 (pin/unpin) is text-only, no swipe — lower risk than the other five.

## CI hardening (2026-10-03)

- `e2e-maestro` runs on API 24 + 36 with `profile: pixel_6` (a tall screen like
  the Pixel_9a AVD; the runner's default AVD is short and pushes rows below the
  fold). Maestro's on-device driver needs API 24+ (`minSdkVersion 24`, adb
  `shell_v2`), so minSdk 23 can't run these flows.
- `scripts/ci-settle-emulator.sh` runs first: waits for the launcher to take
  focus, lets post-boot work quiet down, and dismisses system dialogs.
- `MAESTRO_DRIVER_STARTUP_TIMEOUT=180000` for slow runner emulators.
- Each level uploads its JUnit report plus `--test-output-dir` and
  `--debug-output` (failure screenshots, per-command JSON).
- Flow 05's L22 `mailto:` step accepts either outcome: Bloom's "Couldn't open"
  toast (no handler, newer images) or the OS fallback "Unsupported action"
  dialog (`com.android.fallback`, older images such as API 24).
- To reproduce locally, use an AOSP `default` image AVD with the `pixel_6`
  device (e.g. `Grove_Maestro_API_24`) and pass `--device <serial>`.
