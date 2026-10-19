-- Which stage wins a deal, said explicitly (2026-10-15). Moving a deal onto a won stage closes it won
-- and needs the production team's note (D70). It used to be inferred from the name ("won", trimmed,
-- any case), so a stage renamed in GHL to "Closed won" quietly became a plain move.
--
-- EVALOS OWNS THIS COLUMN, the one exception to "GHL owns every column" on this table: GHL has no
-- won marker on a stage, so the mirror sweep never writes it on a re-sync. A NEW stage the sweep
-- inserts defaults to won when it is named "Won" (same rule as below), so behaviour is unchanged
-- out of the box; after that only an Admin on the Pipelines screen changes it.
ALTER TABLE pipeline_stage ADD COLUMN won boolean NOT NULL DEFAULT false;

UPDATE pipeline_stage SET won = true WHERE lower(btrim(name)) = 'won';

COMMENT ON COLUMN pipeline_stage.won IS
    'EvalOS-owned: moving a deal here closes it won (D70). Defaulted from the name on insert; never re-synced.';
COMMENT ON TABLE pipeline_stage IS
    'Mirror of a GHL pipeline stage (Unit 44a). GHL owns every column except won (V94). Never deleted.';
