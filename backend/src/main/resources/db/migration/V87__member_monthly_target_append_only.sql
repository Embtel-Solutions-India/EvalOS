-- member_monthly_target is append-only (D75): a change is a new row, the newest counts, and the
-- older rows are who moved the target and when. V86 said so by convention; this makes the
-- database refuse an UPDATE or DELETE, so a stray statement cannot rewrite that history.
CREATE FUNCTION member_monthly_target_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'member_monthly_target is append-only: add a new row instead of changing or deleting one';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER member_monthly_target_no_change
    BEFORE UPDATE OR DELETE ON member_monthly_target
    FOR EACH ROW EXECUTE FUNCTION member_monthly_target_append_only();
