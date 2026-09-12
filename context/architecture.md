# EvalOS — Architecture Context

## Stack

| Layer            | Technology                                        | Role                                                                              |
| ---------------- | ------------------------------------------------- | --------------------------------------------------------------------------------- |
| API / runtime    | Java 21 + Spring Boot (Spring Web MVC) + Maven     | REST API, webhook receivers, service orchestration                                |
| Database         | PostgreSQL + Spring Data JPA (Hibernate)           | System of record: brands, cases, experts, payout ledger, contact snapshots, audit |
| Migrations       | Flyway                                             | Versioned schema — every change is a new migration, never an edited one           |
| Internal auth    | Spring Security + JWT + role authorities (RBAC/ABAC) | Staff login; per-role + brand/team/assignee authorization (optional SSO later)   |
| Portal auth      | Separate Spring Security filter chain (scoped, link-based), one chain, two audiences; an `EXPERT` token also **names its expert** (`V37`) and is refused when the case's expert is somebody else | **Both portals live in `client-expert/`, as two separately built apps** (`client/` 5174, `expert/` 5175) calling this backend: the client's document-upload and draft-review surface, and the expert's download-sign-reupload surface. `portal_access.audience` (`CLIENT` / `EXPERT`, V21) is what separates them — isolated from internal auth. **CORS is built** (Unit 30): scoped to `/api/portal/**`, origins from `evalos.portal.allowed-origins` (no default in prod, so a missing value fails the boot), methods `GET/POST/OPTIONS`, headers `Content-Type` + `X-Portal-Token`, and **`allowCredentials(false)`** — the credential is a header, never a cookie. **The delivered app sent `withCredentials: true` and no portal token at all; Unit 34a fixed both** — the token now travels in `X-Portal-Token` out of the URL fragment, held in memory and never persisted |
| Frontend         | React + TypeScript (Vite SPA) + Tailwind, with `radix-ui`, `lucide-react`, `recharts` | Internal role-based dashboards, client portal, expert portal. The three UI packages landed in Unit 22 slice 1, each against a screen that needed it; dnd-kit, TanStack Table and Motion stay deferred with written triggers in that spec |
| Raw documents    | **S3 document store (Unit 30)** — AWS SDK v2 | **EvalOS is the only writer.** Client documents land under `{brandId}/client/{ghlContactId}/{documentId}` when the portal frontend posts them *through* EvalOS, which streams them; its own artefacts sit under `{brandId}/case/{caseId}/{folder}/{documentId}`. **EvalOS holds object keys, never bytes**, and serves them as 5-minute presigned URLs. Replaced Google Drive in Unit 30 |
| E-signature      | **None — no provider.** The expert signs in their own tool and uploads the signed PDF through their portal | A scanned wet signature is the norm for an expert opinion letter. Provenance is a hash pair + an attestation + an `EXPERT` audit row, not a certificate — see `15-expert-portal-handoff-b.md` |
| Notifications    | In-app notification center (staff) + GHL (clients) + a portal link (experts) | **SMTP for authentication mail only, as of Unit 42** (`spring-boot-starter-mail`): *set your password* and *reset your password*, and nothing else. Invariant 14 amended in writing 2026-09-11. No marketing, status or notification mail, and no outbound queue |
| Background work  | Spring `@Scheduled` (+ app events) + a `scheduled_job` run ledger + a session-scoped Postgres advisory lock per sweep | SLA timers, reminders, escalations, expert-sign prompts. No outbound outbox — Unit 18 was removed. **No Quartz, no ShedLock, no broker** |
| Queue            | **None.** `webhook_delivery` went with Unit 18 (removed 2026-09-02) | EvalOS has no cross-process work left. If outbound delivery ever returns, the argument to re-read is Unit 19's: a durable row with backoff, not a broker |
| Integration seam | Inbound webhook gateway + outbound webhook dispatcher (+ the GHL and S3 clients) | Receive GHL events; emit EvalOS lifecycle events to subscribers. **One inbound source, GHL** — dropping the signature provider removed the second |
| GHL read API | `RestClient` against GHL's public API, `opportunities.readonly` (**inbound *pull*, Unit 24**) | The GM's marketing funnel view, and nothing else. **Read-only, and no write method**: two calls and a cached payload. The *aggregate* the screen draws is cached in `ghl_funnel_cache` (it was a heap map until 2026-08-26 — a per-process cache lost a completed background total on restart and could not hand one instance's result to another). **No opportunity rows are stored**: there is no `ghl_opportunity` table and there must not be one, because a stage dragged five seconds ago would already be wrong in it. The table is a cache, not a record — safe to truncate, and not brand-scoped because the figures come from one global GHL location EvalOS cannot attribute to a brand. This is the third direction across the GHL seam — events in, events out, and now one pull — and it is the only one that is not a handoff |

**Object storage, and EvalOS hosts nothing in it.** Documents live in an **S3 bucket**
(Unit 30). The sentence here used to read *"No object storage. EvalOS hosts no files."* —
the first half is now false and is gone; the second half is still true and is what
invariant 14 carries. EvalOS stores **object keys**, exactly as it stored Drive links, and
no bytes on disk, on the heap or in a column.

**One writer, two prefixes, and the prefixes are for humans rather than for IAM.** *(This
paragraph said the opposite until it was corrected — spec 30's first draft had the Client Portal
writing to S3 with EvalOS read-only on `client/`. **That is wrong and must not be revived.** The
portal is a separate **frontend** and holds **no AWS credential at all**; it calls EvalOS, and
EvalOS streams the bytes.)*

`{brandId}/client/{ghlContactId}/…` is what a client sent us; `{brandId}/case/{caseId}/…` is what
we produced. **Brand first**, because every other store in EvalOS enforces brand at the row and a
key prefix is S3's equivalent — and changing that later migrates the objects, not the code. The
object name is the **document's own id**, never its filename, which closes path traversal,
collisions and PII-in-the-key at once.

**What replaces the IAM guarantee, since it is a real reduction in defence:** bucket versioning is
**non-optional**, and **EvalOS never overwrites a `client/` key** — every upload mints a new
document id and therefore a new key, so a client replacing a rejected transcript adds a version
rather than destroying the evidence of why it was rejected. That is a code rule with a test behind
it instead of a policy.

A presigned **PUT** handed to the browser was considered and rejected: it puts the key format —
the thing that makes a document findable — in the hands of the least controlled party, and skips
the content-type and size checks that have to happen somewhere.

**`{clientId}` is GHL's contact id** — the same identifier EvalOS keys
`contact_snapshot.ghl_contact_id` on and the same one the Client Portal uses. One client is
one client in all three systems, with **no mapping table between them**, which is the whole
reason a key written by the portal resolves in EvalOS. Email stays a **fallback** key
(V27): consistent across the three systems, but never the identity, because two GHL
contacts can share an inbox and treating email as identity once attached a case to the
wrong client.

**Reads are bounded capabilities, not permanent ones.** A document is opened through a
**presigned GET URL valid for 5 minutes**, minted per request, never stored, and issued
**only after** the caller passes the same scope check that guards the case — a URL minted
before the check is a URL that leaked before the check. Every issue writes an audit row.
This is strictly safer than the Drive link it replaces, which was a permanent capability
sitting in a column.

**Accepting a file is still not hosting one.** An upload through EvalOS's own portal — the
expert's signed letter — **streams** to S3. No byte array, no temp file, no blob column,
and Unit 30 asserts that rather than assuming it.

**Google Drive is gone as of Unit 30**, along with the service account that blocked Units
13, 15 and 21 for weeks. See `context/specs/30-s3-document-store.md`; the AWS credential is
environment-supplied with no default outside `local`, bound the same way as
`EVALOS_FIELD_KEY`, and the local profile still boots without it.

Unit 11 added the one **upload** in EvalOS, and it does not change that: the
expert roster sheet is parsed in memory and thrown away — no row, column or temp
file keeps it. `spring.servlet.multipart.file-size-threshold` is set equal to
`max-file-size` for exactly that reason, since above the threshold the container
spools the part to a temp file. Two parsers sit behind it (`commons-csv` for
`.csv`, `poi-ooxml` for `.xlsx`); only `service/ExpertImportService` touches
either.

Repository layout is a monorepo of **three** applications, and it was two until
2026-09-03:

- `backend/` — Spring Boot. Java under the base package `com.ie.evalos`.
- `frontend/` — the **internal staff** SPA (React + Vite, port 5173, `/api` proxied
  same-origin). It still carries Unit 14's one-screen client portal at `/portal/*`,
  which **Unit 34 supersedes** when the portal frontend is wired.
- `client-expert/` — the **external portal frontends** (React + Vite): `client/` on port
  5174 and `expert/` on port 5175 are **two applications with two builds**, so each can
  take its own subdomain, over **one** `package.json` and `node_modules` and a `shared/`
  folder both import as `@shared/*` (split 2026-09-03; they shipped as one app). Neither
  app imports the other — that is what keeps them deployable apart. Cross-origin
  against `/api/portal/**`, which is why that chain is the only one with CORS. **One screen
  is wired** — the client's `/documents`, against the real S3-backed portal API (Unit 34
  slices 34a + 34c); every other screen is still a `localStorage` mock. The rest of the
  wiring, and the three invariant conflicts the app arrives with, are
  `context/specs/34-portal-frontend-wiring.md`.

`client-expert/` is a separate deployment and shares no code, no build and no design tokens
with `frontend/` — deliberately. An external client-facing surface is not the internal
operations tool, and a shared component library across that boundary would drag the
staff app's density and palette onto a client's screen.

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
  brand (Brand Manager) or narrower (team/self). No exceptions: Unit 29's
  `SALES_EXECUTIVE` was one role with a NULL brand and it has been removed along with
  the sales desk, so every non-GM row again carries a brand — enforced by
  `team_member_brand_required` (V3, restored by V30).
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
- `integration` — outbound clients for the GHL API and the **S3 document store**
  (Unit 30). Each is one narrow capability, not a general SDK wrapper: the S3 client
  lists a prefix, gets an object, puts one under `case/`, and presigns a read. A failure
  here is a 502 that changes nothing in EvalOS, never a partially-applied state.
- `webhook` — inbound webhook gateway: brand resolution from the per-brand
  endpoint token, idempotency, raw-payload archival, routing to a domain
  service. **No signature verification** — see the inbound gateway below.
  **One source: GHL** (per-brand endpoints). There was going to be a second
  when a signature provider posted callbacks; dropping the provider removed it, and
  with it the only place in the design that threatened the protected
  brand-resolution step.
- `event` — internal domain events (Spring `ApplicationEvent`) published on
  lifecycle transitions. **`CaseEvents` alone**: the outbound webhook dispatcher went with
  Unit 18 (2026-09-02), so `notification/NotificationListeners` is the only subscriber there
  will be, and an event with no route raises nothing by decision rather than by omission.
- `job` — `@Scheduled` sweeps backed by the `scheduled_job` **run ledger** (BUILT, Unit 19).
  **Four sweeps**: doc chases, day-3 escalation, stage SLA, expert sign 20h/24h prompts. No
  outbox sender — it went with Unit 18. Each sweep takes a **session-scoped Postgres advisory
  lock on its job type** (not `pg_try_advisory_xact_lock`, which would release after the first
  item since a sweep runs one transaction per item), so
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

Portal frontends under `client-expert/`: `shared/src` holds what both apps use —
`components/ui` (shadcn-style primitives — the same "generated, do not edit" rule
applies), `components/common`, `services/apiClient.ts`, `lib/portal.ts`, `styles`;
`client/src` and `expert/src` each hold their own
`components`, `layouts` (Auth / Intake / Portal, and ExpertAuth / ExpertPortal),
`pages` (one folder per route group), `routes` (guards), `context` (`AuthContext`,
`ExpertAuthContext`),
**`services` (the entire mock/real boundary — every future HTTP call lives here and
nowhere else)**, `schemas` (Zod), `types`, `constants`, `mock`, `utils`, `styles`.
The `services` isolation is the single property that makes Unit 34 tractable; a page that
reaches past it is the defect that ends it.

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
- **S3 document store (Unit 30)**: client documents and EvalOS's own artefacts. The case
  carries **no client-document link at all** — a client's prefix is *derived* from the
  contact the case already points at (`client/{ghl_contact_id}/`), so there is no second
  copy of a fact the schema already holds. `draft_link` survives as an **object key**
  because a draft is one file among several versions and is not derivable. Only the draft
  is ever shown to a client: the client-document prefix is theirs, and presenting it as
  "your draft" would be a leak, so a case with no `draft_link` is told "not ready" rather
  than given a fallback.
- **No e-signature provider.** The expert signs in whatever tool they already use and
  uploads the signed PDF through their portal; it streams to `case/{caseId}/signed/`.
  EvalOS keeps the object key, a hash of what it sent and of what came back, and the
  expert's attestation. **Unit 30 changed where that file lands and nothing about how it
  is trusted.**
- **Redacted CV**: generated on demand from the expert profile; not persisted to
  any EvalOS-hosted store. Held in memory only — streamed to the caller, or streamed to
  `case/{caseId}/redacted-profile/` — and never written to Postgres or to disk. **Note the
  cost Unit 30 carries here:** Drive's export produced a PDF for free and S3 converts
  nothing, so the output format is an open question rather than a solved one.
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
- **Clients** access the portal via a passwordless link delivered through GHL (a separate,
  scoped filter chain). They see their own case or cases — the draft, the checklist, their own
  uploads — and can approve or request revisions.
- **Experts** access their assignments through a scoped portal link the Case Manager sends
  (the same chain, the other audience), download the letter, and upload it back signed.
- **A portal credential names a PARTY, and that was decided on 2026-09-04** (`D1` in
  `34-portal-frontend-wiring.md`, built in `35-party-scoped-portal-access.md`). A `CLIENT` row
  names a `ghl_contact_id`; an `EXPERT` row names an `expert_id`; `case_id` is nullable, because a
  **case-scoped link stays legal** for the thing it is better at — one case, forwarded once,
  revocable on its own. A party token lives **7 days** against the case token's 30, being the
  wider credential.
  *Superseded: "one token, one case" as the whole access model.* It could not answer "my cases",
  which is what both delivered surfaces draw and what a client with two cases needs.
  **There are still no accounts, and that is the half of D1 that was refused rather than deferred**
  — no password store, no reset, no lockout, no session. Building them by drift reverses four
  documents and needs a mail channel invariant 14 says does not exist.
- **What the widening does not touch**: 256 bits from `SecureRandom`, returned once, stored only as
  a SHA-256 hash, absolute expiry, one live token per scope, re-mint revokes the previous, and one
  identical 401 for unknown / expired / revoked. An `EXPERT` token also names its expert (`V37`),
  so a token that outlived a rematch admits nobody.
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
| Sales pipeline (*Aditya's pipeline*) | **the record.** Every opportunity lives here and nowhere else | **nothing. Unit 27 *reads* it on the same terms as the two funnels above.** No `ghl_opportunity` table, no sales row anywhere in EvalOS. Unit 29 briefly *operated* it from an EvalOS screen; that desk was removed and EvalOS writes nothing to GHL again |

Those last three rows are the exception the table needed, and the distinction they draw
is the one to keep: **custody and visibility are different things.**

**The sales desk was the one exception to that pair and it is gone.** Unit 29 let a sales
executive move a stage from an EvalOS screen — visibility plus *operation*, while still
holding nothing. That role and that screen were removed, so the pair stands unqualified
again: custody and visibility, and EvalOS has visibility only. `GhlHttp` has no write
verb at all now, which is the code-level statement of it.

Unit 24 puts GHL's Google
Ads funnel on a GM screen — counts, values and sources per stage — while GHL stays the
system of record for every row in it. Nothing is copied here (no `ghl_opportunity`
table, by decision: a stage a salesperson dragged five seconds ago is already wrong in
a copy), nothing is written back **from the three funnel screens**, and no case exists
until `opportunity.won` fires as it always did. Invariant 2 is about *running*
marketing, and reading a funnel is not running one.

The `ghl_opportunity` decision — that no EvalOS table copies a pipeline row — outlasted
the desk that made it load-bearing, and is the reason removing the desk cost one
migration and no data reconciliation at all: there was nothing stored to unwind.
`opportunity.won` still fires from GHL and still creates the case through Handoff A.

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
- **Handoff B — internal (trigger: the CM sends the client-approved letter).** The case is in
  `EXPERT_SIGNING` and appears in the expert portal with draft + evidence + goal (**built,
  Unit 15**). The expert **downloads the letter, signs it in their own tool, and uploads the
  signed PDF back**, which streams it to `{brandId}/case/{caseId}/signed/` and moves the case to
  the PM for final QC. There is no signature provider: provenance is **the hash of what came
  back**, an attestation captured at upload with the name it displayed, and an audit row with
  `actor_type = 'EXPERT'`.
  **The hash of what was *sent* is not recorded and cannot be** — the letter is `draft_link`, a
  pasted link to a document EvalOS holds no bytes of, and the document store has no read
  capability. Half the pair is missing until a draft is an object in S3; **PM final QC is
  load-bearing** in the meantime, being the only check that the file is the right letter and is
  actually signed. Exception paths: request-evidence raises `ON_HOLD_AWAITING_CLIENT` and opens a
  **required checklist item** (never a second task entity), so the expert cannot sign until the
  Coordinator resumes; decline returns the case to `EXPERT_DECLINED_REMATCHING` with the reason
  logged and the match engine proposing the next expert.
- **~~Handoff C — EvalOS → GHL~~ — REMOVED with Unit 18 (2026-09-02). There are two
  handoffs, not three.** EvalOS was to emit a signed outbound `case.delivered` webhook
  on delivery, starting GHL's review and referral track. That dispatcher was never
  built and is now out of scope.

  **What survives, and it is the half that matters operationally:** the payout ledger
  entry is still created on delivery, in the same transaction as the transition
  (`CaseLifecycleService.deliverToClient` → `PayoutService.openForDelivery`). That was
  always Unit 16's, not Unit 18's, and it is built.

  **What is genuinely lost — say it plainly.** Nothing tells GHL a case was delivered,
  so the review sequence, the referral track and the suppression-list sync do not fire
  from EvalOS. **Somebody has to start them by hand in GHL**, and that is the cost of
  this decision rather than an oversight to be discovered later.

  **The larger consequence: EvalOS now emits nothing outbound at all.** Its integration
  surface is inbound webhooks from GHL and read-only pulls of GHL's funnels. Domain
  events still publish, but only in-process, and their only consumer is the in-app
  notification centre. See invariant 14 — "sends no email" is no longer a pending
  decision, it is the architecture.

## Case State Machine (EvalOS-owned stages 3–7)

> **⚠ Being replaced by Unit 31 — Production lifecycle v2.** Read
> `context/specs/31-production-lifecycle-v2.md` before changing any transition. Two
> transitions are added — **`qc-fail`**, which does not exist today and is the one that
> catches a bad letter before a client sees it, and **`send-to-expert`**, which is the act
> that should start the 24-hour signing SLA. Several gates move; the Case Manager takes
> ownership of expert signing and reassignment.

Canonical 8-stage business pipeline; GHL owns stages 1 (Marketing), 2 (Sales),
and 8 (Retention). EvalOS's internal `Stage` enum covers stages 3–7:

```
DOC_COLLECTION → EXPERT_ASSIGNMENT → DRAFT_GENERATION → EXPERT_SIGNING → FINAL_DELIVERY → CLOSED
```

**⚠ Unit 31 replaces this with twelve stages (SPECCED 2026-09-02, not built).** Each has
**one owner, one primary action, one event and one next owner**; facts held today as
sub-statuses on a stage become stages of their own.

```
01 Document Collection (Coordinator) → 02 PM Review & Assignment (PM)
→ 03 Draft In Progress (CM) → 04 Draft Review (PM) → 05 Ready to Send (Coordinator)
→ 06 Client Review (client acts) → 07 Client Approval (CM) → 08 Expert Signing (expert acts)
→ 09 Final QC (PM) → 10 Ready to Deliver (Coordinator) → 11 Delivered → 12 Closed

A stage is entered by the ACT that starts its clock: Client Review by the Coordinator's
Send, Expert Signing by the CM's Send to Expert. So `stage_entered_at` is the send time and
no `sent_at` column exists. The board draws EIGHT columns, not twelve.
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

   **The event id is looked for at the top level and inside `customData`, and
   falls back to a SHA-256 of the body.** GHL's Custom Webhook mints no delivery
   id of its own, so demanding one refused every real delivery — and a webhook
   retry replays the same bytes, which is exactly what the digest keys on.
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
- **Document retention is an open question (Unit 30 (a)).** Drive held this policy and
  nothing does now. The default until it is answered is keep-indefinitely with bucket
  versioning on — deleting a client's evidence on a guess is the worse of the two errors.

## Invariants

> **Four of these are scheduled to change (decided 2026-09-10), and none has changed yet.**
> The GHL operational programme (Units 36–41) makes EvalOS the interface Sales and Marketing work
> in. **`context/specs/00b-ghl-operational-programme.md` §2 is the ledger** — it says which
> invariant changes, into what, and in which unit. The summary:
>
> | Invariant | Fate | Unit |
> |---|---|---|
> | **1** brand isolation | **narrowed ✅ (Unit 36 built)** — the GHL-location exception is now "one brand named in `evalos.ghl.sales-brand`", enforced with a 400 | 36 |
> | **2** EvalOS runs no sales/marketing/invoicing | **DEAD ✅ (Unit 37 built)** — rewritten below. *Invoicing stays GHL's* and *Handoff A stays the only door into custody* | 37 |
> | **7** contact data never mutated | **first clause amended only** — the three-identifier rule survives verbatim and is load-bearing | 39 |
> | **14** EvalOS sends no email | **ruled on, not reversed** — EvalOS instructs, GHL delivers | ruled in `00b`, no unit |
>
> **Until the named unit ships, the invariant below is live and enforced.** Do not pre-emptively
> relax any of these because the programme is coming — **7 and 14 have not changed yet.**
>
> **Invariants 1 and 2 have now changed and their text below is rewritten, not annotated.**
> `GhlHttp` writes as of Unit 37. What guards it instead: the verb list is closed, and every
> caller of a write verb must reach `AuditService` — both build-failing tests in `GhlHttpTest`.
>
> **Invariants 5, 8, 13 and 15 are untouched by the programme** and the first three are load-bearing
> inside it — especially **8**, which keeps a case born only of a won opportunity even though Sales
> now marks the opportunity won from EvalOS.

1. **Brand isolation.** Every scoped query filters by `brand_id`; no code path
   returns another brand's data. The GM is the only cross-brand role **reader of
   EvalOS rows**.

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
2. **A case is in exactly one system's custody at any moment.** That half stands and is the
   part worth keeping. **The other half — "EvalOS runs no marketing, invoicing or sales of
   its own" — died at Unit 37 (2026-09-10), deliberately and in writing.**

   **What EvalOS now does.** Sales and Marketing work their leads and opportunities from
   EvalOS screens; `GhlHttp` has `post`, `put` and `delete`; and Unit 38 stores a cache of
   GHL opportunities. GHL remains the CRM, the pipeline engine, the automation engine and
   the invoice/QuickBooks integration underneath. The programme and its reasoning are in
   `context/specs/00b-ghl-operational-programme.md`, which is amended before this is.

   **What did not die, and must not be quietly taken with it:**
   - **Invoicing is still GHL's, full stop.** EvalOS raises no invoice and touches no
     accounting. Unit 41 *reads* invoices for the Client Portal; `Invoice sent` and `Refund`
     remain stages it acts on neither, and a refund is a payment fact.
   - **Handoff A is still the only door a case enters custody through.** `opportunity.won`
     fires from GHL and creates the case (invariant 8, untouched). A salesperson marking an
     opportunity won from EvalOS **waits for the webhook** — EvalOS never creates the case
     itself, and `DomainInvariantsTest` refuses the shape.
   - **EvalOS still sends nothing.** Invariant 14 holds: EvalOS instructs, GHL delivers.

   **The round trip, kept because it is the whole reason Unit 37 was its own unit.** Unit 29
   amended this invariant for a sales desk in August 2026; the desk and the role were removed
   days later and the amendment reverted with them. **What made that reversal cheap was a
   decision that was never amended** — no `ghl_opportunity` table, no sales column on any
   EvalOS entity, so undoing it cost one migration and no data reconciliation.

   **That property is now being spent, knowingly.** Unit 38's cache is the first EvalOS row
   holding a pipeline fact, so **this reversal is not reversible at the price the last one
   was.** That, not the code, is the cost of the pivot. The old warning — *the day EvalOS
   stores a pipeline fact, two systems own it* — was correct, and the answer is that the
   cache holds **only fields GHL owns**, is **droppable without loss**, and is never the
   answer to a write. If a column ever appears in it that GHL does not have, that decision is
   void and gets re-argued here.

   **What replaced the old guarantee, because deleting a test is not a decision.**
   `GhlHttpTest` used to assert that no write verb existed — the capability *absent from the
   codebase*, not merely unused. That assertion is gone. Two took its place, in the same file:

   - **The verb list is closed.** `GhlHttp` exposes exactly `get`, `post`, `put`, `delete`.
     A fifth fails the build, so the next capability is also a decision. There is deliberately
     no `patch`: GHL's API does not use it, and a verb no endpoint accepts is exactly the
     present-and-unused capability this invariant used to be about.
   - **Every caller of a write verb reaches `AuditService`.** A structural test over the
     source. This is invariant 13 for writes that land in another system — **a mutation whose
     only trace is in GHL is invisible to EvalOS forever**, which is the failure mode of
     moving the desk over here. The caller audits, not `GhlHttp`: transport does not know what
     a write *means* (invariant 12's reasoning), so any row it wrote would say nothing useful.

   **The credential was never the guarantee and still is not.** The grant has always been
   `opportunities.write` + `contacts.write` — it permitted writes throughout the period this
   invariant forbade them. Code was the only thing holding that line, and code is the only
   thing holding the new one.

   **Writes do not retry.** Reads do not either, and GHL marks its write operations as
   needing idempotency while EvalOS has no key scheme yet — so a blind retry is how one
   opportunity becomes two, with nothing to reconcile them by. Asserted by counting requests
   against a local server, not by reading configuration.
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
   day-3 escalation and the expert-sign **prompts** (they never reassign) run in `job`.
   Retention/countdown is **not** on that list — GHL owns it, and neither is an outbound
   outbox, which left with Unit 18. Each sweep holds an advisory lock on its job type, and
   **no sweep transitions a case** — there is no call site for `EXPERT_TIMED_OUT` in the
   package, which is what makes that a structure rather than a promise.

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
7. EvalOS is the system of record for cases, experts, and payouts.

   **Contact data is GHL's, and since Unit 39 (2026-09-11) EvalOS may ask GHL to change
   it.** The old clause read *"a read-only, brand-tagged snapshot synced from GHL and
   never mutated"*. Marketing now opens and edits leads from an EvalOS screen.

   **What "may change it" does and does not license**, because this is the clause most
   likely to be over-read:
   - EvalOS **asks GHL** to create or update a contact, and **displays what GHL returns**.
   - EvalOS still holds **no authoritative contact field of its own**. There is no
     EvalOS-owned name, email or phone that a screen reads in preference to GHL's.
   - The snapshot is still a snapshot. Writing through it does not make it a record.

   **The rest of this invariant is unchanged and is load-bearing for the whole GHL
   programme:**

   **`ghl_contact_id` is the canonical external client identity** — everywhere,
   including any future connected app. **EvalOS never mints one** — it asks GHL to
   create a contact and GHL returns the id, which is exactly why the amendment above is
   narrow: the write direction moved, the identity authority did not. EvalOS never
   changes one when a case is created, and never substitutes another identifier for it.
   Three identifiers, never conflated: `ghl_contact_id` = the client;
   `ghl_opportunity_id` = one purchase; `evalos_case.id` / `case_code` = one
   service engagement, **internal only**. One contact has many cases, so a case
   identifier is never a client identifier. Contact matching goes by
   `ghl_contact_id` first; `email` is a fallback only, used when no GHL id is
   given.

   **Unit 42 amends the first clause a second time, for one entity (2026-09-11).**
   `client_account` is an **EvalOS-owned record** whose `ghl_contact_id` is a nullable
   *link*. GHL's contact id remains canonical **in GHL**; what changed is that a client's
   ability to **sign in** no longer depends on GHL holding a row. A client whose GHL contact
   is deleted — or whose whole sub-account was replaced, which is what happened to IE on
   2026-09-11 — still signs in and still sees their cases and documents.

   **This is the second of three edits, and the third is already scheduled.** `00c` Unit 44
   rewrites this invariant **whole** rather than annotating it a fourth time. Three amendments
   across three units is how an invariant dies without anyone deciding to kill it, and naming
   the rewrite here is what stops that.

   **Unit 39 leans on that three-identifier rule rather than merely respecting it.**
   `opportunity_note` is keyed on `ghl_opportunity_id` and not on `ghl_contact_id`,
   because a repeat client is one contact and two deals — keying on the contact would
   merge two conversations with no way to separate them afterwards. GHL itself cannot
   express this: its only note endpoints hang off the contact, which is why the note
   stream is the one thing in this programme EvalOS owns outright.
8. A case is only ever created through a per-brand GHL webhook endpoint, by a
   **won opportunity** — no other code path and no other event may create one. The
   case is created **paid**, from the opportunity's own amount; **no staff action
   sets `paid`**, and no unpaid case may pass `DOC_COLLECTION`.

   **The first real pressure on this arrived with the portal frontend.** `client-expert/client/`
   ships a seven-screen guided intake that mints its own reference and submits a
   request — a case-shaped object created by a client, outside Handoff A. It is
   mock-backed and reaches nothing, so nothing is breached today; `34-portal-frontend-wiring.md`
   **D2 recommends cutting it** rather than finding it a backend. `DomainInvariantsTest`
   is what would refuse it anyway (only `GhlOpportunityHandler` may depend on
   `CaseIntakeService`, so a `POST /api/cases` breaks the build) — but a structural test
   is the last line, not the argument. **Intake is front of house and front of house is
   GHL's.**
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
14. EvalOS hosts no files, and **sends email for exactly one purpose: proving control of a
    client's own address** (Unit 42, decided 2026-09-11). Documents are **objects in the S3
    document store, referenced by key**; the expert's signed letter streams into
    `case/{caseId}/signed/` through their own portal upload, staff alerts are in-app,
    clients are reached through GHL, and experts through a scoped portal link.

    **What the mail amendment licenses, and what it does not.** `ClientMailer` sends two
    messages: *set your password* and *reset your password*. **Not licensed:** status mail,
    marketing mail, notification mail, or any message a client did not initiate by trying to
    sign in. Staff alerts remain in-app and clients are still reached through GHL for
    everything that is not authentication.

    **Why it was amended rather than worked around.** `34-portal-frontend-wiring.md` D1
    refused client accounts precisely because *"reset requires a mail channel EvalOS does not
    have"*, and required the reversal to be taken in writing rather than drifted into. It was
    taken on 2026-09-11. An emailed OTP was considered and refused as no cheaper — it is the
    same channel with more typing — and TOTP was refused as the wrong ask of a
    credential-evaluation client.

    **`00b` §2's ruling is now partly spent, deliberately.** It said *"what would break this is
    EvalOS composing and dispatching a message itself"*. EvalOS now does, for authentication
    only, and **the remaining line is drawn exactly there**. The first feature that wants "just
    a quick status email from the portal" is a new decision and gets argued in its own unit —
    it is not an extension of this one. See `42-client-accounts.md` §4.

    **"Hosts no files" means stores none, not accepts none, and the property is
    unchanged by Unit 30's move from Drive to S3.** An upload through EvalOS's portal
    **streams** to the store — EvalOS writes no file to disk, holds no blob column and no
    byte array, and keeps only the object key. That is a testable property, not a
    convention, and it is the whole reason an upload endpoint does not break this
    invariant. **The backing store changed; the test did not.**

    **What Unit 30 did change:** a document is now read through a **5-minute presigned
    URL**, minted per request after the case's scope check and never stored, rather than
    through a permanent Drive link sitting in a column. And **client documents are written
    by a separate Client Portal**, not by EvalOS — EvalOS's credential is read-only on
    that prefix, so "hosts no files" is now backed by IAM on the half that matters most.

    **"Sends no email" is settled as of 2026-09-02, and is no longer a pending decision.**
    Unit 18 — the outbound dispatcher that would have carried client-facing messages to
    GHL for delivery — is removed. With it goes the mechanism, so the question of whether
    EvalOS sends mail *itself* is not merely unanswered: **EvalOS has no outbound channel
    of any kind.** Domain events publish in-process and the notification centre is their
    only consumer.

    **What this costs, named rather than buried:** a document chase, a "your draft is
    ready", and the post-delivery review request have no automated route to the client.
    They are either done by hand in GHL, or they become **states the client sees in the
    client portal** — which is the natural home now that the portal is a real frontend
    with this backend. That is a product decision still to be taken; what is settled is
    that EvalOS does not send.

    **The portal arrived on 2026-09-03 (`client-expert/`), which makes that decision takeable
    and does not take it.** `34-portal-frontend-wiring.md` D4 recommends the in-portal
    route for T1–T8 and states its limit plainly: **a client who never opens the portal
    is never notified.** So this downgrades the email question from blocking to a reach
    problem; it does not close it, and it must not be written up as if it had.

    The original note, kept because the reasoning still applies to any future proposal:
    every client- and expert-facing touchpoint is listed in
    `context/process-automation.md`. EvalOS sending mail itself would **reverse this
    invariant** and bring in SMTP, deliverability, bounces, unsubscribe and a suppression
    list. **Do not add a mail dependency**; if that decision is ever taken, this invariant
    is what changes, and it changes in writing first.

15. **No AI makes a production decision, and there is no AI in the system at all.**

    **And as of 2026-09-04 it is gone from the schedule too, not only from scope** — the build
    plan's "request an Anthropic key" row and its "the anomaly half ships anyway" row are struck.
    The anomaly figure was arithmetic and belongs to Unit 17 as a tile if the business wants it;
    keeping a unit named for the model is how the model comes back wearing a helpful hat.
    Unit 20 (AI widgets — suggestion and anomaly detection) is **removed from scope**
    (2026-09-02). What was a deferred unit is now a property of the system: **document
    verification, expert selection, drafting, draft review, client approval, expert
    reassignment and quality control are human judgements**, every one of them.

    **What is still allowed is everything that is not a judgement:** notifications, stage
    transitions a human triggers, timestamps, versioning, audit logging, and the
    arithmetic behind a dashboard.

    **The one thing that looks like an exception and is not:** Unit 12's match engine
    ranks experts on four declared, inspectable factors. It is arithmetic over a roster,
    it never auto-assigns, and the full picker sits underneath so a PM can ignore it
    entirely. **It contains no model.** A proposal to make that ranking "smarter" by
    asking something to reason about a case is this invariant, and it must be reversed in
    writing before any such code exists.

    This is the constraint most likely to be eroded by a plausible-sounding convenience:
    a "suggested strategy" or a "draft summary" is a production decision wearing a
    helpful hat.
