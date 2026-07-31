-- Unit 12: the record that makes an expert's acceptance rate computable at all.
--
-- Before this table the answer did not exist anywhere queryable. `expert.performance_flags`
-- carries a DECLINED_CASES marker, which is a flag and not a rate. `evalos_case.expert_id`
-- is overwritten by reassignExpert, so the case row does not remember who declined it. The
-- decline *is* in the audit trail, inside a `before_snapshot` jsonb blob — derivable in
-- principle, and a query no scorer should be built on. So the fact gets its own row, whose
-- whole purpose is to be aggregated.
--
-- **This is not the audit table and does not pretend to be.** The trail already records each
-- transition and stays the history; these rows exist so `ACCEPTED / (ACCEPTED + DECLINED +
-- TIMED_OUT)` is one indexed aggregate instead of a jsonb scan.
--
-- Append-only in spirit, one mutable field in fact: `outcome` moves off OFFERED exactly once
-- (with `outcome_at` and, for a decline, the reason), and everything else is mapped
-- updatable = false on the entity. First write wins — Unit 15 has two acts that both mean
-- accepted, and on the ordinary happy path both fire.
CREATE TABLE expert_case_offer (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id       uuid        NOT NULL REFERENCES brand (id),
    case_id        uuid        NOT NULL REFERENCES evalos_case (id),
    expert_id      uuid        NOT NULL REFERENCES expert (id),
    offered_at     timestamptz NOT NULL,
    -- Values from the OfferOutcome enum. TIMED_OUT is declared here and written by nobody
    -- until Unit 15's EXPERT_TIMED_OUT transition — a staff act prompted by Unit 19's 24h
    -- timer, never fired by it, because reaching TIMED_OUT also opens a rematch and
    -- REASSIGN_EXPERT is gated on an exception state only a declared transition can set.
    outcome        text        NOT NULL,
    outcome_at     timestamptz,
    decline_reason text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- Closed the same two ways as V18's taxonomy: the enum stops a caller, the CHECK stops a
    -- seed script or a hand-written UPDATE. The scorer divides by a count of these values, so
    -- one unrecognised spelling would silently drop out of both the numerator and the
    -- denominator.
    CONSTRAINT expert_case_offer_outcome_known CHECK (outcome IN (
        'OFFERED', 'ACCEPTED', 'DECLINED', 'TIMED_OUT', 'SUPERSEDED'
    )),
    -- An open offer has no outcome timestamp and a resolved one has to have it: those are the
    -- same fact stated twice, and letting them disagree is how a row that reads OFFERED
    -- forever ends up with an outcome nobody can date.
    CONSTRAINT expert_case_offer_outcome_dated CHECK ((outcome = 'OFFERED') = (outcome_at IS NULL))
);

-- The aggregate this unit runs, and the only read of this table today: for the experts on one
-- brand's roster, how many offers each resolved which way.
CREATE INDEX idx_expert_case_offer_acceptance ON expert_case_offer (brand_id, expert_id, outcome);

-- The lookup the three writing transitions do: "the still-open offer on this case", to stamp
-- it DECLINED / ACCEPTED, or SUPERSEDED when a rematch replaces it.
CREATE INDEX idx_expert_case_offer_open ON expert_case_offer (case_id) WHERE outcome = 'OFFERED';
