# EvalOS — Architecture

Structure and enforced rules. Product facts are in `project-context.md`; rulings in
`current-decisions.md`.

## Request paths

```
Staff SPA ──JWT──────────► SecurityConfig (@Order 2) ── /api/**            ── TenantContext(brand,role)
Client SPA ─portal token─► PortalSecurityConfig (@Order 1) ── /api/portal/** ── PortalPrincipal
Expert SPA ─portal token─► same chain, audience=EXPERT
GHL ───────HMAC + token─► /api/webhooks/ghl/{endpointToken}  (permitAll, brand from token)
```

`permitAll` on the portal chain: exactly five POSTs under `/api/portal/auth/**`, plus a per-IP
limiter (60/min) in `PortalTokenFilter`. `permitAll` on the staff chain: `/api/auth/login`,
`/api/health`, `/actuator/health`, `/api/webhooks/**`.

## Multi-tenancy

`ScopedEntity` + `ScopedRepository`. Every scoped repository declares its brand column; a scoped
row with no brand is refused before it is written and stamped on persist — both pinned by
`DomainInvariantsTest`. `ScopePredicate` turns a role's tier into a JPA predicate.

**Known structural hole:** `Case` has no pipeline column, so `ScopePredicate`'s PIPELINE arm
returns `cb.disjunction()` — a SALES/MARKETING principal matches **no case at all**. This is by
construction, not a missing grant. The fix is a `PortalStageProjection.forSales` joining
`evalos_case.ghl_opportunity_id` to `opportunity_note.ghl_opportunity_id`, not a widened tier.

## The three handoffs

| | Direction | Mechanism | State |
|---|---|---|---|
| **A** | GHL → EvalOS | `opportunity.won` webhook → `GhlOpportunityHandler` → `CaseIntakeService` | **the only way a case is born**; live in code, see status doc for the operational caveat |
| **B** | EvalOS → Expert | staff mints a `portal_access` link; expert accepts/declines/signs | built |
| **C** | EvalOS → GHL/client | outbound dispatcher | **not implemented** — `event/CaseEvents.java` only; no `webhook.outbound` package exists |

## Integration layer

`GhlHttp` is the only transport: closed verb set, rate-limited, 10s timeout, brand-agnostic
(one location id globally). **Failures are classified at the door** (Unit 45a): `GhlFailure` tells
retriable (5xx, timeout, 408, 429) from fatal (other 4xx, empty body) and names the two that stop
everything — 401/403 halts a queue, 429 pauses the whole location. A 429 also pushes `GhlHttp`'s own
shared pacer forward, honouring a capped `Retry-After`, so every caller backs off together. Clients on top of it:

| Client | Reads | Writes |
|---|---|---|
| `GhlWriteClient` | — | contacts upsert, opportunities upsert/create/update/stage/status, contact tasks |
| `GhlPipelineClient` | pipelines, stages, opportunities in a window (optionally by status) | — |
| `GhlOpportunityClient` | opportunity search | — |
| `GhlCalendarClient` | calendars, free slots, contact appointments | book, reschedule, note |
| `GhlInvoiceClient` | invoices by contact | — |
| `GhlUserClient` | GHL users | — |
| `GhlCustomFieldClient` | custom field definitions | — |

`DocumentStore` (S3): `put` and `presignedUrl` and nothing else. Unconfigured = every document
route answers 502 naming both missing variables; the rest of the app boots.

## Background jobs

Four `@Scheduled` sweeps — `DOC_CHASE`, `DOC_ESCALATION`, `EXPERT_SIGN`, `STAGE_SLA` — each
taking a `JobLock` and writing a `scheduled_job` row via `JobLedger`. Admin can force a run at
`POST /api/jobs/{jobType}/run`.

## Case state machine (12 stages)

```
DOC_COLLECTION → PM_REVIEW → DRAFT_IN_PROGRESS → DRAFT_REVIEW → READY_TO_SEND
  → CLIENT_REVIEW → CLIENT_APPROVAL → EXPERT_SIGNING → FINAL_QC → READY_TO_DELIVER
  → DELIVERED → CLOSED
```
Plus an orthogonal `exception_state` (default `NONE`) for holds/refunds.
Transitions live in `CaseTransitions`/`CaseLifecycleService`; every one writes audit.

## The 15 invariants (compact — full reasoning archived)

1. **Brand isolation.** Every query over EvalOS rows filters by `brand_id`. One exception: GHL
   location reads, which are GM-only because they are unattributable to a brand.
2. **One custody at a time.** *(The old second half — "EvalOS runs no sales/marketing/invoicing" —
   died at Unit 37.)* What survives: invoicing is GHL's; Handoff A is the only door into custody;
   the GHL opportunity cache holds only fields GHL owns and is droppable without loss.
3. Role, brand and ownership are enforced before every mutation.
4. Expert `payment_detail` is encrypted at rest and never leaves in a DTO.
5. Paid **and** `DELIVERED` is revenue recognition; read only through the metrics services.
6. Controllers stay thin; long work is a sweep.
7. EvalOS is system of record for cases, experts and payouts. GHL owns contact identity
   (the three-identifier rule is load-bearing).
8. **A case is created only by the per-brand GHL webhook.** Build-enforced.
9. Schema changes are new Flyway migrations; applied migrations are never edited.
10. Every inbound webhook is brand-resolved from its endpoint token.
11. Outbound webhooks are HMAC-signed, retried, dead-lettered — *(design; not implemented)*.
12. Webhook transport carries no business logic.
13. Every state transition writes an append-only audit row.
14. EvalOS hosts no files, and sends email for **two purposes and no others** — proving control of
    a mailbox (set password, reset password) and **confirming that a client's request was
    received** (one message, at submit). **A push notification is not mail** and does not touch this
    invariant (D37: notifications are in-app and push, and nothing else).
    **Amended 2026-09-19, on the business's instruction.** It read *"exactly one purpose: proving
    control of a mailbox (two messages)"*, and the submission confirmation is not a mailbox proof —
    so it is an amendment rather than a reading of the old rule. Two things bound it: the
    confirmation **promises nothing the flow can fail to keep** (no price, no turnaround, no date —
    EvalOS holds no price list and the work is quoted by a person), and **nothing depends on it
    arriving**, so a failed send is logged and the submit still succeeds.
    **This is not the four-message expansion `open-decisions.md` (c) recommends** — checklist+link,
    draft ready, expert signing link, delivered. Those remain unbuilt and still need their own
    decision; each one would be mail a client *acts on*, which is a different and larger question
    than telling somebody their form arrived.
15. **No AI makes a production decision, and there is no AI in the system at all.**

## Build-failing structural tests

| Test | Enforces |
|---|---|
| `DomainInvariantsTest` | only `GhlOpportunityHandler` creates a case; scope fields map to real attributes; audit repo cannot change history; brand stamping |
| `GhlHttpTest` | closed verb list; every write-verb caller reaches `AuditService` |
| `ConfigSecretsTest` | no credential-shaped setting carries a default in a shared profile |
| `GmOverviewRouteTest` | `/api/metrics/gm` stays `hasRole('GM')` |
| `navigation.test.ts` (frontend) | every GHL-location screen is GM-only; case detail is not in nav |

## Configuration

Precedence that actually decides on a dev machine: `.env` (loaded by `.vscode/launch.json` as real
env vars) **beats** `backend/config/application-local.yml` **beats** `application-local.yml` on the
classpath. `backend/config/` is gitignored and is where a real GHL token goes.

Key settings: `evalos.ghl.{location-id, token, sales-brand, opportunity-service-field,
opportunity-correlation-field, board-stale-after, delta-ttl}` (`intake-pipeline-name` is retired — Unit 44b, D10b; the two funnel
screens took `sales-pipeline-name` and `email-pipeline-name` with them), `evalos.mail.transport`, `evalos.portal.{client-brand, client-base-url,
expert-base-url, allowed-origins, credential-ttl}`, `evalos.s3.{bucket, region}`,
`evalos.security.jwt.secret`, `evalos.field-key`, `SALES_MONTHLY_GOAL`.

## Deployment

`docker-compose.yml`: postgres 16 + backend (Spring, `prod,testprod`) + frontend (nginx, 80/443).
CI (`.github/workflows/ci.yml`) runs on push to **`main` only**: backend tests, frontend
test/build/lint, then deploy to EC2. `client-expert/` is in neither compose nor CI — **and that is
not this repository's debt: DevOps owns and edits deployment (D38, 2026-09-17).** Know it when
reasoning about what is live; do not schedule work for it here.
