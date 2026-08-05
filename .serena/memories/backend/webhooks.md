# backend/ — Inbound webhook gateway & Handoff A

Unit 05, re-pointed in 05a, re-pointed again by **Case Creation v2.0** (spec `05b`) — the live trigger
is now `opportunity.won`. One public endpoint per brand:
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
never add a bare `"id"` — and since v2.0, never `ghl_opportunity_id` either.** In most envelopes that
is the *resource's* id, so a returning client's second order carries the first one's key and is answered
`duplicate`; an opportunity id would do the same to an opportunity legitimately re-won. A payload with neither field is
**refused** (`MISSING_EXTERNAL_ID`) rather than processed once and hoped about — if GHL turns out to
send only a resource id, the answer is a delivery-id header, not a wider fallback list.

Enforcement is the unique index, not the lookup: a check-then-insert is a race two concurrent
deliveries both win.

## Handoff A — `opportunity.won` (Case Creation v2.0)

`WebhookRouter` maps event type → handler. Live: `opportunity.won` → `GhlOpportunityHandler`.
Recognized no-ops: `refund.requested`, `contact.updated`, **`contact.created`**.

**Neither contact event may route to intake.** `contact.updated` would technically work (intake is
create-or-update) but an edit in GHL is not a reason to open a case; `contact.created` was the v1
trigger and is now just a lead, which is GHL's business. A case exists only once the money is in.

**Firing one by hand needs three things at the top level of the body, and two of them are the
gateway's, not the handler's:** `event_type` (routing — its absence is `400 MISSING_EVENT_TYPE`),
`event_id` (idempotency), and then the `OpportunityWon` fields — where `service_type` is **top-level**,
the person is nested under `contact` as `full_name` / `ghl_contact_id`, and the money is nested under
`opportunity` as `amount` / `ghl_opportunity_id`. There is no `quote_amount` any more: the won
opportunity's amount is what was actually collected. Guessing that shape wrong costs a whole live run;
the working payload is in `mem:suggested_commands`.

- `GhlOpportunityHandler` parses **then** validates in full before calling the service, so a malformed
  delivery is a 400 GHL will not retry rather than a half-created case. The **payload shape is an
  assumption**, confined to `GhlOpportunityHandler.OpportunityWon` so a correction is one file. The
  transport record and `CaseIntakeService.NewCase` deliberately duplicate ~21 fields with a mapper
  between them — that split is what keeps an unconfirmed shape out of `service`. Do not "simplify" it.
- `service/CaseIntakeService` is **the only thing that creates a case** (invariant 8), enforced
  structurally: `DomainInvariantsTest` allows only `GhlOpportunityHandler` to depend on it, so adding a
  `POST /api/cases` breaks the build.
- Intake is **create-or-update**: one open case per contact per service, enforced by `V15`'s partial
  unique index (`WHERE current_stage <> 'CLOSED'`), not by the lookup. A refresh fills blanks and
  **can never move the case** — no stage reset, no dropped assignment, no un-paying — and publishes no
  lifecycle event, because nothing in the lifecycle happened. A second service opens a second case; a
  contact returning after close opens a new one.
  **`deal_value` AND `ghl_opportunity_id` are what a refresh OVERWRITES, and they move together.**
  The overwrite has to exist: deleting `markPaid` removed the only other writer, so fill-only would
  freeze the first figure forever with nothing able to correct it — and that figure feeds revenue
  recognition. GHL owns the amount, so the latest won figure wins. They move as a **pair** because
  they are halves of one fact arriving in one delivery: writing only the amount let a case carry
  opp-B's money under opp-A's id, and **Unit 18 closes whichever opportunity that column names**, so
  a stale id closes the wrong deal in GHL and leaves the paid one open. `V24` catches nothing here —
  no second case is created on the refresh path. `paid` / `paid_at` stay write-once; one value,
  never a running total, so a correction cannot double-count.
  **An amount correction is stated in the audit note and never quantified in it** — "deal value
  corrected", and only when the figure actually changed. `CaseSnapshot` omits `deal_value` (it is
  role-restricted), so without the note the row's before and after are byte-identical and a money
  rewrite reads as a no-op edit; putting the figure in would leak it, because `CaseTimelineService`
  shows the note to every role that may read the case, Case Managers included. The figures live in
  the `webhook_event` archive, which holds every delivery's raw body.
- The brand is **never** read from the payload — always the one the endpoint token resolved to.
  `AuditService.recordSystemEvent` takes the brand explicitly (the only place that is allowed) because
  a webhook has no authenticated caller; without it every case creation would audit against a null
  brand. Separately named so no request-scoped caller can reach it.
- `case_code` is `<initials>-<year>-<6 hex>`, random rather than a per-brand sequence (which needs a
  counter table and a lock). A collision hits the unique constraint and returns a retriable 5xx.
- `ChecklistTemplates` is a static map, not a table. It moves into the database the first time a Brand
  Manager must edit a checklist without a deploy; the seed for that table is this map.

Payment arrives **with** intake and nowhere else. The won opportunity is proof GHL already invoiced and
collected, so `newCase()` sets `paid` / `paid_at` itself and carries `deal_value` and
`ghl_opportunity_id` in from the payload. There is no `mark-paid` endpoint or transition to fall back
on — anything written against "a staff member records payment" is pre-v2.0 and wrong. The pool alert
goes to the **PM/Coordinator** pool, and `NEW_LEAD` is never raised. See `mem:backend/lifecycle`.

`V24` adds `ghl_opportunity_id` with `uq_case_open_per_opportunity`, per brand and **scoped to open
cases** (`WHERE … IS NOT NULL AND current_stage <> 'CLOSED'`), so a re-fired workflow cannot open a
second case for one opportunity — the same index-not-lookup reasoning as `V15`/`V16`. The
open-cases clause is load-bearing: unscoped, a re-used opportunity id on legitimate repeat business
becomes a constraint violation, and GHL retries a 5xx forever. See `mem:backend/persistence`.

**The gateway has exactly one source, GHL, and that is now settled rather than temporary.** A signature
provider was going to be the second, posting `signature_request.*` callbacks — dropping it (Production
Process v2.0) removed that, and with it the only place in the design that threatened the **protected
brand-resolution step**: one provider account means one callback URL, and the per-brand endpoint token
could not have distinguished brands. `WebhookSource.DROPBOX_SIGN` remains as an enum value nothing
writes; leave it, and do not build a handler for it. Expert sign-off arrives as an authenticated portal
request, not a callback.

## Outbound: the outbox *is* the queue

Unit 18's `webhook_delivery` table is the only queue mechanism in EvalOS, and that is a decision, not a
gap. Rows are written **in the same transaction as the domain change**, so a committed case change can
never lose its outbound event; `OutboxSender` (moved into `job` by Unit 19) claims them
`FOR UPDATE SKIP LOCKED`, retries on wall-clock backoff via `next_attempt_at`, and dead-letters at the
attempt ceiling with every attempt logged.

**Do not propose a broker.** The only cross-process work EvalOS has is "deliver one webhook to one
subscriber and keep trying" — retry-with-backoff over a durable row. There is no fan-out, no ordering
requirement across cases and no second consumer, and a broker would move the outbox *out* of the
transaction that guarantees it exists while adding something that can be down.

Client uploads (Unit 21) do **not** go through this: a human is waiting on the response, so it is
synchronous and fails loudly.
