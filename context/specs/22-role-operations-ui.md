# Unit 22 — Role operations UI (supersedes Unit 17)

> **⚠ AMENDED by Unit 31 — Production lifecycle v2 (SPECCED 2026-09-02, not built).**
> The pipeline becomes **twelve explicit stages, each with one owner, one primary action and
> one next owner**, drawn as **eight board columns**. Three facts this codebase currently carries as *sub-statuses on a stage*
> — PM approval, client approval, and the QC/delivered split — become **stages**, and the
> draft becomes a **versioned file** rather than a link. Two transitions are added
> (`qc-fail`, `send-to-expert`) and several gates move, notably the Case Manager taking
> ownership of expert signing and reassignment.
> **Read `context/specs/31-production-lifecycle-v2.md` before changing anything below.**

**Phase:** 2 — Connect the seams
**Depends on:** 04 (lifecycle + timestamps), 08 (the board), 09 (case detail),
11 (roster), 12 (match scoring)
**Unlocks:** 20 (anomaly detection reads these metrics)
**Supersedes:** **Unit 17 — Dashboards.** Unit 17 is not deleted and not stale:
its metric definitions, date-column table and scope rules remain the authority and
are cited here rather than restated. What changes is the _cut_. Unit 17 built six
role dashboards in one unit, layer by layer; this unit builds them **role by role**,
each slice carrying its own backend metrics, its own screens and its own
verification. Read `17-dashboards.md` for what a figure means; read this file for
what gets built when.
**Gating open questions:** none blocking slice 1. Slice 4 inherits Unit 17's open
question 3 (review capture cannot b
e measured inside EvalOS); slices 4 and 5 carry
the two `unavailable` tiles below.

## Goal

Turn EvalOS's staff surface into a role-scoped operations console: each role opens
the app and can answer, without hunting, _what needs attention now, what is at
risk, what changed, what can I do immediately, what is blocked._

**Verifiable result:** each of the six roles opens the app and gets figures that
are correct for their scope, agree with the screens beside them, and lead somewhere
— every KPI that names a population opens that population filtered.

## Provenance, and what was refused

This unit originates in a business-supplied UI brief ("International Evaluations
CRM", 2026-08-25). The brief was accepted as a **UI and interaction brief** and
rejected as a source of domain rules. It was written without sight of the codebase
and contradicts shipped decisions in five places. Recording the refusals here so
they are not re-litigated per screen:

| The brief asked for                                                                                  | Refused because                                                                                                                                                                         | Authority                                 |
| ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| An **Intern** role, with an Intern → CM → PM draft chain                                             | No such role exists in `Role`, `team_member`, or any gate. Adding one is a domain change, not a UI change                                                                               | `domain/Role.java`                        |
| A single-brand product                                                                               | **Multi-brand is invariant 1.** Every scoped query filters `brand_id`; the GM has a brand switcher                                                                                      | `architecture.md`, `CLAUDE.md`            |
| **Eight** Kanban columns                                                                             | Already ruled a _derived grouping_ of the five stages plus the draft sub-status chips. Not new enum values                                                                              | `ui-context.md`, `08-production-board.md` |
| Retention follow-ups (30/90/180/365), Google **review request** campaigns, a client message composer | **Invariant 2:** GHL owns marketing, review campaigns and client messages. **Invariant 14:** no mail server. EvalOS records that a review request _fired_; it does not run the campaign | `architecture.md`, `17-dashboards.md` §4  |
| An expert **recruitment pipeline** Kanban and outreach call/email tracking                           | Not in the build plan, no schema behind it, and a whole supply-side subsystem rather than a UI pass                                                                                     | `00-build-plan.md`                        |

Two further brief requests were refused on engineering grounds and are recorded
under **Deferred dependencies** and **No drag-and-drop** below.

## Decisions taken

1. **Deliverable** — visual and interaction pass over the shipped screens, plus
   the role dashboards and the backend metrics behind them.
2. **Dependencies** — three installs: `radix-ui`, `lucide-react`, `recharts`.
   TanStack Table, dnd-kit and Motion are **deferred with triggers** (below).
3. **Sequencing** — **vertical slices, one role at a time.** The shared foundation
   is built inside slice 1 and then frozen; later slices extend the decision tables
   and may not redesign a shared component. Without that rule this cut produces
   four shells.
4. **CM capacity denominator** — one configured number per brand
   (`evalos.workload.cases-per-cm`), not a `team_member` column. Per-person capacity
   is deferred until part-time or junior loads actually differ.
5. **Mark urgent** — **not built.** The deadline already expresses urgency and
   drives `DeadlineRisk`; a separate flag is a second source of truth for one fact,
   and the two would disagree (a case flagged urgent with a deadline three weeks
   out, a red case nobody flagged). No `urgent` column, no transition, no badge.
6. **The Coordinator's "documents aging" threshold is `SlaCalculator`'s existing
   24-business-hour `DOC_COLLECTION` budget**, not the brief's 48 wall-clock hours.
   One home per fact: a second threshold on the same stage means the dashboard tile
   and the board's SLA rail can disagree about one case. Note the consequence
   honestly — 24 business hours is roughly three working days, so this tile is
   _looser_ than the brief asked for, and that is the trade accepted to keep one
   clock.
7. **"Flag issue to PM" is built**, shaped like `ChecklistService.chase()`: a
   CM-gated endpoint taking a reason, writing an audit row and publishing a
   notification to the brand's Project Managers through Unit 06's existing `ROUTES`.
   **No stage change, no new column, no migration.** This is the one place the brief
   is allowed to add behaviour rather than presentation, and the justification is
   structural rather than deference to the brief: the Case Manager's only gated writes
   are `draft/submit` (`CaseController:317`) and minting a client portal link
   (`PortalLinkController:79`), **neither of which can raise a blocked case to
   anyone.** It also resolves the `STAGE_ACCESS` inconsistency recorded in slice 3.
8. **The ENM client-identity exposure is fixed first, as a standalone change**, ahead
   of all UI work — see the Prerequisite section below. A security fix does not ride
   inside a large UI diff where it cannot be reviewed on its own merits, and it is
   correct whether or not the rest of this unit ever ships.
9. **The `performance_flags` writer is built**, ENM-gated, with an audit row. Same
   structural justification as decision 7: the ENM's entire job is expert quality,
   the column and its enum exist precisely to record it, and nothing in the codebase
   can set it. Closes **G8**. "Mark inactive" needs nothing new — `Availability`
   already carries `INACTIVE`.

10. **"Unpaid pipeline" comes off the money tiles.** Unit 05b creates every case
    paid, so the figure decays to a permanent zero, and a money tile that always
    reads zero is one people stop reading — which is precisely when it matters that
    it is not zero. Its only alternative, quotes-not-yet-won, is GHL's under
    invariant 2. See slice 5.

Two smaller calls taken by precedent rather than by asking, recorded so they are
visible:

- **G7, the recruitment target** ("new experts onboarded vs target") takes
  **decision 4's shape**: one configured number per brand. The count over
  `expert.date_onboarded` was always buildable; only the target lacked a home.
- **G10, the quality-score trend**, takes `17-dashboards.md`'s standing instruction
  — _do not fake it._ `quality_score` is human-entered and unversioned, so there is
  no history to trend. The tile shows the **current** distribution, labelled current,
  with no trend arrow. Adding a history table is a unit, not a tile.

## Prerequisite — the ENM client-identity fix (standalone, before slice 1)

**Found while specifying slice 4, and it is a shipped defect rather than a design
question.** `architecture.md` states the Expert Network Manager must never see case
content or client identity. The code does not hold that invariant.

`CaseDetailService` resolves `clientName` from `contactId` with no role check, and
`CaseController.CaseDetail.of()` passes three fields through unconditionally:

| Field        | What it is                         |
| ------------ | ---------------------------------- |
| `clientName` | the client's identity              |
| `driveLink`  | "the client's own document folder" |
| `draftLink`  | the drafted letter                 |

`pmStrategyNotes` is the **only** gated field, through `SEES_STRATEGY_NOTES`. The
javadoc immediately above the constructor states the principle it fails to apply:
_"a field the caller may not see is absent from the payload, so there is nothing to
reveal with dev tools (spec deliverable 5, invariant 3)."_

`navigation.ts` puts `/cases/:id` on `ALL_ROLES`, and the ENM legitimately holds case
ids — they own three case endpoints (`expert/signed`, `expert/declined`,
`reassign-expert`) and Unit 06 routes case events to them. So the path is reachable
in normal use, not only by hand-crafted request.

### The root cause is one level above the projection

`Role.EXPERT_NETWORK_MANAGER` carries `Tier.SUPPLY`, whose javadoc reads _"Own
brand's expert/roster supply side — **not case content**"_. Its implementation in
`ScopePredicate`:

```java
// BRAND and SUPPLY read their whole brand; ALL returned above.
default -> { }
```

**`SUPPLY` appears nowhere else in the codebase.** The tier that exists to exclude
case content adds no predicate and reads the whole brand, exactly as `BRAND` does.

### Which makes the board the wider hole, not case detail

`CaseBoardController` has **no `@PreAuthorize`**, documented as deliberate: _"Every
staff role has a board and none of them can widen it — the scope is applied in the
service."_ That reasoning depends on the scope actually narrowing, and for `SUPPLY`
it does not.

Worse, `BoardCard.of` gates one field and not the other, on adjacent lines:

```java
subject.getAssignedCoordinator(), ...
CaseController.SEES_DEAL_VALUE.contains(ctx.role()) ? subject.getDealValue() : null);
//                    ^ dealValue IS projected by role
row.clientName(),  // ^ clientName is NOT
```

So an authenticated ENM calling `GET /api/cases/board` receives **every client name
in their brand in a single request** — broader than case detail, which needs a case
id and returns one. The mechanism for fixing it is already in the same factory
method.

### Fix surface, enumerated rather than assumed

Every controller was checked for client-identity fields against its gating:

| Controller            | Carries client identity                | Gate                               | Verdict        |
| --------------------- | -------------------------------------- | ---------------------------------- | -------------- |
| `CaseBoardController` | `clientName`                           | **none**                           | **must fix**   |
| `CaseController`      | `clientName`, `driveLink`, `draftLink` | 23 gates, none on these fields     | **must fix**   |
| `ChecklistController` | `clientName`                           | `COORDINATION` — GM/BM/Coordinator | already safe   |
| all others            | none                                   | —                                  | not applicable |

**Bounding the severity honestly:** it requires an authenticated ENM account, it stays
within that account's own brand, and it is an internal role-boundary breach — not a
public vulnerability and not a cross-tenant leak. It is nonetheless a stated invariant
the code does not keep.

**An existing passing test corroborates it.** `CaseControllerTest:272` loops the ENM
through `GET /api/cases/{id}` and asserts `status().isOk()` plus
`$.data.summary.caseCode`, with only `$.data.pmStrategyNotes` asserted absent. So the
suite already documents that the ENM successfully reads case detail; it simply never
asked what else came back with it. The fix therefore extends that test rather than
adding an unrelated one.

**The fix:** a `seesCaseContent(Role)` predicate beside `SEES_STRATEGY_NOTES`, applied
in **both** projections, plus a `maySeeCaseContent` flag following the
`maySeeStrategyNotes` precedent, plus the missing tests. The mechanism was already
right and one field group was never put behind it.

**A predicate, not a `Set<Role>`, which breaks the local pattern deliberately.**
`SEES_DEAL_VALUE` and `SEES_STRATEGY_NOTES` are role sets because who sees money and
who sees strategy are product decisions with no other home. Who sees case content
_does_ have a home — `Role.Tier.SUPPLY` says exactly this, and `Role`'s javadoc calls
the tier "the single source of truth for scoping". A role list here would be a second
copy of that fact, and it is the copy that goes stale when a seventh role is added.
Deriving from the tier also gives `SUPPLY` its first actual use.

**Field projection, not tier narrowing, and the reason matters.** It is tempting to
fix `Tier.SUPPLY` to match its javadoc and exclude case rows outright. That would
break the ENM's three legitimate case transitions, which must load the case to act on
it. The ENM genuinely needs the case _row_; what they must not receive is the client
_identity_ on it. So `SUPPLY` keeps its brand-wide read and its javadoc is corrected
to say what it actually does, while the identity fields move behind the projection.
Recorded here because the tempting fix is the wrong one and the next reader will have
the same idea.

**`maySeeCaseContent` is not optional politeness.** `clientName` is already
legitimately null when a case has no linked contact (`CaseDetailService` resolves it
through `Optional.ofNullable(getContactId())`), and `StageActions.tsx:73` renders
`clientName ?? 'Unnamed contact'`. Without an explicit flag, a **withheld** name
renders as **"Unnamed contact"** — the UI would state something false about the
client rather than admit the field was withheld. This is precisely why
`maySeeStrategyNotes` exists; the same reasoning applies unchanged.

### A harmless second finding, recorded so it is not rediscovered

`STAGE_ACCESS[EXPERT_NETWORK_MANAGER]` declares five stage-access cells for a board
the ENM cannot open: `navFor` excludes them from `/board`, and `boardPathFor`'s own
comment says _"a role that can reach neither (the Expert Network Manager today)"_.
That row is unreachable today.

**It stays.** `STAGE_ACCESS` is typed `Record<Role, Record<Stage, StageAccess>>`, so
TypeScript requires all six role keys — the row cannot be deleted without weakening
the type, and the type is what guarantees a new role cannot be added without deciding
its board access. A comment is added saying the row is currently unreachable and why.

## The foundation (built in slice 1, then frozen)

### `frontend/src/components/ui/`

Creating this directory **activates the protected-path rule already written in
`ai-workflow-rules.md`**, which has listed `frontend/src/components/ui/*` as
protected since before the directory existed.

shadcn-_style_, not shadcn-_installed_: thin vendored wrappers over the unified
`radix-ui` package. No CLI, no `components.json`, no generated-code pipeline.

Six primitives, each earned by a named screen — this is the "install a dependency
only in the unit where it first unlocks real behavior" rule applied per component:

| Primitive       | Earned by                                                                                                                           |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `dialog`        | Consequential actions: change deadline, reassign CM, QC approve, deliver. Replaces `QuickActionDialog`'s hand-rolled focus handling |
| `sheet`         | Inspecting a record without losing your place in a queue: case preview, expert preview, strategy notes, activity history            |
| `tabs`          | Case detail                                                                                                                         |
| `popover`       | The assignment picker, showing each CM's load and capacity inline                                                                   |
| `tooltip`       | Exact chart values; icon meaning                                                                                                    |
| `dropdown-menu` | Row and card overflow actions                                                                                                       |

### The card system

The brief's seven semantic kinds — KPI, alert, queue, summary, chart, progress,
list — as one module over one state union:

```
loading · empty · ok · warning · error · unavailable
```

- **`unavailable`** takes the name of the blocking unit and renders it:
  _"Expert turnaround — available once Unit 15 ships the signing events."_ This is
  how a metric that cannot be computed ships honestly instead of as a zero. It is
  `17-dashboards.md`'s own rule — a tile that names a metric it cannot compute is
  the header-contradicting-the-instrument failure — given a component.
- **`empty`** takes operational copy, never "No data": _"All incoming cases are
  assigned."_ An empty queue is a statement about operational health.
- **A figure with rows summing to zero renders `0`, not empty.** Per
  `17-dashboards.md`: an empty month is an answer, and a tile that goes blank reads
  as broken. `empty` and _zero_ are different states and must not collapse.

**Clickability is structural.** A card takes an optional `to`; with it, it renders
as a link and gets the hover affordance — without it, none. This is what stops
every tile looking clickable, and it makes the brief's rule (clicking "12 at risk"
opens that population filtered) a prop rather than a convention somebody remembers.

### Tokens

`--status-red/amber/green` are **untouched**. They stay reserved for RAG, and RAG
here is load-bearing.

The one addition is a **categorical chart ramp** derived from `--accent-primary`.
Charts need series colours that are not status colours: a bar chart comparing four
service types rendered red/amber/green reads as three products on fire. New token
group, **`--chart-1` through `--chart-5`** — five because `ServiceType` has exactly
five values and service-type comparison is the widest categorical chart this unit
draws. Documented in `ui-context.md` as decorative-only and explicitly not a status.
A chart needing a sixth series is a chart with too many series.

### Icons

`LeftNav`'s inline SVGs are replaced by Lucide at the sizes `ui-context.md` already
specifies (`h-4 w-4` inline, `h-5 w-5` in buttons and nav). Those are Lucide's own
conventions, so this is an import change and not a re-layout — exactly the swap
`ui-context.md` predicted. Its "Lucide is **not** a dependency" paragraph is
rewritten, not annotated.

### Motion without Motion

Radix exposes `data-state` on every primitive. CSS transitions key off those
attributes and one global `prefers-reduced-motion: reduce` block disables them.
This covers the brief's entire animation list — drawer and modal open/close, filter
change, expandable sections, status change, notification entry — at zero dependency
cost.

### Deferred dependencies, with their triggers

| Deferred           | Add when                                                                                                                                         |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| **TanStack Table** | A table needs virtualization or column-visibility state that `useState` over a sorted array cannot carry. Dashboard tables are single-digit rows |
| **dnd-kit**        | See below                                                                                                                                        |
| **Motion**         | A layout-animated list reorder is actually needed. CSS covers everything currently specified                                                     |

### No drag-and-drop on the board

The brief is emphatic about dnd-kit. **Eleven of the twenty-one quick actions in
`QUICK_ACTIONS` require a field the drop gesture cannot supply** — `reason` on PM
return, client revisions, hold, expert decline, refund request and refund deny;
`cmId` _and_ `expertId` on `assign-cm`; `pmId`, `coordinatorId`, `expertId` on the
staffing actions; `draftLink` on submit. Critically, `assign-cm` is **the only way
out of Expert Assignment**, so the single most-dragged column boundary on the board
is one a drop cannot cross. The server refuses without the field and answers 409
with a reason. A board where the main move fails is worse than one that opens a
dialog.

Revisit if the transition set ever becomes majority field-free.

### What deliberately does not change

`navigation.ts` stays **one table serving both the nav and the route allow-list.**
The brief's "do not rely on hiding nav items" is already how this works. Splitting
it to make the sidebar prettier reintroduces the deep-linkable-but-unlisted bug it
exists to prevent.

## Slice map

| #   | Slice                      | Carries                                                                                                                                     |
| --- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **Project Manager**        | The foundation, plus PM dashboard, board re-skin, case detail re-skin, `/inbox`, `/drafts`                                                  |
| 2   | **Project Coordinator**    | Coordinator dashboard, checklist board re-skin with chase ages, awaiting-client-review preset, contact ledger, delivery queue (closes G3)   |
| 3   | **Case Manager**           | CM dashboard, `/my-cases` re-skin, draft staging workspace, strategy-notes reading, flag-to-PM (decision 7)                                 |
| 4   | **Expert Network Manager** | ENM dashboard, roster + availability board + profile re-skin, coverage-gap alert (G6), onboarding target (G7), performance-flag writer (G8) |
| 5   | **GM / Brand Manager**     | Cross-brand aggregation of slices 1–4, brand-switcher behaviour, money tiles, `/brands` re-skin                                             |

GM and Brand Manager land last because their dashboards are the other four
aggregated cross-brand. Building them first would mean guessing at metrics the
earlier slices define.

---

# Slice 1 — Project Manager

## The correction that shapes the slice

**`SlaCalculator` measures stage budgets, not deadlines.** It compares
`stage_entered_at` against a per-stage budget on the `BusinessCalendar`. It never
reads `case.deadline`.

The board's existing RAG therefore answers _"is this stage taking too long?"_ The
brief's "cases at risk right now" asks _"will we miss the promised date?"_ **These
are different questions and they will routinely disagree** — a case can sit
comfortably inside a 12-hour PM-review budget with its deadline nine hours away.

So this slice adds a second concept beside `SlaStatus`, and does not replace it:

```
SlaStatus     (exists) — stage budget vs stage_entered_at → the board's SLA rail
DeadlineRisk  (new)    — case.deadline vs now             → at-risk KPI, deadline view
```

`DeadlineRisk` **invents no thresholds.** `ui-context.md` already fixes them: red
under 24h, amber under 48h, on the same `BusinessCalendar`. It returns null under
the identical rule `SlaCalculator` uses — a case in an exception state runs no
clock and takes the muted band, never green and never red. This is the `slaMix`
`unknown` lesson: do not colour a paused case as healthy, and do not colour it as
breaching either.

The two are **labelled distinctly on screen** — "Stage SLA" and "Deadline" — because
one instrument silently answering a different question than its neighbour is the
failure this codebase has logged three times (`allInsideSla`, the draft chip, the
"N ready for the PM" count).

`ui-context.md`'s RAG table currently reads as one table covering both and is split.

## Backend

`PmMetricsService`, computed live with aggregates pushed into SQL, per
`17-dashboards.md`'s storage decision. Every figure derives from the caller's
**already-scoped** case read — a dashboard cannot see further than the board does.

| Metric                                            | Definition                                                                                                                                                                                                               | Date column                                                   |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------- |
| **Delivered on time %** _(primary, largest tile)_ | `COUNT(delivery_date <= deadline) / COUNT(delivery_date IS NOT NULL)`. **The denominator renders beside the percentage** so "100%" over two cases cannot read as "100%". Previous period is the same window shifted back | `delivery_date`                                               |
| **Cases at risk right now** _(primary)_           | `DeadlineRisk` red + amber, excluding `FINAL_DELIVERY` and `CLOSED`. **The global date filter is inert here and the tile says so** — "right now" is bounded by now+48h, not by the header's period                       | none — live                                                   |
| **Unassigned cases** _(primary, target zero)_     | `pool_status = IN_POOL`. Above zero the card takes `warning` state and sorts ahead of its neighbours                                                                                                                     | none — live                                                   |
| **Avg completion by service type** _(secondary)_  | `17-dashboards.md`'s cycle-time family — paired `STAGE_CHANGED` rows, median business hours, grouped by `service_type`, benchmark reference line per product. Pairing rules are that spec's, not restated                | `STAGE_CHANGED.created_at`, brand-scoped **through the case** |
| **Draft revision rate per CM** _(secondary)_      | Share of that CM's cases with `draft_version_count > 1`                                                                                                                                                                  | `created_at`                                                  |
| **CM workload**                                   | Grouped count by `assigned_cm` over the configured capacity. RAG bands are `ui-context.md`'s fixed 70/90                                                                                                                 | none — live                                                   |
| **Expert response time** _(tracking, flag >36h)_  | Offer → signature from `expert_case_offer`                                                                                                                                                                               | ⛔ **`unavailable`**, naming Unit 15                          |

**Revision rate is a deliberate deviation from `17-dashboards.md`,** which proposes
`draft_version_count` _plus_ `DRAFT_RETURNED` audit rows. That action does not exist
in `AuditAction`, and adding it would record a fact `draft_version_count` already
carries. One home per fact: the counter is the home. Unit 17's line is corrected at
source rather than annotated.

### New transitions

Both are G12 items with a real column behind them.

- **Change deadline.** `deadline` exists; nothing writes it after intake. PM-gated
  transition, confirmation dialog naming old and new date, `UPDATED` audit row.
  `DeadlineRisk` is computed on read, so reclassification is immediate with no cache
  to invalidate.
- **Reassign CM mid-draft.** `assign-cm` is declared on `EXPERT_ASSIGNMENT` only;
  widened to `DRAFT_GENERATION`. The workload-redistribution workflow is meaningless
  without it. Source and destination load are shown before confirming, and the
  previous assignment is preserved — assignment history is append-only.

## Screens

**`/dashboard`** — real figures. Largest tile is on-time %, per `ui-context.md`'s
"the largest tile is always the role's PRIMARY KPI". CM workload is a section here,
not its own route.

**`/board`** — re-skin only, no API change. Cards carry client, service, deadline
(now `DeadlineRisk`-coloured), owner, expert, blocker, and doc/draft status.
Overflow actions via `dropdown-menu`, revealed on hover **and** reachable from the
keyboard — hover is never the only path to an action. The SLA rail and both scroll
axes are unchanged; the rail is the board's one instrument and it does not move.

**`/cases/:id`** — Radix tabs: Overview · Documents · Draft · Expert · Activity.
The sticky stage-action header stays. Strategy notes move into a `sheet` so they can
be read from any tab while working the draft.

**`/inbox`** — new. The queue you _work_; the board's pool lane stays the
at-a-glance count and links here. **One place to see, one place to act** — the guard
against the `/cases`-beside-`/board` duplication this repo has deleted twice.
Columns: client, client type, service, deadline, doc status, stage. Row assign via
`popover` showing each CM's load and capacity; bulk select with a confirmation
dialog.

**Three brief-requested columns are dropped:** payment confirmation (always true
since Unit 05b creates cases paid — a column of yeses); sales notes (**G11** — no
field carries GHL's notes; the column returns if intake ever starts carrying one);
and **urgency**, which per decision 5 has no column behind it and would duplicate
the deadline column beside it — the deadline column carries the `DeadlineRisk` RAG
and _is_ the urgency signal.

**`/drafts`** — new. Cases in `DRAFT_GENERATION` with `pm_approval_status = PENDING`,
oldest first. **Age reads from `stage_entered_at`** — no new column, and it is the
identical clock the 12-hour `PM_REVIEW` budget runs on, so the queue and the SLA rail
cannot drift apart. Approve moves the case on; return requires a reason.

**Deadline view** — a filter preset on `/inbox` (overdue · today · this week ·
future), not a sixth route.

Both new routes are added to `NAV_ITEMS` with their role lists set from the backend
gates they call, never wider.

## Out of slice 1

Coordinator, CM and ENM dashboards. The expert assignment board (slice 4) — and note
the "AI expert recommendation" the brief asks for **is already built** as Unit 12's
`ExpertMatchService` shortlist. It is re-skinned, not rewritten, and it already
never auto-assigns. The delivery queue is the Coordinator's (slice 2).

## Verification

1. `./mvnw verify` green, including new tests for `DeadlineRiskCalculator` (the
   24/48h bands, the exception-state null, the business-calendar arithmetic) and one
   per metric query.
2. `npm run build` clean — no TypeScript errors, no console errors.
3. Frontend tests green, including `navigation.test.ts` updated for the two new
   routes.
4. **Browser pass at 1366×768**, the reference width. A control that fits at 1440
   and pushes the board off the fold at 1366 is a bug on the only screen that counts.
5. **Six-role scope check:** for each role, no unauthorized route resolves and no
   unauthorized figure appears in a response body. `deal_value` projects through
   `CaseController.SEES_DEAL_VALUE`, read at build time — never re-derived from a doc.
6. Keyboard pass: every action reachable without a pointer; dialogs trap focus.

---

# Slice 2 — Project Coordinator

## The correction that shapes the slice

**`ChecklistService.chase()` sends nothing.** Its own javadoc states it: the chase
writes an `AuditAction.CHASED` row and publishes an event inside the transaction,
and **GHL delivers the message**. EvalOS composes no client-facing text and owns no
message store.

That removes the brief's Coordinator centrepiece. It asks for a **threaded client
communication log with a composer**, and for reminder messages the Coordinator can
"review or modify before sending". EvalOS can do neither — invariant 14 (no mail
server), invariant 2 (GHL owns client messages), and the channel decision is still
open in `process-automation.md`. There is no `message` table and no thread entity to
render, and building one is a subsystem, not a screen.

**What is built instead is a contact ledger, and it is not a consolation prize.**
Per case, every outbound touch EvalOS actually _witnessed_, from data already on
disk: `CHASED` rows with timestamps, `PORTAL_LINK_ISSUED` rows,
`client_portal_read_at`, `portal_access.last_seen_at`, stage changes. That answers
the questions a Coordinator asks — when did we last chase, did they open it, how
long have we waited — which a message thread answers _less_ precisely, because a
thread records what was sent and not whether it landed.

The screen says which it is: a record of contact **events**, not a mailbox.

## What Unit 14 already made easy

The brief's "cases awaiting client review — sent time, elapsed, **opened / not
opened**, response" reads like new instrumentation. It is not.
`client_portal_read_at` is stamped **once, on first read** (`PortalCaseService`), and
`portal_access.last_seen_at` on **every** use. Two distinct facts, both live, both
already written. Opened-versus-not is a column.

## A fourth counter column with no writer

**`google_review_requested` has no writer anywhere in the codebase.** Handoff C
(Unit 18) would fire it; Unit 18 is unbuilt. So the brief's "review requests sent vs
received" splits in two:

- **Received** — unknowable inside EvalOS, permanently. The review lands on Google
  and the campaign runs in GHL (`17-dashboards.md` §4).
- **Sent** — knowable in principle, zero in practice, because nothing sets the flag.

The tile renders `unavailable` naming Unit 18. This joins
`expert.current_active_count`, `avg_response_hours` and `total_cases_completed` on
the standing warning list in `progress-tracker.md`, which this slice extends rather
than leaving the pattern to be rediscovered a fifth time.

## Metrics

| Metric                                            | Definition                                                                                                                                                                                                                                    | Status                                     |
| ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| **Docs incomplete and aging** _(primary)_         | Cases in `DOC_COLLECTION` where `SlaCalculator` reports `AT_RISK` or `OVERDUE`, per decision 6. `ChecklistService.board()` already returns `total`, `complete` and `lastChasedAt` per row                                                     | Buildable — reuses the shipped board query |
| **Delivered today / this week** _(primary)_       | `COUNT` over `delivery_date`                                                                                                                                                                                                                  | Buildable                                  |
| **Avg doc-collection time** _(secondary)_         | Cycle-time family, the `DOC_COLLECTION` interval only                                                                                                                                                                                         | Buildable                                  |
| **Client review turnaround** _(secondary)_        | Draft sent → client responded, **split by `client_portal_read_at`** into time-to-open and time-to-decide. The second number is the one worth coaching on; a single blended figure hides whether the client was slow or the link never arrived | Buildable                                  |
| **Review requests sent vs received** _(tracking)_ | —                                                                                                                                                                                                                                             | ⛔ **`unavailable`**, naming Unit 18       |

## Screens

**`/dashboard`** — Coordinator tiles. The largest is docs aging: it is the blocker
this role exists to clear.

**`/delivery`** — new, closes **G3**. `deliver` and `close` already exist as
Coordinator-gated endpoints (`CaseController`), so this is genuinely only a screen
and adds no transition. Dense rows, `FINAL_DELIVERY`, oldest first, one-click deliver
with the full state chain — confirm → sending → success → failure → retry. Delivery
is consequential and irreversible, so it confirms in a `dialog` naming the client and
the letter. `navigation.test.ts` flips with this entry, as the tracker predicted.

**`/checklists`** — re-skin. Chase age surfaced per row from `lastChasedAt`. The 24h
and 48h chase groupings are a **filter preset on this board**, not a new route — the
same rule that put the deadline view on `/inbox` rather than giving it a URL.

**Awaiting client review** — a filter preset over the same table shape, showing sent,
elapsed, opened, and response. Flagged for attention past 48h.

**Contact ledger** — a `sheet` on the case, not a route.

## Refused in slice 2

Threaded client messaging and the composer. Editable reminder text — EvalOS does not
compose the message, so there is nothing to edit. Retention follow-ups
(30/90/180/365) — invariant 2. "Trigger Google review request" as a Coordinator
button — that is Unit 18's dispatcher, not a control on this screen.

## Verification

Adds to slice 1's list: the Coordinator scope check (no case they do not own, no
`deal_value` — the Coordinator is **not** on `SEES_DEAL_VALUE`), and a delivery
failure path exercised for real, not just its happy case.

---

# Slice 3 — Case Manager

The most constrained role in the system, and the slice with the most refusals.

## 1. There is no draft editor, and there cannot be one

The brief's flow is _open case → review documents → **build draft → save draft** →
submit to PM_, which implies an authoring surface. EvalOS has none and is barred from
one — no object storage, no files (`architecture.md`). `draft_link` is a single Drive
URL column.

The draft workspace is therefore a **staging** surface, not an editor: read the
documents, read the strategy notes, read what came back, open the Drive document,
paste the link, submit.

`submitDraft` increments `draft_version_count`, sets `pm_approval_status = PENDING`,
and **nulls `clientApprovalStatus`** — "a new draft is not the draft the client
already saw". That last one is preserved on screen: after a resubmit the client
review state is genuinely blank, not stale.

## 2. No revision store, so no version comparison

The only migration matching _revision_ is `V5`, the counter column itself. There is
**one** `draft_link`, overwritten on each submit, and an integer beside it.

The brief's "review previous revisions / compare versions" is **refused**. Version
history lives in **Google Drive's own revision history**, which is where the document
actually is. The workspace links there rather than rendering a diff it cannot
compute. Faking one would mean storing drafts, which invariant "no files" forbids.

## 3. The CM's two most important inputs are already stored — in the audit trail

```java
pmReturnDraft(caseId, comments)        → comments become the audit note
clientRequestRevisions(caseId, notes)  → notes become the audit note
```

"Review PM comments" and "review client feedback" are **trail reads**. No new table,
no new column — and because the trail is append-only, the full history of every
return and every revision request is on disk, which is more than the case row itself
remembers.

## 4. Strategy notes are a text blob, not a thread

`pm_strategy_notes` is a single `text` column. The brief asks that notes "preserve
author and timestamp information"; **per-note attribution is not stored.** The audit
trail records who changed the notes and when; it does not keep each note as a
separately authored entry.

The screen shows what is true — last changed by, and when, from the trail — rather
than faking per-note bylines over a column that has none.

Gating is already right and needs no change: `SEES_STRATEGY_NOTES = {GM,
PROJECT_MANAGER, CASE_MANAGER}`, with the code's own comment that the CM is "the one
role that reads without writing".

## 5. Two writes, neither of which is an escalation

The Case Manager's own gated endpoints, enumerated rather than estimated:

| Endpoint                                                           | Kind                                    |
| ------------------------------------------------------------------ | --------------------------------------- |
| `CaseController:317` — `draft/submit`                              | **write**                               |
| `PortalLinkController:79` — mint a client portal link (`MAY_MINT`) | **write**                               |
| `PortalLinkController:70` — read the current link                  | read                                    |
| `ExpertProfileController:71,82` — `/redacted`, `/full`             | read                                    |
| `refund/request`                                                   | write, but open to **every** staff role |

So two writes of their own, not one — and **neither is a way to escalate a blocked
case**, which is the gap decision 7 fills. The portal mint is theirs for a documented
reason: the CM wrote the draft and is "the person fielding _my link doesn't work_".

Against the brief's eight Case Manager quick actions:

| Brief asks                                                            | Reality                                                                                          |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| Open case · view documents · submit draft · revise draft              | ✅ built                                                                                         |
| _(not asked for, but theirs)_ mint / re-mint the client's portal link | ✅ built — surfaced in the workspace                                                             |
| **Send signed letter to expert**                                      | Unit 15, unbuilt → `unavailable`                                                                 |
| **Reassign expert**                                                   | PM/ENM-gated **and** requires the `EXPERT_DECLINED_REMATCHING` exception. Not the CM's — refused |
| **Flag issue to PM**                                                  | Built, per decision 7                                                                            |

**An inconsistency in shipped code, surfaced rather than papered over:**
`STAGE_ACCESS` grants the Case Manager `full` on `EXPERT_SIGNING`, but no CM-gated
action is declared on that stage — so a CM watches the signing column with `full`
access and zero actions. Decision 7 resolves it: the flag becomes the CM's action
there. Had decision 7 gone the other way, the correct fix was to demote the cell to
`status`.

## Metrics

| Metric                           | Definition                                                                                                                                                                                                         |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Priority queue** _(primary)_   | Own cases by `DeadlineRisk` — due today, then tomorrow, then later. Slice 1's calculator, narrowed                                                                                                                 |
| **Completed on time**            | `delivery_date <= deadline`, scoped to `assigned_cm`                                                                                                                                                               |
| **Own draft revision rate**      | Share of own cases with `draft_version_count > 1` — slice 1's definition, narrowed                                                                                                                                 |
| **Client revision request rate** | Count of `CLIENT_REQUEST_REVISIONS` **audit rows** over own delivered cases. **Not** current state: a case that had revisions and was later approved reads `APPROVED`, so a state-based count silently undercounts |

**On "identify consistently high revision rates rather than only showing a raw
number".** At the NFR scale — 50–100 cases per brand per month across a handful of
Case Managers — one CM's monthly rate is a **small sample**. So the denominator
renders beside the rate (slice 1's rule), the comparison is against the brand median,
and **no flag fires below a minimum case count**. A coaching signal derived from four
cases is noise that costs somebody a conversation they did not earn.

## Screens

**`/dashboard`** — the priority queue is the largest tile; it is the whole job.

**`/my-cases`** — re-skin only. Already the board narrowed by server scope, so there
is no scope work here, only presentation.

**Draft workspace** — a tab on case detail, not a route: documents, strategy notes,
PM return comments, client revision notes, the Drive link, submit. Submitting states
plainly that the draft is moving into PM review.

**Expert signing panel** — read-only for this role. Elapsed time is measured against
the existing **24-hour `EXPERT_SIGN` budget** in `SlaCalculator`; the brief's "if
response exceeds 24 hours" _is_ that budget, so it is reused rather than
re-declared. Reassignment is not the CM's, so the panel names who can act and offers
the flag instead.

## Refused in slice 3

The Intern review chain (no such role — see Provenance). In-app draft authoring.
Version comparison. Expert reassignment by the Case Manager.

## Verification

Adds to the running list: the CM scope check — no case not assigned to them, no
`deal_value` (the CM is **not** on `SEES_DEAL_VALUE`), no other CM's docket — and a
test that the flag endpoint notifies the brand's PMs and nobody else.

---

# Slice 4 — Expert Network Manager

Supply side only. The axis `architecture.md` draws is **no case content and no client
identity**, and the Prerequisite above is what makes that true in code rather than
only in the document. Slice 4 does not start until it has landed.

This is the most blocked slice: two of its metric families wait on unbuilt units, and
two of the brief's four screens were already refused in Provenance.

## Buildable now

| Metric                            | Definition                                                                                                                                                                                                                         | Closes         |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| **Roster health** _(primary)_     | Counts by `Availability`, and how many experts hold each `FieldTag`. A field with fewer than five available experts raises a **coverage-gap alert** linking to that field                                                          | **G6**         |
| **Utilization**                   | Active cases per expert against roster capacity, from `ExpertLoadService` — **never `expert.current_active_count`**, which has no writer                                                                                           | —              |
| **Acceptance rate**               | `ACCEPTED / (ACCEPTED + DECLINED + TIMED_OUT)` **imported from `ExpertMatchService`**, not re-expressed. Two definitions of one expert's acceptance rate is how a dashboard and a shortlist come to disagree about the same person | —              |
| **Experts declining two or more** | `OfferOutcome.DECLINED` rows in `expert_case_offer`. The tracker's own guidance: read declines from the offers, not from a flag column                                                                                             | part of **G8** |
| **Onboarded vs target**           | Count over `expert.date_onboarded`; the target is one configured number per brand, per decision 4's precedent                                                                                                                      | **G7**         |
| **Performance flags**             | The new ENM-gated writer, per decision 9                                                                                                                                                                                           | **G8**         |

## Blocked, and rendered `unavailable` rather than zero

| Tile                                                                   | Blocked on                                                                                                                                                          |
| ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Fleet-wide and per-tier response time**                              | Unit 15. Derive from `expert_case_offer` when it exists — **never from `expert.avg_response_hours`**, which is permanently null (**G9**). Do not revive that column |
| **Payments pending / overdue, and the >7-day relationship-risk alert** | Unit 16. `payout_ledger` (`V8`) and `PayoutLedger` exist; there is no service, no controller and no writer                                                          |
| **Quality-score trend**                                                | Nothing — it is an accepted limitation (**G10**), not a dependency. Current distribution only, labelled current, no trend arrow                                     |

## Screens

**`/dashboard`** — the largest tile is roster health and coverage gaps. That is the
question this role exists to answer: _do we have the capacity to take the work._

**`/experts`** — re-skin of the shipped roster, availability board and profile
(Unit 11). Availability grouped by field with the coverage-gap alert inline; filters
across expertise, tier, availability, quality, agreement and payment status. The
profile gains a performance section carrying the new flag action and the expert's
activity history.

**`/payouts`** — no change needed, and this line previously asked for one. It said the route
should render the `unavailable` card "instead of a placeholder page, so the screen states its own
status rather than looking broken". Checked at build time: `PlaceholderPage` already reads the
label and the `becomes` string off the nav table, so `/payouts` renders _"Not built yet — Payout
ledger (Unit 16)"_ with a way out. That is the same statement the card would make, in the pattern
the codebase already has. Swapping it would have been churn.

## What the ENM still must not see

The dashboard and every screen in this slice carry **no client name, no case content,
no draft, no deal value and no revenue figure.** Experts are matched to a case by
**field**, never by client. This is asserted in tests, not left to review — it is the
invariant the Prerequisite exists to restore.

## Refused in slice 4

The recruitment-pipeline Kanban (Identified → Contacted → Agreement Sent → Signed →
Active) and outreach call/email tracking. Both were refused in Provenance: no schema,
not in the build plan, and a supply-side subsystem rather than a UI pass. If the
business wants them, they are a unit of their own with their own migration.

## Verification

Adds to the running list: an ENM scope test asserting **absence** of `clientName`,
`driveLink`, `draftLink` and `dealValue` from every payload the role can reach — the
test `CaseControllerTest:272` should always have had — plus a test that the
performance-flag writer is refused for every role but the ENM and GM.

---

# Slice 5 — GM / Brand Manager

**The brief contains no GM and no Brand Manager section.** It covers PM, Coordinator,
CM and ENM only. So this slice takes no requirements from the brief at all —
`17-dashboards.md`'s role table is the sole source, and the job here is to give the
two oversight roles the same treatment slices 1–4 gave the operators.

## The correction that shapes the slice

**There is no `refunded` column.** Refunded is a _derived pair_ — stage `CLOSED`
**plus** `exception_state = REFUND_REQUESTED` — and `RefundService` already owns both
of the expressions that matter:

```java
RefundService.isRefunded(subject)          // CLOSED && exceptionState == REFUND_REQUESTED
RefundService.isRevenueRecognized(subject) // invariant 5's definition
```

`17-dashboards.md` §1 writes its money table as `where paid AND refunded`, as though
`refunded` were a column. It is not. **The dashboard imports these two static methods
and never re-expresses them** — the same rule slice 4 applies to acceptance rate, for
the same reason: two definitions of "refunded" is how the GM's revenue tile and the
case's own state come to disagree about the same money. Unit 17's table is corrected
at source rather than annotated.

The comment on `RefundService` is worth carrying onto the screen: an approved refund
**deliberately leaves `exception_state = REFUND_REQUESTED`** on a closed case,
because that pair _is_ the refunded state. A screen that renders the exception lane
naively would show every refunded case as an open exception forever.

## The money tiles

Per `17-dashboards.md` §1, and projected through `CaseController.SEES_DEAL_VALUE`
**read at build time** — GM, Brand Manager and Project Manager. A role not on that
list gets the count-based tiles and **no money tiles at all**, not zeroes.

| Tile               | Source                                                                     |
| ------------------ | -------------------------------------------------------------------------- |
| **Collected**      | `SUM(deal_value)` where paid and not refunded                              |
| **Recognized**     | paid, delivered, not refunded                                              |
| **Open liability** | paid, not delivered, not refunded — money taken for work not yet delivered |
| **Refunded**       | shown beside the three, inside none of them                                |
| **Money out**      | ⛔ `unavailable`, naming Unit 16                                           |

`Collected = Recognized + Open liability` exactly, and the screen asserts it. If the
three ever fail to reconcile, the tile says so rather than rendering three numbers
that quietly disagree.

**"Unpaid pipeline" is removed** (decision 10). Unit 05b creates every case paid, so
the figure decays to a permanent zero, and a money tile that always reads zero is one
people stop reading — which is exactly when it matters that it isn't zero. Its
alternative, quotes-not-yet-won, is GHL's under invariant 2. Any legacy unpaid rows
remain visible on the board. `17-dashboards.md`'s table is corrected at source.

## The cross-brand trap

This is the slice where cross-brand aggregation actually happens, so the rule
`17-dashboards.md` states in passing becomes a build note:

**Cycle time is brand-scoped _through the case_, never through
`audit_event.brand_id`** — that column is **null for every action the GM takes**.
Filtering on it would silently drop the GM's own activity from every cross-brand
figure, and the number would look plausible.

The GM's figures cover all brands and narrow with the brand switcher; the Brand
Manager's cover their brand and the switcher is locked.

## The Brand Manager is not "the GM with one brand"

`SEES_STRATEGY_NOTES = {GM, PROJECT_MANAGER, CASE_MANAGER}` — the **Brand Manager is
excluded**, and `CaseControllerTest:272` asserts it. So the BM dashboard is not the
GM's filtered down; it is its own tile set, and any component that assumes oversight
implies full visibility will leak notes to a role deliberately kept out of them.

## Refund rulings need no new screen

`refund/approve` and `refund/deny` are `gmOnly`, and no dedicated queue exists. **That
is fine and no screen is added:** the board already carries a **Refund Requested**
exception lane, and the GM can reach `/board`. A second surface listing the same
cases is the `/cases`-beside-`/board` duplication this spec has now refused three
times. The lane gets the ruling actions; that is the whole fix.

## Screens

**`/dashboard`** — the largest tile is money in vs delivered, per `17-dashboards.md`'s
role table. Per-brand breakdown for the GM; single brand for the BM.

**`/brands`** — GM only, brand administration. Re-skin.

**Everything else** — the GM and BM reach the board, checklists, experts and payouts,
all of which slices 1–4 have already re-skinned. Slice 5 adds no new screens beyond
the two above; its work is aggregation, the brand switcher, and the money tiles.

## Verification

Adds to the running list: `Collected = Recognized + Open liability` asserted as a
test, not just on screen; a Brand Manager scope test asserting strategy notes are
absent; a cross-brand cycle-time test with a GM-authored `audit_event` row proving it
is **not** dropped; and the money tiles absent entirely for every role off
`SEES_DEAL_VALUE`.

## Documents this unit changes

Per `ai-workflow-rules.md`, these are updated **with** the slice that causes them,
not as a later tidy-up.

| Document                 | Change                                                                                                                                                                                                                                               | Slice  |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| `00-build-plan.md`       | Add Unit 22; record that it supersedes and re-cuts Unit 17                                                                                                                                                                                           | 1      |
| `17-dashboards.md`       | Note the re-cut. Three corrections at source: the revision-rate definition (slice 1); the money table's `refunded`, which is a derived pair and not a column (slice 5); and the removal of "Unpaid pipeline" (slice 5)                               | 1, 5   |
| `ui-context.md`          | Split the RAG table into stage SLA vs deadline risk; rewrite the Lucide and component-library paragraphs; add the chart ramp; document the card states                                                                                               | 1      |
| `architecture.md`        | Stack table: three new frontend dependencies                                                                                                                                                                                                         | 1      |
| `architecture.md`        | Record that the ENM information wall is now enforced in code, not only stated                                                                                                                                                                        | Prereq |
| `progress-tracker.md`    | The Prerequisite fix and its finding; slice status; close G3/G4/G5/G6/G7/G8/G11/G12 as each lands; add `google_review_requested` to the standing counter-column-with-no-writer list; record G9 and G10 as accepted limitations rather than open gaps | every  |
| `code-standards.md`      | The `components/ui/` convention and the frozen-foundation rule                                                                                                                                                                                       | 1      |
| **Serena `frontend/*`**  | The new structure, the card system, the primitives                                                                                                                                                                                                   | 1      |
| **Serena `conventions`** | Frozen foundation; deferred-dependency triggers                                                                                                                                                                                                      | 1      |
| **Serena `tech_stack`**  | `radix-ui`, `lucide-react`, `recharts`                                                                                                                                                                                                               | 1      |
