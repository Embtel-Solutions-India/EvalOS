-- The GM's monthly sales target, set from the dashboard instead of the SALES_MONTHLY_GOAL env var.
--
-- Append-only: a change is a new row, the newest row for (brand, month) is the target, and the
-- rows before it are the history of who moved the goal and when. SALES_MONTHLY_GOAL stays as the
-- fallback for a month nobody has set.
CREATE TABLE sales_monthly_goal (
    id        uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id  uuid          NOT NULL REFERENCES brand (id),
    month     date          NOT NULL CHECK (extract(day FROM month) = 1),
    amount    numeric(12,2) NOT NULL CHECK (amount >= 0),
    set_by    uuid          NOT NULL REFERENCES team_member (id),
    set_at    timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX sales_monthly_goal_latest ON sales_monthly_goal (brand_id, month, set_at DESC);
