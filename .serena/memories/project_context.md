# Project context

**The authoritative file is `.claude/project-context.md`. Read it; do not work from this summary.**

EvalOS: back-of-house production CRM for a multi-brand credential-evaluation business
(International Evaluations, XpertsPortal). It takes custody of a case when a GHL opportunity is
won, runs it to delivery and expert payout. Since Units 36-41 it is also the interface Sales and
Marketing work in; GoHighLevel stays the CRM, pipeline engine, automation engine and invoicing
system underneath.

Four apps in one repo:

- `backend/` — Java 21 / Spring Boot / JPA / Flyway / Postgres 16. 215 main classes.
- `frontend/` — staff SPA, React 19 + Vite + TS. Deployed.
- `client-expert/client/` — Client Portal. **Not deployed by this repo.**
- `client-expert/expert/` — Expert Portal. **Not deployed by this repo.**

Integrations: GoHighLevel (read and write), AWS S3 (documents), SMTP for two auth emails only —
**the provider is `spring.mail.*`, not a class** (D3e, 2026-09-18), so Brevo, Resend, Mailgun,
Postmark or SES is an environment change; the Brevo API transport is deleted.
No AI anywhere in the system.

Knowledge baseline (reset 2026-09-16): `.claude/project-context.md`, `current-decisions.md`,
`architecture.md`, `data-model.md`, `workflows.md`, `implementation-status.md`,
`open-decisions.md`. Everything under `context/archive/2026-09-16-pre-reset/` is history and is
never cited as current.
