-- Units 13 (redacted CV), 18 (outbound dispatcher) and 20 (AI widgets) are removed from scope.
--
-- Only Unit 13 had a schema footprint, and only a small one: `REDACTED_PROFILE` in
-- `case_document`'s kind vocabulary. The enum no longer carries it, and the CHECK is the writer
-- the enum cannot reach -- a seed script or a hand-run UPDATE could still write one, and the row
-- would then break `valueOf` on the next read.
--
-- **`AuditAction.EXPORTED` is deliberately NOT removed**, and the difference is worth stating.
-- Nothing ever wrote a REDACTED_PROFILE version (that table is V31; Unit 13 stalled long before
-- it), so dropping the value can orphan nothing. The audit trail is the opposite case: it is
-- append-only by invariant, its rows can never be rewritten, and an enum that cannot read a value
-- some historical row carries would fail on read. A retired audit action stays readable forever.
--
-- 18 and 20 were never built: no table, no column, nothing to reverse. Their removal is a scope
-- decision recorded in the specs, not a migration.

ALTER TABLE case_document
    DROP CONSTRAINT case_document_kind_known,
    ADD CONSTRAINT case_document_kind_known CHECK (kind IN (
        'DRAFT', 'CLIENT_UPLOAD', 'SIGNED_LETTER'
    ));

-- No rows to clean: nothing ever wrote a REDACTED_PROFILE version (Unit 13 predates
-- `case_document`, which V31 created), and the DELETE is here so a database that somehow has one
-- fails loudly at the constraint rather than silently keeping it.
DELETE FROM case_document WHERE kind = 'REDACTED_PROFILE';
