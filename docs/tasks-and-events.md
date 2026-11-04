# Tasks and Events

How Bloom decides whether a heading is a task or an event, how SCHEDULED, DEADLINE, and bare active timestamps behave with and without a repeater, and which surfaces (Agenda screen, home-screen widget, notification) offer a Complete/Done affordance for each combination.

## Task vs. event

The split is the presence of a TODO keyword, nothing else:

- **Task**: the heading carries a keyword from the configured set (`TODO`, `DONE`, `NEXT`, `WAITING`, whatever `OrgKeywords` defines), active or done-type. `* TODO Buy milk`.
- **Event**: the heading carries no keyword at all. `* Team offsite` or `* Take out compost` with a `SCHEDULED:` line underneath.

This is independent of which timestamp fields are set. A keyword-less heading with a SCHEDULED date is still an event, not a task with an implicit state.

## The three timestamp fields

| Field | Where it lives | What it means |
|---|---|---|
| **SCHEDULED** | Planning line under the heading | "Work on this starting on this date." Places the heading on the Agenda from that date onward (and into the overdue bucket once past). |
| **DEADLINE** | Planning line under the heading | "This is due by this date." Places the heading on the Agenda, styled as due/overdue rather than scheduled. |
| **Bare active timestamp** | A `<date>` (optionally with a time or range) directly in the heading's body | An event occurrence, not planning metadata. The heading shows up on the Agenda on that day (and, with a repeater, on every later occurrence), with no overdue/deadline styling. |

A bare active timestamp comes in two flavors:

- **Dedicated**: sits alone on the managed line right after the planning line (or right after the heading if there's no planning line). This is the line `OrgMutations.setActiveTimestamps` rewrites and the Dates editor's ACTIVE tab manages.
- **Inline in prose**: a `<date>` typed anywhere else in the body text. Bloom reads and displays it; a Complete/Done action on a repeating one rewrites just that stamp in place, leaving the rest of the line untouched.

A heading can carry any combination of the three fields at once (e.g. both SCHEDULED and DEADLINE), and each is tracked independently.

## Repeaters

A repeater is a `+1w` / `++1w` / `.+1w`-style suffix written *inside* a timestamp's brackets, e.g. `<2026-06-09 Mon +1w>`. The three forms (`OrgTimestamp.RepeaterType`) differ only in how they catch up a date that's fallen behind:

- `+N` (**CUMULATIVE**): shift exactly one interval from the old date.
- `++N` (**CATCH_UP**): shift by intervals until the date lands in the future.
- `.+N` (**FUTURE**): shift one interval from today, regardless of the old date.

Completing a repeating timestamp always advances its date to the next occurrence instead of closing or marking anything done. A zero interval (`+0d`) counts as no repeater, as in Emacs. Month and year repeaters follow Emacs's arithmetic: a day the target month lacks overflows into the next month, so `<2026-01-31 Sat +1m>` advances to Mar 3 and a Feb 29 `+1y` to Mar 1 (`plusOrgUnits`).

On the Agenda, widget, and Search `ad.N`, a repeating bare active timestamp shows on its own date and on every later occurrence, projected from the stored date the way the Emacs agenda does (`OrgTimestamp.occurrenceCovering`). All three forms display alike; the form only matters when the entry is completed. Unlike the Emacs agenda, a repeating range and a repeater followed by a warning cookie (`<... +1w -1d>`) repeat too. SCHEDULED and DEADLINE still show on their stored date only (see `internal/2026-11-04-emacs-date-parity-report.md` for the remaining Emacs gaps).

## Completing a task

- **No repeater anywhere on it**: the keyword becomes the first configured done-type keyword (usually `DONE`) and a CLOSED timestamp is added. If Settings has auto-archive on, the heading is then refiled to its archive location.
- **Any active timestamp in the entry repeats** (SCHEDULED, DEADLINE, the dedicated line, an inline body stamp, or one in the heading title; `OrgMutations.isRepeating`, matching Emacs's `org-get-repeat`): the task never reaches a done keyword. Following Emacs's `org-auto-repeat-maybe`, every repeating active timestamp in the entry advances, a SCHEDULED without a repeater and any CLOSED stamp are dropped, the keyword returns to the heading's `REPEAT_TO_STATE` property if it names a keyword, else to the first active keyword (so `NEXT` goes back to `TODO`), and a LOGBOOK state-change note plus a `LAST_REPEAT` property are recorded. Auto-archive never applies here, since there's no done keyword to trigger it. A done-to-done change (DONE to CANCELLED) never repeats.

## Advancing an event's timestamp

An event has no keyword to change, so "completing" it never sets a keyword and never touches LOGBOOK or `LAST_REPEAT`:

- **Nothing on it repeats**: there's nothing to advance, so no Complete/Done affordance is offered anywhere.
- **Exactly one field repeats** (SCHEDULED, DEADLINE, or a bare active timestamp, dedicated or inline): that field's date advances to its next occurrence. Nothing else about the heading changes.
- **More than one field repeats** (e.g. both SCHEDULED and DEADLINE): each is tracked and advanced independently. A heading like this can produce more than one Agenda/widget row (one per occurrence), and each row's own affordance only ever advances the specific field that placed it there, never every repeating field on the heading at once.

## Where the affordance appears

| Heading state | Agenda checkbox / swipe | Widget circle | Notification Complete | Effect of completing |
|---|---|---|---|---|
| Task, no repeater | Yes | Yes | Yes | Sets a done keyword + CLOSED; auto-archives if enabled |
| Task, any active timestamp repeats | Yes | Yes | Yes | Keyword returns to `REPEAT_TO_STATE` or the first active keyword; every repeating stamp advances; non-repeating SCHEDULED and CLOSED dropped; LOGBOOK note + `LAST_REPEAT` recorded |
| Event, no repeater anywhere | No | No | No (only Reschedule) | Nothing to complete |
| Event, SCHEDULED repeats | Yes | Yes | Yes | SCHEDULED date advances; nothing else touched |
| Event, DEADLINE repeats | Yes | Yes | Yes | DEADLINE date advances; nothing else touched |
| Event, bare active timestamp repeats (dedicated or inline) | Yes | Yes | Yes | That timestamp's date advances in place; nothing else touched |

A notification's Complete action and the Agenda/widget's done affordance are the same operation viewed from three surfaces: they always agree on whether a heading gets a button and on what tapping it does.

## Where this lives in code

- `org/OrgTimestamp.kt`: `Repeater`, `RepeaterType`, `advanceRepeater(today)`, the date math shared by every path below.
- `org/OrgMutations.kt`: `markDone` (task completion, repeating or not), `advanceRepeatingPlanning` (event SCHEDULED/DEADLINE repeater), `advanceActiveTimestamp` (event bare active timestamp repeater, dedicated line only).
- `ui/agenda/AgendaViewModel.kt`: `AgendaRow.repeaterKind` and `AgendaRow.hasDoneAffordance` decide whether a row gets the checkbox/swipe; `toggleDone` (tasks) and `advanceRepeater` (events) are the two entry points.
- `widget/LedgerWidget.kt`: `MarkDoneAction` mirrors the same split for the home-screen ledger widget.
- `reminders/ReminderActionReceiver.kt`: `complete()` mirrors the same split for the notification's Complete action.

## Related

- [Terminology](terminology.md): definitions of timestamp, repeater, planning line, and other org-mode terms used above.
- [Architecture](architecture.md): how `org/`, `ui/agenda/`, `widget/`, and `reminders/` fit into the rest of the codebase.
