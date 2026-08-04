# EvalOS — Core

Back-of-house production CRM for a **multi-brand** credential-evaluation business (International
Evaluations, XpertsPortal). Takes custody at **`contact.created`** in GoHighLevel (GHL) and owns the
case to signed delivery + expert payout. GHL stays front-of-house (leads, sales, invoicing, review
campaigns); **EvalOS never does marketing, sales, or invoicing.**

The custody trigger moved off payment in Unit 05a: a case now exists **before** money does, unpaid,
and payment is a fact recorded on it. Anything written against "the webhook proves payment" is
pre-05a and wrong.

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

**Current state: Phase 1 (Units 01–10 + 05a) is complete and verified, and Phase 2 has started.**
Scaffold + response envelope, the tenancy/auth/RBAC spine, the domain schema, the case state machine
+ SLA calendar, the inbound webhook gateway with Handoff A, the in-app notification centre, and five
live frontend surfaces (app shell + role routing, production Kanban board, case detail + timeline,
document checklist board, expert database). CI runs the DB suite against a real Postgres on every
push.

**Phase 2 is Units 11–17 and is under way. Units 11 (expert database + sheet upload), 12 (match
scoring engine) and 14 (client draft-review portal) are complete and verified. Unit 13 (redacted CV
generation + the Drive write) is code-complete with ONE acceptance criterion outstanding — the manual
live upload, blocked on a Google service account that does not exist. Unit 15 is next.** Migrations
run to **`V23`** (Unit 13 added none — nothing it produces is persisted; Unit 14 added three, and its
code review added `V23`); **343 backend tests, none skipped** (26 DB-backed) and 101 frontend tests.
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

Later units carry named external dependencies that do not exist yet (Dropbox Sign account for 15,
GHL outbound contract for 18) — all listed in the tracker. **Unit 13's Google service account is
the one that has already bitten**: the code is finished and the live upload is not, so the unit is
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
  never only in the UI. GM is the only cross-brand role. **One exception, added in Unit 11 and not
  a scope:** `POST /api/experts` and the two import endpoints take an optional `brandId` naming
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
  Dropbox Sign, staff alerts are in-app, client messages go out through GHL. Do not add S3 or SMTP.
  (Unit 13 **added** that Drive API client, for one write path — links-only stopped being the whole
  story, and EvalOS still hosts no bytes: the redacted profile is generated in memory, streamed to
  the caller or handed to Drive, and written to neither Postgres nor disk. The client is
  deliberately the narrowest capability that works: one file into a folder that already exists,
  no folder creation, no permissions management, no reads.) Unit 11 added the one **upload** — the expert
  roster sheet — and it holds too: parsed in memory, never stored, with
  `multipart.file-size-threshold` set equal to `max-file-size` so the container cannot spool it to a
  temp file.
- **A case is created only by a per-brand GHL webhook endpoint** — no other path, enforced
  structurally by `DomainInvariantsTest` (only the contact handler may depend on `CaseIntakeService`,
  so adding a `POST /api/cases` breaks the build). Marking one **paid** is a separate staff act.
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
