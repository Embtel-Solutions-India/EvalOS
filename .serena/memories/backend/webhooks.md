# backend/ — Inbound webhook gateway & Handoff A

Unit 05, re-pointed in 05a, re-pointed again by **Case Creation v2.0** (spec `05b`) — the live trigger
is now `opportunity.won`. One public endpoint per brand:
`POST /api/webhooks/ghl/{token}`. `webhook` package holds transport only — resolve, route, no business
logic (invariant 12).

## Authentication is the endpoint token, and nothing else

**There is no inbound HMAC as of 2026-08-27.** `WebhookVerifier` is deleted, `X-Evalos-Signature` is
neither read nor required, and `evalos.webhook.signature-header` is gone from `application.yml`. The
reason is the only one that matters here: **GHL's Custom Webhook action cannot compute an HMAC.** It
posts a URL, a content type and a JSON body, so requiring a signature meant Handoff A could not be
wired up from GHL's own UI at all — the guard made the integration unusable rather than safe.

What authenticates a delivery now:

- `brand.webhook_endpoint_token`, in the path, resolved by
  `findByWebhookEndpointTokenAndActiveTrue`. **That token is the credential** — treat it like a
  secret: never log it, never put it in a DTO (`BrandControllerTest` asserts the DTO omission), and
  rotate it to revoke an endpoint. Anyone holding it can post to that brand's endpoint.
- **Active is part of the check, not a separate one.** The `AndActiveTrue` in the query is what makes
  deactivating a brand stop its webhook. An unknown token and an inactive brand's real token are the
  same `404 UNKNOWN_ENDPOINT` with the same message — the caller learns nothing about which.
- The payload contract (below) is the rest of the gate: an unusable body is a `400` that never
  reaches a handler and never archives.

`brand.ghl_webhook_secret` still exists as a column (`V11`) and in the local seed (`V901`); the
**entity field and getter are gone**, so nothing reads it. Left in the schema on purpose — an applied
migration is never edited (invariant 9), and dropping a column nothing queries buys nothing.

## Gateway order, and why it is not one transaction

`WebhookGateway`: resolve brand → dedupe → archive → route → ack. Deliberately **not**
`@Transactional`: each step commits on its own, which is what lets the archive row outlive a failed
handler and record why. Do not wrap it.

The body is still `byte[]` from the controller, decoded to `String` as UTF-8 in the gateway — JSON's
charset by specification (RFC 8259). That began as an HMAC requirement ("hash the exact bytes
received") and outlived it as the plain right way to read a body: the archive then holds exactly the
text that was parsed and routed, whatever charset the sender declared. Both charsets are asserted in
`InboundWebhookTest`.

## Three identifiers, and what each one is allowed to mean

Confirmed as policy 2026-08-27 and already how the code works — do not let a future integration
blur it:

| Identifier | Lives on | Means | Never |
|---|---|---|---|
| `ghl_contact_id` | `contact_snapshot` | **the client**, canonically, across GHL, EvalOS and any future connected app | minted by EvalOS; changed when a case is created; replaced by a case id |
| `ghl_opportunity_id` | `evalos_case` | one purchase / deal | a client identity — a client has many |
| `evalos_case.id` / `case_code` | `evalos_case` | one service engagement, **internal** | exposed as the client's external identity |

**One contact, many cases.** `uq_case_open_per_contact_service` (`V15`) is keyed on
`(brand_id, contact_id, service_type) WHERE current_stage <> 'CLOSED'` — per *service*, so the same
client buying a second service opens a second case. There is deliberately no one-case-per-client rule.

**Matching precedence** (`CaseIntakeService.existingContact`): `ghl_contact_id` first, `email` only as
a fallback, and `syncContact` then backfills the id onto a row matched by email so it stops
re-matching that way. Never match a client on case id, name or phone.

**The fallback never overrides the id** (`contradicts`, + `V27`): an email match on a row that already
holds a *different* `ghl_contact_id` is refused, and intake creates a new snapshot instead. Two GHL
contacts sharing an inbox are two clients. Only a genuine conflict is refused — both ids present and
different — so the two cases the fall-through exists for still match: a row with no id yet, and a
delivery with no id to assert. See `backend/persistence.md` for why the index had to move with it.

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
  **Optional `notes` (Unit 23)** — whatever sales wrote on the opportunity. Never stored on the case:
  it becomes the `note` on the `CREATED` audit row, so it is the first entry on the case's Notes &
  timeline. Blank/whitespace is normalised to null. Optional on purpose — a required field here
  would fail Handoff A over a nicety.

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
could not have distinguished brands. `WebhookSource.DROPBOX_SIGN` has now been **deleted** — the enum is
`GHL` alone. (It previously said to leave the value in place; that is out of date.) Do not re-add it or
build a handler for it. Expert sign-off arrives as an authenticated portal request, not a callback.

The enum survives as an enum rather than becoming a constant because `source` is part of
`webhook_event`'s dedup key, so a second provider can be added without re-keying the table. Existing
`DROPBOX_SIGN` rows in a dev database are inert: the only query on the column is always called with
`GHL`, so they are excluded by the `WHERE` clause and never converted back to the enum. No cleanup
migration, and no DB `CHECK` without one.

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
