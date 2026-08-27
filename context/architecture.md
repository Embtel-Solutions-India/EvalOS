# EvalOS — Architecture Context

## Stack

| Layer            | Technology                                        | Role                                                                              |
| ---------------- | ------------------------------------------------- | --------------------------------------------------------------------------------- |
| API / runtime    | Java 21 + Spring Boot (Spring Web MVC) + Maven     | REST API, webhook receivers, service orchestration                                |
| Database         | PostgreSQL + Spring Data JPA (Hibernate)           | System of record: brands, cases, experts, payout ledger, contact snapshots, audit |
| Migrations       | Flyway                                             | Versioned schema — every change is a new migration, never an edited one           |
| Internal auth    | Spring Security + JWT + role authorities (RBAC/ABAC) | Staff login; per-role + brand/team/assignee authorization (optional SSO later)   |
| Portal auth      | Separate Spring Security filter chains (scoped, link-based) | Expert portal and client draft-review portal — isolated from internal auth |
| Frontend         | React + TypeScript (Vite SPA) + Tailwind, with `radix-ui`, `lucide-react`, `recharts` | Internal role-based dashboards, client portal, expert portal. The three UI packages landed in Unit 22 slice 1, each against a screen that needed it; dnd-kit, TanStack Table and Motion stay deferred with written triggers in that spec |
| Raw documents    | Google Drive (existing) + **Drive API v3 (outbound, Unit 13)** | Client document folders — link stored on the case, not re-hosted. **Since Unit 13 EvalOS also writes one file into the case's folder**: the redacted expert profile, uploaded as a Google Doc |
| E-signature      | **None — no provider.** The expert signs in their own tool and uploads the signed PDF through their portal | A scanned wet signature is the norm for an expert opinion letter. Provenance is a hash pair + an attestation + an `EXPERT` audit row, not a certificate — see `15-expert-portal-handoff-b.md` |
| Notifications    | In-app notification center (staff) + GHL (clients) + a portal link (experts) | No EvalOS mail server                                            |
| Background work  | Spring `@Scheduled` (+ app events) + a `scheduled_job` run ledger + a Postgres advisory lock per sweep | SLA timers, reminders, escalations, expert-sign prompts, the outbound outbox. **No Quartz, no ShedLock, no broker** |
| Queue            | The `webhook_delivery` outbox table, claimed `FOR UPDATE SKIP LOCKED` | Outbound delivery with backoff + dead-letter. The only cross-process work is "deliver one webhook and keep trying", which a durable row does |
| Integration seam | Inbound webhook gateway + outbound webhook dispatcher (+ GHL and Google Drive clients) | Receive GHL events; emit EvalOS lifecycle events to subscribers. **One inbound source, GHL** — dropping the signature provider removed the second |
| GHL read API | `RestClient` against GHL's public API, `opportunities.readonly` (**inbound *pull*, Unit 24**) | The GM's marketing funnel view, and nothing else. **Read-only, and no write method**: two calls and a cached payload. The *aggregate* the screen draws is cached in `ghl_funnel_cache` (it was a heap map until 2026-08-26 — a per-process cache lost a completed background total on restart and could not hand one instance's result to another). **No opportunity rows are stored**: there is no `ghl_opportunity` table and there must not be one, because a stage dragged five seconds ago would already be wrong in it. The table is a cache, not a record — safe to truncate, and not brand-scoped because the figures come from one global GHL location EvalOS cannot attribute to a brand. This is the third direction across the GHL seam — events in, events out, and now one pull — and it is the only one that is not a handoff |

**No object storage.** EvalOS hosts no files. Client documents and drafts are
referenced by Google Drive link; the signed letter is filed into the case's own Drive
folder by the expert's upload; the
redacted CV is generated on demand (and, if persisted, written to the case's
Drive folder — never to a database blob).

**Accepting a file is not hosting one.** Unit 21 takes client uploads on the portal
and **streams them through** to the case's Drive folder — `InputStreamContent`, not a
byte array, so a large file never lands on the heap either. EvalOS keeps the Drive
file id and nothing else. There is no upload directory, no temp file and no blob
column, and Unit 21 asserts that rather than assuming it.

**Drive stopped being a link-only external in Unit 13.** Until then
`Case.driveLink` was a string EvalOS stored and never dereferenced, and this
table said so. It is now an **outbound integration**: `integration/GoogleDriveClient`
uploads the generated redacted profile into the folder that link names, with a
target mime type of `application/vnd.google-apps.document` so Drive converts it
to a Doc on the way in. This is deliberately the narrowest possible capability —
**one file into a folder that already exists**, no folder creation, no permissions
management, no reading documents back out. It is also why EvalOS has no PDF
library: Drive's own export produces a PDF from the created Doc.

Two consequences worth stating where the stack is described. First, this is the
**one external dependency in Phase 2** — and, since the signature provider was
dropped, the **only** one: Units 13, 15 and 21 all need this same service account, and
nothing else in the phase needs a credential. It
needs credentials that are provisioned rather than coded — a Google Cloud service
account, its JSON key (`GOOGLE_DRIVE_KEY_JSON` or `GOOGLE_APPLICATION_CREDENTIALS`,
bound the same env-backed way as `EVALOS_FIELD_KEY`, with **no default outside
`local`**, so an environment that forgets it fails to start). Second, the write
access behind that key **must be granted per brand folder tree**: one service
account with blanket access to both brands' Drives is a cross-brand hole *outside
the database*, which no `brand_id` predicate can close. EvalOS holds the half it
can — it writes only into the folder the case's own `drive_link` names, and an
unparseable link is a refusal, never a fallback to a default folder.

Unit 11 added the one **upload** in EvalOS, and it does not change that: the
expert roster sheet is parsed in memory and thrown away — no row, column or temp
file keeps it. `spring.servlet.multipart.file-size-threshold` is set equal to
`max-file-size` for exactly that reason, since above the threshold the container
spools the part to a temp file. Two parsers sit behind it (`commons-csv` for
`.csv`, `poi-ooxml` for `.xlsx`); only `service/ExpertImportService` touches
either.

Repository layout is a monorepo: `backend/` (Spring Boot) and `frontend/`
(React + Vite). Java lives under the base package `com.ie.evalos`.

## Multi-Tenancy (brands)

- **Shared database, row-level tenancy.** Every scoped entity carries a
  mandatory `brand_id`. Brands are tenants of one system (International
  Evaluations, XpertsPortal, and any future brand).
- **Query-layer scoping.** A scoping mechanism (JPA Specifications / Hibernate
  filters) injects `brand + team + assignee` predicates into every scoped query
  at the repository layer. Access is enforced in data access, never only hidden
  in the UI. A query written without brand scoping is a defect.
- **Brand resolution at Handoff A.** Each brand is a separate GHL sub-account and
  gets its **own inbound webhook endpoint with a secret token**; the endpoint
  determines `brand_id` even when GHL's payload omits it. The brand ↔ endpoint
  mapping is 1:1.
- **The GM is the only cross-brand role.** Everyone else is hard-locked to their
  brand (Brand Manager) or narrower (team/self).
- **One exception to "no request field names a brand", and it is not a scope.**
  Unit 11 is the first unit where staff *create* a scoped row (every case comes
  from a webhook), and a GM has no brand of their own to create it in. So
  `POST /api/experts` and the two import endpoints accept an optional `brandId`,
  which names where the new row lives — it never widens a read. It is not
  trusted either: `OwnershipGuard.assertCanAct` decides whether the caller may
  act in the brand named, so only the cross-brand role can name anything and a
  Brand Manager naming another brand gets a 403. Reads still take brand from the
  principal alone, and a `brandId` on a read can only ever narrow.

## System Boundaries

Java packages under `com.ie.evalos`:

- `web` — REST controllers. Thin: validate, authorize, call a service, return a
  DTO. No business logic. Controllers accept/return DTOs, never JPA entities.
- `service` — business logic: case lifecycle/state machine, expert matching,
  payout creation, QC, brand-scoped queries. All rules and `@Transactional`
  boundaries live here.
- `domain` — JPA entities (and enums like `Stage`, `PayoutStatus`, `Role`).
  Mapping and invariants only, no business orchestration.
- `repository` — Spring Data JPA repositories; brand/team/assignee scoping filters.
- `integration` — outbound clients for the GHL API and **Google
  Drive** (`GoogleDriveClient`, Unit 13 — the first one built). Each is one narrow
  capability, not a general SDK wrapper: the Drive client uploads one file into one
  existing folder and does nothing else. A failure here is a 502 that changes
  nothing in EvalOS, never a partially-applied state.
- `webhook` — inbound webhook gateway: brand resolution from the per-brand
  endpoint token, idempotency, raw-payload archival, routing to a domain
  service. **No signature verification** — see the inbound gateway below.
  **One source: GHL** (per-brand endpoints). There was going to be a second
  when a signature provider posted callbacks; dropping the provider removed it, and
  with it the only place in the design that threatened the protected
  brand-resolution step.
- `event` — internal domain events (Spring `ApplicationEvent`) published on
  lifecycle transitions, plus the outbound webhook dispatcher (subscriber
  registry, HMAC signing, retry/backoff, dead-letter, delivery log, replay).
- `job` — `@Scheduled` sweeps backed by the `scheduled_job` **run ledger**
  (doc chases, day-3 escalation, stage SLA, expert sign 20h/24h prompts, and the
  outbox sender). Each sweep takes a **Postgres advisory lock on its job type**, so
  the seconds of overlap in every rolling deploy cannot double-chase a client. The
  ledger records *runs, not intentions*: idempotency comes from the data the sweep
  reads, never from a queued timer row. Sweeps **prompt and publish; they never
  transition a case.** Retention is not here — GHL owns it. See
  `context/specs/19-background-jobs.md`.
- `notification` — in-app staff notification center (create/list/mark-read);
  client-facing messages are emitted as domain events for GHL to deliver.
- `security` — Spring Security config, JWT, RBAC roles + ABAC scoping, ownership
  checks; the separate expert-portal and client-portal filter chains.
- `config` — application configuration and beans.
- `common` — shared utilities including the field-level encryption converter for
  the optional expert `payment_detail`, error types, and the response envelope.

Frontend under `frontend/src`: `components/ui` (generated primitives),
`features` (board, case detail, dashboards, client portal, expert portal),
`lib` (API client, hooks).

## Storage Model

- **PostgreSQL (system of record)**: brands, brand-scoped case records, stage +
  per-stage timestamps, document-checklist state, expert profiles, payout ledger
  entries **and the payments that settle them**, read-only contact snapshots synced
  from GHL, in-app notifications, and
  the append-only audit trail. Relational integrity via foreign keys; JSONB only
  for genuinely schemaless blobs (e.g. raw webhook payload archive).
- **Money owed and money sent are two tables, because they are two facts** (Unit 16b).
  A `payout_ledger` row is one delivered draft an expert is owed for; a
  `payout_payment` row is one transfer that actually left, covering however many
  drafts it covered, and the rows point at it. The expert charges per draft and is
  paid weekly, so the two counts do not match and a single table would have to lie
  about one of them. A payment's `amount` is exactly the sum of the rows it settles,
  enforced on write — anything else is a ledger that disagrees with the bank silently.
- **There is no `case_note` table, and case notes are not a gap** (Unit 23). A note
  anybody on the case writes is an **audit row** — `NOTE_ADDED`, with the text in the
  snapshot's `note`, exactly as a hold reason or a decline reason already travels. The
  trail is already append-only, already brand-scoped, already resolves actor names and
  already interleaves with the transitions a note is usually about; a second store beside
  it would have to re-earn all four and then be merged on read. The cost is stated rather
  than hidden: **a note can never be edited or withdrawn.** That is invariant 13 working,
  not a limitation to design around. `pm_strategy_notes` stays a column and stays separate
  — it is the PM's private working note and is role-restricted; a case note is the
  opposite, readable by everyone the case scope admits.
- **Google Drive (existing, external)**: raw client document folders and drafts.
  EvalOS stores **two separate links** on the case and they are never
  interchangeable: `drive_link` is the client's own document folder (passports,
  transcripts) and `draft_link` (Unit 14) is the drafted letter. Only the second
  is ever shown to a client — the first is a folder whose contents and sharing
  EvalOS does not control, so presenting it as "your draft" would be a leak, and a
  case with no `draft_link` is told "not ready" rather than given a fallback.
  EvalOS does not re-host documents. Since
  Unit 13 it also **writes** one generated document into that folder (see the
  outbound-integration note under the stack table) — writing into a folder is not
  re-hosting: the file lives in Drive, and EvalOS keeps only the audit row.
- **No e-signature provider.** The expert signs in whatever tool they already use and
  uploads the signed PDF; it is filed in Drive like every other document. EvalOS keeps
  the file id, a hash of what it sent and of what came back, and the expert's
  attestation.
- **Redacted CV**: generated on demand from the expert profile; not persisted to
  any EvalOS-hosted store. Held in memory only — streamed to the caller, or handed
  to Drive — and never written to Postgres or to disk.
- **Encrypted at rest (field-level)**: the single optional expert
  `payment_detail` field, via a JPA `AttributeConverter`. Never logged, never
  placed in a DTO, webhook payload, or chat tool. (Payouts are manual and no
  bank/card processing occurs, so this is the only sensitive field.)

## Auth and Access Model

- **Internal staff** authenticate via Spring Security with a JWT (optional SSO
  later). Roles map to authorities: `GM`, `BRAND_MANAGER`, `PROJECT_MANAGER`,
  `PROJECT_COORDINATOR`, `CASE_MANAGER`, `EXPERT_NETWORK_MANAGER`. Endpoint
  access is guarded with method security (`@PreAuthorize`); row access is scoped
  by `brand + team + assignee` in the service/repository layer.
- **Scope tiers (ABAC).** All (GM) · Brand (Brand Manager) · Team (PM) · Self
  (Coordinator, Case Manager). The ENM is a supply-side axis: expert/roster data
  yes; client identity/case content no. *(**Enforced in code as of 2026-08-25, not
  merely stated.** `Tier.SUPPLY` reads its whole brand at the **row** level — the ENM's
  three signing transitions must load the case — and the axis is a **field**
  projection: `CaseController.seesCaseContent` withholds `clientName`, `driveLink` and
  `draftLink` from that tier on both the board and the detail payload. Before this the
  tier added no predicate, was referenced nowhere, and every case payload carried the
  client through it.)* *(Self-tier scoping needs a column that
  names the caller, and both now exist: `V17` added `evalos_case.assigned_coordinator`
  beside `assigned_cm`, and `ScopePredicate.Fields` takes a **set** of assignment
  attributes — a Self caller matches when any of them names them. One case is one
  pipeline and the people working it hold different slots. This supersedes the note
  that a Coordinator's case scope was not yet expressible; it was the gap that left
  their board empty and answered 403 on cases they owned.)*
- **Clients** access the draft-review portal via a passwordless link delivered
  through GHL (a separate, scoped filter chain). They see only their own case's
  draft, and can approve or request revisions.
- **Experts** access an assigned case via a scoped portal link shared by the Case
  Manager (a separate filter chain), download the letter, and upload it back signed.
  One token names one case, so an expert can only ever see the case that link is for.
- **The portal token model** (built in Unit 14, one table for both portals). A
  `portal_access` row names one case and one audience (`CLIENT` / `EXPERT`); the
  token is 256 bits from `SecureRandom`, returned **once** at mint time and stored
  only as a SHA-256 hash, so a database read yields no working link. Expiry is
  absolute (30 days, configurable) and re-minting retires the previous token
  inside the same transaction, so a support request cannot widen the number of
  live credentials — with **at most one unrevoked token per case and audience
  enforced by a partial unique index** (`V23`), because a lookup-then-insert is a
  check-then-act two concurrent mints could both win. Unknown, expired and revoked are one indistinguishable 401,
  and the chain is rate-limited. The token travels in an `X-Portal-Token` header —
  never a query parameter, which would land in access logs and `Referer` headers.
- **A portal caller is not a narrow staff caller.** `PortalPrincipal`
  (`portalAccessId`, `brandId`, `caseId`, `audience`) is deliberately *not* a
  `TenantContext`: the token **is** the scope, so no predicate is built and
  nothing can fail open, and `ScopePredicate` is not involved. A synthetic tenant
  context would put a non-staff caller into the staff scoping path, where a later
  widening of a role tier would silently widen what a client can read. The two
  chains are fully separate in both directions: no JWT is accepted on
  `/api/portal/**`, and no portal token is accepted on a staff route.
- **What the portal returns is a whitelist**, not a narrowed staff DTO
  (`PortalCaseService`). The client sees their name, the service, the case
  reference, the draft link, the version, the approval state and the redacted
  expert profile — and none of `deal_value`, `pm_strategy_notes`, the expert's
  identity, `invoice_ref`, `campaign_attribution`, any assignment field, the audit
  timeline, the checklist, or `drive_link`.
- Role, brand, and ownership checks run before any mutation.

## Custody: GHL owns pipelines, EvalOS owns what is real

The front/back seam generalises, and stating it once settles a class of questions:

> **GHL owns every pipeline until the thing at the end of it becomes real. EvalOS
> takes custody at that moment.**

| Pipeline | GHL owns | EvalOS takes custody at |
|---|---|---|
| Client sale | lead → opportunity → invoice → collection | `opportunity.won` → the case exists, paid |
| Expert recruitment | prospect → outreach → agreement | the ENM adding the expert to the roster |
| Retention & reviews | the 7-day review request, the 30/90/180/365 sequence | nothing — EvalOS emits `case.delivered` and schedules none of it |
| Google Ads funnel | the whole thing — lead → warm → hot → won/cold/lost | **nothing. Unit 24 *reads* it and takes custody of none of it** |
| Email marketing funnel | the whole thing, campaigns included | **nothing. Unit 26 *reads* it on the same terms** |
| Sales pipeline (*Aditya's pipeline*) | the whole thing — meetings, quotes, invoices sent, refunds | **nothing. Unit 27 *reads* it on the same terms** |

Those last three rows are the exception the table needed, and the distinction they draw
is the one to keep: **custody and visibility are different things.** Unit 27's row is
where that is easiest to misread — it is a *sales* pipeline, and reading one is no more
selling than reading a campaign funnel is running marketing. Nine stages including
`Invoice sent` and `Refund` are read and none is acted on: invoicing is GHL's, and a
refund is a payment fact that reaches EvalOS through the payment record if it reaches it
at all. Unit 24 puts GHL's Google
Ads funnel on a GM screen — counts, values and sources per stage — while GHL stays the
system of record for every row in it. Nothing is copied here (no `ghl_opportunity`
table, by decision: a stage a salesperson dragged five seconds ago is already wrong in
a copy), nothing is written back, and no case exists until `opportunity.won` fires as
it always did. Invariant 2 is about *running* marketing, and reading a funnel is not
running one.

**Each brand has its own GHL sub-account** — confirmed when the first Private
Integration Token arrived, for International Evaluations. So a funnel read is one
brand's, and Handoff A's per-brand endpoint token was already built on the same shape.
Unit 24 predates that knowledge and reads a single globally-configured location; Unit 25
moves it onto `brand`.

This is why there is **no recruitment pipeline in EvalOS** (decision, Production
Process v2.0): a prospect moving through Identified → Contacted → Agreement Sent is
structurally the same object as a sales opportunity, and GHL already runs pipelines,
sequences and response-rate reporting. Building a second one here would be a second
implementation of a thing the business already owns.

The residue, recorded so it is not mistaken for an oversight: `expert.agreement_status`
has no writer, and is now understood as **GHL's fact**. If it ever needs to be live in
EvalOS, the shape is an inbound `expert.agreement_signed` event through the existing
gateway — mirroring `opportunity.won` exactly. Not specced, not built.

What stays EvalOS's on the supply side is everything about experts who already exist:
roster, availability, coverage gaps, match scoring, offers, payouts, performance.

**And on the production side, the door is opened by the Project Manager** (Unit 23). A case
arrives from Handoff A paid and in the pool — `PoolStatus.IN_POOL`, no team — and it surfaces in
the **PM inbox**, where the PM takes it (`assign-pm`, now on their gate) and then staffs the
coordinator and the case manager. The GM has no pool lane and no inbox: they hold every backend
gate through `GM_OR` — bar the two named below — and can unblock almost anything from the board or
the case, but the queue is worked by the person whose job it is.

**Draft review is the one production decision the GM cannot make** (Unit 23a). `draft/pm-approve`
and `draft/pm-return` drop `GM_OR` outright: approving a Case Manager's draft is the judgement of
the Project Manager who assigned it and who answers for what reaches the client. A superuser path
*around* the reviewer is not oversight — it is a second reviewer with none of the context, and it
makes "who approved this" ambiguous on the one artefact the business is paid for. The GM's lever is
reassigning the PM, not overriding them. This is the only place the GM is *excluded* rather than
added; the two refund rulings are the opposite exception, GM-**only**.

This is why `ScopePredicate` lets a `TEAM`-tier caller see rows with a **null team** when the axis
declares `unteamedVisible` — on a case an absent team means *unclaimed*, and the role expected to
claim it has to be able to read it. It is set on cases and nowhere else; the brand predicate is
unconditional either way, so it widens a tier inside one brand and never across brands.

## The Three Handoffs (the front/back seam)

- **Handoff A — GHL → EvalOS (trigger: opportunity marked Won).** *Case Creation
  v2.0 — see `context/specs/05b-opportunity-won-intake.md`.* GHL owns the whole
  sale: lead, opportunity, invoice, collection. When the opportunity is marked
  **Won**, a GHL workflow fires a webhook to that brand's dedicated endpoint, and
  that one event is both the reason the case exists **and** the proof it was paid
  — the money is in before EvalOS hears anything. The `webhook` gateway verifies
  the secret, resolves the brand from the endpoint token, deduplicates on the
  source event id (idempotency), archives the raw payload, then the case service
  creates-or-refreshes a brand-tagged case at `DOC_COLLECTION` in the brand pool,
  already **paid**, syncs the contact snapshot from the opportunity's contact,
  opens the document checklist, and notifies the **PM/Coordinator pool**. EvalOS
  does **not** talk to the payment processor — it takes GHL's word for it.

  Payment is recorded at creation (`paid` / `paid_at`, with `deal_value` and
  `ghl_opportunity_id` carried in from the opportunity). **No staff action sets
  it**: there is no `mark-paid` transition and no endpoint, because a second way
  to say "paid" is a second thing that can disagree with GHL. Two things still
  depend on the flag — **no case reaches an expert unpaid** (the guard is on the
  `DOC_COLLECTION → EXPERT_ASSIGNMENT` transition, which every later stage is
  only reachable through), and **no unpaid case counts as earned revenue**. Every
  case is born paid, so that guard is normally satisfied on arrival; what still
  moves the flag's *meaning* is a GM-approved refund, which makes a paid case
  not-earned again — by closing it with `REFUND_REQUESTED` standing, **not** by
  clearing `paid`.

  **The amount stays correctable, through GHL rather than by hand.** `paid` /
  `paid_at` are write-once, but `deal_value` is not: a re-delivered
  `opportunity.won` overwrites it, because GHL is now the source of truth for the
  figure and deleting `mark-paid` removed the only other writer. One value, never a
  running total, so a correction cannot double-count.

  A case therefore never exists before the money does. EvalOS no longer sees a
  lead and no longer collects documents ahead of payment — that window was
  deliberate in v1 and is deliberately closed in v2.0, because leads are GHL's
  business.

  One open case per contact per service: a repeat delivery refreshes the case
  that contact already has open, never resetting its stage, assignment, or
  `paid`. A contact buying a second service opens a second case; one returning
  after the first case closed opens a new one. Enforced by a **partial unique
  index** (`V15`), not by the lookup — a lookup followed by an insert is a
  check-then-act that two concurrent deliveries can both win.
- **Handoff B — internal (trigger: client approves draft).** The case moves to
  `EXPERT_SIGNING` and appears in the expert portal with draft + evidence + goal.
  The expert **downloads the letter, signs it in their own tool, and uploads the
  signed PDF back**, which files it into the case's Drive folder and moves the case to
  the PM for final QC. There is no signature provider: provenance is a hash of what
  was sent and what came back, an attestation captured at upload, and an audit row
  with `actor_type = 'EXPERT'`. Exception paths: request-evidence
  opens a client task; decline returns the case to `EXPERT_DECLINED_REMATCHING`
  with the reason logged and the match engine proposing the next expert.
- **Handoff C — EvalOS → GHL (trigger: delivered).** On QC-complete delivery,
  EvalOS emits a signed outbound `case.delivered` webhook to its subscribers
  (GHL first; its inbound automation URL starts the review + referral track and
  stamps the closed value). The payout ledger entry (Pending) is created in the
  same transaction. Delivered/active contacts are synced to GHL's global
  suppression list so no cold/bulk campaign ever emails a current client.

## Case State Machine (EvalOS-owned stages 3–7)

Canonical 8-stage business pipeline; GHL owns stages 1 (Marketing), 2 (Sales),
and 8 (Retention). EvalOS's internal `Stage` enum covers stages 3–7:

```
DOC_COLLECTION → EXPERT_ASSIGNMENT → DRAFT_GENERATION → EXPERT_SIGNING → FINAL_DELIVERY → CLOSED
```

Exception states reachable from any active stage: `ON_HOLD_AWAITING_CLIENT`,
`EXPERT_DECLINED_REMATCHING` (→ EXPERT_ASSIGNMENT), `REFUND_REQUESTED`. The
draft/PM-review/client-review loops live *within* `DRAFT_GENERATION`, tracked by
`pm_approval_status`, `client_approval_status`, and `draft_version_count`. Only
declared transitions are allowed; each writes an audit entry. SLA math runs on
`America/Los_Angeles` (9–5 PT, US federal holidays); timestamps stored UTC.

**Refund handling:** `REFUND_REQUESTED` is approvable by **GM only**. On
approval: the case moves to `REFUND_REQUESTED`; revenue-recognition is reversed
(the case drops out of "Delivered" on dashboards); any Pending payout for the
case is voided/blocked; a refund signal fires to GHL. Audit-logged like any
transition.

## Webhooks (inbound & outbound)

EvalOS has a webhook subsystem with two independent halves. Neither carries
business logic in the transport layer; both are idempotent and observable.

### Inbound gateway (`webhook` package)
1. **Resolve brand** from the per-brand endpoint token in the path (Handoff A).
   That token *is* the authentication: unguessable, per-brand, and only valid
   while the brand is active. An unknown token and an inactive brand's real token
   are the same `404`, so a caller learns nothing from either.

   **There is no inbound signature check, and that is deliberate.** GHL's Custom
   Webhook action posts a URL and a JSON body and cannot compute an HMAC, so
   requiring one meant Handoff A could not be configured from GHL at all. The
   endpoint token carries the whole burden; rotating it revokes the endpoint.
   The *outbound* half below is unaffected and stays HMAC-signed — EvalOS can
   sign what it sends.
3. **Deduplicate** on the source event id (idempotency), scoped by brand; a
   replayed event never produces a second side effect. "Already seen" is not
   "already done" — only a *processed* row is a duplicate, so a redelivery after
   a handler failure retries instead of being swallowed.
4. **Archive** the raw payload (JSONB) for audit and replay.
5. **Route** to the matching handler, which calls a domain service.
6. **Acknowledge** fast; slow work is handed to a `job`. Failures return a
   retriable status so the source re-delivers.

Inbound sources and events:
- **GHL** — `opportunity.won` (Handoff A, per-brand endpoint) is the **only** event
  that creates a case; `refund.requested` → Refund Requested exception state
  (GM-only approval to finalize); `contact.updated` → refresh the read-only contact
  snapshot. (Refund/contact-update events are recognized by the gateway; build them
  when the payloads are confirmed.) **`contact.created` is a recognized no-op** —
  since v2.0 a new contact is a lead, and a lead is GHL's business; neither it nor
  `contact.updated` may route to intake, because an interest or an edit in GHL is
  not a reason to open a case.
There is deliberately **no second inbound source.** A signature provider would have
been one; the expert now acts through their own portal token, so sign-off status comes
from an authenticated request rather than a callback.

### Outbound dispatcher (`event` + `webhook.outbound`)
EvalOS publishes internal **domain events** on every lifecycle transition. The
dispatcher subscribes and delivers to registered external subscribers:
- **Signed** payloads (HMAC over body + timestamp).
- **Retry with backoff**, a **dead-letter** after N attempts, and a **delivery
  log** with **replay**.
- A **subscriber registry** (URL + secret + subscribed event types); GHL is the
  first subscriber. Client-facing messages are delivered by GHL off these events
  (no EvalOS mail server).

Outbound event catalog (initial): `case.created`, `documents.completed`,
`expert.assigned`, `draft.client_approved`, `expert.signed`, `case.delivered`,
`payout.created`, `case.closed`, plus client-notification triggers
(`checklist.requested`, `draft.ready_for_client`, `case.delivered_to_client`).
Payloads carry brand/case/contact/attribution refs only — **never** the
`payment_detail` field or internal notes.

Unit 04 publishes one event per declared transition, so the lifecycle set is
complete rather than illustrative — `case.pm_assigned`, `documents.completed`,
`expert.assigned`, `draft.submitted`, `draft.returned`, `draft.pm_approved`,
`draft.ready_for_client`, `draft.revision_requested`, `draft.client_approved`,
`expert.signed`, `expert.declined`, `qc.approved`, `case.delivered`,
`case.closed`, `case.on_hold`, `case.resumed`, `case.refund_requested`,
`case.refunded`, `case.refund_denied`. The four not in the list above
(`case.pm_assigned`, `expert.declined`, `case.resumed`, `case.refund_denied`)
exist because every transition owes exactly one event. They live in
`event/CaseEvents.Type`, which is where a new type is added.

## Non-Functional Targets (v1)

- Scale: 50–100 cases per brand per month. No microservices, message broker, or
  sharding — a single Spring Boot app + one Postgres.
- Availability ~99%, single region; nightly DB backups (RPO ~24h).
- Document retention is handled in Drive, not by EvalOS.

## Invariants

1. **Brand isolation.** Every scoped query filters by `brand_id`; no code path
   returns another brand's data. The GM is the only cross-brand role.
   **One stated exception, and it is not a query over EvalOS rows**: the GHL pipeline
   reads (Units 24, 26 and 27) go to the one GHL sub-account named by
   `evalos.ghl.location-id`, a *global* setting with no link to a brand — so no
   `brand_id` predicate exists that could narrow them. **GM-only and no `brandId`**
   follow from that.

   **The exception is per-*location*, not per-screen, and Unit 27 is where that is
   easiest to lose.** Its screen sits under a `Sales` nav heading rather than
   Marketing, which invites the reading that the marketing exception does not reach
   it. It is the same `location-id`, so it is the same unattributable brand and the
   same door. A fourth screen over this location inherits all of it; the nav test
   asserts every such path is GM-only in one loop, so adding one without its gate
   fails the build.

   **The premise was corrected once the first real credential arrived, and the
   correction matters.** This paragraph used to say the brands *share* one GHL
   sub-account, making the figure a cross-brand roll-up. They do not: each brand has
   its own sub-account, so the configured location is *one* brand's funnel and EvalOS
   cannot tell whose. The exception therefore rests on the figure being
   **unattributable**, not on it spanning brands — and the Brand Manager is excluded
   because a single-brand role must not be shown a number that might be another
   brand's. **Unit 25 puts the location on `brand`, which closes this exception**;
   Unit 25a then re-scopes all three screens together.
   **Read the invariant as: every query over EvalOS rows.** An unscoped query over
   EvalOS rows is still a defect, and this exception licenses nothing about them.
2. A case is in exactly one system's custody at any moment. EvalOS never runs
   marketing, sales, nurture/cold email, ad attribution, or invoicing.
   **"Runs" is the operative word, and Units 24, 26 and 27 test it.** EvalOS may *read*
   GHL's campaign funnels *and its sales pipeline* onto a GM screen; it may not create a
   lead, move a stage, price a deal, send a campaign, or write anything back. Unit 27 is
   the sharpest test of the word, because it reads stages named `Invoice sent` and
   `Refund` and acts on neither — invoicing is GHL's, full stop. The GHL credential is
   `opportunities.readonly` and the client has no write method — read-only by grant as
   well as by code, so a mistake in either place is still not a write. The day something
   here writes to a GHL pipeline, two systems own it and this invariant is gone.
3. Role, brand, and ownership are enforced before every mutation. Case Managers,
   clients, and experts never see data outside their assignment.
4. The optional expert `payment_detail` is encrypted at rest and never appears in
   logs, webhook payloads, DTOs, chat tools, or any response body. Unit 11 gave
   it its only write path (`PUT /api/experts/{id}/payment-detail`) and
   deliberately **no read path at all** — not for the ENM who typed it. Screens
   get a server-derived "on file" boolean; the sheet import refuses a mapping
   that names the field.
5. **Paid *and* `Delivered`** is revenue recognition, read only through
   `RefundService.isRevenueRecognized`. Since Case Creation v2.0 a case is born
   paid, so in practice the open half is delivery — but the conjunction stays,
   because a refund can take `paid` back and delivery alone must never imply
   earned. Collected-but-undelivered value is tracked as open liability (refund
   exposure), never as earned. A GM-approved refund reverses recognition and voids
   the pending payout.
6. Controllers stay thin and never run long-lived work. SLA timers, reminders, the
   day-3 escalation, the expert-sign **prompts** (they never reassign), and the
   outbound outbox run in `job`. Retention/countdown is **not** on that list — GHL
   owns it. Each sweep holds an advisory lock on its job type, and no sweep
   transitions a case.

   **One read-side exception, and it does not weaken the rule.**
   `MarketingPipelineService` totals a GHL window larger than ~1,000 opportunities on
   its own single background thread rather than in `job`. The rule points at `job`
   because a *lifecycle side effect* must not be lost — this loses nothing: it writes
   no EvalOS row, and a failed total is a cache entry marked `UNAVAILABLE` that ages
   out on the normal TTL. What forces it off the request thread is arithmetic, not
   preference: GHL's year is ~11.4k opportunities in 115 cursor pages that cannot be
   parallelised, and GHL's own limit of 100 requests per 10 seconds per location puts
   a ~13s floor under it — past the browser's 15s timeout. The controller still
   returns immediately; the cache is the handover. A `job` row for a read nobody has
   asked to be durable would be ceremony around a cache miss.
7. EvalOS is the system of record for cases, experts, and payouts. Contact data
   is a read-only, brand-tagged snapshot synced from GHL and is never mutated.
   **`ghl_contact_id` is the canonical external client identity** — everywhere,
   including any future connected app. EvalOS never mints one, never changes one
   when a case is created, and never substitutes another identifier for it. Three
   identifiers, never conflated: `ghl_contact_id` = the client;
   `ghl_opportunity_id` = one purchase; `evalos_case.id` / `case_code` = one
   service engagement, **internal only**. One contact has many cases, so a case
   identifier is never a client identifier. Contact matching goes by
   `ghl_contact_id` first; `email` is a fallback only, used when no GHL id is
   given.
8. A case is only ever created through a per-brand GHL webhook endpoint, by a
   **won opportunity** — no other code path and no other event may create one. The
   case is created **paid**, from the opportunity's own amount; **no staff action
   sets `paid`**, and no unpaid case may pass `DOC_COLLECTION`.
9. Schema changes ship as new Flyway migrations. An applied migration is never
   edited in place.
10. Every inbound webhook is brand-resolved from its endpoint token (the brand
    must be active), deduplicated, and archived before it produces any side
    effect. Inbound deliveries are not signature-verified: GHL cannot sign them.
11. Every outbound webhook is HMAC-signed, retried with backoff, dead-lettered on
    exhaustion, and recorded in the delivery log. Outbound payloads never contain
    the `payment_detail` field or role-restricted internal notes.
12. Webhook transport carries no business logic — it resolves, routes to a
    service, or delivers a published domain event.
13. Every state transition on every object writes an append-only, non-editable
    audit entry (actor, action, timestamp). The audit table has no update or
    delete path. **The actor is a kind as well as an id** (Unit 14): `actor_id`
    names a staff member, and `actor_type` says `STAFF` / `SYSTEM` / `CLIENT` /
    `EXPERT` — because a client approving their own draft is neither staff nor the
    system, and it is that approval which sends a letter to an expert to sign. The
    column is nullable and historical rows are **not** backfilled: the `V10`
    trigger means no `UPDATE` can ever touch them, so for a null read `SYSTEM`
    when `actor_id` is null and `STAFF` otherwise. Three writers, one per
    surface — `recordEvent`, `recordSystemEvent`, `recordPortalEvent` — and each
    takes its brand from the most authoritative signal it has, never from a
    request body.
14. EvalOS hosts no files and sends no email. Documents are Drive links, the signed
    letter is filed into the case's Drive folder by the expert's own upload, staff
    alerts are in-app, clients are reached through GHL, and experts through a scoped
    portal link.

    **"Hosts no files" means stores none, not accepts none.** Unit 21 lets a client
    upload a document through the portal, and the bytes **stream through to the
    case's Drive folder** — EvalOS writes no file to disk, holds no blob column, and
    keeps only the Drive file id. That is a testable property, not a convention, and
    it is the whole reason an upload endpoint does not break this invariant.

    **"Sends no email" is currently true and is under review.** Every client- and
    expert-facing touchpoint is listed in `context/process-automation.md` with its
    channel marked *decision pending*: GHL delivers them off the outbound event
    (today's answer) or EvalOS sends mail itself, which would **reverse this
    invariant** and bring in SMTP, deliverability, bounces, unsubscribe and a
    suppression list. Nothing is built either way. Until that decision is taken, do
    not add a mail dependency — and if it is taken, this invariant is what changes.
