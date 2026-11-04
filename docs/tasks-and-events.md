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
| **Bare active timestamp** | A `<date>` (optionally with a time or range) directly in the heading's body | An event occurrence, not planning metadata. The heading shows up on the Agenda on that day, with no overdue/deadline styling. |

A bare active timestamp comes in two flavors that matter for repeaters:

- **Dedicated**: sits alone on the managed line right after the planning line (or right after the heading if there's no planning line). This is the line `OrgMutations.setActiveTimestamps` rewrites, and the only one a Complete/Done action can advance.
- **Inline in prose**: a `<date>` typed anywhere else in the body text. Bloom reads and displays it but never rewrites it, so a repeater on an inline stamp gets no button anywhere.

A heading can carry any combination of the three fields at once (e.g. both SCHEDULED and DEADLINE), and each is tracked independently.

## Repeaters

A repeater is a `+1w` / `++1w` / `.+1w`-style suffix written *inside* a timestamp's brackets, e.g. `<2026-06-09 Mon +1w>`. The three forms (`OrgTimestamp.RepeaterType`) differ only in how they catch up a date that's fallen behind:

- `+N` (**CUMULATIVE**): shift exactly one interval from the old date.
- `++N` (**CATCH_UP**): shift by intervals until the date lands in the future.
- `.+N` (**FUTURE**): shift one interval from today, regardless of the old date.

Completing a repeating timestamp always advances its date to the next occurrence instead of closing or marking anything done. A repeater only earns a Complete/Done affordance when it sits on a field Bloom actually manages: SCHEDULED, DEADLINE, or the dedicated active-timestamp line. A repeater typed inline in prose is display-only and offers no button, since there's no managed line to rewrite.

## Completing a task

- **No repeater anywhere on it**: the keyword becomes the first configured done-type keyword (usually `DONE`) and a CLOSED timestamp is added. If Settings has auto-archive on, the heading is then refiled to its archive location.
- **SCHEDULED and/or DEADLINE repeats**: the keyword is left exactly as it was (a repeating task never reaches a done keyword, matching Emacs' `org-todo` repeat handling), the repeating date(s) advance, and a LOGBOOK state-change note plus a `LAST_REPEAT` property are recorded. Auto-archive never applies here, since there's no done keyword to trigger it.

Either way, a clock running on the task is clocked out first (`org-clock-out-when-done`; Settings › Notes › Clocking can turn this off), from every surface that completes it.

## Advancing an event's timestamp

An event has no keyword to change, so "completing" it never sets a keyword and never touches LOGBOOK or `LAST_REPEAT`:

- **Nothing on it repeats**: there's nothing to advance, so no Complete/Done affordance is offered anywhere.
- **Exactly one field repeats** (SCHEDULED, DEADLINE, or the dedicated active-timestamp line): that field's date advances to its next occurrence. Nothing else about the heading changes.
- **More than one field repeats** (e.g. both SCHEDULED and DEADLINE): each is tracked and advanced independently. A heading like this can produce more than one Agenda/widget row (one per occurrence), and each row's own affordance only ever advances the specific field that placed it there, never every repeating field on the heading at once.

## Where the affordance appears

| Heading state | Agenda checkbox / swipe | Widget circle | Notification Complete | Effect of completing |
|---|---|---|---|---|
| Task, no repeater | Yes | Yes | Yes | Sets a done keyword + CLOSED; auto-archives if enabled |
| Task, SCHEDULED and/or DEADLINE repeats | Yes | Yes | Yes | Keyword unchanged; repeating date(s) advance; LOGBOOK note + `LAST_REPEAT` recorded |
| Event, no repeater anywhere | No | No | No (only Reschedule) | Nothing to complete |
| Event, SCHEDULED repeats | Yes | Yes | Yes | SCHEDULED date advances; nothing else touched |
| Event, DEADLINE repeats | Yes | Yes | Yes | DEADLINE date advances; nothing else touched |
| Event, dedicated bare active timestamp repeats | Yes | Yes | Yes | That timestamp's date advances; nothing else touched |
| Event, repeater typed inline in prose | No | No | No | Nothing to rewrite; not a managed line |

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
