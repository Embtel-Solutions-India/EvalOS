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
an unsigned case warns at 20 and prompts reassignment at 24 business hours; closed
cases fire retention events at 30/90/180/365 days; the outbox drains; and **every
one of those fires once**, survives a missed run, and is visible in a run log.

## In scope

- The `job` package and the `scheduled_job` **run ledger**.
- Doc-collection reminders (24h / 48h) and the day-3 escalation.
- Stage-SLA escalation across every stage.
- Expert sign 20h / 24h alerts and the reassignment prompt.
- Retention / countdown timers at 30 / 90 / 180 / 365 days.
- **Absorbing Unit 18's `WebhookSender`** into this package.

## Out of scope

- Any new business rule. Every rule here already exists in the unit that owns it;
  this unit decides *when* it runs, never *what* it does. A threshold changing is a
  change to the owning unit's spec, not this one.
- **Auto-reassigning an expert without a human.** Unit 15 settled this: the timer
  *prompts*, matching `project-overview.md`'s "the case auto-prompts reassignment".
  Nothing here moves a case off an expert on its own.
- Sending anything to a client or an expert directly. Jobs publish domain events;
  Unit 18 delivers them; GHL and Dropbox Sign do the sending (invariant 14).
- A message broker, a distributed scheduler, or a second application instance.
  `architecture.md`'s NFRs rule all three out.

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
  - retention: the `retention_30_sent_at` … `retention_365_sent_at` columns
    **already on `evalos_case`** since Unit 03, unused until now;
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

## The calendar, and the one place it does not apply

Every SLA and reminder threshold is in **business hours** on
`service/BusinessCalendar` — 09:00–17:00 America/Los_Angeles, weekends and the
eleven US federal holidays with their observance shifts. It already exists and is
already tested; **no new calendar code**. A case that arrives Friday at 16:00 is not
overdue on Saturday morning.

**Two exceptions, both deliberate:**

- **Retention timers are wall-clock days**, not business hours. "90 days since we
  delivered" is a fact about the client's calendar, not EvalOS's working week —
  the same distinction Unit 10 drew between the SLA (business hours) and checklist
  aging (wall-clock, "which is what a client experiences").
- **The outbox sender's backoff is wall-clock** (Unit 18). A subscriber being down
  has nothing to do with office hours.

## The jobs

| Job | Tick | Finds | Does | Idempotency from |
| --- | --- | --- | --- | --- |
| `DocChaseSweep` | 30 min | `DOC_COLLECTION`, checklist incomplete, ≥24h and ≥48h business since `stage_entered_at` | publishes `checklist.reminder` (Unit 10's event → GHL chases the client) | **per threshold**, from the count of `CHASED` audit rows: the 24h chase fires when there are none, the 48h chase when there is one, and **nothing after that** |
| `DocEscalationSweep` | 1 h | `DOC_COLLECTION`, incomplete at 3 business days | publishes `docs.escalation.day3` → in-app to the PM + Brand Manager, and flags the board | the Unit 06 notification rows |
| `StageSlaSweep` | 30 min | any active case whose `SlaCalculator` status is `AT_RISK`/`BREACHED` and whose stored `sla_status` is stale | refreshes `sla_status`, notifies the stage's owner on a transition into breach | the stored `sla_status` — it only notifies on a change |
| `ExpertSignSweep` | 30 min | `EXPERT_SIGNING`, unsigned, ≥20h and ≥24h business | 20h warning to CM + PM; at 24h `expert.sign_overdue` + the reassignment prompt with Unit 12's shortlist. **The prompt asks a human to fire Unit 15's `EXPERT_TIMED_OUT`; this sweep never fires it** | notification rows per threshold |
| `RetentionSweep` | daily | `CLOSED`, un-refunded, 30/90/180/365 **wall-clock** days since `case_closed_date` | publishes the retention event → GHL runs the sequence; stamps the matching `retention_*_sent_at` | the four `retention_*_sent_at` columns |
| `OutboxSender` | 1 min | `PENDING`/`FAILED` deliveries with `next_attempt_at <= now()` | signs and posts (Unit 18's logic, moved not rewritten) | the delivery row's `status`/`attempts`, claimed `FOR UPDATE SKIP LOCKED` |

Notes that matter:

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
  The finders are therefore deliberately brand-wide, carry the javadoc convention the
  Unit 10 review established for `findByCaseIdIn` ("do not call it with ids that came
  from a request"), and get the same DB-gated brand-isolation test. Notifications and
  events they raise carry the **case's own** brand, via
  `AuditService.recordSystemEvent` — the actor is the system, and the brand comes from
  the row, never from a default.
- **Unpaid cases are chased for documents and nothing else.** Doc collection against
  an unpaid case is deliberately allowed (Unit 05a); no sweep escalates or prompts on
  a case that has not paid past that point, because it cannot leave
  `DOC_COLLECTION` anyway.

## Backend

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/jobs/runs | GM | the run ledger: last run, duration, items seen/acted, failures |
| POST | /api/jobs/{jobType}/run | GM | run one sweep now — for verification and for recovering after an outage |

GM-only, gated at the route and in the service, for the reason Unit 18's log is:
this is cross-brand infrastructure.

**The manual trigger is safe to press twice**, because every sweep's idempotency
comes from the data rather than from having-not-run-yet. That is a design property
worth having a button for: after an outage somebody can catch up deliberately
instead of waiting for the next tick.

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
      produces one chase, one escalation, one warning, one retention event.
- [ ] Thresholds are business hours: a case entering `DOC_COLLECTION` at 16:00 on the
      Friday before a Monday holiday is first chased on **Tuesday**, not Saturday.
      Asserted against `BusinessCalendar`, with a federal holiday in the fixture.
- [ ] Retention timers are **wall-clock**: a case closed 30 calendar days ago fires,
      regardless of weekends, and stamps `retention_30_sent_at`.
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
EvalOS — retention and chase events go to GHL, which runs the sequences (2); no
sweep mutates a case in a way a role could not (3) — every one of them goes through
the owning unit's existing transition or publishes an event; `payment_detail` is
untouched (4); **all timed work lives in `job`, which is exactly what invariant 6
asks for** — this unit is the invariant being satisfied rather than merely respected;
system-actor audit rows carry the resolved brand (13); no email — every message is a
domain event for GHL or Dropbox Sign (14).

## Files touched

**Created.** Backend: `job/DocChaseSweep.java`, `job/DocEscalationSweep.java`,
`job/StageSlaSweep.java`, `job/ExpertSignSweep.java`, `job/RetentionSweep.java`,
`job/JobRunLedger.java`, `job/JobScheduleConfig.java`, `domain/ScheduledJob.java`,
`domain/JobStatus.java`, `repository/ScheduledJobRepository.java`,
`web/JobAdminController.java`. Migration `V<next>__scheduled_job.sql`. Frontend:
`frontend/src/features/admin/JobRuns.tsx` + `jobApi`.

**Moved.** `webhook/outbound/WebhookSender.java` → `job/OutboxSender.java`, with its
tests. Moved, not rewritten — Unit 18's retry, backoff, dead-letter and
`SKIP LOCKED` behaviour is already specified and tested, and re-deriving it here
would be a second implementation of the same rules.

**Modified.** `application.yml` + profiles (intervals, `evalos.jobs.enabled`).
`domain/Case.java` — accessors for the four `retention_*_sent_at` columns that have
existed unused since Unit 03. `event/CaseEvents.java` — the retention event types, if
Unit 18 has not already added them. `frontend/src/features/shell/navigation.ts`.

**Not touched.** Every business rule: `CaseTransitions`, `SlaCalculator`,
`BusinessCalendar`, `ChecklistService`, `ExpertSignService`, `RefundService`. This
unit calls them and changes none of them. `service/ScopePredicate.java`, the inbound
gateway, every applied migration.
