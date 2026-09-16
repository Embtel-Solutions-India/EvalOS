-- Unit 45, slice B: every divergence between EvalOS and GHL becomes a row.
--
-- WHY A TABLE. `00d` §6.3, first bullet: "`sync_drift` must be a table. `00c` §4b and §4c both say
-- 'the drift report' as if it were a document. §4c's guarantee that EVERY DIVERGENCE IS DETECTED is
-- only checkable if yesterday's divergences are still queryable." A report printed to a log is a
-- claim; a row is evidence.
--
-- IT IS ALSO WHAT MAKES UNIT 48 POSSIBLE. `00c` §3 calls 48 "a real unit, not a config flip":
-- running the business with sync disabled and listing what degrades. That list cannot be written by
-- anyone who cannot first answer "is the mirror actually right?" -- and until this table, nobody
-- could.
--
-- THIS SLICE DETECTS AND RECORDS. IT NEVER REPAIRS. Conflict resolution is per-field ownership
-- (`00d` §6.2) and belongs to a later slice, deliberately: a detector that also mutates is one you
-- cannot trust to tell you the truth, because its own writes become the next night's findings.

CREATE TABLE sync_drift (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),

    -- OPPORTUNITY today. CONTACT joins when a contact read client exists (see the sweep's note);
    -- PIPELINE and PIPELINE_STAGE are deliberately absent -- the 44a sweep overwrites them hourly
    -- through the same code path an audit would compare against, so auditing them would report zero
    -- by construction.
    entity_type     text        NOT NULL,

    -- EvalOS's row. NULL when GHL holds something EvalOS has never seen.
    entity_id       uuid,

    -- GHL's id. NULL when EvalOS holds a row GHL has never acknowledged -- which is legal and
    -- common, not drift: a portal-born opportunity has no `ghl_id` until GHL answers. The sweep
    -- excludes those; this column is null only on a row recorded for some other reason.
    ghl_id          text,

    -- MISSING_LOCALLY | MISSING_IN_GHL | FIELD_MISMATCH.
    kind            text        NOT NULL
                    CHECK (kind IN ('MISSING_LOCALLY', 'MISSING_IN_GHL', 'FIELD_MISMATCH')),

    -- Which field disagreed, and what each side said. Null on the two MISSING kinds.
    --
    -- BOTH VALUES ARE STORED AS TEXT AND BOTH ARE STORED. A drift row naming a field without saying
    -- what the two sides held is a row somebody has to go and investigate by hand, which is the
    -- report-as-a-document failure this table exists to replace.
    field           text,
    local_value     text,
    ghl_value       text,

    -- WHEN IT WAS FIRST SEEN AND WHEN IT WAS LAST SEEN, not one row per audit run.
    --
    -- A drift that persists for a week is ONE fact, and a row per night would bury the new findings
    -- under the old ones -- the exact noise that makes a report get ignored. `last_seen_at` is what
    -- says "still true this morning".
    first_detected_at timestamptz NOT NULL,
    last_seen_at      timestamptz NOT NULL,

    -- A later audit found the two sides agreeing again. The row STAYS: "this drifted and then
    -- stopped" is the history §6.3 asks to keep, and deleting it would make the table unable to
    -- answer whether a fix worked.
    resolved_at     timestamptz,

    created_at      timestamptz NOT NULL DEFAULT now()
);

-- ONE OPEN ROW PER THING THAT IS WRONG. Partial, `where resolved_at is null`, which is the same
-- lesson `00d` §6.3 records for the outbox's dedupe key: a plain unique constraint would make
-- tonight's re-detection of a drift that was fixed and came back collide with the resolved row from
-- last week.
--
-- `coalesce` on the nullable halves because Postgres treats NULLs as distinct in a unique index,
-- which would let the same missing-locally row be recorded every single night.
CREATE UNIQUE INDEX uq_sync_drift_open
    ON sync_drift (brand_id, entity_type, coalesce(ghl_id, ''), coalesce(entity_id::text, ''),
                   coalesce(field, ''))
    WHERE resolved_at IS NULL;

-- The screen's read: what is wrong right now, newest first.
CREATE INDEX idx_sync_drift_open ON sync_drift (brand_id, last_seen_at DESC) WHERE resolved_at IS NULL;

COMMENT ON TABLE sync_drift IS
    'Divergences between EvalOS and GHL, detected nightly (Unit 45b). Never repaired here -- '
    'resolution is per-field ownership in a later slice. Rows survive resolution as history.';
