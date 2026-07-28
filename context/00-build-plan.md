# EvalOS — Build Plan

The complete, ordered unit list. Each unit produces one visible, verifiable
result, stays inside one system boundary, and builds only on units before it.
Ordering follows: dependencies first, security before functionality, backend
before frontend wiring, UI shells before real data, install just-in-time.

Stack: Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) backend, React +
Vite + Tailwind frontend, monorepo `backend/` + `frontend/`. Multi-brand:
row-level tenancy by `brand_id`, brand-scoped queries throughout.

Sequence maps to the roadmap: Units 01–10 = Phase 1 (structure the data),
11–17 = Phase 2 (connect the seams), 18–20 = Phase 3 (close the loop).

Generate a `specs/NN-name.md` for a unit just before building it.

---

## Phase 1 — Structure the data (the spine)

### Unit 01 — Project scaffold & config
Builds: Spring Boot (Maven, Java 21) service with a health-check endpoint,
PostgreSQL via Spring Data JPA, Flyway wired for migrations, `application.yml`
profiles + externalized config; React/Vite + Tailwind frontend with the design
tokens from `ui-context.md`; monorepo layout; `./mvnw verify` and `npm run
build` both green.
Depends on: nothing.

### Unit 02 — Multi-tenancy + Auth & RBAC/ABAC (security foundation)
Builds: the Brand entity + tenancy plumbing (mandatory `brand_id` on scoped
entities, a query-layer scoping mechanism that injects `brand + team + assignee`
predicates); Spring Security + JWT for internal staff; the six roles as
authorities (`GM`, `BRAND_MANAGER`, `PROJECT_MANAGER`, `PROJECT_COORDINATOR`,
`CASE_MANAGER`, `EXPERT_NETWORK_MANAGER`); method-security (`@PreAuthorize`);
and a reusable brand/ownership-check helper in the service layer. No feature
endpoints yet — just the guard rails. (No Head-of-Evals role, no interns.)
Depends on: 01.

### Unit 03 — Domain model & migrations
Builds: JPA entities + repositories + Flyway migrations for Brand, TeamMember,
ContactSnapshot (read-only), Case, Expert, PayoutLedger, DocumentChecklist,
Notification (in-app), and the append-only AuditTrail. `Stage` / `PayoutStatus`
/ `ServiceType` / `VisaCategory` / `Role` enums. Every scoped entity carries
`brand_id` + an audit hook. Field-level encryption `AttributeConverter` for the
single optional expert `payment_detail`. Compound indexes on
`(brand_id, team_id, assigned_to, stage)`, `(brand_id, deadline)`,
`(brand_id, sla_status)`.
Depends on: 02.

### Unit 04 — Case lifecycle service (state machine)
Builds: the 8-stage internal state machine (`DOC_COLLECTION → EXPERT_ASSIGNMENT
→ DRAFT_GENERATION → EXPERT_SIGNING → FINAL_DELIVERY → CLOSED`, plus exception
states `ON_HOLD_AWAITING_CLIENT`, `EXPERT_DECLINED_REMATCHING`,
`REFUND_REQUESTED`); declared-transition-only enforcement; `@Transactional`
transition methods; an audit entry on every transition; the pool→PM→CM
assignment model; SLA-status computation on the Pacific business calendar; the
GM-only refund transition (revenue reversal + pending-payout void + GHL signal);
and the brand-scoped case REST controller. Each transition publishes an internal
domain event for the outbound dispatcher (Unit 18).
Depends on: 02, 03.

### Unit 05 — Inbound webhook gateway + GHL payment handler (Handoff A)
Builds: the reusable inbound gateway (secret verification, **per-brand endpoint
→ brand_id resolution**, idempotency on invoice/payment id, raw-payload archival,
handler routing, fast ack) and its first handler — GHL `payment.confirmed` →
contact-snapshot sync, brand-tagged case creation at `DOC_COLLECTION` in the
brand pool, document-checklist open, GM/Brand-Manager pool notification. Reused
by Dropbox Sign in Unit 15. This is the only path that may create a case.
Depends on: 03, 04. (Confirm GHL payload + per-brand signing secret first.
`refund.requested` / `contact.updated` handlers recognized but deferred.)

### Unit 06 — In-app notification center
Builds: the Notification service + brand-scoped staff notification center
(create/list/mark-read), fed by domain events (assignment, SLA breach,
escalation, KPI flag). Client-facing notifications are emitted as domain events
for GHL to deliver — EvalOS sends no email.
Depends on: 04.

### Unit 07 — App shell + role/brand-scoped dashboard routing (UI shell)
Builds: the internal React app shell (left nav, top bar with global date filter,
brand switcher — all-brands/filter for GM, locked for everyone else, notification
bell), role-scoped routing, and empty/placeholder dashboard states.
Depends on: 02, 06.

### Unit 08 — Production Kanban board
Builds: the stage-column board wired to the case API (EvalOS stages: Doc
Collection · Expert Assignment · Draft/Report · Expert Signing · Final Delivery,
plus exception lanes), RAG deadline badges, brand + role-filtered views (pool +
unassigned queue for GM/BM/PM; own docket for CM).
Depends on: 04, 07.

### Unit 09 — Case detail page
Builds: the two-column case view — documents (Drive link) / draft / expert on
the left, the timeline/audit trail on the right — with stage-action controls,
PM strategy notes, and the draft sub-status chips (PM review / client review).
Depends on: 04, 08.

### Unit 10 — Document checklist board + Coordinator flow
Builds: the Coordinator's checklist board (required/uploaded/missing status
against the Drive link), mark-docs-complete → push to PM, and the doc-collection
SLA/reminder hooks. Client chase messages are emitted as domain events for GHL
to send (no EvalOS email).
Depends on: 04, 09.

---

## Phase 2 — Connect the seams

### Unit 11 — Expert database (ENM) + sheet upload
Builds: the Expert entity/repository detail (brand-scoped; field-tag taxonomy,
letter types, tier, availability, quality score, fee, turnaround/decline
history), the optional encrypted `payment_detail`, ENM CRUD endpoints, the
availability board, and **bulk sheet upload (CSV/XLSX import mapped to fields)**
as the roster's primary maintenance path.
Depends on: 03.

### Unit 12 — Match scoring engine (assist mode)
Builds: the rule-based ranked top-3 shortlist service (field match + letter-type
experience + acceptance rate + current load), brand-scoped, surfaced to the PM
at assignment. Suggests only; a human confirms. (AI-enhanced ranking/anomaly
detection is Phase 3.)
Depends on: 11, 04.

### Unit 13 — Redacted CV generation
Builds: template-based redacted profile generation (name/institution/contact
stripped), generated on demand, with full-profile release on payment. No object
storage — output is served on demand (or written to the case's Drive folder).
Depends on: 11.

### Unit 14 — Client draft-review portal
Builds: the separate, scoped filter chain for passwordless client access (link
delivered via GHL), the single-page draft view, approve / request-revisions
actions, and read-receipt tracking. Draft-review only — no source-doc upload.
Depends on: 02 (separate auth surface), 04.

### Unit 15 — Expert portal + Handoff B + sign-off
Builds: the separate scoped filter chain for expert access (link via Dropbox
Sign / CM-shared), the single-column assigned-case view (draft + evidence +
goal), send-to-expert (client-approved → `EXPERT_SIGNING`), the accept /
request-evidence (opens client task) / decline (→ `EXPERT_DECLINED_REMATCHING`)
paths, Dropbox Sign integration for signing, the Dropbox Sign **inbound webhook**
callbacks (signed/declined/viewed via the Unit 05 gateway), and the 20h/24h SLA
timer + auto-reassign job.
Depends on: 05 (inbound gateway), 12, 14.

### Unit 16 — Payout ledger (manual)
Builds: payout entry auto-created (status Pending) when a case reaches
Delivered, tied to case + invoice; a **manual form** for the responsible team
member to record method/reference/amount/date and mark Paid/Confirmed; status
tracking; the weekly batch view; and expert-facing payout status in the portal.
Ledger only — no disbursement rail, no payment-platform integration.
Depends on: 03, 11.

---

## Phase 3 — Close the loop

### Unit 17 — Dashboards (read models)
Builds: the production-side role dashboards (GM cross-brand; Brand Manager, PM,
Coordinator, Case Manager, ENM within brand) — money-in vs. delivered (open
liability), cycle time by stage, expert utilization & acceptance rate, review
capture — reading precomputed read models refreshed on events.
Depends on: 04, 11, 16.

### Unit 18 — Outbound webhook dispatcher + Handoff C (delivered)
Builds: the reusable outbound dispatcher (subscribes to the domain events
published since Unit 04; subscriber registry; HMAC-signed payloads; retry with
backoff; dead-letter; delivery log + replay) and its first live events —
`case.delivered` → GHL's inbound automation URL to start the review + referral
track and stamp closed value; client-notification triggers → GHL. Creates the
payout ledger entry in the same transaction and syncs delivered/active contacts
to GHL's suppression list.
Depends on: 04 (domain events), 16. (Confirm GHL subscriber URL + secret first.)

### Unit 19 — Background jobs consolidation
Builds: the full `job` package backed by the `scheduled_job` table —
doc-collection reminders (24h/48h), stage-SLA escalations, expert sign 20h/24h
alerts + auto-reassign, retention/countdown timers, and the delivery → Handoff C
dispatch — on the Pacific business calendar.
Depends on: 05, 10, 15, 18.

### Unit 20 — AI widgets (later)
Builds: KPI anomaly detection (>15% vs 4-week rolling avg) and AI-enhanced expert
suggestion layered on top of the Unit 12 rule-based shortlist. Assist-only.
Depends on: 12, 17.

---

## Notes

- Automation rules A05–A24 from the CRM spec (production, expert, delivery, KPI
  alerts) are covered across Units 04–19. Rules A01–A04 and A06 (lead/sales/
  marketing) are GHL's responsibility and out of scope.
- **Multi-tenancy is not a unit — it is a property of every unit.** From Unit 02
  onward, every scoped query filters by `brand_id`; brand resolution at Handoff A
  is by per-brand endpoint token.
- **No object storage, no mail server.** Documents are Drive links; signed
  letters are in Dropbox Sign; staff alerts are in-app (Unit 06); client/expert
  messages go through GHL / Dropbox Sign.
- **Webhook subsystem spans units**: inbound gateway built once in Unit 05 (GHL)
  and reused in Unit 15 (Dropbox Sign); outbound dispatcher built once in Unit 18
  and delivers domain events published from Unit 04 onward.
- Open questions gate specific units (see `progress-tracker.md`): the **full
  brand list**, whether EvalOS **builds the sales/marketing dashboards** (default
  no — GHL), **StatCommand**, the **GHL webhook/API contract** (per-brand inbound
  secret + payload for Unit 05; outbound subscriber URL + secret and client-
  message capability for Unit 18), the **Dropbox Sign callback secret** (Unit 15),
  and **staff SSO** (optional/later). Resolve each before starting the gated unit.
