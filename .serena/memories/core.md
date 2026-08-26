# EvalOS — Core

Back-of-house production CRM for a **multi-brand** credential-evaluation business (International
Evaluations, XpertsPortal). Takes custody at **`opportunity.won`** in GoHighLevel (GHL) and owns the
case to signed delivery + expert payout. GHL stays front-of-house (leads, sales, invoicing, review
campaigns); **EvalOS never does marketing, sales, or invoicing.**

**"Never does" means never *runs* — Units 24 and 26 draw the line and are the only things on the
other side of it.** EvalOS *reads* two GHL funnels onto GM screens — the **Google ADS Pipeline**
(Unit 24) and **Shivangi's Email Marketing** (Unit 26), both in the one configured location — and
owns neither: no lead created, no stage moved, no campaign sent, no write back, and **nothing
persisted** — there is no `ghl_opportunity` table and there must not be, because a stage a
salesperson dragged five seconds ago is already wrong in a copy. This resolves a question open
since Unit 17 (whether EvalOS builds the sales/marketing dashboards) as **read-only GM views over
pipelines in that location, everything else in GHL**.

Unit 24 said a second marketing screen would be a new question; Unit 26 asked it and the answer is
**yes for another *reading* of a pipeline in the same location, on identical terms**. Read that
narrowly too: a marketing *module* — anything that creates, sends, prices or attributes — is still
a different question, and its default is still no.

The custody trigger has moved twice. **Case Creation v2.0 (spec `05b`) is current: the webhook *does*
prove payment.** GHL invoices and collects before an opportunity is marked Won, so that one event is
both the reason the case exists and the record that it was paid — the case is created **paid**, and no
staff action sets `paid`. Unit 05a's rule (`contact.created`, an unpaid case, payment recorded by hand
afterwards) is history; so is the note that used to sit here saying "the webhook proves payment" is
wrong. In v2.0 it is right.

**Custody symmetry — GHL owns pipelines, EvalOS owns what is real.** A case at
`opportunity.won`; an expert when the ENM adds them to the roster; retention never. This is why there
is **no expert-recruitment pipeline** here — a prospect moving through Identified → Contacted →
Agreement Sent is the same object as a sales opportunity, and GHL already runs pipelines. So
`expert.agreement_status` is GHL's fact and has no writer on purpose; if it ever needs to be live the
shape is an inbound `expert.agreement_signed` mirroring `opportunity.won`.

**`context/process-automation.md` is the trigger→recipient map.** Every A-numbered automation from the
CRM build spec, what event it publishes, who hears it, the owning unit, and whether it is built. Read
it before adding a notification or a timer — and update it in the same step as the code, because
moving a row from *gap* to *built* is part of the unit that built it.

## Spec-driven build — read before coding

This repo is built unit-by-unit against written specs, not improvised. `CLAUDE.md` is the entry
point; `context/` holds the working build context (`project-overview`, `architecture`, `ui-context`,
`code-standards`, `ai-workflow-rules`, `progress-tracker`). The authoritative design is the **EvalOS
Technical Design Document v1.1**; where a context file conflicts with it, v1.1 wins.

- Ordered unit list: **`context/specs/00-build-plan.md`** (20 units, 3 phases). An earlier version of
  this memory claimed it sits at `context/` root and that `CLAUDE.md` was wrong about it — that was
  itself wrong; `CLAUDE.md` has always been right.
- Unit specs are `context/specs/NN-name.md`. **01–10 were generated just before each unit was built;
  11–20 were written in one pass at the start of Phase 2.** The just-in-time rule stands as the
  default, so 11–20 are **drafts to re-read and revise at the start of their own unit**, not settled
  contracts (18–20 say so in their own headers). Writing them ahead already produced four
  corrections to earlier assumptions, which is the cost the rule exists to avoid.
- Do not invent product behavior absent from the context files — add an open question to
  `context/progress-tracker.md` instead. That file is also where **decisions and their costs** are
  recorded (Phase 2 readiness section + Open Questions); read it rather than re-deriving why
  something was chosen.
- Update `context/progress-tracker.md` after every meaningful change; update the relevant context
  file (and the TDD) if a decision changes.

**Current state: Phase 1 (Units 01–10, with 05a since re-pointed by 05b — see the trigger
note above) is complete and verified, and Phase 2 has started.**
Scaffold + response envelope, the tenancy/auth/RBAC spine, the domain schema, the case state machine
+ SLA calendar, the inbound webhook gateway with Handoff A, the in-app notification centre, and five
live frontend surfaces (app shell + role routing, production Kanban board, case detail + notes/timeline,
document checklist board, expert database). CI runs the DB suite against a real Postgres on every
push.

**Phase 2 is Units 11–17 and is under way. Units 11 (expert database + sheet upload), 12 (match
scoring engine), 14 (client draft-review portal) and 05b (Case Creation v2.0) are complete and
verified. Unit 13 (redacted CV generation + the Drive write) is code-complete with ONE acceptance
criterion outstanding — the manual live upload, blocked on a Google service account that does not
exist. Unit 16 (payout ledger) is next — not 15, which waits on Unit 21 and on that same Google
account; the schedule is `00-build-plan.md`'s "Execution sequence for v2.0", and it differs from the
numbering on purpose.** Migrations run to **`V24`** (Unit 13 added none — nothing it produces is
persisted; Unit 14 added three, its code review added `V23`, and Unit 05b added `V24`);
**406 backend tests** (27 DB-backed, skipped without a local Postgres) and 111 frontend tests.
**Unit 23 made the Project Manager the front door and gave the case a conversation.** The GM lost the
board's pool lane and the `/inbox` + `/checklists` nav entries (**nav only — no backend gate was
narrowed**); `assign-pm` now admits the PM, who claims a pooled case from their inbox. A PM can read
a pooled case because `ScopePredicate.Fields.unteamedVisible` is set on cases and nowhere else
(`mem:backend/security`). **Case notes are audit rows, not a table** — `NOTE_ADDED`, written with no
`@PreAuthorize` because the scoped load is the gate; see `mem:backend/lifecycle` and
`mem:backend/persistence`. `Timeline` is now *Notes & timeline* (`mem:frontend/core`). Spec:
`context/specs/23-case-notes-and-pm-routing.md`.

**Unit 25 is specced and NOT built. It is UNSCHEDULED, not blocked — the one decision it waited on
was signed off 2026-08-26.** It replaces Unit 24's hand-pasted Private Integration Token with a
**per-brand OAuth grant**, turning the GHL credential from global config into a brand-scoped row —
the move `architecture.md` already anticipated. A refresh token must be *recoverable* (we replay it
to GHL) so it cannot be hashed like a portal token, which makes it **EvalOS's second encrypted
column** against `code-standards.md`'s former "only encrypted field".

**The approved way to add it (option 1 of four): extract the AES-GCM from `PaymentDetailConverter`
into one `common/EncryptedStringConverter`, leaving `PaymentDetailConverter` a thin subclass.** That
is a **named, narrow exception** to the protected-file rule — that extraction only, expert-path
behaviour unchanged (same key, AES-256-GCM, fresh 12-byte IV per write, authenticated failure on a
tampered column). Every other change to that file still needs its own sign-off. **Do not write the
extraction until Unit 25 is actually built**: a shared abstraction with one implementation is what
this codebase deletes. The rule that did not move — a credential that never has to be replayed is
**hashed, not encrypted** (portal tokens); encryption is only for what must be recovered. Two things to know before touching it: **GHL rotates the refresh token on every
refresh**, so refresh must hold `SELECT … FOR UPDATE` on the row and re-read after acquiring the
lock or two rolling-deploy instances will retire each other's grant; and **the PIT is deleted rather
than kept as a fallback**, which was free while no deployment used the PIT — note the live
*client* test now runs against it (read-only), but no environment serves the screen from it yet, so
the window is still open. It closes the day a deployment sets `GHL_API_TOKEN` for real. Spec:
`context/specs/25-ghl-oauth-connection.md`. Its follow-on **25a** re-scopes the funnel (brandId
legal, Brand Manager admitted, invariant 1's exception removed) and is deliberately a separate unit.

**Unit 24 added the first *pull* across the GHL seam.** Until it, that seam was events in
(Handoff A) and events out (Handoff C); `GhlPipelineClient` is a third direction and the only one
that is not a handoff — two read calls on an `opportunities.readonly` token, **no write method on
the client at all**. Three things to know before touching it: the **cache is the rate limiter, not
a speed-up** (without it N open dashboards are N multi-page GHL reads per refresh, and a failed
refresh is deliberately **never** served from the previous value, so the screen shows the error
rather than a stale figure presented as live); **`status` is deliberately not read**, because
opportunities sitting in the *Won* stage still report `status: "open"` and two disagreeing axes is
two places for one fact to be wrong; and **no stage name is special-cased anywhere** — order,
labels and membership all come from GHL, so a rename there is not a silent hole. Config is
`evalos.ghl.*`, and `GHL_API_TOKEN` / `GHL_LOCATION_ID` default to empty on purpose: a missing
token gates one read-only screen (502) rather than failing the boot the way `JWT_SECRET` must.
Spec: `context/specs/24-marketing-google-ads-funnel.md` — whose header records that **it was
written after the code**, which is the wrong order for a change that resolves an open question.
**24 tests over four classes**, including the client driven against a real JDK `HttpServer` serving
GHL's captured response shapes (`GhlPipelineClientHttpTest`) — so header names, the camelCase query
params and the pagination cursor are proven, not assumed.

**The live run from inside the app is DONE (2026-08-26).** `GhlPipelineClientLiveTest` — opt-in on
`GHL_LIVE_TEST=true`, reading the token from the gitignored `backend/config/application-local.yml`
so no credential reaches a command line — calls the real API and passes. Observed: `Google ADS
Pipeline` id `g6lo50r9Wn0qZvmp2bMP`, `Shivangi's Email Marketing` id `LHoIRjpypwhswqO8Ayn0`, both
six stages; the email funnel counted **11,417** over `2025-08-27..2026-08-26` from `meta.total`
alone; the ads pipeline returned **0 rows in the last 30 days**. **The old "expected first load of
93 deals (New Lead 7 / Warm 26 / Won 14)" was a hand check, never a live observation, and is
stale — do not use it as an expected result.** What is still owed is narrower: nobody has opened
the *screen* in a browser against live GHL, so the poll-until-`READY` handover has not been watched
with real figures.

Unit 05b re-pointed Handoff A to `opportunity.won` and **deleted the manual payment path** — details
in `mem:backend/webhooks` and `mem:backend/lifecycle`. Its live hand-fired run is still owed, blocked
on confirmation of what GHL actually sends on Won.
Unit 11 added the closed
`FieldTag`/`LetterType` vocabularies (enum **and** DB CHECK), `email`/`phone`/`letter_types`/
`standard_fee` on `expert`, the write-only `payment_detail` path, `ExpertLoadService` (load derived
from `evalos_case`, never from the dead `V7` counters), the CSV+XLSX roster import, and the
`/experts` screen — details in `mem:backend/persistence` and the tracker's Unit 11 entry.
**The `FieldTag` values shipped WITHOUT the ENM's sign-off**, on instruction: still an open
question, and widening the list now means a new migration widening `V18`'s CHECK plus the enum plus
`frontend/src/features/experts/expertRules.ts`, moved together.
Unit 12 ranks that roster for the PM at assignment. It added `V19__expert_case_offer` +
`ExpertCaseOffer`/`OfferOutcome` — **the only queryable record of an accept/decline**, so acceptance
rate is computed from it and never from `expert.performance_flags` (a flag, not a rate),
`evalos_case.expert_id` (overwritten by `reassignExpert`), or an audit `before_snapshot` blob —
and `ExpertMatchService`, whose four factors are **one weighted table** (field 40 / letter-type 25 /
acceptance 20 / load 15) so a reweighting is a data diff; the score is the sum of the rounded parts,
so the breakdown shown to the PM adds up by construction. Two consequences that bite:
`fieldTag` is a **required query parameter** because no case column records a case's field —
recorded as a deliberate omission, not an oversight; and an expert below 3 resolved offers scores
**the roster mean, not zero**, because last place is what stops a newcomer ever getting a record.
Offer invariants are in `mem:backend/persistence`; which transitions stamp them, in
`mem:backend/lifecycle`.
Phase boundaries are 01–10 / 11–17 / 18–20 — earlier tracker entries mislabelled 06 onward as Phase 2
and were corrected.
Unit 13 generates the anonymous expert profile a client approves the expert from, and files it into
the case's Drive folder. Three things to know before touching it: redaction is a **whitelist** in
`RedactedProfileService.credentials` (a blacklist is how a field added later leaks by default, and
the test proves it by seeding tokens in every excluded field and searching the output); the
`Expert AK` reference label is a digest of the **case and expert ids together**, so it is stable per
case and different for the same expert on another case; and an unparseable `drive_link` is a
**refusal, never a fallback** to a default folder — a misfiled document is a cross-brand leak
outside the database. `mem:backend/core` for the config and the 502 path.

Unit 14 gave the client their own surface, and it is the first non-staff caller in the system. Four
things to know before touching it. **Two filter chains, neither accepting the other's credential** —
`PortalSecurityConfig` matches `/api/portal/**` and holds no JWT filter, and its `PortalTokenFilter` is
constructed rather than annotated so Boot cannot register it globally (that is the detail that would
otherwise let a portal token authenticate a staff route). **A portal caller is not a `TenantContext`**:
`PortalPrincipal` carries the one case the token names, so the token *is* the scope and
`ScopePredicate` is not involved — see `mem:backend/security`. **A portal link is a credential**: 256
random bits, returned once, stored only hashed, absolute expiry, and re-minting revokes the previous
one; unknown/expired/revoked are one indistinguishable 401. And **`audit_event` grew its first new
column ever** (`actor_type`, on explicit instruction, nullable and unbackfillable) so a client's
approval is attributed to the client rather than to a null that reads as the system —
`mem:backend/persistence`. Handoff B is now something a client can perform. The link still has to be
**copied out by staff**: whether GHL can deliver it on an event is open question (b), and Unit 18 owns
the dispatch if the answer is yes.

Its **code review found five real things and none of them were in the scoping, the whitelist, the two
chains or append-only** — three were comments describing code that had changed under them (two of
them saying the portal mounts from `main.tsx`, which it does not), one was `recordEvent` hardcoding
`ActorType.STAFF` where its own contract allows a null actor, and one was the mint being a
check-then-act, fixed with `V23`'s index. The lesson to carry: on this codebase the comments *are* the
contract, so a design decision reversed mid-unit has to be chased through every place that describes
it — the tracker, the memory, the context file and the javadoc.

Later units carry named external dependencies that do not exist yet (the GHL outbound contract for
18) — all listed in the tracker. **Unit 13's Google service account is
the one that has already bitten**, and it now blocks three units rather than one: 13's own live
upload, Unit 21's client document upload, and Unit 15's signed-letter upload. Unit 15 used to be
gated on a Dropbox Sign account; there is no signature provider any more. Here, the code is finished
and the live upload is not, so the unit is
open. Until it runs, three things are proven only against a test double — that the credentials
work, that the `drive.file` scope suffices for a create into a shared folder, and that Drive's
HTML → Doc conversion is worth sending to a client.

## Layout

Monorepo, but no root build: each half is built and run from **inside its own directory**.

- `backend/` — Spring Boot 3.5 / Java 21 Maven project, base package `com.ie.evalos`.
  `mem:backend/core` for package boundaries, config profiles, Flyway ownership, the response
  envelope.
- `frontend/` — Vite + React 19 + TS SPA. `mem:frontend/core` for routing, the HTTP layer, and the
  design-token styling system.
- `context/` — the specs and design docs above. `README.md` — local run + verify steps.
- `.github/workflows/ci.yml` — the only CI. Note it runs **`npm install`, not `npm ci`**: the
  lockfile is written on Windows and records wasm-fallback bindings without their `@emnapi/*` deps,
  which `npm ci` rejects on Linux. Cost is that CI resolves within semver ranges instead of pinning;
  regenerating the lockfile once on Linux restores `npm ci`. Reason is written into `ci.yml`.
- No root `package.json`, workspace tool, or Docker compose.

## Project-wide invariants (override convenience everywhere)

- **Brand-scoped by default.** Every scoped query filters by `brand_id` (plus team/assignee where
  applicable). A query without brand scoping is a defect, enforced at the repository/service layer —
  never only in the UI. GM is the only cross-brand role. **Read the rule as: every query over
  EvalOS rows.** Unit 24's marketing funnel is the one screen that is not scoped and it queries no
  EvalOS rows at all — it reads a GHL location the brands share, so no `brand_id` predicate exists
  that could narrow it. That is why it is **GM-only and accepts no `brandId`**: a parameter there
  would narrow nothing while implying it had. The **Brand Manager is deliberately excluded** —
  single-brand everywhere else, and this is the one figure that could not honour it;
  `navigation.test.ts` pins the GM-only list so adding them fails a test. If the brands are ever
  split across two GHL locations, `location-id` becomes a column on `brand` and the exception
  closes. It licenses nothing about unscoped queries over EvalOS rows. **A second exception, added
  in Unit 11 and not a scope:** `POST /api/experts` and the two import endpoints take an optional `brandId` naming
  *where a new row goes*, because a GM has no brand of their own and this is the first unit where
  staff create a scoped row. `OwnershipGuard.assertCanAct` decides whether the caller may act there,
  so a brand-locked role naming another brand gets a 403. Reads never take brand from a request; a
  `brandId` on a read can only narrow.
- **Append-only truth.** Audit + assignment history are never updated or deleted; no update/delete
  path may exist on those repositories. This has a consequence worth knowing before you hit it:
  `audit_event` rows **can never be backfilled** — see `mem:backend/persistence`. Unit 14 is the first
  unit to have felt it, and also the precedent for touching that table at all: the entity and its write
  path are **protected files**, so its one new column was signed off before it was written, not argued
  for afterwards.
- **Flyway owns the schema.** `ddl-auto: validate`. Every change is a new migration; an applied
  migration is never edited.
- **No object storage, no mail server.** Documents are Google Drive links, signed letters live in
  the case's own Drive folder (the expert uploads it there), staff alerts are in-app, client messages
  go out through GHL, and an expert is reached by a scoped portal link. Do not add S3 or SMTP.
  (Unit 13 **added** that Drive API client, for one write path — links-only stopped being the whole
  story, and EvalOS still hosts no bytes: the redacted profile is generated in memory, streamed to
  the caller or handed to Drive, and written to neither Postgres nor disk. The client is
  deliberately the narrowest capability that works: one file into a folder that already exists,
  no folder creation, no permissions management, no reads.) Unit 11 added the one **upload** — the expert
  roster sheet — and it holds too: parsed in memory, never stored, with
  `multipart.file-size-threshold` set equal to `max-file-size` so the container cannot spool it to a
  temp file. **Unit 21 is the third and must use the same two mechanisms**: that threshold setting,
  plus `InputStreamContent` into Drive rather than a byte array — a client document streams through
  and EvalOS keeps only the Drive file id. "Hosts no files" means **stores none, not accepts none**;
  three units now accept bytes and none stores them.
  Sending email is still forbidden, but that rule is now **under review** — every client/expert
  touchpoint and the open GHL-vs-EvalOS-mail decision are in `context/process-automation.md`. Until it
  is decided, adding a mail dependency is still wrong.
- **A case is created only by a per-brand GHL webhook endpoint, from a won opportunity** — no other
  path and no other event, enforced structurally by `DomainInvariantsTest` (only
  `GhlOpportunityHandler` may depend on `CaseIntakeService`, so adding a `POST /api/cases` breaks the
  build). The case is created **paid**; no staff action sets `paid`.
- **Unpaid work stops at `DOC_COLLECTION`.** Revenue is recognized only when paid **and** delivered.
  Both live in `mem:backend/lifecycle`.
- **A client-offered link/action must be checked against the reader's allow-list.** Four separate
  defects have been one bug: a screen or escape hatch linked without `mayReach`. `navigation.ts` is
  one table for nav + router + allow-list, and `boardPathFor(role)` walks it. Grep before adding any
  cross-screen link.
- Module contract is HTTP under `/api`, same-origin in dev via the Vite proxy; no CORS config exists
  on either side — add endpoints under `/api` rather than introducing CORS.

Cross-cutting refs: `mem:tech_stack`, `mem:suggested_commands`, `mem:conventions`,
`mem:task_completion`.
