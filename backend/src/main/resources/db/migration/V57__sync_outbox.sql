-- Unit 45, slice C: a push to GHL becomes a durable row.
--
-- `00c` §4d, decided 2026-09-12, and the chain of reasoning is short: no mismatch requires that a
-- failed push is retried; a retry is only safe if it cannot double-apply; and **invariant 2 said
-- "writes do not retry" precisely because EvalOS had no key scheme.** Unit 44d's correlation key is
-- that scheme, so this is the day.
--
-- NO BROKER, NO QUEUE SERVER. A table, a sweep and backoff -- Unit 19's shape, which is what
-- `architecture.md` said to re-read the argument from if outbound delivery ever came back.
--
-- WHAT IT DOES *NOT* DO YET. The desks still write to GHL synchronously and read the answer back --
-- a salesperson needs the created id and GHL's `isNew`. Moving them onto the mirror is **Unit 46**.
-- What this slice queues is the two writes that are today SWALLOWED AND LOST: the client portal's
-- opportunity create when GHL is unreachable, and its "request submitted" marker. Both are marked
-- `ponytail:` in `ClientApplicationService` as waiting for exactly this.

CREATE TABLE sync_outbox (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),

    entity_type     text        NOT NULL,

    -- THE ROW, NOT A PAYLOAD SNAPSHOT (`00d` §6.3, second bullet). The sender reads the CURRENT row
    -- at send time, "otherwise 'collapse onto the pending one even if a field changed' silently
    -- sends the older values". A queue holding a copy of what was true when it was queued is a queue
    -- that delivers stale writes on every retry.
    entity_id       uuid        NOT NULL,

    -- COARSE ON PURPOSE (`00d` §6.3): "keep `intent` coarse (UPSERT/CLOSE/DELETE), or the collapse
    -- never happens." Three edits to one opportunity in a minute must become ONE pending row; an
    -- intent per field would make each of them distinct and send three.
    intent          text        NOT NULL CHECK (intent IN ('UPSERT', 'CLOSE', 'DELETE')),

    queued_at       timestamptz NOT NULL,

    -- Retry state. `last_failure` is `GhlFailure`'s own name (Unit 45a) rather than a parsed string,
    -- which is the whole point of classifying at the door: the sweep branches on it, and a human
    -- reading the table sees the same word the code did.
    attempts        integer     NOT NULL DEFAULT 0,
    last_attempt_at timestamptz,
    last_failure    text,
    last_error      text,

    sent_at         timestamptz,

    -- Dead-lettered: a non-retriable refusal, or the attempt cap. The row STAYS -- a write that
    -- never reached GHL is exactly the thing somebody needs to be able to find afterwards.
    dead_at         timestamptz,
    dead_reason     text
);

-- THE DEDUPE KEY, AND IT MUST BE PARTIAL (`00d` §6.3): "`where sent_at is null and dead_at is null`.
-- A plain unique constraint is the obvious wrong reading, and it makes the second edit of the same
-- opportunity an hour later collide with the first's SENT row."
--
-- Pending rows collapse; delivered and dead ones are history and do not block the next edit.
CREATE UNIQUE INDEX uq_sync_outbox_pending
    ON sync_outbox (brand_id, entity_type, entity_id, intent)
    WHERE sent_at IS NULL AND dead_at IS NULL;

-- The drain's read: oldest pending first, so a write is never starved by a newer one.
CREATE INDEX idx_sync_outbox_pending ON sync_outbox (queued_at)
    WHERE sent_at IS NULL AND dead_at IS NULL;

-- What a human looks for after an incident.
CREATE INDEX idx_sync_outbox_dead ON sync_outbox (brand_id, dead_at DESC) WHERE dead_at IS NOT NULL;

COMMENT ON TABLE sync_outbox IS
    'Durable EvalOS->GHL pushes (Unit 45c). entity_id, never a payload: the sender reads the current '
    'row at send time. Pending rows collapse on (entity, intent); sent and dead rows are history.';
