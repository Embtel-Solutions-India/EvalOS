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
- **D3a.** **A stranger must not be able to drive unbounded writes into the live CRM.**
  `/auth/sign-up` is `permitAll` behind one per-IP counter, so a contact write there is an
  unauthenticated write — an IP-rotating script fills the sub-account Sales works in and spends
  GHL's 100-req/10s location budget, which 502s every GHL-backed staff screen. **This is the
  property; D3d changed how it is held.** From 2026-09-16 morning to 2026-09-16 evening it was held
  by ordering (contact created at `setPassword`); it is now held by the route's own gate plus
  `PORTAL_CLEANUP`. Spec `52` §8.
- **D3d.** **The contact is created at sign-up, because GHL sends the mail.**
  `POST /conversations/messages` requires a `contactId` — there is no contact-less send and a
  `conversationId` is no escape, since a conversation belongs to a contact (verified against
  `/docs/ghl/conversations/send-a-new-message`). So the contact must exist *before* the
  set-password link, not after it, and D3a's ordering could not survive that. What replaces it:
  sign-up gets its **own tighter rate budget** rather than the shared 60/min, a **proof-of-human
  gate**, and `PORTAL_CLEANUP` removing what a flood leaves in EvalOS. One upsert per new account,
  none for an address already held. Spec `52` §10.
- **D3e.** **Mail is a transport behind an interface** (`MailTransport`), chosen by
  `evalos.mail.transport` from the beans on the classpath — `smtp` or `ghl` today, **Brevo is a new
  class and a changed variable**, not an edit to the thing that knows what the messages say. A name
  matching nothing **fails at startup** and names what it found; falling back silently would be
  found by a client who never got their link. `canReach` is asked rather than `isConfigured`,
  because the GHL transport can be perfectly configured and still unable to address a client it has
  no contact for.
- **D3b.** A portal-born contact carries **`source: "Client Portal"`**. GHL's public API accepts no
  attribution on a write — neither `POST /contacts/` nor `/contacts/upsert` documents
  `attributionSource`, `utmSource`, `utmMedium` or `campaign`, and GHL fills those only from its own
  form and funnel tracking. The documented `source` string is the whole of what provenance can be.
  Every caller of `upsertContact` names itself. Spec `52` §8.3.
- **D3c.** A missing `ghl_contact_id` is **backfilled at the moment one is needed**, not refused
  earlier. A GHL outage during set-password does not lock a client out of their own account; it
  defers the contact to the first request, where `ClientApplicationService` creates it instead of
  returning silently. This also covers `V45` accounts seeded from snapshots that carried no id.
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
- **D10.** The opportunity is created **when the client submits the request**, not when they pick
  a service. **Changed 2026-09-16, third time of asking.** It opened at service-pick until then, so that a
  client who abandoned the questionnaire still reached a salesperson; the requirement said submit from the start,
  EvalOS refused it twice, and the third asking carries it. The deal on the board is now a **finished
  request** and nothing else — which is what makes Sales' review step mean something, and is the
  trade taken knowingly: **an abandoned questionnaire now reaches nobody.** Recovering those is a
  separate job (nothing sweeps `DRAFT` applications today) and is in `open-decisions.md`.
- **D10c.** The funnel is **submit → opportunity → Sales review → won → payment**. EvalOS creates
  the deal at submit and stops; review, win and invoicing are GHL's and Sales', exactly as D11
  already said about placement. A case is still born only of `opportunity.won` (D9, invariant 8).
- **D10a.** EvalOS sends GHL the **requested service id** as an opportunity custom field
  (`evalos.ghl.opportunity-service-field`) and nothing else about placement. A GHL workflow routes
  the deal to a pipeline from it. The `SUBMITTED` marker rides on that same create now — under D10
  every opportunity is a submitted one, so a second call to announce it would say nothing.
  Mapping services onto pipelines is a business rule and lives in
  the workflow — the same ruling that deleted `hot-stage-name`. No stage, no assignee, no price.
- **D11.** EvalOS puts the opportunity on the intake pipeline and **stops**. Stage placement and
  assignee are GHL automation's job. There is deliberately no "hot stage" setting.
- **D10b.** The client's request lands on the pipeline a GM marked `INTAKE`, not on one matched by
  name. `evalos.ghl.intake-pipeline-name` is retired (Unit 44b) — a rename in GHL silently stopped
  every request reaching Sales. Zero or two `INTAKE` pipelines are both refusals that name the fix.
- **D11a.** GHL's pipelines and stages are **mirrored** as EvalOS rows using GHL's own ids
  (Unit 44a, `V50`). GHL owns every column except `pipeline.purpose`, which EvalOS owns and a
  sweep never writes. Rows are upserted and **never deleted** — one GHL stops returning is
  stamped `missing_since`, because `purpose` is EvalOS's judgement and a pipeline archived for
  an afternoon must not come back meaning nothing. Nothing infers a purpose from a pipeline's
  name: a GM sets it, or it stays `UNASSIGNED`.
- **D12.** Submit changes `client_application.status` **and is what opens the opportunity** (D10).
  The `SUBMITTED` custom field is written **on that create**, not by a follow-up call — every
  opportunity is now a submitted one, so announcing it separately would say nothing, and
  `setOpportunityFields` is no longer on this path. It still moves no stage, sends no pipeline and
  writes no note. **A GHL failure refuses the submit (502) and leaves the draft intact**, which is
  stricter than the swallow this decision used to describe: under D10 a failed create means Sales
  has no deal at all, and telling a client "sent" for that would be a lie.
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
- **D19b.** A SALES/MARKETING member holds a **set** of GHL pipelines (`team_member_pipeline`,
  Unit 44b), not one. The one-owner rule is retired: Case Delivery is a pipeline nobody owns.
  Assignment is by **mirror id**, never a pasted GHL string — that is `00d` C4 closed structurally.
  The set is read at sign-in and carried in the token, so a reassignment takes effect on next
  sign-in; unchanged in kind from the single-claim model it replaced.
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

## Resolved 2026-09-16

- **D32.** `client_account` and `contact_snapshot` stay **two tables, joined** — not merged.
  `V55` adds `client_account.contact_id`, a real foreign key, backfilled on `ghl_contact_id`
  within the brand and set at sign-up; null stays legal. **D6 is now enforced by the schema**, by
  a partial unique index, where it previously was not — `ghl_contact_id` was nullable and not
  unique, so nothing stopped two accounts naming one contact. *(This closes `open-decisions.md`
  Q5, which recommended the merge.)*
  **The `contact_snapshot` → `contact` rename is deferred and the reason is mechanical:** two
  seeds write that table (`V905` local, `V951` testprod), both numbered 900+, both running after
  every `db/migration` script — and `MigrationTreeTest` forbids a migration in that range while
  editing an applied seed is a checksum mismatch that refuses the boot. A rename has nowhere to
  sit. Do it in the change that rebaselines the seed tree. `contact_snapshot` **is** the mirror's
  contact table until then, and nothing about that is wrong except its name.

