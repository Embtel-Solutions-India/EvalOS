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

**Current state: Phase 1 (Units 01–10 + 05a) is complete and verified.** Scaffold + response
envelope, the tenancy/auth/RBAC spine, the domain schema, the case state machine + SLA calendar, the
inbound webhook gateway with Handoff A, the in-app notification centre, and four live frontend
surfaces (app shell + role routing, production Kanban board, case detail + timeline, document
checklist board). Migrations run to **`V17`**; ~44 endpoints across 10 controllers. 183 backend tests
run with **none skipped** (the DB-backed ones included) and 44 frontend tests; CI runs the DB suite
against a real Postgres on every push.

**Phase 2 is Units 11–17 and has not started.** Next is Unit 11 (expert database + sheet upload).
Phase boundaries are 01–10 / 11–17 / 18–20 — earlier tracker entries mislabelled 06 onward as Phase 2
and were corrected. Unit 11 is gated on one thing: the **`FieldTag` value list needs the ENM's
sign-off** before its migration lands (the mechanism — a closed enum + DB CHECK — is already decided).
Later units carry named external dependencies that do not exist yet (Google Drive service account for
13, Dropbox Sign account for 15, GHL outbound contract for 18) — all listed in the tracker.

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
  never only in the UI. GM is the only cross-brand role.
- **Append-only truth.** Audit + assignment history are never updated or deleted; no update/delete
  path may exist on those repositories. This has a consequence worth knowing before you hit it:
  `audit_event` rows **can never be backfilled** — see `mem:backend/persistence`.
- **Flyway owns the schema.** `ddl-auto: validate`. Every change is a new migration; an applied
  migration is never edited.
- **No object storage, no mail server.** Documents are Google Drive links, signed letters live in
  Dropbox Sign, staff alerts are in-app, client messages go out through GHL. Do not add S3 or SMTP.
  (Phase 2 adds a Drive **API client** for one write path in Unit 13 — links-only stops being the
  whole story there, but EvalOS still hosts no bytes.)
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
