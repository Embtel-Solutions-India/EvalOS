# Unit 28 - Dashboard date filters: calendar periods, completed periods, custom range

> **Status: built.** Backend, frontend, migration, tests and docs landed together.

## What was asked for

"This week, this month, this year and drop down for last month, last year and
date to date filter."

## What it builds

- **Four buttons** - Today, This week, This month, This year - all now
  **calendar-to-date**.
- **A dropdown** - Last month, Last year, Custom range - the periods picked
  occasionally rather than constantly.
- **A date-to-date picker**: two native `<input type="date">`.
- `range=last-month|last-year|custom` plus `from`/`to` on
  `/api/metrics/pm` and all three `/api/marketing/*-pipeline` routes.
- **The production board's date filter split out into its own control**, because
  it points the other way.

## The thing this unit is actually about

The request reads like adding options to a control. It is not, because the
control was **one value read in two opposite directions**:

- the dashboards and the three GHL screens read it **backwards** - "what
  happened since"
- `BoardView` read it **forwards** through `dueBeforeFor` - "what is due before"

`ui-context.md` had recorded that collision for two units, and it had already
cost a defect: the shell default was set to `year` to suit a marketing screen,
which moved the board's deadline horizon from one month out to twelve and left
the production board effectively unfiltered for every role on first load.

**Every one of the three new options breaks that sharing outright.** `last-month`
is a completed past period, so as a "due before" cutoff it returns every open
case. A date-to-date interval is not a cutoff at all - it is two edges where the
board needs one. There was no reading of the new vocabulary that the board could
usefully take.

So the filter was split, and the split is in the **type**: the board owns
`DeadlineWindow` (`week | month | year`, forward), the shell owns `DateRange`
(seven periods, backward). Passing one where the other belongs no longer
compiles. That is the whole reason this is a unit rather than three list entries.

## A range stopped being a number

`DateRange` carried an `int days` and every window was "now minus N days". Both
halves had to go:

- A calendar-to-date period **has no fixed width**. "This month" is one day wide
  on the 1st and 31 wide on the 31st.
- `last-month` **does not end today**, so no amount of subtracting from now
  produces it.

`DateWindow` now resolves a range name into a pair of inclusive days, and it is
the single place that arithmetic lives. `DateRange` keeps only the vocabulary.

Three decisions inside it worth stating:

- **Days are the primitive, instants are derived.** GHL's filter is date-only and
  inclusive on both edges, so days are what it wants natively; the metrics
  callers convert via `startInstant()`/`endInstant()`. The old code had it the
  other way round and shipped a bug for it - an instant window converted to
  dates came out a day too wide, so a screen headed "today" showed yesterday's
  rows too and roughly doubled its figure.
- **The zone is carried, not re-derived.** A window resolved in
  `America/Los_Angeles` produces its instants in that same zone. Holding the zone
  in the record makes disagreement impossible; taking a `Clock` means "what day
  is it" and "in which zone" come from one source, and lets every boundary case
  be tested on a fixed clock rather than waiting for the calendar.
- **`endInstant()` is exclusive** while the days are inclusive. An inclusive end
  would have to be the last representable instant of the day, and anything
  stored with finer precision than that bound falls outside it - a row silently
  dropped from the day it happened on.

## The bug `custom` would have caused, and the migration that prevents it

`ghl_funnel_cache` was keyed `UNIQUE (funnel, range_name)`. That was correct only
while a range name described exactly one window.

**Every custom window is named `custom`.** Keyed by name, two different custom
periods share one row and serve each other's figures for a whole TTL - and it is
undetectable on screen, because the payloads are identical in shape: a January
total under a March heading looks entirely plausible and no log contradicts it.
It is the same failure the `funnel` half of that key already prevents, one axis
over.

So V26 keys on the **resolved window** - `2026-08-01..2026-08-26` - and renames
the column to `window_key` to stop the schema stating something untrue. It also
fixes a smaller existing fault free: a row written for `month` used to keep
answering for `month` after midnight, when "this month" had become a different
window.

V26 **deletes every existing row** rather than translating the keys. The window a
row was computed for depends on the day it was written, which the row does not
record - so a translation would attribute figures to a window they were never
computed for, which is the exact defect being fixed. Deleting is free because the
table is a cache and V25 says so: safe to truncate, costs one slow page load.

## What this changed on screens that already worked

**Every dashboard figure moves, and this is the part to be aware of.** `today`
meant the last 24 hours and now means today in the business's zone; `month` meant
the last 30 days and now means since the 1st. On the 3rd of a month the PM and
revenue dashboards report far smaller numbers than the day before - correctly,
but anyone comparing against a screenshot will think something broke.

The labels are what made the old behaviour wrong. A control saying "This month"
that answered for 30 days spanning two of them was stating something untrue, and
that is not a thing to preserve for continuity.

**`PmMetricsService`'s period-over-period comparison still works and needed no
change.** It compares a window against the equal-length span immediately before
it, so "this month to date" (26 days) is measured against the 26 days before the
1st. That remains a sensible like-for-like comparison, and for `last-month` it
compares one whole month against the one before.

## Two things found on the way

- **`InboxPage` was refetching on a filter it deliberately ignores.** It passes
  `dueBefore: null` because its own presets answer the date question, but the
  shell period was still in its effect deps. Harmless while the filter was four
  names; plainly wrong once one of them can be a custom interval this screen
  would never apply. Removed.
- **`MarketingController`'s `year` default claimed to "match the shell's own
  initial selection".** The shell default is `month` and has been since it was
  reverted for unfiltering the board, so that comment was the only thing keeping
  the two apart. Both now default to `month`. It never bit because the frontend
  always sends an explicit range - a default nothing exercises is exactly where a
  stale claim survives.

## The frontend shape

`DateRange` is a discriminated union - `{kind: NamedRange} | {kind: 'custom',
from, to}` - so a custom period **cannot be constructed without its dates**. The
alternative carries `from`/`to` that are undefined for six of seven cases and
relies on every call site remembering which.

Three helpers keep consumers from branching on `kind`: `rangeParams` (which query
parameters go on the wire), `rangeLabel` (what to call it on screen, including a
custom period's actual dates rather than the word "custom"), and `sameRange`
(which control is lit).

`rangeParams` sends `from`/`to` **only** for `custom`, which is required rather
than tidy: the server refuses explicit dates on a named range with a 400 instead
of ignoring them, so leaking a stale `from` alongside `month` would break the
request outright.

Native `<input type="date">` and a native `<select>`, no picker dependency: both
are keyboard-accessible, localised and mobile-friendly for free.

## Validation, at the boundary

`custom` requires both edges, they must parse as ISO dates, and `from` may not be
after `to`. Equal edges are allowed - a single-day window is a legitimate
question. A `to` in the future is allowed - it is merely empty, and refusing it
would be the server inventing a rule about what is worth asking.

Explicit dates on a **named** range are refused rather than ignored, for the same
reason `DateRange.parse` refuses an unknown name: a caller who writes
`?range=month&from=2026-01-01` means January, and answering for this month
instead is a wrong number wearing a right label.

**No maximum span.** The GHL screens already degrade honestly past their row
ceiling (`UNAVAILABLE`, "narrow the period"), and the metrics queries are bounded
by the table rather than the window. A second limit would be a second place for
"too big" to be defined, and the two would disagree.

## Verification

- `DateWindowTest` (27 cases) is the load-bearing suite, all on a fixed clock:
  every boundary, Monday week starts - including the Sunday case, which is what
  actually distinguishes ISO from a Sunday-start week - `last-month` from the
  31st and in January, leap-year `last-year`, the zone-crossing case at 22:00
  local, and every custom rejection.
- `MarketingPipelineServiceTest` - two different custom windows do not share a
  cache row, asserted **through the service** rather than on `DateWindow.key()`
  alone: a service that keyed on the range name would pass a key test and fail
  this one.
- `MarketingControllerTest` - a custom window's dates reach the service verbatim,
  and the whole family of bad windows comes back 400 `VALIDATION_FAILED` with
  nothing reaching the service.
- `LocalPostgresIntegrationTest` - V26 applied against real PostgreSQL, and four
  windows including two custom ones coexist as separate rows under the renamed
  unique constraint.
- Frontend: `filtersContext.test.ts` and `deadlineWindow.test.ts`, plus
  `boardRules.test.ts` retyped to the board's own window.
- **494 backend tests, 130 frontend tests, `npm run build` green.**

## A verification lesson worth keeping

`npx tsc --noEmit` in `frontend/` **checks nothing**. The root `tsconfig.json` is
`{"files": [], "references": [...]}`, so plain `tsc` has no inputs; only
`tsc -b` (what `npm run build` runs) checks the referenced projects.

Three real errors were hiding behind that no-op, including a production call site
in `AssignPopover.tsx` passing a bare string where the union was now required.
**Use `npm run build`, or `tsc --noEmit -p tsconfig.app.json`.** A green
typecheck that inspected no files is worse than no typecheck, because it gets
reported as evidence.

Depends on: 08, 17, 24, 26, 27.
