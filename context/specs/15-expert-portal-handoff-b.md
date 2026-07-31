# Unit 15 — Expert portal + Handoff B + sign-off

**Phase:** 2 — Connect the seams — the heaviest external dependency in it
**Depends on:** 05 (the inbound gateway the callbacks arrive through), 12 (the
offer record and the rematch shortlist), 14 (the portal token model and chain)
**Unlocks:** 16 (a signed, delivered case is what creates a payout), 19 (the
sign-timer this unit defines)
**Gating open questions:** two, both external, both blocking.
1. **The Dropbox Sign account, API key, signature-request template, and callback
   signing secret** do not exist. Nothing beyond the `WebhookSource.DROPBOX_SIGN`
   enum value has ever run.
2. **How the callback resolves its brand** — see "The brand-resolution problem"
   below. This one may touch a protected step, so it needs answering before any
   code, not during.

## Goal

Close Handoff B. The client has approved the draft and the case is sitting in
`EXPERT_SIGNING` waiting for a human with credentials to put their name on it.
This unit gives that human a screen and a signature.

**Verifiable result:** on client approval a Dropbox Sign signature request goes out
to the assigned expert; the expert opens a link, sees that one case's draft, goal
and evidence, and can accept, ask for more evidence, or decline with a reason;
Dropbox Sign's signed / declined / viewed callbacks arrive through the Unit 05
gateway and drive the case's sign status without polling; and a case still unsigned
at 24 business hours is flagged for reassignment.

## In scope

- The `EXPERT`-audience portal chain and view, on Unit 14's token model.
- The three expert responses: accept, request evidence, decline.
- **Dropbox Sign**: issuing the signature request, and its inbound callbacks
  through the Unit 05 gateway.
- The 20h / 24h sign-SLA **alerting and the reassign operation**.

## Out of scope

- **Scheduling** the 20h/24h timers. The build plan names the timer here; the
  invariant puts the scheduler in `job` (invariant 6), and Unit 19 owns that
  package. Split the same way Unit 10 split the chase: **this unit owns the
  logic, the events and the reassign operation; Unit 19 owns the clock.** Nothing
  fires on a schedule until Unit 19.
- Any new stage transition for "send to expert" — see below, the case is already
  in `EXPERT_SIGNING`.
- Storing the signed letter. It lives in Dropbox Sign (invariant 14). EvalOS keeps
  the request id and the status.
- An expert account, password, or a list of the expert's cases — see the
  one-token-one-case note.
- The payout the signature eventually earns — Unit 16.

## There is no "send to expert" transition, and none is needed

The build plan describes "send-to-expert (client-approved → `EXPERT_SIGNING`)".
That transition **already exists and already runs**:
`CaseTransitions` declares `CLIENT_APPROVE_DRAFT: DRAFT_GENERATION → EXPERT_SIGNING`,
and Unit 14's portal fires it. By the time this unit is involved the case is in
`EXPERT_SIGNING` already.

What is missing is not a stage change but an **outbound act**: issuing the
signature request. So this unit subscribes to `draft.client_approved` and sends
the Dropbox Sign request. No new column remembers "sent" — the presence of a
signature-request id is that fact.

Do **not** add a second transition into `EXPERT_SIGNING`. Two paths into one stage
is how a case gets there without an expert assigned.

## New actions on the transition table

Four, all declared **from `EXPERT_SIGNING` only**. Three are things only an expert
with a case in front of them can do; the fourth is a staff act about an expert who
did nothing, and the reason it has to exist is set out under the sign SLA below.
Each carries its own event and audit action, per `CaseTransitions.Action`'s
contract that a transition cannot be logged as one thing and published as another.

| Action | Lands on | Effect | New event |
| --- | --- | --- | --- |
| `EXPERT_ACCEPTED` | `EXPERT_SIGNING` (in place) | stamps the offer `ACCEPTED`; the expert has taken the case | `expert.accepted` |
| `EXPERT_REQUEST_EVIDENCE` | `EXPERT_SIGNING` (in place) | sets `ON_HOLD_AWAITING_CLIENT`, **adds a required checklist item** carrying the expert's description | `expert.evidence_requested` |
| `EXPERT_DECLINED` | *already exists* | sets `EXPERT_DECLINED_REMATCHING`; `REASSIGN_EXPERT` is the declared way out | `expert.declined` (exists) |
| `EXPERT_TIMED_OUT` | `EXPERT_SIGNING` (in place) | sets `EXPERT_DECLINED_REMATCHING` and stamps the offer `TIMED_OUT`. **GM · Brand Manager · PM**, never the expert and never a job | `expert.timed_out` |

`EXPERT_TIMED_OUT` mirrors `EXPERT_DECLINED`'s exact shape — stage-preserving,
setting the same exception state, so `REASSIGN_EXPERT` (which
`CaseTransitions.REQUIRES_EXCEPTION` pins to `EXPERT_DECLINED_REMATCHING`) is the
way out of both without being widened. It is a **separate action rather than a
reuse of `EXPERT_DECLINED`** for the reason this whole unit exists: "the expert
refused" and "the expert never answered" are different facts about a person whose
acceptance rate the match engine scores, and recording silence as a refusal would
put a decline the expert never made into the trail and into their rate.

`EXPERT_DECLINED` and `REASSIGN_EXPERT` are **already built** (Unit 04) and already
wired into `REQUIRES_EXCEPTION`. This unit only lets the expert be the one who
fires the first, instead of a staff member recording it second-hand.

**"Opens a client task" is a checklist item**, not a new entity. Unit 10 already
owns required-vs-supplied documents, the Coordinator's board already shows what a
case is waiting for, and the chase already reaches the client through GHL. A
separate task table would be a second answer to "what does this case need from the
client", and two answers disagree. So request-evidence calls
`ChecklistService`'s add-item path and publishes the event GHL turns into a
message.

The case going `ON_HOLD_AWAITING_CLIENT` is deliberate and has a consequence worth
stating: while held, the case accepts **nothing but `RESUME_FROM_HOLD`**
(`CaseTransitions`: "a case sitting in an exception state accepts nothing but its
way out"). The expert therefore cannot sign until the Coordinator resumes it. That
is correct — the expert asked for evidence precisely because they were not willing
to sign yet — but it means **the sign-SLA clock must not run while the case is
held**, which `SlaCalculator` already gets right (it returns null in an exception
state).

## Dropbox Sign

`integration/DropboxSignClient` — the only place the API is called from.

- **Sending.** On `draft.client_approved`: create a signature request for the
  assigned expert against the configured template, carrying the `draft_link`
  document and the case id in Dropbox Sign's `metadata`. Store the returned
  `signature_request_id` on the case.
- **Config**, all env-backed with **no non-local default**, the rule
  `EVALOS_FIELD_KEY` set: API key, template id, and the callback signing secret.
- **Dependency:** the Dropbox Sign Java SDK, added here and nowhere earlier.
- **The expert's email comes from `Expert.email`** (added in Unit 11). An expert
  row with no email cannot be sent a signature request — refuse with a message
  naming that, rather than sending to nobody.
- **Failure does not corrupt the case.** The send is retried by Unit 19's job on
  failure; until it succeeds the case sits in `EXPERT_SIGNING` with no request id,
  which is a visible, recoverable state that the board can show. Nothing is
  half-transitioned, because the transition already happened in Unit 14.

### Callbacks — through the Unit 05 gateway, not beside it

`signature_request.signed` / `..._declined` / `..._viewed` arrive at the existing
inbound gateway: verify → resolve brand → dedupe → archive → route → ack
(invariant 10). A new `webhook/DropboxSignHandler` and its router entries; the
gateway itself is untouched and stays a protected file.

- `signed` → `EXPERT_SIGNED` (the Unit 04 transition), sign status `SIGNED`, and the
  offer stamped `ACCEPTED` **only if it is still `OFFERED`**. An expert who pressed
  Accept in the portal and then signed produces two writes of the same outcome on
  the ordinary happy path, and Unit 12's rule is that an outcome leaves `OFFERED`
  exactly once — so the second write is a no-op, not a second stamp and not an
  error. First write wins, whichever act arrives first.
- `declined` → `EXPERT_DECLINED` with the reason from the callback.
- `viewed` → stamps a viewed timestamp only. **No transition** — looking at a
  document is not an act on the case, and giving it one would put a meaningless
  row on the timeline every time the expert refreshed.
- Idempotency is the gateway's, on the source event id scoped by brand. Dropbox
  Sign retries; a replayed `signed` must not sign twice.
- **The staff-recorded stand-ins stay.** `POST /api/cases/{id}/expert/signed` and
  `.../expert/declined` remain, gated as they are. A signing integration that is
  down cannot be allowed to stop the business, and those endpoints are the manual
  path. They write the same transition, so the trail does not care which fired it.

### The brand-resolution problem

The gateway resolves `brand_id` **from the per-brand endpoint token** — step 2, and
a protected step (`ai-workflow-rules.md`). GHL satisfies this naturally: each brand
is its own sub-account, so each gets its own endpoint.

**Dropbox Sign may not.** If the business has one Dropbox Sign account, it has one
callback URL, and the endpoint token cannot distinguish brands.

- **Preferred: one Dropbox Sign account (or API app) per brand**, each configured
  with that brand's own EvalOS callback endpoint. The gateway then works unchanged
  and the protected step is untouched. This is also the safer arrangement
  operationally — one brand's signing credentials cannot reach another's requests.
- **If that is impossible**, the brand must come from the `metadata` case id on
  the callback, which **is a change to the protected brand-resolution step** and
  requires explicit instruction before it is written. Do not implement it as a
  quiet fallback inside the handler; a second brand-resolution path that nobody
  approved is exactly what protecting that step is for.

**Confirm which, before starting.** This is question 2 in the header.

## What the expert may see

A whitelist, like Unit 13's and Unit 14's.

**Included:** the draft (`draft_link`), the **stated goal** — initial petition vs.
RFE, which is `service_type` plus `visa_category` — the evidence the checklist
records as supplied, the client's name (the expert is signing a letter about a
named person; it cannot be withheld), the case reference, and the signing
deadline. **Excluded:** `deal_value`, `invoice_ref`, `campaign_attribution`,
`pm_strategy_notes` (the PM's internal framing is not the expert's), the audit
timeline, staff assignment fields, other experts, and every other case.

**One token, one case** — the same rule as Unit 14, and it is a good fit here
rather than a compromise: the Dropbox Sign link *is* per signature request, so an
expert with three cases legitimately has three links. There is no expert case list
to build, and therefore no expert account, password, or session to secure.

## Backend

Expert portal routes, on the Unit 14 portal chain, `X-Portal-Token` header:

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/portal/expert/case | portal token (EXPERT) | the whitelisted view; stamps `last_seen_at` |
| POST | /api/portal/expert/accept | portal token (EXPERT) | → `EXPERT_ACCEPTED` |
| POST | /api/portal/expert/request-evidence | portal token (EXPERT) | → `EXPERT_REQUEST_EVIDENCE`; body carries what is missing |
| POST | /api/portal/expert/decline | portal token (EXPERT) | → `EXPERT_DECLINED`; reason required |

Staff-side, on the normal chain:

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | /api/cases/{id}/expert-portal-link | GM · Brand Manager · PM · CM | mint the expert link (the CM-shared path, for when Dropbox Sign's own link is not the route in) |
| POST | /api/cases/{id}/expert/resend-signature-request | GM · Brand Manager · PM · CM | re-issue the Dropbox Sign request; audited |
| POST | /api/cases/{id}/expert/timed-out | GM · Brand Manager · PM | → `EXPERT_TIMED_OUT`. The human answer to the 24h prompt: stamps the offer `TIMED_OUT` and opens the rematch. **Not on the CM's list** — taking a case off an expert is the same weight of call as staffing it |

Audit rows from the portal use `AuditService.recordPortalEvent` with
`actor_type = EXPERT` (Unit 14's column), so "the expert declined" and "a Case
Manager recorded that the expert declined" are two distinguishable facts in the
trail. That distinction is the reason this unit exists.

## The sign SLA

`SlaCalculator` already budgets expert sign at **24 business hours**. This unit adds
the **20-hour warning** and the **reassign operation**; Unit 19 fires both on the
Pacific business calendar.

- `expert.sign_overdue_warning` at 20h → in-app notification (Unit 06) to the CM
  and PM.
- `expert.sign_overdue` at 24h → notification, and the case is **flagged for
  reassignment** — surfaced to the PM with the Unit 12 shortlist for the next
  expert.
- **Auto-reassign proposes; it does not reassign.** Silently pulling a case off an
  expert who was about to sign, and emailing a second expert the same letter, is
  worse than a late case. The build plan's "auto-reassign" is read as
  **auto-prompt**, which is also the wording `project-overview.md` uses ("the case
  auto-prompts reassignment"). Where the two documents differ, the narrower reading
  wins and is recorded here.
- **And the prompt needs somewhere to lead, which is why `EXPERT_TIMED_OUT`
  exists.** `REASSIGN_EXPERT` requires `EXPERT_DECLINED_REMATCHING`
  (`CaseTransitions.REQUIRES_EXCEPTION`), and an expert who has not answered has
  not declined — so before this action was declared there was **no legal path from
  a 24h timeout to a rematch at all**, and `TIMED_OUT` was an outcome nothing could
  ever write. An earlier draft of this file said `TIMED_OUT` is "stamped when the
  case is actually reassigned after a timeout" without saying how the case got
  there; the honest answers were a staff member firing `EXPERT_DECLINED` on an
  expert who never declined, or widening `REASSIGN_EXPERT` to fire from anywhere.
  The first corrupts the trail this unit exists to keep straight; the second
  removes the guard that stops a case being pulled off an expert mid-signature.
  So: the timer prompts, and a **human fires `EXPERT_TIMED_OUT`**, which is the act
  that both stamps the offer `TIMED_OUT` and opens the rematch. `TIMED_OUT` is
  therefore written **here**, by a person, not by Unit 19's clock — the clock only
  raises the notification that asks for it.

## Frontend deliverables

1. **Expert portal** (`features/expert-portal`), a separate entry point outside
   `AppShell`, on Unit 14's pattern. Route `/portal/expert#<token>`.
2. **Single-column assigned-case view**: the goal at the top (what this letter has
   to achieve), then the draft, then the evidence list, then the actions. One
   column because the expert has one decision to make and reads top to bottom.
3. **Three actions**: Accept · Request evidence (with a required description of
   what is missing) · Decline (with a required reason). Decline confirms — it sends
   the case back to rematching.
4. **Sign** hands off to Dropbox Sign's own hosted flow; EvalOS does not reimplement
   a signing surface.
5. **Status after acting**, including the deadline and, when held for evidence,
   that EvalOS is waiting on the client — not on the expert.
6. Staff side: the case detail expert card gains the signature-request status, the
   viewed timestamp, the sign deadline, and **Resend request**; the production
   board's `EXPERT_SIGNING` column shows the 20h/24h warning state. Past 24h the
   card also offers **Mark timed out & rematch** (`EXPERT_TIMED_OUT`), which is the
   answer to the overdue prompt — confirmed, and worded so it is not mistaken for
   recording a decline the expert never made.

## Acceptance criteria

- [ ] Client approval issues exactly one Dropbox Sign request to the assigned
      expert's email, and the returned request id is stored on the case.
- [ ] An `EXPERT` token reads its own case only; the whitelist excludes
      `deal_value`, `invoice_ref`, `campaign_attribution` and
      `pm_strategy_notes`, asserted by grepping the serialized response.
- [ ] A `CLIENT` token is **rejected** on `/api/portal/expert/**` and an `EXPERT`
      token on `/api/portal/client/**`. One table, two audiences, no crossing.
- [ ] Accept stamps the offer `ACCEPTED` and writes an audit row with
      `actor_type = EXPERT`.
- [ ] Request-evidence puts the case in `ON_HOLD_AWAITING_CLIENT`, **creates a
      required checklist item** visible on the Coordinator's board, publishes the
      GHL event, and — asserted explicitly — the expert **cannot then sign** until
      the case is resumed, and the sign SLA reports no clock running while held.
- [ ] Decline sets `EXPERT_DECLINED_REMATCHING`, stamps the offer `DECLINED` with
      the reason, and the case appears in the board's rematching lane with a
      shortlist for the next expert.
- [ ] **A timed-out case can actually be rematched, and the trail says why.**
      `EXPERT_TIMED_OUT` sets `EXPERT_DECLINED_REMATCHING`, stamps the offer
      `TIMED_OUT` (not `DECLINED`), and `REASSIGN_EXPERT` then succeeds — asserted
      end to end, because before this action existed the path did not close.
      An expert who timed out shows no decline in their audit trail and no
      `DECLINED` row against their acceptance rate.
- [ ] The expert cannot fire `EXPERT_TIMED_OUT` (no portal route reaches it) and a
      CM gets 403 from the staff route.
- [ ] Accepting in the portal and then signing leaves the offer `ACCEPTED` with
      **one** outcome write — the callback's stamp is a no-op, not a second one.
- [ ] A replayed `signature_request.signed` callback signs once — the second is
      answered `duplicate` and produces no second audit row or event.
- [ ] A `..._viewed` callback stamps the timestamp and produces **no** transition
      and **no** timeline row.
- [ ] An unsigned callback (bad signature) is refused identically to the GHL case
      and is **not archived** — the Unit 05 behaviour, re-proved for this source.
- [ ] The staff-recorded `expert/signed` and `expert/declined` endpoints still work
      and write the same transition as the callback.
- [ ] The app fails to start outside `local` with no Dropbox Sign secret
      configured.
- [ ] `npm run build` green; `./mvnw verify` green. **A live signature round-trip
      against a real Dropbox Sign account is required to close the unit** and is
      recorded in the tracker — the same standard Unit 05 was held to, where a
      mocked handler was not accepted as evidence for a live path.

## Invariants honored

Brand isolation — the expert token names one case in one brand (1); the expert sees
only their assignment (3); `payment_detail` is not on this surface, and the expert's
own payment detail is not shown to them either, there being no read path for it at
all (4); the outbound send and the timers run in `job`, not in a controller (6);
**every callback is verified, brand-resolved, deduplicated and archived before any
side effect** (10); the webhook handler carries no business logic — it routes to
`CaseLifecycleService` (12); every response writes an append-only audit row naming
the expert as actor (13); the signed letter lives in Dropbox Sign and EvalOS sends
no email — Dropbox Sign issues the request (14).

## Files touched

**Created.** Backend: `integration/DropboxSignClient.java`,
`config/DropboxSignConfig.java`, `webhook/DropboxSignHandler.java`,
`service/ExpertSignService.java` (issue / resend / the SLA computation),
`web/ExpertPortalController.java`, `web/ExpertSignController.java` (+ DTOs).
Migration `V<next>__case_signature_request.sql` — `signature_request_id`,
`expert_viewed_at`, `sign_deadline_at` on `evalos_case`. Frontend:
`frontend/src/features/expert-portal/*` (`ExpertCaseView`, `expertPortalApi`).

**Modified.** `service/CaseTransitions.java` — the three new actions
(`EXPERT_ACCEPTED`, `EXPERT_REQUEST_EVIDENCE`, `EXPERT_TIMED_OUT`).
`event/CaseEvents.java` — `expert.accepted`, `expert.evidence_requested`,
`expert.timed_out`, `expert.sign_overdue_warning`, `expert.sign_overdue`.
`service/CaseLifecycleService.java` — the three new transition methods, and the
first-write-wins guard on the offer outcome.
`webhook/WebhookRouter.java` — the three Dropbox Sign event types.
`service/ChecklistService.java` — the add-item path reused by request-evidence.
`notification/NotificationListeners.java` — the new events' recipients.
`frontend/src/features/case/ExpertCard.tsx`, `frontend/src/App.tsx`.
`pom.xml`, `application.yml`.

**Not touched.** `webhook/WebhookGateway.java`, `webhook/WebhookVerifier.java` and
the brand-resolution step (protected — and if question 2 forces a change there, it
needs explicit instruction first). `service/ScopePredicate.java`. Every applied
migration.
