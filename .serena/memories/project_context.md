# Project context

**The authoritative file is `.claude/project-context.md`. Read it; do not work from this summary.**

EvalOS: back-of-house production CRM for a multi-brand credential-evaluation business
(International Evaluations, XpertsPortal). It takes custody of a case when a GHL opportunity is
won, runs it to delivery and expert payout. Since Units 36-41 it is also the interface Sales and
Marketing work in; GoHighLevel stays the CRM, pipeline engine, automation engine and invoicing
system underneath.

Four apps in one repo:

- `backend/` — Java 21 / Spring Boot / JPA / Flyway / Postgres 16. 371 main classes, 189 test classes; 14 `@Scheduled` sweeps; 8 GHL clients + a stub.
- `frontend/` — staff SPA (nine staff roles incl. ADMIN), React 19 + TanStack Query (Unit 70a) + Vite + TS. Deployed.
- `client-expert/client/` — Client Portal. Deployed via docker-compose (`Dockerfile.client`, `nginx-portal.conf`); **CI does not build it.**
- `client-expert/expert/` — Expert Portal. Same (`Dockerfile.expert`).
- `packages/evalos-chat/` — source-only chat UI (Unit 57 §7); its `node_modules` holds only Vite/vitest caches, no packages; imported by
  `client-expert/` through a Vite alias.

Integrations: GoHighLevel (read and write), AWS S3 (documents), Ably (real-time), Web Push (VAPID),
SMTP — client case opened / documents needed / chase / draft ready / delivered / set-reset password,
expert offer / ready to sign / set-reset link, Admin test mail (`workflows.md` §10).
**The provider is `spring.mail.*`, not a class** (D3e, 2026-09-18), so Brevo, Resend, Mailgun,
Postmark or SES is an environment change; the Brevo API transport is deleted.
No AI anywhere in the system (D30; the client terms still mention AI — open Q31).

Knowledge baseline (reset 2026-09-16): `.claude/project-context.md`, `current-decisions.md`,
`architecture.md`, `data-model.md`, `workflows.md`, `implementation-status.md`,
`open-decisions.md`. Everything under `context/archive/2026-09-16-pre-reset/` is history and is
never cited as current.
