# EvalOS — AI Workflow Rules

## Approach

Build EvalOS incrementally, spec-driven. **The `.claude/*.md` baseline defines what exists,
what is decided and what is open** (reset 2026-09-16); the unit specs define how to build the
next thing; the EvalOS Technical Design Document (v1.1) is the design behind them. Implement against the unit spec
in `context/specs/NN-name.md` — do not infer or invent behavior from scratch.
The stack is Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) on the backend and
**two** React/Vite + Tailwind frontends: `frontend/` (staff, same-origin) and `client/`
(the external portal frontend carrying both portals, cross-origin against
`/api/portal/**`). Do not introduce a Node backend, a different database, a mail server,
or a different auth model than Spring Security + JWT (staff) + scoped link-based portal
chains (client/expert). **The object store is no longer on that list** — Unit 30 put
documents in S3 and EvalOS still stores no bytes; what stays forbidden is a blob column,
a temp file or a byte array on the way through.

## Non-negotiable properties (apply to every unit)

- **Brand-scoped by default.** Every scoped query filters by `brand_id`. Never
  write a finder that can cross brands except for the GM's explicit cross-brand
  reads.
- **Append-only audit** on every object; no update/delete path.
- **No files, no email.** S3 **object keys** for every artefact including the signed
  letter, read through 5-minute presigned URLs minted after the scope check; in-app
  notifications for staff; GHL for client messages; a scoped portal link for experts.
  (This said "Drive links and file ids" until Unit 30; Drive is gone.)
  - *No files* means **stores none, not accepts none.** An upload **streams** through to
    S3 and EvalOS keeps the key. Do not read this rule as forbidding an upload endpoint —
    read it as forbidding the temp file, the upload directory, the byte array and the blob
    column.
  - *No email* was **amended on 2026-09-11, not reversed.** EvalOS now sends **exactly two**
    messages, both authentication: set-password and reset-password (`ClientMailer`). There is
    still no outbound dispatcher and no notification, marketing or status mail of any kind.
    A third message is a new decision — see `.claude/open-decisions.md` (carried question c).
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
- If a requirement is missing, add it as an open question in `.claude/open-decisions.md`
  before continuing — do not guess. Attach a recommendation to it (house rule).
- **Known-open items that must not be built around silently** (see
  `.claude/open-decisions.md` for the live list): the **full brand list**; **StatCommand**;
  the **GHL webhook/API contract** (per-brand inbound
  secret + payload, outbound subscriber URL + secret, client-message capability);
  and **staff SSO** (optional/later). *(The Dropbox Sign callback secret used to be on
  this list; there is no signature provider any more — the expert uploads the signed
  letter through their portal.)*

Resolved (do not re-open as questions): the payout **rail** — there is none; the
ledger is filled by a manual form. **Object storage** — there is none. **Email
provider** — none; no EvalOS mail server. **Sales/marketing dashboards** — answered by
Unit 24: EvalOS builds **one read-only GM screen** over GHL's Google Ads funnel, and
everything else stays in GHL. Read it as narrowly as it is written — a *reading*, not a
marketing function. There is no lead, campaign, nurture or attribution feature here, no
write back to GHL, and no stored copy of GHL's pipeline; invariant 2 is unchanged.

## Protected Files

Do not modify these unless explicitly instructed:
- `frontend/src/components/ui/*` and `client/src/components/ui/*` — generated headless UI
  components, in both frontends.
- Any third-party library internals.
- The audit-trail entity and its write path — append-only; never add update/delete.
- The field-level encryption `AttributeConverter` in `common` and any code
  handling the expert `payment_detail`.
  - **One named exception, signed off 2026-08-26** (see `code-standards.md`): extracting
    the AES-GCM into a shared `common/EncryptedStringConverter` with
    `PaymentDetailConverter` left as a thin subclass, behaviour on the expert path
    unchanged. To be done when Unit 25 is built. Every other change to this file still
    needs its own sign-off.
- The inbound webhook secret-verification and **per-brand brand-resolution** step.
- The brand-scoping filter in the repository/service layer.
- Any Flyway migration that has already been applied — add a new migration.

## Keeping Docs in Sync

Update the relevant context file whenever implementation changes:
- Architecture, boundaries, tenancy, or handoff contracts → `.claude/architecture.md`
- Storage model or data ownership → `.claude/architecture.md` / `.claude/data-model.md`
- Code conventions → `context/code-standards.md`
- Visual tokens or layout patterns → `context/ui-context.md`. **Two token sets, one per frontend** —
  `frontend/src/styles/tokens.css` and `client/src/styles/globals.css`. They diverge on
  purpose; only RAG-is-status-only and tabular figures cross the boundary.
- Feature scope → `.claude/project-context.md`
- What a domain's status is, with its evidence → `.claude/implementation-status.md`
- A current vs. target workflow → `.claude/workflows.md`
- **A trigger, its recipients, an SLA, or a client/expert touchpoint →** the owning unit spec
  under `context/specs/`. The old A-register (`process-automation.md`) described the 5-stage
  lifecycle and is archived; `V31` shipped 12 stages.
- A decision that changes the design → `.claude/current-decisions.md` (and the TDD)

Also update the **Serena memories** (`.serena/memories/`) in the same step, so the
next session starts from the current picture instead of rediscovering it. Since the
2026-09-16 reset there are seven, each a short pointer at its `.claude/` counterpart:
`project_context`, `current_decisions`, `architecture`, `data_model`, `workflows`,
`implementation_status`, `open_decisions`, plus `conventions`.

**Write the fact in `.claude/` first, then trim the memory to a pointer.** A memory that
restates a whole document is the pollution this reset removed.

A changed decision means **editing the existing file and its memory**, never appending a
contradicting note beside them — a memory that disagrees with the code is worse than no
memory. Durable, non-obvious facts only, never task-local notes.

## Before Moving to the Next Unit

1. The unit works end to end within its defined scope.
2. No invariant in `.claude/architecture.md` was violated — especially: **brand scoping
   on every query**, role+ownership on every mutation, `payment_detail` never
   exposed, audit entry on every transition, thin handlers, GHL-only payment path,
   no files, and no mail beyond the two authentication messages.
3. `.claude/implementation-status.md` reflects the completed work.
4. Backend `./mvnw verify` passes and the app starts cleanly; `frontend/`
   `npm run build` passes with no TypeScript or console errors — and, for any unit
   touching `client/`, that app's `npm run build` too. **Use `tsc -b`, never a bare
   `tsc --noEmit`**: `frontend/tsconfig.json` is `files: []` with project references, so
   `--noEmit` typechecks nothing and exits 0.
5. The Serena memories affected by the unit are updated (see *Keeping Docs in
   Sync*), and none of them still describes the old behavior.
