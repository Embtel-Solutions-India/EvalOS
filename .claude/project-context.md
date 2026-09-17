# EvalOS — Project Context

Stable facts only. Decisions live in `current-decisions.md`; status in `implementation-status.md`.

## What it is

Back-of-house production CRM for a multi-brand credential-evaluation business
(brands: International Evaluations, XpertsPortal). It takes custody of a case when a GHL
opportunity is won, runs it through document collection → drafting → expert signing → delivery,
and pays the expert.

Since the Units 36–41 pivot it is **also** the interface Sales and Marketing work in. GoHighLevel
(GHL) remains the CRM, pipeline engine, automation engine and invoicing system underneath.

## Applications (4 deployable units, 3 repos-in-one)

| App | Path | Stack | Audience | Deployed? |
|---|---|---|---|---|
| Backend API | `backend/` | Java 21, Spring Boot, JPA, Flyway, Postgres 16 | all | yes (Docker → EC2) |
| Staff SPA | `frontend/` | React 19 + Vite + TS, React Router, vitest | 8 staff roles | yes (nginx image) |
| Client Portal | `client-expert/client/` | React + Vite + TS | clients | **no** — no Dockerfile, no CI, not in compose |
| Expert Portal | `client-expert/expert/` | React + Vite + TS | experts | **no** — same |

`client-expert/shared/` holds components/hooks/styles used by both portals.
`client-expert/` is one npm workspace with one vitest config; CI does not run it.

## Backend package map (`com.ie.evalos`)

| Package | Holds | Count |
|---|---|---|
| `domain` | entities + enums (`Case`, `ClientAccount`, `ClientApplication`, `Expert`, `Stage`, `Role`…) | 58 |
| `repository` | Spring Data repos; `ScopedRepository` declares each entity's brand scope | 24 |
| `service` | all business logic | 50 |
| `web` | thin REST controllers, `/api/**` | 30 |
| `integration` | `GhlHttp` + 7 GHL clients, `DocumentStore` (S3) | 11 |
| `webhook` | inbound gateway, router, `GhlOpportunityHandler` | 5 |
| `job` | 4 `@Scheduled` sweeps + ledger/lock/admin | 11 |
| `security` | JWT (staff) + portal token (client/expert) filter chains | 9 |
| `notification`, `event`, `common` | in-app notifications, domain events, errors/util | 15 |

215 main classes, 97 test classes.

## External integrations

| System | Direction | Used for |
|---|---|---|
| GoHighLevel | read + write | contacts, opportunities, pipelines, calendars/appointments, tasks, invoices, custom fields, users |
| AWS S3 | write + presign | all documents; two capabilities only (`put`, `presignedUrl`) |
| SMTP (Spring Mail) — Brevo, Resend, Mailgun, Postmark or SES | out | **two messages only** — set-password and reset-password. **The provider is `spring.mail.*`, not a class** (D3e, 2026-09-18): four environment variables and a restart, no build. The Brevo API transport is deleted |

No AI anywhere in the system. No Google Drive (removed, `V34`).

## Architectural boundaries

- **GHL owns** contact identity, pipelines, stages, opportunities, invoices, automation, calendars.
- **EvalOS owns** cases, experts, payouts, documents, checklists, audit, client accounts,
  client applications, the note stream.
- The seam is the **three handoffs** (see `workflows.md`). Handoff A (`opportunity.won` webhook)
  is the only door a case enters custody through.

## Source-of-truth rules

1. **Repository + live schema = what exists.** Never claim a feature from a spec.
2. **`current-decisions.md` = what should exist.**
3. **`context/specs/NN-*.md` = implementation guidance**, not evidence of existence.
4. `context/archive/2026-09-16-pre-reset/` is history. Never cite it as current.

## Where things live

```
backend/src/main/resources/db/migration/   V1..V49, all applied
backend/src/main/resources/db/seed-local/  V900..V909, local profile only
context/specs/                             unit specs 01..51 + programmes 00b/00c/00d
context/audit/2026-09-13/                  six senior audit reports (evidence)
context/ui-context.md                      design tokens, RAG colours, density
context/code-standards.md                  implementation conventions
.claude/*.md                               THIS BASELINE
.serena/memories/                          concise mirrors of this baseline
```

## Commands

```
backend:        cd backend && ./mvnw.cmd test        (1006 tests)
staff SPA:      cd frontend && npx vitest run        (127 tests) ; npx tsc -b
portals:        cd client-expert && npx vitest run   (30 tests)  ; (cd client && npx tsc -b) ; (cd expert && npx tsc -b)
                tsc -b, never `tsc --noEmit` — every tsconfig is files:[] + references
local db:       postgres://postgres:1234@localhost:5432/evalos, profile `local`
```
