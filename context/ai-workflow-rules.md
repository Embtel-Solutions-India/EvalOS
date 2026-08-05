# EvalOS — AI Workflow Rules

## Approach

Build EvalOS incrementally, spec-driven. The context files define what to build,
how to build it, and the current state; the EvalOS Technical Design Document
(v1.1) is the authoritative design behind them. Implement against the unit spec
in `context/specs/NN-name.md` — do not infer or invent behavior from scratch.
The stack is Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) on the backend
and a React/Vite + Tailwind client. Do not introduce a Node backend, a different
database, an object store, a mail server, or a different auth model than Spring
Security + JWT (staff) + scoped link-based portal chains (client/expert).

## Non-negotiable properties (apply to every unit)

- **Brand-scoped by default.** Every scoped query filters by `brand_id`. Never
  write a finder that can cross brands except for the GM's explicit cross-brand
  reads.
- **Append-only audit** on every object; no update/delete path.
- **No files, no email.** Drive links and file ids for every artifact including the
  signed letter; in-app notifications for staff; GHL for client messages; a scoped
  portal link for experts.
  - *No files* means **stores none, not accepts none.** Unit 21 accepts a client
    upload and streams it through to Drive; EvalOS keeps the file id and nothing
    else. Do not read this rule as forbidding that unit — read it as forbidding the
    temp file, the upload directory and the blob column.
  - *No email* is **true today and under review.** The touchpoints and the open
    channel decision are in `context/process-automation.md`. Until it is decided,
    still do not add a mail dependency.
- **One home per fact.** SLA budgets live in `SlaCalculator`, transitions in
  `CaseTransitions`, recipients in `NotificationListeners.ROUTES`, scope in
  `ScopePredicate`. Docs cite them; they never restate a threshold as an authority.
  If a doc and the code disagree, the code wins and the doc is the bug.

## Scoping Rules

- Work on one unit at a time, in the order set by `context/specs/00-build-plan.md`.
- Prefer small, verifiable increments over large speculative changes.
- Do not combine unrelated system boundaries in one implementation step.
- Build backend before wiring frontend to it. Build UI shells with placeholder
  data before connecting real API calls.
- Install a dependency only in the unit where it first unlocks real behavior.

## When to Split Work

Split the step if it combines any of:
- A webhook/integration change **and** a UI change **and** a background job.
- Two or more unrelated API routes or domains (e.g. matching + payouts).
- Behavior that is not clearly defined in the context files.

If a change cannot be verified end to end quickly, the scope is too broad — split.

## Handling Missing Requirements

- Do not invent product behavior that is not in the context files.
- If a requirement is ambiguous, resolve it in the relevant context file (and the
  TDD if a decision changes) first, then implement.
- If a requirement is missing, add it as an open question in `progress-tracker.md`
  before continuing — do not guess.
- **Known-open items that must not be built around silently** (see
  `progress-tracker.md` for the live list): the **full brand list**; whether
  EvalOS **builds the sales/marketing dashboards** (default: no, they stay in
  GHL); **StatCommand**; the **GHL webhook/API contract** (per-brand inbound
  secret + payload, outbound subscriber URL + secret, client-message capability);
  and **staff SSO** (optional/later). *(The Dropbox Sign callback secret used to be on
  this list; there is no signature provider any more — the expert uploads the signed
  letter through their portal.)*

Resolved (do not re-open as questions): the payout **rail** — there is none; the
ledger is filled by a manual form. **Object storage** — there is none. **Email
provider** — none; no EvalOS mail server.

## Protected Files

Do not modify these unless explicitly instructed:
- `frontend/src/components/ui/*` — generated headless UI components.
- Any third-party library internals.
- The audit-trail entity and its write path — append-only; never add update/delete.
- The field-level encryption `AttributeConverter` in `common` and any code
  handling the expert `payment_detail`.
- The inbound webhook secret-verification and **per-brand brand-resolution** step.
- The brand-scoping filter in the repository/service layer.
- Any Flyway migration that has already been applied — add a new migration.

## Keeping Docs in Sync

Update the relevant context file whenever implementation changes:
- Architecture, boundaries, tenancy, or handoff contracts → `architecture.md`
- Storage model or data ownership → `architecture.md` / `code-standards.md`
- Code conventions → `code-standards.md`
- Visual tokens or layout patterns → `ui-context.md`
- Feature scope → `project-overview.md`
- **A trigger, its recipients, an SLA, or a client/expert touchpoint →
  `process-automation.md`** (the A-register). Moving an automation from *gap* to
  *built* is part of the unit that built it, not a later tidy-up.
- A decision that changes the design → the TDD as well

Also update the **Serena memories** (`.serena/memories/`) in the same step, so the
next session starts from the current picture instead of rediscovering it:
- Backend domain, lifecycle, persistence, security, webhooks → `backend/*`
- Frontend structure and conventions → `frontend/core`
- Stack or tooling change → `tech_stack` / `suggested_commands`
- Convention change → `conventions`; verification-step change → `task_completion`
- New domain worth its own memory → add it and link it from `core`

A changed decision means **editing the existing memory**, never appending a
contradicting note beside it — a memory that disagrees with the code is worse
than no memory. Respect the add/update threshold in the `memory_maintenance`
memory: durable, non-obvious conventions only, never task-local notes.

## Before Moving to the Next Unit

1. The unit works end to end within its defined scope.
2. No invariant in `architecture.md` was violated — especially: **brand scoping
   on every query**, role+ownership on every mutation, `payment_detail` never
   exposed, audit entry on every transition, thin handlers, GHL-only payment path,
   no files, no email.
3. `progress-tracker.md` reflects the completed work.
4. Backend `./mvnw verify` passes and the app starts cleanly; frontend
   `npm run build` passes with no TypeScript or console errors.
5. The Serena memories affected by the unit are updated (see *Keeping Docs in
   Sync*), and none of them still describes the old behavior.
