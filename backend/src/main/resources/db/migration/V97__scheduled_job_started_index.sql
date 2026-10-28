-- The Background jobs chart counts runs per day over the last week across every sweep (2026-10-28).
-- The only index led with `job_type`, so a range on `started_at` alone scanned the whole ledger, which
-- grows by a few thousand rows a day and is never pruned.
CREATE INDEX idx_scheduled_job_started ON scheduled_job (started_at);
