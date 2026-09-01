-- Re-maps the demo data's stage names onto Unit 31's twelve stages.
--
-- **Why this is a new file rather than an edit to V905.** V905 is applied on every developer
-- database and on `evalos_test`; editing it changes its checksum and Flyway refuses to migrate at
-- all. Applied migrations are not edited — the same rule that made V30 a migration of its own
-- rather than a rewrite of V29.
--
-- **Why V31 alone is not enough.** On an *existing* database V31 runs out-of-order (the local
-- profile allows it) and fixes the rows V905 already inserted. On a *fresh* one the version order
-- is V31 → V900…V905, so V905 inserts the old names **after** V31 has finished renaming, leaving a
-- demo database full of stages `Stage` cannot read. This file runs last and closes that window.
--
-- Safe to re-run and safe on a database that never had the old names: every statement is a no-op
-- when its source value is absent.

UPDATE evalos_case SET current_stage = 'PM_REVIEW'        WHERE current_stage = 'EXPERT_ASSIGNMENT';
UPDATE evalos_case SET current_stage = 'READY_TO_DELIVER' WHERE current_stage = 'FINAL_DELIVERY';
UPDATE evalos_case SET current_stage = 'DELIVERED'
    WHERE current_stage = 'READY_TO_DELIVER' AND delivery_date IS NOT NULL;

-- The same most-specific-first order V31 uses, and for the same reason: a row can satisfy more
-- than one condition, so the last write would otherwise win. Keep the two in step — if V31's
-- mapping is ever corrected, this is the second place that states it.
UPDATE evalos_case SET current_stage = 'CLIENT_REVIEW'
    WHERE current_stage = 'DRAFT_GENERATION' AND client_approval_status = 'PENDING';
UPDATE evalos_case SET current_stage = 'CLIENT_APPROVAL'
    WHERE current_stage = 'DRAFT_GENERATION' AND client_approval_status = 'APPROVED';
UPDATE evalos_case SET current_stage = 'DRAFT_IN_PROGRESS'
    WHERE current_stage = 'DRAFT_GENERATION' AND client_approval_status = 'REVISION_REQUESTED';
UPDATE evalos_case SET current_stage = 'DRAFT_REVIEW'
    WHERE current_stage = 'DRAFT_GENERATION' AND pm_approval_status = 'PENDING';
UPDATE evalos_case SET current_stage = 'READY_TO_SEND'
    WHERE current_stage = 'DRAFT_GENERATION' AND pm_approval_status = 'APPROVED';
UPDATE evalos_case SET current_stage = 'DRAFT_IN_PROGRESS'
    WHERE current_stage = 'DRAFT_GENERATION';

-- The demo data exists so the board has something on it. **Two of the twelve stages would now be
-- empty** — READY_TO_SEND and CLIENT_APPROVAL are new, and no seeded row lands on them by the
-- mapping above — which makes the two columns Unit 31 added the two nobody can see working.
-- One case each, moved from the drafting pile so the totals do not change.
UPDATE evalos_case SET current_stage = 'READY_TO_SEND', pm_approval_status = 'APPROVED'
    WHERE id = (SELECT id FROM evalos_case WHERE current_stage = 'DRAFT_IN_PROGRESS'
                ORDER BY case_code LIMIT 1);

UPDATE evalos_case SET current_stage = 'CLIENT_APPROVAL', pm_approval_status = 'APPROVED',
                       client_approval_status = 'APPROVED'
    WHERE id = (SELECT id FROM evalos_case WHERE current_stage = 'DRAFT_IN_PROGRESS'
                ORDER BY case_code LIMIT 1);
