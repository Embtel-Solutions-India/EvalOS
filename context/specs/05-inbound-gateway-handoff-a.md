# Unit 05 — Inbound webhook gateway + GHL payment handler (Handoff A)

> **Built, superseded by Unit 05a, superseded again by Case Creation v2.0
> (`05b-opportunity-won-intake.md`).** The gateway half of this spec stands as
> written — verify, resolve brand, dedupe, archive, route, ack is unchanged in all
> three versions. **The handler half is dead twice over.** Handoff A now fires on
> **`opportunity.won`** and creates a case that is already **paid**, because GHL
> invoices and collects before the opportunity is marked Won.
>
> Dead here, and dead in 05a too: `payment.confirmed` / `GhlPaymentHandler`, then
> `contact.created` / `GhlContactHandler` — the live handler is
> `GhlOpportunityHandler`, and `contact.created` is now a recognized no-op.
> `POST /api/cases/{id}/mark-paid`, `CaseLifecycleService.markPaid` and the
> `MARK_PAID` transition are **deleted**: GHL is the only source of the payment fact.
> The `NEW_LEAD` alert is gone with the unpaid window, and the pool alert now goes to
> the **PM/Coordinator** pool, not GM/Brand-Manager.
>
> Still dead from the original draft: "idempotency key = `invoice_ref`" (now
> `event_id`, then `webhook_id` — never a bare `id`) and
> `UNIQUE (source, external_id)` (now `UNIQUE NULLS NOT DISTINCT (source, brand_id,
> external_id)`, `V13`). Case creation is still **only** through this door.
>
> Current truth: **`context/specs/05b-opportunity-won-intake.md`**,
> `context/architecture.md` (Handoff A + invariants 5 and 8), and the Case Creation
> v2.0 entry in `context/progress-tracker.md`.

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 03, 04
**Unlocks:** 18/19 (downstream of created cases). *(This line used to say Unit 15 would
reuse the gateway for Dropbox Sign callbacks. There is no signature provider, so the
gateway stays single-source — GHL.)*
**Gating open questions:** confirm the GHL `payment.confirmed` payload shape and
the per-brand signing secret before building the handler. `refund.requested` and
`contact.updated` are recognized by the router but deferred until their payloads
are confirmed.

## Goal

The single hardened way external events enter EvalOS, and its first handler: a
GHL "payment confirmed" webhook (the proof of payment) that idempotently creates
a brand-tagged case in the pool. This is the **only** path that may create a
case.

**Verifiable result:** a correctly signed `payment.confirmed` to a brand's
endpoint creates exactly one case at `DOC_COLLECTION` (pool), with a contact
snapshot, an opened checklist, and a GM/Brand-Manager pool notification; a replay
of the same invoice id creates nothing new; an unsigned or wrong-token request is
dropped with no side effect.

## In scope

- The reusable inbound gateway pipeline (verify → resolve brand → dedupe →
  archive → route → fast ack).
- The `webhook_event` idempotency/archive table.
- Per-brand GHL endpoints + signing-secret verification.
- The `payment.confirmed` handler (contact upsert, case create, checklist seed,
  pool notification, events).

## Out of scope

- Dropbox Sign handlers (Unit 15) — and now permanently out: the signature provider was
  dropped, so no second source ever arrives. The gateway is still built generic here.
- Building `refund.requested` / `contact.updated` behavior (deferred; router
  recognizes and no-ops).
- Outbound webhooks (Unit 18).

## Gateway pipeline (`webhook` package)
1. **Verify** the source signature/HMAC using the shared secret **before**
   deserializing the body. Fail → 400/401, log, no side effect.
2. **Resolve brand** from the path endpoint token → `brand_id`. Unknown token →
   404, no side effect.
3. **Deduplicate** on `(source, external_id)` where `external_id` is the
   invoice/payment id. Seen → mark duplicate, ack 200, no second side effect.
4. **Archive** the raw payload (JSONB) in `webhook_event` for audit/replay.
5. **Route** to the handler by event type; the handler calls a domain service.
6. **Ack fast** (200). Any processing failure returns a retriable 5xx so GHL
   re-delivers; the archived row records the error.

## Data / schema

### `webhook_event` (migration `V12`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| source | text NOT NULL | `GHL` \| `DROPBOX_SIGN` |
| event_type | text NOT NULL | e.g. `payment.confirmed` |
| external_id | text NOT NULL | idempotency key (invoice/payment/provider id) |
| brand_id | uuid | resolved from endpoint token |
| signature_verified | boolean NOT NULL | |
| raw_payload | jsonb NOT NULL | archived body |
| processed | boolean NOT NULL default false | |
| received_at / processed_at | timestamptz | |
| error | text | last failure, if any |
Unique: `(source, external_id)`. Index: `(brand_id, event_type)`.

### `brand` extension (migration `V11`)
Add `ghl_webhook_secret text` (per-brand HMAC secret for signature verification).

## Handler — GHL `payment.confirmed` (Handoff A)
Payload (to confirm): contact {name, email, phone, company, client_type,
source, utm}, service_type (+subtype), visa_category, selected_expert_id
(optional), quote_amount, deadline (optional), drive_link, invoice_ref,
campaign_attribution.

Actions (one transaction):
1. **Idempotency key** = `invoice_ref` (fallback: provider payment id).
2. **Upsert `ContactSnapshot`** for the brand from the GHL contact (read-only
   thereafter).
3. **Create `Case`**: brand-tagged, generated `case_code`, `pool_status =
   IN_POOL`, `current_stage = DOC_COLLECTION`, `exception_state = NONE`, deal
   value, deadline, drive_link, invoice_ref, campaign_attribution, service_type
   /subtype, visa_category, client_type, contact_id, `expert_id` if pre-selected.
4. **Seed `DocumentChecklistItem`s** from the service-type checklist template
   (config map keyed by `ServiceType`), each `REQUIRED`.
5. **Publish** `case.created` and `checklist.requested` (the latter → GHL sends
   the client the checklist, via Unit 18); **notify** the brand's GM + Brand
   Manager pool in-app (via Unit 06).
6. **Audit** a `CASE_CREATED` event (actor = SYSTEM/GHL).

Case creation happens **only** here (invariant 8).

## Endpoints
| Method | Path | Auth |
| --- | --- | --- |
| POST | /api/webhooks/ghl/{endpointToken} | public, signature-verified |

Dispatches by `event_type`: `payment.confirmed` (implemented);
`refund.requested`, `contact.updated` (recognized, deferred no-op with a logged
"not yet implemented").

## Acceptance criteria
- [ ] A signed `payment.confirmed` to a valid brand token creates exactly one
      case (DOC_COLLECTION, IN_POOL), a contact snapshot, an opened checklist, a
      pool notification to GM + Brand Manager, an audit entry, and publishes
      `case.created`.
- [ ] Re-posting the same invoice id creates no second case and no second side
      effect; the duplicate is recorded and acked 200.
- [ ] A bad/missing signature → rejected, nothing created, event logged.
- [ ] An unknown endpoint token → 404, nothing created.
- [ ] The created case carries the brand resolved from the endpoint token, never
      from the body.
- [ ] There is no other code path (no REST endpoint) that can create a case.
- [ ] A processing failure returns a retriable 5xx and the archived row shows the
      error; a redelivery succeeds without duplicating.
- [ ] `./mvnw verify` green; `V11`/`V12` apply and `validate` passes.

## Invariants honored
Payment enters only via a per-brand GHL webhook (8); verify → resolve brand →
dedupe → archive before any side effect (10); brand isolation (1); contact
snapshot read-only after upsert (7); transport carries no business logic — it
routes to a service (12); audit on creation (13); no `payment_detail` in any
payload (4).

## Files touched (created)
`.../webhook/{InboundWebhookController, WebhookVerifier, WebhookGateway,
GhlPaymentHandler, WebhookRouter}.java`, `.../domain/WebhookEvent.java`,
`.../repository/WebhookEventRepository.java`,
`.../service/{CaseIntakeService, ChecklistTemplates}.java`,
`db/migration/V11__brand_ghl_secret.sql`, `V12__webhook_event.sql`.
