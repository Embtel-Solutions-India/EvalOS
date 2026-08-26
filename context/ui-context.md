# EvalOS — UI Context

> **A visual refresh is specced in `UI_MIGRATION_GUIDE.md`** (repo root), which adopts
> the Protend admin template's design language — tinted canvas, floating rounded
> sidebar, ambient shadows, indigo accent. **This file stays the source of truth for
> everything semantic**: RAG meanings and their thresholds, tabular figures, the focus
> ring. The guide defers to it by name and restates no threshold, and it flags the three
> places the template is *worse* than what is shipped today (no tabular figures, colour
> used decoratively, thin accessibility) as explicitly not-to-adopt. Where the guide and
> this file appear to disagree about a status colour, this file wins.

## Surfaces

EvalOS has three surfaces:

- **Internal app** (staff): dashboards, Kanban production board, data tables,
  case detail, expert database, payout ledger, and the GM's marketing funnel.
  Information-dense. Multi-brand:
  the GM gets a brand switcher / all-brands view; the Brand Manager and below are
  locked to a single brand.

  **The GHL pipeline screens (Units 24, 26, 27) are the ones the brand switcher does not
  reach**, and they say so in their own header rather than letting the control imply
  otherwise: they read one GHL location EvalOS cannot attribute to a brand, so there is
  nothing for the switcher to narrow. They are GM-only for that reason. Every other screen
  in this app is brand-scoped, so a reader would reasonably assume these are too — stating
  it on screen is part of the design, not a caption.

  All three are **one component** (`MarketingPipelinePage`) behind three nav entries, and
  the header always shows **GHL's own pipeline name** rather than the nav label — so the
  reader can tell which funnel they are looking at even though the layout is identical.
  Unit 27's sits under a **`Sales`** heading rather than Marketing: the other two are
  campaign funnels, and presenting a salesperson's pipeline beside them would imply three
  comparable channel results.
- **Client portal** (external): a single case's drafted letter for review —
  approve or request revisions. Passwordless via a GHL-delivered link. Minimal
  chrome, no navigation.
- **Expert portal** (external): one assigned case at a time — draft + evidence +
  goal, with accept / request-evidence / decline actions and a **download-then-upload
  sign step**. Passwordless via a CM-shared link. The sign panel's copy has to say
  plainly that a scanned wet signature is expected, so nobody hunts for an
  e-signature button that does not exist.

EvalOS is an internal operations tool: data-dense, fast, and legible over long
shifts. Light workspace — a calm neutral base with layered surfaces and a single
strong accent for interactive elements.

**RAG is load-bearing, not decorative.** Everything runs off red / amber / green
status (deadlines, SLA, capacity, overdue). The three status colors below are
reserved for status only and must never be used as brand or decorative color.

## Colors

Define these as CSS custom properties / Tailwind tokens. No hardcoded hex in
components.

**`frontend/src/styles/tokens.css` is the source of truth for the values; this table
mirrors it.** If the two disagree, the stylesheet is right and this is the bug.

| Role                | CSS Variable          | Value     |
| ------------------- | --------------------- | --------- |
| Page background     | `--bg-base`           | `#F4F5F7` |
| Surface / card      | `--bg-surface`        | `#FFFFFF` |
| Raised surface      | `--bg-raised`         | `#F1F3F5` |
| Primary text        | `--text-primary`      | `#111827` |
| Muted text          | `--text-muted`        | `#6B7280` |
| Primary accent      | `--accent-primary`    | `#2563EB` |
| Accent hover        | `--accent-hover`      | `#1D4ED8` |
| **Nav rail**        | `--sidebar-bg`        | `#16213C` |
| Nav rail text       | `--sidebar-text`      | `#E8ECF5` |
| Nav rail muted      | `--sidebar-muted`     | `#8B97B0` |
| Nav rail active     | `--sidebar-active-bg` | `#1F3A6D` |
| Nav rail divider    | `--sidebar-border`    | `#24304D` |

**The nav rail is its own surface and the only dark one.** Contrast was measured, not
assumed: rail text 13.5:1, rail muted 5.4:1, active-item white 11.1:1 — all above AA.
A future change to `--sidebar-bg` re-opens those three numbers.
| Border              | `--border-default`    | `#E3E6EB` |
| **Status — red**    | `--status-red`        | `#DC2626` |
| **Status — amber**  | `--status-amber`      | `#D97706` |
| **Status — green**  | `--status-green`      | `#16A34A` |
| Status red (bg)     | `--status-red-bg`     | `#FEECEC` |
| Status amber (bg)   | `--status-amber-bg`   | `#FEF3E2` |
| Status green (bg)   | `--status-green-bg`   | `#E8F6EC` |

### RAG semantics (single source of truth)

| Meaning                                                          | Token           |
| --------------------------------------------------------------- | --------------- |
| Overdue / breached SLA / capacity >90% / at-risk deadline <24h  | `--status-red`   |
| At risk / deadline <48h / capacity 70–90% / aging past threshold| `--status-amber` |
| On track / delivered on time / capacity <70%                    | `--status-green` |

**Two different clocks share those colours, and they must never share a label.** The
table above reads as one instrument and is not:

| Concept | Code | Question it answers | Where it is drawn |
| --- | --- | --- | --- |
| **Stage SLA** | `SlaCalculator` → `SlaStatus` | is this *stage* taking too long, against a per-stage budget from `stage_entered_at`? | the board's SLA rail |
| **Deadline risk** | `DeadlineRiskCalculator` → `DeadlineRisk` | will we miss the *promised date*, from `case.deadline`? | the at-risk KPI, the inbox, the deadline presets |

They disagree routinely — a case sits comfortably inside a 12-hour PM-review budget
with its deadline nine hours away — so wherever both appear they are labelled
"Stage SLA" and "Deadline" and neither is ever substituted for the other. `SlaCalculator`
does not read `case.deadline` at all.

Both return **null when no clock runs** (closed, or holding an exception state). That
band is `--rail-unknown`, not green: a paused case is neither healthy nor breaching,
and painting it green overstates the board's health. `DeadlineRisk.OVERDUE` is the
*red band* — past the date **or** inside 24 business hours — because the table above
puts those in one colour; a view needing genuinely-past-due reads the column.

## Typography

| Role                       | Font                    | Variable      |
| -------------------------- | ----------------------- | ------------- |
| UI text                    | Inter                   | `--font-sans` |
| Numbers / IDs / times      | Inter (tabular figures) | `--font-num`  |
| Monospace (case IDs, refs) | IBM Plex Mono           | `--font-mono` |

Use tabular figures for every column of currency, dates, deadlines, counts, and
case IDs so numbers align.

### Numbers: money vs counts

Every figure is rendered through `lib/money.ts` — `formatMoney` or `formatCount`.
Both group thousands; only `formatMoney` carries the `$`.

**The `$` is opt-in, per figure, and must stay that way.** A currency symbol is a
claim about what a number *is*, so only the caller can make it. `KpiCard` takes a
`money` boolean for this; without it a tile renders a bare grouped count.

The regression that set the rule: `KpiCard` printed `$` in front of every value
unconditionally, so all 28 tiles in the app carried one — "Deals in pipeline" read
`$93` on a deal *count*, and a rate read `$94%`. Currency assumed USD (one US SLA
calendar, no currency column); if a brand ever bills in another, that is a column,
not a second formatter. Asserted in `lib/money.test.ts`.

## Border Radius

| Context                                        | Class          | Value |
| ---------------------------------------------- | -------------- | ----- |
| Inline / small UI — badges, chips, **controls** | `rounded-md`   | 6px   |
| Cards / panels / Kanban                        | `rounded-lg`   | 8px   |
| Modals / drawers / overlays                    | `rounded-xl`   | 12px  |

**Nothing exceeds 12px, and controls take `md`.** `--radius-xl` was 30px under the
previous language, which made every 36px control a pill; a row of pills across a dense
operations header is noise. `xl` is now for overlays only — if a 36px button reaches for
it, the button is wrong, not the token.

## Density

**The reference screen is 1366 × 768.** That is the laptop the staff run, not the
1920 desktop the design language was extracted from, and every size below was set
against it. A control that fits at 1440 and pushes the board off the fold at 1366 is
a bug on the only screen that counts.

| Context                                   | Size                    |
| ----------------------------------------- | ----------------------- |
| Shell controls — pills, selects, search, icon buttons | **36px** tall (`h-9`) |
| Nav item                                  | **36px** tall           |
| Sidebar (`--sidebar-width`)               | **240px**, 208px ≤1200px |
| Header (`--header-height`)                | **72px**                |
| Shell gutter (`--shell-gutter`)           | **20px**                |
| Board column                              | **288px** wide (`w-72`) |
| Screen `h1`                               | `text-2xl`              |

Geometry that the shell shares lives in `tokens.css`, not in a component — a height
guessed twice is a height that drifts.

## Component Library

Tailwind CSS, with components written in the feature folder that owns them.

**`frontend/src/components/ui/` exists as of Unit 22, slice 1**, which is the moment
this file predicted: the first screens needing a focus-trapped modal, a drawer and a
tab set arrived together. It is now a **protected path** (`ai-workflow-rules.md`).

Vendored **shadcn-style wrappers over the unified `radix-ui` package** — no CLI, no
`components.json`, no generated-code pipeline. Three files, because the split follows
behaviour rather than component count:

| File | Holds | Why grouped |
| --- | --- | --- |
| `dialog.tsx` | `Dialog`, `Sheet` | a sheet *is* a dialog against an edge — same primitive, same focus trap. Splitting would duplicate the overlay and close button so two files could each own a `className` |
| `menu.tsx` | `DropdownMenu`, `Popover`, `InfoTip` | one raised-surface treatment; the behaviour that differs comes from Radix |
| `card.tsx` | `Card`, `KpiCard`, `ChartCard`, `CapacityBar` | the dashboard card system |
| `tabs.tsx` | `Tabs` | its own file; nothing shares its keyboard contract |

**Use a dialog for a decision, a sheet for inspecting without losing your place.**

**The card state union is the contract:** `loading · ok · warning · error · empty ·
unavailable`.
- `unavailable` names the blocking unit ("available once Unit 15 ships…") — the
  honest alternative to a zero for a metric whose data does not exist yet.
- `empty` carries operational copy, never "No data": *"All incoming cases are
  assigned."*
- **`empty` and zero are different states.** A figure whose rows sum to zero renders
  `0`; an empty month is an answer and a blank tile reads as broken.
- **Clickability is structural**: a card takes an optional `to`, and only then gets
  the link affordance. That is what stops every tile looking interactive.

The rule that mattered still holds: **do not hand-roll a second version of
something that already exists.**

**Motion needs no library.** Radix stamps `data-state` on every overlay, so entry and
exit are CSS keyframes keyed on it (`index.css`), under the existing
`prefers-reduced-motion` block. Motion, dnd-kit and TanStack Table are deferred with
written triggers in `context/specs/22-role-operations-ui.md`.

## Layout Patterns

- **App shell**: a **flush, full-height dark nav rail** on the left (role + brand-scoped
  items), and a top bar with a **brand switcher** (all-brands/filter for GM, single
  locked brand otherwise), a **global date filter**, search, and a **notification bell**
  (in-app notification center).

  **The date filter is backwards-looking, and only backwards-looking** (Unit 28). Four buttons —
  Today / This week / This month / This year, all **calendar-to-date** — plus a dropdown for
  **Last month**, **Last year** and a **date-to-date** range on two native `<input type="date">`.
  It was four rolling windows (last 7 / 30 / 365 days) until the filter gained completed periods,
  at which point two meanings of "month" would have shared one control; the labels were the half
  that was already lying, since "This month" answering for 30 days spanning two of them says
  something untrue on the 3rd.

  **The production board does NOT read this control, and that is the important part.** The board's
  date filter asks "what is due between now and X" — a forward cutoff — and lived on this same
  value for two units, a collision this file used to record as a caution. It is now resolved
  structurally: the board owns its own `Due within: 1 week / 1 month / 1 year`, sitting first in
  its filter row because it is the only one of the board's filters that refetches. The split was
  forced rather than tidied — `Last month` as a deadline horizon returns every open case, and a
  date-to-date interval is two edges where a cutoff needs one. **Do not re-point the board at the
  shell filter**: that sharing is what once left the production board effectively unfiltered for
  every role on first load.

  The rail is **not** a floating rounded card inset from the viewport — that was the
  previous language and is reversed. It is fixed to the edge with no gutter, so the
  content column offsets by `--sidebar-width` alone. A dark rail is what lets the content
  area stay quiet under a dozen panels at once; a white rail beside white cards needs a
  border to separate it and then competes with every card on screen.
- **Dashboard**: RAG tile grid at top (KPIs from the spec), tables and charts
  below. The largest tile is always the role's PRIMARY KPI. The GM's dashboard
  aggregates across brands with a brand filter.

  The **capacity thresholds below are the contract for any workload indicator** —
  the Case Manager workload widget the business asked for uses them as-is rather
  than inventing its own bands.

  **Charts: settled in Unit 22, slice 1 — Recharts.** The decision this paragraph used
  to leave open. The brief requires exact values on hover, which means tooltips, axis
  ticks and responsive resize; hand-rolled SVG would have meant writing and testing
  hit-testing for three shapes. Series colours come from the `--chart-1..5` ramp and
  **never** from the RAG tokens — a bar chart of four service types drawn red/amber/green
  reads as three products on fire.

- **The stage strip (Units 24 and 26)**: deals per stage as **horizontal Recharts bars**
  — stage names down the left, counts along the bottom, the count printed at the end of
  its own bar.

  **This replaced the `clip-path` chevron strip, and the reason is the honest-form rule.**
  The chevrons (and the line that briefly followed them) implied a *progression* through
  the stages, which these pipelines do not have: Won, Cold and Lost sit beside each other
  as outcomes. Bars carry only length, which is the one thing the data supports. Names go
  down the left because stage names are words — six along a bottom axis either truncate or
  tilt.

  Three rules the strip establishes, all of which generalise past these screens:

  - **A progressive ramp is `color-mix`, not a new token.** The bars shade from
    `--sidebar-bg` toward `--accent-primary` (40% → 90%) via
    `color-mix(in srgb, var(--accent-primary) N%, var(--sidebar-bg))`. `--chart-1..5`
    is a *categorical* ramp of five and this is an ordered ramp of however many stages
    the upstream has, so mixing the two existing tokens beats adding `--chart-6` — which
    that ramp's own note already refuses. No hex, and it scales to any stage count.
  - **The ramp stops short of the accent on purpose.** White on `--accent-primary` is
    4.54:1 — AA by a hair — and the 11px stage label is the text that would pay for it.
    Keeping ~10% of the rail's navy in the mix puts every bar clear of the line. Any
    new white-on-accent fill re-opens that number.
  - **A funnel is never shaded red→green.** RAG is load-bearing here (see the top of
    this file); a five-stage red-to-green funnel says five stages are in trouble.

  **What it deliberately does not draw: step-to-step conversion.** A conversion figure
  between two stages only means something when the second is downstream of the first,
  and an upstream pipeline's stages are not always a progression — all three GHL
  pipelines put Won, Cold and Lost beside each other as outcomes (the sales one adds
  `Refund` alongside them), so a percentage
  between Won and Cold is arithmetic over unrelated buckets. **Share of the total** is
  true of every stage whatever order the upstream puts them in. This is the same rule as
  the RAG table's "two clocks must never share a label": a number that looks like a rate
  and is not one is worse than no number.
- **Production board (Kanban)**: horizontal columns for the EvalOS-owned stages —
  Doc Collection · Expert Assignment · Draft / Report · Expert Signing · Final
  Delivery — with exception lanes (On Hold · Rematching · Refund Requested). The
  Draft / Report column shows draft sub-status chips (Draft in progress · PM
  review · Client review). Cards show client, service type, deadline (RAG), owner.

  The business's eight-column reading (splitting Draft into three and Signing into
  Signing + QC) is a **derived grouping of these five plus the chips**, not new
  columns to add to the enum — see `context/specs/08-production-board.md`.

  **The board scrolls on both axes, and each axis has one owner.** The column strip
  scrolls horizontally; each column's card list scrolls vertically inside a height
  bounded by `--board-column-max`. Nothing else on the screen scrolls. That is what
  keeps the SLA rail and the column headers fixed while cases move under them — the
  rail is the board's one instrument, and an instrument that scrolls off the top is
  not one. The pool lane is capped at two rows of pills for the same reason, and
  "Off the pipeline" is closed by default.
  **The pool lane is the Brand Manager's only, as of Unit 23.** The GM watches the board;
  the pool is a queue somebody works, and it now lives in the PM inbox where taking a case
  and staffing it are one flow.
- **Delivery queue** (`/delivery`): dense rows, cases in Final Delivery, oldest
  first, one-click **Deliver** per row. This is the data-table pattern's other
  landing place besides the dashboard — a Coordinator working a batch rather than
  hunting cards. The entry was previously deleted as an empty nav item; it is back
  with a screen behind it.
- **Client document upload** (portal): the client's own checklist as a list of rows,
  one upload control per required item, showing required / uploaded / flagged with
  the Coordinator's reason. Portal chrome rules apply — minimal, no nav. Accepted
  types and the size cap are stated on the control, not discovered by a rejection,
  and a rejection says which rule it broke.
- **Data tables**: dense rows, sortable, a dedicated RAG status column, row click
  opens the case. Overdue rows tinted with the `*-bg` status token. No screen uses
  this pattern yet — Unit 10 deleted the `/cases` list as a duplicate of the
  production board, and the dashboards below the RAG tiles are where it lands
  first. The checklist board is deliberately a list of rows rather than a table:
  one stage, so there is nothing to sort by that the server's longest-wait-first
  order does not already answer.
- **Case detail**: two-column — left is documents (Drive link) + draft + expert,
  right is **Notes & timeline**. Stage actions sit in a sticky header.
  - **Notes and history are one panel, not two tabs** (Unit 23). A note is almost always
    *about* the transition beside it, so splitting them puts the sentence on one screen
    and the event it explains on another and leaves the reader merging two orderings.
    A note is stored as an audit row, which is what lets them interleave at all.
  - A note is drawn with a `--accent-primary` left rule and its text as the body; a
    transition keeps its stage line and quotes any reason. That rule is the one visual
    difference between *the system recorded this* and *a person said this*.
  - The composer sits at the foot of the panel and is shown to **every** role, with no
    client-side permission check: the server's gate is the case scope, so anyone who
    could load the page may write. It says plainly that the note is readable by everyone
    on the case and cannot be edited — because it cannot, ever, by anyone.
- **Client portal** (built in Unit 14): single centered column, one case, the
  drafted letter with big Approve / Request revisions actions and a visible
  "changes requested" note field.
  - **No shell, no nav, no brand switcher, and no auth provider** — `App` answers
    `/portal/*` before any staff-session code runs, and mounts `AuthProvider`
    around the staff surface only. A client is not a staff user with fewer links.
    Same tokens, same typography; the difference is what is absent.
  - **Both actions confirm inline before firing**, with wording that says what
    happens next — approving commits the letter to an expert's signature, and a
    revision request puts a person to work. After either, the actions are gone and
    the page states the outcome and what follows.
  - **Failure states are written for the reader, and this is product copy, not
    plumbing.** Expired, revoked and unknown links all say the same thing and point
    at whoever sent the link — never a stack trace, and **never a login form**, for
    somebody who has no account.
- **Expert portal**: single centered column, one case at a time, big Accept /
  Request-evidence / Decline / Sign actions.

## Icons

Stroke-based only, `h-4 w-4` inline and `h-5 w-5` in buttons and nav.

**Lucide is a dependency as of Unit 22, slice 1** (`lucide-react`). The condition this
paragraph set — "once the glyph count makes the inline paths the bigger cost" — was met
when the dashboards, the card states and the overlay primitives landed together. The
sizes above were already Lucide's own convention, so adoption was an import rather than
a re-layout, exactly as predicted.

Inline SVG paths still in place (e.g. `LeftNav.tsx`) are correct where they stand and
are not a migration backlog; new work imports from Lucide.
