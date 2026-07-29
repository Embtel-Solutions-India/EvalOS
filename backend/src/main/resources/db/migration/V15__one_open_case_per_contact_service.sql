-- "One open case per contact per service" was enforced only by a SELECT-then-INSERT
-- inside CaseIntakeService.intake. That is a check-then-act: two contact.created
-- deliveries carrying different event ids are not deduplicated by the gateway (they are
-- genuinely different deliveries), so under READ COMMITTED both transactions can run
-- the lookup before either commits, both see nothing, and both create a case. The
-- result is two case codes, two checklists and two pool alerts for one piece of work.
--
-- A partial unique index cannot race, because the second INSERT is refused by the
-- database rather than by a read. The loser's transaction rolls back, the gateway
-- records the error and answers a retriable 5xx, and the redelivery finds the committed
-- row and refreshes it — which is the behaviour intake wanted in the first place.
--
-- Partial on `current_stage <> 'CLOSED'` because the rule is about *open* cases: a
-- contact coming back after their first case closed is new business and must be allowed
-- a second row. NULL contact_id or service_type rows are exempt (Postgres treats NULLs
-- as distinct here), which is correct — a row that names no contact is not a duplicate
-- of anything. Intake always sets both.
CREATE UNIQUE INDEX uq_case_open_per_contact_service
    ON evalos_case (brand_id, contact_id, service_type)
    WHERE current_stage <> 'CLOSED';
