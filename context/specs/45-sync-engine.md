# Unit 45 — The sync engine

**Status: slices A, B and C BUILT (2026-09-16). What remains is D (webhooks + delta sweep) and
E (per-field ownership).**

`00c` §4 designs the engine; `00d` §6.1–6.4 amends it before it is built. This spec sequences
those amendments against what actually exists.

---

## 1. The slices, and why this order

| Slice | Ships | Writes anything? | Status |
|---|---|---|---|
| **45a** | error classification at the door | no | **BUILT — §2** |
| **45b** | `sync_drift` + the nightly paged diff audit + the GM's read | only drift rows | **BUILT — §2B** |
| **45c** | the outbox | **yes — the two portal writes that were being lost** | **BUILT — §2C** |
| **45d** | `opportunity.update` / `contact.*` webhooks + the delta sweep | **yes — the mirror** | **BUILT — §2D** |
| **45e** | per-field ownership + the sync-status surface's resolution half | **yes — both sides** | **BUILT — §2E** |

**The detector went before the writers, and that is the whole ordering argument.** 45c, 45d and 45e
are all writers; a writer you cannot audit is a writer you have to take on trust. `00c` §4c promises
that *every divergence is detected*, and until 45b nobody could check whether the mirror was right
at all — which is also the question Unit 48's independence claim rests on.

**45b was buildable only after Unit 44 completed.** An earlier version of this section recorded that
four of five pieces had no rows to reconcile, and that a drift audit over pipelines alone would be
theatre: the 44a sweep overwrites them hourly through the same code path an audit would compare
against, so it would have reported zero by construction. Unit 44d's `opportunity` is what gave the
audit something that can genuinely diverge.

---

## 2. Slice 45a — error classification at the door (BUILT)

`00d` §6.3, fourth bullet, and it is a **hard prerequisite for the outbox** rather than a tidy-up:

> Classify errors at the door. `GhlHttp` flattens status into a message string, so 4xx, 5xx and
> timeout are indistinguishable. Only 5xx/timeout/408/429 are retriable; `429` must pause the
> **whole** outbox (the budget is per location); `401`/`403` must **halt** it and alert, because
> retrying a scope failure ten times across every pending row spends the entire budget on a
> credential problem.

### 2.1 `GhlFailure`

Seven classes, each with a reason it is or is not retriable:

| Class | From | Retriable | Stops everything |
|---|---|---|---|
| `NOT_CONFIGURED` | no token / no location | no | no — nothing left the JVM |
| `UNAUTHORIZED` | 401, 403 | no | **yes** — halt and alert |
| `RATE_LIMITED` | 429 | yes | **yes** — pause, the budget is per location |
| `NO_ANSWER` | 408, timeout, transport | yes | no |
| `REFUSED` | any other 4xx | no | no |
| `UPSTREAM_ERROR` | 5xx | yes | no |
| `EMPTY_RESPONSE` | 2xx with no body | no | no |

Two of these are the ones a retry loop gets wrong if it guesses:

- **`REFUSED` is not retriable.** The temptation is to retry a 4xx "in case it was transient". It
  was not; a malformed body sent again is still malformed, and the budget is spent proving it.
- **`EMPTY_RESPONSE` is not retriable even though nothing usable came back.** GHL considered the
  write successful. Repeating it writes twice — which is exactly `00d` §6.1's failure.

And `NO_ANSWER` is the one that matters most for a **create**: a timeout means EvalOS does not know
whether GHL acted. That is the case the correlation custom field in **44d** exists for, and
classifying it is what lets the outbox know to search before creating rather than guess.

### 2.2 The exception carries it

`GhlUnavailableException` now has `failure()` and `status()`. **Every one of them is still a 502 to
a staff caller** — the fault is upstream either way, and no HTTP status EvalOS returns changed.
What changed is that code can tell them apart.

The single-argument constructors default to `REFUSED` — **not retriable** — because a fault EvalOS
raises about GHL without a call failing (an unconfigured name, a missing field) does not become
true on a second attempt. A default that guessed "retriable" would be the wrong direction to fail
in.

### 2.3 Two string matches deleted

`GhlCalendarClient.missingScopeHint` and `GhlInvoiceClient.missingScopeHint` decorated a 401/403 by
**searching the message for `"401"`** — the exact defect §6.3 describes, and one that would have
matched a `403` mentioned anywhere in a response body just as happily. Both now read
`failure() == UNAUTHORIZED`.

### 2.4 A 429 pauses the whole location, today

`GhlHttp` already owns the shared pacer for the 100-per-10-seconds budget, so the back-off belongs
there rather than in a queue that does not exist yet: **every caller benefits now**, and the outbox
inherits it for free.

- `Retry-After` is honoured when GHL sends one, **capped at two minutes**. It is input from
  somebody else's server, and an uncapped value would hand a remote system the ability to stall
  every EvalOS thread that touches GHL.
- Without a usable header, one full budget window (10s).
- A non-numeric `Retry-After` (the HTTP-date form) falls back rather than throwing inside an error
  path. A second date format is a second thing to get wrong for no gain.

---

## 2B. Slice 45b — drift detection (BUILT)

### 2B.1 `sync_drift` is a table, because a promise needs evidence

`00d` §6.3, first bullet: *"`00c` §4b and §4c both say 'the drift report' as if it were a document.
§4c's guarantee that every divergence is detected is only checkable if yesterday's divergences are
still queryable."*

- **One open row per thing that is wrong**, with `first_detected_at` and `last_seen_at` — not a row
  per audit run. A drift that persists for a week is one fact, and a row per night buries the new
  findings under the old ones, which is exactly how a report stops being read.
- **The unique index is partial**, `where resolved_at is null` — the same lesson §6.3 records for the
  outbox's dedupe key. A plain constraint would make tonight's re-detection of something that was
  fixed and came back collide with last week's resolved row.
- **Resolved rows stay.** *"This drifted and then stopped"* is the history worth keeping; deleting it
  would make the table unable to say whether a fix worked.
- **Both values travel.** A row naming a field without saying what each side held is one somebody has
  to investigate by hand — the report-as-a-document failure this replaces.

### 2B.2 It detects and records. It never repairs.

Conflict resolution is per-field ownership (§3.2) and is **45e's**, deliberately. A detector that
also mutates cannot be trusted to tell the truth, because its own writes become tomorrow's findings.
There is no route to clear a drift row either: a human clearing one would clear the *symptom* and
leave the two systems disagreeing.

### 2B.3 A paged full-list diff, never a per-row `GET`

`00d` §6.3 asks for this to be said out loud, because the per-row version *"is the one somebody
writes first, because it is easier to reason about"*. Budget: ~115 pages at 110ms ≈ **30s at ~9
req/s**, an order of magnitude inside GHL's 100-per-10-seconds. Per-row is 11.4k requests — about
**21 minutes of continuous budget**, starving every desk while it runs. A test asserts the audit
calls `allIn` and never the windowed reads.

Nightly, on Unit 19's machinery, so it joins the admin panel's staleness warning for free: **an audit
that quietly stopped running reads as "no drift"**, which is worse than no audit at all.

### 2B.4 Two things it must never call drift

Both are pinned by tests, because a detector that cries wolf is one nobody reads:

- **A portal-born row with no `ghl_id`.** `00c` §2a says that is a *legal* state — the whole reason a
  row has two names. Recording every one as MISSING_IN_GHL would fill the report with rows nobody
  should act on, on the busiest day the portal has.
- **`1000` against `1000.00`.** Compared by value, not by string. A report wrong every single night
  is one that gets ignored.

### 2B.5 Scope: opportunities, and the two reasoned absences

**Pipelines and stages are not audited** — the 44a sweep overwrites them hourly through the same code
path, so a finding is impossible by construction. **Contacts are not audited yet** for a different
reason that will change: EvalOS has no GHL contact *read* client at all, only the upsert, so there is
nothing to compare against. They join with 45d's contact webhooks.

**Four fields compared** — stage, status, amount, name. Each is one a desk acts on; a field nobody
reads cannot produce a finding anybody can act on. `assignedTo` is deliberately absent because `00d`
§6.2 gives it to GHL outright, so EvalOS disagreeing about it is not drift, it is EvalOS being
behind.

### 2B.6 One index detail worth knowing

`uq_sync_drift_open` includes `entity_id` while the finders key on `(brand, type, ghl_id, field)`.
They cannot disagree: `uq_opportunity_per_brand_ghl_id` means at most one local row per GHL id, so
`entity_id` is functionally determined by `ghl_id`. Noted rather than left for somebody to spot and
worry about.

---

## 2C. Slice 45c — the outbox (BUILT)

`00c` §4d, and the chain of reasoning is short: no mismatch requires that a failed push is retried; a
retry is only safe if it cannot double-apply; and **invariant 2 said *"writes do not retry"* precisely
because EvalOS had no key scheme.** Unit 44d's correlation key is that scheme, which is why this
could not have been built first.

### 2C.1 What it queues, and what it does not

**The desks still write to GHL synchronously.** A salesperson needs the created id and GHL's
`isNew` back; taking that away is **Unit 46's** job. What this drains is the two writes that were
previously *swallowed and lost*, each of which carried a `ponytail:` note naming this slice:

- the client portal's opportunity **create** when GHL is unreachable, and
- its **"request submitted"** marker.

Both are now `enqueue`d instead of evaporating into a log line.

### 2C.2 `entity_id`, never a payload

`00d` §6.3: *"store `entity_id`, **not a payload snapshot**, and read the current row at send time —
otherwise 'collapse onto the pending one even if a field changed' silently sends the older values."*
A queue holding what was true when it was queued delivers stale writes on every retry.

That is also why the **submit marker needs no intent of its own**: an `UPSERT` re-sends the row's
current fields, which by then include it.

### 2C.3 The dedupe key is partial, and `intent` is coarse

- **Partial** — `where sent_at is null and dead_at is null`. §6.3 calls the plain constraint "the
  obvious wrong reading": it makes the second edit of the same opportunity an hour later collide
  with the first's **sent** row.
- **Coarse `intent`** (`UPSERT`/`CLOSE`/`DELETE`) — *"or the collapse never happens"*. An intent per
  field would make three edits in a minute three distinct rows and three sends.
- `enqueue` runs `REQUIRES_NEW`, so a push survives the caller's transaction rolling back. A push
  lost because the request that asked for it failed afterwards is exactly what this table prevents.

### 2C.4 The retry-after-timeout, which is the whole of `00d` §6.1

A create that did not answer leaves EvalOS unable to tell *"GHL never got it"* from *"GHL got it and
the reply was lost"*. At-least-once delivery over a non-idempotent create is how one opportunity
becomes two.

So before creating, the drain asks GHL for **the contact's** opportunities and looks for its own
correlation key. Finding it means the create already landed: the row is **linked, not made twice**.

**This is the corrected form of §6.1's mechanism** (§5.1 of the Unit 44 spec records the finding):
GHL offers *no* custom-field filter on either search endpoint, so "search that field" is not a query
anybody can write. `contactId` *is* a documented filter, and one contact has a handful of deals.

With no correlation field configured there is nothing to look for, and the create goes out
unguarded — the duplicate exposure that exists today rather than a new one. Refusing to retry would
lose the client's request outright, which is worse.

### 2C.5 The stop conditions are 45a's classification, by name

| Failure | What the drain does | Why |
|---|---|---|
| `RATE_LIMITED` (429) | **halts the whole drain**, row stays pending | the budget is per *location* — backing off one row while the next fires is not a back-off |
| `UNAUTHORIZED` (401/403) | **halts and logs an error**, row stays pending | nothing in EvalOS can fix a missing grant, and retrying it across every row spends the whole budget on one credential |
| `REFUSED`, `EMPTY_RESPONSE` | dead-lettered on the first attempt | a malformed body sent again is still malformed; a write GHL accepted must not be repeated |
| `NO_ANSWER`, `UPSTREAM_ERROR` | retried, capped at 5 attempts | genuinely transient — but a row retried forever spends budget forever and never reaches anybody |
| an EvalOS exception | dead-lettered | a `NullPointerException` does not become true on the fourth attempt, and looping hides the bug behind a queue that never drains |

**Dead rows stay.** A write that never reached GHL is exactly what somebody needs to find afterwards.

### 2C.6 Where a human sees it

`GET /api/sync/drift` gained an `outbox` block: pending, dead, the age of the oldest pending push,
and the last twenty dead ones with their failure class and reason. **`dead` is the number that needs
a person** — a backlog is a drain catching up, a dead row is a write that will never arrive without
somebody looking. The drain is a sweep on Unit 19's machinery for the same reason the audit is: **a
queue nobody is draining looks exactly like a queue with nothing in it.**

Two minutes, and it is the one interval here chosen for a person rather than a budget: what is
queued is a client's request reaching Sales after an outage.

---

## 2D. Slice 45d — the webhooks and the delta sweep (BUILT)

### 2D.1 The event is a trigger, not a payload

GHL's Custom Webhook action posts the **contact record**, flat — no stage, no status, no value.
`GhlOpportunityHandler`'s javadoc records that discovery: the nested `opportunity`/`contact`
envelope this codebase was first built for does not exist. Anything else in the body is
`customData`, which is **hand-typed by whoever edited the workflow last**.

So a mirror fed from the payload would be only as correct as that person. `GhlMirrorHandler`
takes one fact from the event — the contact id, the one field GHL always sends — and reads the
field values back from GHL, where they are authoritative:

- **`contact.created` / `contact.updated`** → `ContactSnapshotService.findOrCreate`. This is the
  one case where the payload *is* the entity: name, email, phone and company are exactly what GHL
  posts and exactly what `contact_snapshot` holds. **No GHL read at all.** Create and update are
  one handler because `findOrCreate` is one operation — splitting them would turn a dropped
  `contact.created` into a refused `contact.updated`.
- **`opportunity.*`** → `GhlPipelineClient.forContact(contactId)` → `absorbForContact`. The
  `contactId` filter is documented and already proven by 45c's retry-after-timeout read; a contact
  holds a handful of deals, so one request answers the question completely. **A single-opportunity
  GET is not possible here anyway** — the payload carries no opportunity id.

**Every spelling routes.** `opportunity.create`, `.created`, `.update`, `.updated`,
`.stage_changed`, `.status_changed` all reach the same handler, because `event_type` is typed by
hand into a GHL workflow and the difference between two tenses must not be the difference between
a mirrored deal and a silently unmirrored one. **`opportunity.won` is not among them and must not
join them** — that is Handoff A, it creates a case (invariant 8), and one event doing both would
make two very different failures look like one.

### 2D.2 `absorbForContact` has no absence pass, unlike `absorb`

`absorb` was handed a pipeline's whole list, so a row missing from it has genuinely gone and is
stamped `missing_since`. `absorbForContact` is handed **one person's deals**: a row missing from
that answer is missing from a question that never asked about it, and marking it would stamp every
deal the contact does not have. A real disappearance stays the nightly audit's to notice.

Each row lands on **the pipeline GHL names**, not one the caller chose — which closes the
staleness `isOnPipeline`'s javadoc predicted: a deal dragged onto another rep's board changes hands
here at the moment it moves.

### 2D.3 "Delta" means a stale pipeline, never a changed row

**GHL offers no updated-since filter.** `GET /opportunities/search` filters `date`/`endDate` on
`createdAt` — verified, and documented on `GhlPipelineClient.opportunitiesIn`, which is the same
absence that forces "won this month" to be bucketed locally. There is no read that returns only
what changed, and a sweep claiming to be one would be a full list wearing a smaller name.

`MIRROR_DELTA` (15m) therefore asks `refreshIfStale` of every **live mirrored pipeline**, which is
a no-op for any pipeline a desk has read inside `evalos.ghl.delta-ttl` (10m — deliberately shorter
than the interval, so clock jitter between two passes cannot double the promised freshness). **Its
cost is the pipelines nobody is looking at**, which is exactly what it exists for: those are the
rows the nightly audit would otherwise report as drift when the truth is that nobody looked, and
the rows `isOnPipeline` would otherwise answer an authorisation question from.

It creates no case. A dropped `opportunity.won` is not drift (§3.5), and nothing here will ever
open one.

### 2D.4 What it does not do

**It does not resolve a conflict.** A webhook write and a local edit racing is per-field
ownership, which is 45e. Today GHL's answer is absorbed wholesale, which is the behaviour every
other mirror write already has — 45d widens *when* the mirror is written, not *who wins*.

---

## 2E. Slice 45e — per-field ownership (BUILT)

### 2E.1 Three owners, and the assignee is why

`FieldOwnership` classifies every mirrored field as **GHL's**, **shared** or **EvalOS's**.

| Owner | Fields | Rule |
|---|---|---|
| **GHL** | `ghlAssignedTo`, `pipelineId`, GHL's timestamps, `source`, `ghlContactId` — and anything unclassified | taken every time. A difference is EvalOS being behind, not a conflict |
| **SHARED** | `ghlStageId`, `status`, `amount`, `name` | EvalOS wins a genuine conflict **and it is reported** |
| **EvalOS** | `opportunityNote` | never synced in either direction |

**The assignee is the field this slice exists for.** `00c` §4b's blanket "EvalOS wins" reverts GHL
automations — the one thing `00b` explicitly kept GHL for — and a round-robin reassigning a deal is
not a conflict to be undone. **An unclassified field defaults to GHL**, deliberately: everything in
the mirror is GHL's until somebody adds a field to the shared set, and that addition is an edit a
reviewer can see.

### 2E.2 "EvalOS wins" means one narrow thing

`localUpdatedAt` means **EvalOS holds an edit GHL has not confirmed**, and nothing else. The shared
fields are kept only while that is true:

- **No local edit → GHL wins**, whatever the timestamps say. The mirror's default is to follow GHL.
- **A null `ghl_updated_at` with a local edit → conflict**, and EvalOS keeps its value (`00d` §6.2).
  The field is GHL-supplied and nullable; reading absence as "GHL is newer" would discard the edit.
- **A null `ghl_updated_at` with nothing to defend → still GHL's.** Without this half the null rule
  degrades into "EvalOS always wins" the moment a location stops sending the field — the blanket
  rule arriving by the back door.

**Two places clear the flag, and without them the mirror freezes.** A GHL win clears it (GHL's
answer superseded the edit), and `linkGhl` clears it (GHL acknowledged the create, so the values
EvalOS opened the row with are the values GHL was just handed). A portal-born row would otherwise
out-rank GHL on all four shared fields for the rest of its life.

**Row-level timestamps are what this replaces.** Comparing one `updated_at` against another makes
every concurrent edit a conflict, including two edits to different fields that could both have
stood.

### 2E.3 The surface's resolution half is an answer, not a button

`SyncStatusController` still has **no route to resolve a row**, and 45e is what makes that
defensible rather than merely stubborn. A human clearing a drift row clears the symptom while the
two systems still disagree. What a GM is owed is *"will this fix itself"*, so every row now carries:

- **`owner`** — who wins the field.
- **`resolution`** — `GHL_WINS` (the next sync closes it; no action), `EVALOS_WINS` (the mirror is
  keeping a local edit on purpose — expected, and worth chasing only if it persists), or
  `NEEDS_A_HUMAN`.
- **`needsAHuman`** on the envelope — the count worth alerting on. The open count alone reads the
  same whether every row closes tonight or none of them do.

**Both are derived at read time, never stored.** They are a function of the field's ownership and of
whether the mirror still holds an unconfirmed edit, both of which move after the row was written — a
stored value would be yesterday's answer presented as today's.

**Only `MISSING_IN_GHL` needs a person**, and that is the honest list: re-creating a deal GHL has
lost, or deleting the mirror's copy, is a business decision, and a sweep doing it would turn one
mistaken archive in GHL into lost EvalOS history.

### 2E.4 What it does not do

**It pushes nothing to GHL.** "EvalOS wins" is about what the *mirror* keeps, not about correcting
GHL — the desks still write to GHL synchronously, and routing them through the outbox is Unit 46.
Until then the ownership rules are largely latent: the only rows carrying an unconfirmed local edit
are portal-born ones between the create and GHL's acknowledgement.

**No migration.** `local_updated_at` and `ghl_updated_at` were both added at 44d for this.

---

## 3. What the rest of Unit 45 must do

Carried here so the amendments are not re-derived from `00d`. Nothing below is blocked any more —
Unit 44 is complete.

### 3.1 The outbox (`00d` §6.3, second bullet) — **BUILT at 45c, see §2C**

### 3.2 Per-field ownership, not "EvalOS wins" (`00d` §6.2) — **BUILT at 45e, see §2E**

`00c` §4b's blanket rule **reverts GHL automations — the one thing `00b` explicitly kept GHL for.**
A round-robin reassigning a deal is not a conflict to be undone. Ownership goes per field, in code;
`opportunity.assigned_to` is GHL's, `opportunity.name/.amount/.stage_id/.status` are shared with
EvalOS winning and reporting, `opportunity_note.*` is EvalOS's and never synced.

Two corrections `00c` misses: **a null `ghl_updated_at` is an explicit conflict**, never "EvalOS is
newer" — it is GHL-supplied and nullable, and the policy would otherwise degrade silently. And
row-level timestamps make every concurrent edit a conflict, which per-field ownership resolves.

### 3.3 The nightly audit is a paged full-list diff (`00d` §6.3, third bullet)

**Never a per-row `GET`.** The budget works: ~115 opportunity pages + ~115 contact pages at 110ms
≈ 30s at ~9 req/s, an order of magnitude inside the limit. The per-row version — 11.4k requests,
~21 minutes of continuous budget — starves every desk, and is the one somebody writes first because
it is easier to reason about.

### 3.4 `sync_drift` is a table (`00d` §6.3, first bullet) — **BUILT at 45b, see §2B**

### 3.5 The webhook replay is not the delta sweep (`00d` §6.4)

A dropped `opportunity.won` is **not drift** — no case exists to diverge — and the delta sweep on
`opportunity` will never create one, because Handoff A lives in `CaseIntakeService`, not the
mirror. `00d` §2.3's replay sweep stays separate and Unit 45 must not absorb it.

---

## 4. Acceptance — slice 45a

- [x] A 401 or 403 classifies `UNAUTHORIZED`, is not retriable, and stops everything.
- [x] A 400 classifies `REFUSED` and is **not** retriable.
- [x] A 503 classifies `UPSTREAM_ERROR` and is retriable without stopping everything.
- [x] A 408 is retriable despite being a 4xx; a 404 is not.
- [x] A 429 classifies `RATE_LIMITED` and pushes the **shared** pacer forward for every caller.
- [x] `Retry-After` is honoured, capped, and falls back on the HTTP-date form.
- [x] An unconfigured environment is its own class, not retriable, and does not stop a queue.
- [x] `EMPTY_RESPONSE` is never retriable.
- [x] No HTTP status EvalOS returns changed — every class is still a 502 to a staff caller.
- [x] The two `missingScopeHint` string matches are gone.

---

## 5. Acceptance — slice 45b

- [x] `sync_drift` exists, is brand-scoped, and holds one **open** row per disagreement with
      `first_detected_at` / `last_seen_at` rather than one row per run.
- [x] A drift seen again is updated, with both values refreshed; a drift no longer seen is
      **resolved, not deleted**.
- [x] A deal only GHL has is `MISSING_LOCALLY`; a deal only EvalOS has (with a `ghl_id`) is
      `MISSING_IN_GHL`.
- [x] Each disagreeing field is its own row, carrying **both** sides' values.
- [x] A portal-born row with no `ghl_id` is **not** drift.
- [x] `1000` and `1000.00` are **not** drift.
- [x] The audit reads one paged list per pipeline and never one row at a time.
- [x] A blank selling brand audits nothing and does not call GHL.
- [x] `GET /api/sync/drift` is GM-only and carries the age of the oldest open finding.
- [x] Nothing is repaired, and there is no route to clear a row by hand.

---

## 6. Acceptance — slice 45c

- [x] A push is a durable row naming an **entity id**, never a payload; the sender reads the current
      row at send time.
- [x] The dedupe key is **partial**; a second push for the same entity and intent collapses onto the
      pending one and does not collide with a sent or dead row.
- [x] `enqueue` survives the caller's transaction rolling back.
- [x] A **timed-out create is linked, not made twice** — the drain finds its own correlation key on
      the contact's GHL opportunities first.
- [x] A create that genuinely never landed goes out **carrying the key**.
- [x] No correlation field configured skips the lookup rather than refusing to retry.
- [x] A `429` and a `401`/`403` each halt the **whole** drain and leave the row pending.
- [x] A non-retriable refusal is dead-lettered on the first attempt; a transient one is retried and
      capped; an EvalOS exception is dead-lettered rather than looped on.
- [x] The portal's create and submit-marker failures are queued instead of swallowed.
- [x] `GET /api/sync/drift` carries the queue's pending count, dead count, oldest pending age and
      the recently dead.

---

## 8. Acceptance — slice 45d

- [x] `contact.created` and `contact.updated` reach `contact_snapshot` and **never** `CaseIntakeService`.
- [x] A contact event with a blank `full_name` still lands a name, rebuilt from first + last.
- [x] `opportunity.update` re-reads that contact's deals from GHL and absorbs GHL's own fields.
- [x] All six spellings of an opportunity change route to the same handler.
- [x] `opportunity.won` still reaches Handoff A and nothing else.
- [x] A mirror event naming no contact is a **400**, not an ack — GHL does not retry a 4xx, and
      acking would archive as processed a delivery that changed nothing.
- [x] A deal that moved pipeline in GHL lands on the pipeline GHL names.
- [x] A deal absent from one contact's answer is **not** marked missing.
- [x] A deal on an unmirrored pipeline is skipped and logged, never filed somewhere.
- [x] The delta sweep makes **no GHL call** for a pipeline inside the TTL, re-reads one outside it,
      and leaves a pipeline GHL stopped returning alone.
- [x] `SweepRegistrationTest` covers `MIRROR_DELTA` — it is addressable, ticked and configured.

`InboundWebhookTest` (29), `OpportunityMirrorSyncTest` (7). Full suite: **1036 tests, 0 failures**.

---

## 9. Acceptance — slice 45e

- [x] The four shared fields are `SHARED`; the assignee and the pipeline are `GHL`'s; a note is
      `EVALOS`'s; an unclassified field defaults to `GHL`.
- [x] The assignee is taken from GHL **even when EvalOS holds an unconfirmed edit** — the
      automation-reverting failure §3.2 exists to prevent.
- [x] A row with no local edit follows GHL on every field.
- [x] An EvalOS edit newer than GHL's keeps all four shared fields.
- [x] A null `ghl_updated_at` **with** a local edit is a conflict; **without** one it still follows
      GHL.
- [x] A GHL win clears the unconfirmed edit, and the next answer is taken whatever its timestamp.
- [x] `linkGhl` clears it too, so a portal-born row does not defend itself for ever.
- [x] A shared-field drift row reports `EVALOS_WINS` when the mirror holds an unconfirmed edit and
      `GHL_WINS` when it does not.
- [x] `MISSING_LOCALLY` reports `GHL_WINS`; `MISSING_IN_GHL` is the only `NEEDS_A_HUMAN`.
- [x] There is still no route to resolve a row.

`FieldOwnershipTest` (8), `SyncAuditServiceTest` (13). Full suite: **1049 tests, 0 failures**.

**Unit 45 is complete.** §3.5's webhook replay is `00d` §2.3's sweep and was never this unit's.
