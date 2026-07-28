-- The system of record. `case` is reserved in SQL, hence `evalos_case`.
-- `expert_id` is declared here but its foreign key is added in V7, because the
-- expert table does not exist yet.
CREATE TABLE evalos_case (
    id                         uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id                   uuid          NOT NULL REFERENCES brand (id),
    team_id                    uuid,
    -- Human-facing id, generated on create (Unit 05).
    case_code                  text          UNIQUE,
    pool_status                text,
    assigned_pm                uuid          REFERENCES team_member (id),
    assigned_cm                uuid          REFERENCES team_member (id),
    contact_id                 uuid          REFERENCES contact_snapshot (id),
    service_type               text,
    service_subtype            text,
    visa_category              text,
    client_type                text,
    -- Role-restricted in the DTO (PM / Brand Manager / GM only).
    deal_value                 numeric(12,2),
    deadline                   timestamptz,
    current_stage              text          NOT NULL,
    exception_state            text          NOT NULL DEFAULT 'NONE',
    stage_entered_at           timestamptz,
    -- Computed by the state machine in Unit 04, never hand-set.
    sla_status                 text,
    pm_strategy_notes          text,
    expert_id                  uuid,
    expert_sign_status         text,
    draft_version_count        int           NOT NULL DEFAULT 0,
    pm_approval_status         text,
    client_approval_status     text,
    client_portal_read_at      timestamptz,
    -- Documents live in Google Drive; EvalOS stores the link, never the bytes.
    drive_link                 text,
    invoice_ref                text,
    campaign_attribution       text,
    delivery_date              timestamptz,
    case_closed_date           timestamptz,
    google_review_requested    boolean       NOT NULL DEFAULT false,
    google_review_requested_at timestamptz,
    retention_30_sent_at       timestamptz,
    retention_90_sent_at       timestamptz,
    retention_180_sent_at      timestamptz,
    retention_365_sent_at      timestamptz,
    created_at                 timestamptz   NOT NULL DEFAULT now()
);

-- The board read: brand, then the finer scopes, then the stage column it groups by.
CREATE INDEX idx_case_scope_stage ON evalos_case (brand_id, team_id, assigned_cm, current_stage);
CREATE INDEX idx_case_brand_deadline ON evalos_case (brand_id, deadline);
CREATE INDEX idx_case_brand_sla ON evalos_case (brand_id, sla_status);
CREATE INDEX idx_case_brand_expert ON evalos_case (brand_id, expert_id);
