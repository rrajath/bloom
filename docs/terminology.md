# Terminology

Terms used throughout the code, the UI, and these docs. Org-mode terms keep their Emacs meanings; Bloom adds a few of its own.

## Org-mode terms

| Term | Meaning |
|---|---|
| **Org file / org-mode** | A plain-text outliner format from Emacs. Headings start with `*` characters; everything Bloom does is reading and splicing these files. |
| **Headline / heading** | A line starting with one or more stars: `** TODO [#A] Title :tag1:tag2:`. Parsed into level, keyword, priority, title, and tags (`OrgHeadline`). |
| **Level** | A headline's depth = its star count. Level 1 (`*`) is top-level. |
| **Subtree** | A headline plus everything under it: its body and all deeper headlines until the next headline at the same or shallower level. The unit of editing, moving, cutting, and pasting in Bloom. |
| **TODO keyword** | The state word after the stars (`TODO`, `DONE`, …). The set is configurable in Settings using org's syntax, `TODO IN-PROGRESS \| DONE CANCELLED`, where the `\|` separates active from done-type keywords (`OrgKeywords`). |
| **Priority cookie** | `[#A]`–style marker after the keyword. |
| **Tags** | `:work:deep:` at the end of a headline. Notes also *inherit* tags from ancestor headlines and from the file's `#+FILETAGS:` line. |
| **Planning line** | The line immediately under a headline holding `SCHEDULED:`, `DEADLINE:`, and/or `CLOSED:` timestamps. |
| **Properties drawer** | A `:PROPERTIES:` … `:END:` block under a headline holding key–value pairs. Bloom reads `ID`, `CUSTOM_ID`, and `CREATED` in particular. |
| **Timestamp** | `<2026-06-12 Fri>` (active) or `[2026-06-12 Fri]` (inactive), optionally with a time and a repeater (`OrgTimestamp`). |
| **Repeater** | A `+1w` / `++1w` / `.+1w` suffix on a timestamp. Completing a repeating task advances the date instead of closing it; the three forms differ in how they catch up past-due dates. |
| **Task / event** | A heading with a TODO keyword is a task; one with no keyword is an event, regardless of which timestamp fields it carries. See [tasks-and-events.md](tasks-and-events.md) for how each is completed and which surfaces offer a Complete/Done button. |
| **Datetree** | A `year → month → day` heading hierarchy (`* 2026` / `** 2026-06 June` / `*** 2026-06-12 Friday`) used for journals. Capture creates missing nodes on demand, in chronological position. |
| **Capture** | Org's quick-entry workflow: a template says *what* to insert and *where*; the user supplies only the content. |
| **Narrowing** | Restricting the view to one subtree (org's `org-narrow-to-subtree`). In Bloom: swipe left on an outline row or "Show in context". |
| **Folding** | Collapsing a headline so its subtree is hidden. The outline's carets (`▸`/`▾`) and the expand/collapse-all button control fold state. |

## Bloom terms

| Term | Meaning |
|---|---|
| **Vault** | The user's chosen org folder, accessed through a persisted SAF tree URI. Flat: no subdirectories. Also the name of the facade class (`Vault`) that lists/opens/saves files in it. |
| **Notebook** | One `.org` file in the vault. The home screen lists notebooks. |
| **Note** | One *top-level* headline in a notebook, together with its subtree (subheadings are part of their note), **or** the file's intro if it has one. A notebook's note count = level-1 headlines + 1 if there is an intro. |
| **Preamble** | Everything in a file before its first `*` heading. Three parts, each with its own outline section and its own scoped editor: the file-level property drawer, the preface, and the intro. |
| **Preface** | A file's leading `#+KEY:` lines (`#+title:`, `#+filetags:`, …). Shown in the outline as the collapsible `PREFACE` box; double-tapping it opens an editor scoped to those lines alone. |
| **Intro** | Real content (paragraphs, lists, …) between the preface and a file's first `*` heading — or the whole body of a file with no headings, like an org-roam note. Org's own parse tree calls this the zeroth `section`; "zeroth section" is the community shorthand. Distinct from the preface, which is the `#+` keyword lines. Bloom surfaces it as its own note: a tap-only row above the outline, an intro-only read view, an intro-only editor, searchable, counted. Adding metadata to it first inserts a blank `* ` heading above the content. |
| **NoteRef** | How a note is addressed in navigation and deep links: `"file.org@headlineLineIndex"`, or `"file.org@-1"` for the file's intro. A stopgap identity until cross-file org IDs are used. |
| **FileStore** | The minimal suspend interface over a flat directory of files (`list/read/write/create/rename/delete/exists`). `SafFileStore` in production, `JvmFileStore` in tests. |
| **Revision** | A file's change marker: `"mtime:size"`. Sync compares revisions to find externally-changed files; the editor compares them to detect a stale buffer before saving. |
| **Index** | The Room database: one row per notebook and per headline, used for search, tag autocomplete, and the home screen. A *rebuildable cache*: never the source of truth; it can be wiped and rebuilt from the files at any time. |
| **Sync** | For the v1 local-folder backend: detecting changed files (by revision) and re-indexing them. The actual byte transport between devices is Syncthing's job. |
| **Sync mode** | When sync runs: Manual, On open/close (default), Periodic (WorkManager, ≥15 min), or Continuous (10 s polling while the app is foregrounded). |
| **Conflict copy** | A `*.sync-conflict-*` file Syncthing drops next to a notebook when two devices edited it concurrently. Bloom badges the notebook and offers the conflict picker. |
| **Conflict picker** | The resolution UI: *keep current* / *keep conflict copy* / *keep both* (the copy's content is demoted under a `* CONFLICT` heading). No auto-merge in v1. |
| **Delete** | Permanent. SAF exposes no OS-level trash, so deleting a notebook or folder removes the file(s) outright and prunes any directory left empty. Recovery relies on the sync backend's own history (Syncthing file versioning). A confirmation dialog guards both. |
| **`.orgzlyignore`** | An optional file of glob patterns (Orgzly convention) naming `.org` files the app should pretend don't exist. |
| **Capture template** | `(name, icon, target file, target location, template text)`; see [architecture.md](architecture.md#capture-capture). |
| **Target location** | Where a capture inserts: top of file, bottom of file, under a heading (matched by `CUSTOM_ID`, recommended, or exact title), or a datetree. |
| **Placeholder** | A `%`-token in template text expanded at capture time: timestamps (`%t %T %u %U`), date parts (`%date %time %day %year %month`), content (`%clipboard %shared_text %shared_url`), prompts (`%^{Title}`), and cursor position (`%cursor` / `%?`). |
| **Star prefill** | The capture editor pre-inserts the heading stars an entry will receive on save (e.g. `**** ` for a datetree entry), so what you see is what lands in the file. |
| **Stale file** | The file changed on disk while a note was being edited (revision mismatch at save). The editor refuses to silently overwrite and offers Overwrite (force save) or Reload. |
| **Force reload** | Drops a notebook's index rows and re-pulls it from disk: the "turn it off and on again" for index/file disagreements. |
| **Saved search** | A named query in the drawer (defaults: Scheduled Today `s.today`, All TODO `i.todo`, This Week `s.7d`). |
| **Agenda** | A search result grouped by day via the `ad.N` directive; the drawer's Agenda item is `ad.7`. Overdue items surface on today. |
| **`GroveColors` / `syn-*` tokens** | The design-token palette (`GroveColors`, `MaterialTheme.grove`), including org syntax colors (`synStar`, `synTodo`, `synDone`, `synTs`, `synTag`, `synLink`, `synProp`) and the 6-color heading-star cycle (green → blue → amber → red → violet → brown, repeating with depth). |
