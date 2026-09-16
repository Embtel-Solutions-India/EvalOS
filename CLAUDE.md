## EvalOS

Back-of-house production CRM for a multi-brand credential-evaluation business
(International Evaluations, XpertsPortal), and — since Units 36–41 — the interface Sales and
Marketing work in. GoHighLevel remains the CRM, pipeline engine, automation engine and invoicing
system underneath.

## The knowledge baseline (reset 2026-09-16)

Read these before implementing or deciding anything. They are short on purpose.

1. `.claude/project-context.md` — stack, apps, modules, integrations, boundaries
2. `.claude/current-decisions.md` — the 32 decisions in force
3. `.claude/architecture.md` — chains, tenancy, invariants, the tests that fail the build
4. `.claude/data-model.md` — CURRENT DATABASE, then REQUIRED FUTURE MODEL, never mixed
5. `.claude/workflows.md` — CURRENT IMPLEMENTATION, then TARGET WORKFLOW, never mixed
6. `.claude/implementation-status.md` — a status table with evidence per row
7. `.claude/open-decisions.md` — unresolved only, each with a recommendation

Mirrored as concise Serena memories in `.serena/memories/`. Still active alongside them:
`context/specs/` (implementation guidance), `context/audit/2026-09-13/` (evidence),
`context/ui-context.md`, `context/code-standards.md`, `context/ai-workflow-rules.md`.

**`context/archive/2026-09-16-pre-reset/` is history.** Never cite it as current, and do not
resurrect architecture, workflows, APIs, tables or UI from it.

## How to answer a question about this codebase

1. Inspect the relevant code.
2. Inspect the schema if data behaviour is involved.
3. Check `.claude/current-decisions.md`.
4. Check `.claude/implementation-status.md`.
5. Only then conclude.

Never answer from memory alone. Never claim a feature exists because a spec says so. Never claim
one is missing because an old note says so.

**Code = what exists. Current decision = what should exist. Spec = implementation guidance.**

If a requirement is ambiguous, add it to `.claude/open-decisions.md` rather than inventing
behaviour.

## Two rules that override convenience everywhere

- **Brand-scoped by default.** Every scoped query filters by `brand_id` (plus team or assignee
  where applicable). A query without brand scoping is a bug.
- **Append-only truth.** Audit and assignment history are never updated or deleted.

## After each meaningful change

Update `.claude/implementation-status.md`, plus `current-decisions.md`, `data-model.md` or
`workflows.md` if the change touches them — in the same step. Then update the matching Serena
memory in `.serena/memories/`. A changed decision is an **edit** to the file that states the old
one, never a contradicting note beside it.
