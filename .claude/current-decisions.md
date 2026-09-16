# EvalOS — Current Decisions

Confirmed and in force as of **2026-09-17**. Short and explicit. No history, no abandoned
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
- **D3d.** **Sign-up creates no GHL contact — D3a's ordering, restored.** The contact lived on
  `/auth/sign-up` for exactly as long as GHL carried the set-password mail, which required a
  `contactId` and would not take a bare address. Brevo takes the address, so the reason is gone and
  the exposure is not worth keeping: that route is `permitAll` behind one per-IP counter, so a CRM
  write there is a stranger's write — an IP-rotating script fills the sub-account Sales works in
  and spends GHL's shared 100-req/10s location budget, which 502s every GHL-backed staff screen.
  **Two call sites had to go, not one:** `signUp` itself, and `issueCredential` — because `signUp`
  falls through to `identify`, so removing only the first would have looked fixed and changed
  nothing. `signingUpReachesGhlZeroTimes` pins both. The contact is created at `setPassword`, at
  the next sign-in, or at the first request that needs one (D3c). Spec `52` §11.
- **D3e.** **Mail is a transport behind an interface** (`MailTransport`), chosen by
  `evalos.mail.transport` from the beans on the classpath — **`smtp` or `brevo`**
  (`POST /v3/smtp/email`, header `api-key`). The `ghl` transport existed for one day and is
  deleted; swapping it for Brevo was one new class and one changed variable, which is the seam
  paying for itself. A name matching nothing **fails at startup** and names what it found; falling
  back silently would be found by a client who never got their link. `Recipient` no longer carries
  a GHL contact id — nothing addresses a person that way any more — and `ClientMailer` owns both
  the wording and the one `PORTAL_LINK_ISSUED` audit row, so the trail is not a property of
  whichever provider happens to be carrying the mail.
- **D4.** Signup never signs anyone in. It returns a state; control of the mailbox is proved by
  the set-password link. Signing up with a known email creates nothing and cannot overwrite.
- **D5.** Sign-in creates no contact and no account.
- **D6.** One GHL Contact, many opportunities. A repeat request creates a **new opportunity on the
  existing contact**, never a second contact.
- **D41.** **One id represents a contact everywhere in the system, and it is the GHL
  `contact_id`.** Decided 2026-09-17, for client-id consistency across EvalOS, GHL and anything
  built on either. Where a contact is _named_ — an S3 prefix, a route parameter, a payload field —
  it is named by GHL's id, so the same client resolves in both systems with no mapping table.
  **Database primary keys are untouched:** D18 stands, EvalOS still mints its own UUID and keeps
  `ghl_contact_id` beside it, and `client_account.contact_id` / `evalos_case.contact_id` stay real
  foreign keys to `contact_snapshot`. An internal join is not a name.
  **This reverses the 2026-09-14 narrowing** that moved `DocumentStore.clientKey` onto
  `contact_snapshot.id` after IE's sub-account swap left post-swap clients with no GHL id to build
  a key from. What makes the id safe to name again is D3d and D3c: the contact is created at
  set-password, at the next sign-in, or at the first request that needs one, and a document is
  uploaded at questionnaire submit, which already ensures the id before it opens the deal.
  **Two consequences stated rather than hidden:** a contact with no GHL id yet is _refused_ with a
  message naming the repair instead of being filed under a guessed prefix, and a second sub-account
  swap would orphan the namespace again — reads resolve through the stored `object_key`, so nothing
  breaks retroactively, but new writes would land beside the old ones.
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
  answers `null` + 200 for deals that did not come from the portal. **The documents ride on that
  same read** (D34) — one opportunity, one screen, both halves of what the client sent.

- **D33.** **Documents arrive with the request, at questionnaire submit, and are stored against the
  contact** — the person — not against a case, which does not exist yet. This is the DOCUMENT
  SUBMISSION step the target lifecycle always named and Unit 43 deferred, because every upload
  route EvalOS had took a checklist item on a **case**. Decided 2026-09-17.
  **"Contact" means the GHL contact id** (D41). The request upload reuses `DocumentStore.clientKey`
  unchanged in shape — `{brand}/client/{ghl_contact_id}/{doc}` — and the upload set carries forward
  into `case_document` at Handoff A as a row insert over the **same S3 object**: nothing copies,
  nothing re-keys, and Production starts holding exactly what Sales read.
  Spec `53-request-documents.md`. _(Closes `open-decisions.md` Q4.)_
- **D34.** **Sales clicks one opportunity and sees both** the questionnaire answers and the
  documents. The documents are **a second screen on that same deal, never a second permission** —
  their own route and their own tab beside the application, reached by whoever could already open
  the opportunity. Nothing about them asks a new authorisation question.
- **D35.** **There is no EvalOS sales-review state.** `client_application.status` stays `DRAFT` /
  `SUBMITTED`. Review, approval and rejection are GHL **pipeline stages** — D10c said that of the
  funnel and it now settles the request row too. The business named the shorter flow on 2026-09-17:
  submitted → Sales reads → won → case. _(Closes Q2 and Q3, which both recommended adding states.)_
- **D36.** **The case is staffed PM-first, and the PM staffs the rest.** Handoff A creates the case,
  a PM takes it, and the PM assigns the **Project Coordinator**, the **Case Manager** and the
  **Expert**. The CM drafts and uploads; the client sees and approves it in the portal; only then
  does it reach the expert, who downloads, signs and uploads it back. `CaseLifecycleService` and
  `CaseTransitions` already do exactly this — it is recorded here because it is a stated business
  rule now, not an implementation detail that happened to be convenient.

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
- **D39.** **A mirror webhook is a trigger, not a payload** (45d, 2026-09-17). GHL's Custom Webhook
  action posts the _contact record_ flat — no stage, no status, no value — and everything else in
  the body is `customData`, hand-typed by whoever edited the workflow last. So an
  `opportunity.*` event supplies one fact, the contact id, and the mirror reads the field values
  back from GHL. A contact event is the one exception, because there the payload **is** the entity.
  Every spelling of an opportunity change routes to one handler; **`opportunity.won` never joins
  them** — that is Handoff A and it creates a case (invariant 8).
- **D40.** **"Delta sweep" means a stale pipeline, never a changed row**, and the API decides that:
  GHL's opportunity search filters on `createdAt` and has **no updated-since filter at all**. There
  is no read that returns only what changed. `MIRROR_DELTA` asks `refreshIfStale` of every live
  mirrored pipeline, so it costs only the pipelines nobody is reading — which are the ones the
  nightly audit would otherwise report as drift when the truth is that nobody looked.
- **D42.** **Ownership of a mirrored field is per field, in code** (45e, 2026-09-17), never the
  blanket "EvalOS wins" `00c` §4b proposed — that rule reverts GHL automations, which is the one
  thing GHL was kept for, and a round-robin reassigning a deal is not a conflict to undo. GHL owns
  the **assignee** and the **pipeline** (and anything unclassified, deliberately);
  `stage`/`status`/`amount`/`name` are **shared**, where EvalOS wins **and the conflict is
  reported**; `opportunity_note` is EvalOS's and never synced. **"EvalOS wins" means one narrow
  thing** — the mirror keeps a shared field only while `local_updated_at` says EvalOS holds an edit
  GHL has not confirmed, and that flag is cleared the moment GHL's answer supersedes it or GHL
  acknowledges the create. **A null `ghl_updated_at` is a conflict, never "GHL is newer"** — but
  only when there is an edit to defend, or the rule degrades into the blanket one it replaced.
  **Nothing is pushed to GHL by this**: correcting GHL is Unit 46's, through the outbox.
- **D43.** **A drift row is never resolved by a human pressing a button.** Clearing it clears the
  symptom while the two systems still disagree. `GET /api/sync/drift` instead answers *will this fix
  itself*: every row carries `owner` and `resolution` (`GHL_WINS` / `EVALOS_WINS` /
  `NEEDS_A_HUMAN`), derived at read time and never stored, and the envelope carries `needsAHuman`.
  **Only a row GHL no longer returns needs a person** — re-creating a lost deal, or deleting the
  mirror's copy, is a business decision a sweep must not take.
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
  screen's brand _is_ provable and SALES/MARKETING reach it.
- **D19b.** A SALES/MARKETING member holds a **set** of GHL pipelines (`team_member_pipeline`,
  Unit 44b), not one. The one-owner rule is retired: Case Delivery is a pipeline nobody owns.
  Assignment is by **mirror id**, never a pasted GHL string — that is `00d` C4 closed structurally.
  The set is read at sign-in and carried in the token, so a reassignment takes effect on next
  sign-in; unchanged in kind from the single-claim model it replaced.
- **D19c.** **Sales reads their own pipelines and nothing else, and their world ends at won.**
  A SALES member reads the opportunities and requests on the pipelines they hold (D19b) and **no
  case at all**. `ScopePredicate`'s PIPELINE arm matching no `evalos_case` row is therefore
  **correct**, not the gap `implementation-status.md` called it until 2026-09-17.
  _(Closes `00d` §12a, which asked how much of a case SALES gets: none.)_
- **D20.** Eight staff roles with ABAC tiers: `GM`(ALL), `BRAND_MANAGER`(BRAND),
  `PROJECT_MANAGER`(TEAM), `PROJECT_COORDINATOR`/`CASE_MANAGER`(SELF),
  `EXPERT_NETWORK_MANAGER`(SUPPLY), `SALES`/`MARKETING`(PIPELINE).
- **D21.** Sales and Marketing are roles; attorney/employer/individual are `team_member.segment`,
  not roles — identical permissions.
- **D22.** Two Spring Security chains: `/api/portal/**` on an opaque portal token (order 1),
  everything else on staff JWT (order 2).
- **D23.** Clients authenticate with email + password. **Experts do not have accounts** — an expert
  reaches the portal only through a staff-minted link. Whether they get accounts is a **stakeholder
  decision not yet taken** (Q6, re-gated 2026-09-17); until it is, the minted link is the whole of
  expert access and nothing should be built assuming otherwise.

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

## Notifications

- **D37.** **Notifications are in-app and push — both, and only those two.** The `notification`
  table and the bell stay the record of what happened; web push is added beside them so a user
  who is not on the screen still hears about it. **No email and no SMS**, so invariant 14 is
  untouched: a push is not a message to a mailbox. Decided 2026-09-17.

## Other

- **D30.** No AI in the system at all; no AI makes a production decision.
- **D31.** Controllers stay thin. Long-lived work is a `@Scheduled` sweep with a DB lock and a
  `scheduled_job` ledger row.
- **D38.** **Deploying and wiring the Client and Expert Portals is DevOps's, outside this
  repository.** No Dockerfile, compose service or CI job is owed here for either, and their
  absence stops being a gap in the status table. Decided 2026-09-17. _(Closes Q7.)_

## Resolved 2026-09-16

- **D32.** `client_account` and `contact_snapshot` stay **two tables, joined** — not merged.
  `V55` adds `client_account.contact_id`, a real foreign key, backfilled on `ghl_contact_id`
  within the brand and set at sign-up; null stays legal. **D6 is now enforced by the schema**, by
  a partial unique index, where it previously was not — `ghl_contact_id` was nullable and not
  unique, so nothing stopped two accounts naming one contact. _(This closes `open-decisions.md`
  Q5, which recommended the merge.)_
  **The `contact_snapshot` → `contact` rename is deferred and the reason is mechanical:** two
  seeds write that table (`V905` local, `V951` testprod), both numbered 900+, both running after
  every `db/migration` script — and `MigrationTreeTest` forbids a migration in that range while
  editing an applied seed is a checksum mismatch that refuses the boot. A rename has nowhere to
  sit. Do it in the change that rebaselines the seed tree. `contact_snapshot` **is** the mirror's
  contact table until then, and nothing about that is wrong except its name.
