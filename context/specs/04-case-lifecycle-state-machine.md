# Unit 04 — Case lifecycle service (state machine)

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 02, 03
**Unlocks:** 05 (Handoff A creates cases through this), 06 (events → notifications),
08/09 (board + detail act on transitions), 15 (expert paths), 16/18 (payout + Handoff C consume events)
**Gating open questions:** none

## Goal

Turn the `Case` row into a **formal state machine**: only declared transitions
are allowed, each runs in a transaction, writes an append-only audit entry, and
publishes an internal domain event. Also: the pool→PM→CM assignment model, SLA
status on the Pacific business calendar, and the GM-only refund path.

**Verifiable result:** a case walks the legal path via REST; an illegal
transition is rejected (409); every transition writes one audit entry and
publishes one event; SLA status computes correctly against the business
calendar; a GM refund reverses recognition and voids the pending payout.

## In scope

- The `Stage` state machine (declared transitions only) + exception states.
- Assignment actions (pool → PM → CM) and the guards that gate stage progress.
- The draft/PM/client sub-loops inside `DRAFT_GENERATION`.
- SLA-status computation + a `BusinessCalendar` (America/Los_Angeles, 9–5 PT, US
  federal holidays).
- GM-only refund (revenue reversal + pending-payout void + refund event).
- Domain events on every transition; brand-scoped case REST controller.

## Out of scope

- Case *creation* — only Handoff A (Unit 05) creates a case.
- Delivering notifications (Unit 06) or outbound webhooks (Unit 18) — Unit 04
  only *publishes* events.
- Payout row creation (Unit 16 subscribes to `case.delivered`).
- Expert-signed / declined callbacks (Unit 15 calls the transition methods).
- SLA timer *jobs* / reminders (Unit 19); Unit 04 provides the computation only.

## State machine

Stages: `DOC_COLLECTION → EXPERT_ASSIGNMENT → DRAFT_GENERATION → EXPERT_SIGNING →
FINAL_DELIVERY → CLOSED`. Exception states: `ON_HOLD_AWAITING_CLIENT`,
`EXPERT_DECLINED_REMATCHING`, `REFUND_REQUESTED`.

### Transition table
| From | Action (service method) | To | Actor role | Guard |
| --- | --- | --- | --- | --- |
| any (IN_POOL) | `assignPm(pmId)` | same stage, `ASSIGNED` | GM, Brand Mgr | pool_status = IN_POOL |
| DOC_COLLECTION | `markDocsComplete()` | EXPERT_ASSIGNMENT | Coordinator, PM | assigned_pm set; all checklist items APPROVED/UPLOADED |
| EXPERT_ASSIGNMENT | `assignCaseManager(cmId, expertId)` | DRAFT_GENERATION | PM | expertId valid + available; cm in same brand/team |
| DRAFT_GENERATION | `submitDraft()` | DRAFT_GENERATION | Case Mgr | draft_version_count++; pm_approval = PENDING |
| DRAFT_GENERATION | `pmReturnDraft(comments)` | DRAFT_GENERATION | PM | pm_approval = PENDING → RETURNED |
| DRAFT_GENERATION | `pmApproveDraft()` | DRAFT_GENERATION | PM | pm_approval → APPROVED |
| DRAFT_GENERATION | `sendDraftToClient()` | DRAFT_GENERATION | Coordinator | pm_approval = APPROVED; client_approval → PENDING |
| DRAFT_GENERATION | `clientRequestRevisions(notes)` | DRAFT_GENERATION | client (Unit 14) | client_approval → REVISION_REQUESTED |
| DRAFT_GENERATION | `clientApproveDraft()` | EXPERT_SIGNING | client (Unit 14) | client_approval → APPROVED |
| EXPERT_SIGNING | `expertSigned()` | EXPERT_SIGNING | expert (Unit 15) | expert_sign_status → SIGNED |
| EXPERT_SIGNING | `expertDeclined(reason)` | EXPERT_DECLINED_REMATCHING | expert (Unit 15) | logs reason |
| EXPERT_DECLINED_REMATCHING | `reassignExpert(expertId)` | EXPERT_ASSIGNMENT | PM, ENM | new expert valid |
| EXPERT_SIGNING | `pmQcApprove()` | FINAL_DELIVERY | PM | expert_sign_status = SIGNED |
| FINAL_DELIVERY | `deliverToClient()` | FINAL_DELIVERY | Coordinator | sets delivery_date; emits `case.delivered` |
| FINAL_DELIVERY | `confirmReceiptAndClose()` | CLOSED | Coordinator | sets case_closed_date; emits `case.closed` |
| active | `putOnHold()` / `resumeFromHold()` | exception ↔ prior stage | Coordinator, PM | records reason |
| active | `requestRefund()` | exception REFUND_REQUESTED | any staff / GHL | — |
| REFUND_REQUESTED | `approveRefund()` | CLOSED (refunded) | **GM only** | reverse recognition + void pending payout + emit `case.refunded` |
| REFUND_REQUESTED | `denyRefund()` | prior stage (exception → NONE) | **GM only** | — |

Illegal (from,action) pairs throw `IllegalTransitionException` → HTTP 409. Every
successful transition calls `AuditService.recordEvent(...)` and publishes a
domain event inside the same `@Transactional` boundary.

### Refund semantics
`approveRefund()` (GM): if the case was Delivered, its value is removed from
revenue-recognition (dashboards read it as reversed, not earned); any
`PayoutLedger` row for the case in `PENDING` is set `VOIDED`; a `case.refunded`
event is published (Unit 18 relays a refund signal to GHL); the case moves to
CLOSED flagged refunded.

## SLA computation
- `BusinessCalendar`: `America/Los_Angeles`, working hours 09:00–17:00 PT,
  weekends + US federal holidays excluded. Utility to add/subtract business
  hours and to measure elapsed business time.
- `sla_status` per stage from the stage's SLA budget (Doc collection 3 business
  days; Expert assignment 4h; first draft 48h / PM review 12h / client review 48h
  per round; expert sign 24h; delivery 2h of QC) → `ON_TRACK` / `AT_RISK`
  (<threshold remaining) / `OVERDUE`. Computed on read and refreshed on
  transition; the *alerting jobs* are Unit 19.

## Domain events (published; delivered later)
`documents.completed`, `expert.assigned`, `draft.submitted`,
`draft.pm_approved`, `draft.returned`, `draft.client_approved`,
`draft.revision_requested`, `expert.signed`, `qc.approved`, `case.delivered`,
`case.closed`, `case.on_hold`, `case.refund_requested`, `case.refunded`, plus
client-notification triggers (`checklist.requested`, `draft.ready_for_client`,
`case.delivered_to_client`). Payloads carry brand/case/contact/attribution refs
only — never `payment_detail` or internal notes.

## Endpoints (brand-scoped, role-guarded)
| Method | Path | Actor |
| --- | --- | --- |
| GET | /api/cases | scoped list (filters: stage, deadline, sla) |
| GET | /api/cases/{id} | scoped read |
| POST | /api/cases/{id}/assign-pm | GM, Brand Mgr |
| POST | /api/cases/{id}/assign-cm | PM |
| POST | /api/cases/{id}/docs-complete | Coordinator, PM |
| POST | /api/cases/{id}/draft/submit \| /pm-approve \| /pm-return \| /send-to-client | CM / PM / Coordinator |
| POST | /api/cases/{id}/qc-approve | PM |
| POST | /api/cases/{id}/deliver \| /close | Coordinator |
| POST | /api/cases/{id}/hold \| /resume | Coordinator, PM |
| POST | /api/cases/{id}/refund/request | any staff |
| POST | /api/cases/{id}/refund/approve \| /deny | **GM** |

`clientApproveDraft` / `clientRequestRevisions` are exposed to the client portal
(Unit 14); `expertSigned` / `expertDeclined` to the expert surface (Unit 15).
Both call the same guarded service methods.

## Acceptance criteria
- [ ] A case created in DOC_COLLECTION walks DOC→ASSIGN→DRAFT→SIGN→DELIVER→CLOSED
      via the endpoints; each hop is 200 and moves exactly one stage.
- [ ] An illegal transition (e.g. `qc-approve` from DRAFT_GENERATION) returns 409
      and changes nothing.
- [ ] Every successful transition writes exactly one `audit_event` and publishes
      exactly one domain event (assert via a test subscriber).
- [ ] Role/brand guards hold: a Case Manager cannot `assign-pm`; a non-GM cannot
      `refund/approve`; no caller can act on another brand's case.
- [ ] `markDocsComplete` is blocked until all checklist items are complete and a
      PM is assigned.
- [ ] SLA status computes On track/At risk/Overdue correctly across a weekend +
      one federal holiday (business-calendar test).
- [ ] `approveRefund` on a delivered case: revenue reversed, the case's PENDING
      payout → VOIDED, `case.refunded` published, case CLOSED.
- [ ] `./mvnw verify` green.

## Invariants honored
Declared-transition-only with audit + event per hop (3, 13); brand + role +
ownership before every mutation (1, 3); `Delivered` is the sole recognition event
and refund reverses it (5); no long-lived work in the controller (6);
`payment_detail`/internal notes never in event payloads (4, 11).

## Files touched (created)
`.../service/{CaseLifecycleService, CaseTransitions, BusinessCalendar,
SlaCalculator, RefundService}.java`, `.../domain/IllegalTransitionException.java`,
`.../event/CaseEvents.java` (event types), `.../web/CaseController.java` (+ DTOs),
`.../repository/CaseRepository.java` (scoped finders). No new migration (operates
on the Unit 03 schema).
