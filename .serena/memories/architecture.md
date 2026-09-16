# Architecture

**The authoritative file is `.claude/architecture.md`. Read it before any structural change.**

Two Spring Security chains: `/api/portal/**` on an opaque portal token (order 1), everything else
on staff JWT (order 2). Webhooks are `permitAll` and brand-resolved from their endpoint token.

Multi-tenancy: `ScopedEntity` plus `ScopedRepository` plus `ScopePredicate`. **Known hole:** `Case`
has no pipeline column, so the PIPELINE tier (SALES and MARKETING) matches no case at all. Fix it
with a `PortalStageProjection.forSales`, never by widening a tier.

Integration: `GhlHttp` is the only transport and its verb list is closed (get, post, put, delete).
A fifth verb fails the build, and every write-verb caller must reach `AuditService`. Seven GHL
clients sit on it. `DocumentStore` does `put` and `presignedUrl` and nothing else.

Case lifecycle: 12 stages, `DOC_COLLECTION` through `CLOSED`, plus an orthogonal `exception_state`.
Four scheduled sweeps, each taking a database lock and writing a `scheduled_job` ledger row.

Build-failing structural tests: `DomainInvariantsTest`, `GhlHttpTest`, `ConfigSecretsTest`,
`GmOverviewRouteTest`, and `frontend/src/features/shell/navigation.test.ts`. Read what each one
pins before changing anything near it.

The 15 invariants are in `.claude/architecture.md` in compact form; the long reasoning is archived
at `context/archive/2026-09-16-pre-reset/architecture.md`.

**2026-09-17.** Invariant 14 is untouched by D37: notifications are in-app **and push**, and a push
is not mail. Deployment is **DevOps's** (D38) — `client-expert/` missing from compose and CI is not
this repo's debt. `evalos.ghl.intake-pipeline-name`, `sales-pipeline-name` and `email-pipeline-name`
no longer exist (Unit 44b, and the two funnel screens' removal).
