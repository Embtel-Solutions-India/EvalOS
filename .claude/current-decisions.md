# EvalOS — Current Decisions

Confirmed and in force as of **2026-09-16**. Short and explicit. No history, no abandoned
approaches, no proposals. Unresolved items are in `open-decisions.md`.

## Identity

- **D1.** A GHL Contact and an EvalOS ClientAccount are different things. A GHL Contact is CRM
  identity; a ClientAccount is portal identity and access. A contact existing is **not** proof of
  portal registration.
- **D2.** Three identity states are all legal and all supported: (a) no contact + no account =
  new client; (b) contact + no account = GHL lead who never registered; (c) contact + account =
  portal client.
- **D3.** GHL decides whether a contact is new. EvalOS asks via `POST /contacts/upsert` (matches
  on email, then phone) rather than deciding itself.
- **D4.** Signup never signs anyone in. It returns a state; control of the mailbox is proved by
  the set-password link. Signing up with a known email creates nothing and cannot overwrite.
- **D5.** Sign-in creates no contact and no account.
- **D6.** One GHL Contact, many opportunities. A repeat request creates a **new opportunity on the
  existing contact**, never a second contact.
- **D7.** `client_account.email` is unique per brand (case-insensitive). Portal brand is fixed by
  `evalos.portal.client-brand` — the portal is single-brand today.

## The request → case lifecycle

- **D8.** Request ≠ Case. A **Request** (`client_application`) is what the client asked for. A
  **Case** (`evalos_case`) is production work. An **Opportunity** (GHL) is the commercial process
  between them.
- **D9.** A case is created **only** by the `opportunity.won` GHL webhook (Handoff A). No other
  code path may create one; `DomainInvariantsTest` fails the build if a second class injects
  `CaseIntakeService`. (Invariant 8.)
- **D10.** The opportunity is created at the **first screen** of the funnel (service chosen), not
  at submit — a half-finished request is still a lead Sales can ring.
  **Reaffirmed 2026-09-16 against a request to move it to submit.** The business asked for the
  opportunity to be created on submit; that reverses the reason D10 exists, so instead the deal
  still opens at service-pick and **submitting writes a `SUBMITTED` custom field on it**
  (`evalos.ghl.opportunity-submitted-field`). GHL gets the signal, no lead is lost.
- **D10a.** EvalOS sends GHL the **requested service id** as an opportunity custom field
  (`evalos.ghl.opportunity-service-field`) and nothing else about placement. A GHL workflow routes
  the deal to a pipeline from it. Mapping services onto pipelines is a business rule and lives in
  the workflow — the same ruling that deleted `hot-stage-name`. No stage, no assignee, no price.
- **D11.** EvalOS puts the opportunity on the intake pipeline and **stops**. Stage placement and
  assignee are GHL automation's job. There is deliberately no "hot stage" setting.
- **D12.** Submit changes `client_application.status` and **writes the `SUBMITTED` custom field
  on the GHL opportunity** (amended 2026-09-16, see D10). It still moves no stage, sends no
  pipeline and writes no note — the call is `GhlWriteClient.setOpportunityFields`, which carries
  custom fields and nothing else precisely so it cannot undo GHL's own routing. A failure is
  logged and swallowed: the request is submitted in EvalOS either way.
- **D13.** Sales reads the questionnaire through `GET /api/opportunities/{id}/application`, which
  answers `null` + 200 for deals that did not come from the portal.

## GHL relationship

- **D14.** GHL is the CRM, pipeline engine, automation engine and invoicing/QuickBooks
  integration. EvalOS is the interface Sales and Marketing work in.
- **D15.** **Invoicing is GHL's, full stop.** EvalOS reads invoices (Client Portal) and raises none.
- **D16.** EvalOS writes to GHL. `GhlHttp` exposes exactly `get`/`post`/`put`/`delete` — the verb
  list is closed and a fifth fails the build. Every caller of a write verb must reach
  `AuditService`; that too is a build-failing structural test.
- **D17.** `upsertOpportunity` means one open opportunity per contact per pipeline — correct for a
  **marketing lead**, wrong for a genuine second deal. Genuine second deals use
  `createOpportunity`, with duplicate risk managed one layer up.
- **D18.** The target is an **id-faithful mirror** of GHL (same pipeline/stage/contact/opportunity
  ids both sides), synced both ways, that keeps working when sync is off. Units 44–48
  (`context/specs/00c-ghl-independence-programme.md`). EvalOS mints its own primary key and keeps
  `ghl_id` beside it, nullable.

## Access

- **D19.** Brand-scoped by default. Every scoped query filters by `brand_id`. One stated exception:
  GHL reads go to the single `evalos.ghl.location-id` sub-account, which belongs to no brand —
  those routes are **GM-only** (`GET /api/metrics/gm`; `/api/opportunities/board` is the narrowed
  case, see D19a).
  **2026-09-16 — the exception cost two screens rather than being widened.** `/marketing/email`
  and `/sales/pipeline` rendered a GHL funnel stage by stage and were GM-only under this rule, so
  the audience for a marketing funnel could not open one. The business removed both screens, their
  controller, service, cache table and `countIn`. A funnel screen comes back only after Unit 25
  puts the location on `brand`, at which point it is brand-scoped and Marketing can be let in.
- **D19a.** `/api/opportunities/board` is the one narrowed case: `evalos.ghl.sales-brand` names the
  brand that owns the location and assignment refuses any other brand's member with a 400, so that
  screen's brand *is* provable and SALES/MARKETING reach it.
- **D20.** Eight staff roles with ABAC tiers: `GM`(ALL), `BRAND_MANAGER`(BRAND),
  `PROJECT_MANAGER`(TEAM), `PROJECT_COORDINATOR`/`CASE_MANAGER`(SELF),
  `EXPERT_NETWORK_MANAGER`(SUPPLY), `SALES`/`MARKETING`(PIPELINE).
- **D21.** Sales and Marketing are roles; attorney/employer/individual are `team_member.segment`,
  not roles — identical permissions.
- **D22.** Two Spring Security chains: `/api/portal/**` on an opaque portal token (order 1),
  everything else on staff JWT (order 2).
- **D23.** Clients authenticate with email + password. **Experts do not have accounts** — an expert
  reaches the portal only through a staff-minted link.

## Data

- **D24.** Append-only truth: `audit_event` and `opportunity_note` carry database triggers that
  raise on UPDATE and DELETE.
- **D25.** Every state transition writes an audit row (invariant 13). Failed client sign-ins too.
- **D26.** Schema changes ship as new Flyway migrations. An applied migration is never edited.
- **D27.** EvalOS hosts no files — S3 holds them, presigned reads expire in 5 minutes and are
  never stored.
- **D28.** Expert `payment_detail` is encrypted at rest and appears in no DTO.

## Mail

- **D29.** EvalOS sends **exactly two** messages, both authentication: set-password and
  reset-password. Any other mail is a new decision. (Invariant 14, amended 2026-09-11.)

## Other

- **D30.** No AI in the system at all; no AI makes a production decision.
- **D31.** Controllers stay thin. Long-lived work is a `@Scheduled` sweep with a DB lock and a
  `scheduled_job` ledger row.
