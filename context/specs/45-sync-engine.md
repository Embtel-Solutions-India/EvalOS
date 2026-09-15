# Unit 45 — The sync engine

**Status: slice A BUILT (2026-09-16). Everything else is blocked on Unit 44c and 44d, and this
spec says so rather than pretending otherwise.**

`00c` §4 designs the engine; `00d` §6.1–6.4 amends it before it is built. This spec sequences
those amendments against what actually exists.

---

## 1. The blocker, stated first

**Unit 45 reconciles rows. Four of its five pieces have no rows to reconcile yet.**

`00c` §3 lists 45 as depending on 44, and 44 is one slice in of four. What the mirror holds today
is `pipeline` and `pipeline_stage` (slice 44a) — and those are the one entity GHL owns outright
(`00d` §6.2), refreshed hourly by a straight overwrite. There is nothing to reconcile there and no
conflict to resolve.

| Piece of Unit 45 | Needs | State |
|---|---|---|
| **Error classification at the door** | nothing | **BUILT — §2** |
| `opportunity.update` / `contact.*` webhooks | `opportunity`, `contact` rows to write into | blocked on **44c**, **44d** |
| The delta sweep | same | blocked on **44c**, **44d** |
| The nightly paged diff audit | same, plus something that can actually diverge | blocked on **44c**, **44d** |
| The outbox | a local row to read at send time (`00d` §6.3) | blocked on **44c**, **44d** |
| `sync_drift` + the sync-status surface | a producer of drift | blocked on the above |

**A drift audit over pipelines alone would be theatre.** The 44a sweep overwrites them hourly
through the same code path an audit would compare against, so it would report zero by construction.
Building it now would be a screen that proves nothing and a table nobody fills.

**Recommendation: build 44d next**, then the rest of this unit. 44d carries `opportunity` and the
correlation custom field, which is both the largest consumer of the sync engine and the thing
`00d` §6.1 calls *"the single biggest sequencing error in `00c`"*.

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

## 3. What the rest of Unit 45 must do when 44c/44d land

Carried here so the amendments are not re-derived from `00d`.

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

### 3.4 `sync_drift` is a table (`00d` §6.3, first bullet)

`00c` §4b and §4c both say "the drift report" as if it were a document. §4c's guarantee that
*every divergence is detected* is only checkable if yesterday's divergences are still queryable.

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
