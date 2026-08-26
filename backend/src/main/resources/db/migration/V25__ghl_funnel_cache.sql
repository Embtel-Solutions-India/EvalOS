-- The marketing funnel's cached payload, moved out of heap memory and into the database.
--
-- WHAT THIS IS, AND WHAT IT IS NOT. This is a cache of a READ from GHL, not a copy of GHL's
-- pipeline. There is still no `ghl_opportunity` table and there must not be one: individual
-- opportunities are not stored, only the aggregate the screen displays (per-stage counts, the
-- sums, the source group-by). GHL remains the owner of the funnel; EvalOS keeps the last
-- answer it was given, with the timestamp it was given at.
--
-- WHY IT IS A TABLE. It was a `ConcurrentHashMap` on one instance, which cost three things:
--   1. A completed background total was lost on restart, so the next reader paid the whole
--      11,000-row read again.
--   2. With more than one app instance, a screen polling for a TOTALLING window could land on
--      an instance that had never heard of it and be told to keep waiting forever, or flip
--      between a READY answer and a TOTALLING one depending on which instance answered.
--   3. The rate-limit protection was per instance, so N instances meant N times GHL's budget.
-- All three are the same defect: the handover state was private to a process.
--
-- NOT BRAND-SCOPED, and that is not an oversight. Every scoped table in EvalOS filters by
-- `brand_id`; this one has no such column because the figures have no brand. They come from
-- one GHL sub-account named by a global setting, and EvalOS has no mapping from a location to
-- a brand, so a `brand_id` here would be a value nobody could correctly fill in. The endpoint
-- is GM-only for exactly that reason. Unit 25 puts the location on `brand`, and THAT is when
-- this table gets a `brand_id` -- as part of the same change, not before it.
--
-- NOT APPEND-ONLY. The append-only rule covers audit and assignment history, which are the
-- record of what happened. This is a cache: a row is overwritten every time the window is
-- re-read, and losing every row costs one slow page load and nothing else. Safe to TRUNCATE.
CREATE TABLE ghl_funnel_cache (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- `MarketingPipelineService.Funnel` -- ADS or EMAIL.
    funnel          text        NOT NULL,
    -- `DateRange` -- TODAY, WEEK, MONTH, YEAR. Named `range_name` and not `range` because
    -- `range` reads as the SQL type name and shadows it in every hand-written query.
    range_name      text        NOT NULL,
    -- The whole `MarketingPipeline` record as JSON. Deliberately one opaque document rather
    -- than normalised stage and source rows: nothing queries inside it -- it is read back whole
    -- and handed to the controller -- so columns per stage would be schema nobody reads,
    -- migrated every time the payload gains a field.
    payload         jsonb       NOT NULL,
    -- READY | TOTALLING | UNAVAILABLE, lifted out of the payload so the claim below can be
    -- taken in one statement without parsing JSON.
    detail          text        NOT NULL,
    -- When GHL was actually asked. The TTL is measured from here, and the screen shows it so a
    -- cached figure is never read as a live one.
    read_at         timestamptz NOT NULL,
    -- The background totaller's claim, and the replacement for the in-heap `totalling` set.
    -- Non-null means an instance is reading the rows for this window right now, so a screen
    -- polling every 5s queues one read rather than one per poll.
    --
    -- It is a TIMESTAMP rather than a boolean so the claim can EXPIRE. A heap set died with the
    -- process that held it; a row does not, so an instance killed mid-total would otherwise
    -- wedge this window at TOTALLING until somebody noticed. A claim older than the staleness
    -- bound in MarketingPipelineService is re-claimable.
    totalling_since timestamptz,
    -- Optimistic locking, and it is load-bearing. Two callers can race past a stale row: the
    -- slow one must not overwrite a READY payload the background totaller just wrote (which
    -- would also restart a read for work already finished), and a failed background read must
    -- not blank a READY row to UNAVAILABLE. Both were compare-and-set on the map; this is the
    -- same guarantee one layer down.
    version         bigint      NOT NULL DEFAULT 0,
    -- One row per window. The key is BOTH halves: the two funnels' payloads are identical in
    -- shape, so a key missing `funnel` would serve the ads figures under the email heading for
    -- a whole TTL with nothing on screen to contradict it.
    CONSTRAINT uq_ghl_funnel_cache_window UNIQUE (funnel, range_name)
);
