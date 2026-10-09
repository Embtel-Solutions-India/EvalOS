# EvalOS — Architecture

Structure and enforced rules. Product facts are in `project-context.md`; rulings in
`current-decisions.md`.

## Request paths

```
Staff SPA ──JWT──────────► SecurityConfig (@Order 2) ── /api/**            ── TenantContext(brand,role)
Client SPA ─portal token─► PortalSecurityConfig (@Order 1) ── /api/portal/** ── PortalPrincipal
Expert SPA ─portal token─► same chain, audience=EXPERT
(portal token kept in localStorage, D73 — survives a closed browser, bounded by the 7-day server expiry; `POST /api/portal/sign-out` revokes it)
GHL ───────HMAC + token─► /api/webhooks/ghl/{endpointToken}  (permitAll, brand from token)
```

`permitAll` on the portal chain: exactly nine POSTs under `/api/portal/auth/**` — the client's five and the expert's four (`expert/{sign-up, forgot-password, sign-in, set-password}`, Unit 59) — plus a per-IP
limiter (60/min) in `PortalTokenFilter`. **The client's `sign-up` was removed by Unit 64 (2026-09-29)**, leaving eight. `permitAll` on the staff chain: `/api/auth/login`,
`/actuator/health` and `/actuator/health/**` (the liveness/readiness probes; bare status only), `/api/webhooks/**` (`/api/health` and its `HealthController` deleted 2026-09-30 — a hard-coded `UP` beside actuator's real one).
The rest of `/actuator` (`info`, `metrics` — nothing else exists, D82) is `hasRole('ADMIN')` and GET-only via `AdminAllowlist`.
The Admin's browser view of the same figures is `GET /api/system/health` (an Admin area, spec 84), because nginx proxies only `/api/`.

**Every request gets an id** (`RequestIdFilter`, first in the servlet chain): MDC `requestId`, printed on every log
line beside the active profile, and returned as `X-Request-Id` (a proxy's plain id is reused). Security refusals
are logged by `ApiErrors.refuse` (401 INFO, 403 WARN; method, path, member id — never a header or email).

**A staff token is re-checked on every request** (Unit 68): `JwtFilter` refuses a member whose
`team_member.active` is false, so a GM's deactivation bites at once rather than at the 8 h expiry.
Role and brand still travel in the token and change on the next sign-in.

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
| **A** | GHL → EvalOS | `opportunity.won` webhook → `GhlOpportunityHandler` → `CaseIntakeService` | **the only way a case is born**; live in code, see status doc for the operational caveat. **Unit 64:** `CASE_CREATED` after commit also opens the client's portal account (`CasePortalAccountListener`, D3d) |
| **B** | EvalOS → Expert | staff mints a `portal_access` link; expert accepts/declines/signs | built |
| **C** | EvalOS → GHL/client | — | **dropped (D53)** — no outbound webhooks; `event/CaseEvents.java` is in-process only |

## Integration layer

`GhlHttp` is the only transport: closed verb set, rate-limited, 10s timeout, brand-agnostic
(one location id globally). **Failures are classified at the door** (Unit 45a): `GhlFailure` tells
retriable (5xx, timeout, 408, 429) from fatal (other 4xx, empty body) and names the two that stop
everything — 401/403 halts a queue, 429 pauses the whole location. A 429 also pushes `GhlHttp`'s own
shared pacer forward, honouring a capped `Retry-After`, so every caller backs off together. Clients on top of it:

| Client | Reads | Writes |
|---|---|---|
| `GhlWriteClient` | — | contacts upsert, opportunities upsert/create/update/details/stage/status/**delete** (Unit 69), contact tasks |
| `GhlPipelineClient` | pipelines, stages, opportunities in a window (optionally by status) | — |
| `GhlOpportunityClient` | opportunity search | — |
| `GhlCalendarClient` | calendars, free slots, contact appointments | book, reschedule, note |
| `GhlInvoiceClient` | invoices by contact | — |
| `GhlUserClient` | GHL users | — |
| `GhlCustomFieldClient` | custom field definitions | — |

**Ably (D50, D68)** relays, PostgreSQL is the record, and no browser token may publish. Two uses:
chat (`ChatFanout`, each member's `chat:user:{KIND}:{id}`) and **screen refresh** (Unit 70,
`chat/live/CaseLive`): a Hibernate listener (`CaseLiveHibernate`) sees every write of a `CaseOwned`
row (case, document, checklist item, offer, payout row; a draft comment via its document), and after
commit `CaseLive` publishes `case.changed {caseId}` — a signal, never data — to `live:brand:{brandId}`
and to the case's client and experts' private channels; `NotificationService` publishes
`notifications.changed` to the bell's owner. Screens re-read over their own scoped REST routes. JPQL
bulk writes to those tables call `CaseLive.touched` themselves (`PayoutService`'s two).

`DocumentStore` (S3): `put`, `presignedUrl` (always `attachment`) and the two view mints —
`presignedPdfView` for a known PDF and `presignedView` for any PDF / PNG / JPEG, both `inline` with
the type **forced** (Unit 74, D51) — and nothing else. Unconfigured = every document
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

**The ENM is a desk on one kind of pipeline** (Unit 63, D61). `Tier.SUPPLY` still reads every
expert and case of the brand; in addition an ENM's pipeline claim is derived from their brand's
`EXPERT_HIRING` pipelines (`TeamMemberPipelineRepository.ghlIdsFor`), so `PipelineScope` bounds
their board, stage moves, candidate creates and deal notes exactly as it bounds a Sales desk. A won
opportunity on such a pipeline is refused by `CaseIntakeService` — invariant 8's second lock.
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
11. ~~Outbound webhooks are HMAC-signed, retried, dead-lettered~~ — dropped with Handoff C (D53).
12. Webhook transport carries no business logic.
13. Every state transition writes an append-only audit row.
14. EvalOS hosts no files, and sends email for **two purposes and no others** — proving control of
    a mailbox (set password, reset password) and **confirming that a client's request was
    received** (one message, at submit). **Unit 64 (2026-09-29) removed the request and so the
    confirmation**. **Unit 64b (D65, 2026-09-30) adds the case-opened mail** — one per case, at
    `CASE_CREATED`: "your case has started — set your password" (a 7-day set-password link) for a
    client without a password, "your new case has started — sign in" (no credential) for one with.
    **Unit 64c (D58, D67, 2026-09-30) adds the case progress mails** — checklist (N documents to
    upload), draft ready, delivered (to the client), and the offer and signing mails (to the
    expert) — each once per transition, a deep link and never a credential — **and (2026-10-01)
    the chase reminder, sent only when a PC/CM presses Send chase** (`checklist.chased`). So the
    invariant is: mailbox proof, the case-opened mail, those five and the manual chase, **and (2026-10-10, D83) one
    test mail the Administrator sends to their own address from Settings**, and nothing
    else; the `DOC_CHASE` sweep's automatic reminders send no mail. **A push notification is not mail** and does not touch this
    invariant (D37: notifications are in-app and push, and nothing else).
    **Amended 2026-09-19, on the business's instruction.** It read *"exactly one purpose: proving
    control of a mailbox (two messages)"*, and the submission confirmation is not a mailbox proof —
    so it is an amendment rather than a reading of the old rule. Two things bound it: the
    confirmation **promises nothing the flow can fail to keep** (no price, no turnaround, no date —
    EvalOS holds no price list and the work is quoted by a person), and **nothing depends on it
    arriving**, so a failed send is logged and the submit still succeeds.
    The four-message expansion `00d` §12 (c) recommended was decided as D58 and built as Unit 64c
    (above).
15. **No AI makes a production decision, and there is no AI in the system at all.**

## Build-failing structural tests

| Test | Enforces |
|---|---|
| `DomainInvariantsTest` | only `GhlOpportunityHandler` creates a case; scope fields map to real attributes; audit repo cannot change history; brand stamping |
| `GhlHttpTest` | closed verb list; every write-verb caller reaches `AuditService` |
| `ConfigSecretsTest` | no credential-shaped setting carries a default in a shared profile |
| `GmOverviewRouteTest` | `/api/metrics/gm` stays `hasRole('GM')` |
| `MonitoringConfigTest` | Actuator is off but for health/info/metrics; prod and testprod never widen it or log at DEBUG/TRACE |
| `ActuatorEndpointsTest`, `ActuatorDatabaseDownTest` (DB) | anonymous probes see a status only; info/metrics Admin-only; dump/env/loggers absent; readiness DOWN and liveness UP when the DB is down |
| `LoggingHygieneTest` | request id on every line of a request; no password, hash, token or email in the log |
| `AdminSeedTest` (DB) | prod Admin seed preserves an existing Admin exactly, creates a missing one once, refuses ambiguity |
| `navigation.test.ts` (frontend) | every GHL-location screen is GM-only; case detail is not in nav |

## Configuration

**Precedence since D83 (2026-10-10, spec 85):** for the settings in `config/Setting.java` — SMTP host/port/username/
password/from, GHL token/location/correlation field, the selling brand and three targets, plus the outbound-email and
GHL-writes switches — **a value the Administrator saved in Settings (`app_setting`) beats every file and env var below**,
and applies on the next use with no restart (`AppSettings.app()`, ≤30 s stale on another instance). Everything else
still follows the order below, read once at boot.

Precedence that actually decides on a dev machine: `.env` (loaded by `.vscode/launch.json` as real
env vars) **beats** `backend/config/application-local.yml` **beats** `application-local.yml` on the
classpath. `backend/config/` is gitignored and is where a real GHL token goes.

Key settings: `evalos.ghl.{location-id, token, sales-brand, opportunity-service-field,
opportunity-correlation-field, board-stale-after, delta-ttl}` (`intake-pipeline-name` is retired — Unit 44b, D10b; the two funnel
screens took `sales-pipeline-name` and `email-pipeline-name` with them), `evalos.mail.from`, `evalos.portal.{client-brand, client-base-url,
expert-base-url, allowed-origins, credential-ttl}`, `evalos.s3.{bucket, region}`,
`evalos.security.jwt.secret`, `evalos.field-key`, `SALES_MONTHLY_GOAL` (fallback only — the GM sets each month's target on the dashboard, `sales_monthly_goal`).

## Deployment

`docker-compose.yml`: postgres 16 + backend (Spring, `prod,testprod`) + frontend (nginx, 80/443).
The backend now receives `ADMIN_EMAIL` and `ADMIN_PASSWORD_HASH` (required by the prod profile on every boot; it
failed on the unresolved placeholder without them). **nginx proxies only `/api/`**, so `/actuator` is reachable
only on the compose network (`backend:8080`) — a probe or scraper runs there, not through the public host.
CI (`.github/workflows/ci.yml`) runs on push to **`main` only**: backend tests, frontend
test/build/lint, then deploy to EC2. `client-expert/` is in neither compose nor CI — **and that is
not this repository's debt: DevOps owns and edits deployment (D38, 2026-09-17).** Know it when
reasoning about what is live; do not schedule work for it here.
