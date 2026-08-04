# backend/ — Inbound webhook gateway & Handoff A

Unit 05, re-pointed in 05a. One public endpoint per brand:
`POST /api/webhooks/ghl/{token}`. `webhook` package holds transport only — verify, route, no business
logic (invariant 12).

## Gateway order, and why it is not one transaction

`WebhookGateway`: resolve brand → verify → dedupe → archive → route → ack. Deliberately **not**
`@Transactional`: each step commits on its own, which is what lets the archive row outlive a failed
handler and record why. Do not wrap it.

Brand resolution runs before verification even though the spec lists it second — the HMAC secret
belongs to the brand, and a lookup is not a side effect.

- `WebhookVerifier`: HMAC-SHA256 over the **exact bytes received**, compared with
  `MessageDigest.isEqual`. Missing secret, missing header, bad hex and a wrong digest all fail
  identically — nothing is learnable from the response. Header name is configuration
  (`evalos.webhook.signature-header`, default `X-Evalos-Signature`) because GHL's real header is
  unconfirmed.
- **A rejected signature is logged, not archived.** `webhook_event` only ever holds deliveries that
  verified, so `signature_verified` is always true today. Archiving unverified bodies would let anyone
  who can reach the URL fill the table.

## Idempotency — the two mistakes already made here

1. **"Already seen" is not "already done."** Only a row with `processed = true` is a duplicate. A
   handler failure archives the row *unprocessed* and returns a retriable 5xx; the redelivery must
   reuse that row and retry. Short-circuiting on mere presence silently lost paid cases. Regression
   test: `InboundWebhookTest.aRedeliveryAfterAFailureRetriesInsteadOfLookingLikeADuplicate`.
2. **The key is scoped by brand** — `UNIQUE NULLS NOT DISTINCT (source, brand_id, external_id)`
   (`V13`). Each brand is a separate GHL sub-account numbering its own invoices, so a brand-agnostic
   key made one brand's `INV-0001` swallow another's. `NULLS NOT DISTINCT` because `brand_id` is
   nullable and Postgres would otherwise treat two brand-less rows as distinct, losing exactly the
   deduplication the constraint exists for.

`EXTERNAL_ID_FIELDS = { "event_id", "webhook_id" }`, in order. **Both are delivery-scoped on purpose;
never add a bare `"id"`.** In most envelopes that is the *resource's* id, so a returning client's
second order carries the first one's key and is answered `duplicate`. A payload with neither field is
**refused** (`MISSING_EXTERNAL_ID`) rather than processed once and hoped about — if GHL turns out to
send only a resource id, the answer is a delivery-id header, not a wider fallback list.

Enforcement is the unique index, not the lookup: a check-then-insert is a race two concurrent
deliveries both win.

## Handoff A — `contact.created`

`WebhookRouter` maps event type → handler. Live: `contact.created` → `GhlContactHandler`. Recognized
no-ops: `refund.requested`, `contact.updated`. **`contact.updated` must not route to intake** — intake
is create-or-update so it would technically work, but an edit in GHL is not a reason to open a case.

**Firing one by hand needs three things at the top level of the body, and two of them are the
gateway's, not the handler's:** `event_type` (routing — its absence is `400 MISSING_EVENT_TYPE`),
`event_id` (idempotency), and then the `ContactCreated` fields — where `service_type` is **top-level**
and the person is nested under `contact` as `full_name` / `ghl_contact_id`. Guessing that shape wrong
costs a whole live run; the working payload is in `mem:suggested_commands`.

- `GhlContactHandler` parses **then** validates in full before calling the service, so a malformed
  delivery is a 400 GHL will not retry rather than a half-created case. The **payload shape is an
  assumption**, confined to `GhlContactHandler.ContactCreated` so a correction is one file. The
  transport record and `CaseIntakeService.NewCase` deliberately duplicate ~21 fields with a mapper
  between them — that split is what keeps an unconfirmed shape out of `service`. Do not "simplify" it.
- `service/CaseIntakeService` is **the only thing that creates a case** (invariant 8), enforced
  structurally: `DomainInvariantsTest` allows only `GhlContactHandler` to depend on it, so adding a
  `POST /api/cases` breaks the build.
- Intake is **create-or-update**: one open case per contact per service, enforced by `V15`'s partial
  unique index (`WHERE current_stage <> 'CLOSED'`), not by the lookup. A refresh only fills blanks and
  **can never move the case** — no stage reset, no dropped assignment, no un-paying — and publishes no
  lifecycle event, because nothing in the lifecycle happened. A second service opens a second case; a
  contact returning after close opens a new one.
- The brand is **never** read from the payload — always the one the endpoint token resolved to.
  `AuditService.recordSystemEvent` takes the brand explicitly (the only place that is allowed) because
  a webhook has no authenticated caller; without it every case creation would audit against a null
  brand. Separately named so no request-scoped caller can reach it.
- `case_code` is `<initials>-<year>-<6 hex>`, random rather than a per-brand sequence (which needs a
  counter table and a lock). A collision hits the unique constraint and returns a retriable 5xx.
- `ChecklistTemplates` is a static map, not a table. It moves into the database the first time a Brand
  Manager must edit a checklist without a deploy; the seed for that table is this map.

Payment is **not** part of this flow any more — see `mem:backend/lifecycle`.
