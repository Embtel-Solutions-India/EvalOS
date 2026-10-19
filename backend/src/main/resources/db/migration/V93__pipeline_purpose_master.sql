-- MASTER: the location's Master Pipeline gets a purpose tag instead of being found by name.
--
-- OpportunityBoardService.defaultOf started the GM's drop-down on the first live pipeline whose name holds
-- "master" as a whole word, any case. A rename in GHL would have moved the GM's default with no warning, the
-- failure PipelinePurpose's own javadoc says a purpose exists to prevent.

-- 1. The CHECK learns the new value. V78 re-declared it under the name Postgres gave V50's inline one.
ALTER TABLE pipeline DROP CONSTRAINT pipeline_purpose_check;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_purpose_check
    CHECK (purpose IN ('MARKETING', 'SALES', 'DELIVERY', 'EXPERT_HIRING', 'MASTER', 'UNASSIGNED'));

-- 2. At most one Master Pipeline per brand. Over every row, missing ones included: a pipeline GHL stops
--    returning keeps its purpose (V50), and a partial index that skipped it would fail the sync that brought
--    it back. PipelineMirrorService.setPurpose refuses a second one with a sentence before this is reached.
CREATE UNIQUE INDEX uq_pipeline_one_master_per_brand ON pipeline (brand_id) WHERE purpose = 'MASTER';

-- 3. Tag the pipeline the name rule picks today, with the same rule: per brand, the first live pipeline in
--    board order (position, name) whose name holds "master" as a whole word (\m \M are Postgres' word
--    boundaries, as \b was Java's). Only when it is still UNASSIGNED: a purpose a GM already chose is their
--    judgement and is not overwritten. A brand left with no MASTER keeps the name rule as its fallback.
UPDATE pipeline SET purpose = 'MASTER'
 WHERE purpose = 'UNASSIGNED'
   AND id IN (SELECT DISTINCT ON (brand_id) id
                FROM pipeline
               WHERE missing_since IS NULL
                 AND name ~* '\mmaster\M'
               ORDER BY brand_id, position, name);
