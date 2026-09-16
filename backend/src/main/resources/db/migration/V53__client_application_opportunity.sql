-- The request points at the opportunity row it opened, not just at GHL's id for it.
--
-- WHY, AND IT IS NOT TIDINESS. `client_application.ghl_opportunity_id` is null until GHL answers
-- a create. That was fine while a failed create simply meant "try again on the next save" -- and it
-- stops being fine at Unit 44d, where EvalOS writes its OWN opportunity row first and puts that
-- row's id into a GHL custom field as the correlation key (`00d` §6.1).
--
-- The correlation key only works if it is PERSISTED BEFORE THE CALL. A key minted in memory, sent,
-- and lost to a timeout is a key no retry can search for -- which is the exact failure §6.1 is
-- about: "at-least-once delivery over a non-idempotent create is how one opportunity becomes two".
-- This column is where it is persisted, and it is also what makes the retry idempotent: a second
-- attempt reuses the row it already opened instead of minting a second one.
--
-- It is additionally the join `.claude/data-model.md` lists as missing -- "a join from
-- client_application to the case it became" -- one step closer. Today both sides hold
-- `ghl_opportunity_id` as unrelated text.
ALTER TABLE client_application
    ADD COLUMN opportunity_id uuid REFERENCES opportunity (id);

COMMENT ON COLUMN client_application.opportunity_id IS
    'The EvalOS opportunity row this request opened. Its id is the GHL correlation key (Unit 44d). '
    'Null on a request that has not reached the service-chosen step.';
