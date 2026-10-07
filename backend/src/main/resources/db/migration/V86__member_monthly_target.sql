-- A monthly target per sales or marketing member, set by the GM (D75, Unit 77).
--
-- Append-only, shaped like sales_monthly_goal (V77): a change is a new row, the newest row for
-- (member, month) is the target, older rows are who moved it and when. A month with no row is
-- "not set", which is not the same as a target of 0.
CREATE TABLE member_monthly_target (
    id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id       uuid          NOT NULL REFERENCES brand (id),
    team_member_id uuid          NOT NULL REFERENCES team_member (id),
    month          date          NOT NULL CHECK (extract(day FROM month) = 1),
    kind           text          NOT NULL CHECK (kind IN ('WON_VALUE', 'LEADS')),
    amount         numeric(12,2) NOT NULL CHECK (amount >= 0),
    set_by         uuid          NOT NULL REFERENCES team_member (id),
    set_at         timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX member_monthly_target_latest
    ON member_monthly_target (brand_id, team_member_id, month, set_at DESC);
