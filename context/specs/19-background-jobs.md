# Unit 19 — Background jobs consolidation

**Phase:** 3 — Close the loop
**Depends on:** 05 (intake starts the doc clock), 10 (the reminder/escalation hooks
and `docs.escalation.day3`), 15 (the sign-SLA logic and the reassign operation), 18
(the outbox sender this unit absorbs)
**Unlocks:** nothing after it depends on this; it is the unit that makes every
"Unit 19 owns the clock" note in Units 10, 15 and 18 come true
**Gating open questions:** none of its own. It inherits whatever is unresolved in
the units it schedules — a doc-collection chase that GHL cannot deliver is still
undeliverable on a timer.

> **Written ahead of its code.** Specs 11–20 were written in one pass. Three earlier
> units (10, 15, 18) each declared an event or an operation for this unit to fire;
> re-read those specs and the code against this one at the start of the unit, because
> the list below is only correct if none of them changed.

## Goal

Four units have deliberately shipped a hook with no clock behind it. This unit is
the clock. It consolidates every timed behaviour in EvalOS into one `job` package on
the Pacific business calendar, so there is one place that answers "what does EvalOS
do on its own, and when".

**Verifiable result:** a case whose documents have not arrived is chased at 24 and
48 business hours and escalated at 3 business days; a stage over its SLA escalates;
an unsigned case warns at 20 and prompts reassignment at 24 business hours; the
outbox drains; and **every one of those fires once**, survives a missed run, and is
visible in a run log.

## In scope

- The `job` package, `@EnableScheduling`, and the `scheduled_job` **run ledger**.
- The advisory lock that stops two instances double-firing a sweep.
- Doc-collection reminders (24h / 48h) and the day-3 escalation.
- Stage-SLA escalation across every stage.
- Expert sign 20h / 24h alerts and the reassignment prompt.
- **Absorbing Unit 18's `WebhookSender`** into this package.

## Out of scope

- Any new business rule. Every rule here already exists in the unit that owns it;
  this unit decides *when* it runs, never *what* it does. A threshold changing is a
  change to the owning unit's spec, not this one.
- **Auto-reassigning an expert without a human.** Unit 15 settled this: the timer
  *prompts*, matching `project-overview.md`'s "the case auto-prompts reassignment".
  Nothing here moves a case off an expert on its own.
- Sending anything to a client or an expert directly. Jobs publish domain events;
  Unit 18 delivers them; GHL does the sending (invariant 14). Experts are reached by a
  portal link, not by a message this package emits.
- A message broker, a distributed scheduler, or a second application instance.
  `architecture.md`'s NFRs rule all three out. **But not the single-statement guard
  against two instances ticking at once** — see "Scheduler, locking and the queue"
  below. Nothing *enforces* one instance, and every rolling deploy briefly runs two.
- Retention and the post-delivery review sequence. **GHL owns those end to end**
  (decision, Production Process v2.0) — EvalOS emits `case.delivered` and schedules
  none of it. `RetentionSweep` was specced here and is deliberately gone.

## The design: sweepers over a queue of future rows

`architecture.md` names "a persisted `scheduled_job` table", which can be read two
ways. **Taken as: sweepers on a schedule, with `scheduled_job` as the run ledger —
not one row per future timer.** The reasoning, because this is the unit's main
design decision:

- **A missed run must self-heal.** A row-per-timer queue that the app was down for
  either fires a burst of stale rows on restart or drops them. A sweeper asks "which
  cases are overdue *right now*" and is correct on the first run after any outage,
  however long.
- **Idempotency already exists in the data**, and does not need job rows to carry
  it. Unit 10 established the pattern — "last chased is derived from the append-only
  trail, not a column on the case" — and the same holds for every timer here:
  - doc chases: the `CHASED` audit rows Unit 10 already writes;
  - sign alerts: the notification rows Unit 06 writes;
  - outbox: the delivery row's own `status` and `attempts`.
  A job row recording "the 24h chase fired" would be a **second** record of a fact
  the system already holds, and two records of one fact can disagree — the reasoning
  Unit 05a used to refuse a `paid_by` column.
- **What a table genuinely earns:** a run ledger (which sweep ran, when, how long,
  how many rows it touched, what failed) and the row lock the outbox sender needs.
  That is observability and concurrency, which the data model does *not* already
  provide.

So `scheduled_job` records **runs, not intentions**. Recorded in the tracker as a
reading of the architecture line rather than a departure from it — the table exists,
backed by the `job` package, exactly as written; what it holds is the part the line
did not specify.

`scheduled_job`:

| column | note |
| --- | --- |
| `id`, `created_at` | no `brand_id` — a sweep spans brands by nature, like the audit table's nullable brand |
| `job_type`, `started_at`, `finished_at` | which sweep, and how long |
| `status` | `RUNNING` · `OK` · `FAILED` |
| `items_seen`, `items_acted` | the two numbers that tell you a sweep is working |
| `error` | the failure, if any |

Index `(job_type, started_at DESC)` — the "when did this last run" query.

**A sweep that throws does not stop the schedule.** Each sweep catches, records
`FAILED` with the error, and the next tick tries again; and each *item* inside a
sweep is handled in its own transaction, so one bad case cannot stop the other
ninety-nine. This is the difference between a job that degrades and a job that
silently stops, and the second is the failure mode nobody notices for a month.

## Scheduler, locking and the queue

The architecture named a `scheduled_job` table but never said what *runs* the
sweeps. Settled here.

**Scheduler: Spring's own `@Scheduled` + `@EnableScheduling`.** Already on the
classpath, so it adds nothing. Intervals bind from `application.yml` under
`evalos.jobs.*`. Quartz is **not** taken: there are no dynamic schedules, no
per-row timers (that is the whole design decision above), and no clustering
requirement that one Postgres builtin does not cover. A scheduler with its own
eleven tables is a large answer to "call six methods on a timer".

**Concurrency: one Postgres advisory lock per sweep — session-scoped, not
transaction-scoped.**

```sql
SELECT pg_try_advisory_lock(hashtext(:jobType))     -- claim, outside any item txn
...run the sweep, one transaction per item...
SELECT pg_advisory_unlock(hashtext(:jobType))       -- in a finally, always
```

If the claim returns false the sweep logs "already running elsewhere" and returns.

**It must not be `pg_try_advisory_xact_lock`.** That variant releases when its
transaction commits, and this sweep deliberately runs **one transaction per item** so
a single bad case cannot abort the run — so an xact-scoped lock would be dropped after
the *first* item and leave the whole remainder unprotected, which is precisely the
rolling-deploy double-chase it exists to prevent. Hold it on the connection for the
sweep's lifetime instead, take it outside the per-item transactions, and release it in
a `finally` so a thrown sweep does not keep it.

The cost of session scope is that a hard JVM kill holds the lock until the connection
is reaped rather than releasing instantly. That is the right trade: the failure mode is
"a sweep is skipped for one tick", against "a client is messaged twice". A stuck lock
is also visible — the run ledger stops gaining rows for that job type, which is what
the stale-job warning already watches for.

**Why this is not speculative, given the single-instance NFR.** The NFR is an
assumption, not an enforcement: nothing in the config, the container or the
platform prevents a second instance, and **every rolling deploy runs two for a few
seconds** while the old process drains and the new one starts. If both tick in that
window, the sweeps do not conflict loudly — they send the client a second chase and
the staff a second alert, which nobody traces back to the deploy. One statement
buys immunity to that, and to the day someone scales out without reading this file.
ShedLock is the named alternative and is refused: another dependency and another
table for what `pg_try_advisory_lock` already does.

**Queue: the outbox that Unit 18 already specced. No broker.** `webhook_delivery`
*is* the queue — rows written in the same transaction as the domain change (so a
committed case change can never lose its outbound event), drained by `OutboxSender`
claiming with `FOR UPDATE SKIP LOCKED`, retried on wall-clock backoff via
`next_attempt_at`, dead-lettered after the attempt ceiling, every attempt logged.

The reason that is sufficient, recorded so nobody proposes Kafka later: the only
cross-process work EvalOS has is **"deliver one webhook to one subscriber, and keep
trying"**. That is retry-with-backoff over a durable row. There is no fan-out, no
ordering requirement across cases, no consumer group, and no second consumer. A
broker would add an operational dependency that can be down while adding nothing
the table does not already do — and it would move the outbox *out* of the
transaction that guarantees it exists.

**Client uploads do not go through any of this.** Unit 21 is synchronous on purpose:
a human is waiting on the response and needs to know the file landed. The outbox is
for machine-to-machine delivery, not for a request someone is watching.

## The calendar, and the one place it does not apply

Every SLA and reminder threshold is in **business hours** on
`service/BusinessCalendar` — 09:00–17:00 America/Los_Angeles, weekends and the
eleven US federal holidays with their observance shifts. It already exists and is
already tested; **no new calendar code**. A case that arrives Friday at 16:00 is not
overdue on Saturday morning.

**Two exceptions, both deliberate:**

- **The outbox sender's backoff is wall-clock** (Unit 18). A subscriber being down
  has nothing to do with office hours.
- **The client chases are wall-clock** — 24h and 48h as the *client* experiences
  them, the same distinction Unit 10 already drew when it made checklist aging
  wall-clock ("which is what a client experiences") while the SLA stayed business
  hours.

  **This is not cosmetic — business hours here breaks the sequence.** The
  `DOC_COLLECTION` budget is 24 *business* hours (three eight-hour days, which is why
  the constant reads 24). If the chases were business hours too, the first chase and
  the day-3 escalation would both land at 24 business hours — the escalation firing
  at the same moment as the client's first reminder — and the "48h" chase would
  arrive at 48 business hours, i.e. **six** business days, three days *after* the
  escalation. The intended order is chase → chase → escalate, and only wall-clock
  chases against a business-hours escalation produce it.

There used to be a second — retention timers in wall-clock days, because "90 days
since we delivered" is the client's calendar, not EvalOS's working week. That
distinction still holds, but it is now **GHL's to apply**: retention left this unit
with `RetentionSweep`.

## The jobs

Every `Finds` below is **additionally filtered on `paid`**, and the sweeps read it from
the row rather than assuming it. Case Creation v2.0 means there are no unpaid cases to
find, so the predicate matches everything today and costs nothing — which is the point:
the rule is stated once, in the query, instead of resting on a fact about intake that
could change again. It has already changed twice. See the closing note on payment.

| Job | Tick | Finds | Does | Idempotency from |
| --- | --- | --- | --- | --- |
| `DocChaseSweep` | 30 min | `DOC_COLLECTION`, checklist incomplete, ≥24h and ≥48h **wall-clock** since `stage_entered_at` | publishes `checklist.reminder` (Unit 10's event → GHL chases the client) | **per threshold**, from the count of `CHASED` audit rows: the 24h chase fires when there are none, the 48h chase when there is one, and **nothing after that** |
| `DocEscalationSweep` | 1 h | `DOC_COLLECTION`, incomplete once the stage's SLA budget is spent — **ask `SlaCalculator`, do not hardcode "3 business days"** | publishes `docs.escalation.day3` → in-app to the **PM + GM**, and flags the board | the Unit 06 notification rows |
| `StageSlaSweep` | 30 min | any active case whose `SlaCalculator` status is `AT_RISK` or `OVERDUE` and whose stored `sla_status` is stale | refreshes `sla_status`, notifies the stage's owner on a transition into breach | the stored `sla_status` — it only notifies on a change |
| `ExpertSignSweep` | 30 min | `EXPERT_SIGNING`, unsigned, ≥20h and ≥24h business | 20h warning to CM + PM; at 24h `expert.sign_overdue` + the reassignment prompt with Unit 12's shortlist. **The prompt asks a human to fire Unit 15's `EXPERT_TIMED_OUT`; this sweep never fires it** | notification rows per threshold |
| `OutboxSender` | 1 min | `PENDING`/`FAILED` deliveries with `next_attempt_at <= now()` | signs and posts (Unit 18's logic, moved not rewritten) | the delivery row's `status`/`attempts`, claimed `FOR UPDATE SKIP LOCKED` |

**Five sweeps, not six — `RetentionSweep` is deleted from this spec.** GHL owns the
post-delivery review request and the 30/90/180/365 sequence end to end, off the
`case.delivered` webhook. The consequence for the schema: the four
`retention_*_sent_at` columns are **permanently unwritten**, no longer "reserved for
this unit", and this unit must not give them accessors. Left in place because an
applied migration is never edited; recorded in `mem:backend/persistence` so nobody
adopts them later thinking they are free.

**`google_review_requested` / `_at` are different and stay written** — by Unit 18,
when Handoff C succeeds. They record that *EvalOS asked GHL to start the review
track*, which is EvalOS's own fact and the pair Unit 17's "review requests sent"
tile counts. What EvalOS does not own is the 7-day delay or the sending.

Notes that matter:

- **No sweep hardcodes a threshold that `SlaCalculator` already owns.** The day-3
  document escalation and the doc-collection SLA budget are *the same number*, so
  the escalation asks the calculator whether the budget is spent rather than writing
  "3 business days" a second time. The 20h/24h sign thresholds are the exception
  worth naming: 24h is the budget, and **20h is a warning fraction of it**, which is
  the sign sweep's own to define — but derive it from the budget, do not restate it.
- **`StageSlaSweep` notifies on transition into breach, not on every tick.** A job
  that alerts every 30 minutes about the same breached case trains everyone to
  ignore the bell — and Unit 06's notification centre is the only channel staff have.
- **A case in an exception state is skipped by every SLA sweep.** `SlaCalculator`
  already returns null there, and `CaseTransitions` refuses everything but the way
  out — so a case on hold awaiting the client is not late, and chasing it about
  documents while it is held for expert evidence would be two contradictory messages.
  `DocChaseSweep` **does** still cover a `DOC_COLLECTION` case on hold, matching
  Unit 10's deliberate choice to keep held cases on the checklist board ("on hold
  awaiting client is exactly the case whose documents have not arrived").
- **Every sweep reads unscoped and acts per brand.** There is no authenticated caller,
  so `ScopePredicate` does not apply — the same situation the inbound gateway is in.
  The finders are therefore deliberately brand-wide and get a DB-gated brand-isolation
  test. They do **not** inherit the old "do not call it with ids that came from a
  request" javadoc convention: that convention was retired on 2026-08-06 after a review
  pointed out a comment is not a scope, and the two finders carrying it were given brand
  predicates. A sweep has no caller to scope against, which is what makes brand-wide
  legitimate here — say that in the javadoc rather than asking callers to be careful. Notifications and
  events they raise carry the **case's own** brand, via
  `AuditService.recordSystemEvent` — the actor is the system, and the brand comes from
  the row, never from a default.
- **Every case is paid, so no sweep needs to reason about payment.** Case Creation
  v2.0 (spec `05b`) creates the case from a won opportunity, already paid, so the
  unpaid window Unit 05a left open is closed. This bullet used to say "unpaid cases
  are chased for documents and nothing else"; there are no unpaid cases to chase.
  A sweep still must not assume it — it reads `paid` from the row like everything
  else — but no sweep branches on it.

## Backend

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/jobs/runs | GM | the run ledger: last run, duration, items seen/acted, failures |
| POST | /api/jobs/{jobType}/run | GM | run one sweep now — for verification and for recovering after an outage |

GM-only, gated at the route and in the service, for the reason Unit 18's log is:
this is cross-brand infrastructure.

**The manual trigger takes the same advisory lock as the tick**, and returns "already
running" if it cannot get it. This is the part idempotency does not cover: pressing
**Run now** while the scheduled sweep is mid-run is not a repeat, it is a *concurrent*
run, and the idempotency above is read-then-write — count the `CHASED` rows, decide,
publish. Two runs interleaving inside that gap both read zero chases and both send the
24h chase, so the client gets two emails and the property that was supposed to prevent
it never engaged. The lock is per `job_type` and already exists for exactly this; the
button must go through it rather than around it, and "already running elsewhere" is a
perfectly good answer to give the GM.

**Pressing it twice in sequence is safe**, and that is the property worth having a
button for: every sweep's idempotency comes from the data rather than from
having-not-run-yet, so after an outage somebody can catch up deliberately instead of
waiting for the next tick.

Scheduling is `@Scheduled` with the intervals in `application.yml`, and
**`evalos.jobs.enabled` defaults to false in the test profile** — a suite that
starts sweeps races its own fixtures.

## Frontend deliverables

1. **Job runs panel** (`features/admin`, GM only): each job's last run, status,
   duration and items acted, with failures first and a **Run now** action. Same
   Integrations area as Unit 18's delivery log — one place for "what does the system
   do by itself".
2. **A stale-job warning**: a job whose last successful run is older than several
   times its interval is flagged. A scheduler that has quietly stopped looks
   identical to one with nothing to do, and this is the only screen that can tell
   the difference.
3. No other new screen. Every one of these jobs surfaces its work through
   notifications and boards that already exist — which is the point of having built
   the hooks first.

## Acceptance criteria

- [ ] Each sweep fires **once** per threshold per case: running it twice in a row
      produces one chase, one escalation, one warning.
- [ ] **The chases are wall-clock and the escalation is business hours, and the order
      holds.** For a case entering `DOC_COLLECTION` at 16:00 on the Friday before a
      Monday holiday: chased ~24h and ~48h later on the calendar, and escalated only
      once 24 *business* hours have elapsed — which the holiday pushes to the
      Thursday. Assert the **ordering** (chase, chase, escalate) rather than three
      hardcoded timestamps, since that is the property that matters and the one an
      earlier draft of this spec got wrong.
- [ ] The escalation threshold is **read from `SlaCalculator`**, not written as a
      literal, so changing the doc-collection budget moves the escalation with it.
- [ ] **Two instances do not double-fire.** With the advisory lock held, a second
      concurrent invocation of the same sweep returns without acting — asserted
      directly, because this is the failure a rolling deploy produces and it is
      otherwise silent.
- [ ] A case in an exception state is skipped by the SLA and sign sweeps, and a
      `DOC_COLLECTION` case on hold **is still chased**.
- [ ] `StageSlaSweep` notifies on the transition into breach and **not** on the next
      tick for the same case.
- [ ] The 24h sign timer raises the prompt and **does not move the case** — the
      expert is still assigned and `REASSIGN_EXPERT` still requires
      `EXPERT_DECLINED_REMATCHING`. Asserted, because this is where an "auto-reassign"
      reading would do real damage. The way forward is a human firing Unit 15's
      `EXPERT_TIMED_OUT`; **no sweep may fire it**, asserted by there being no call
      site for it in this package.
- [ ] A `DOC_COLLECTION` case still incomplete a week later has been chased
      **twice, not seven times** — the 24h and 48h reminders and no more; the day-3
      escalation is what carries it after that.
- [ ] One case throwing inside a sweep does not prevent the other cases in the same
      sweep from being processed, and the run is recorded `FAILED` with the error.
- [ ] Every event and notification a sweep raises carries the **case's** brand, and a
      sweep spanning two brands never mixes them. Proved DB-gated in real SQL.
- [ ] The outbox sender behaves exactly as Unit 18 specified after the move — its
      tests move with it and still pass unchanged.
- [ ] Jobs are **disabled in the test profile** and the suite does not depend on
      them being off by luck.
- [ ] `npm run build` green; `./mvnw verify` green.

## Invariants honored

Brand isolation — sweeps are unscoped by necessity, carry the same javadoc convention
and DB-gated isolation test as the other deliberately-unscoped finders, and every
side effect names the case's own brand (1); no marketing or nurture is sent by
EvalOS — chase events go to GHL, which runs the sequences, and retention never
enters EvalOS at all (2); no
sweep mutates a case in a way a role could not (3) — every one of them goes through
the owning unit's existing transition or publishes an event; `payment_detail` is
untouched (4); **all timed work lives in `job`, which is exactly what invariant 6
asks for** — this unit is the invariant being satisfied rather than merely respected;
system-actor audit rows carry the resolved brand (13); no email — every message is a
domain event for GHL, or a portal link an expert opens (14).

## Files touched

**Created.** Backend: `job/DocChaseSweep.java`, `job/DocEscalationSweep.java`,
`job/StageSlaSweep.java`, `job/ExpertSignSweep.java`, `job/JobRunLedger.java`,
`job/JobScheduleConfig.java` (`@EnableScheduling` + the advisory-lock helper),
`domain/ScheduledJob.java`, `domain/JobStatus.java`,
`repository/ScheduledJobRepository.java`, `web/JobAdminController.java`. Migration
`V<next>__scheduled_job.sql`. Frontend: `frontend/src/features/admin/JobRuns.tsx` +
`jobApi`.

No `RetentionSweep` — see the sweep table.

**Moved.** `webhook/outbound/WebhookSender.java` → `job/OutboxSender.java`, with its
tests. Moved, not rewritten — Unit 18's retry, backoff, dead-letter and
`SKIP LOCKED` behaviour is already specified and tested, and re-deriving it here
would be a second implementation of the same rules.

**Modified.** `application.yml` + profiles (intervals, `evalos.jobs.enabled`).
`frontend/src/features/shell/navigation.ts`.

**Deliberately not modified.** `domain/Case.java`'s four `retention_*_sent_at`
columns get **no accessors** — an earlier draft of this spec added them. Retention is
GHL's, so those columns and the review-request stamps stay unwritten and unreadable;
giving them accessors would advertise a capability EvalOS no longer has.

**Not touched.** Every business rule: `CaseTransitions`, `SlaCalculator`,
`BusinessCalendar`, `ChecklistService`, `ExpertSignService`, `RefundService`. This
unit calls them and changes none of them. `service/ScopePredicate.java`, the inbound
gateway, every applied migration.
