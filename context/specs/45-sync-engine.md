# Unit 45 — The sync engine

**Status: slices A and B BUILT (2026-09-16). Unit 44 is complete, so nothing is blocked any more —
what remains is C (the outbox), D (webhooks + delta sweep) and E (per-field ownership).**

`00c` §4 designs the engine; `00d` §6.1–6.4 amends it before it is built. This spec sequences
those amendments against what actually exists.

---

## 1. The slices, and why this order

| Slice | Ships | Writes anything? | Status |
|---|---|---|---|
| **45a** | error classification at the door | no | **BUILT — §2** |
| **45b** | `sync_drift` + the nightly paged diff audit + the GM's read | only drift rows | **BUILT — §2B** |
| **45c** | the outbox | **yes — every EvalOS→GHL write** | specced, §3.1 |
| **45d** | `opportunity.update` / `contact.*` webhooks + the delta sweep | **yes — the mirror** | specced |
| **45e** | per-field ownership + the sync-status surface's resolution half | **yes — both sides** | specced, §3.2 |

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

## 3. What the rest of Unit 45 must do

Carried here so the amendments are not re-derived from `00d`. Nothing below is blocked any more —
Unit 44 is complete.

### 3.1 The outbox (`00d` §6.3, second bullet)

- **Partial unique index**, `where sent_at is null and dead_at is null`. A plain unique constraint
  is the obvious wrong reading and makes the second edit of the same opportunity an hour later
  collide with the first's sent row.
- **Store `entity_id`, never a payload snapshot**, and read the current row at send time —
  otherwise "collapse onto the pending one even if a field changed" silently sends older values.
- **Keep `intent` coarse** (`UPSERT` / `CLOSE` / `DELETE`), or the collapse never happens.
- Retry policy reads §2's classification: retriable classes only, `RATE_LIMITED` pauses the queue,
  `UNAUTHORIZED` halts it and alerts.

### 3.2 Per-field ownership, not "EvalOS wins" (`00d` §6.2)

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
