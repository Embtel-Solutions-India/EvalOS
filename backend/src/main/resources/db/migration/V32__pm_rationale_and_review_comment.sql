-- Unit 32: the PM's expert-selection rationale, and the review comment on a draft version.

-- **Its own column rather than more prose in `pm_strategy_notes`**, for three reasons that are all
-- about it being a different fact rather than a longer one.
--
-- It has a different LIFETIME: strategy is written once and refined, while this is written per
-- expert -- and Unit 31 made reassignment a normal path, so a case can go through two or three.
-- Folded into the strategy field, a reassignment either overwrites the case strategy or the new
-- expert's rationale is never recorded at all.
--
-- It has a different AUDIENCE: strategy is guidance for the Case Manager writing the draft; this is
-- oversight, read by the Expert Network Manager and not by the CM. One column cannot be projected
-- to two different role sets.
--
-- And it is EVIDENCE. "Why was this expert chosen" is the question asked after something has gone
-- wrong, and an answer buried inside a paragraph about case strategy is one nobody finds.
--
-- Not versioned, deliberately: the current rationale is what matters, and the history of who was
-- assigned when already exists twice -- in the audit trail and in `expert_case_offer`.
ALTER TABLE evalos_case ADD COLUMN expert_selection_rationale text;

-- The PM's comment on one draft version.
--
-- **Stamped on the version by the transition, never joined to it afterwards.** The reason already
-- reaches the audit trail as the transition's reason, so the temptation is to render the history by
-- matching an audit row to a version by timestamp. Time is not an identity: two quick review rounds
-- would attach the wrong comment to the wrong version, and it would fail silently and look
-- plausible. The audit row stays -- this is a projection of it onto the artefact it is about, the
-- same relationship `expert_case_offer` has to the trail.
ALTER TABLE case_document ADD COLUMN review_comment text;

-- **`filename` becomes nullable until Unit 30 lands, and this is a temporary honesty rather than a
-- relaxation.** V31 declared it NOT NULL on the assumption that a row names an uploaded file. Today
-- there is no upload: `submitDraft` carries a LINK, and the S3 store that will carry bytes is
-- specced and unbuilt. A NOT NULL column filled with a placeholder is worse than a null -- it looks
-- like a filename and is not one. Unit 30 restores the constraint when there is a file to name.
ALTER TABLE case_document ALTER COLUMN filename DROP NOT NULL;
