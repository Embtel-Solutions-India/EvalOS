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
| Staff SPA | `frontend/` | React 19 + Vite + TS, React Router, TanStack Query (Unit 70a), vitest | 9 staff roles (incl. ADMIN, D78) | yes (nginx image) |
| Client Portal | `client-expert/client/` | React + Vite + TS | clients | via compose — `client-expert/Dockerfile.client` + `nginx-portal.conf`, `client` service; **CI does not build it** |
| Expert Portal | `client-expert/expert/` | React + Vite + TS | experts | via compose — `Dockerfile.expert`, `expert` service; **CI does not build it** |

`client-expert/shared/` holds components/hooks/styles used by both portals.
`client-expert/` is one npm workspace with one vitest config; CI does not run it.

## Backend package map (`com.ie.evalos`)

| Package | Holds | Count |
|---|---|---|
| `domain` | entities + enums (`Case`, `ClientAccount`, `Expert`, `Stage`, `Role`…) | 71 |
| `repository` | Spring Data repos; `ScopedRepository` declares each entity's brand scope | 39 |
| `service` | all business logic | 78 |
| `web` | thin REST controllers, `/api/**` | 46 |
| `integration` | `GhlHttp` + 8 GHL clients + `StubGhlWriteClient`, `DocumentStore` (S3), SMTP `MailTransport` | 16 |
| `webhook` | inbound gateway, router, `GhlOpportunityHandler` | 7 |
| `job` | 14 `@Scheduled` sweeps + ledger/lock/admin | 22 |
| `security` | JWT (staff) + portal token (client/expert) filter chains | 10 |
| `chat` | case chat (Unit 57) + `live` (Ably) + `push` (Web Push) | 55 |
| `config` | Spring configuration | 6 |
| `notification`, `event`, `common` | in-app notifications, domain events, errors/util | 20 |

371 main classes, 189 test classes (`*.java`, 2026-10-30).

## External integrations

| System | Direction | Used for |
|---|---|---|
| GoHighLevel | read + write | contacts, opportunities, pipelines, calendars/appointments, tasks, invoices, custom fields, users |
| AWS S3 | write + presign | all documents; `put` and presigned reads only — a download (`attachment`) or a view (`inline`, type forced to PDF / PNG / JPEG, Unit 74) |
| SMTP (Spring Mail) — Brevo, Resend, Mailgun, Postmark or SES | out | client: case opened (set-password or sign-in), documents needed, chase, draft ready, delivered, set / reset password; expert: offer, ready to sign, set / reset link; the Admin's test mail (`workflows.md` §10). **The provider is `spring.mail.*`, not a class** (D3e, 2026-09-18): four environment variables and a restart, no build. The Brevo API transport is deleted |
| Ably | out | real-time: chat fan-out, `case.changed` live updates, the bell (`ABLY_API_KEY`; Units 57, 70) |
| Web Push (VAPID) | out | chat, bell and case-event pushes (`EVALOS_PUSH_VAPID_*`; empty = push off) |

No AI anywhere in the system (D30 — the client terms still say otherwise, `open-decisions.md` Q31). No Google Drive (removed, `V34`).

## Architectural boundaries

- **GHL owns** contact identity, pipelines, stages, opportunities, invoices, automation, calendars.
- **EvalOS owns** cases, experts, payouts, documents, checklists, audit, client accounts, the
  note stream, case chat. (Client applications were removed by Unit 64, `V78`.)
- The seam is the **handoffs** (see `workflows.md` §8; C dropped, D53). A case enters custody only
  through Handoff A — the `opportunity.won` webhook, or "New case" `POST /api/cases/from-ghl` for a
  deal won in GHL — both through `GhlOpportunityHandler` (D9).

## Source-of-truth rules

1. **Repository + live schema = what exists.** Never claim a feature from a spec.
2. **`current-decisions.md` = what should exist.**
3. **`context/specs/NN-*.md` = implementation guidance**, not evidence of existence.
4. `context/archive/2026-09-16-pre-reset/` is history. Never cite it as current.

## Where things live

```
backend/src/main/resources/db/migration/     V1..V98; prod scans this and only this
backend/src/main/resources/db/seed-local/    V900..V916, local profile only (V915 = the demo world, V916 = the Admin; samples in backend/seed-local-documents/)
backend/src/main/resources/db/seed-testprod/ V950..V954, testprod profile only
backend/src/main/resources/db/seed-prod/     V960..V961, prod profile only (V960 the six IE desk logins, V961 the Admin)
packages/evalos-chat/                      source-only chat UI (Unit 57 §7); its node_modules holds only Vite/vitest caches, no packages; imported by client-expert/ through a Vite alias
context/specs/                             unit specs 01..86 + programmes 00b/00c/00d
context/audit/2026-09-13/                  six senior audit reports (evidence)
context/ui-context.md                      design tokens, RAG colours, density
context/code-standards.md                  implementation conventions
.claude/*.md                               THIS BASELINE
.serena/memories/                          concise mirrors of this baseline
```

## Commands

```
backend:        cd backend && ./mvnw.cmd test        (1840 tests, surefire 2026-10-30)
staff SPA:      cd frontend && npx vitest run        (258 test cases) ; npx tsc -b
portals:        cd client-expert && npx vitest run   (130 test cases: 52 portal + 78 evalos-chat) ; (cd client && npx tsc -b) ; (cd expert && npx tsc -b)
                tsc -b, never `tsc --noEmit` — every tsconfig is files:[] + references
local db:       postgres://postgres:1234@localhost:5432/evalos, profile `local`
```
