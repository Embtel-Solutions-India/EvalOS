-- Unit 19 — the sweep run ledger.
--
-- **This table records RUNS, not intentions**, and that is the unit's main design decision.
-- `architecture.md` names "a persisted scheduled_job table", which can be read as a queue of
-- future timers or as a ledger of past sweeps. It is the second, because:
--
--   1. A missed run must self-heal. A row-per-timer queue that the app was down for either
--      fires a burst of stale rows on restart or drops them. A sweeper asks "which cases are
--      overdue RIGHT NOW" and is correct on its first run after any outage, however long.
--   2. Idempotency already exists in the data. The doc chases count `CHASED` audit rows, the
--      sign alerts count notification rows, and the SLA sweep compares against the stored
--      `sla_status`. A job row saying "the 24h chase fired" would be a SECOND record of a fact
--      the system already holds — and two records of one fact can disagree, which is the
--      reasoning Unit 05a used to refuse a `paid_by` column.
--   3. What a table genuinely earns is observability: which sweep ran, when, for how long, how
--      many rows it touched, and what failed. The data model does not already provide that.
--
-- Spec: context/specs/19-background-jobs.md.

CREATE TABLE scheduled_job (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- **No `brand_id`, deliberately.** A sweep spans brands by nature — there is no
    -- authenticated caller to scope it to — which is the same situation the inbound gateway
    -- and `audit_event`'s nullable brand are in. What each sweep DOES is per-brand: every
    -- notification and event it raises carries the case's own brand, taken from the row.
    job_type    text        NOT NULL,

    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,

    -- RUNNING until the sweep returns. A row stuck in RUNNING is itself the signal that a JVM
    -- died mid-sweep — which is worth seeing, and is why the row is written at the start.
    status      text        NOT NULL,

    -- The two numbers that say whether a sweep is working. `seen` without `acted` is a healthy
    -- quiet day; `seen` at zero every run means the finder is wrong.
    items_seen  integer     NOT NULL DEFAULT 0,
    items_acted integer     NOT NULL DEFAULT 0,

    error       text,

    CONSTRAINT scheduled_job_status_valid CHECK (status IN ('RUNNING', 'OK', 'FAILED'))
);

-- "When did this last run" is the only query this table has.
CREATE INDEX idx_scheduled_job_type_started ON scheduled_job (job_type, started_at DESC);
