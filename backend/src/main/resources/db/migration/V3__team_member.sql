-- Staff users: the auth identity plus the brand/team anchor every scoped query
-- filters on.
CREATE TABLE team_member (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL means all brands. The check below keeps that to the GM alone, so a
    -- mis-seeded row can never silently become cross-brand.
    brand_id      uuid        REFERENCES brand (id),
    team_id       uuid,
    role          text        NOT NULL,
    email         text        NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    display_name  text        NOT NULL,
    reports_to    uuid        REFERENCES team_member (id),
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT team_member_role_valid CHECK (role IN (
        'GM', 'BRAND_MANAGER', 'PROJECT_MANAGER',
        'PROJECT_COORDINATOR', 'CASE_MANAGER', 'EXPERT_NETWORK_MANAGER'
    )),
    CONSTRAINT team_member_brand_required CHECK (role = 'GM' OR brand_id IS NOT NULL)
);

CREATE INDEX idx_team_member_brand_role ON team_member (brand_id, role);
-- (email is already indexed by its UNIQUE constraint)
