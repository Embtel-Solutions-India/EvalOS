# EvalOS — Core

Back-of-house production CRM for a **multi-brand** credential-evaluation business (International
Evaluations, XpertsPortal). Takes custody of a case at "payment confirmed" in GoHighLevel (GHL) and
owns it to signed delivery + expert payout. GHL stays front-of-house (leads, sales, invoicing,
review campaigns); **EvalOS never does marketing, sales, or invoicing.**

## Spec-driven build — read before coding

This repo is built unit-by-unit against written specs, not improvised. `CLAUDE.md` is the entry
point; `context/` holds the working build context (`project-overview`, `architecture`, `ui-context`,
`code-standards`, `ai-workflow-rules`, `progress-tracker`). The authoritative design is the **EvalOS
Technical Design Document v1.1**; where a context file conflicts with it, v1.1 wins.

- Ordered unit list: `context/00-build-plan.md` — note it sits at `context/` root, **not** in
  `specs/`, despite what `CLAUDE.md` and the tracker say (20 units, 3 phases). Individual specs
  `context/specs/NN-name.md` are generated **just before** each unit is built.
- Do not invent product behavior absent from the context files — add an open question to
  `context/progress-tracker.md` instead.
- Update `context/progress-tracker.md` after every meaningful change; update the relevant context
  file (and the TDD) if a decision changes.
- **Current state:** Units 01–03 done — scaffold + envelope, the tenancy/auth/RBAC spine, and the
  full domain schema (`V1`–`V10`, all entities, repositories, audit, field encryption). Unit 04 (case
  state machine + SLA computation) is next. No endpoints exist yet beyond auth/health/team-members.

## Layout

Monorepo, but no root build: each half is built and run from **inside its own directory**.

- `backend/` — Spring Boot 3.5 / Java 21 Maven project, base package `com.ie.evalos`.
  `mem:backend/core` for package boundaries, config profiles, Flyway ownership, the response
  envelope.
- `frontend/` — Vite + React 19 + TS SPA. `mem:frontend/core` for routing, the HTTP layer, and the
  design-token styling system.
- `context/` — the specs and design docs above. `README.md` — local run + verify steps.
- No root `package.json`, workspace tool, CI config, or Docker compose.

## Project-wide invariants (override convenience everywhere)

- **Brand-scoped by default.** Every scoped query filters by `brand_id` (plus team/assignee where
  applicable). A query without brand scoping is a defect, enforced at the repository/service layer —
  never only in the UI. GM is the only cross-brand role.
- **Append-only truth.** Audit + assignment history are never updated or deleted; no update/delete
  path may exist on those repositories.
- **Flyway owns the schema.** `ddl-auto: validate`. Every change is a new migration; an applied
  migration is never edited.
- **No object storage, no mail server.** Documents are Google Drive links, signed letters live in
  Dropbox Sign, staff alerts are in-app, client messages go out through GHL. Do not add S3 or SMTP.
- **Payment enters only via a per-brand GHL webhook endpoint** — no other path creates a case.
- Module contract is HTTP under `/api`, same-origin in dev via the Vite proxy; no CORS config exists
  on either side — add endpoints under `/api` rather than introducing CORS.

Cross-cutting refs: `mem:tech_stack`, `mem:suggested_commands`, `mem:conventions`,
`mem:task_completion`.
