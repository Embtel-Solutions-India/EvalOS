# EvalOS — Independent Product Audit

**Auditor:** Senior Product Manager (independent)
**Date:** 2026-09-13
**Repo:** `C:\Users\ASUS\OneDrive\Desktop\EvalOS`, branch `development`, HEAD `dee45c6`
**Method:** specs + code read directly. Serena MCP was down; everything below is grounded in a
file path, a class, an enum value or a migration. Where a spec and the code disagree, the code wins
and the disagreement is itself a finding.

**Scope boundary:** this is a product audit. Architecture, code quality, UX craft and integration
mechanics are other agents' briefs. Where I name a class it is as evidence for a product claim, not
as a code review.

---

## Executive orientation — what is actually built

| Layer | State |
|---|---|
| Production pipeline (12 stages, Unit 31) | **Built.** `domain/Stage.java`, `service/CaseTransitions.java`, `V31` |
| Staff app | **Built**, 19 routes, 6 production roles + 2 pipeline roles (`frontend/src/features/shell/navigation.ts`) |
| Sales & Marketing desks in EvalOS (Units 36–41) | **Built** 2026-09-10/11, `V39`–`V41`. Invariant 2 is dead; GHL write door is open |
| Client Portal | **Built and accounted** (Unit 42, 2026-09-12) — sign-in, password, cases, documents, draft review, invoices, meetings |
| Expert Portal | **One screen.** `/case` only. `GET /portal/expert/cases` and `/payouts` exist on the backend with no screen calling them |
| Intake funnel (Unit 43) | **Specced, not built.** `/start` is a placeholder that says "we cannot take evaluations through the portal yet" |
| GHL mirror + independence (Units 44–49) | **No specs, no code.** `context/specs/` stops at `43` |
| ENM as a function | **A roster + a payout ledger.** Nothing else |

The business has built an excellent *production* system and, in the last four days, a credible
*selling* system. What it has not built is the **connective tissue between them**, and that is where
every pain the brief describes lives.

---

## Lifecycle walkthrough

The target lifecycle: Lead/Client → Marketing → Sales → Payment → Case Creation → Production →
Expert → Client Review/Approval → Completion, with Expert Network Management alongside.

I walk it in twelve steps because that is how many distinct owners it actually has.

---

### 1 · Lead capture

| | |
|---|---|
| **Owner** | MARKETING (`Role.MARKETING`, `Tier.PIPELINE`) |
| **System today** | Split. A lead created *by* marketing is created in EvalOS (`MarketingLeadController POST /api/marketing/leads` → `GhlWriteClient.upsertContact` + `upsertOpportunity`). A lead arriving *inbound* from the website lands in GHL directly and EvalOS never sees it until it is on a pipeline board |
| **Target** | Every lead enters through EvalOS — including the self-service applicant |
| **Break at the seam** | **The website is not wired.** Unit 43's funnel is the only self-service door and it is a placeholder file (`client-expert/client/src/pages/auth/Start.tsx`). Today an inbound web lead reaches GHL by whatever form the marketing site posts to, and the marketer meets it on a board. There is no EvalOS-side record that a human tried and gave up |

Also note: `NotificationType.NEW_LEAD` exists in the enum. Nothing in the production-side routing
table uses it for an inbound lead — it is the marketing desk's own.

---

### 2 · Marketing nurture

| | |
|---|---|
| **Owner** | MARKETING, one person per GHL pipeline (`uq_team_member_pipeline`, `V39`) |
| **System today** | **EvalOS.** `/opportunities/board` (`OpportunityBoardPage.tsx`), `NewLeadForm`, `DealNotes`, valuations. Writes go to GHL synchronously and GHL's answer is displayed |
| **Target** | Same. This is built and is the programme's success story |
| **Break at the seam** | **The marketer's notes are invisible to everyone downstream.** `OpportunityNoteController` is gated `@PreAuthorize("hasAnyRole('SALES','MARKETING')")` on both GET and POST — *the GM is excluded by explicit decision* and no production role has ever been considered. What Marketing learned about a lead is readable by exactly two roles |

The handoff *within* the pipeline layer is genuinely solved: a GHL automation moves the opportunity
to a sales pipeline **keeping its id**, and because `opportunity_note.ghl_opportunity_id` is the
key, the conversation travels with it at zero cost (`OpportunityNoteService` javadoc). That is good
design. It stops dead at the production boundary.

---

### 3 · Sales

| | |
|---|---|
| **Owner** | SALES |
| **System today** | **EvalOS.** `SalesDeskController` — update opportunity, move stage, set won/lost, follow-ups (GHL tasks), meetings (`GhlCalendarClient`, shipped 2026-09-11) |
| **Target** | Same, plus: Sales can answer a client's status question without asking Production |
| **Break at the seam** | **A salesperson cannot see a single case, ever.** `Role.SALES` is `Tier.PIPELINE`; `ScopePredicate`'s PIPELINE arm reads `fields.pipeline()` and `Case` has no pipeline column, so the arm returns `cb.disjunction()` — matches nothing, by design ("an empty board is a support call, a full one is a breach"). And `/board` is not in SALES' nav. This is the status loop, proven in code rather than inferred |

---

### 4 · Payment / invoicing

| | |
|---|---|
| **Owner** | GHL (+ QuickBooks) |
| **System today** | GHL invoices and collects. EvalOS *reads* invoices for the client portal (`GhlInvoiceClient`, `PortalInvoiceService`, Unit 41, exercised live 2026-09-11) |
| **Target** | Unchanged until Unit 49. `CLAUDE.md` is explicit: invoicing is still GHL's |
| **Break at the seam** | **Invoices are keyed on `ghl_contact_id` and that key broke on 2026-09-11.** IE replaced its GHL sub-account with `WY6bW2xUCI8Tz8gw7aLJ` and migrated no contacts (`00c` §1). Every pre-cutover client's invoice list is now empty. Same for meetings. This is live, in production, today |

---

### 5 · Case creation — Handoff A

| | |
|---|---|
| **Owner** | System (`GhlOpportunityHandler`) |
| **System today** | GHL workflow POSTs `opportunity.won` → `POST /api/webhooks/ghl/{token}` → a **paid** case at `DOC_COLLECTION` in the brand pool, `NEW_CASE_IN_POOL` to PMs + Coordinators |
| **Target** | Unchanged. Invariant 8 survives both programmes |
| **Break at the seam** | **Two, and they are the most consequential findings in this audit.** |

**(a) The webhook workflow does not exist in the new GHL account.** `00c` §1b: *"Until it is
recreated, Handoff A is dead and no case is created by anything."* Nothing in the tracker records
it as rebuilt. This is a business-stopping item that is currently written down as a bullet in a
programme document.

**(b) Nothing Sales knew reaches the case.** `GhlOpportunityHandler.OpportunityWon` carries
exactly: `contactId`, names, `email`, `phone`, `companyName`, and `customData{serviceType,
opportunityId, amount}`. The class comment is candid about it: *"Visa category, subtype, deadline,
invoice ref, expert and the intake note are a PM's to fill in."*

Unit 23 §4 specced this differently — *"`OpportunityWon` gains an optional `notes` field … whatever
sales typed on the opportunity is the first thing on the timeline"* — and **it was never built**.
Spec 05b then wrote the opposite decision without reconciling the two. So the PM opens a paid case
with a name and a number and re-interviews the client for everything else.

---

### 6 · Document collection

| | |
|---|---|
| **Owner** | PROJECT_COORDINATOR |
| **System today** | EvalOS checklist (`ChecklistController`, `/checklists`); client uploads through the Client Portal to S3 under `{brandId}/client/{...}`; Coordinator marks each item `MISSING`/`INCORRECT` and the client sees that in-portal (Unit 34c) |
| **Target** | Same, plus the client is actually *told* something is missing |
| **Break at the seam** | **The chase has no channel.** `DocChaseSweep` fires at 24h and 48h and raises `DOC_CHASE_DUE` **to the Coordinator**, asking a human to chase by hand (`process-automation.md` T2/T3). EvalOS has SMTP (`ClientMailer`) and is forbidden to use it for this. So the 3-business-day `DOC_COLLECTION` clock runs against a client who may never have been contacted. **A07 (Unit 21) is still an open gap** — G1 |

Ownership question the brief raises and the system does not answer: **now that SALES exists, who
chases a document?** Today it is unambiguously the Coordinator. But the client's relationship is
with the salesperson who closed them, and the Coordinator has no channel. See Findings F7.

---

### 7 · PM review & expert selection

| | |
|---|---|
| **Owner** | PROJECT_MANAGER (`Stage.PM_REVIEW`) |
| **System today** | **EvalOS, and well.** `/inbox`, `/expert-assignment`, `ExpertShortlistController` (ranked top-3, four declared factors, never auto-assigns), `pm_strategy_notes`, `expert_selection_rationale` (`V32`) |
| **Target** | Same |
| **Break at the seam** | **The ENM advises on availability and has no way to be asked.** `process-automation.md` §05 lists *"ENM notified when a case needs an expert"* as an automation; `NotificationType` has no such value and `NotificationListeners.ROUTES` has no such route. The ENM learns a case needs an expert by not learning it |

---

### 8 · Drafting and PM review

| | |
|---|---|
| **Owner** | CASE_MANAGER → PROJECT_MANAGER |
| **System today** | **EvalOS, and well.** `DRAFT_IN_PROGRESS` ⇄ `DRAFT_REVIEW`, versioned `case_document` rows with per-version `review_comment`, `/drafts` (PM queue), `/my-drafts` + `/pm-notes` (CM's side) |
| **Target** | Same |
| **Break at the seam** | Minor. In-document annotation is explicitly not built and explicitly not planned (`32` §4) — correctly, in my view. `A12` is honestly marked *partly* |

This is the strongest part of the product. It should be the model for everything else.

---

### 9 · Client review and approval

| | |
|---|---|
| **Owner** | PROJECT_COORDINATOR holds; the client acts |
| **System today** | `READY_TO_SEND` → Coordinator presses Send → `CLIENT_REVIEW`. The client signs in (Unit 42), opens `/draft/:caseId`, approves or requests revisions. The approval writes an audit row naming *the client* (`ActorType`, `V22`) |
| **Target** | Same |
| **Break at the seam** | **"Send" sends nothing.** `SEND_DRAFT_TO_CLIENT` is a state transition; T5 ("draft ready to review") is `DECISION PENDING` in the touchpoint table. The client learns their draft is ready only by opening the portal. The 48h client-review clock starts at the transition regardless |

The portal *does* now make this discoverable — `Requests.tsx` renders a server-written `step`
("Review — action required") — which is a real improvement. But a state waits; it does not reach.

---

### 10 · Expert signing — Handoff B

| | |
|---|---|
| **Owner** | CASE_MANAGER holds; the expert acts |
| **System today** | CM presses *Send to Expert* → `EXPERT_SIGNING`. CM mints a link (`POST /api/cases/{id}/portal-link`), **copies it out of the UI by hand**, and sends it by some means EvalOS does not know about. Expert opens `/case#<token>`, accepts / requests evidence / declines, downloads, uploads the signed PDF |
| **Target** | The expert has an account, sees all their cases, their deadlines and their earnings |
| **Break at the seam** | **Three, stacked.** |

1. **G15 is open and it is the sharpest reach failure in the system.** `PortalLinkController`'s own
   javadoc: *"the whole link … Shown once and stored nowhere — staff copy it to the expert by hand,
   which is G15."* An expert who never receives the link cannot sign, and the 20h/24h clocks
   (`ExpertSignSweep`) run anyway.
2. **The expert portal is one case, one link.** Unit 35 shipped party-scoped expert credentials and
   the backend routes `GET /portal/expert/cases` and `GET /portal/expert/payouts` — **and the SPA
   has exactly one route, `/case`** (`client-expert/expert/src/App.tsx`). `expertNavigation.ts` and
   `mock/expertMockData.ts` are dead files pointing at `/expert/payments` and `/expert/profile`
   that do not exist. An expert with three cases holds three links and can see none of the others.
3. **No expert accounts.** Unit 42 gave the *client* a front door and deliberately did not
   generalise it to the expert. So the expert's identity is a link in an inbox.

---

### 11 · Final QC, delivery, close

| | |
|---|---|
| **Owner** | PM (`FINAL_QC`) → Coordinator (`READY_TO_DELIVER` → `DELIVERED` → `CLOSED`) |
| **System today** | Built end to end, including `PM_QC_FAIL → DRAFT_IN_PROGRESS`. `DELIVERED` writes the `payout_ledger` row in the same transaction |
| **Target** | Same |
| **Break at the seam** | T8 ("final signed letter delivered") is `DECISION PENDING`. The Coordinator "delivers" by changing a stage; the client discovers it by logging in. T9 (review + retention) is settled as GHL's and is **started by hand** since Handoff C was removed with Unit 18 |

---

### 12 · Expert payout

| | |
|---|---|
| **Owner** | EXPERT_NETWORK_MANAGER (widened from GM/BM in Unit 16b, deliberately) |
| **System today** | `payout_ledger` row per delivered draft; `payout_payment` = one weekly transfer settling many drafts; `/payouts` batch screen, Monday-start weeks |
| **Target** | Same, plus the expert can see it |
| **Break at the seam** | `GET /api/portal/expert/payouts` is built (D6, Unit 35). **No screen calls it.** So the expert asks the ENM, who asks… this is the *second* status loop and nobody has named it |

---

### Handoffs that are still a human passing a message

Enumerated, because this was asked for explicitly:

| # | Handoff | Carried by |
|---|---|---|
| H1 | Marketing → Sales context | GHL automation moves the opportunity; notes follow by id. **Solved** |
| H2 | Sales → Production context | **A human. Nothing is carried.** Handoff A drops the note, the questionnaire, the promises, the deadline |
| H3 | Production → client, "upload your documents" | Coordinator, by hand, off a `DOC_CHASE_DUE` prompt |
| H4 | Production → client, "your draft is ready" | Nothing. The client must open the portal |
| H5 | Production → expert, the signing link | **CM copies a URL and sends it by unknown means (G15)** |
| H6 | Expert → client, evidence request | `expert.evidence_requested` → T7, pending. A human tells the client |
| H7 | Production → client, "delivered" | Nothing. T8 pending |
| H8 | Production → GHL, start the review sequence | **A human, in GHL.** Handoff C removed with Unit 18 |
| H9 | Client/Sales → Production, "what's the status?" | **A human chain: client → Sales → PM → Sales → client** |
| H10 | Expert → Production, "where's my money / what's next?" | A human chain: expert → CM/ENM |
| H11 | PM → ENM, "this case needs an expert" | Nothing. No notification type exists |
| H12 | Anyone → ENM, "we need more experts in field X" | A coverage-gap tile on the ENM dashboard, and hope |

**Nine of twelve handoffs are a human carrying a message.** That is the product problem, stated
once.

---

## The status loop

**The stated pain:** a client asks Sales for status → Sales asks Production → Production answers →
Sales relays it. Plus experts asking Production/Sales about their own cases.

### Why it happens — the mechanism, not the symptom

Three independent facts, each verified:

1. **Sales cannot read a case.** `Role.SALES` → `Tier.PIPELINE` → `ScopePredicate` line 119: if the
   entity has no pipeline column the predicate is `cb.disjunction()`. `Case` has none. So even if
   somebody deep-linked a salesperson to `/cases/{id}`, they get nothing. `frontend/.../navigation.ts`
   gives SALES exactly one route: `/opportunities/board`.
2. **Sales cannot read the production conversation either**, and Production cannot read the sales
   one. Two note systems, disjoint audiences (see next section).
3. **Nothing links a case to its opportunity on screen.** `Case.ghlOpportunityId` exists (`V24`).
   `OpportunityNote.ghlOpportunityId` exists (`V41`). **Nobody joins them.** G11 in the gap register
   records this precisely: *"`opportunity_note` (Unit 39) makes the join possible but showing it is a
   scope decision, not built."*

The loop is not a habit. It is the only path the permission model leaves open.

### What exists that helps

- **`PortalStageProjection`** — the single home for "what does an outsider see". Twelve stages →
  five client words (`Upload` / `In progress` / `Review` / `Ready to download` / `Complete`) with an
  `actionRequired` boolean, and three expert words. This is the right design and it is built.
- **The client portal renders it.** `Requests.tsx` shows a per-case step line. So a *client who signs
  in* can self-serve today — for the coarse answer.
- **The staff timeline** (`Timeline.tsx` + `CaseTimelineService` + `audit_event`) is a genuine
  interleaved history of every transition and note, with actor attribution.

### What kills the loop — the requirement

Four things, in dependency order. None is large.

**S1 · A case-status read for SALES, scoped by the opportunity they own.**
The case already carries `ghl_opportunity_id`. The rule writes itself: *a SALES user may read the
production status of a case whose `ghl_opportunity_id` sits in their pipeline.* That is one new arm
in `ScopePredicate` (or, more honestly, one dedicated read service — the case has no pipeline
column and inventing one would be wrong).
**What they see: `PortalStageProjection.forClient(stage)` plus the stage-entered timestamp and the
SLA status — not the draft, not the strategy notes, not the expert.** The client-facing projection
is exactly the right payload for a salesperson, because it is exactly what they are allowed to
repeat to the client. That is not a coincidence; it is the reason to reuse it.

**S2 · The opportunity note stream, shown on the case, and the case status shown on the deal.**
One join, both directions, on a column that already exists in both tables. This is the single
highest value-to-effort item in the audit.

**S3 · The client portal already answers the client — so make the client use it.**
The loop starts with *a client asking Sales*. A client who has a sign-in and a status line does not
need to ask. Unit 42 shipped the door 24 hours ago; nobody has told the clients. This is a comms
task, not a build task, and it should be done before S1 ships.

**S4 · An expert home screen.** The backend is already built (`/portal/expert/cases`,
`/portal/expert/payouts`). The expert SPA needs a list screen and a payouts screen — and, to reach
them, an expert credential that is not a per-case link (see ENM section, and G15).

### What must *not* be built to solve it

A "status update" message, a Sales-composes-an-email feature, or a shared inbox. The client already
has an authenticated surface that holds the truth. Adding a second channel that restates it is how
two answers to one question get created.

---

## Notes & activity model

### There are eight places called "notes". Three are systems; five are text boxes.

| # | Where | Keyed on | Append-only | Who can read |
|---|---|---|---|---|
| 1 | `opportunity_note` (`V41`) | `ghl_opportunity_id` | **Yes**, trigger-enforced | **SALES + MARKETING only** — GM explicitly excluded |
| 2 | Case notes — `audit_event` rows with `AuditAction.NOTE_ADDED`, text in the JSON snapshot | `object_id` = case id | **Yes**, `BEFORE UPDATE OR DELETE` trigger (`V10`) | Anyone the case scope admits (6 production roles) |
| 3 | `evalos_case.pm_strategy_notes` | case | No — overwritten | GM · PM · CM (`SEES_STRATEGY_NOTES`) |
| 4 | `evalos_case.expert_selection_rationale` (`V32`) | case | No | GM · BM · PM · **ENM** |
| 5 | `case_document.review_comment` | draft version | No | production roles |
| 6 | `case_document.notes` | draft version | No | production roles |
| 7 | `expert.notes` | expert | No | GM · BM · PM · ENM |
| 8 | `payout_payment.notes` | one transfer | No | GM · BM · ENM |

**Two disconnected note *systems*, with disjoint audiences and no join.** #1 and #2 are both
append-only, both are "the conversation", and there is no role on earth that can read both. The GM —
the one role whose entire job is oversight — is excluded from #1 *by an explicit decision recorded
in the controller javadoc*, on the grounds that `PipelineScope` would refuse them anyway.

That decision was locally correct and globally wrong. It is the precise moment the system decided
that the sales conversation and the production conversation are different businesses.

### The product-level model: one timeline, three anchors, one projection

Do **not** build a `universal_note` table. The two stores are correct as they are — they are
append-only, they are scoped differently for good reasons, and merging them would mean choosing one
key and losing the other. The right move is a **read model**, not a write model.

```
                 ┌──────────────────────────────────┐
   CUSTOMER      │  ghl_contact_id                  │   one person
                 └──────────────────────────────────┘
                            │ 1..n
                 ┌──────────────────────────────────┐
   OPPORTUNITY   │  ghl_opportunity_id              │   one purchase
                 │   ← opportunity_note             │   what Marketing said
                 │   ← GHL stage moves, meetings,   │   what Sales promised
                 │     follow-ups (already audited) │
                 └──────────────────────────────────┘
                            │ 0..1   (Case.ghl_opportunity_id — the column exists)
                 ┌──────────────────────────────────┐
   CASE          │  evalos_case.id                  │   one engagement
                 │   ← audit_event (transitions     │   what Production is doing
                 │     + NOTE_ADDED)                │   what the Expert needs
                 │   ← case_document versions       │
                 │   ← expert_case_offer            │
                 └──────────────────────────────────┘
```

**Invariant 7's three-identifier rule is already the model.** It says: `ghl_contact_id` = the
client, `ghl_opportunity_id` = one purchase, `evalos_case.id` = one engagement. The timeline is
that hierarchy, read downward, with each level's events merged in timestamp order and **each event
filtered by the reader's role at serve time**.

One service (`UnifiedTimelineService`), one endpoint, one projection function. No migration. No new
truth. The anchor you pass decides how much you get: pass a case id and you get the case's events
plus its opportunity's; pass an opportunity id and you get the deal's plus any case born of it.

**The one thing that must change in the write model** is Handoff A: `OpportunityWon` should carry
the intake note onto the `CREATED` audit row, exactly as Unit 23 §4 specced and 05b overrode. That
is a `customData` field and four lines in `GhlOpportunityHandler`.

### Per-role visibility matrix

Rows are event classes; columns are roles. **R** = read, **W** = write, **—** = not visible.
`ENM` deliberately never sees client identity or case content (`22` slice 4; asserted in tests).

| Event class | GM | BM | PM | PC | CM | ENM | SALES | MARKETING | Client | Expert |
|---|---|---|---|---|---|---|---|---|---|---|
| Marketing notes / lead activity | R | R | R | — | — | — | R | R·W | — | — |
| Sales notes, promises, quoted scope | R | R | R | R | R | — | R·W | R | — | — |
| Meetings & follow-ups booked | R | R | R | — | — | — | R·W | R·W | R (own) | — |
| Won/amount/invoice reference | R | R | R | — | — | — | R | — | R (own) | — |
| Case created, stage transitions | R | R | R | R | R | **step only, no client id** | **projected step** | — | **projected step** | **projected step, from signing on** |
| Case notes (`NOTE_ADDED`) | R | R | R·W | R·W | R·W | — | — | — | — | — |
| Document checklist state | R | R | R | R·W | R | — | **summary only** | — | R·W (own) | — |
| PM strategy notes | R | — | R·W | — | R | — | — | — | — | — |
| Expert selection rationale | R | R | R·W | — | — | R | — | — | — | — |
| Draft versions + review comments | R | R | R·W | R | R·W | — | — | — | **current version only** | R (the letter) |
| Client approval / revision request | R | R | R | R | R | — | **fact + date** | — | R·W | — |
| Expert offer / accept / decline | R | R | R | — | R | **R, no client id** | — | — | — | R·W (own) |
| Signed letter uploaded | R | R | R | R | R | R (fact) | — | — | — | R·W (own) |
| Delivery & close | R | R | R | R·W | R | R (fact) | **R** | — | R | R (fact) |
| Payout rows & settlement | R | R | — | — | — | R·W | — | — | — | **R (own rows, never `payment_detail`)** |
| Deal value / revenue | R | R | R | — | — | **never** | R | R | R (own invoice) | — |

Three rules make that table maintainable rather than a permission matrix nobody keeps in step:

1. **Outsiders get a projection, never a row.** `PortalStageProjection` already is this. Sales joins
   the outsider set for case data — that is the whole of S1.
2. **The ENM axis is supply, not secrecy-by-omission.** They see everything about an *expert* and a
   case's *field requirement*, and never a client name or a money figure. Already asserted in tests;
   keep it.
3. **Money has one gate** (`CaseController.SEES_DEAL_VALUE`) and it does not get a second.

---

## ENM product definition

### What ENM is today

A roster screen, an availability board, a sheet importer, a match engine, a payout batch screen, and
a dashboard. `ExpertController` (10 endpoints), `ExpertService`, `ExpertImportService`,
`ExpertMatchService`, `ExpertLoadService`, `ExpertNetworkMetricsService`, `PayoutService`.

**Hiring, onboarding, relationships and work-monitoring do not exist, and were refused in writing.**
`22-role-operations-ui.md` slice 4: *"Refused: the recruitment-pipeline Kanban (Identified →
Contacted → Agreement Sent → Signed → Active) and outreach call/email tracking … If the business
wants them, they are a unit of their own with their own migration."* `17-dashboards.md` marks both
rows **GHL**.

That refusal was correct at the time and is now the blocking constraint. The brief asks for exactly
the thing that was refused. **It should be reversed in writing, as a unit, not drifted into.**

### Entities

| Entity | State | Notes |
|---|---|---|
| `expert` | **EXISTS** (`V7`, `V18`, `V35`) — 37 mapped fields including the full dossier (degree, position, h-index, patents, languages, rush capability) | Excellent. Nothing to add for profile |
| `expert_case_offer` | **EXISTS** (`V19`) — `offered_at`, `outcome`, `outcome_at`, `decline_reason` | This is the work-monitoring substrate. Under-read |
| `payout_ledger` / `payout_payment` | **EXISTS** (`V8`, `V28`) | Complete |
| `expert_application` | **MISSING** | A candidate before they are an expert. Name, field, source, CV object key, stage, owner, notes |
| `expert_pipeline_stage` history | **MISSING** | Append-only stage moves on an application. Reuse `audit_event` with `object_type='EXPERT_APPLICATION'` — the table is already generic |
| `expert_outreach` | **MISSING — and I recommend not building it.** See Cut list | Calls/emails logged by hand rot within a month |
| `expert_availability_window` | **MISSING** | `Availability` is one enum value with no dates. "On leave until the 14th" cannot be expressed |
| `expert_capability` | **EXISTS as arrays** — `primary_fields[]`, `secondary_fields[]`, `letter_types[]`, `visa_categories[]`, closed enums with DB CHECKs | Good. Do not normalise |
| `expert_relationship_owner` | **MISSING** | Which ENM owns this expert. Matters the day there are two ENMs |
| `expert_note` | **MISSING** — `expert.notes` is a single overwritable text box | Should become append-only, keyed on expert. This is the third note stream and the only one that *should* exist and does not |

### Pipeline stages — the Expert Network pipeline

Mirror the shape of Unit 31 exactly: **one stage, one owner, one action, one next stage.** Six
stages, not the five the old brief proposed, because *Sourced* and *Contacted* are different work.

| # | Stage | Owner | Action | → |
|---|---|---|---|---|
| 1 | **Sourced** | ENM | Reach out | 2 |
| 2 | **Contacted** | ENM | Record response / qualify | 3 or Rejected |
| 3 | **Qualified** | ENM | Send agreement | 4 |
| 4 | **Agreement Sent** | ENM | Record signature | 5 |
| 5 | **Onboarding** | ENM | Complete profile + fee + payment detail | 6 |
| 6 | **Active** | — | (becomes an `expert` row) | — |
| X | **Rejected / Lapsed** | ENM | — | terminal |

**Stage 6 is the join.** An application at *Active* mints the `expert` row and stamps
`date_onboarded` — which is what `expert.date_onboarded` has always meant and has never had a
writer. `AgreementStatus{SENT, SIGNED, EXPIRED}` on `expert` is the vestige of this pipeline
surviving on the wrong entity; it moves to the application and stays on the expert as the *current*
agreement fact.

### Screens

| Screen | State | What it is |
|---|---|---|
| `/experts` roster + availability board + profile | **EXISTS** | Keep unchanged |
| Sheet import + validation report | **EXISTS** | Keep |
| `/payouts` batch + `/payouts/experts/:id` + `/payouts/payments/:id` | **EXISTS** | Keep |
| ENM dashboard (roster health, coverage gaps, utilization, acceptance rate, performance flags) | **EXISTS** (Unit 22 slice 4, Unit 17a) | Keep |
| **`/expert-pipeline`** — the six-stage Kanban | **MISSING** | The core new screen |
| **Application detail** — CV, field, stage, notes, agreement | **MISSING** | |
| **`/experts/:id` → workload tab** — live cases, offers outstanding, median turnaround, declines | **MISSING** as a tab; the data all exists (`ExpertLoadService`, `expert_case_offer`, `ExpertNetworkMetricsService`) | Assembly, not new data |
| **Expert-facing home** — my cases, my deadlines, my payouts | **MISSING (frontend)**; backend built | `GET /portal/expert/cases` + `/payouts` have no caller |
| **Expert-facing profile** — availability, fields, fee | **MISSING** | Lets the expert maintain their own dossier, which is the cheapest way to keep it fresh |
| Coverage-gap → "recruit for this field" action | **MISSING** | One button from the dashboard tile into the pipeline, pre-filled with the field |

### Permissions

- The whole ENM surface stays `Tier.SUPPLY` — brand-wide, supply-side, **never client identity,
  case content, draft, or deal value**. Already asserted in tests; the new screens inherit it.
- `expert_application` is ENM + GM + Brand Manager. Not PM (they consume the roster, they do not
  build it).
- The expert's own portal reads only their own rows, by the party credential Unit 35 already built.
- `payment_detail` keeps its property of having **no read path anywhere, for anyone** (invariant 4).
  The expert portal must not become the first exception.

### Reports

| Report | Data exists? |
|---|---|
| Roster health by field, coverage gaps (<5 available) | **EXISTS** |
| Utilization vs capacity | **EXISTS** (`ExpertLoadService`) |
| Acceptance rate, decline reasons | **EXISTS** (`expert_case_offer`) |
| Median turnaround by tier | **EXISTS** (derived 2026-09-11, G9 closed) |
| Payouts pending / overdue >7d | **EXISTS** |
| **Pipeline funnel** — sourced → active, conversion and time-in-stage | **MISSING** (needs the pipeline) |
| **Onboarded vs target** | Count exists; the target has no home (G7, open) |
| **Cost per signed letter, by expert and by field** | **MISSING** — `payout_ledger.amount` ÷ delivered cases. Arithmetic over existing rows. This is the report an ENM is actually judged on and nobody has asked for it |
| Quality-score trend | **Accepted limitation** (G10) — no history. Correct call |

---

## Process gap register

### Verified G-list

| # | Gap | Verdict |
|---|---|---|
| G1 | A07 — client uploads vs the checklist, flags + client notified | **STILL REAL.** Unit 21 partly superseded by Unit 30/34c: the client uploads and sees `MISSING`/`INCORRECT` in-portal. What is still missing is the *notify* half. Narrow it to "the client is told", and it merges into G15's channel question |
| G10 | Quality-score trend | **Accepted limitation.** Correct. Leave it |
| G11 | No field carries GHL's sales notes onto the case | **STILL REAL, and it is the single most valuable open gap.** Both keys exist; the join is unwritten. Reopened-then-declined on 2026-09-11 as "a scope decision". It is now a business requirement, not a scope decision |
| G13 | Client communication log | **STILL REAL and worse than recorded.** Filed as "GHL's, recorded not planned". But EvalOS now runs Sales *and* Marketing *and* the client portal. Nobody can answer "what have we told this client" from one place |
| G14 | Antivirus posture on uploads | **Code half done** (content sniffing, attachment-only serving). Infra half outstanding — bucket-side scanning. Not a product gap |
| G15 | How the expert's portal link reaches them | **STILL REAL and now the most operationally dangerous gap.** Hand-copied; the 24h clock runs regardless |
| G16 | No screen shows which portal links exist or were opened | **CLOSED 2026-09-11** — `PortalLinkLedger.tsx` + `GET /api/metrics/portal-links`. Verified |

G2–G9, G12, G17 verified closed.

### New gaps

| # | Gap | Evidence |
|---|---|---|
| **G18** | **Handoff A is dead in the new GHL account.** The `opportunity.won` workflow lived in the abandoned sub-account and there is no record of it being recreated | `00c` §1b; no tracker entry |
| **G19** | **Sales has zero visibility of any case.** `ScopePredicate` PIPELINE arm + `Case` has no pipeline column → `cb.disjunction()`. Nav gives SALES one route | `service/ScopePredicate.java:119`, `navigation.ts` |
| **G20** | **The sales conversation is unreadable to every production role and to the GM** | `OpportunityNoteController` — `@PreAuthorize("hasAnyRole('SALES','MARKETING')")`, GM excluded in the javadoc by decision |
| **G21** | **Handoff A drops the intake note.** Unit 23 §4 specced it; `GhlOpportunityHandler` does not carry it; 05b wrote the opposite decision without reconciling | `webhook/GhlOpportunityHandler.java` |
| **G22** | **The expert portal has one screen for a backend with four reads.** `/expert/cases` and `/expert/payouts` have no caller; `expertNavigation.ts` and `mock/expertMockData.ts` are dead files | `client-expert/expert/src/App.tsx` |
| **G23** | **The ENM is never told a case needs an expert.** `process-automation.md` §05 lists the automation; no `NotificationType`, no `ROUTES` entry | `domain/NotificationType.java` |
| **G24** | **Invoices and meetings are empty for every pre-cutover client**, live, today | `00c` §1c |
| **G25** | **No outbound channel for anything the business actually needs to say.** SMTP exists and is licensed for two authentication messages only. T1, T2, T3, T5, T6, T7, T8 all `DECISION PENDING` | `process-automation.md`; `service/ClientMailer.java` |
| **G26** | **No expert identity.** No account, no self-service profile, no way to reach an expert except a pasted link | Unit 42 covered clients only |
| **G27** | **No team/user administration screen.** `/brands` is a nav entry with no component; there is no screen to create a user, assign a role, set a team or assign a pipeline — `TeamMemberController` exists, nothing calls `PUT /{id}/ghl-pipeline` from a UI | `frontend/src/App.tsx` `SCREENS` map |
| **G28** | **`expert.notes` is an overwritable text box** on the one entity whose history matters for a relationship | `domain/Expert.java` |
| **G29** | **Stale documentation in code.** Five javadoc references to Dropbox Sign, a provider dropped in Unit 15 | `Expert.java:47`, `ExpertCaseOffer.java:69`, `CaseLifecycleService.java:760`, `CaseController.java:683,692` |
| **G30** | **No ETA anywhere.** Every SLA is internal. Neither the client nor Sales is ever shown an expected completion date, so "when will it be ready" cannot be answered by any surface | `SlaCalculator` is internal-only |

---

## Findings

Ordered by priority, then impact.

---

**F1 · Handoff A is dead in the new GHL sub-account**
**What:** The `opportunity.won` workflow that POSTs to `/api/webhooks/ghl/{token}` lived in
`kBumF0uUOmMBB5bneYjx`, which was abandoned on 2026-09-11. Until it is recreated in
`WY6bW2xUCI8Tz8gw7aLJ`, **no case is created by anything**.
**Evidence:** `context/specs/00c-ghl-independence-programme.md` §1b, marked ⚠. No progress-tracker
entry records it as rebuilt.
**Business impact:** Total. Money is collected and no production work begins. Everything else in
this audit is downstream of a system that is currently not taking orders.
**Decision:** ADD (operational — rebuild the workflow, then fire a live test)
**Priority:** **P0**

---

**F2 · Sales cannot see a case, and that is the status loop**
**What:** `Role.SALES` is `Tier.PIPELINE`. `ScopePredicate`'s PIPELINE arm returns `cb.disjunction()`
for any entity without a pipeline column; `Case` has none. Nav gives SALES exactly `/opportunities/board`.
So the only way a salesperson learns a case's status is to ask a human.
**Evidence:** `backend/.../service/ScopePredicate.java:119`; `frontend/src/features/shell/navigation.ts`.
**Business impact:** Every client status question costs two people an interruption and a round trip,
and the answer degrades in transit. It is the #1 stated operational pain.
**Decision:** ADD — a SALES case-status read, scoped by `Case.ghl_opportunity_id` ∈ my pipeline,
serving `PortalStageProjection.forClient(stage)` + `stage_entered_at` + SLA status. Nothing else.
**Priority:** **P0**

---

**F3 · The sales conversation and the production conversation cannot see each other**
**What:** `opportunity_note` is readable only by SALES and MARKETING — the GM is excluded by an
explicit decision in the controller. Case notes live in `audit_event` and are readable only by
production roles. The join key (`ghl_opportunity_id`) exists on both sides and nobody uses it.
**Evidence:** `web/OpportunityNoteController.java` `@PreAuthorize("hasAnyRole('SALES','MARKETING')")`;
`domain/Case.java:224`; `domain/OpportunityNote.java:44`; gap G11.
**Business impact:** The PM re-interviews a client Sales already interviewed. Sales cannot see what
was promised after the fact. Nobody can reconstruct a disputed engagement end to end.
**Decision:** REFACTOR — one `UnifiedTimelineService` read model over three anchors, projected per
role. No migration, no new truth.
**Priority:** **P0**

---

**F4 · Handoff A drops everything Sales and the client already said**
**What:** `GhlOpportunityHandler.OpportunityWon` carries contact fields plus
`{serviceType, opportunityId, amount}`. Unit 23 §4 specced an intake `notes` field; it was never
built and spec 05b wrote the contrary decision without reconciling the two.
**Evidence:** `webhook/GhlOpportunityHandler.java` — *"Visa category, subtype, deadline, invoice ref,
expert and the intake note are a PM's to fill in."* vs `23-case-notes-and-pm-routing.md` §4.
**Business impact:** Every case starts with a re-interview. Unit 43's questionnaire — fifteen
answers a client will have typed — would be discarded at exactly the moment production needs it.
**Decision:** MODIFY — carry `notes` on `customData` onto the `CREATED` audit snapshot, as specced.
Then F3 makes the rest of the stream reachable anyway.
**Priority:** **P0**

---

**F5 · The expert is reached by a URL somebody pastes into an unknown channel**
**What:** `POST /api/cases/{id}/portal-link` returns the token once; it is stored nowhere; staff copy
it by hand. EvalOS cannot know whether it was sent. The 20h/24h `ExpertSignSweep` clocks run
regardless.
**Evidence:** `web/PortalLinkController.java` javadoc; G15; `process-automation.md` T6.
**Business impact:** The most likely cause of a breached signing SLA is invisible by construction.
It works at current volume and does not scale, which is the definition of a problem that arrives
without warning.
**Decision:** ADD — expert accounts on the Unit 42 pattern (the client half is already built and
proven), so the expert has a durable identity and the link stops being the credential.
**Priority:** **P0**

---

**F6 · The expert portal is one screen over a four-read backend**
**What:** `GET /portal/expert/cases` and `GET /portal/expert/payouts` are built and whitelisted
(Unit 35 D5/D6). `client-expert/expert/src/App.tsx` has one route, `/case`.
`constants/expertNavigation.ts` and `mock/expertMockData.ts` point at routes that do not exist.
**Evidence:** frontend inventory; `web/ExpertPortalController.java`.
**Business impact:** This is the second status loop — the expert asks Production or the ENM what is
next and what they are owed. The answer is already computed and has no screen.
**Decision:** ADD (two screens) + REMOVE (two dead files)
**Priority:** **P1** — P0 the moment F5 lands, because an account with one case screen is worse than
a link.

---

**F7 · Nobody owns reaching the client, and now three roles could**
**What:** Since Unit 18 was removed, EvalOS sends nothing. T1/T2/T3/T5/T7/T8 are all
`DECISION PENDING`. The document chase is a `DOC_CHASE_DUE` prompt asking a **Coordinator** to chase
by hand. With SALES now in EvalOS, the person with the client relationship and the person with the
prompt are different people, and neither is named as the owner.
**Evidence:** `process-automation.md` outward-touchpoint table; `NotificationType.DOC_CHASE_DUE`.
**Business impact:** SLA clocks run against clients who were never contacted. Ownership ambiguity
turns into nobody doing it.
**Decision:** MODIFY — name the owner per touchpoint before choosing a channel. My recommendation
is in Open Questions Q2: **Sales owns pre-production client contact, the Coordinator owns
in-production contact**, and the prompt routes accordingly.
**Priority:** **P1**

---

**F8 · Invoices and meetings are empty for every existing client, in production, right now**
**What:** Both are keyed on `ghl_contact_id` and fetched live from GHL. The sub-account was replaced
with no contact migration.
**Evidence:** `00c` §1c table.
**Business impact:** A client who signs in sees an invoices tab with nothing in it and draws the
obvious wrong conclusion. Two screens the business shipped last week are now actively misleading.
**Decision:** MODIFY — both screens must distinguish "no invoices" from "we cannot look them up for
this client" and say so. This is a two-state empty component, not a data fix.
**Priority:** **P1**

---

**F9 · The ENM is a roster clerk, not a network manager**
**What:** Hiring, onboarding, relationship ownership, dated availability and work-monitoring do not
exist. The recruitment pipeline was refused in writing in Unit 22 slice 4 and marked "GHL" in Unit 17.
**Evidence:** `22-role-operations-ui.md` slice 4 *Refused*; `17-dashboards.md` ENM table rows
*Recruitment pipeline — GHL*, *Outreach activity — GHL*.
**Business impact:** Supply is the constraint on this business. The coverage-gap alert tells the ENM
they are short in a field and there is no system for doing anything about it.
**Decision:** ADD — one unit: `expert_application` + six stages + a Kanban + an expert workload tab.
Reverse the Unit 22 refusal **in writing** first (house rule: pivots are specced before they are
coded).
**Priority:** **P1**

---

**F10 · Unit 43's funnel is the front door and it is a placeholder that turns people away**
**What:** `/start` renders "we cannot take evaluations through the portal yet", names no contact
details, and is linked from `/welcome` and from the `UNKNOWN` branch of sign-in — i.e. it is shown
to somebody who has just been told their email is unrecognised.
**Evidence:** `client-expert/client/src/pages/auth/Start.tsx`; `43-client-intake-funnel.md` header.
**Business impact:** A dead end on the highest-intent page in the product. Even unbuilt, it should
carry a phone number and an email address.
**Decision:** MODIFY now (add contact details — a one-line fix), then ADD (build Unit 43).
**Priority:** **P1** for the copy fix; **P1** for the unit.

---

**F11 · There is no ETA anywhere in the system**
**What:** `SlaCalculator` computes per-stage budgets on a business calendar, and the output is
`ON_TRACK`/`AT_RISK`/`OVERDUE` for staff only. No client-facing or Sales-facing expected date exists.
**Evidence:** `service/SlaCalculator.java`; `PortalStageProjection` has no date field.
**Business impact:** "When will it be ready" is the second most common status question and the
system cannot answer it even internally without a human adding up stages.
**Decision:** ADD — a derived expected-completion date on the case, from remaining stage budgets, on
the client projection and the Sales read. It is arithmetic over data that exists.
**Priority:** **P1**

---

**F12 · No user/team administration screen**
**What:** `TeamMemberController` exposes `GET`, `GET /assignable`, `PUT /{id}/ghl-pipeline`. The
frontend has `/brands` in nav with no component, and no screen anywhere creates a user, sets a role,
sets a team, or assigns a pipeline.
**Evidence:** `frontend/src/App.tsx` `SCREENS` map; frontend inventory.
**Business impact:** Onboarding a new salesperson requires a database write. For a system whose
whole pivot is "six new Sales and Marketing seats", that is a hard ceiling.
**Decision:** ADD — one admin screen, GM/Brand Manager gated.
**Priority:** **P1**

---

**F13 · The ENM is never told a case needs an expert**
**What:** `process-automation.md` §05 lists *"ENM notified when a case needs an expert"* as an
automation. There is no such `NotificationType` and no `ROUTES` entry. Unit 19 also decided
deliberately not to notify the ENM on the 24h sign overdue.
**Evidence:** `domain/NotificationType.java` (10 values, none of them this);
`notification/NotificationListeners.ROUTES`.
**Business impact:** The supply-side role finds out about demand by watching a board that does not
show them cases.
**Decision:** MODIFY — either build the route or delete the claim from the process document. My
recommendation: build it, gated to field-only content.
**Priority:** **P2**

---

**F14 · `expert.notes` is an overwritable text box on a relationship record**
**What:** The one entity where "what did we agree with this person, and when" matters most has a
single mutable `notes` column.
**Evidence:** `domain/Expert.java`.
**Business impact:** Relationship history is destroyed on every edit. Contrast case notes, which are
trigger-protected.
**Decision:** REFACTOR — append-only expert notes on the `audit_event` pattern already used for case
notes (`object_type='EXPERT'`). The table is generic; this is a new `AuditAction` value and a read.
**Priority:** **P2**

---

**F15 · Five stale javadoc references to a dropped signature provider**
**What:** Dropbox Sign was removed in Unit 15. Five javadocs still describe callbacks from it,
including on `CaseController`'s two expert endpoints.
**Evidence:** `Expert.java:47`, `ExpertCaseOffer.java:69`, `CaseLifecycleService.java:760`,
`CaseController.java:683,692`.
**Business impact:** Low individually; corrosive in aggregate. This repo's documentation discipline
is unusually high and this is the visible crack in it.
**Decision:** MODIFY
**Priority:** **P2**

---

**F16 · G13 (client communication log) is filed under the wrong owner**
**What:** Recorded as "GHL's, recorded not planned". Since then EvalOS acquired the sales desk, the
marketing desk, the client portal and SMTP. It is no longer plausible that GHL holds the record of
what the business has told a client.
**Evidence:** Gap register G13; Units 39/40/41/42.
**Business impact:** In a dispute, no single system can answer "what did we tell them".
**Decision:** REDESIGN — G13 is answered by F3's unified timeline, not by a separate log. Re-file it
against that.
**Priority:** **P2**

---

**F17 · The client portal has a front door and nobody has been told**
**What:** Unit 42 shipped accounts on 2026-09-12. There is no evidence of any client having been
invited, and there is no invitation mechanism — the two mail messages are set-password and
reset-password, both reactive.
**Evidence:** `service/ClientMailer.java` — two methods; `V45__seed_client_accounts.sql` seeded
accounts **with no password**.
**Business impact:** The cheapest fix for the status loop is a client who self-serves, and the door
is shut because nobody has the key. A "your account is ready, set your password" send to the seeded
accounts is within the amended invariant 14 (it *is* authentication mail) and would do more for the
status problem this week than any feature.
**Decision:** ADD (an operational send, using the existing `sendSetPassword`)
**Priority:** **P1**

---

## Cut list

**C1 · `expert_outreach` — do not build it.**
Per-call, per-email outreach logging is the classic supply-side feature that is enthusiastically
populated for six weeks and then silently abandoned, after which the pipeline stage is right and the
outreach log is wrong. **The stage move *is* the outreach record.** Unit 22 refused this once
already, on the same reasoning. Build the six-stage pipeline; let the stage transitions and an
append-only note carry the history.

**C2 · The `/marketing/google-ads`, `/marketing/email` and `/sales/pipeline` funnel screens.**
Three GM-only read-only aggregations over GHL pipelines, rendered by one component
(`MarketingPipelinePage.tsx` with a `funnel` prop) and backed by `GhlFunnelCache` (`V25`, `V26`) and
three `evalos.ghl.*-pipeline-name` properties matched **by name**, which is exactly what breaks on a
CRM replacement. The build plan already flags the cut as available: *"That cut is still available,
on its own, if the business wants the funnels retired — it would remove Units 24, 26 and 27's
screens, not just their config."* They answer a question GHL's own dashboards answer, for one role,
over a location that was just replaced. **REMOVE.** It deletes two migrations' worth of cache, three
config properties and a naming-based lookup that will fail silently.

**C3 · `client-expert/expert/src/constants/expertNavigation.ts` and `src/mock/expertMockData.ts`.**
Dead. Nothing imports them. They point at `/expert/payments` and `/expert/profile`, routes that do
not exist. They will be read as a plan by the next person who opens that app. **REMOVE** — and note
that the screens they name should be built, which is F6; deleting the ghost is not deciding against
the feature.

**C4 · `client-expert/shared/src/.../mockDelay.ts`.** No live importer. **REMOVE.**

**C5 · Do not build a separate `client_communication_log` for G13.** It would be a fourth note
system. It is a projection of the unified timeline filtered to outward-facing events. **Do not
build.**

**C6 · Do not build a "status update message" feature for Sales.** The loop is killed by giving
Sales *read access to the truth*, not by giving them a second way to restate it. A composer would
create a second, staler answer to the same question and would require the outbound-channel decision
that F7/Q2 has not taken.

**C7 · Do not normalise the expert capability arrays.** `primary_fields[]`, `letter_types[]`,
`visa_categories[]` are closed enums with database CHECK constraints and a GIN index. A join table
would buy nothing and cost the match engine.

**C8 · Unit 49 (EvalOS invoicing) — do not schedule it.** It reverses invariant 2's surviving half,
needs a finance decision engineering cannot take (`00c` Q(d)), and would re-argue invariant 8 —
"won" stops being something only GHL can tell us. Nothing in the brief requires it. It sits at the
end of a nine-unit programme whose first unit is unspecced. **Defer indefinitely**, and say so, so
that it stops appearing as "the last unit" and starts appearing as a decision nobody has taken.

**C9 · Reconsider the *scope* of Units 44–48 (the full GHL mirror).** `00c` §2c commits to mirroring
*"everything the API exposes"* — four tiers including custom fields, tags, tasks, calendars and
invoices. `00c` §6 already concedes *"sync surface with no consumer is pure drift risk with no
offsetting benefit"*. Tier 1 (pipelines, stages, contacts, opportunities) has consumers today and
earns its cost. **Tiers 2–4 should be cut to what a named screen reads**, and Unit 47 rewritten as
"mirror what 46 needs" rather than as a completeness exercise. This is not a rejection of the
programme; it is a rejection of mirroring for its own sake.

---

## Open questions

Each carries a recommendation, per the house rule.

**Q1 · Does SALES get read access to cases, and how much?**
*Recommendation:* **Yes, and exactly the client's projection.** Serve
`PortalStageProjection.forClient(stage)` + `stage_entered_at` + SLA status + expected completion
date (F11), scoped by `Case.ghl_opportunity_id` ∈ the caller's pipeline. **Never** the draft, the
strategy notes, the expert's identity or the checklist detail. The test is simple and defensible: a
salesperson may see precisely what they are allowed to tell the client, and nothing they would have
to be careful about.

**Q2 · Who owns reaching the client, now that Sales exists?**
*Recommendation:* **Split at Handoff A, and route the prompt accordingly.** Before the case exists,
the client belongs to Sales — including chasing the application and the documents if a funnel
collected them. From case creation, the Coordinator owns operational contact and Sales is copied,
not responsible. Concretely: `DOC_CHASE_DUE` keeps going to the Coordinator, and a *second* recipient
is added only if the case is under 48 hours old. Ambiguity here is currently resolved by nobody
acting.

**Q3 · Does EvalOS send status mail, or does the portal remain the only surface?**
*Recommendation:* **EvalOS sends, for four messages only, and it is a unit with a written invariant
amendment.** T1 (checklist + link), T5 (draft ready), T6 (expert signing link), T8 (delivered). Not
T2/T3 (chases — those become portal state plus a staff prompt), not T7. The reasoning: `ClientMailer`
and `spring-boot-starter-mail` exist; what is unpaid is deliverability, bounces, unsubscribe and a
suppression list. Four transactional messages to people who have bought from you is the cheapest
possible version of that bill, and **T6 alone justifies it** — an expert who never receives a link
cannot sign while a 24h clock runs. `process-automation.md` is explicit that this is a new decision
and a new unit; take it as one.

**Q4 · Do experts get accounts?**
*Recommendation:* **Yes, on the Unit 42 pattern.** The client half is built, proven and cheap to
repeat: one bcrypt column, the existing per-IP limiter, the existing `PortalTokenFilter`, and
`mintForParty` already accepts an expert party. It closes G15, unblocks F6, and is the precondition
for every ENM work-monitoring feature that assumes the expert is reachable.

**Q5 · Is the Expert Network pipeline a unit, or is it GHL's?**
*Recommendation:* **A unit in EvalOS.** Unit 17 marked it "GHL" and Unit 22 refused it, both before
the pivot that put Sales and Marketing pipelines into EvalOS. Supply is this business's binding
constraint; running it in a system where the ENM cannot see it is the same mistake the Sales pivot
just corrected. Reverse both documents in writing first.

**Q6 · What happens to the three GM funnel screens?**
*Recommendation:* **Retire them (C2).** If the business disagrees, they must at minimum stop matching
pipelines by name — that lookup broke on 2026-09-11 and fails as a 502 naming what it could not find.

**Q7 · Where do Unit 43's questionnaire answers live once a case exists?**
*Recommendation:* **Nowhere new.** Unit 43 already files them as an `OpportunityNote` on submit, and
F3's unified timeline makes the case show its opportunity's stream. So the answer to "must Production
see the application" is *yes, and it is free* once F3 ships. Do **not** copy them onto the case — the
client is the only authority on what they answered, and a second copy is a second version of it.

**Q8 · Does the client see who is working on their case?**
*Recommendation:* **No name, but yes a role and a date.** "With your Project Manager, expected back
to you by Tuesday" answers the question the client is actually asking. A named individual creates a
direct channel around the Coordinator and makes holiday cover a client-visible event.

**Q9 · Scope of the mirror (Units 44–48)?**
*Recommendation:* **Tier 1 only, until a screen names tier 2.** See C9. `00c` §6 already makes this
argument against itself; follow it.

**Q10 · Does the GM read the opportunity note stream?**
*Recommendation:* **Yes.** The current exclusion is an implementation consequence (`PipelineScope`
answers "is this *my* pipeline" and a GM owns none) that hardened into a policy. The GM is
`Tier.ALL` and is the oversight role. Widening `PipelineScope` for the ALL tier is the honest fix and
is a prerequisite for F3's timeline being useful to anyone above a desk.

---

## Prioritisation — the critical path in product terms

Ranked by business impact × technical dependency. Everything in P0 is small; that is the point.

```
P0  ── the business is not running, and the loop is unbroken ──────────────────────
  1. F1  Rebuild the opportunity.won workflow in the new GHL account   (operational, hours)
  2. F4  Carry the intake note through Handoff A                        (4 lines)
  3. F3  Unified timeline read model: case ↔ opportunity, projected     (1 service, 1 endpoint)
  4. F2  SALES case-status read over the client projection              (1 read service, 1 screen)

P1  ── the loop's second half, and the doors that are shut ────────────────────────
  5. F17 Send set-password mail to seeded client accounts               (operational)
  6. F8  Two-state empty on invoices + meetings                         (1 component)
  7. F10 Put a phone number on /start                                   (1 line)
  8. F5  Expert accounts (Unit 42 pattern)  ── unblocks F6, closes G15
  9. F6  Expert home: my cases, my payouts                              (2 screens, 0 backend)
 10. F11 Expected completion date, derived                              (arithmetic)
 11. F7/Q3  The four-message outbound decision, in writing, as a unit
 12. F12 Team administration screen
 13. F9  ENM pipeline unit  (spec first — reverses two written refusals)
 14. F10 Unit 43 intake funnel  (restoration from f9f1165^, mostly)

P2  ── hygiene and honesty ────────────────────────────────────────────────────────
 15. F13 ENM demand notification, or delete the claim
 16. F14 Append-only expert notes
 17. F16 Re-file G13 against the unified timeline
 18. F15 Delete the Dropbox Sign javadocs
 19. C2/C3/C4 the deletions
```

**The critical path, in one sentence:** rebuild the webhook, carry the note, join the two
conversations on a key both tables already have, and give Sales and the expert a screen onto the
answer that already exists — after which nine of twelve handoffs still involve a human, but the two
that generate the daily pain do not.

**The single highest value-to-effort item in this audit is F3.** `Case.ghl_opportunity_id` and
`OpportunityNote.ghl_opportunity_id` have been sitting in the same database since 2026-09-11,
unjoined, while the business's top operational complaint is that Sales and Production cannot see
each other's work.
