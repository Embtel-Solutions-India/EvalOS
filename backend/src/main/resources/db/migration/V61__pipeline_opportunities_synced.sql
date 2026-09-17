-- The board's sync stamp moves onto the pipeline it describes.
--
-- THE BUG THIS FIXES: every board read "never synced", including ones that had just synced.
--
-- `OpportunityBoardService` asks "when did the sync last confirm these pipelines", and until now
-- the answer came from `max(opportunity.synced_at)` over the pipeline's deals. That is the wrong
-- place for it, and the failure is not subtle once seen: **a pipeline with no deals has no row to
-- carry a timestamp**, so a pipeline that synced successfully and found nothing reports exactly the
-- same thing as one that has never been synced at all.
--
-- It stayed invisible while the board refilled from GHL on every render (pre-46) and while the
-- board substituted `now()` for a null age. Unit 46 removed the refill, and made a null render as a
-- "Sync delayed" banner -- correct on its own terms, and it turned this latent wrong answer into
-- every board on the screen claiming the sync was broken.
--
-- SO THE STAMP GOES WHERE THE QUESTION IS ASKED. A sync is something that happens to a PIPELINE:
-- it either read that pipeline's opportunities or it did not, and how many came back is a separate
-- fact. `opportunities_synced_at` is written by `OpportunityMirrorService.absorb` on every pass,
-- including a pass that absorbed zero rows -- which is the case the derived answer could not
-- express.
--
-- NULL STILL MEANS NEVER, and now it means only that.

ALTER TABLE pipeline
    ADD COLUMN opportunities_synced_at timestamptz;

COMMENT ON COLUMN pipeline.opportunities_synced_at IS
    'When this pipeline''s opportunities were last read from GHL -- including a read that returned '
    'none. Distinct from synced_at, which is when the PIPELINE ITSELF was last confirmed by the '
    'PIPELINE_MIRROR sweep. Null means never read. Unit 46 board staleness reads this.';
