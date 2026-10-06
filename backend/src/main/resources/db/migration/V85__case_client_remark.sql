-- D74 (Unit 76): what the client is told about their case, apart from the internal trail.
-- A remark is written by staff, shown to the client under the status the case was in when it
-- was written, and never edited or deleted: a correction is a newer remark.
CREATE TABLE case_client_remark (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id   uuid        NOT NULL REFERENCES brand (id),
    case_id    uuid        NOT NULL REFERENCES evalos_case (id),
    author_id  uuid        NOT NULL REFERENCES team_member (id),
    body       text        NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX case_client_remark_case_idx ON case_client_remark (brand_id, case_id, created_at);

CREATE FUNCTION case_client_remark_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'case_client_remark is append-only: % is not permitted', tg_op;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER case_client_remark_no_mutation
    BEFORE UPDATE OR DELETE ON case_client_remark
    FOR EACH ROW EXECUTE FUNCTION case_client_remark_is_append_only();
