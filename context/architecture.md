# EvalOS — Architecture Context

## Stack

| Layer            | Technology                                        | Role                                                                              |
| ---------------- | ------------------------------------------------- | --------------------------------------------------------------------------------- |
| API / runtime    | Java 21 + Spring Boot (Spring Web MVC) + Maven     | REST API, webhook receivers, service orchestration                                |
| Database         | PostgreSQL + Spring Data JPA (Hibernate)           | System of record: brands, cases, experts, payout ledger, contact snapshots, audit |
| Migrations       | Flyway                                             | Versioned schema — every change is a new migration, never an edited one           |
| Internal auth    | Spring Security + JWT + role authorities (RBAC/ABAC) | Staff login; per-role + brand/team/assignee authorization (optional SSO later)   |
| Portal auth      | Separate Spring Security filter chains (scoped, link-based) | Expert portal and client draft-review portal — isolated from internal auth |
| Frontend         | React + TypeScript (Vite SPA) + Tailwind           | Internal role-based dashboards, client portal, expert portal                      |
| Raw documents    | Google Drive (existing) + **Drive API v3 (outbound, Unit 13)** | Client document folders — link stored on the case, not re-hosted. **Since Unit 13 EvalOS also writes one file into the case's folder**: the redacted expert profile, uploaded as a Google Doc |
| E-signature      | Dropbox Sign API                                   | Expert electronic sign-off + storage of signed letters                            |
| Notifications    | In-app notification center (staff) + GHL (clients) + Dropbox Sign (experts) | No EvalOS mail server                                            |
| Background work  | Spring `@Scheduled` + `@Async` (+ app events) + a persisted `scheduled_job` table | SLA timers, reminders, escalations, auto-reassign, retention/countdown timers, Handoff C |
| Integration seam | Inbound webhook gateway + outbound webhook dispatcher (+ GHL & Dropbox Sign clients) | Receive GHL/Dropbox Sign events; emit EvalOS lifecycle events to subscribers |

**No object storage.** EvalOS hosts no files. Client documents and drafts are
referenced by Google Drive link; signed letters live in Dropbox Sign; the
redacted CV is generated on demand (and, if persisted, written to the case's
Drive folder — never to a database blob).

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
**second external dependency in Phase 2** alongside Dropbox Sign (Unit 15), and it
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
- `integration` — outbound clients for the GHL API, Dropbox Sign, and **Google
  Drive** (`GoogleDriveClient`, Unit 13 — the first one built). Each is one narrow
  capability, not a general SDK wrapper: the Drive client uploads one file into one
  existing folder and does nothing else. A failure here is a 502 that changes
  nothing in EvalOS, never a partially-applied state.
- `webhook` — inbound webhook gateway: signature/secret verification,
  idempotency, raw-payload archival, brand resolution, routing to a domain
  service. Sources: GHL (per-brand endpoints), Dropbox Sign.
- `event` — internal domain events (Spring `ApplicationEvent`) published on
  lifecycle transitions, plus the outbound webhook dispatcher (subscriber
  registry, HMAC signing, retry/backoff, dead-letter, delivery log, replay).
- `job` — `@Scheduled` / `@Async` workers backed by the `scheduled_job` table
  (SLA, reminders, auto-reassign, retention/countdown, Handoff-C dispatch).
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
  entries, read-only contact snapshots synced from GHL, in-app notifications, and
  the append-only audit trail. Relational integrity via foreign keys; JSONB only
  for genuinely schemaless blobs (e.g. raw webhook payload archive).
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
- **Dropbox Sign (external)**: signed letters + e-signature workflow + storage.
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
  yes; client identity/case content no. *(Self-tier scoping needs a column that
  names the caller, and both now exist: `V17` added `evalos_case.assigned_coordinator`
  beside `assigned_cm`, and `ScopePredicate.Fields` takes a **set** of assignment
  attributes — a Self caller matches when any of them names them. One case is one
  pipeline and the people working it hold different slots. This supersedes the note
  that a Coordinator's case scope was not yet expressible; it was the gap that left
  their board empty and answered 403 on cases they owned.)*
- **Clients** access the draft-review portal via a passwordless link delivered
  through GHL (a separate, scoped filter chain). They see only their own case's
  draft, and can approve or request revisions.
- **Experts** access assigned cases via the secure link in the Dropbox Sign
  request / shared by the Case Manager (a separate, scoped filter chain), and can
  only ever see cases assigned to them.
- **The portal token model** (built in Unit 14, one table for both portals). A
  `portal_access` row names one case and one audience (`CLIENT` / `EXPERT`); the
  token is 256 bits from `SecureRandom`, returned **once** at mint time and stored
  only as a SHA-256 hash, so a database read yields no working link. Expiry is
  absolute (30 days, configurable) and re-minting revokes the previous token
  inside the same transaction, so a support request cannot widen the number of
  live credentials. Unknown, expired and revoked are one indistinguishable 401,
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

## The Three Handoffs (the front/back seam)

- **Handoff A — GHL → EvalOS (trigger: contact created).** GHL fires a webhook to
  that brand's dedicated endpoint when a contact is created; the webhook is proof
  that somebody wants something, **not** proof of payment. The `webhook` gateway
  verifies the secret, resolves the brand from the endpoint token, deduplicates
  on the source event id (idempotency), archives the raw payload, then the case
  service creates-or-refreshes a brand-tagged case at `DOC_COLLECTION` in the
  brand pool, syncs the contact snapshot, opens the document checklist, and
  notifies the GM/Brand-Manager pool. EvalOS does **not** talk to the payment
  processor.

  Payment is a separate fact recorded on the case (`paid` / `paid_at`) by a GM or
  Brand Manager through the `mark-paid` transition — or by intake itself when GHL
  already knows the contact paid. Two things depend on it: **no case reaches an
  expert unpaid** (the guard is on the `DOC_COLLECTION → EXPERT_ASSIGNMENT`
  transition, which every later stage is only reachable through), and **no unpaid
  case counts as earned revenue**. Document collection against an unpaid case is
  deliberately allowed — it costs EvalOS nothing.

  `mark-paid` stays callable on a paid case: `paid` / `paid_at` are write-once,
  but the **amount is correctable**, because a contact that arrived already paid
  carries only the quote and somebody has to be able to record what was actually
  collected. One value, never a running total, so a correction cannot
  double-count.

  One open case per contact per service: a repeat delivery refreshes the case
  that contact already has open, never resetting its stage, assignment, or
  `paid`. A contact buying a second service opens a second case; one returning
  after the first case closed opens a new one. Enforced by a **partial unique
  index** (`V15`), not by the lookup — a lookup followed by an insert is a
  check-then-act that two concurrent deliveries can both win.
- **Handoff B — internal (trigger: client approves draft).** The case moves to
  `EXPERT_SIGNING` and appears in the expert portal with draft + evidence + goal;
  Dropbox Sign issues the signing request. Exception paths: request-evidence
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
1. **Verify** the source signature / shared secret before the body is
   deserialized. Unverified requests are dropped and logged — never processed.
2. **Resolve brand** from the per-brand endpoint token (Handoff A).
3. **Deduplicate** on the source event id (idempotency), scoped by brand; a
   replayed event never produces a second side effect. "Already seen" is not
   "already done" — only a *processed* row is a duplicate, so a redelivery after
   a handler failure retries instead of being swallowed.
4. **Archive** the raw payload (JSONB) for audit and replay.
5. **Route** to the matching handler, which calls a domain service.
6. **Acknowledge** fast; slow work is handed to a `job`. Failures return a
   retriable status so the source re-delivers.

Inbound sources and events:
- **GHL** — `contact.created` (Handoff A, per-brand endpoint); `refund.requested`
  → Refund Requested exception state (GM-only approval to finalize);
  `contact.updated` → refresh the read-only contact snapshot. (Refund/contact-update
  events are recognized by the gateway; build them when the payloads are confirmed.
  `contact.updated` deliberately does *not* route to intake: an edit in GHL is not
  a reason to open a case.)
- **Dropbox Sign** — `signature_request.signed` / `..._declined` / `..._viewed`
  callbacks drive expert sign-off status instead of polling.

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
2. A case is in exactly one system's custody at any moment. EvalOS never runs
   marketing, sales, nurture/cold email, ad attribution, or invoicing.
3. Role, brand, and ownership are enforced before every mutation. Case Managers,
   clients, and experts never see data outside their assignment.
4. The optional expert `payment_detail` is encrypted at rest and never appears in
   logs, webhook payloads, DTOs, chat tools, or any response body. Unit 11 gave
   it its only write path (`PUT /api/experts/{id}/payment-detail`) and
   deliberately **no read path at all** — not for the ENM who typed it. Screens
   get a server-derived "on file" boolean; the sheet import refuses a mapping
   that names the field.
5. **Paid *and* `Delivered`** is revenue recognition. Since Handoff A moved to
   contact intake a case can exist, and be worked, with no money behind it, so
   delivery alone no longer implies earned. Collected-but-undelivered value is
   tracked as open liability (refund exposure), never as earned. A GM-approved
   refund reverses recognition and voids the pending payout.
6. Controllers stay thin and never run long-lived work. SLA timers, reminders,
   auto-reassignment, retention/countdown, and Handoff C run in `job`.
7. EvalOS is the system of record for cases, experts, and payouts. Contact data
   is a read-only, brand-tagged snapshot synced from GHL and is never mutated.
8. A case is only ever created through a per-brand GHL webhook endpoint — no other
   code path may create one. Marking a case **paid** is a separate, deliberate
   staff act (GM or Brand Manager, or intake when GHL already reports it paid),
   and no unpaid case may pass `DOC_COLLECTION`.
9. Schema changes ship as new Flyway migrations. An applied migration is never
   edited in place.
10. Every inbound webhook is signature-verified, brand-resolved, deduplicated,
    and archived before it produces any side effect.
11. Every outbound webhook is HMAC-signed, retried with backoff, dead-lettered on
    exhaustion, and recorded in the delivery log. Outbound payloads never contain
    the `payment_detail` field or role-restricted internal notes.
12. Webhook transport carries no business logic — it verifies, routes to a
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
14. EvalOS hosts no files and sends no email. Documents are Drive links, signed
    letters are in Dropbox Sign, staff alerts are in-app, and client/expert
    messages go through GHL / Dropbox Sign.
