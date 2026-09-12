-- Unit 38 — the opportunity cache.
--
-- **This is the first EvalOS row that holds a pipeline fact, and that is the whole cost of the
-- pivot.** Unit 29's sales desk was removed in one migration with no data reconciliation because
-- no such row existed. After this, that is no longer true. Spec:
-- context/specs/38-opportunity-reads-and-boards.md; the truth model is in
-- context/specs/00b-ghl-operational-programme.md §1.3.
--
-- **Why it exists at all**, since architecture.md invariant 2 spent years warning against exactly
-- this. Arithmetic, not preference — the same arithmetic invariant 6 already records for
-- MarketingPipelineService. GHL allows 100 requests per 10 seconds per location; a year is ~11.4k
-- opportunities across ~115 cursor pages that cannot be parallelised; that is a ~13s floor, past
-- the browser's 15s timeout. A pass-through board is not slow, it does not load.
--
-- **What keeps it from becoming a second CRM**, and each of these is checkable:
--   1. Every column below is a field GHL owns. If a column ever appears here that GHL has no
--      field for, the truth model is void and gets re-argued in 00b before the column lands.
--   2. It is droppable without loss. `TRUNCATE ghl_opportunity_cache` costs a refill and nothing
--      else: no write path reads it to decide anything, and no screen shows a field that lives
--      only here.
--   3. It is never the answer to a write. A write goes to GHL and this row is replaced from GHL's
--      RESPONSE, never from the request body — optimistic local state is what turns a cache into
--      a second source of truth.

CREATE TABLE ghl_opportunity_cache (
    -- GHL's own id is the key. EvalOS mints nothing here (invariant 7): this table is a copy of
    -- someone else's records, and giving the copies identities of our own is the first step to
    -- treating them as ours.
    ghl_opportunity_id text        PRIMARY KEY,

    -- The access key. Every read of this table is `WHERE ghl_pipeline_id = ?` with the value
    -- taken from the caller's own principal, so the scope is the query rather than a predicate
    -- somebody has to remember to add.
    ghl_pipeline_id    text        NOT NULL,

    -- The client, and the canonical external identity everywhere (invariant 7).
    ghl_contact_id     text        NOT NULL,

    stage_id           text        NOT NULL,
    status             text        NOT NULL,
    name               text,
    -- No `currency` column: GHL's search response does not carry one, so it would be null on
    -- every row. One GHL location bills in one currency, and `brand.currency` already records
    -- which. A column nothing can populate is not a placeholder, it is a field that will be
    -- misread as "unknown" rather than "not applicable".
    amount             numeric(12, 2),

    -- GHL's own last-modified, kept so a future delta poll has something to poll on. Nullable
    -- because GHL does not always send it.
    updated_in_ghl_at  timestamptz,

    -- When EvalOS last read this row from GHL. The TTL is measured from here.
    fetched_at         timestamptz NOT NULL
);

-- **Deliberately NO brand_id, and this is a decision rather than an omission.**
--
-- An opportunity belongs to a GHL *location*, not to an EvalOS brand, and while Unit 36 §4a's
-- single-brand ceiling holds there is exactly one selling brand. A brand column here would hold
-- one value for every row and would look like a scope while narrowing nothing — which is worse
-- than no column, because the next reader would trust it.
--
-- What replaces it: `evalos.ghl.sales-brand` bounds who can hold a pipeline at all, and every
-- read here is keyed on a pipeline id that came from the caller's principal.
--
-- Unit 25 (location + token per brand) is when this gains a brand column, and that migration
-- rewrites this comment rather than adding one beside it.

-- The board reads a whole pipeline at a time, which is the only access pattern there is.
CREATE INDEX idx_ghl_opportunity_cache_pipeline ON ghl_opportunity_cache (ghl_pipeline_id);

-- The sweep that drops stale rows reads this. Separate from the index above because a TTL sweep
-- spans every pipeline and would otherwise scan.
CREATE INDEX idx_ghl_opportunity_cache_fetched ON ghl_opportunity_cache (fetched_at);
