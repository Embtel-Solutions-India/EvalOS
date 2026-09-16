# Conventions and working rules

Code standards: `context/code-standards.md`. UI tokens, RAG colours and density:
`context/ui-context.md`. Workflow and scoping rules: `context/ai-workflow-rules.md`.

Two rules that override convenience everywhere:

- **Brand-scoped by default.** A scoped query without a `brand_id` filter is a bug.
- **Append-only truth.** Audit and assignment history are never updated or deleted.

## Before answering any question about this codebase

1. Inspect the relevant code.
2. Inspect the schema if data behaviour is involved.
3. Check `.claude/current-decisions.md`.
4. Check `.claude/implementation-status.md`.
5. Only then conclude.

Never answer from memory alone. Never claim a feature exists because a spec says so. Never claim
one is missing because an old note says so. If code and spec disagree: **code is what exists,
the current decision is what should exist, the spec is implementation guidance.**

## After any meaningful change

Update `.claude/implementation-status.md` — and `current-decisions.md`, `data-model.md` or
`workflows.md` if the change touches them — **in the same step**, then the matching memory here. A
changed decision is an **edit**, never a note appended beside the old one.

Do not resurrect abandoned architecture, workflows, APIs, tables or UI because they appear in
`context/archive/2026-09-16-pre-reset/`.

## Commands

```
backend    cd backend && ./mvnw.cmd test
staff SPA  cd frontend && npx vitest run ; npx tsc --noEmit
portals    cd client-expert && npx vitest run ; (cd client && npx tsc -b)
local db   postgres://postgres:1234@localhost:5432/evalos   (Spring profile `local`)
```

`.env` at the repo root is what VS Code actually launches with, and env vars beat every config
file. A real GHL token goes in `backend/config/application-local.yml` (gitignored), never in a
tracked yml.
