-- One required document on a case. The file itself is a Google Drive link on the
-- case; this row only tracks whether the document has arrived and passed review.
CREATE TABLE document_checklist_item (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id   uuid        NOT NULL REFERENCES brand (id),
    case_id    uuid        REFERENCES evalos_case (id),
    label      text,
    status     text,
    updated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_checklist_brand_case ON document_checklist_item (brand_id, case_id);
