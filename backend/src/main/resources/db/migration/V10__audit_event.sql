-- Append-only audit for every object, not just cases (invariant 13). Global
-- rather than brand-scoped: `brand_id` is nullable because system events (a job,
-- an inbound webhook before brand resolution) have no brand.
CREATE TABLE audit_event (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id        uuid        REFERENCES brand (id),
    object_type     text        NOT NULL,
    object_id       uuid        NOT NULL,
    action          text        NOT NULL,
    -- team_member.id, or NULL when the actor is the system. No foreign key: an
    -- audit row must outlive the member it refers to.
    actor_id        uuid,
    before_snapshot jsonb,
    after_snapshot  jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_object ON audit_event (object_type, object_id);
CREATE INDEX idx_audit_brand_created ON audit_event (brand_id, created_at);

-- Append-only, enforced in the database as well as in the repository. A GRANT
-- cannot do this job: the application connects as the table owner, and an owner
-- is not subject to REVOKE. A trigger holds for every role, including the owner.
CREATE FUNCTION audit_event_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is not permitted', tg_op;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_mutation
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_is_append_only();
