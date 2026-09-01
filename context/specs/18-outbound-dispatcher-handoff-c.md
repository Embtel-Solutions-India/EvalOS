# Unit 18 — Outbound webhook dispatcher + Handoff C (delivered)

> # ⚠ **REMOVED (2026-09-02). Never built. This spec is history, not a plan.**
>
> **Two handoffs now, not three.** EvalOS emits nothing outbound at all: its integration
> surface is inbound GHL webhooks and read-only pulls of GHL's funnels. Domain events still
> publish in-process; the notification centre is their only consumer.
>
> **What survives:** the payout ledger entry on delivery, inside `deliverToClient`'s
> transaction. That was always Unit 16's, not this one's.
>
> **What is genuinely lost, and is a manual step now rather than a gap to rediscover:**
> nothing tells GHL a case was delivered, so the review sequence, the referral track and
> the suppression-list sync are started **by hand**. The document chase and "your draft is
> ready" likewise have no automated route to the client.
>
> **Invariant 14's "sends no email" stops being a pending decision** and becomes the
> architecture — there is no outbound channel left to argue about.

**Phase:** 3 — Close the loop
**Depends on:** 04 (the domain events, published since Unit 04 to nobody), 16 (the
payout entry that shares delivery's transaction)
**Unlocks:** 19 (which consolidates this unit's sender into the `job` package), and
every client-facing message in the product
**Gating open questions:** the **GHL outbound contract** — the subscriber URL, the
signing secret, **whether GHL can send a client-facing transactional message on an
EvalOS event trigger** (the question Unit 14's portal link also depends on), and
API credentials for the suppression sync. None of it is confirmed. Confirm before
starting.

> **Written ahead of its code.** Specs 11–20 were written in one pass, so this one
> describes a system whose Phase-2 foundations do not exist yet. Re-read it against
> the code at the start of the unit and revise it — particularly the event list,
> which Units 12, 14 and 15 each add to.

## Goal

Since Unit 04 every transition has published a domain event and **nothing has ever
subscribed**. This unit is the other half: a dispatcher that delivers those events
to registered external subscribers, signed, retried, logged and replayable — and
its first live use, Handoff C, which tells GHL a case was delivered so the review
and referral track starts.

**Verifiable result:** delivering a case sends one signed `case.delivered` request
to GHL's automation URL; a subscriber that is down is retried with backoff and
dead-lettered rather than losing the event; the delivery log shows every attempt
with its response; a dead-lettered delivery can be replayed by hand; and no payload
ever contains `payment_detail` or an internal note.

## In scope

- The reusable outbound dispatcher: subscriber registry, HMAC signing, retry with
  backoff, dead-letter, delivery log, replay.
- **Handoff C**: `case.delivered` → GHL.
- Client-notification triggers → GHL (`checklist.requested`,
  `checklist.reminder`, `draft.ready_for_client`, `case.delivered_to_client`, and
  the ones Unit 15 added).
- The GHL **suppression-list sync** for delivered and active contacts.

## Out of scope

- **Creating the payout entry.** The build plan attributes it here; **Unit 16
  already owns it**, inside `deliverToClient`'s transaction. This unit does not
  create a second one and does not move it. Corrected here rather than duplicated —
  two writers of one row is how a case gets two payouts.
- **The scheduler.** This unit ships a minimal sender so it can be verified;
  Unit 19 consolidates it into the `job` package on the `scheduled_job` table. See
  the split below.
- Inbound anything. The gateway is Unit 05's and is untouched.
- A message broker. `architecture.md`'s NFRs are explicit: no broker, one Spring
  Boot app and one Postgres. The outbox table below is the queue.
- Subscriber self-service (a UI for third parties to register). Subscribers are
  seeded by migration and configuration; GHL is the only one.

## The transactional outbox, and why the dispatcher cannot just send

`CaseLifecycleService.apply` publishes its `CaseEvent` **inside the transition's
transaction**, synchronously. A dispatcher that subscribes and makes an HTTP call
there would break two things at once:

- **Invariant 6** — a controller would be waiting on an external network call.
- **Correctness** — a subscriber that is slow or failing would slow down or roll
  back a transition. GHL being down must not stop EvalOS delivering a case.

The other obvious option, `@TransactionalEventListener(AFTER_COMMIT)`, fixes the
rollback but loses the event if the process dies between commit and send — and
Handoff C is the trigger for the business's #2 health metric, so silently losing one
is not acceptable.

**So the listener writes a row, and a sender sends it.** In the transition's
transaction, one `webhook_delivery` row per (event, matching subscriber), status
`PENDING`. It commits with the transition or not at all — the same guarantee
`AuditService` gives by joining the caller's transaction. Then a separate sender
picks up `PENDING` rows and delivers them.

The outbox **is** the delivery log required by invariant 11. One table, not two: an
attempt log that is separate from the queue is two records of one delivery that can
disagree.

## Tables

New migrations (next free `V`-numbers).

`webhook_subscriber`:

| column | note |
| --- | --- |
| `id`, `brand_id`, `created_at` | `brand_id` **nullable** — a subscriber may be brand-specific (each brand's GHL sub-account) or global |
| `name` | e.g. `GHL_IE` |
| `target_url` | where to post |
| `secret` | the HMAC key. **Nullable, and that fails closed** — see below. Never returned by any endpoint, the same rule `BrandOption` follows for the brand's webhook token. **Encrypted at rest** via the `payment_detail` converter (Unit 11), not stored as plaintext: it is a signing key, so anyone holding it can forge any event to that subscriber, and a database dump or an errant `SELECT *` in a log should not hand it over. The same argument already applied to `brand.ghl_webhook_secret` and applies here with the direction reversed |
| `event_types` | `text[]` of subscribed wire names |
| `active` | a subscriber is disabled, not deleted |

`webhook_delivery`:

| column | note |
| --- | --- |
| `id`, `brand_id`, `created_at` | |
| `subscriber_id`, `event_type`, `payload` (`jsonb`) | what is being sent to whom |
| `case_id` | nullable; for finding a case's deliveries |
| `status` | `PENDING` · `DELIVERED` · `FAILED` · `DEAD` |
| `attempts`, `next_attempt_at` | the backoff state |
| `last_status_code`, `last_error`, `last_attempt_at` | the log |
| `replay_of` | nullable self-reference; a replay is a **new row**, never an edit |

Indexes: `(status, next_attempt_at)` for the sender's claim query,
`(brand_id, event_type, created_at)` for the log view, `(case_id)`.

**The secret is nullable and comes from the environment, not from a migration.**
An earlier draft of the row above said "written by migration/config", which would
put a live signing key in a file that ships in the jar and sits in git history.
The inbound half already settled this the other way and its reasoning transfers
exactly: `V11__brand_ghl_secret.sql` made the brand's secret nullable *because*
that fails closed — "a brand with no secret cannot verify anything, so every
webhook to its endpoint is rejected until the secret is set. The alternative (a
`NOT NULL` with a default) would ship a known secret." So here: the column is
nullable, **a subscriber with no secret is never delivered to** (an unsigned
outbound payload would breach invariant 11), the real value is set out of band from
env-backed config, and literal values appear only in the `local` seed — the same
split `local/V901__seed_local_webhook_secrets.sql` already uses, with the same
throwaway-values note.

**A delivery row is never rewritten to hide a failure.** Attempts increment on the
row; a *replay* creates a new row pointing at the old one, so the log tells the
truth about how many times something was sent. This is not the audit table and has
no trigger, but it follows the same instinct: the record of what happened does not
get edited into the record of what we wish had happened.

## Signing

`X-EvalOS-Signature: sha256=<hex>` over `"<timestamp>.<raw body>"`, plus
`X-EvalOS-Timestamp`. HMAC-SHA256 with the subscriber's secret.

- The timestamp is in the signed material so a captured request cannot be replayed
  against the subscriber indefinitely — the same reasoning the inbound side applies,
  from the other end.
- **The window is five minutes, and it is part of the published contract.** A signed
  timestamp nobody checks against a window is decoration twice over: the signature
  stays valid forever, so a captured request replays forever, which is the exact
  property the timestamp was added to remove. Document that a subscriber must reject
  anything whose `X-EvalOS-Timestamp` is more than 300 seconds from its own clock, in
  **either** direction — a future-dated stamp is as suspicious as a stale one, and
  allowing it lets an attacker mint a request that stays fresh. Five minutes is chosen
  to absorb ordinary NTP skew between two hosts without leaving a usefully long replay
  window; EvalOS sends its own clock and does not compensate for a subscriber's.
  Retries carry a **fresh** timestamp and therefore a fresh signature, which is why
  `X-EvalOS-Delivery` and not the signature is the deduplication key.
- **The scheme is documented in the delivery-log UI and the README**, because a
  signature the subscriber cannot verify is decoration. GHL has to implement the
  check.
- `X-EvalOS-Event`, `X-EvalOS-Delivery` (the row id) and `X-EvalOS-Attempt` headers,
  so a subscriber can deduplicate on the delivery id — the courtesy the Unit 05
  gateway needs from GHL and has been missing.

## Retry, dead-letter, replay

- **Backoff:** attempt at 0s, then 1m, 5m, 30m, 2h, 6h — six attempts, then `DEAD`.
  Wall-clock, not business hours: a subscriber being down has nothing to do with
  office hours, unlike every timer in Unit 19.
- **Retry on:** connection failure, timeout, `5xx`, `429`. **Do not retry** `4xx`
  other than `429` — a subscriber answering `400` will answer `400` forever, and
  retrying it six times just delays finding out. Straight to `DEAD` with the
  response recorded.
- **Timeout** per attempt (10s), so one hanging subscriber cannot occupy the sender.
- **`DEAD` raises an in-app notification to the GM** (Unit 06). A dead-letter
  nobody is told about is a lost event with extra steps.
- **Replay** is a staff action creating a new `PENDING` row with the same payload —
  the stored payload, not a regenerated one. Regenerating would send today's view of
  a case that has since moved on, which is a different event wearing the same name.

## Handoff C

On `case.delivered`:

- Post to GHL's automation URL to **start the review + referral track** and **stamp
  the closed value**.
- Stamp `google_review_requested` / `google_review_requested_at` on the case when
  the delivery succeeds — that is the pair Unit 17's review tile counts, and it must
  mean "GHL was successfully told", not "we tried".
- **The payout entry is Unit 16's** and already exists by this point.

**This one webhook is the whole of A21.** Everything the business spec schedules
after delivery — the review request at 7 days and the retention sequence at
30/90/180/365 — is **GHL's, end to end** (decision, Production Process v2.0). EvalOS
fires `case.delivered` once and schedules none of it: there is no `RetentionSweep`
(deleted from Unit 19), no retention queue screen, and the four
`retention_*_sent_at` columns stay unwritten. The only post-delivery fact EvalOS
keeps is the stamp above, which records that the handoff succeeded.

The client-facing side of this delivery — actually sending the signed letter to the
client — is touchpoint **T8** in `context/process-automation.md`, and its channel is
undecided.

### Suppression sync

Delivered and active contacts go to GHL's global suppression list so no cold or bulk
campaign ever emails a current client.

- An **outbound API call**, not a webhook: `integration/GhlClient`, needing GHL API
  credentials (part of the gating question).
- Triggered on `case.created` (active) and `case.delivered`, through the same outbox
  — a suppression call that fails must be retried, and retry, backoff and the delivery
  log are exactly what the outbox already provides.
- **But it is not "a delivery row like any other", and the table has to say which kind
  it is.** Every other row means *POST this JSON to `target_url` and sign it with the
  subscriber's secret*; this one means *call a named GHL API with our API credentials
  and no HMAC at all*. Sharing one row shape without distinguishing them leaves the
  sender inferring intent from the subscriber's name, and leaves `secret` null on a row
  where null is supposed to mean **fail closed** — so the one operation that must not be
  skipped is the one the fail-closed rule would skip. Give `webhook_delivery` an
  explicit **`operation`** discriminator (`WEBHOOK` | `GHL_SUPPRESSION`), let the sender
  branch on it, and scope the "null secret fails closed" rule to `WEBHOOK`. The retry,
  backoff and log stay shared, which was the point of putting it here.
- Sends the **GHL contact id** from the contact snapshot, nothing more. EvalOS does
  not push contact data to GHL; GHL owns contacts (invariant 7).

## What a payload may contain

A **whitelist**, the third in this phase after Units 13 and 14, and for the same
reason: an outbound payload is the easiest place for a field added later to leak.

**Included:** `event`, `occurred_at`, `brand` (id + slug), `case` (id, code, stage,
service type, delivery date), `contact` (GHL contact id and email — GHL's own data
coming home), `attribution` (`campaign_attribution`, which came from GHL), and the
`delivery_id`.

**Excluded, and asserted:** `payment_detail` (invariant 11, and it is not reachable
from `CaseEvent` anyway), `pm_strategy_notes`, `notes` and every free-text internal
field, the expert's identity, the document checklist, the audit trail. **`deal_value`
is included only for the closed-value stamp on `case.delivered`** and on no other
event — GHL needs the number to close its own opportunity, and that is the single
stated reason it crosses the boundary.

`CaseEvents.CaseEvent` already carries "brand/case/contact/attribution/stage and
nothing else", which is most of this guarantee by construction. The payload builder
is still an explicit whitelist, because the next field added to `CaseEvent` should
not become outbound by default.

## Backend

Nothing here is a public route. Staff routes for operating it:

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/webhooks/deliveries | GM | the delivery log: filter by status, event type, brand, case. **Never returns a subscriber secret** |
| GET | /api/webhooks/deliveries/{id} | GM | one delivery with its payload, attempts and last response |
| POST | /api/webhooks/deliveries/{id}/replay | GM | creates a replay row |
| GET | /api/webhooks/subscribers | GM | registry, secrets omitted |
| PATCH | /api/webhooks/subscribers/{id} | GM | activate / deactivate / edit subscribed events |

**GM-only, all of it.** The delivery log is inherently cross-brand infrastructure
and the payloads inside it span brands — the same reasoning that made
`GET /api/brands` GM-only in Unit 07, gated twice. Gate at the route **and** in the
service.

### The sender, and the split with Unit 19

A `@Scheduled` poller claiming due `PENDING`/`FAILED` rows —
`status IN (...) AND next_attempt_at <= now()`, claimed with
`FOR UPDATE SKIP LOCKED` so two pollers cannot claim the same row at the same moment.
Single instance today (single region, one app), but `SKIP LOCKED` is one clause and
double-sending a `case.delivered` starts a review campaign twice.

**`SKIP LOCKED` buys mutual exclusion, not exactly-once, and the difference matters
here.** It stops two workers claiming one row concurrently. It does nothing about the
gap between *the HTTP call succeeding* and *the row being marked `SENT`*: a worker that
crashes, is killed mid-deploy, or times out on a request the subscriber actually
processed leaves a row that looks unsent and will be sent again. Committing the status
first only moves the hole — then a crash loses the delivery instead of repeating it,
and for `case.delivered` losing it is worse. **So this is at-least-once, deliberately,
and it must be written down as at-least-once** rather than described as if the lock
closed the hole. What makes that safe is the subscriber's side of the contract:
`X-EvalOS-Delivery` is stable across every attempt of one delivery — it is the row id —
so a subscriber that deduplicates on it gets effectively-once. That header stops being
a courtesy and becomes required, and the published contract says so. Retry counts and
`X-EvalOS-Attempt` exist so a subscriber can tell a genuine repeat from a duplicate.

**Unit 19 consolidates this into the `job` package on the `scheduled_job` table.**
This unit ships the smallest sender that can be verified end to end, because a
dispatcher with no sender cannot be verified at all. Recorded so Unit 19 knows to
move it rather than write a second one.

## Frontend deliverables

1. **Delivery log** (`features/webhooks`, GM only): a table of deliveries with
   status, event, subscriber, attempts, last response code and time. Failed and dead
   rows first — this screen is opened when something is wrong.
2. **Delivery detail**: the payload as sent, every attempt with its response, and
   **Replay**, which states plainly that it re-sends the stored payload and creates
   a new row.
3. **Subscriber registry**: name, URL, subscribed events, active. **No secret is
   ever rendered**, not even masked — a masked secret is a secret in the response
   body.
4. **A dead-letter count in the top bar for the GM**, since a dead delivery is
   silent by nature.
5. Nav: one **Integrations** entry under the existing Admin group, GM only, added to
   the one `NAV_ITEMS` table and gated with `mayReach` — the client's table and the
   backend's `@PreAuthorize` naming the same role, which is what
   `navigation.test.ts` exists to hold.

## Acceptance criteria

- [ ] Delivering a case writes **one** `PENDING` delivery row per matching
      subscriber, **in the delivery transaction** — a rolled-back delivery leaves no
      outbox row, and an outbox write failure rolls the delivery back.
- [ ] The request carries a correct `X-EvalOS-Signature` over
      `timestamp.body`, verifiable with the subscriber's secret; a test computes it
      independently rather than calling the signer.
- [ ] A subscriber returning `500` is retried on the declared backoff and is `DEAD`
      after six attempts, with every attempt in the log and a GM notification.
- [ ] A subscriber returning `400` goes `DEAD` **immediately**, not after six
      attempts.
- [ ] A hanging subscriber hits the per-attempt timeout and does not block other
      deliveries.
- [ ] Replay creates a **new** row referencing the original, sends the **stored**
      payload, and leaves the original row's history intact.
- [ ] The payload contains none of `payment_detail`, `pm_strategy_notes`, internal
      notes, the expert's identity or the checklist — asserted by grepping the
      serialized payload for seeded tokens, the same method Units 13 and 14 use.
- [ ] `deal_value` appears on `case.delivered` and on **no other event type**.
- [ ] No response from any route contains a subscriber secret; asserted across all
      five routes.
- [ ] **A subscriber with no secret is never delivered to** — its rows are not
      claimed and nothing unsigned leaves the app; and no applied migration outside
      `local/` contains a secret literal, checked by a test that greps the migration
      directory.
- [ ] Every non-GM role gets 403 from all five routes, checked in the service too.
- [ ] A successful Handoff C stamps `google_review_requested`; a failed one does
      **not** — so Unit 17's tile counts what actually reached GHL.
- [ ] `SKIP LOCKED` claiming: two concurrent sender passes deliver each row once,
      proved DB-gated in real SQL.
- [ ] `npm run build` green; `./mvnw verify` green. **A live signed delivery to a
      real GHL automation URL is required to close the unit** — the standard Units
      05 and 15 were held to.

## Invariants honored

Brand isolation — a brand-scoped subscriber receives only its own brand's events,
and the GM is the only reader of the cross-brand log (1); EvalOS emits an event and
GHL runs the campaign; EvalOS still runs no marketing (2); GM-only, gated twice (3);
**`payment_detail` in no payload** (4, 11); the delivered value crossing to GHL is
the closed-value stamp and nothing more (5); **all sending happens outside the
request, in a scheduled sender** (6); GHL owns contacts — the suppression sync sends
GHL's own ids back and pushes no contact data (7); new migrations (9); **every
outbound delivery is HMAC-signed, retried with backoff, dead-lettered on exhaustion
and recorded in the delivery log** (11); the dispatcher carries no business logic —
it delivers published events (12); transitions still write their audit rows in
`service`, untouched by this unit (13); no email — GHL sends every client message
(14).

## Files touched

**Created.** Backend: `webhook/outbound/OutboxListener.java` (the
`@EventListener` writing rows in-transaction),
`webhook/outbound/WebhookSender.java` (claim, sign, post, backoff),
`webhook/outbound/PayloadBuilder.java` (the whitelist),
`webhook/outbound/OutboundSigner.java`, `service/DeliveryLogService.java`,
`integration/GhlClient.java` (the suppression call),
`web/WebhookAdminController.java` (+ DTOs), `domain/WebhookSubscriber.java`,
`domain/WebhookDelivery.java`, `domain/DeliveryStatus.java`, their repositories.
Migrations `V<next>__webhook_subscriber.sql`, `V<next+1>__webhook_delivery.sql`,
and a `local` seed subscriber pointing at a loopback receiver so the unit is
verifiable without GHL. Frontend: `frontend/src/features/webhooks/*`
(`DeliveryLog`, `DeliveryDetail`, `SubscriberList`, `webhookApi`).

**Modified.** `notification/NotificationListeners.java` (the dead-letter alert).
`service/CaseLifecycleService.java` — the `google_review_requested` stamp on
successful Handoff C, and nothing else. `application.yml` (GHL API credentials,
env-backed, no non-local default). `frontend/src/features/shell/navigation.ts`,
`frontend/src/features/shell/TopBar.tsx`.

**Not touched.** The whole inbound `webhook` gateway — `WebhookGateway`,
`WebhookVerifier`, brand resolution (protected). `event/CaseEvents.java` — this unit
subscribes to the existing catalogue and declares no new type. `service/CaseTransitions.java`,
`service/ScopePredicate.java`, every applied migration.
