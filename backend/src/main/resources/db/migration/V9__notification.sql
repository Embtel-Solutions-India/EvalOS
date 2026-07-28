-- In-app staff notification. EvalOS runs no mail server: this table is the whole
-- staff notification centre.
CREATE TABLE notification (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id     uuid        NOT NULL REFERENCES brand (id),
    recipient_id uuid        REFERENCES team_member (id),
    type         text,
    -- Loose reference: a notification may outlive the case it points at.
    case_id      uuid,
    body         text,
    read         boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_brand_recipient_read ON notification (brand_id, recipient_id, read);
